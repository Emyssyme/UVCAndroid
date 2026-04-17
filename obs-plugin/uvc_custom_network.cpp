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
static const int CUSTOM_TALLY_PORT = 8867;

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
static void uvc_custom_network_discovery_callback(const char *host, int port, void *userdata);
static void uvc_custom_network_send_control(uvc_custom_network *context,
                                            bool exposure_lock, bool focus_lock,
                                            int exposure_compensation, int af_mode, bool af_lock,
                                            int flash_mode, int wb_mode, int wb_kelvin,
                                            int resolution_index, int fps, int quality);
static void *uvc_custom_network_receiver_thread(void *data);
static void *uvc_custom_network_control_state_thread(void *data);
static void uvc_custom_network_control_state_start(uvc_custom_network *context);
static void uvc_custom_network_control_state_stop(uvc_custom_network *context);
static void uvc_custom_network_apply_remote_control_state(uvc_custom_network *context, const char *msg);
static void uvc_custom_network_video_tick(void *data, float seconds);
static void uvc_custom_network_send_tally(uvc_custom_network *context,
#ifdef _WIN32
                                          SOCKET tally_socket,
#else
                                          int tally_socket,
#endif
                                          const struct sockaddr_in *tally_addr,
                                          bool force_send);

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

    if (context->source) {
        obs_data_t *settings = obs_source_get_settings(context->source);
        if (settings) {
            obs_properties_apply_settings(props, settings);
            obs_source_update(context->source, settings);
            obs_data_release(settings);
        }
    }

    bool was_running = context->receiver_running;
    bool has_target = (context->port > 0) && (context->host && context->host[0] != '\0');

    if (was_running) {
        uvc_custom_network_receiver_stop(context);
        uvc_custom_network_set_status(context, "Stopped");
        return true;
    }

    if (!has_target) {
        uvc_custom_network_set_status(context, "Enter Phone IP and port first");
        return true;
    }

    uvc_custom_network_start_receiver(context);
    uvc_custom_network_set_status(context, "Connecting to %s:%d (H.265 TCP)", context->host, context->port);
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

static void uvc_custom_network_send_tally(uvc_custom_network *context,
#ifdef _WIN32
                                          SOCKET tally_socket,
#else
                                          int tally_socket,
#endif
                                          const struct sockaddr_in *tally_addr,
                                          bool force_send)
{
    if (!context || !tally_addr) {
        return;
    }
#ifdef _WIN32
    if (tally_socket == INVALID_SOCKET) {
#else
    if (tally_socket < 0) {
#endif
        return;
    }

    bool program = obs_source_active(context->source);
    bool showing = obs_source_showing(context->source);
    bool preview = showing && !program;

    uint64_t now_ns = os_gettime_ns();
    bool changed = (program != context->tally_program) || (preview != context->tally_preview);
    bool stale = (now_ns - context->last_tally_send_ns) >= 1000000000ULL;
    if (!force_send && !changed && !stale) {
        return;
    }

    char payload[64];
    snprintf(payload, sizeof(payload), "TALLY;program=%d;preview=%d", program ? 1 : 0, preview ? 1 : 0);

#ifdef _WIN32
    sendto(tally_socket, payload, (int)strlen(payload), 0,
           (const struct sockaddr *)tally_addr, sizeof(*tally_addr));
#else
    sendto(tally_socket, payload, strlen(payload), 0,
           (const struct sockaddr *)tally_addr, sizeof(*tally_addr));
#endif

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
                                            int resolution_index, int fps, int quality)
{
    if (!context || !context->host || context->host[0] == '\0') {
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
        return;
    }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)CUSTOM_TALLY_PORT);
    if (inet_pton(AF_INET, host_copy, &addr.sin_addr) <= 0) {
#ifdef _WIN32
        closesocket(sock);
#else
        close(sock);
#endif
        return;
    }

    char payload[320];
    snprintf(payload, sizeof(payload),
             "CONTROL;exposure_lock=%d;focus_lock=%d;exposure_compensation=%d;af_mode=%d;af_lock=%d;flash_mode=%d;wb_mode=%d;wb_kelvin=%d;resolution_index=%d;fps=%d;quality=%d",
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
             quality);

    blog(LOG_INFO, "UVC CONTROL -> Android: %s", payload);

#ifdef _WIN32
    sendto(sock, payload, (int)strlen(payload), 0,
           (const struct sockaddr *)&addr, sizeof(addr));
    closesocket(sock);
#else
    sendto(sock, payload, strlen(payload), 0,
           (const struct sockaddr *)&addr, sizeof(addr));
    close(sock);
#endif
}

static void uvc_custom_network_apply_remote_control_state(uvc_custom_network *context, const char *msg)
{
    if (!context || !msg || strncmp(msg, "CONTROL_STATE;", 14) != 0) {
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

    pthread_mutex_lock(&context->lock);
    resolution_index = context->resolution_index;
    fps = context->fps;
    quality = context->quality;
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
        || context->quality != quality) {
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
    obs_data_release(settings);

    /* Refresh the open Properties dialog so the user sees the latest Android
     * state.  Rate-limited to once per second to avoid rebuilding the dialog
     * while the user is interacting with it.  Only called after the authority
     * window has expired (video_tick already checked that), so OBS-initiated
     * edits are never disrupted by echo-state refreshes. */
    uint64_t now_props = os_gettime_ns();
    if (now_props - context->last_props_refresh_ns >= 1000000000ULL) {
        obs_source_update_properties(context->source);
        context->last_props_refresh_ns = now_props;
    }
}

static void uvc_custom_network_video_tick(void *data, float seconds)
{
    UNUSED_PARAMETER(seconds);
    uvc_custom_network *context = (uvc_custom_network *)data;
    if (!context || context->destroying) {
        return;
    }

    uint64_t now_ns = os_gettime_ns();
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
    context->pending_remote_control_state = false;
    pthread_mutex_unlock(&context->lock);

    uvc_custom_network_apply_pending_state_to_source(context);
    context->last_remote_apply_ns = now_ns;
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

static void uvc_custom_network_receiver_stop(uvc_custom_network *context)
{
    if (!context || !context->receiver_running) {
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
        SOCKET tally_sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (sock == INVALID_SOCKET) {
#else
        int sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        int tally_sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
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

        struct sockaddr_in tally_addr;
        memset(&tally_addr, 0, sizeof(tally_addr));
        tally_addr.sin_family = AF_INET;
        tally_addr.sin_port = htons((uint16_t)CUSTOM_TALLY_PORT);

        if (inet_pton(AF_INET, host, &addr.sin_addr) <= 0 || inet_pton(AF_INET, host, &tally_addr.sin_addr) <= 0) {
            blog(LOG_WARNING, "UVC H265 TCP: invalid phone IP '%s' — check source settings", host);
#ifdef _WIN32
            closesocket(sock);
            if (tally_sock != INVALID_SOCKET) closesocket(tally_sock);
#else
            close(sock);
            if (tally_sock >= 0) close(tally_sock);
#endif
            for (int i = 0; i < 40 && context->receiver_running; i++) os_sleep_ms(50);
            continue;
        }

        if (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
            blog(LOG_INFO, "UVC H265 TCP: connect to %s:%d failed, retrying...", host, port);
#ifdef _WIN32
            closesocket(sock);
            if (tally_sock != INVALID_SOCKET) closesocket(tally_sock);
#else
            close(sock);
            if (tally_sock >= 0) close(tally_sock);
#endif
            for (int i = 0; i < 40 && context->receiver_running; i++) os_sleep_ms(50);
            continue;
        }

        /* TCP_NODELAY: disable Nagle for lowest latency */
        int nodelay = 1;
        setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, (char *)&nodelay, sizeof(nodelay));


        context->receiver_socket = sock;
        blog(LOG_INFO, "UVC H265 TCP: connected to Android %s:%d", host, port);

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
            if (tally_sock != INVALID_SOCKET) closesocket(tally_sock);
#else
            close(sock);
            context->receiver_socket = -1;
            if (tally_sock >= 0) close(tally_sock);
#endif
            for (int i = 0; i < 40 && context->receiver_running; i++) os_sleep_ms(50);
            continue;
        }

        bool logged_first = false;
        uint64_t pts_epoch_ns = 0;   /* anchor: OBS time when first PTS arrived */
        int64_t  pts_epoch_us = 0;   /* anchor: first PTS value (microseconds) */
        uint64_t last_output_timestamp_ns = 0;

        /* inner receive/decode loop */
        while (context->receiver_running) {
            uvc_custom_network_send_tally(context, tally_sock, &tally_addr, false);

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

        /* force send neutral tally on disconnect */
        context->tally_program = true;
        context->tally_preview = true;
        uvc_custom_network_send_tally(context, tally_sock, &tally_addr, true);

        /* close the socket — guard against double-close with receiver_stop */
#ifdef _WIN32
        if (context->receiver_socket != INVALID_SOCKET) {
            closesocket(sock);
            context->receiver_socket = INVALID_SOCKET;
        }
        if (tally_sock != INVALID_SOCKET) {
            closesocket(tally_sock);
        }
#else
        if (context->receiver_socket >= 0) {
            close(sock);
            context->receiver_socket = -1;
        }
        if (tally_sock >= 0) {
            close(tally_sock);
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
            uvc_custom_network_set_status(context, "Discovered 1 device: %s:%d", host, port);
        } else {
            uvc_custom_network_set_status(context, "Discovered %d devices", count);
        }
        if (!context->receiver_running && context->port > 0 && context->host && context->host[0] != '\0') {
            uvc_custom_network_start_receiver(context);
        }
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
    context->receiver_running = true;
#ifdef _WIN32
    context->receiver_socket = INVALID_SOCKET;
#else
    context->receiver_socket = -1;
#endif
    pthread_create(&context->receiver_thread, NULL, uvc_custom_network_receiver_thread, context);
    uvc_custom_network_control_state_start(context);
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
    context->tally_program = false;
    context->tally_preview = false;
    context->last_tally_send_ns = 0;
    context->last_remote_apply_ns = 0;
    context->last_obs_control_send_ns = 0;
    context->last_props_refresh_ns = 0;
    context->destroying = false;
    context->discovery_status = bstrdup("Idle");

    if (context->discovery_enabled) {
        context->discovery = network_discovery_create("uvc_custom_network", CUSTOM_DISCOVERY_PORT,
                                                    uvc_custom_network_discovery_callback, context);
        network_discovery_start(context->discovery);
    }

    // Auto-start receiver if phone IP and port are already configured
    if (context->port > 0 && context->host && context->host[0] != '\0') {
        blog(LOG_INFO, "UVC H265 TCP: auto-starting receiver (connecting to %s:%d)",
             context->host, context->port);
        uvc_custom_network_start_receiver(context);
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

    p = obs_properties_add_list(props, "selected_device_index", "Discovered Device", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    if (p) {
        if (context->discovered_device_count == 0) {
            obs_property_list_add_int(p, "No devices discovered", -1);
            obs_property_set_enabled(p, false);
        } else {
            for (int i = 0; i < context->discovered_device_count; i++) {
                obs_property_list_add_int(p, context->discovered_devices[i].label, i);
            }
        }
    }

    p = obs_properties_add_text(props, "host", "Phone IP", OBS_TEXT_DEFAULT);
    UNUSED_PARAMETER(p);
    p = obs_properties_add_int(props, "port", "Stream Port", 1024, 65535, 1);
    UNUSED_PARAMETER(p);

    p = obs_properties_add_text(props, "discovery_status", "Discovery Status", OBS_TEXT_INFO);
    UNUSED_PARAMETER(p);

    p = obs_properties_add_button(props, "activate", "Activate", (obs_property_clicked_t)uvc_custom_network_activate_button);
    UNUSED_PARAMETER(p);

    p = obs_properties_add_button(props, "refresh_discovery", "Refresh Discovery", (obs_property_clicked_t)uvc_custom_network_refresh_button);
    UNUSED_PARAMETER(p);

    p = obs_properties_add_list(props, "resolution_index", "Resolution", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    for (int i = 0; i < (int)(sizeof(RESOLUTIONS) / sizeof(RESOLUTIONS[0])); i++) {
        obs_property_list_add_int(p, RESOLUTIONS[i], i);
    }

    obs_properties_add_int(props, "fps", "FPS", 15, 60, 1);
    obs_properties_add_int(props, "quality", "Quality", 1, 100, 1);
    obs_properties_add_bool(props, "exposure_lock", "Exposure Lock");
    obs_properties_add_int(props, "exposure_compensation", "Exposure Compensation", -10, 10, 1);
    obs_properties_add_bool(props, "focus_lock", "Focus Lock");
    p = obs_properties_add_list(props, "af_mode", "Autofocus Mode", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    if (p) {
        obs_property_list_add_int(p, "Off", 0);
        obs_property_list_add_int(p, "Auto", 1);
        obs_property_list_add_int(p, "Continuous", 2);
        obs_property_list_add_int(p, "Tap", 3);
        obs_property_list_add_int(p, "Infinity", 4);
        obs_property_list_add_int(p, "Macro", 5);
    }
    obs_properties_add_bool(props, "af_lock", "AF Lock");
    p = obs_properties_add_list(props, "flash_mode", "Flash Mode", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    if (p) {
        obs_property_list_add_int(p, "Auto", 0);
        obs_property_list_add_int(p, "On", 1);
        obs_property_list_add_int(p, "Off", 2);
        obs_property_list_add_int(p, "Torch", 3);
    }
    p = obs_properties_add_list(props, "wb_mode", "White Balance", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    if (p) {
        obs_property_list_add_int(p, "Auto", 0);
        obs_property_list_add_int(p, "Incandescent", 1);
        obs_property_list_add_int(p, "Fluorescent", 2);
        obs_property_list_add_int(p, "Daylight", 3);
        obs_property_list_add_int(p, "Cloudy", 4);
        obs_property_list_add_int(p, "Shade", 5);
        obs_property_list_add_int(p, "Kelvin", 6);
    }
    obs_properties_add_int(props, "wb_kelvin", "White Balance Kelvin", 1000, 10000, 100);
    obs_properties_add_bool(props, "discovery_enabled", "Enable Network Discovery");

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
    if (context->receiver_running && new_host[0] == '\0' && previous_host[0] != '\0') {
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
    if (context->receiver_running && new_port <= 0 && previous_port > 0) {
        obs_data_set_int(settings, "port", previous_port);
        new_port = previous_port;
    }
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

    int resolution_index = (int)obs_data_get_int(settings, "resolution_index");
    int fps = (int)obs_data_get_int(settings, "fps");
    int quality = (int)obs_data_get_int(settings, "quality");
    bool stream_settings_changed = resolution_index != context->resolution_index
        || fps != context->fps
        || quality != context->quality;
    context->resolution_index = resolution_index;
    context->fps = fps;
    context->quality = quality;
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

    if ((port_changed || host_changed) && context->receiver_running) {
        need_receiver_restart = true;
    }

    pthread_mutex_unlock(&context->lock);

    blog(LOG_INFO,
         "UVC update: prev_target=%s:%d new_target=%s:%d host_changed=%d port_changed=%d stream_changed=%d send_control=%d suppress=%d restart=%d",
         previous_host[0] != '\0' ? previous_host : "(none)",
         previous_port,
         context->host && context->host[0] != '\0' ? context->host : "(none)",
         context->port,
         host_changed ? 1 : 0,
         port_changed ? 1 : 0,
         stream_settings_changed ? 1 : 0,
         send_control ? 1 : 0,
         suppress_control_send ? 1 : 0,
         need_receiver_restart ? 1 : 0);

    // Do blocking operations OUTSIDE the lock to avoid deadlock with callback threads
    if (old_discovery) {
        network_discovery_destroy(old_discovery);
    }

    if (need_receiver_restart) {
        uvc_custom_network_receiver_stop(context);
        pthread_mutex_lock(&context->lock);
        if (context->port > 0) {
            uvc_custom_network_start_receiver(context);
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
                                        resolution_index, fps, quality);
    } else if (send_control && suppress_control_send) {
        blog(LOG_INFO, "UVC CONTROL skipped because suppress_next_control_send was set");
    } else if (send_control) {
        blog(LOG_WARNING, "UVC CONTROL skipped because target host is empty");
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
    obs_data_set_default_bool(settings, "exposure_lock", false);
    obs_data_set_default_bool(settings, "focus_lock", false);
    obs_data_set_default_int(settings, "exposure_compensation", 0);
    obs_data_set_default_int(settings, "af_mode", 2);
    obs_data_set_default_bool(settings, "af_lock", false);
    obs_data_set_default_int(settings, "flash_mode", 2);
    obs_data_set_default_int(settings, "wb_mode", 0);
    obs_data_set_default_int(settings, "wb_kelvin", 4500);
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
    uvc_custom_network_info.video_tick = uvc_custom_network_video_tick;

    obs_register_source(&uvc_custom_network_info);
    blog(LOG_INFO, "Loaded UVC Custom Network OBS plugin");
    return true;
}

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("uvc-custom-network", "en-US")
