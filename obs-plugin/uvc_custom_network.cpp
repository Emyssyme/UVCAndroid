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
#include <fcntl.h>
#include <errno.h>
#define INVALID_SOCKET_VAL (-1)
#endif

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libavutil/hwcontext.h>
}

#include <stdlib.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>

static const char *RESOLUTIONS[] = {"640x360", "1280x720", "1920x1080", "2560x1440", "3840x2160"};
static const uint32_t RESOLUTION_VALUES[][2] = {{640, 360}, {1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};
static const int CUSTOM_DISCOVERY_PORT = 8866;
static const int CUSTOM_TALLY_PORT = 8867;
static const int SRT_DEFAULT_PORT = 5601;

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

static bool parse_message_int(const char *msg, const char *key, int *value)
{
    if (!msg || !key || !value) {
        return false;
    }

    const char *pos = strstr(msg, key);
    if (!pos) {
        return false;
    }

    pos += strlen(key);
    char *end = NULL;
    long parsed = strtol(pos, &end, 10);
    if (end == pos) {
        return false;
    }

    *value = (int)parsed;
    return true;
}

static void uvc_custom_network_start_receiver(uvc_custom_network *context);
static void uvc_custom_network_receiver_stop(uvc_custom_network *context);
static void uvc_custom_network_set_status(uvc_custom_network *context, const char *format, ...);
static bool uvc_custom_network_activate_button(obs_properties_t *props, obs_property_t *property, void *data);
static bool uvc_custom_network_refresh_button(obs_properties_t *props, obs_property_t *property, void *data);
static bool uvc_custom_network_device_selected(obs_properties_t *props, obs_property_t *property, void *data);
static void uvc_custom_network_discovery_callback(const char *host, int port, void *userdata);
static void uvc_custom_network_send_control(uvc_custom_network *context,
                                            bool exposure_lock, bool focus_lock,
                                            int exposure_compensation, int af_mode, bool af_lock,
                                            int flash_mode, int wb_mode, int wb_kelvin,
                                            int resolution_index, int fps, int quality, int bitrate);
static void *uvc_custom_network_receiver_thread(void *data);
static void *uvc_custom_network_control_state_thread(void *data);
static void uvc_custom_network_control_state_start(uvc_custom_network *context);
static void uvc_custom_network_control_state_stop(uvc_custom_network *context);
static void uvc_custom_network_srt_receiver_start(uvc_custom_network *context);
static void uvc_custom_network_srt_receiver_stop(uvc_custom_network *context);
static void *uvc_custom_network_srt_receiver_thread(void *data);
static void uvc_custom_network_apply_remote_control_state(uvc_custom_network *context, const char *msg);
static void uvc_custom_network_video_tick(void *data, float seconds);
static void uvc_custom_network_send_tally(uvc_custom_network *context, bool force_send);

/* ── Hardware-accelerated decoder ─────────────────────────────────────────────
 * Attempts to open an H.265 hardware decoder, falling back to software.
 * Returns the opened AVCodecContext or NULL on failure.
 * The caller is responsible for freeing via avcodec_free_context(). */
static AVCodecContext *try_open_hw_decoder(const char **codec_name_out)
{
    static const char *hw_decoders[] = {
        "hevc_d3d11va",
        "hevc_dxva2",
        "hevc_qsv",
        "hevc_nvdec",
        "hevc_cuvid",
        "hevc_amf",
        "hevc_videotoolbox",
        NULL
    };

    for (int i = 0; hw_decoders[i] != NULL; i++) {
        const AVCodec *hwc = avcodec_find_decoder_by_name(hw_decoders[i]);
        if (!hwc) continue;

        AVCodecContext *ctx = avcodec_alloc_context3(hwc);
        if (!ctx) continue;

        ctx->flags       |= AV_CODEC_FLAG_LOW_DELAY;
        ctx->flags2      |= AV_CODEC_FLAG2_FAST;
        ctx->thread_type  = FF_THREAD_SLICE;
        ctx->thread_count = 4;

        if (avcodec_open2(ctx, hwc, NULL) == 0) {
            blog(LOG_INFO, "UVC HW decode: opened %s", hw_decoders[i]);
            if (codec_name_out) *codec_name_out = hw_decoders[i];
            return ctx;
        }
        avcodec_free_context(&ctx);
    }

    if (codec_name_out) *codec_name_out = NULL;
    return NULL;
}

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

    /* Sync current dialog values into settings so the initial CONTROL
     * sent by the receiver thread has the user's chosen resolution/FPS/
     * quality.  This is separate from the dialog rebuild which is deferred
     * via pending_ui_refresh to avoid tearing down the dialog mid-event. */
    if (context->source) {
        obs_data_t *settings = obs_source_get_settings(context->source);
        if (settings) {
            obs_source_update(context->source, settings);
            obs_data_release(settings);
        }
    }

    bool was_running = context->receiver_running || context->srt_receiver_running;
    bool has_target = (context->host && context->host[0] != '\0')
        && (context->use_srt ? context->srt_port > 0 : context->port > 0);

    if (was_running) {
        uvc_custom_network_receiver_stop(context);
        uvc_custom_network_srt_receiver_stop(context);
        context->user_activated = false;
        bfree(context->source_display_name);
        context->source_display_name = bstrdup("UVC Custom Network (stopped)");
        uvc_custom_network_set_status(context, "⏹ Stopped — press ▶ Activate to reconnect");
        context->pending_ui_refresh = true;
        return true;
    }

    if (!has_target) {
        uvc_custom_network_set_status(context, "Enter Phone IP and port first");
        return true;
    }

    context->user_activated = true;
    if (context->use_srt) {
        uvc_custom_network_srt_receiver_start(context);
        uvc_custom_network_set_status(context, "Connecting SRT %s:%d (tally/control on TCP)",
                                      context->host, context->srt_port);
    } else {
        uvc_custom_network_start_receiver(context);
        uvc_custom_network_set_status(context, "Connecting to %s:%d (H.265 TCP)", context->host, context->port);
    }
    context->pending_ui_refresh = true;
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
        uvc_custom_network_set_status(context, "Discovery refreshed");
    } else {
        uvc_custom_network_set_status(context, "Discovery refresh skipped (disabled)");
    }
    pthread_mutex_unlock(&context->lock);

    return true;
}

/* Called when the user picks a device from the "Discovered devices" dropdown.
 * Returning true tells OBS to refresh the properties dialog, which triggers
 * update() — and update() already contains the logic to autofill Phone IP
 * and Port from the selected device index. */
static bool uvc_custom_network_device_selected(obs_properties_t *props, obs_property_t *property, void *data)
{
    UNUSED_PARAMETER(props);
    UNUSED_PARAMETER(property);
    UNUSED_PARAMETER(data);
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

    /* Write to settings only when called from the main thread (update/
     * properties/button callbacks), not from background threads. */
    if (context->source && !context->destroying
#ifdef _WIN32
        && GetCurrentThreadId() == context->main_thread_id
#endif
        ) {
        obs_data_t *settings = obs_source_get_settings(context->source);
        if (settings) {
            obs_data_set_string(settings, "discovery_status", temp);
            obs_data_release(settings);
        }
    }
}

static void uvc_custom_network_tally_open_socket(uvc_custom_network *context)
{
    if (!context) {
        return;
    }

    /* Close any existing socket first */
#ifdef _WIN32
    if (context->tally_socket != INVALID_SOCKET) {
        closesocket(context->tally_socket);
    }
    context->tally_socket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (context->tally_socket == INVALID_SOCKET) {
#else
    if (context->tally_socket >= 0) {
        close(context->tally_socket);
    }
    context->tally_socket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (context->tally_socket < 0) {
#endif
        context->tally_addr_valid = false;
        blog(LOG_WARNING, "UVC Tally: failed to create UDP socket");
        return;
    }
}

static void uvc_custom_network_tally_update_addr(uvc_custom_network *context)
{
    if (!context || !context->host || context->host[0] == '\0') {
        context->tally_addr_valid = false;
        return;
    }

    /* Open socket if needed */
#ifdef _WIN32
    if (context->tally_socket == INVALID_SOCKET) {
#else
    if (context->tally_socket < 0) {
#endif
        uvc_custom_network_tally_open_socket(context);
    }

    memset(&context->tally_addr, 0, sizeof(context->tally_addr));
    context->tally_addr.sin_family = AF_INET;
    context->tally_addr.sin_port = htons((uint16_t)CUSTOM_TALLY_PORT);

    if (inet_pton(AF_INET, context->host, &context->tally_addr.sin_addr) <= 0) {
        context->tally_addr_valid = false;
        blog(LOG_WARNING, "UVC Tally: cannot resolve %s for tally", context->host);
        return;
    }

    context->tally_addr_valid = true;
}

static void uvc_custom_network_tally_close_socket(uvc_custom_network *context)
{
    if (!context) {
        return;
    }
#ifdef _WIN32
    if (context->tally_socket != INVALID_SOCKET) {
        closesocket(context->tally_socket);
        context->tally_socket = INVALID_SOCKET;
    }
#else
    if (context->tally_socket >= 0) {
        close(context->tally_socket);
        context->tally_socket = -1;
    }
#endif
    context->tally_addr_valid = false;
}

static void uvc_custom_network_send_tally(uvc_custom_network *context, bool force_send)
{
    if (!context || !context->host || context->host[0] == '\0') {
        return;
    }

    /* Self-heal: if the tally address is stale, rebuild it now.
     * This catches cases where the host was temporarily blank during
     * a port-change / reconnect cycle and tally_addr_valid was cleared. */
    if (!context->tally_addr_valid) {
        uvc_custom_network_tally_update_addr(context);
    }
    if (!context->tally_addr_valid) {
        return; /* still invalid — host resolution failed */
    }

#ifdef _WIN32
    if (context->tally_socket == INVALID_SOCKET) {
        uvc_custom_network_tally_open_socket(context);
    }
    if (context->tally_socket == INVALID_SOCKET) {
#else
    if (context->tally_socket < 0) {
        uvc_custom_network_tally_open_socket(context);
    }
    if (context->tally_socket < 0) {
#endif
        return;
    }

    bool active = obs_source_active(context->source);
    bool showing = obs_source_showing(context->source);
    /* "active" means the source is being rendered for output (program).
     * "showing" means the source is visible in the UI (preview or program).
     * A source in preview-only is showing=true, active=false.
     * A source in program (live) is showing=true, active=true. */
    bool program = active;
    bool preview = showing && !active;

    uint64_t now_ns = os_gettime_ns();
    bool changed = (program != context->tally_program) || (preview != context->tally_preview);
    bool stale = (now_ns - context->last_tally_send_ns) >= 1000000000ULL;
    if (!force_send && !changed && !stale) {
        return;
    }

    char payload[96];
    snprintf(payload, sizeof(payload),
             "TALLY;program=%d;preview=%d;srt_port=%d",
             program ? 1 : 0, preview ? 1 : 0, context->srt_port);

    if (changed || force_send) {
        blog(LOG_INFO, "UVC TALLY -> Android (%s:%d): %s",
             context->host ? context->host : "?",
             CUSTOM_TALLY_PORT, payload);
    } else if (stale) {
        /* Periodic heartbeat — log at DEBUG level to confirm tally is alive */
        blog(LOG_DEBUG, "UVC TALLY heartbeat -> %s:%d  program=%d preview=%d",
             context->host ? context->host : "?", CUSTOM_TALLY_PORT,
             program ? 1 : 0, preview ? 1 : 0);
    }

#ifdef _WIN32
    int sent = sendto(context->tally_socket, payload, (int)strlen(payload), 0,
                      (const struct sockaddr *)&context->tally_addr, sizeof(context->tally_addr));
#else
    ssize_t sent = sendto(context->tally_socket, payload, strlen(payload), 0,
                          (const struct sockaddr *)&context->tally_addr, sizeof(context->tally_addr));
#endif
    if (sent < 0) {
        blog(LOG_WARNING, "UVC TALLY: sendto() to %s:%d failed (socket=%d, addr_valid=%d)",
             context->host ? context->host : "?", CUSTOM_TALLY_PORT,
#ifdef _WIN32
             (int)context->tally_socket,
#else
             context->tally_socket,
#endif
             context->tally_addr_valid ? 1 : 0);
        /* One failure may be transient; invalidate the address so the
         * next tick will re-resolve the host and try again. */
        context->tally_addr_valid = false;
    }

    context->tally_program = program;
    context->tally_preview = preview;
    context->last_tally_send_ns = now_ns;
}

/* Values are passed by the caller (captured inside the mutex in update()) so
 * that video_tick cannot race-overwrite context->control_* between the diff
 * detection and the actual sendto(). */
static void uvc_custom_network_send_control(uvc_custom_network *context,
                                            bool exposure_lock, bool focus_lock,
                                            int exposure_compensation, int af_mode, bool af_lock,
                                            int flash_mode, int wb_mode, int wb_kelvin,
                                            int resolution_index, int fps, int quality, int bitrate)
{
    if (!context || !context->host || context->host[0] == '\0') {
        blog(LOG_WARNING, "UVC CONTROL: skipped — host is empty");
        return;
    }

    /* Snapshot the host string before releasing any locks (caller already
     * holds no lock here, but host only changes under the lock in update()). */
    char host_copy[64];
    strncpy(host_copy, context->host, sizeof(host_copy) - 1);
    host_copy[sizeof(host_copy) - 1] = '\0';

#ifdef _WIN32
    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock == INVALID_SOCKET) {
#else
    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) {
#endif
        blog(LOG_WARNING, "UVC CONTROL: socket() failed, cannot send to %s", host_copy);
        return;
    }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)CUSTOM_TALLY_PORT);
    if (inet_pton(AF_INET, host_copy, &addr.sin_addr) <= 0) {
        blog(LOG_WARNING, "UVC CONTROL: inet_pton failed for host '%s'", host_copy);
#ifdef _WIN32
        closesocket(sock);
#else
        close(sock);
#endif
        return;
    }

    char payload[384];
    snprintf(payload, sizeof(payload),
             "CONTROL;exposure_lock=%d;focus_lock=%d;exposure_compensation=%d;af_mode=%d;af_lock=%d;flash_mode=%d;wb_mode=%d;wb_kelvin=%d;resolution_index=%d;fps=%d;quality=%d;bitrate=%d;srt_port=%d",
             exposure_lock ? 1 : 0,
             focus_lock ? 1 : 0,
             exposure_compensation,
             af_mode,
             af_lock ? 1 : 0,
             flash_mode,
             wb_mode,
             wb_kelvin,
             resolution_index,
             fps,
             quality,
             bitrate,
             context->srt_port);

    blog(LOG_INFO, "UVC CONTROL -> Android %s:%d: %s", host_copy, CUSTOM_TALLY_PORT, payload);

#ifdef _WIN32
    int sent = sendto(sock, payload, (int)strlen(payload), 0,
           (const struct sockaddr *)&addr, sizeof(addr));
    closesocket(sock);
#else
    ssize_t sent = sendto(sock, payload, strlen(payload), 0,
           (const struct sockaddr *)&addr, sizeof(addr));
    close(sock);
#endif
    if (sent < 0) {
        blog(LOG_WARNING, "UVC CONTROL: sendto() to %s:%d failed (socket=%d)",
             host_copy, CUSTOM_TALLY_PORT,
#ifdef _WIN32
             (int)sock
#else
             sock
#endif
        );
    }

    /* Ensure the control-state listener is running so we can receive
     * Android's CONTROL_STATE reply and keep settings in sync. */
    uvc_custom_network_control_state_start(context);
}

static void uvc_custom_network_apply_remote_control_state(uvc_custom_network *context, const char *msg)
{
    if (!context || !msg || strncmp(msg, "CONTROL_STATE;", 14) != 0) {
        return;
    }

    /* If the source is being removed, bail out — the mutex is about to
     * be destroyed and any queued state changes are irrelevant. */
    if (context->destroying) {
        return;
    }

    int exposure_lock = 0;
    int focus_lock = 0;
    int exposure_compensation = 0;
    int af_mode = 2;
    int af_lock = 0;
    int flash_mode = 2;
    int wb_mode = 0;
    int wb_kelvin = 4500;
    int resolution_index = 1;
    int fps = 30;
    int quality = 50;
    int bitrate = 0;

    pthread_mutex_lock(&context->lock);
    resolution_index = context->resolution_index;
    fps = context->fps;
    quality = context->quality;
    bitrate = context->bitrate;
    pthread_mutex_unlock(&context->lock);

    int matched = sscanf(msg,
                         "CONTROL_STATE;exposure_lock=%d;focus_lock=%d;exposure_compensation=%d;af_mode=%d;af_lock=%d;flash_mode=%d;wb_mode=%d;wb_kelvin=%d",
                         &exposure_lock,
                         &focus_lock,
                         &exposure_compensation,
                         &af_mode,
                         &af_lock,
                         &flash_mode,
                         &wb_mode,
                         &wb_kelvin);
    if (matched != 8) {
        return;
    }

    parse_message_int(msg, "resolution_index=", &resolution_index);
    parse_message_int(msg, "fps=", &fps);
    parse_message_int(msg, "quality=", &quality);
    parse_message_int(msg, "bitrate=", &bitrate);

    /* Ignore noisy EV drift when AE lock is off to avoid sync churn/freeze loops. */
    if (exposure_lock == 0) {
        pthread_mutex_lock(&context->lock);
        exposure_compensation = context->control_exposure_compensation;
        pthread_mutex_unlock(&context->lock);
    }

    bool changed = false;
    pthread_mutex_lock(&context->lock);
    /* Only queue the Android state if it actually differs from what OBS already
     * has in control_*.  This is the echo-suppression mechanism: after OBS
     * sends CONTROL, it updates control_* to the new value.  If Android echoes
     * back the same value it received, the diff is zero and nothing is queued,
     * so OBS's change is not overwritten. */
    if (context->control_exposure_lock != (exposure_lock != 0)
        || context->control_focus_lock != (focus_lock != 0)
        || context->control_exposure_compensation != exposure_compensation
        || context->control_af_mode != af_mode
        || context->control_af_lock != (af_lock != 0)
        || context->control_flash_mode != flash_mode
        || context->control_wb_mode != wb_mode
        || context->control_wb_kelvin != wb_kelvin
        || context->resolution_index != resolution_index
        || context->fps != fps
        || context->quality != quality
        || context->bitrate != bitrate) {
        context->pending_remote_control_state = true;
        context->pending_exposure_lock = (exposure_lock != 0);
        context->pending_focus_lock = (focus_lock != 0);
        context->pending_exposure_compensation = exposure_compensation;
        context->pending_af_mode = af_mode;
        context->pending_af_lock = (af_lock != 0);
        context->pending_flash_mode = flash_mode;
        context->pending_wb_mode = wb_mode;
        context->pending_wb_kelvin = wb_kelvin;
        context->pending_resolution_index = resolution_index;
        context->pending_fps = fps;
        context->pending_quality = quality;
        context->pending_bitrate = bitrate;
        changed = true;
    }
    pthread_mutex_unlock(&context->lock);
    UNUSED_PARAMETER(changed);
}

static void uvc_custom_network_apply_pending_state_to_source(uvc_custom_network *context)
{
    if (!context || !context->source || context->destroying) {
        return;
    }

    /* Write the current control_* values into the source's stored settings so
     * they are persisted to the scene collection file and the Properties dialog
     * shows the latest values when reopened.
     *
     * We deliberately do NOT call obs_source_update() -- that would re-enter
     * update() from the video tick thread and restart the receiver. */
    obs_data_t *settings = obs_source_get_settings(context->source);
    if (!settings) {
        return;
    }

    obs_data_set_bool(settings, "exposure_lock", context->control_exposure_lock);
    obs_data_set_bool(settings, "focus_lock", context->control_focus_lock);
    obs_data_set_int(settings, "exposure_compensation", context->control_exposure_compensation);
    obs_data_set_int(settings, "af_mode", context->control_af_mode);
    obs_data_set_bool(settings, "af_lock", context->control_af_lock);
    obs_data_set_int(settings, "flash_mode", context->control_flash_mode);
    obs_data_set_int(settings, "wb_mode", context->control_wb_mode);
    obs_data_set_int(settings, "wb_kelvin", context->control_wb_kelvin);
    obs_data_set_int(settings, "resolution_index", context->resolution_index);
    obs_data_set_int(settings, "fps", context->fps);
    obs_data_set_int(settings, "quality", context->quality);
    obs_data_set_int(settings, "bitrate", context->bitrate);
    obs_data_release(settings);
}

static void uvc_custom_network_video_tick(void *data, float seconds)
{
    UNUSED_PARAMETER(seconds);
    uvc_custom_network *context = (uvc_custom_network *)data;
    if (!context || context->destroying) {
        return;
    }

    uint64_t now_ns = os_gettime_ns();

    /* ── Send tally to Android (independent of video frame arrival) ── */
    uvc_custom_network_send_tally(context, false);

    /* ── Sync status & source name to settings (rate-limited) ── */
    bool do_ui_refresh = context->pending_ui_refresh;
    bool do_discovery_save = context->pending_discovery_save;
    if (do_ui_refresh) {
        context->pending_ui_refresh = false;
    }
    if (do_discovery_save) {
        context->pending_discovery_save = false;
    }
    if (now_ns - context->last_props_refresh_ns >= 500000000ULL || do_ui_refresh || do_discovery_save) {
        context->last_props_refresh_ns = now_ns;
        obs_data_t *settings = obs_source_get_settings(context->source);
        if (settings) {
            pthread_mutex_lock(&context->lock);
            if (context->discovery_status) {
                obs_data_set_string(settings, "discovery_status",
                    context->discovery_status);
            }
            /* Persist auto-discovered host/port so the source can
             * auto-start on the next OBS launch. */
            if (do_discovery_save && context->host && context->host[0] != '\0') {
                obs_data_set_string(settings, "host", context->host);
                obs_data_set_int(settings, "port", context->port);
            }
            pthread_mutex_unlock(&context->lock);
            obs_data_release(settings);
        }
        if (do_ui_refresh && !context->destroying) {
            obs_source_update_properties(context->source);
        }
    }

    /* ── Handle remote control state from Android ── */
    pthread_mutex_lock(&context->lock);
    if (!context->pending_remote_control_state) {
        pthread_mutex_unlock(&context->lock);
        return;
    }
    if (context->last_remote_apply_ns > 0 && (now_ns - context->last_remote_apply_ns) < 250000000ULL) {
        pthread_mutex_unlock(&context->lock);
        return;
    }

    /* If OBS sent a CONTROL to Android recently, ignore any Android reply for
     * the authority window.  Android sends back its PRE-CHANGE state immediately
     * on receiving the packet (before runOnUiThread processes it), so we need
     * to suppress that stale echo.  1.5 s covers the initial stale reply plus
     * one full 500 ms Android send cycle with margin. */
#define OBS_CONTROL_AUTHORITY_NS 1500000000ULL  /* 1.5 seconds */
    if (context->last_obs_control_send_ns > 0 &&
        (now_ns - context->last_obs_control_send_ns) < OBS_CONTROL_AUTHORITY_NS) {
        context->pending_remote_control_state = false;  /* consume, don't apply */
        pthread_mutex_unlock(&context->lock);
        return;
    }

    /* Apply Android state directly into control fields. */
    context->control_exposure_lock        = context->pending_exposure_lock;
    context->control_focus_lock           = context->pending_focus_lock;
    context->control_exposure_compensation = context->pending_exposure_compensation;
    context->control_af_mode              = context->pending_af_mode;
    context->control_af_lock              = context->pending_af_lock;
    context->control_flash_mode           = context->pending_flash_mode;
    context->control_wb_mode              = context->pending_wb_mode;
    context->control_wb_kelvin            = context->pending_wb_kelvin;
    context->resolution_index             = context->pending_resolution_index;
    context->fps                          = context->pending_fps;
    context->quality                      = context->pending_quality;
    context->bitrate                      = context->pending_bitrate;
    context->pending_remote_control_state = false;
    context->pending_ui_refresh = true;  /* trigger dialog rebuild so UI reflects remote changes */
    pthread_mutex_unlock(&context->lock);

    uvc_custom_network_apply_pending_state_to_source(context);
    context->last_remote_apply_ns = now_ns;
}

/* ── Source activate / deactivate ─────────────────────────────────────
 * Scene switching calls these.  We keep the receiver running so video
 * flow is never interrupted — OBS simply stops/starts consuming frames
 * via video_tick.  No reconnect needed. */
static void uvc_custom_network_activate(void *data)
{
    uvc_custom_network *context = (uvc_custom_network *)data;
    if (!context) return;
    blog(LOG_INFO, "UVC: source activated — video_tick resumes");
}

static void uvc_custom_network_deactivate(void *data)
{
    uvc_custom_network *context = (uvc_custom_network *)data;
    if (!context) return;
    /* Do NOT stop the receiver.  Just log — video_tick pauses
     * automatically and the receiver keeps buffering in the background. */
    blog(LOG_INFO, "UVC: source deactivated (receiver stays alive)");
}


static void *uvc_custom_network_control_state_thread(void *data)
{
    uvc_custom_network *context = (uvc_custom_network *)data;
    if (!context) {
        return NULL;
    }

#ifdef _WIN32
    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock == INVALID_SOCKET) {
        context->control_state_running = false;
        return NULL;
    }
    context->control_state_socket = sock;
    BOOL reuse = TRUE;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, (const char *)&reuse, sizeof(reuse));
    DWORD timeout_ms = 500;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (const char *)&timeout_ms, sizeof(timeout_ms));
#else
    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) {
        context->control_state_running = false;
        return NULL;
    }
    context->control_state_socket = sock;
    int reuse = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = 500000;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
#endif

    struct sockaddr_in local_addr;
    memset(&local_addr, 0, sizeof(local_addr));
    local_addr.sin_family = AF_INET;
    local_addr.sin_port = htons((uint16_t)CUSTOM_TALLY_PORT);
    local_addr.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(sock, (const struct sockaddr *)&local_addr, sizeof(local_addr)) < 0) {
#ifdef _WIN32
        closesocket(sock);
        context->control_state_socket = INVALID_SOCKET;
#else
        close(sock);
        context->control_state_socket = -1;
#endif
        context->control_state_running = false;
        return NULL;
    }

    while (context->control_state_running) {
        char buf[256];
        struct sockaddr_in from_addr;
#ifdef _WIN32
        int from_len = (int)sizeof(from_addr);
        int got = recvfrom(sock, buf, (int)sizeof(buf) - 1, 0,
                           (struct sockaddr *)&from_addr, &from_len);
#else
        socklen_t from_len = (socklen_t)sizeof(from_addr);
        ssize_t got = recvfrom(sock, buf, sizeof(buf) - 1, 0,
                               (struct sockaddr *)&from_addr, &from_len);
#endif
        if (got <= 0) {
            continue;
        }

        buf[got] = '\0';

        /* Bail out if the source is being removed — the mutex may
         * already be invalid or about to be destroyed. */
        if (context->destroying) {
            continue;
        }

        if (strncmp(buf, "CONTROL_STATE;", 14) != 0) {
            continue;
        }

        char host_expected[64] = {0};
        pthread_mutex_lock(&context->lock);
        if (context->host && context->host[0] != '\0') {
            strncpy(host_expected, context->host, sizeof(host_expected) - 1);
        }
        pthread_mutex_unlock(&context->lock);
        if (host_expected[0] != '\0') {
            char from_ip[64] = {0};
            const char *src = inet_ntop(AF_INET, &from_addr.sin_addr, from_ip, sizeof(from_ip));
            if (!src || strcmp(from_ip, host_expected) != 0) {
                continue;
            }
        }

        uvc_custom_network_apply_remote_control_state(context, buf);
    }

#ifdef _WIN32
    if (context->control_state_socket != INVALID_SOCKET) {
        closesocket(context->control_state_socket);
        context->control_state_socket = INVALID_SOCKET;
    }
#else
    if (context->control_state_socket >= 0) {
        close(context->control_state_socket);
        context->control_state_socket = -1;
    }
#endif

    return NULL;
}

static void uvc_custom_network_control_state_start(uvc_custom_network *context)
{
    if (!context || context->control_state_running) {
        return;
    }
    context->control_state_running = true;
#ifdef _WIN32
    context->control_state_socket = INVALID_SOCKET;
#else
    context->control_state_socket = -1;
#endif
    pthread_create(&context->control_state_thread, NULL, uvc_custom_network_control_state_thread, context);
}

static void uvc_custom_network_control_state_stop(uvc_custom_network *context)
{
    if (!context || !context->control_state_running) {
        return;
    }

    context->control_state_running = false;
#ifdef _WIN32
    if (context->control_state_socket != INVALID_SOCKET) {
        closesocket(context->control_state_socket);
        context->control_state_socket = INVALID_SOCKET;
    }
#else
    if (context->control_state_socket >= 0) {
        close(context->control_state_socket);
        context->control_state_socket = -1;
    }
#endif

    pthread_join(context->control_state_thread, NULL);
}

static void uvc_custom_network_srt_receiver_stop(uvc_custom_network *context)
{
    if (!context || !context->srt_receiver_running) {
        return;
    }
    context->srt_receiver_running = false;
#ifdef _WIN32
    if (context->srt_receiver_socket != INVALID_SOCKET) {
        closesocket(context->srt_receiver_socket);
        context->srt_receiver_socket = INVALID_SOCKET;
    }
#else
    if (context->srt_receiver_socket >= 0) {
        close(context->srt_receiver_socket);
        context->srt_receiver_socket = -1;
    }
#endif
    pthread_join(context->srt_receiver_thread, NULL);
}

static void uvc_custom_network_srt_receiver_start(uvc_custom_network *context)
{
    if (!context || context->srt_receiver_running) {
        return;
    }

    /* Set up persistent tally socket so video_tick can send tally
     * independently of video frame arrival. */
    uvc_custom_network_tally_update_addr(context);

    /* CONTROL is now sent from inside the SRT receiver thread AFTER the
     * UDP socket binds to its actual port — the port may differ from the
     * configured one when auto-bind picks the next free port.  Sending
     * CONTROL here (before the thread binds) would give the phone a stale
     * port and cause the video stream to be silently lost. */

    context->srt_receiver_running = true;
    context->last_video_frame_ns = os_gettime_ns();
#ifdef _WIN32
    context->srt_receiver_socket = INVALID_SOCKET;
#else
    context->srt_receiver_socket = -1;
#endif
    pthread_create(&context->srt_receiver_thread, NULL, uvc_custom_network_srt_receiver_thread, context);
}

static void *uvc_custom_network_srt_receiver_thread(void *data)
{
    uvc_custom_network *context = (uvc_custom_network *)data;

    pthread_mutex_lock(&context->lock);
    int port = context->srt_port > 0 ? context->srt_port : 5601;
    char *host = bstrdup(context->host ? context->host : "");
    pthread_mutex_unlock(&context->lock);

    if (!host || host[0] == '\0') {
        blog(LOG_WARNING, "UVC SRT: Phone IP is not set");
        bfree(host);
        context->srt_receiver_running = false;
        return NULL;
    }

    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_HEVC);
    if (!codec) {
        blog(LOG_ERROR, "UVC SRT: H.265 decoder not found");
        bfree(host);
        context->srt_receiver_running = false;
        return NULL;
    }

    AVPacket *pkt = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    AVCodecContext *decoder = NULL;

    if (!pkt || !frame) {
        av_packet_free(&pkt);
        av_frame_free(&frame);
        bfree(host);
        context->srt_receiver_running = false;
        return NULL;
    }

    blog(LOG_INFO, "UVC SRT: starting UDP listener on port %d", port);

#ifdef _WIN32
    SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock == INVALID_SOCKET) {
#else
    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) {
#endif
        blog(LOG_WARNING, "UVC SRT: socket() failed");
        av_packet_free(&pkt);
        av_frame_free(&frame);
        bfree(host);
        context->srt_receiver_running = false;
        return NULL;
    }

    int rcvbuf = 8 * 1024 * 1024;
    setsockopt(sock, SOL_SOCKET, SO_RCVBUF, (char *)&rcvbuf, sizeof(rcvbuf));

    /* Auto-select an available UDP port.  Try the configured port first,
     * then scan upward so multiple OBS sources never collide. */
    int bound_port = 0;
    {
        struct sockaddr_in local_addr;
        for (int attempt = 0; attempt < 100; attempt++) {
            int try_port = port + attempt;
            if (try_port < 1024 || try_port > 65535) continue;
            memset(&local_addr, 0, sizeof(local_addr));
            local_addr.sin_family = AF_INET;
            local_addr.sin_port = htons((uint16_t)try_port);
            local_addr.sin_addr.s_addr = htonl(INADDR_ANY);
            if (bind(sock, (struct sockaddr *)&local_addr, sizeof(local_addr)) == 0) {
                bound_port = try_port;
                break;
            }
        }
    }
    if (bound_port == 0) {
        blog(LOG_WARNING, "UVC SRT: no available UDP port in range %d–%d", port, port + 99);
#ifdef _WIN32
        closesocket(sock);
#else
        close(sock);
#endif
        av_packet_free(&pkt);
        av_frame_free(&frame);
        bfree(host);
        context->srt_receiver_running = false;
        return NULL;
    }
    port = bound_port;

    /* Store the actual bound port so other code (TALLY, status) uses it. */
    pthread_mutex_lock(&context->lock);
    context->srt_port = bound_port;
    context->configured_srt_port = bound_port;
    pthread_mutex_unlock(&context->lock);
    blog(LOG_INFO, "UVC SRT: bound to UDP port %d", bound_port);

#ifdef _WIN32
    DWORD timeout = 500;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (char *)&timeout, sizeof(timeout));
#else
    struct timeval tv = {0, 500000};
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
#endif

    context->srt_receiver_socket = sock;
    blog(LOG_INFO, "UVC SRT: listening on UDP port %d", port);

    /* Update source display name */
    {
        char name_buf[128];
        snprintf(name_buf, sizeof(name_buf), "📱 %s:%d (SRT)", host, port);
        pthread_mutex_lock(&context->lock);
        bfree(context->source_display_name);
        context->source_display_name = bstrdup(name_buf);
        pthread_mutex_unlock(&context->lock);
    }

    /* Send initial CONTROL with the ACTUAL bound port (not the
     * configured one — auto-bind may have picked a different port).
     * This tells the phone exactly where to stream video.  Must be
     * done BEFORE the status update below so the phone has a chance
     * to start sending before we wait for the first packet. */
    {
        bool exp_lock, fcs_lock, af_lock;
        int exp_comp, af_md, fl_md, wb_md, wb_k, res_idx, fps_v, qual, bitr;
        pthread_mutex_lock(&context->lock);
        exp_lock = context->control_exposure_lock;
        fcs_lock = context->control_focus_lock;
        exp_comp = context->control_exposure_compensation;
        af_md    = context->control_af_mode;
        af_lock  = context->control_af_lock;
        fl_md    = context->control_flash_mode;
        wb_md    = context->control_wb_mode;
        wb_k     = context->control_wb_kelvin;
        res_idx  = context->resolution_index;
        fps_v    = context->fps;
        qual     = context->quality;
        bitr     = context->bitrate;
        pthread_mutex_unlock(&context->lock);
        uvc_custom_network_send_control(context,
            exp_lock, fcs_lock, exp_comp,
            af_md, af_lock, fl_md, wb_md, wb_k,
            res_idx, fps_v, qual, bitr);
    }

    uvc_custom_network_set_status(context, "SRT listening %s:%d — waiting for video", host, port);

    bool use_hw = false;
    pthread_mutex_lock(&context->lock);
    use_hw = context->hw_decode;
    pthread_mutex_unlock(&context->lock);

    const char *active_codec_name = NULL;
    if (use_hw) {
        decoder = try_open_hw_decoder(&active_codec_name);
        if (!decoder) {
            blog(LOG_WARNING, "UVC SRT: HW decoder unavailable — falling back to software");
        }
    }

    if (!decoder) {
        active_codec_name = "hevc (software)";
        decoder = avcodec_alloc_context3(codec);
        decoder->flags |= AV_CODEC_FLAG_LOW_DELAY;
        decoder->flags2 |= AV_CODEC_FLAG2_FAST;
        decoder->thread_type = FF_THREAD_SLICE;
        decoder->thread_count = 4;
        if (avcodec_open2(decoder, codec, NULL) != 0) {
            blog(LOG_ERROR, "UVC SRT: avcodec_open2 failed");
            avcodec_free_context(&decoder);
            av_packet_free(&pkt);
            av_frame_free(&frame);
#ifdef _WIN32
            closesocket(sock);
            context->srt_receiver_socket = INVALID_SOCKET;
#else
            close(sock);
            context->srt_receiver_socket = -1;
#endif
            bfree(host);
            context->srt_receiver_running = false;
            return NULL;
        }
    }

    bool logged_first = false;
    bool logged_first_packet = false;
    uint8_t recv_buf[64 * 1024];

    /* Chunk reassembly state */
    uint8_t *reasm_buf = NULL;
    size_t  reasm_capacity = 0;
    uint64_t reasm_pts = H264_NO_PTS;
    uint16_t reasm_total = 0;
    uint16_t reasm_got = 0;
    size_t  reasm_size = 0;

    /* ── SRT latency buffer: smooths jitter by delaying frame output ── */
#define SRT_DELAY_MAX 64
    struct srt_delayed {
        struct obs_source_frame obs;
        AVFrame *av_frame;   // referenced (av_frame_ref), freed on output
        uint64_t output_ns;
    } delay_buf[SRT_DELAY_MAX];
    int delay_head = 0, delay_count = 0;

    int latency_ms;
    pthread_mutex_lock(&context->lock);
    latency_ms = context->srt_latency_ms;
    pthread_mutex_unlock(&context->lock);
    uint64_t latency_ns = (uint64_t)latency_ms * 1000000ULL;
    blog(LOG_INFO, "UVC SRT: latency buffer = %d ms", latency_ms);

    while (context->srt_receiver_running) {
        struct sockaddr_in from_addr;
#ifdef _WIN32
        int from_len = (int)sizeof(from_addr);
        int got = recvfrom(sock, (char *)recv_buf, (int)sizeof(recv_buf), 0,
                           (struct sockaddr *)&from_addr, &from_len);
#else
        socklen_t from_len = (socklen_t)sizeof(from_addr);
        ssize_t got = recvfrom(sock, recv_buf, sizeof(recv_buf), 0,
                               (struct sockaddr *)&from_addr, &from_len);
#endif
        if (got <= 0) continue;
        if (got < 16) continue; /* need at least the 16-byte chunk header */

        if (!logged_first_packet) {
            char from_ip[64];
            inet_ntop(AF_INET, &from_addr.sin_addr, from_ip, sizeof(from_ip));
            blog(LOG_INFO, "UVC SRT: first packet received — %d bytes from %s:%d",
                 got, from_ip, (int)ntohs(from_addr.sin_port));
            logged_first_packet = true;
        }

        uint64_t recv_time_ns = os_gettime_ns();

        /* Parse 16-byte chunk header (revised format):
         *   bytes 0-7:   PTS (uint64 BE)
         *   bytes 8-9:   seqNum (uint16 BE) — per-frame sequence for NAK
         *   byte  10:    chunkIdx (uint8)
         *   byte  11:    totalChunks (uint8)
         *   bytes 12-13: payloadLen (uint16 BE)
         *   bytes 14-15: flags (uint16 BE) — bit0=isRetransmit
         */
        uint64_t chunk_pts    = read_u64be(recv_buf);                      /* bytes 0-7 */
        uint16_t seq_num      = (uint16_t)((recv_buf[8] << 8) | recv_buf[9]);   /* bytes 8-9 */
        uint16_t chunk_idx    = (uint16_t)recv_buf[10];                   /* byte 10 */
        uint16_t total_chunks = (uint16_t)recv_buf[11];                   /* byte 11 */
        uint16_t chunk_len    = (uint16_t)((recv_buf[12] << 8) | recv_buf[13]); /* bytes 12-13 */
        uint16_t flags        = (uint16_t)((recv_buf[14] << 8) | recv_buf[15]); /* bytes 14-15 */

        (void)seq_num;   // reserved for future NAK-based loss detection
        (void)flags;     // reserved for isRetransmit flag

        if (chunk_len == 0 || chunk_len > H264_MAX_SIZE
                || (size_t)(16 + chunk_len) > (size_t)got
                || total_chunks == 0 || total_chunks > 1024
                || chunk_idx >= total_chunks) {
            continue;
        }

        if (total_chunks == 1) {
            /* Fast path: single chunk, no reassembly needed */
            if (av_new_packet(pkt, (int)chunk_len + AV_INPUT_BUFFER_PADDING_SIZE) < 0) continue;
            pkt->size = (int)chunk_len;
            memcpy(pkt->data, recv_buf + 16, chunk_len);
            memset(pkt->data + chunk_len, 0, AV_INPUT_BUFFER_PADDING_SIZE);
            pkt->pts = (chunk_pts == H264_NO_PTS) ? AV_NOPTS_VALUE : (int64_t)chunk_pts;
        } else {
            /* Multi-chunk: start or continue reassembly */
            if (reasm_pts != chunk_pts || reasm_total != total_chunks) {
                /* New NAL unit — reset reassembly state */
                reasm_pts = chunk_pts;
                reasm_total = total_chunks;
                reasm_got = 0;
                reasm_size = 0;
                /* Estimate total size from first chunk's proportion */
                size_t est = (size_t)chunk_len * (size_t)total_chunks;
                if (est > H264_MAX_SIZE) est = H264_MAX_SIZE;
                if (est + AV_INPUT_BUFFER_PADDING_SIZE > reasm_capacity) {
                    bfree(reasm_buf);
                    reasm_capacity = est + AV_INPUT_BUFFER_PADDING_SIZE;
                    reasm_buf = (uint8_t *)bmalloc(reasm_capacity);
                }
                memset(reasm_buf, 0, reasm_capacity);
            }

            if (reasm_buf && reasm_size + chunk_len <= reasm_capacity) {
                memcpy(reasm_buf + reasm_size, recv_buf + 16, chunk_len);
                reasm_size += chunk_len;
                reasm_got++;
            }

            if (reasm_got < reasm_total) {
                continue; /* wait for more chunks */
            }

            /* All chunks received — feed reassembled NAL unit to decoder */
            if (av_new_packet(pkt, (int)reasm_size + AV_INPUT_BUFFER_PADDING_SIZE) < 0) {
                reasm_pts = H264_NO_PTS; /* reset */
                continue;
            }
            pkt->size = (int)reasm_size;
            memcpy(pkt->data, reasm_buf, reasm_size);
            memset(pkt->data + reasm_size, 0, AV_INPUT_BUFFER_PADDING_SIZE);
            pkt->pts = (chunk_pts == H264_NO_PTS) ? AV_NOPTS_VALUE : (int64_t)chunk_pts;
            reasm_pts = H264_NO_PTS; /* consumed */
        }

        int ret = avcodec_send_packet(decoder, pkt);
        av_packet_unref(pkt);
        if (ret < 0 && ret != AVERROR(EAGAIN)) continue;

        while (avcodec_receive_frame(decoder, frame) == 0) {
            struct obs_source_frame obs_frame;
            memset(&obs_frame, 0, sizeof(obs_frame));
            obs_frame.width = (uint32_t)frame->width;
            obs_frame.height = (uint32_t)frame->height;
            obs_frame.timestamp = recv_time_ns;
            obs_frame.trc = VIDEO_TRC_DEFAULT;

            bool format_ok = true;
            switch (frame->format) {
            case AV_PIX_FMT_YUV420P:
            case AV_PIX_FMT_YUVJ420P:
                obs_frame.format = VIDEO_FORMAT_I420;
                obs_frame.data[0] = frame->data[0];
                obs_frame.data[1] = frame->data[1];
                obs_frame.data[2] = frame->data[2];
                obs_frame.linesize[0] = (uint32_t)frame->linesize[0];
                obs_frame.linesize[1] = (uint32_t)frame->linesize[1];
                obs_frame.linesize[2] = (uint32_t)frame->linesize[2];
                break;
            case AV_PIX_FMT_NV12:
                obs_frame.format = VIDEO_FORMAT_NV12;
                obs_frame.data[0] = frame->data[0];
                obs_frame.data[1] = frame->data[1];
                obs_frame.linesize[0] = (uint32_t)frame->linesize[0];
                obs_frame.linesize[1] = (uint32_t)frame->linesize[1];
                break;
            default:
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
                context->width = (uint32_t)frame->width;
                context->height = (uint32_t)frame->height;
                if (chunk_pts != H264_NO_PTS && chunk_pts > 0) {
                    uint64_t capture_ns = (uint64_t)chunk_pts * 1000ULL;
                    if (recv_time_ns > capture_ns) {
                        context->latency_ms = (double)(recv_time_ns - capture_ns) / 1000000.0;
                    }
                }
                double lat = context->latency_ms;
                int fw = frame->width;
                int fh = frame->height;
                uint64_t now_ns = os_gettime_ns();
                bool do_update = (now_ns - context->last_latency_update_ns >= 500000000ULL);
                if (do_update) {
                    context->last_latency_update_ns = now_ns;
                    /* Also update source name in OBS list */
                    char name_buf[128];
                    snprintf(name_buf, sizeof(name_buf),
                             "📱 %s:%d  %dx%d  %.0fms",
                             host, port, fw, fh, lat);
                    bfree(context->source_display_name);
                    context->source_display_name = bstrdup(name_buf);
                }
                pthread_mutex_unlock(&context->lock);

                /* Write status through the setter so 📡 Status text field updates */
                if (do_update) {
                    char status[128];
                    snprintf(status, sizeof(status),
                             "SRT %s:%d | %dx%d | %.0f ms delay",
                             host, port, fw, fh, lat);
                    uvc_custom_network_set_status(context, "%s", status);
                }

                /* ── Latency-buffered output ──
                 * Drain any frames whose delay has expired, then
                 * enqueue this frame for delayed output. */
                {
                    uint64_t drain_now = os_gettime_ns();
                    while (delay_count > 0) {
                        if (drain_now >= delay_buf[delay_head].output_ns) {
                            if (!context->destroying) {
                                obs_source_output_video(context->source,
                                    &delay_buf[delay_head].obs);
                                pthread_mutex_lock(&context->lock);
                                context->last_video_frame_ns = os_gettime_ns();
                                pthread_mutex_unlock(&context->lock);
                            }
                            av_frame_free(&delay_buf[delay_head].av_frame);
                            delay_head = (delay_head + 1) % SRT_DELAY_MAX;
                            delay_count--;
                        } else {
                            break;
                        }
                    }

                    /* Enqueue the new frame */
                    if (delay_count < SRT_DELAY_MAX) {
                        int tail = (delay_head + delay_count) % SRT_DELAY_MAX;
                        delay_buf[tail].obs = obs_frame;
                        delay_buf[tail].av_frame = av_frame_alloc();
                        if (delay_buf[tail].av_frame) {
                            av_frame_ref(delay_buf[tail].av_frame, frame);
                        }
                        delay_buf[tail].output_ns = os_gettime_ns() + latency_ns;
                        delay_count++;
                    } else {
                        /* Buffer saturated — force output immediately */
                        if (!context->destroying) {
                            obs_source_output_video(context->source, &obs_frame);
                            pthread_mutex_lock(&context->lock);
                            context->last_video_frame_ns = os_gettime_ns();
                            pthread_mutex_unlock(&context->lock);
                        }
                    }
                }

                if (!logged_first) {
                    blog(LOG_INFO, "UVC SRT: first decoded frame %dx%d codec=%s",
                         frame->width, frame->height,
                         active_codec_name ? active_codec_name : "sw");
                    logged_first = true;
                }
            }
            av_frame_unref(frame);
        }
    }

    /* Drain any frames still in the latency buffer before cleanup */
    for (int i = 0; i < delay_count; i++) {
        int idx = (delay_head + i) % SRT_DELAY_MAX;
        if (!context->destroying) {
            obs_source_output_video(context->source, &delay_buf[idx].obs);
        }
        av_frame_free(&delay_buf[idx].av_frame);
    }

    if (decoder) avcodec_free_context(&decoder);
    av_packet_free(&pkt);
    av_frame_free(&frame);
    bfree(reasm_buf);

    /* Clean up SRT socket */
#ifdef _WIN32
    if (context->srt_receiver_socket != INVALID_SOCKET) {
        closesocket(sock);
        context->srt_receiver_socket = INVALID_SOCKET;
    }
#else
    if (context->srt_receiver_socket >= 0) {
        close(sock);
        context->srt_receiver_socket = -1;
    }
#endif
    bfree(host);
    blog(LOG_INFO, "UVC SRT: receiver thread exiting");
    return NULL;
}

static void uvc_custom_network_receiver_stop(uvc_custom_network *context)
{
    if (!context || !context->receiver_running) {
        /* SRT-only mode: just stop the SRT receiver.  Keep control_state
         * alive — it's managed independently for settings sync. */
        if (context && context->srt_receiver_running) {
            uvc_custom_network_srt_receiver_stop(context);
        }
        return;
    }

    uvc_custom_network_control_state_stop(context);

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
        blog(LOG_WARNING, "UVC H265 TCP: Phone IP is not set — enter it in the source properties");
        bfree(host);
        context->receiver_running = false;
        return NULL;
    }

    /* ----- initialise avcodec H.265 decoder ----- */
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_HEVC);
    if (!codec) {
        blog(LOG_ERROR, "UVC H265 TCP: avcodec H.265 decoder not found");
        bfree(host);
        context->receiver_running = false;
        return NULL;
    }

    AVPacket *pkt   = av_packet_alloc();
    AVFrame  *frame = av_frame_alloc();
    AVCodecContext *decoder = NULL;

    if (!pkt || !frame) {
        blog(LOG_ERROR, "UVC H265 TCP: failed to allocate avcodec packet/frame");
        av_packet_free(&pkt);
        av_frame_free(&frame);
        bfree(host);
        context->receiver_running = false;
        return NULL;
    }

    blog(LOG_INFO, "UVC H265 TCP: connecting to Android at %s:%d", host, port);

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
        int rcvbuf = 8 * 1024 * 1024;
        setsockopt(sock, SOL_SOCKET, SO_RCVBUF, (char *)&rcvbuf, sizeof(rcvbuf));

        struct sockaddr_in addr;
        memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_port   = htons((uint16_t)port);

        if (inet_pton(AF_INET, host, &addr.sin_addr) <= 0) {
            blog(LOG_WARNING, "UVC H265 TCP: invalid phone IP '%s' — check source settings", host);
#ifdef _WIN32
            closesocket(sock);
#else
            close(sock);
#endif
            for (int i = 0; i < 40 && context->receiver_running; i++) os_sleep_ms(50);
            continue;
        }

        /* Non-blocking connect with a 2 s timeout so receiver_stop()
         * can shut down the thread without hanging the OBS UI thread on
         * pthread_join.  A blocking connect() to an unreachable host can
         * stall for 20+ seconds on Windows or 127+ s on Linux. */
#ifdef _WIN32
        {
            u_long mode = 1;
            ioctlsocket(sock, FIONBIO, &mode);
        }
#else
        int sock_flags = fcntl(sock, F_GETFL, 0);
        fcntl(sock, F_SETFL, sock_flags | O_NONBLOCK);
#endif

        int connect_ret = connect(sock, (struct sockaddr *)&addr, sizeof(addr));
        bool connect_ok = (connect_ret == 0);
#ifdef _WIN32
        if (connect_ret < 0 && WSAGetLastError() == WSAEWOULDBLOCK) {
#else
        if (connect_ret < 0 && errno == EINPROGRESS) {
#endif
            fd_set wfds;
            FD_ZERO(&wfds);
            FD_SET(sock, &wfds);
            struct timeval tv;
            tv.tv_sec = 2;
            tv.tv_usec = 0;
            int sel = select((int)(sock + 1), NULL, &wfds, NULL, &tv);
            if (sel > 0) {
                /* select says writable — check SO_ERROR for real outcome */
                int so_err = 0;
                socklen_t so_len = sizeof(so_err);
                getsockopt(sock, SOL_SOCKET, SO_ERROR,
#ifdef _WIN32
                           (char *)&so_err, &so_len);
#else
                           &so_err, &so_len);
#endif
                connect_ok = (so_err == 0);
            }
        }

        /* Restore blocking mode */
#ifdef _WIN32
        {
            u_long mode = 0;
            ioctlsocket(sock, FIONBIO, &mode);
        }
#else
        fcntl(sock, F_SETFL, sock_flags);
#endif

        if (!connect_ok) {
            blog(LOG_INFO, "UVC H265 TCP: connect to %s:%d failed, retrying...", host, port);
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
#ifdef _WIN32
        {
            DWORD timeout = 500;
            setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (char *)&timeout, sizeof(timeout));
        }
#else
        {
            struct timeval tv = {0, 500000};
            setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        }
#endif

        context->receiver_socket = sock;
        blog(LOG_INFO, "UVC H265 TCP: connected to Android %s:%d", host, port);

        /* Update the source display name so OBS sources list shows device */
        {
            char name_buf[128];
            snprintf(name_buf, sizeof(name_buf), "📱 %s:%d (TCP)", host, port);
            pthread_mutex_lock(&context->lock);
            bfree(context->source_display_name);
            context->source_display_name = bstrdup(name_buf);
            pthread_mutex_unlock(&context->lock);
        }

        /* Initial CONTROL is now sent from start_receiver() on the main
         * thread before this thread is spawned — no need to duplicate. */
        uvc_custom_network_set_status(context, "Connected %s:%d — waiting for video", host, port);

        /* fresh H.265 decoder for each connection */
        if (decoder) { avcodec_free_context(&decoder); decoder = NULL; }

        bool use_hw = false;
        pthread_mutex_lock(&context->lock);
        use_hw = context->hw_decode;
        pthread_mutex_unlock(&context->lock);

        const char *active_codec_name = NULL;
        if (use_hw) {
            decoder = try_open_hw_decoder(&active_codec_name);
            if (!decoder) {
                blog(LOG_WARNING, "UVC H265 TCP: HW decoder unavailable — falling back to software");
            }
        }

        if (!decoder) {
            active_codec_name = "hevc (software)";
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
        }

        bool logged_first = false;
        uint64_t pts_epoch_ns = 0;   /* anchor: OBS time when first PTS arrived */
        int64_t  pts_epoch_us = 0;   /* anchor: first PTS value (microseconds) */
        uint64_t last_output_timestamp_ns = 0;

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
            uint64_t recv_time_ns = os_gettime_ns(); /* time when header was received */

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
                    uint64_t now_frame_ns = os_gettime_ns();
                    if (pts_epoch_us == 0) {
                        pts_epoch_us = frame->pts;
                        pts_epoch_ns = now_frame_ns;
                    }
                    int64_t delta_us = frame->pts - pts_epoch_us;
                    if (delta_us < 0) {
                        /* PTS went backward (e.g. Android camera settings caused
                         * an encoder reset).  Re-anchor the epoch to avoid a
                         * negative cast to uint64_t that would produce an
                         * astronomically large timestamp and stall OBS frames. */
                        pts_epoch_us = frame->pts;
                        pts_epoch_ns = now_frame_ns;
                        delta_us = 0;
                    }
                    uint64_t candidate_ts = pts_epoch_ns + (uint64_t)(delta_us * 1000);

                    /* Camera-control changes can momentarily stall the Android
                     * camera/encoder pipeline and the next frame may arrive with
                     * a capture timestamp far in the future.  If we pass that
                     * through directly, OBS waits until that future timestamp and
                     * the preview appears frozen.  Re-anchor when the timestamp
                     * drifts too far from the local wall clock or regresses too
                     * far behind the last frame. */
                    if (candidate_ts > now_frame_ns + 250000000ULL
                            || (last_output_timestamp_ns > 0
                                && candidate_ts + 250000000ULL < last_output_timestamp_ns)) {
                        pts_epoch_us = frame->pts;
                        pts_epoch_ns = now_frame_ns;
                        candidate_ts = now_frame_ns;
                    }

                    if (last_output_timestamp_ns > 0 && candidate_ts <= last_output_timestamp_ns) {
                        candidate_ts = last_output_timestamp_ns + 1000ULL;
                    }

                    obs_frame.timestamp = candidate_ts;
                } else {
                    uint64_t now_frame_ns = os_gettime_ns();
                    if (last_output_timestamp_ns > 0 && now_frame_ns <= last_output_timestamp_ns) {
                        now_frame_ns = last_output_timestamp_ns + 1000ULL;
                    }
                    obs_frame.timestamp = now_frame_ns;
                }
                last_output_timestamp_ns = obs_frame.timestamp;
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

                    /* Guard: don't output video if the source is being removed */
                    if (!context->destroying) {
                        obs_source_output_video(context->source, &obs_frame);
                        pthread_mutex_lock(&context->lock);
                        context->last_video_frame_ns = os_gettime_ns();
                        pthread_mutex_unlock(&context->lock);
                    }

                    /* Calculate end-to-end latency: OBS receive time minus
                     * Android capture time.  The capture PTS is in microseconds
                     * (System.nanoTime()/1000).  We convert to ns and compare
                     * with the recv_time_ns captured when the header arrived. */
                    if (pts != H264_NO_PTS && pts > 0) {
                        uint64_t capture_ns = (uint64_t)pts * 1000ULL;
                        if (recv_time_ns > capture_ns) {
                            double lat = (double)(recv_time_ns - capture_ns) / 1000000.0;
                            pthread_mutex_lock(&context->lock);
                            context->latency_ms = lat;
                            pthread_mutex_unlock(&context->lock);
                        }
                    }
                    /* Update status text with latency every 500 ms */
                    {
                        uint64_t now_ns = os_gettime_ns();
                        if (now_ns - context->last_latency_update_ns >= 500000000ULL) {
                            context->last_latency_update_ns = now_ns;
                            double lat;
                            int fw, fh;
                            pthread_mutex_lock(&context->lock);
                            lat = context->latency_ms;
                            fw  = frame->width;
                            fh  = frame->height;
                            pthread_mutex_unlock(&context->lock);
                            /* Build status — write through the setter so the
                             * 📡 Status property text field stays in sync. */
                            char status[128];
                            snprintf(status, sizeof(status),
                                     "Connected %s:%d | %dx%d | %.0f ms delay",
                                     host, port, fw, fh, lat);
                            uvc_custom_network_set_status(context, "%s", status);
                            /* Also update the source name in the OBS list */
                            char name_buf[128];
                            snprintf(name_buf, sizeof(name_buf),
                                     "📱 %s:%d  %dx%d  %.0fms",
                                     host, port, fw, fh, lat);
                            pthread_mutex_lock(&context->lock);
                            bfree(context->source_display_name);
                            context->source_display_name = bstrdup(name_buf);
                            pthread_mutex_unlock(&context->lock);
                        }
                    }

                    if (!logged_first) {
                        blog(LOG_INFO, "UVC H265 TCP: first decoded frame %dx%d pixfmt=%d codec=%s",
                             frame->width, frame->height, frame->format,
                             active_codec_name ? active_codec_name : "sw");
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

    pthread_mutex_lock(&context->lock);
    bool added = uvc_custom_network_add_discovered_device(context, host, port);
    int count = context->discovered_device_count;

    /* Auto-fill ONLY if this source has no host set yet AND no other source
     * in the scene collection already claimed this device.  This prevents
     * multiple sources from all grabbing the first discovered device. */
    bool host_was_empty = (!context->host || *context->host == '\0');
    if (host_was_empty) {
        bfree(context->host);
        context->host = bstrdup(host);
        context->port = port;
        if (context->selected_device_index < 0) {
            context->selected_device_index = 0;
        }
        /* Update the display name so the source is identifiable immediately */
        bfree(context->source_display_name);
        char name_buf[128];
        snprintf(name_buf, sizeof(name_buf), "📱 %s:%d (new)", host, port);
        context->source_display_name = bstrdup(name_buf);
        /* Signal video_tick to persist the auto-filled host/port to settings
         * so the source can auto-start on next OBS launch. */
        context->pending_discovery_save = true;
        context->pending_ui_refresh = true;
    }

    if (added) {
        blog(LOG_INFO, "UVC Custom Network: discovered device %s:%d", host, port);
        if (count == 1) {
            uvc_custom_network_set_status(context, "📡 Found %s:%d — press ▶ Activate to connect", host, port);
        } else {
            uvc_custom_network_set_status(context, "📡 Found %d devices — pick one & press ▶ Activate", count);
        }
        /* Discovery only populates the list.  The user must press Activate
         * to start the connection — auto-connect was removed here to give
         * the user full control. */
    }

    pthread_mutex_unlock(&context->lock);
}

static void uvc_custom_network_start_receiver(uvc_custom_network *context)
{
    if (!context || context->receiver_running || context->port <= 0) {
        return;
    }

    blog(LOG_INFO, "UVC H265 TCP: starting receiver thread (will connect to %s:%d)",
         context->host ? context->host : "(none)", context->port);

    /* Set up persistent tally socket so video_tick can send tally
     * independently of video frame arrival. */
    uvc_custom_network_tally_update_addr(context);

    /* Send initial CONTROL from the main thread BEFORE the receiver thread
     * starts.  This avoids a race on context->host (send_control reads it
     * without the lock) and ensures CONTROL reaches Android immediately,
     * before any FFmpeg/socket setup delay in the background thread. */
    {
        bool exp_lock, fcs_lock, af_lock;
        int exp_comp, af_md, fl_md, wb_md, wb_k, res_idx, fps_v, qual, bitr;
        pthread_mutex_lock(&context->lock);
        exp_lock = context->control_exposure_lock;
        fcs_lock = context->control_focus_lock;
        exp_comp = context->control_exposure_compensation;
        af_md    = context->control_af_mode;
        af_lock  = context->control_af_lock;
        fl_md    = context->control_flash_mode;
        wb_md    = context->control_wb_mode;
        wb_k     = context->control_wb_kelvin;
        res_idx  = context->resolution_index;
        fps_v    = context->fps;
        qual     = context->quality;
        bitr     = context->bitrate;
        pthread_mutex_unlock(&context->lock);
        uvc_custom_network_send_control(context,
            exp_lock, fcs_lock, exp_comp,
            af_md, af_lock, fl_md, wb_md, wb_k,
            res_idx, fps_v, qual, bitr);
    }

    context->receiver_running = true;
    context->last_video_frame_ns = os_gettime_ns();
#ifdef _WIN32
    context->receiver_socket = INVALID_SOCKET;
#else
    context->receiver_socket = -1;
#endif
    pthread_create(&context->receiver_thread, NULL, uvc_custom_network_receiver_thread, context);
    /* control_state_start is already called by send_control() above */
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
    context->bitrate = (int)obs_data_get_int(settings, "bitrate");
    context->discovery_enabled = obs_data_get_bool(settings, "discovery_enabled");
    context->control_exposure_lock = obs_data_get_bool(settings, "exposure_lock");
    context->control_focus_lock = obs_data_get_bool(settings, "focus_lock");
    context->control_exposure_compensation = (int)obs_data_get_int(settings, "exposure_compensation");
    context->control_af_mode = (int)obs_data_get_int(settings, "af_mode");
    context->control_af_lock = obs_data_get_bool(settings, "af_lock");
    context->control_flash_mode = (int)obs_data_get_int(settings, "flash_mode");
    context->control_wb_mode = (int)obs_data_get_int(settings, "wb_mode");
    context->control_wb_kelvin = (int)obs_data_get_int(settings, "wb_kelvin");
    context->suppress_next_control_send = false;
    context->pending_remote_control_state = false;
    context->pending_exposure_lock = context->control_exposure_lock;
    context->pending_focus_lock = context->control_focus_lock;
    context->pending_exposure_compensation = context->control_exposure_compensation;
    context->pending_af_mode = context->control_af_mode;
    context->pending_af_lock = context->control_af_lock;
    context->pending_flash_mode = context->control_flash_mode;
    context->pending_wb_mode = context->control_wb_mode;
    context->pending_wb_kelvin = context->control_wb_kelvin;
    context->pending_resolution_index = context->resolution_index;
    context->pending_fps = context->fps;
    context->pending_quality = context->quality;
    context->pending_bitrate = context->bitrate;
    context->discovery = NULL;
#ifdef _WIN32
    context->receiver_socket = INVALID_SOCKET;
    context->control_state_socket = INVALID_SOCKET;
#else
    context->receiver_socket = -1;
    context->control_state_socket = -1;
#endif
    context->receiver_running = false;
    context->control_state_running = false;
    context->width = 0;
    context->height = 0;
    context->latency_ms = 0.0;
    context->last_latency_update_ns = 0;
    context->last_video_frame_ns = 0;
    context->source_display_name = bstrdup("UVC Custom Network (idle)");
    context->last_name_update_ns = 0;
    context->srt_receiver_running = false;
    context->srt_port = SRT_DEFAULT_PORT;
    context->use_srt = obs_data_get_bool(settings, "use_srt");
    {
        int saved_srt_port = (int)obs_data_get_int(settings, "srt_port");
        if (saved_srt_port > 0 && saved_srt_port <= 65535) {
            context->srt_port = saved_srt_port;
        }
    }
    context->configured_srt_port = context->srt_port;
    context->srt_latency_ms = (int)obs_data_get_int(settings, "srt_latency_ms");
    if (context->srt_latency_ms < 20) context->srt_latency_ms = 20;
    if (context->srt_latency_ms > 5000) context->srt_latency_ms = 5000;
#ifdef _WIN32
    context->srt_receiver_socket = INVALID_SOCKET;
#else
    context->srt_receiver_socket = -1;
#endif
    context->tally_program = false;
    context->tally_preview = false;
    context->last_tally_send_ns = 0;
    context->last_remote_apply_ns = 0;
    context->last_obs_control_send_ns = 0;
    context->last_props_refresh_ns = 0;
    context->destroying = false;
    context->pending_ui_refresh = false;
    context->pending_discovery_save = false;
    context->user_activated = false;
#ifdef _WIN32
    context->tally_socket = INVALID_SOCKET;
#else
    context->tally_socket = -1;
#endif
    memset(&context->tally_addr, 0, sizeof(context->tally_addr));
    context->tally_addr_valid = false;
#ifdef _WIN32
    context->main_thread_id = GetCurrentThreadId();
#endif
    context->hw_decode = obs_data_get_bool(settings, "hw_decode");
    context->discovery_status = bstrdup("Idle");

    if (context->discovery_enabled) {
        context->discovery = network_discovery_create("uvc_custom_network", CUSTOM_DISCOVERY_PORT,
                                                    uvc_custom_network_discovery_callback, context);
        network_discovery_start(context->discovery);
    }

    // Auto-start receiver if phone IP and port are already configured.
    // For SRT mode the relevant port is srt_port; for TCP it's port.
    bool has_host = (context->host && context->host[0] != '\0');
    bool can_start = context->use_srt
        ? (has_host && context->srt_port > 0)
        : (has_host && context->port > 0);
    if (can_start) {
        context->user_activated = true; /* saved settings = user intended this */
        if (context->use_srt) {
            blog(LOG_INFO, "UVC SRT: auto-starting receiver (listening on UDP port %d from %s)",
                 context->srt_port, context->host);
            uvc_custom_network_srt_receiver_start(context);
        } else {
            blog(LOG_INFO, "UVC H265 TCP: auto-starting receiver (connecting to %s:%d)",
                 context->host, context->port);
            uvc_custom_network_start_receiver(context);
        }
    }

    return context;
}

static void uvc_custom_network_destroy(void *data)
{
    uvc_custom_network *context = (uvc_custom_network *)data;
    if (!context) return;

    /* Signal video_tick to stop making OBS API calls before we free anything. */
    context->destroying = true;

    if (context->discovery) {
        network_discovery_destroy(context->discovery);
    }
    uvc_custom_network_receiver_stop(context);
    uvc_custom_network_srt_receiver_stop(context);
    uvc_custom_network_control_state_stop(context); /* safety: ensure thread is dead */
    uvc_custom_network_tally_close_socket(context);

    uvc_custom_network_clear_discovered_devices(context);
    bfree(context->discovery_status);
    bfree(context->source_display_name);
    bfree(context->host);
    pthread_mutex_destroy(&context->lock);
    bfree(context);
}

static obs_properties_t *uvc_custom_network_properties(void *data)
{
    uvc_custom_network *context = (uvc_custom_network *)data;
    obs_properties_t *props = obs_properties_create();
    obs_property_t *p;
    obs_properties_t *group;
    bool is_active = (context && (context->receiver_running || context->srt_receiver_running));

    /* ── Status bar: connection state + resolution + delay ── */
    p = obs_properties_add_text(props, "discovery_status", "Status", OBS_TEXT_INFO);
    if (p && context) {
        if (is_active) {
            char label[160];
            double lat;
            pthread_mutex_lock(&context->lock);
            lat = context->latency_ms;
            pthread_mutex_unlock(&context->lock);
            snprintf(label, sizeof(label), "Connected  %s:%d  %s  %dx%d  %.0fms",
                     context->host ? context->host : "?",
                     context->use_srt ? context->srt_port : context->port,
                     context->use_srt ? "SRT" : "TCP",
                     (int)(context->width > 0 ? context->width : 1280),
                     (int)(context->height > 0 ? context->height : 720),
                     lat);
            obs_property_set_long_description(p, label);
        } else if (context->host && context->host[0] != '\0') {
            obs_property_set_long_description(p, "Connecting...");
        } else {
            obs_property_set_long_description(p, "Pick a device below then press Activate");
        }
    }

    /* ═══════════════════════════════════════════════════════════════
     * ▸ Connection
     * ═══════════════════════════════════════════════════════════════ */
    group = obs_properties_create();
    obs_properties_add_group(props, "grp_connection", "Connection", OBS_GROUP_NORMAL, group);

    p = obs_properties_add_list(group, "selected_device_index",
            "Discovered devices", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    if (p) {
        obs_property_set_modified_callback(p, uvc_custom_network_device_selected);
        if (is_active) obs_property_set_enabled(p, false);
        obs_property_list_add_int(p, is_active ? "(stop first to change)" : "(auto-detect)", -1);
        if (context && context->discovered_device_count > 0) {
            for (int i = 0; i < context->discovered_device_count; i++) {
                char entry[160];
                bool is_this = (context->host && context->discovered_devices[i].host
                        && strcmp(context->host, context->discovered_devices[i].host) == 0
                        && context->port == context->discovered_devices[i].port);
                snprintf(entry, sizeof(entry), "%s  %s",
                         context->discovered_devices[i].label,
                         is_this ? "(active)" : "");
                obs_property_list_add_int(p, entry, i);
            }
        } else {
            obs_property_list_add_int(p, "No devices discovered yet", -1);
            if (!is_active) obs_property_set_enabled(p, false);
        }
    }

    p = obs_properties_add_text(group, "host", "Phone IP", OBS_TEXT_DEFAULT);
    if (is_active) obs_property_set_enabled(p, false);

    p = obs_properties_add_int(group, "port", "Port", 1024, 65535, 1);
    if (is_active) obs_property_set_enabled(p, false);

    p = obs_properties_add_bool(group, "use_srt", "Use SRT (UDP)");
    if (is_active) obs_property_set_enabled(p, false);

    p = obs_properties_add_int(group, "srt_port", "SRT port", 1024, 65535, 1);
    if (is_active) obs_property_set_enabled(p, false);

    if (context && context->srt_receiver_running) {
        p = obs_properties_add_text(group, "srt_bound_port", "Listening on", OBS_TEXT_INFO);
        if (p) {
            char bound[32];
            snprintf(bound, sizeof(bound), "UDP :%d", context->srt_port);
            obs_property_set_long_description(p, bound);
        }
    }

    p = obs_properties_add_button2(group, "activate",
        is_active ? "Stop" : "Activate",
        (obs_property_clicked_t)uvc_custom_network_activate_button, context);

    p = obs_properties_add_button2(group, "refresh_discovery", "Refresh discovery",
                                   (obs_property_clicked_t)uvc_custom_network_refresh_button, context);

    /* ═══════════════════════════════════════════════════════════════
     * ▸ Video
     * ═══════════════════════════════════════════════════════════════ */
    group = obs_properties_create();
    obs_properties_add_group(props, "grp_video", "Video", OBS_GROUP_NORMAL, group);

    p = obs_properties_add_list(group, "resolution_index", "Resolution",
            OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    for (int i = 0; i < (int)(sizeof(RESOLUTIONS) / sizeof(RESOLUTIONS[0])); i++) {
        obs_property_list_add_int(p, RESOLUTIONS[i], i);
    }

    obs_properties_add_int(group, "fps", "FPS", 15, 60, 1);
    obs_properties_add_int(group, "quality", "Quality", 1, 100, 1);
    obs_properties_add_int(group, "bitrate", "Bitrate (kbps, 0=auto)", 0, 100000, 100);
    obs_properties_add_bool(group, "hw_decode", "Hardware decoding");

    p = obs_properties_add_int(group, "srt_latency_ms", "SRT buffer (ms)", 20, 5000, 10);
    obs_property_set_long_description(p, "Higher = smoother, lower = less delay (recommended: 80–200)");

    /* ═══════════════════════════════════════════════════════════════
     * ▸ Camera
     * ═══════════════════════════════════════════════════════════════ */
    group = obs_properties_create();
    obs_properties_add_group(props, "grp_camera", "Camera", OBS_GROUP_NORMAL, group);

    obs_properties_add_bool(group, "exposure_lock", "Exposure lock");
    obs_properties_add_int(group, "exposure_compensation", "EV compensation", -10, 10, 1);
    obs_properties_add_bool(group, "focus_lock", "Focus lock");

    p = obs_properties_add_list(group, "af_mode", "AF mode", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(p, "Off", 0);
    obs_property_list_add_int(p, "Auto", 1);
    obs_property_list_add_int(p, "Continuous", 2);
    obs_property_list_add_int(p, "Tap", 3);
    obs_property_list_add_int(p, "Infinity", 4);
    obs_property_list_add_int(p, "Macro", 5);

    obs_properties_add_bool(group, "af_lock", "AF lock");

    p = obs_properties_add_list(group, "flash_mode", "Flash", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(p, "Auto", 0);
    obs_property_list_add_int(p, "On", 1);
    obs_property_list_add_int(p, "Off", 2);
    obs_property_list_add_int(p, "Torch", 3);

    p = obs_properties_add_list(group, "wb_mode", "White balance", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(p, "Auto", 0);
    obs_property_list_add_int(p, "Incandescent", 1);
    obs_property_list_add_int(p, "Fluorescent", 2);
    obs_property_list_add_int(p, "Daylight", 3);
    obs_property_list_add_int(p, "Cloudy", 4);
    obs_property_list_add_int(p, "Shade", 5);
    obs_property_list_add_int(p, "Kelvin", 6);

    obs_properties_add_int(group, "wb_kelvin", "Kelvin", 1000, 10000, 100);

    /* ═══════════════════════════════════════════════════════════════
     * ▸ Advanced
     * ═══════════════════════════════════════════════════════════════ */
    group = obs_properties_create();
    obs_properties_add_group(props, "grp_advanced", "Advanced", OBS_GROUP_NORMAL, group);

    obs_properties_add_bool(group, "discovery_enabled", "Network discovery");

    return props;
}

static void uvc_custom_network_update(void *data, obs_data_t *settings)
{
    uvc_custom_network *context = (uvc_custom_network *)data;
    network_discovery_t *old_discovery = NULL;
    bool need_discovery_start = false;
    bool need_receiver_restart = false;
    char previous_host[64] = {0};
    int previous_port = 0;

    pthread_mutex_lock(&context->lock);

    if (context->host && context->host[0] != '\0') {
        strncpy(previous_host, context->host, sizeof(previous_host) - 1);
    }
    previous_port = context->port;

    const char *new_host = obs_data_get_string(settings, "host");
    bool host_changed = false;
    if (!new_host) {
        new_host = "";
    }
    if ((context->receiver_running || context->srt_receiver_running) && new_host[0] == '\0' && previous_host[0] != '\0') {
        /* When OBS rebuilds/reloads the properties view, transient blank host
         * values can be submitted with unrelated control changes.  Preserve the
         * active target so a checkbox toggle cannot silently clear the host and
         * restart the receiver. */
        obs_data_set_string(settings, "host", previous_host);
        new_host = previous_host;
    }
    if (!context->host || strcmp(context->host, new_host) != 0) {
        bfree(context->host);
        context->host = bstrdup(new_host);
        host_changed = true;
    }

    int new_port = (int)obs_data_get_int(settings, "port");
    if ((context->receiver_running || context->srt_receiver_running) && new_port <= 0 && previous_port > 0) {
        obs_data_set_int(settings, "port", previous_port);
        new_port = previous_port;
    }
    bool port_changed = new_port != context->port;
    context->port = new_port;

    int selected_device_index = (int)obs_data_get_int(settings, "selected_device_index");
    if (selected_device_index != context->selected_device_index) {
        context->selected_device_index = selected_device_index;
        if (selected_device_index >= 0 && selected_device_index < context->discovered_device_count) {
            const char *new_device_host = context->discovered_devices[selected_device_index].host;
            int new_device_port = context->discovered_devices[selected_device_index].port;
            obs_data_set_string(settings, "host", new_device_host);
            obs_data_set_int(settings, "port", new_device_port);
            if (!context->host || strcmp(context->host, new_device_host) != 0) {
                bfree(context->host);
                context->host = bstrdup(new_device_host);
                host_changed = true;
            }
            if (context->port != new_device_port) {
                port_changed = true;
            }
            context->port = new_device_port;
            /* Signal video_tick to rebuild the dialog so Phone IP /
             * Port fields reflect the selection immediately. */
            context->pending_ui_refresh = true;
        }
    }

    /* SRT toggle and port */
    bool use_srt = obs_data_get_bool(settings, "use_srt");
    int srt_port = (int)obs_data_get_int(settings, "srt_port");
    bool srt_changed = (use_srt != context->use_srt) || (srt_port != context->configured_srt_port);
    context->use_srt = use_srt;

    bool hw_decode = obs_data_get_bool(settings, "hw_decode");
    bool hw_decode_changed = (hw_decode != context->hw_decode);
    context->hw_decode = hw_decode;

    if (srt_port > 0 && srt_port <= 65535) {
        context->configured_srt_port = srt_port;
        /* Only overwrite the live port if the receiver is NOT running.
         * When running, srt_port was set by auto-bind and may differ. */
        if (!context->srt_receiver_running) {
            context->srt_port = srt_port;
        }
    }

    int srt_latency_ms = (int)obs_data_get_int(settings, "srt_latency_ms");
    if (srt_latency_ms < 20) srt_latency_ms = 20;
    if (srt_latency_ms > 5000) srt_latency_ms = 5000;
    context->srt_latency_ms = srt_latency_ms;

    int resolution_index = (int)obs_data_get_int(settings, "resolution_index");
    int fps = (int)obs_data_get_int(settings, "fps");
    int quality = (int)obs_data_get_int(settings, "quality");
    int bitrate = (int)obs_data_get_int(settings, "bitrate");
    bool stream_settings_changed = resolution_index != context->resolution_index
        || fps != context->fps
        || quality != context->quality
        || bitrate != context->bitrate;
    context->resolution_index = resolution_index;
    context->fps = fps;
    context->quality = quality;
    context->bitrate = bitrate;
    bool exposure_lock = obs_data_get_bool(settings, "exposure_lock");
    bool focus_lock = obs_data_get_bool(settings, "focus_lock");
    int exposure_compensation = (int)obs_data_get_int(settings, "exposure_compensation");
    int af_mode = (int)obs_data_get_int(settings, "af_mode");
    bool af_lock = obs_data_get_bool(settings, "af_lock");
    int flash_mode = (int)obs_data_get_int(settings, "flash_mode");
    int wb_mode = (int)obs_data_get_int(settings, "wb_mode");
    int wb_kelvin = (int)obs_data_get_int(settings, "wb_kelvin");

    bool send_control = false;
    bool suppress_control_send = context->suppress_next_control_send;
    if (suppress_control_send) {
        context->suppress_next_control_send = false;
    }
        if (stream_settings_changed
            || exposure_lock != context->control_exposure_lock || focus_lock != context->control_focus_lock || exposure_compensation != context->control_exposure_compensation
            || af_mode != context->control_af_mode || af_lock != context->control_af_lock || flash_mode != context->control_flash_mode
            || wb_mode != context->control_wb_mode || wb_kelvin != context->control_wb_kelvin) {
        context->control_exposure_lock = exposure_lock;
        context->control_focus_lock = focus_lock;
        context->control_exposure_compensation = exposure_compensation;
        context->control_af_mode = af_mode;
        context->control_af_lock = af_lock;
        context->control_flash_mode = flash_mode;
        context->control_wb_mode = wb_mode;
        context->control_wb_kelvin = wb_kelvin;
        send_control = true;
        /* Record the OBS-authority timestamp so video_tick suppresses Android
         * echo-state for OBS_CONTROL_AUTHORITY_NS after this send. */
        context->last_obs_control_send_ns = os_gettime_ns();
    }

    bool discovery_enabled = obs_data_get_bool(settings, "discovery_enabled");

    if (discovery_enabled && !context->discovery) {
        need_discovery_start = true;
    } else if (!discovery_enabled && context->discovery) {
        old_discovery = context->discovery;
        context->discovery = NULL;
    }

    if ((port_changed || host_changed || srt_changed || hw_decode_changed) && (context->receiver_running || context->srt_receiver_running)) {
        need_receiver_restart = true;
    }

    pthread_mutex_unlock(&context->lock);

    blog(LOG_INFO,
         "UVC update: prev_target=%s:%d new_target=%s:%d host_changed=%d port_changed=%d srt_changed=%d stream_changed=%d send_control=%d suppress=%d restart=%d use_srt=%d",
         previous_host[0] != '\0' ? previous_host : "(none)",
         previous_port,
         context->host && context->host[0] != '\0' ? context->host : "(none)",
         context->port,
         host_changed ? 1 : 0,
         port_changed ? 1 : 0,
         srt_changed ? 1 : 0,
         stream_settings_changed ? 1 : 0,
         send_control ? 1 : 0,
         suppress_control_send ? 1 : 0,
         need_receiver_restart ? 1 : 0,
         context->use_srt ? 1 : 0);

    // Do blocking operations OUTSIDE the lock to avoid deadlock with callback threads
    if (old_discovery) {
        network_discovery_destroy(old_discovery);
    }

    /* Update the persistent tally socket destination when the host changes,
     * even if the receiver is not currently running. */
    if (host_changed) {
        uvc_custom_network_tally_update_addr(context);
    }

    if (need_receiver_restart) {
        uvc_custom_network_receiver_stop(context);
        uvc_custom_network_srt_receiver_stop(context);
        pthread_mutex_lock(&context->lock);
        if (context->port > 0 || context->srt_port > 0) {
            if (context->use_srt) {
                uvc_custom_network_srt_receiver_start(context);
            } else {
                uvc_custom_network_start_receiver(context);
            }
        }
        pthread_mutex_unlock(&context->lock);
    }

    if (need_discovery_start) {
        pthread_mutex_lock(&context->lock);
        context->discovery = network_discovery_create("uvc_custom_network", CUSTOM_DISCOVERY_PORT,
                                                    uvc_custom_network_discovery_callback, context);
        network_discovery_start(context->discovery);
        pthread_mutex_unlock(&context->lock);
    }

    if (send_control && !suppress_control_send && context->host && context->host[0] != '\0') {
        /* Pass captured local values -- avoids race where video_tick could
         * overwrite context->control_* between here and send_control() reading them. */
        uvc_custom_network_send_control(context,
                                        exposure_lock, focus_lock,
                                        exposure_compensation, af_mode, af_lock,
                                        flash_mode, wb_mode, wb_kelvin,
                                        resolution_index, fps, quality, bitrate);
    } else if (send_control && suppress_control_send) {
        blog(LOG_INFO, "UVC CONTROL skipped because suppress_next_control_send was set");
    } else if (send_control) {
        blog(LOG_WARNING, "UVC CONTROL skipped because target host is empty");
    }

    UNUSED_PARAMETER(host_changed);
}

static const char *uvc_custom_network_get_name(void *data)
{
    uvc_custom_network *context = (uvc_custom_network *)data;
    if (context && context->source_display_name && context->source_display_name[0] != '\0') {
        return context->source_display_name;
    }
    return "UVC Custom Network (idle)";
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
    obs_data_set_default_bool(settings, "hw_decode", true);
    obs_data_set_default_bool(settings, "exposure_lock", false);
    obs_data_set_default_bool(settings, "focus_lock", false);
    obs_data_set_default_int(settings, "exposure_compensation", 0);
    obs_data_set_default_int(settings, "af_mode", 2);
    obs_data_set_default_bool(settings, "af_lock", false);
    obs_data_set_default_int(settings, "flash_mode", 2);
    obs_data_set_default_int(settings, "wb_mode", 0);
    obs_data_set_default_int(settings, "wb_kelvin", 4500);
    obs_data_set_default_int(settings, "port", 5600);
    obs_data_set_default_bool(settings, "use_srt", false);
    obs_data_set_default_int(settings, "srt_port", SRT_DEFAULT_PORT);
    obs_data_set_default_int(settings, "srt_latency_ms", 120);
    obs_data_set_default_int(settings, "fps", 30);
    obs_data_set_default_int(settings, "quality", 50);
    obs_data_set_default_int(settings, "bitrate", 0);
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
    uvc_custom_network_info.video_tick = uvc_custom_network_video_tick;
    uvc_custom_network_info.activate = uvc_custom_network_activate;
    uvc_custom_network_info.deactivate = uvc_custom_network_deactivate;

    obs_register_source(&uvc_custom_network_info);
    blog(LOG_INFO, "Loaded UVC Custom Network OBS plugin");
    return true;
}

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("uvc-custom-network", "en-US")
