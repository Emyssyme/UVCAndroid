#include "uvc_custom_network.h"
#include "net_discovery.h"

#include <obs-module.h>
#include <util/platform.h>
#include <util/dstr.h>
#include <util/threading.h>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "avcodec.lib")
#pragma comment(lib, "avutil.lib")
#define INVALID_SOCKET_VAL INVALID_SOCKET
#else
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <unistd.h>
#define INVALID_SOCKET_VAL (-1)
#endif

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
}

#include <stdlib.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>

static const char *RESOLUTIONS[] = {"640x360", "1280x720", "1920x1080", "3840x2160"};
static const uint32_t RESOLUTION_VALUES[][2] = {{640, 360}, {1280, 720}, {1920, 1080}, {3840, 2160}};
static const int CUSTOM_DISCOVERY_PORT = 8866;

/* H.265-over-TCP frame header constants (same as DroidCam protocol) */
static const uint64_t H264_NO_PTS   = 0xFFFFFFFFFFFFFFFFULL; /* config/SPS+PPS marker */
static const uint32_t H264_MAX_SIZE = 16 * 1024 * 1024;       /* safety cap: 16 MiB    */

/* Helper: read exactly `len` bytes from a TCP socket */
static bool tcp_recv_all(
#ifdef _WIN32
    SOCKET sock,
#else
    int sock,
#endif
    void *buf, size_t len)
{
    uint8_t *ptr = (uint8_t *)buf;
    size_t received = 0;
    while (received < len) {
#ifdef _WIN32
        int n = recv(sock, (char *)(ptr + received), (int)(len - received), 0);
#else
        ssize_t n = recv(sock, ptr + received, len - received, 0);
#endif
        if (n <= 0) return false;
        received += (size_t)n;
    }
    return true;
}

static inline uint64_t read_u64be(const uint8_t *b)
{
    return ((uint64_t)b[0] << 56) | ((uint64_t)b[1] << 48) |
           ((uint64_t)b[2] << 40) | ((uint64_t)b[3] << 32) |
           ((uint64_t)b[4] << 24) | ((uint64_t)b[5] << 16) |
           ((uint64_t)b[6] <<  8) |  (uint64_t)b[7];
}
static inline uint32_t read_u32be(const uint8_t *b)
{
    return ((uint32_t)b[0] << 24) | ((uint32_t)b[1] << 16) |
           ((uint32_t)b[2] <<  8) |  (uint32_t)b[3];
}

static void uvc_custom_network_start_receiver(uvc_custom_network *context);
static void uvc_custom_network_receiver_stop(uvc_custom_network *context);
static void uvc_custom_network_set_status(uvc_custom_network *context, const char *format, ...);
static bool uvc_custom_network_activate_button(obs_properties_t *props, obs_property_t *property, void *data);
static bool uvc_custom_network_disconnect_button(obs_properties_t *props, obs_property_t *property, void *data);
static bool uvc_custom_network_refresh_button(obs_properties_t *props, obs_property_t *property, void *data);
static void uvc_custom_network_discovery_callback(const char *host, int port, void *userdata);
static void *uvc_custom_network_receiver_thread(void *data);

static void uvc_custom_network_clear_discovered_devices(uvc_custom_network *context)
{
    if (!context) {
        return;
    }

    for (int i = 0; i < context->discovered_device_count; i++) {
        bfree(context->discovered_devices[i].host);
        bfree(context->discovered_devices[i].label);
    }

    context->discovered_device_count = 0;
    context->selected_device_index = -1;
}

static bool uvc_custom_network_add_discovered_device(uvc_custom_network *context,
                                                    const char *host, int port)
{
    if (!context || !host || port <= 0) {
        return false;
    }

    for (int i = 0; i < context->discovered_device_count; i++) {
        if (strcmp(context->discovered_devices[i].host, host) == 0 &&
            context->discovered_devices[i].port == port) {
            return false;
        }
    }

    if (context->discovered_device_count >= MAX_DISCOVERED_DEVICES) {
        return false;
    }

    int index = context->discovered_device_count++;
    context->discovered_devices[index].host = bstrdup(host);
    context->discovered_devices[index].port = port;

    char label[128];
    snprintf(label, sizeof(label), "%s:%d", host, port);
    context->discovered_devices[index].label = bstrdup(label);

    if (context->selected_device_index < 0) {
        context->selected_device_index = 0;
    }

    return true;
}

static bool uvc_custom_network_activate_button(obs_properties_t *props, obs_property_t *property,
                                               void *data)
{
    UNUSED_PARAMETER(property);
    uvc_custom_network *context = (uvc_custom_network *)data;
    if (!context) {
        return false;
    }

    if (context->receiver_running) {
        /* Connect button is idempotent: do not apply settings/restart when already running. */
        uvc_custom_network_set_status(context, "✓ Already connected to %s:%d", context->host ? context->host : "", context->port);
        blog(LOG_INFO, "UVC H265 TCP: connect requested while already running (%s:%d)",
             context->host ? context->host : "", context->port);
        return true;
    }

    if (context->source) {
        obs_data_t *settings = obs_source_get_settings(context->source);
        if (settings) {
            obs_properties_apply_settings(props, settings);
            obs_source_update(context->source, settings);
            obs_data_release(settings);
        }
    }

    bool has_target = (context->port > 0) && (context->host && context->host[0] != '\0');

    if (!has_target) {
        uvc_custom_network_set_status(context, "⚠ Set Phone IP and Stream Port first");
        blog(LOG_WARNING, "UVC H265 TCP: activation attempted without phone IP/port configured");
        return true;
    }

    uvc_custom_network_start_receiver(context);
    uvc_custom_network_set_status(context, "↻ Connecting to %s:%d...", context->host, context->port);
    blog(LOG_INFO, "UVC H265 TCP: manual activation — connecting to %s:%d", context->host, context->port);
    return true;
}

static bool uvc_custom_network_disconnect_button(obs_properties_t *props, obs_property_t *property,
                                                 void *data)
{
    UNUSED_PARAMETER(props);
    UNUSED_PARAMETER(property);

    uvc_custom_network *context = (uvc_custom_network *)data;
    if (!context) {
        return false;
    }

    if (!context->receiver_running) {
        uvc_custom_network_set_status(context, "ℹ Already disconnected");
        return true;
    }

    uvc_custom_network_receiver_stop(context);
    uvc_custom_network_set_status(context, "✓ Stream disconnected");
    blog(LOG_INFO, "UVC H265 TCP: stream manually disconnected");
    return true;
}

static bool uvc_custom_network_refresh_button(obs_properties_t *props, obs_property_t *property, void *data)
{
    UNUSED_PARAMETER(props);
    UNUSED_PARAMETER(property);

    uvc_custom_network *context = (uvc_custom_network *)data;
    if (!context) {
        return false;
    }

    network_discovery_t *old_discovery = NULL;

    pthread_mutex_lock(&context->lock);
    if (context->discovery) {
        old_discovery = context->discovery;
        context->discovery = NULL;
    }
    pthread_mutex_unlock(&context->lock);

    if (old_discovery) {
        network_discovery_destroy(old_discovery);
    }

    pthread_mutex_lock(&context->lock);
    uvc_custom_network_clear_discovered_devices(context);

    if (old_discovery) {
        context->discovery = network_discovery_create("uvc_custom_network", CUSTOM_DISCOVERY_PORT,
                                                    uvc_custom_network_discovery_callback, context);
        network_discovery_start(context->discovery);
        uvc_custom_network_set_status(context, "Discovery refreshed, waiting for Android beacons...");
    } else {
        uvc_custom_network_set_status(context, "Discovery is disabled. Enable Auto-Discovery first.");
    }
    pthread_mutex_unlock(&context->lock);

    return true;
}

static void uvc_custom_network_set_status(uvc_custom_network *context, const char *format, ...)
{
    if (!context || !format) {
        return;
    }

    char temp[256];
    va_list args;
    va_start(args, format);
    vsnprintf(temp, sizeof(temp), format, args);
    va_end(args);

    bfree(context->discovery_status);
    context->discovery_status = bstrdup(temp);
}

static void uvc_custom_network_receiver_stop(uvc_custom_network *context)
{
    if (!context || !context->receiver_running) {
        return;
    }

    context->receiver_running = false;

    // Close the socket first — this immediately unblocks recvfrom() in the thread
    // instead of waiting up to 500 ms for the timeout to fire.
#ifdef _WIN32
    if (context->receiver_socket != INVALID_SOCKET) {
        closesocket(context->receiver_socket);
        context->receiver_socket = INVALID_SOCKET;
    }
#else
    if (context->receiver_socket >= 0) {
        close(context->receiver_socket);
        context->receiver_socket = -1;
    }
#endif

    pthread_join(context->receiver_thread, NULL);
}

static void *uvc_custom_network_receiver_thread(void *data)
{
    uvc_custom_network *context = (uvc_custom_network *)data;

    pthread_mutex_lock(&context->lock);
    int  port = context->port;
    char *host = bstrdup(context->host ? context->host : "");
    pthread_mutex_unlock(&context->lock);

    if (!host || host[0] == '\0') {
        blog(LOG_WARNING, "UVC H265 TCP: Phone IP is not set — enter it in the source Properties");
        bfree(host);
        context->receiver_running = false;
        return NULL;
    }

    /* ----- initialise avcodec H.265 decoder ----- */
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_HEVC);
    if (!codec) {
        blog(LOG_ERROR, "UVC H265 TCP: H.265 (HEVC) decoder not found — ensure FFmpeg is installed with H.265 support");
        uvc_custom_network_set_status(context, "❌ H.265 decoder not found");
        bfree(host);
        context->receiver_running = false;
        return NULL;
    }

    AVPacket *pkt   = av_packet_alloc();
    AVFrame  *frame = av_frame_alloc();
    AVCodecContext *decoder = NULL;

    if (!pkt || !frame) {
        blog(LOG_ERROR, "UVC H265 TCP: failed to allocate FFmpeg structures");
        uvc_custom_network_set_status(context, "❌ FFmpeg memory allocation failed");
        av_packet_free(&pkt);
        av_frame_free(&frame);
        bfree(host);
        context->receiver_running = false;
        return NULL;
    }

    blog(LOG_INFO, "UVC H265 TCP: attempting to connect to Android at %s:%d...", host, port);

    /* outer loop: reconnect on disconnect */
    while (context->receiver_running) {
#ifdef _WIN32
        SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (sock == INVALID_SOCKET) {
#else
        int sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (sock < 0) {
#endif
            blog(LOG_WARNING, "UVC H265 TCP: socket() failed");
            for (int i = 0; i < 40 && context->receiver_running; i++) os_sleep_ms(50);
            continue;
        }

        /* large receive buffer helps with 4K bitrates */
        int rcvbuf = 4 * 1024 * 1024;
        setsockopt(sock, SOL_SOCKET, SO_RCVBUF, (char *)&rcvbuf, sizeof(rcvbuf));

        struct sockaddr_in addr;
        memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_port   = htons((uint16_t)port);
        if (inet_pton(AF_INET, host, &addr.sin_addr) <= 0) {
            blog(LOG_WARNING, "UVC H265 TCP: invalid phone IP '%s' — check source settings and ensure it's a valid IPv4 address", host);
            uvc_custom_network_set_status(context, "❌ Invalid phone IP address");
#ifdef _WIN32
            closesocket(sock);
#else
            close(sock);
#endif
            for (int i = 0; i < 40 && context->receiver_running; i++) os_sleep_ms(50);
            continue;
        }

        if (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
            blog(LOG_INFO, "UVC H265 TCP: connect to %s:%d failed — phone may not be running the app or firewall may be blocking (retrying...)", host, port);
            uvc_custom_network_set_status(context, "⚠ Cannot connect to %s:%d — check phone IP/port", host, port);
#ifdef _WIN32
            closesocket(sock);
#else
            close(sock);
#endif
            for (int i = 0; i < 40 && context->receiver_running; i++) os_sleep_ms(50);
            continue;
        }

        /* TCP_NODELAY: disable Nagle for lowest latency */
        int nodelay = 1;
        setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, (char *)&nodelay, sizeof(nodelay));

        context->receiver_socket = sock;
        blog(LOG_INFO, "UVC H265 TCP: ✓ successfully connected to Android at %s:%d — waiting for video stream...", host, port);
        uvc_custom_network_set_status(context, "✓ Connected to %s:%d — waiting for video...", host, port);

        /* fresh H.265 decoder for each connection */
        if (decoder) { avcodec_free_context(&decoder); decoder = NULL; }
        decoder = avcodec_alloc_context3(codec);
        decoder->flags       |= AV_CODEC_FLAG_LOW_DELAY;
        decoder->flags2      |= AV_CODEC_FLAG2_FAST;
        decoder->thread_type  = FF_THREAD_SLICE;
        decoder->thread_count = 4;  /* parallel slice decode for 4K HEVC */
        if (avcodec_open2(decoder, codec, NULL) != 0) {
            blog(LOG_ERROR, "UVC H265 TCP: avcodec_open2 failed");
#ifdef _WIN32
            closesocket(sock);
            context->receiver_socket = INVALID_SOCKET;
#else
            close(sock);
            context->receiver_socket = -1;
#endif
            for (int i = 0; i < 40 && context->receiver_running; i++) os_sleep_ms(50);
            continue;
        }

        bool logged_first = false;
        uint64_t pts_epoch_ns = 0;   /* anchor: OBS time when first PTS arrived */
        int64_t  pts_epoch_us = 0;   /* anchor: first PTS value (microseconds) */

        /* inner receive/decode loop */
        while (context->receiver_running) {
            uint8_t header[12];
            if (!tcp_recv_all(sock, header, 12)) {
                if (context->receiver_running)
                    blog(LOG_INFO, "UVC H265 TCP: connection lost — will reconnect");
                break;
            }

            uint64_t pts = read_u64be(header);
            uint32_t len = read_u32be(header + 8);

            if (len == 0 || len > H264_MAX_SIZE) {
                blog(LOG_WARNING, "UVC H265 TCP: bad packet length %u, reconnecting", len);
                break;
            }

            if (av_new_packet(pkt, (int)len + AV_INPUT_BUFFER_PADDING_SIZE) < 0) {
                blog(LOG_WARNING, "UVC H265 TCP: av_new_packet(%u) OOM", len);
                break;
            }
            pkt->size = (int)len;
            memset(pkt->data + len, 0, AV_INPUT_BUFFER_PADDING_SIZE);

            if (!tcp_recv_all(sock, pkt->data, len)) {
                av_packet_unref(pkt);
                if (context->receiver_running)
                    blog(LOG_INFO, "UVC H265 TCP: connection lost reading payload");
                break;
            }

            pkt->pts = (pts == H264_NO_PTS) ? AV_NOPTS_VALUE : (int64_t)pts;

            /* send to decoder — SPS/PPS config packet and IDR/P frames treated the same */
            int ret = avcodec_send_packet(decoder, pkt);
            av_packet_unref(pkt);
            if (ret < 0 && ret != AVERROR(EAGAIN)) {
                /* non-fatal: e.g. repeated SPS/PPS returns AVERROR_INVALIDDATA */
                continue;
            }

            /* retrieve all decoded output frames */
            while (avcodec_receive_frame(decoder, frame) == 0) {
                struct obs_source_frame obs_frame;
                memset(&obs_frame, 0, sizeof(obs_frame));
                obs_frame.width     = (uint32_t)frame->width;
                obs_frame.height    = (uint32_t)frame->height;

                /* Use PTS from Android to pace frames evenly in OBS.
                 * Android sends PTS in microseconds (System.nanoTime()/1000).
                 * We anchor the first PTS to the current OBS clock, then
                 * derive all subsequent timestamps from the PTS delta.
                 * This smooths out TCP delivery jitter. */
                if (frame->pts != AV_NOPTS_VALUE && frame->pts > 0) {
                    if (pts_epoch_us == 0) {
                        pts_epoch_us = frame->pts;
                        pts_epoch_ns = os_gettime_ns();
                    }
                    int64_t delta_us = frame->pts - pts_epoch_us;
                    obs_frame.timestamp = pts_epoch_ns + (uint64_t)(delta_us * 1000);
                } else {
                    obs_frame.timestamp = os_gettime_ns();
                }
                obs_frame.trc       = VIDEO_TRC_DEFAULT;

                bool format_ok = true;
                switch (frame->format) {
                case AV_PIX_FMT_YUV420P:
                case AV_PIX_FMT_YUVJ420P:
                    obs_frame.format      = VIDEO_FORMAT_I420;
                    obs_frame.data[0]     = frame->data[0];
                    obs_frame.data[1]     = frame->data[1];
                    obs_frame.data[2]     = frame->data[2];
                    obs_frame.linesize[0] = (uint32_t)frame->linesize[0];
                    obs_frame.linesize[1] = (uint32_t)frame->linesize[1];
                    obs_frame.linesize[2] = (uint32_t)frame->linesize[2];
                    break;
                case AV_PIX_FMT_NV12:
                    obs_frame.format      = VIDEO_FORMAT_NV12;
                    obs_frame.data[0]     = frame->data[0];
                    obs_frame.data[1]     = frame->data[1];
                    obs_frame.linesize[0] = (uint32_t)frame->linesize[0];
                    obs_frame.linesize[1] = (uint32_t)frame->linesize[1];
                    break;
                default:
                    blog(LOG_WARNING, "UVC H265 TCP: unsupported pixel format %d", frame->format);
                    format_ok = false;
                    break;
                }

                if (format_ok) {
                    enum video_range_type range =
                        (frame->color_range == AVCOL_RANGE_JPEG)
                            ? VIDEO_RANGE_FULL : VIDEO_RANGE_PARTIAL;
                    obs_frame.full_range = (range == VIDEO_RANGE_FULL);
                    video_format_get_parameters_for_format(
                        VIDEO_CS_DEFAULT, range, obs_frame.format,
                        obs_frame.color_matrix,
                        obs_frame.color_range_min, obs_frame.color_range_max);

                    pthread_mutex_lock(&context->lock);
                    context->width  = (uint32_t)frame->width;
                    context->height = (uint32_t)frame->height;
                    pthread_mutex_unlock(&context->lock);

                    obs_source_output_video(context->source, &obs_frame);

                    if (!logged_first) {
                        blog(LOG_INFO, "UVC H265 TCP: first decoded frame %dx%d pixfmt=%d",
                             frame->width, frame->height, frame->format);
                        logged_first = true;
                    }
                }
                av_frame_unref(frame);
            }
        } /* inner loop */

        /* close the socket — guard against double-close with receiver_stop */
#ifdef _WIN32
        if (context->receiver_socket != INVALID_SOCKET) {
            closesocket(sock);
            context->receiver_socket = INVALID_SOCKET;
        }
#else
        if (context->receiver_socket >= 0) {
            close(sock);
            context->receiver_socket = -1;
        }
#endif

        if (context->receiver_running) {
            /* natural disconnect: short wait before reconnect */
            for (int i = 0; i < 40 && context->receiver_running; i++) os_sleep_ms(50);
        }
    } /* outer reconnect loop */

    if (decoder) avcodec_free_context(&decoder);
    av_packet_free(&pkt);
    av_frame_free(&frame);
    bfree(host);
    blog(LOG_INFO, "UVC H265 TCP: receiver thread exiting");
    return NULL;
}

static void uvc_custom_network_discovery_callback(const char *host, int port, void *userdata)
{
    uvc_custom_network *context = (uvc_custom_network *)userdata;
    if (!context || !host || port <= 0) {
        return;
    }

    bool should_sync_settings = false;
    int selected_index_for_sync = -1;
    char *host_for_sync = NULL;
    int port_for_sync = 0;

    pthread_mutex_lock(&context->lock);
    bool added = uvc_custom_network_add_discovered_device(context, host, port);
    int count = context->discovered_device_count;

    if (!context->host || *context->host == '\0') {
        bfree(context->host);
        context->host = bstrdup(host);
        context->port = port;
        if (context->selected_device_index < 0) {
            context->selected_device_index = 0;
        }
    }

    if (added) {
        blog(LOG_INFO, "UVC Custom Network: discovered device %s:%d", host, port);
        if (count == 1) {
            uvc_custom_network_set_status(context, "Found 1 device: %s:%d (selected automatically)", host, port);
        } else {
            uvc_custom_network_set_status(context, "Found %d devices, latest: %s:%d", count, host, port);
        }

        if (context->source && context->selected_device_index >= 0
                && context->selected_device_index < context->discovered_device_count) {
            should_sync_settings = true;
            selected_index_for_sync = context->selected_device_index;
            host_for_sync = bstrdup(context->host ? context->host : "");
            port_for_sync = context->port;
        }

        if (!context->receiver_running && context->port > 0 && context->host && context->host[0] != '\0') {
            blog(LOG_INFO, "UVC H265 TCP: auto-starting receiver for discovered device %s:%d", host, port);
            uvc_custom_network_start_receiver(context);
        }
    }

    pthread_mutex_unlock(&context->lock);

    /* Keep OBS source settings in sync so each source remembers its selected target. */
    if (should_sync_settings && context->source) {
        obs_data_t *settings = obs_source_get_settings(context->source);
        if (settings) {
            obs_data_set_string(settings, "host", host_for_sync ? host_for_sync : "");
            obs_data_set_int(settings, "port", port_for_sync);
            obs_data_set_int(settings, "selected_device_index", selected_index_for_sync);
            obs_source_update(context->source, settings);
            obs_data_release(settings);
        }
    }
    bfree(host_for_sync);
}

static void uvc_custom_network_start_receiver(uvc_custom_network *context)
{
    if (!context || context->receiver_running || context->port <= 0) {
        return;
    }

    blog(LOG_INFO, "UVC H265 TCP: starting receiver thread (will connect to %s:%d)",
         context->host ? context->host : "(none)", context->port);
    context->receiver_running = true;
#ifdef _WIN32
    context->receiver_socket = INVALID_SOCKET;
#else
    context->receiver_socket = -1;
#endif
    pthread_create(&context->receiver_thread, NULL, uvc_custom_network_receiver_thread, context);
}

static void *uvc_custom_network_create(obs_data_t *settings, obs_source_t *source)
{
    uvc_custom_network *context = (uvc_custom_network *)bzalloc(sizeof(uvc_custom_network));
    context->source = source;
    pthread_mutex_init(&context->lock, NULL);
    context->host = bstrdup(obs_data_get_string(settings, "host"));
    context->port = (int)obs_data_get_int(settings, "port");
    context->selected_device_index = (int)obs_data_get_int(settings, "selected_device_index");
    context->discovered_device_count = 0;
    context->resolution_index = (int)obs_data_get_int(settings, "resolution_index");
    context->fps = (int)obs_data_get_int(settings, "fps");
    context->quality = (int)obs_data_get_int(settings, "quality");
    context->discovery_enabled = obs_data_get_bool(settings, "discovery_enabled");
    context->discovery = NULL;
#ifdef _WIN32
    context->receiver_socket = INVALID_SOCKET;
#else
    context->receiver_socket = -1;
#endif
    context->receiver_running = false;
    context->width = 0;
    context->height = 0;
    context->discovery_status = bstrdup("Idle");

    if (context->discovery_enabled) {
        context->discovery = network_discovery_create("uvc_custom_network", CUSTOM_DISCOVERY_PORT,
                                                    uvc_custom_network_discovery_callback, context);
        network_discovery_start(context->discovery);
        uvc_custom_network_set_status(context, "🔍 Discovery listening on port 8866...");
    } else {
        uvc_custom_network_set_status(context, "ℹ Discovery disabled (enable in Properties)");
    }

    // Auto-start receiver if phone IP and port are already configured
    if (context->port > 0 && context->host && context->host[0] != '\0') {
        blog(LOG_INFO, "UVC H265 TCP: auto-starting receiver (connecting to %s:%d)",
             context->host, context->port);
        uvc_custom_network_start_receiver(context);
        uvc_custom_network_set_status(context, "↻ Connecting to %s:%d...", context->host, context->port);
    }

    return context;
}

static void uvc_custom_network_destroy(void *data)
{
    uvc_custom_network *context = (uvc_custom_network *)data;
    if (!context) return;

    if (context->discovery) {
        network_discovery_destroy(context->discovery);
    }
    uvc_custom_network_receiver_stop(context);

    uvc_custom_network_clear_discovered_devices(context);
    bfree(context->discovery_status);
    bfree(context->host);
    pthread_mutex_destroy(&context->lock);
    bfree(context);
}

static obs_properties_t *uvc_custom_network_properties(void *data)
{
    uvc_custom_network *context = (uvc_custom_network *)data;
    obs_properties_t *props = obs_properties_create();
    obs_property_t *p;

    // Discovery section
    p = obs_properties_add_bool(props, "discovery_enabled", "Enable Auto-Discovery");
    obs_property_set_long_description(p, 
        "When enabled, OBS will automatically discover your Android device on the network. "
        "Ensure 'Enable Network Discovery' is also enabled in the Android app settings.");
    UNUSED_PARAMETER(p);

    p = obs_properties_add_text(props, "discovery_status", "Discovery Status", OBS_TEXT_INFO);
    obs_property_set_long_description(p, "Shows the current auto-discovery status and any devices found.");
    UNUSED_PARAMETER(p);

    p = obs_properties_add_button(props, "refresh_discovery", "🔄 Refresh Discovery", 
                                  (obs_property_clicked_t)uvc_custom_network_refresh_button);
    obs_property_set_long_description(p, "Manually scan for Android devices broadcasting on the network.");
    UNUSED_PARAMETER(p);

    // Device selection
    p = obs_properties_add_list(props, "selected_device_index", "Discovered Device", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    if (p) {
        if (context->discovered_device_count == 0) {
            obs_property_list_add_int(p, "(Enable Discovery and click Refresh)", -1);
            obs_property_set_long_description(p,
                "No discovered devices yet. Enable discovery in Android app and click Refresh Discovery.");
        } else {
            for (int i = 0; i < context->discovered_device_count; i++) {
                obs_property_list_add_int(p, context->discovered_devices[i].label, i);
            }
            obs_property_set_long_description(p,
                "Select a discovered device to auto-fill IP/port and reconnect.");
        }
    }

    // Manual connection (advanced)
    obs_properties_add_text(props, "host", "📱 Phone IP Address", OBS_TEXT_DEFAULT);
    obs_properties_add_int(props, "port", "Stream Port", 1024, 65535, 1);

    // Connection control
    p = obs_properties_add_button(props, "activate", "📡 Connect", 
                                  (obs_property_clicked_t)uvc_custom_network_activate_button);
    obs_property_set_long_description(p, 
        "Manually connect to the Android device. "
        "This button will not disconnect if already connected.");
    UNUSED_PARAMETER(p);

    p = obs_properties_add_button(props, "disconnect", "⏹ Disconnect",
                                  (obs_property_clicked_t)uvc_custom_network_disconnect_button);
    obs_property_set_long_description(p,
        "Manually stop and disconnect the current stream connection.");
    UNUSED_PARAMETER(p);

    // Stream settings
    p = obs_properties_add_list(props, "resolution_index", "Expected Resolution", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    for (int i = 0; i < (int)(sizeof(RESOLUTIONS) / sizeof(RESOLUTIONS[0])); i++) {
        obs_property_list_add_int(p, RESOLUTIONS[i], i);
    }
    obs_property_set_long_description(p, 
        "Select the expected video resolution from the Android device. "
        "This is used before the first frame arrives. "
        "The source will auto-adjust when actual frames are received.");
    UNUSED_PARAMETER(p);

    p = obs_properties_add_int(props, "fps", "Expected FPS", 15, 60, 1);
    obs_property_set_long_description(p, 
        "Expected frame rate from the Android device (informational only). "
        "Actual FPS is determined by the Android device settings.");
    UNUSED_PARAMETER(p);

    p = obs_properties_add_int(props, "quality", "Encoder Quality (1-100)", 1, 100, 1);
    obs_property_set_long_description(p, 
        "Quality hint for the Android encoder (0-100). Higher = better quality but larger file size. "
        "This is a guide; actual bitrate is adaptive based on network conditions.");
    UNUSED_PARAMETER(p);

    return props;
}

static void uvc_custom_network_update(void *data, obs_data_t *settings)
{
    uvc_custom_network *context = (uvc_custom_network *)data;
    network_discovery_t *old_discovery = NULL;
    bool need_discovery_start = false;
    bool need_receiver_restart = false;
    bool need_receiver_start = false;
    bool need_receiver_stop = false;

    pthread_mutex_lock(&context->lock);

    const char *new_host = obs_data_get_string(settings, "host");
    bool host_changed = false;
    if (!new_host) {
        new_host = "";
    }
    if (!context->host || strcmp(context->host, new_host) != 0) {
        bfree(context->host);
        context->host = bstrdup(new_host);
        host_changed = true;
    }

    int new_port = (int)obs_data_get_int(settings, "port");
    bool port_changed = new_port != context->port;
    context->port = new_port;

    int selected_device_index = (int)obs_data_get_int(settings, "selected_device_index");
    if (selected_device_index != context->selected_device_index) {
        context->selected_device_index = selected_device_index;
        if (selected_device_index >= 0 && selected_device_index < context->discovered_device_count) {
            bfree(context->host);
            context->host = bstrdup(context->discovered_devices[selected_device_index].host);
            if (context->port != context->discovered_devices[selected_device_index].port) {
                port_changed = true;
            }
            context->port = context->discovered_devices[selected_device_index].port;
            host_changed = true;
        }
    }

    context->resolution_index = (int)obs_data_get_int(settings, "resolution_index");
    context->fps = (int)obs_data_get_int(settings, "fps");
    context->quality = (int)obs_data_get_int(settings, "quality");
    bool discovery_enabled = obs_data_get_bool(settings, "discovery_enabled");

    if (discovery_enabled && !context->discovery) {
        need_discovery_start = true;
    } else if (!discovery_enabled && context->discovery) {
        old_discovery = context->discovery;
        context->discovery = NULL;
    }

    bool has_target = context->port > 0 && context->host && context->host[0] != '\0';

    if (port_changed || host_changed) {
        if (context->receiver_running) {
            need_receiver_restart = true;
        } else if (has_target) {
            /* Reconnect even if a previous thread exited after a bad IP/port. */
            need_receiver_start = true;
        }
    }

    if (!has_target && context->receiver_running) {
        need_receiver_stop = true;
    }

    pthread_mutex_unlock(&context->lock);

    // Do blocking operations OUTSIDE the lock to avoid deadlock with callback threads
    if (old_discovery) {
        network_discovery_destroy(old_discovery);
    }

    if (need_receiver_stop) {
        uvc_custom_network_receiver_stop(context);
        pthread_mutex_lock(&context->lock);
        uvc_custom_network_set_status(context, "Stream stopped (missing host/port)");
        pthread_mutex_unlock(&context->lock);
    }

    if (need_receiver_restart) {
        uvc_custom_network_receiver_stop(context);
        pthread_mutex_lock(&context->lock);
        if (context->port > 0 && context->host && context->host[0] != '\0') {
            uvc_custom_network_start_receiver(context);
            uvc_custom_network_set_status(context, "Reconnecting to %s:%d...", context->host, context->port);
        }
        pthread_mutex_unlock(&context->lock);
    }

    if (need_receiver_start) {
        pthread_mutex_lock(&context->lock);
        if (!context->receiver_running && context->port > 0 && context->host && context->host[0] != '\0') {
            uvc_custom_network_start_receiver(context);
            uvc_custom_network_set_status(context, "Connecting to %s:%d...", context->host, context->port);
        }
        pthread_mutex_unlock(&context->lock);
    }

    if (need_discovery_start) {
        pthread_mutex_lock(&context->lock);
        context->discovery = network_discovery_create("uvc_custom_network", CUSTOM_DISCOVERY_PORT,
                                                    uvc_custom_network_discovery_callback, context);
        network_discovery_start(context->discovery);
        uvc_custom_network_set_status(context, "Discovery enabled, waiting for Android beacons...");
        pthread_mutex_unlock(&context->lock);
    }

    UNUSED_PARAMETER(host_changed);
}

static const char *uvc_custom_network_get_name(void *unused)
{
    UNUSED_PARAMETER(unused);
    return "UVC Custom Network Source";
}

static uint32_t uvc_custom_network_get_width(void *data)
{
    uvc_custom_network *context = (uvc_custom_network *)data;
    if (context && context->width > 0) {
        return context->width;
    }
    int index = context ? context->resolution_index : 1;
    if (index < 0 || index >= (int)(sizeof(RESOLUTION_VALUES) / sizeof(RESOLUTION_VALUES[0]))) {
        index = 1;
    }
    return RESOLUTION_VALUES[index][0];
}

static uint32_t uvc_custom_network_get_height(void *data)
{
    uvc_custom_network *context = (uvc_custom_network *)data;
    if (context && context->height > 0) {
        return context->height;
    }
    int index = context ? context->resolution_index : 1;
    if (index < 0 || index >= (int)(sizeof(RESOLUTION_VALUES) / sizeof(RESOLUTION_VALUES[0]))) {
        index = 1;
    }
    return RESOLUTION_VALUES[index][1];
}

static void uvc_custom_network_defaults(obs_data_t *settings)
{
    obs_data_set_default_bool(settings, "discovery_enabled", true);
    obs_data_set_default_int(settings, "port", 5600);
    obs_data_set_default_int(settings, "fps", 30);
    obs_data_set_default_int(settings, "quality", 50);
    obs_data_set_default_int(settings, "resolution_index", 1);
    obs_data_set_default_int(settings, "selected_device_index", -1);
}

static obs_source_info uvc_custom_network_info;

extern "C" bool obs_module_load(void)
{
    memset(&uvc_custom_network_info, 0, sizeof(uvc_custom_network_info));
    uvc_custom_network_info.id = "uvc_custom_network_source";
    uvc_custom_network_info.type = OBS_SOURCE_TYPE_INPUT;
    uvc_custom_network_info.output_flags = OBS_SOURCE_ASYNC_VIDEO;
    uvc_custom_network_info.get_name = uvc_custom_network_get_name;
    uvc_custom_network_info.create = uvc_custom_network_create;
    uvc_custom_network_info.destroy = uvc_custom_network_destroy;
    uvc_custom_network_info.get_defaults = uvc_custom_network_defaults;
    uvc_custom_network_info.get_properties = uvc_custom_network_properties;
    uvc_custom_network_info.update = uvc_custom_network_update;

    obs_register_source(&uvc_custom_network_info);
    blog(LOG_INFO, "Loaded UVC Custom Network OBS plugin");
    return true;
}

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("uvc-custom-network", "en-US")
