#pragma once

#include <obs-module.h>
#include <obs-source.h>
#include <graphics/graphics.h>
#include <util/threading.h>
#include <stdint.h>
#include "net_discovery.h"

#define MAX_DISCOVERED_DEVICES 16

struct discovered_device {
    char *host;
    int port;
    char *label;
};

struct uvc_custom_network {
    obs_source_t *source;
    pthread_mutex_t lock;

    char *host;
    int port;
    int selected_device_index;
    struct discovered_device discovered_devices[MAX_DISCOVERED_DEVICES];
    int discovered_device_count;
    int resolution_index;
    int fps;
    int quality;
    int bitrate;
    bool discovery_enabled;

    bool control_exposure_lock;
    bool control_focus_lock;
    int control_exposure_compensation;
    int control_af_mode;
    bool control_af_lock;
    int control_flash_mode;
    int control_wb_mode;
    int control_wb_kelvin;
    bool suppress_next_control_send;

    bool pending_remote_control_state;
    bool pending_exposure_lock;
    bool pending_focus_lock;
    int pending_exposure_compensation;
    int pending_af_mode;
    bool pending_af_lock;
    int pending_flash_mode;
    int pending_wb_mode;
    int pending_wb_kelvin;
    int pending_resolution_index;
    int pending_fps;
    int pending_quality;
    int pending_bitrate;

    char *discovery_status;
    network_discovery_t *discovery;

    bool receiver_running;
    pthread_t receiver_thread;
#ifdef _WIN32
    SOCKET receiver_socket;
    SOCKET control_state_socket;
#else
    int receiver_socket;
    int control_state_socket;
#endif
    bool control_state_running;
    pthread_t control_state_thread;

    // last known frame dimensions (updated on each received frame)
    uint32_t width;
    uint32_t height;

    // latency tracking (ms) — displayed in discovery_status
    double latency_ms;
    uint64_t last_latency_update_ns;

    // timestamp of the last received decoded frame or output video
    uint64_t last_video_frame_ns;

    // dynamic source name (shown in OBS sources list)
    char *source_display_name;
    uint64_t last_name_update_ns;

    // SRT receiver (UDP) — separate from TCP receiver
    bool srt_receiver_running;
    pthread_t srt_receiver_thread;
#ifdef _WIN32
    SOCKET srt_receiver_socket;
#else
    int srt_receiver_socket;
#endif
    int srt_port;
    bool use_srt; // true when source is configured as SRT
    int configured_srt_port; // user-configured port (srt_port may differ after auto-bind)
    int srt_latency_ms; // frame buffer latency (default 120 ms)

    // last sent tally state (OBS -> Android UDP backchannel)
    bool tally_program;
    bool tally_preview;
    uint64_t last_tally_send_ns;
    uint64_t last_remote_apply_ns;

    // Persistent tally UDP socket + destination — created once and reused
    // from video_tick so tally is sent independently of video frame arrival.
#ifdef _WIN32
    SOCKET tally_socket;
#else
    int tally_socket;
#endif
    struct sockaddr_in tally_addr;
    bool tally_addr_valid;

    /* Timestamp of the last CONTROL packet sent from OBS to Android.
     * While within OBS_CONTROL_AUTHORITY_NS of this timestamp, incoming
     * Android CONTROL_STATE echoes are suppressed so they do not overwrite
     * the user's intended change before Android has a chance to apply it. */
    uint64_t last_obs_control_send_ns;

    /* Rate-limits obs_source_update_properties() calls from the video tick. */
    uint64_t last_props_refresh_ns;

    /* Set to true at the top of destroy() so video_tick skips unsafe OBS API calls. */
    volatile bool destroying;

    /* Set by button callbacks when the UI needs a dialog rebuild.
     * video_tick picks this up and calls obs_source_update_properties. */
    volatile bool pending_ui_refresh;

    /* Set by the discovery callback when it auto-fills host/port so that
     * video_tick can persist them to the source settings on the main thread. */
    volatile bool pending_discovery_save;

    /* Set to true when the user pressed Activate; false after Stop.
     * Prevents scene-switch deactivation from stopping the receiver. */
    bool user_activated;

    /* Cached main-thread ID for safe OBS API calls. */
    unsigned long main_thread_id;

    /* Hardware decode toggle (on = try DXVA2/D3D11VA/QSV/NVDEC, off = software only) */
    bool hw_decode;
};

obs_source_info *get_uvc_custom_network_info();
