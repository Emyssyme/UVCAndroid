#include "net_discovery.h"

#include <obs-module.h>
#include <util/threading.h>
#include <util/dstr.h>
#include <util/platform.h>

#include <string.h>
#include <stdlib.h>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "ws2_32.lib")
#else
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#endif

static const int DISCOVERY_PORT = 8866;
static const char DISCOVERY_MAGIC[] = "UVCAPP";

struct network_discovery {
    pthread_t thread;
    bool running;
    char *service_name;
    int port;
    network_discovery_callback_t callback;
    void *userdata;
};

static bool parse_discovery_payload(const char *payload, int len, int *port)
{
    if (len <= 0 || len >= 512) {
        return false;
    }

    char buffer[512];
    memcpy(buffer, payload, len);
    buffer[len] = '\0';

    if (strncmp(buffer, DISCOVERY_MAGIC, strlen(DISCOVERY_MAGIC)) != 0) {
        return false;
    }

    char *token = strtok(buffer, ";");
    while (token) {
        if (strncmp(token, "port=", 5) == 0) {
            *port = atoi(token + 5);
        }
        token = strtok(NULL, ";");
    }

    return *port > 0;
}

static void *network_discovery_thread(void *data)
{
    network_discovery_t *discovery = (network_discovery_t *)data;
#ifdef _WIN32
    SOCKET sock = INVALID_SOCKET;
#else
    int sock = -1;
#endif

#ifdef _WIN32
    WSADATA wsaData;
    WSAStartup(MAKEWORD(2, 2), &wsaData);
#endif

    sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
#ifdef _WIN32
    if (sock == INVALID_SOCKET) {
#else
    if (sock < 0) {
#endif
        blog(LOG_WARNING, "UVC Network Discovery: failed to create socket");
#ifdef _WIN32
        WSACleanup();
#endif
        return NULL;
    }

    int reuse = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, (char *)&reuse, sizeof(reuse));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(DISCOVERY_PORT);
    addr.sin_addr.s_addr = INADDR_ANY;

    if (bind(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        blog(LOG_WARNING, "UVC Network Discovery: failed to bind discovery socket");
#ifdef _WIN32
        closesocket(sock);
        WSACleanup();
#else
        close(sock);
#endif
        return NULL;
    }

#ifdef _WIN32
    DWORD timeout = 500;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (char *)&timeout, sizeof(timeout));
#else
    struct timeval timeout = {0, 500000};
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
#endif

    while (discovery->running) {
        char recv_buffer[512];
        struct sockaddr_in sender_addr;
#ifdef _WIN32
        int sender_len = sizeof(sender_addr);
#else
        socklen_t sender_len = sizeof(sender_addr);
#endif
        int recv_len = recvfrom(sock, recv_buffer, sizeof(recv_buffer), 0,
                                (struct sockaddr *)&sender_addr, &sender_len);

        if (recv_len <= 0) {
            continue;
        }

        char sender_host[128] = {0};
        inet_ntop(AF_INET, &sender_addr.sin_addr, sender_host, sizeof(sender_host));

        int port = 0;
        if (parse_discovery_payload(recv_buffer, recv_len, &port) && sender_host[0] != '\0') {
            // Send unicast ACK back to the phone so it can switch from broadcast to
            // unicast video delivery.  The phone's discovery socket is still listening
            // on the same ephemeral port it sent from (stored in sender_addr.sin_port).
            const char ack[] = "UVCOBS";
            sendto(sock, ack, (int)(sizeof(ack) - 1), 0,
                   (struct sockaddr *)&sender_addr, sender_len);

            if (discovery->callback) {
                discovery->callback(sender_host, port, discovery->userdata);
            }
        }
    }

#ifdef _WIN32
    closesocket(sock);
    WSACleanup();
#else
    close(sock);
#endif
    return NULL;
}

network_discovery_t *network_discovery_create(const char *service_name, int port,
                                              network_discovery_callback_t callback,
                                              void *userdata)
{
    network_discovery_t *discovery = (network_discovery_t *)bzalloc(sizeof(network_discovery_t));
    if (!discovery)
        return NULL;

    discovery->running = false;
    discovery->service_name = bstrdup(service_name ? service_name : "uvc_custom_network");
    discovery->port = port;
    discovery->callback = callback;
    discovery->userdata = userdata;
    return discovery;
}

void network_discovery_destroy(network_discovery_t *discovery)
{
    if (!discovery) return;
    network_discovery_stop(discovery);
    bfree(discovery->service_name);
    bfree(discovery);
}

void network_discovery_start(network_discovery_t *discovery)
{
    if (!discovery || discovery->running) return;
    discovery->running = true;
    pthread_create(&discovery->thread, NULL, (void *(*)(void *))network_discovery_thread, discovery);
}

void network_discovery_stop(network_discovery_t *discovery)
{
    if (!discovery || !discovery->running) return;
    discovery->running = false;
    pthread_join(discovery->thread, NULL);
}

bool network_discovery_is_running(network_discovery_t *discovery)
{
    return discovery && discovery->running;
}
