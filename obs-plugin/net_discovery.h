#pragma once

#ifdef _WIN32
#include <winsock2.h>
#endif

#include <stdbool.h>

typedef struct network_discovery network_discovery_t;
typedef void (*network_discovery_callback_t)(const char *host, int port, void *userdata);

network_discovery_t *network_discovery_create(const char *service_name, int port,
                                              network_discovery_callback_t callback,
                                              void *userdata);
void network_discovery_destroy(network_discovery_t *discovery);
void network_discovery_start(network_discovery_t *discovery);
void network_discovery_stop(network_discovery_t *discovery);
bool network_discovery_is_running(network_discovery_t *discovery);
