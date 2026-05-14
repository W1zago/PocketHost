#include "network_manager.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <fstream>
#include <sstream>
#include <android/log.h>

#define LOG_TAG "NetworkManager"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

std::map<int, int> NetworkManager::boundPorts;

bool NetworkManager::bindPort(int port, const std::string& protocol) {
    int sockfd;

    if (protocol == "TCP" || protocol == "tcp") {
        sockfd = socket(AF_INET, SOCK_STREAM, 0);
    } else if (protocol == "UDP" || protocol == "udp") {
        sockfd = socket(AF_INET, SOCK_DGRAM, 0);
    } else {
        LOGE("Unknown protocol: %s", protocol.c_str());
        return false;
    }

    if (sockfd < 0) {
        LOGE("Failed to create socket");
        return false;
    }

    int opt = 1;
    if (setsockopt(sockfd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) < 0) {
        LOGE("Failed to set socket options");
        close(sockfd);
        return false;
    }

    struct sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(port);

    if (bind(sockfd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("Failed to bind port %d", port);
        close(sockfd);
        return false;
    }

    if (protocol == "TCP" || protocol == "tcp") {
        if (listen(sockfd, 5) < 0) {
            LOGE("Failed to listen on port %d", port);
            close(sockfd);
            return false;
        }
    }

    boundPorts[port] = sockfd;
    LOGI("Successfully bound port %d with protocol %s", port, protocol.c_str());
    return true;
}

bool NetworkManager::unbindPort(int port) {
    auto it = boundPorts.find(port);
    if (it != boundPorts.end()) {
        close(it->second);
        boundPorts.erase(it);
        LOGI("Unbound port %d", port);
        return true;
    }
    return false;
}

bool NetworkManager::isPortOpen(int port) {
    int sockfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sockfd < 0) {
        return false;
    }

    struct sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    addr.sin_port = htons(port);

    fcntl(sockfd, F_SETFL, O_NONBLOCK);

    connect(sockfd, (struct sockaddr*)&addr, sizeof(addr));

    fd_set fdset;
    FD_ZERO(&fdset);
    FD_SET(sockfd, &fdset);

    struct timeval tv;
    tv.tv_sec = 1;
    tv.tv_usec = 0;

    bool isOpen = select(sockfd + 1, nullptr, &fdset, nullptr, &tv) > 0;

    close(sockfd);
    return isOpen;
}

std::vector<int> NetworkManager::getActivePorts() {
    std::vector<int> ports;

    std::ifstream tcp("/proc/net/tcp");
    if (tcp.is_open()) {
        std::string line;
        std::getline(tcp, line);

        while (std::getline(tcp, line)) {
            std::istringstream iss(line);
            std::string sl, localAddr, remAddr, st;
            iss >> sl >> localAddr >> remAddr >> st;

            if (st == "0A") {
                size_t colonPos = localAddr.find(':');
                if (colonPos != std::string::npos) {
                    std::string portHex = localAddr.substr(colonPos + 1);
                    int port = std::stoi(portHex, nullptr, 16);
                    ports.push_back(port);
                }
            }
        }
    }

    std::ifstream udp("/proc/net/udp");
    if (udp.is_open()) {
        std::string line;
        std::getline(udp, line);

        while (std::getline(udp, line)) {
            std::istringstream iss(line);
            std::string sl, localAddr, remAddr, st;
            iss >> sl >> localAddr >> remAddr >> st;

            if (st == "07") {
                size_t colonPos = localAddr.find(':');
                if (colonPos != std::string::npos) {
                    std::string portHex = localAddr.substr(colonPos + 1);
                    int port = std::stoi(portHex, nullptr, 16);
                    ports.push_back(port);
                }
            }
        }
    }

    return ports;
}

bool NetworkManager::isPortAvailable(int port) {
    int sockfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sockfd < 0) {
        return false;
    }

    struct sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(port);

    bool available = bind(sockfd, (struct sockaddr*)&addr, sizeof(addr)) == 0;

    close(sockfd);
    return available;
}
