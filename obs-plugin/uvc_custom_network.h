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

    // last sent tally state (OBS -> Android UDP backchannel)
    bool tally_program;
    bool tally_preview;
    uint64_t last_tally_send_ns;
    uint64_t last_remote_apply_ns;
};

obs_source_info *get_uvc_custom_network_info();
