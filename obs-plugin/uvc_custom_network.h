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

    char *discovery_status;
    network_discovery_t *discovery;

    bool receiver_running;
    pthread_t receiver_thread;
#ifdef _WIN32
    SOCKET receiver_socket;
#else
    int receiver_socket;
#endif

    // last known frame dimensions (updated on each received frame)
    uint32_t width;
    uint32_t height;
};

obs_source_info *get_uvc_custom_network_info();
