#include <jni.h>
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <cstdio>
#include <string>
#include <sstream>
#include <ifaddrs.h>
#include <net/if.h>
#include <cctype>
#include <chrono>

#define LOG_TAG "NetworkHandler"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Access to global tcp_socket from wifi_audio_native.cpp
extern int tcp_socket;


extern "C" {

jstring parseServerResponse(JNIEnv *env, jintArray foundPort, const char *response, int udp_socket);

// Enhanced UDP discovery with faster response and multiple network support
JNIEXPORT jstring JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeDiscoverServer(JNIEnv *env, jclass clazz,
                                                                    jintArray foundPort) {
    LOGI("Starting server discovery for all network types");

    int udp_socket = socket(AF_INET, SOCK_DGRAM, 0);
    if (udp_socket < 0) {
        LOGE("Failed to create UDP socket");
        return nullptr;
    }

    // Enable broadcast
    int broadcast = 1;
    setsockopt(udp_socket, SOL_SOCKET, SO_BROADCAST, &broadcast, sizeof(broadcast));

    // Extended timeout for mobile hotspot networks
    struct timeval timeout;
    timeout.tv_sec = 10; // Extended to 5 seconds for reliable hotspot discovery
    timeout.tv_usec = 0;
    setsockopt(udp_socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

    const char *discovery_msg = "DISCOVER_WIFI_AUDIO_REQUEST";

    // PHASE 0: interface-bound discovery. This is the critical path for
    // phone-as-hotspot and OEM RNDIS interfaces that ConnectivityManager may
    // not expose as a Network. Bind each socket to its local IPv4 address.
    {
        struct ifaddrs *ifaddr = nullptr;
        if (getifaddrs(&ifaddr) == 0) {
            for (struct ifaddrs *ifa = ifaddr; ifa != nullptr; ifa = ifa->ifa_next) {
                if (!ifa->ifa_addr || ifa->ifa_addr->sa_family != AF_INET) continue;
                if (!(ifa->ifa_flags & IFF_UP)) continue;
                const char *name = ifa->ifa_name ? ifa->ifa_name : "";
                if (!strcmp(name, "lo")) continue;
                std::string ifs(name);
                std::string iflow = ifs;
                for (char &c : iflow) c = static_cast<char>(tolower(static_cast<unsigned char>(c)));
                if (iflow.find("tun") != std::string::npos || iflow.find("p2p") != std::string::npos ||
                    iflow.find("vbox") != std::string::npos || iflow.find("vmnet") != std::string::npos ||
                    iflow.find("dummy") != std::string::npos || iflow.find("rmnet") != std::string::npos ||
                    iflow.find("ccmni") != std::string::npos) continue;
                const bool wifiLike = iflow.find("wlan") != std::string::npos ||
                                      iflow.find("wifi") != std::string::npos ||
                                      iflow.find("ap0") != std::string::npos ||
                                      iflow.find("softap") != std::string::npos ||
                                      iflow.find("swlan") != std::string::npos;
                const bool usbLike = iflow.find("rndis") != std::string::npos ||
                                     iflow.find("usb0") != std::string::npos ||
                                     iflow.find("usbnet") != std::string::npos ||
                                     iflow.find("ecm") != std::string::npos ||
                                     iflow.find("ncm") != std::string::npos ||
                                     iflow.find("tether") != std::string::npos ||
                                     iflow == "eth0" || iflow.rfind("eth", 0) == 0;
                if (!wifiLike && !usbLike) continue;
                auto *sin = reinterpret_cast<struct sockaddr_in *>(ifa->ifa_addr);
                char ip[INET_ADDRSTRLEN] = {0};
                inet_ntop(AF_INET, &sin->sin_addr, ip, sizeof(ip));
                if (!strncmp(ip, "127.", 4) || !strncmp(ip, "169.254.", 8)) continue;
                int s = socket(AF_INET, SOCK_DGRAM, 0);
                if (s < 0) continue;
                int one = 1;
                setsockopt(s, SOL_SOCKET, SO_BROADCAST, &one, sizeof(one));
                struct timeval to{0, 300000};
                setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &to, sizeof(to));
                struct sockaddr_in bind_addr{};
                bind_addr.sin_family = AF_INET;
                inet_pton(AF_INET, ip, &bind_addr.sin_addr);
                if (bind(s, reinterpret_cast<struct sockaddr*>(&bind_addr), sizeof(bind_addr)) != 0) { close(s); continue; }
                uint32_t local = ntohl(sin->sin_addr.s_addr);
                uint32_t mask = 0xFFFFFF00u;
                if (ifa->ifa_netmask && ifa->ifa_netmask->sa_family == AF_INET) mask = ntohl(reinterpret_cast<struct sockaddr_in*>(ifa->ifa_netmask)->sin_addr.s_addr);
                uint32_t bc = (local & mask) | (~mask);
                struct sockaddr_in dst{};
                dst.sin_family = AF_INET; dst.sin_addr.s_addr = htonl(bc);
                LOGI("🌐 PHASE 0 interface=%s local=%s", name, ip);
                const int ports0[] = {50000, 32000, 50007};
                for (int p : ports0) {
                    dst.sin_port = htons(p);
                    sendto(s, discovery_msg, strlen(discovery_msg), 0, reinterpret_cast<struct sockaddr*>(&dst), sizeof(dst));
                    sendto(s, discovery_msg, strlen(discovery_msg), 0, reinterpret_cast<struct sockaddr*>(&dst), sizeof(dst));
                }
                char response[1024];
                struct sockaddr_in from{}; socklen_t from_len = sizeof(from);
                for (int i=0; i<5; ++i) {
                    ssize_t n = recvfrom(s, response, sizeof(response)-1, 0, reinterpret_cast<struct sockaddr*>(&from), &from_len);
                    if (n <= 0) continue;
                    response[n] = '\0';
                    if (strstr(response, "WIFI_AUDIO_SERVER") != nullptr) {
                        LOGI("🎉 PHASE 0 SUCCESS via %s: %s", name, response);
                        close(s); freeifaddrs(ifaddr);
                        return parseServerResponse(env, foundPort, response, udp_socket);
                    }
                }
                close(s);
            }
            freeifaddrs(ifaddr);
        }
    }

    // 🎯 PHASE 1: DIRECT IP TARGETING (Your Network Priority)
    LOGI("🎯 Phase 1: Direct IP targeting for 📱 hotspot");
    const char* priority_ips[] = {
            "192.168.137.1",        // Windows PC hotspot
            "192.168.137.255",
            "192.168.42.1",         // Android USB tethering (common)
            "192.168.42.255",
            "192.168.44.1",         // OEM USB tethering variant
            "192.168.44.255",
            "192.168.49.1",         // OEM USB tethering variant
            "192.168.49.255",
            "192.168.43.1",         // Classic Android mobile hotspot
            "192.168.43.255",
            "192.168.234.1",        // Samsung hotspot variant
            "192.168.234.255",
            "192.168.0.1",          // Common router / Realme hotspot
            "192.168.0.255",
            "10.134.110.1",         // Realme GT 6T alternate
            "10.134.110.255",
            "192.168.1.1",          // Likely gateway
            "192.168.1.255",
    };

    int discovery_ports[] = {50000, 32000 ,50007}; // Primary discovery ports
    const int priority_ip_count = static_cast<int>(sizeof(priority_ips) / sizeof(priority_ips[0]));

    // Comprehensive network discovery for all connection types
    struct sockaddr_in broadcast_addr;
    memset(&broadcast_addr, 0, sizeof(broadcast_addr));
    broadcast_addr.sin_family = AF_INET;


    // Direct targeting with progressive retry
    for (int phase1_attempt = 1; phase1_attempt <= 3; phase1_attempt++) {
        LOGI("🎯 Phase 1 Attempt %d/3", phase1_attempt);

        for (int ip_idx = 0; ip_idx < priority_ip_count; ip_idx++) {
            for (int port_idx = 0; port_idx < 3; port_idx++) {
                if (inet_pton(AF_INET, priority_ips[ip_idx], &broadcast_addr.sin_addr) <= 0) {
                    continue;
                }
                broadcast_addr.sin_port = htons(discovery_ports[port_idx]);

                LOGI("🎯 Direct probe: %s:%d", priority_ips[ip_idx], discovery_ports[port_idx]);

                // Send 3 rapid packets for reliability
                for (int burst = 0; burst < 3; burst++) {
                    ssize_t sent = sendto(udp_socket, discovery_msg, strlen(discovery_msg), 0,
                                          (struct sockaddr*)&broadcast_addr, sizeof(broadcast_addr));
                    if (sent > 0) {
                        LOGI("✅ Sent burst %d to %s:%d", burst + 1, priority_ips[ip_idx], discovery_ports[port_idx]);
                    }
                    usleep(30000); // 30ms between bursts
                }

                // Check for immediate response
                char response[512];
                struct sockaddr_in server_addr;
                socklen_t addr_len = sizeof(server_addr);
                ssize_t received = recvfrom(udp_socket, response, sizeof(response) - 1, MSG_DONTWAIT,
                                            (struct sockaddr*)&server_addr, &addr_len);

                if (received > 0) {
                    response[received] = '\0';
                    LOGI("🎉 PHASE 1 SUCCESS: Response from %s: %s", inet_ntoa(server_addr.sin_addr), response);

                    if (strstr(response, "WIFI_AUDIO_SERVER") != nullptr) {
                        return parseServerResponse(env, foundPort, response, udp_socket);
                    }
                }
                usleep(100000); // 100ms between port attempts
            }
        }

        // Progressive delay between phase 1 attempts
        if (phase1_attempt < 3) {
            usleep(phase1_attempt * 500000); // 0.5s, 1s delays
        }
    }

    // Extended broadcast addresses covering all possible network configurations
    // 🌐 PHASE 2: COMPREHENSIVE BROADCAST SWEEP
    LOGI("🌐 Phase 2: Comprehensive broadcast sweep for GT 6T");
    const char* broadcast_addresses[] = {
            "255.255.255.255", // Global broadcast
            "192.168.1.255",   // Common home router
            "192.168.0.255",   // Alternative home router
            "192.168.43.255",  // Android hotspot
            "192.168.137.255", // Windows mobile hotspot
            "192.168.42.255",  // Android USB tethering
            "192.168.44.255",  // OEM USB tethering
            "192.168.49.255",  // OEM USB tethering
            "10.0.0.255",      // iOS hotspot
            "172.20.10.255",   // iOS hotspot alternative
            "10.185.37.255",   // User's specific hotspot network
            "192.168.234.255", // Samsung hotspot range
            "192.168.31.255",  // Xiaomi router range
            "192.168.2.255",   // Alternative router range
            "192.168.100.255", // Corporate network range
    };


    // Multi-attempt discovery with enhanced reliability
    for (int attempt = 1; attempt <= 2; attempt++) {
        LOGI("Discovery attempt  Phase 2 %d/3", attempt);

        // Try each broadcast address
        for (int addr_idx = 0; addr_idx < 15; addr_idx++) {
            if (inet_pton(AF_INET, broadcast_addresses[addr_idx], &broadcast_addr.sin_addr) <= 0) {
                continue;
            }

            for (int port_idx = 0; port_idx < 2; port_idx++) {
                broadcast_addr.sin_port = htons(discovery_ports[port_idx]);
                LOGI("Broadcasting to %s:%d", broadcast_addresses[addr_idx], discovery_ports[port_idx]);

                // Send multiple requests for enhanced reliability on hotspots
                for (int retry = 0; retry < 5; retry++) {
                    ssize_t sent = sendto(udp_socket, discovery_msg, strlen(discovery_msg), 0,
                                          (struct sockaddr *) &broadcast_addr, sizeof(broadcast_addr));
                    if (sent > 0) {
                        LOGI("Sent discovery request %d to %s:%d", retry + 1,
                             broadcast_addresses[addr_idx], discovery_ports[port_idx]);
                    }
                    usleep(60000); // 60ms between retries for faster discovery
                }

                // Check for response after each broadcast attempt
                // Extended listening window for responses
                for (int listen_cycle = 0; listen_cycle < 5; listen_cycle++) {
                    char response[512];
                    struct sockaddr_in server_addr;
                    socklen_t addr_len = sizeof(server_addr);
                    ssize_t received = recvfrom(udp_socket, response, sizeof(response) - 1, 0,
                                                (struct sockaddr *) &server_addr, &addr_len);

                    if (received > 0) {
                        response[received] = '\0';
                        LOGI("🎉 PHASE 2 SUCCESS Received response: %s from %s", response, inet_ntoa(server_addr.sin_addr));

                        if (strstr(response, "WIFI_AUDIO_SERVER") != nullptr) {
                            return parseServerResponse(env, foundPort, response, udp_socket);
                        }
                    }
                    usleep(320000); // 320ms listening cycles
                }
            }
        }
    }

    // Enhanced listening for server announcements with longer timeout
    // 📻 PHASE 3: SERVER ANNOUNCEMENT LISTENING
    LOGI("📻 Phase 3: Server announcement listening ");

    LOGI("Listening for server announcements...");

    // Bind to discovery port to listen for server announcements
    int listen_socket = socket(AF_INET, SOCK_DGRAM, 0);
    if (listen_socket >= 0) {
        int reuse = 1;
        setsockopt(listen_socket, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

        struct sockaddr_in listen_addr;
        memset(&listen_addr, 0, sizeof(listen_addr));
        listen_addr.sin_family = AF_INET;
        listen_addr.sin_addr.s_addr = INADDR_ANY;
        listen_addr.sin_port = htons(50000);



        if (bind(listen_socket, (struct sockaddr*)&listen_addr, sizeof(listen_addr)) == 0) {
            // Extended timeout for listening to announcements
            struct timeval listen_timeout;
            listen_timeout.tv_sec = 5; // 3 seconds for announcements
            listen_timeout.tv_usec = 0;
            setsockopt(listen_socket, SOL_SOCKET, SO_RCVTIMEO, &listen_timeout, sizeof(listen_timeout));

            char announce_response[512];
            struct sockaddr_in announce_addr;
            socklen_t announce_len = sizeof(announce_addr);
            ssize_t announce_received = recvfrom(listen_socket, announce_response,
                                                 sizeof(announce_response) - 1, 0,
                                                 (struct sockaddr*)&announce_addr, &announce_len);

            if (announce_received > 0) {
                announce_response[announce_received] = '\0';
                LOGI("🎉 PHASE 3 SUCCESS: Received server announcement: %s", announce_response);

                if (strstr(announce_response, "WIFI_AUDIO_SERVER") != nullptr) {
                    // Parse announcement: "WIFI_AUDIO_SERVER_ANNOUNCE:IP:PORT"
                    close(listen_socket);
                    return parseServerResponse(env, foundPort, announce_response, udp_socket);
                }
            }
        }
        close(listen_socket);
    }

    close(udp_socket);
    LOGI("No server found after comprehensive discovery All 3 phases completed");
    return nullptr;
}


jstring parseServerResponse(JNIEnv *env, jintArray foundPort, const char *response, int udp_socket) {
// Enhanced parsing for multiple response formats // WIFI_AUDIO_SERVER:ip:port:server_name

    char *first_colon = strchr((char*)response, ':');
    if (first_colon) {
        char *ip_start = first_colon + 1;
        char *second_colon = strchr(ip_start, ':');
        if (second_colon) {
            *second_colon = '\0';
            char *port_start = second_colon + 1;

            char *third_colon = strchr(port_start, ':');
            char *server_name = nullptr;
            char *backend_name = nullptr;

            if (third_colon) {
                *third_colon = '\0';
                server_name = third_colon + 1;

                char *fourth_colon = strchr(server_name, ':');
                if (fourth_colon) {
                    *fourth_colon = '\0';
                    backend_name = fourth_colon + 1;
                }
            }
            int audio_port = atoi(port_start);

            LOGI("Discovery SUCCESS  Server found: %s:%d (%s / %s) ",
                 ip_start, audio_port,(server_name && *server_name) ? server_name : "Unknown",
                 (backend_name && *backend_name) ? backend_name : "Unknown");

            // Return port to Java
            jint *port_array = env->GetIntArrayElements(foundPort, nullptr);
            port_array[0] = audio_port;
            env->ReleaseIntArrayElements(foundPort, port_array, 0);

            std::string result = std::string(ip_start) + "|" +
                                 ((server_name && *server_name) ? server_name : "Unknown") +
                                 "|" +
                                 ((backend_name && *backend_name) ? backend_name : "Unknown");


            // Return IP ,Name to Java
            jstring server_result = env->NewStringUTF(result.c_str());
            close(udp_socket);
            return server_result;

        }
    }

    close(udp_socket);
    return nullptr;
}
// Send handshake information to server - INSTANT with validation  /CRLF for maximum compatibility
JNIEXPORT jstring JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeSendHandshake(JNIEnv *env, jclass clazz,
                                                                   jstring manufacturer,
                                                                   jstring model, jstring device) {
    const char *mfg = env->GetStringUTFChars(manufacturer, nullptr);
    const char *mdl = env->GetStringUTFChars(model, nullptr);
    const char *dev = env->GetStringUTFChars(device, nullptr);

    // Construct enhanced handshake message
    char handshake[1024];
    // CRLF-terminated line
    snprintf(handshake, sizeof(handshake), "CLIENT_INFO:%s:%s:%s\r\n", mfg, mdl, dev);

    LOGI("Sending handshake: %s", handshake);

    // Send handshake IMMEDIATELY after connection - NO DELAY!
    ssize_t sent = send(tcp_socket, handshake, strlen(handshake), 0);

    env->ReleaseStringUTFChars(manufacturer, mfg);
    env->ReleaseStringUTFChars(model, mdl);
    env->ReleaseStringUTFChars(device, dev);

    if (sent <= 0) {
        LOGE("Failed to send handshake: %s", strerror(errno));
        return nullptr;
    }

    LOGI("Handshake 🤝 sent (%zd bytes) - waiting for SERVER_ACK", sent);

    char ack[512];
    bool ack_received = false;

    // The native socket's normal receive timeout is 100 ms for responsive stream
    // teardown. Do not interpret the first EAGAIN as a failed handshake: during
    // Wi-Fi/SoftAP/RNDIS route changes, the SERVER_ACK can arrive a few 100 ms
    // later even though the server is up. Keep the stream socket's normal timeout
    // unchanged, but retry those transient timeouts for a bounded 900 ms window.
    const auto handshake_deadline = std::chrono::steady_clock::now() +
                                    std::chrono::milliseconds(900);
    while (std::chrono::steady_clock::now() < handshake_deadline) {
        memset(ack, 0, sizeof(ack));
        ssize_t received = recv(tcp_socket, ack, sizeof(ack) - 1, 0);

        if (received > 0) {
            ack[received] = '\0';
            if (strncmp(ack, "SERVER_ACK:", 11) == 0) {
                LOGI("? SERVER_ACK received: %s", ack);
                ack_received = true;
                break;
            }
            // Ignore unrelated/early audio or control datagrams and keep waiting.
            continue;
        }

        if (received < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
            // A short receive timeout is expected. Retry until the fixed deadline.
            continue;
        }

        if (received < 0) {
            LOGE("? Handshake receive failed before deadline: %s", strerror(errno));
            break;
        }
    }

    if (ack_received) {
        return env->NewStringUTF(ack);
    }

    LOGE("? No SERVER_ACK received within 900 ms; handshake failed");
    return nullptr;
}

// Send DISCONNECT signal to server
JNIEXPORT void JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeSendDisconnect(JNIEnv *env, jclass clazz) {
    if (tcp_socket >= 0) { // tcp_socket is our UDP socket variable
        const char *msg = "DISCONNECT\r\n";
        send(tcp_socket, msg, strlen(msg), 0);
        LOGI("Sent DISCONNECT signal to server");
    }
}

//HeartBeat Function
JNIEXPORT jboolean JNICALL
Java_com_example_wifiaudiospeaker_AudioStreamingService_nativeServiceSendHeartbeat(
        JNIEnv *env,
        jclass clazz) {

    if (tcp_socket < 0) {
        return JNI_FALSE;
    }

    const char *msg = "CLIENT_HEARTBEAT\r\n";

    ssize_t sent = send(
            tcp_socket,
            msg,
            strlen(msg),
            0
    );

    return sent == (ssize_t)strlen(msg)
           ? JNI_TRUE
           : JNI_FALSE;
}

} // extern "C"