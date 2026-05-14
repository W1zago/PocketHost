#include "system_monitor.h"
#include <fstream>
#include <sstream>
#include <unistd.h>
#include <sys/sysinfo.h>
#include <dirent.h>
#include <android/log.h>

#define LOG_TAG "SystemMonitor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

SystemStatsNative SystemMonitor::getSystemStats() {
    SystemStatsNative stats;
    stats.cpuUsagePercent = getCpuUsage();
    stats.totalMemory = getTotalMemory();
    stats.usedMemory = getUsedMemory();
    stats.freeMemory = getFreeMemory();
    stats.networkSent = getNetworkBytesSent();
    stats.networkReceived = getNetworkBytesReceived();
    stats.activePorts = getActivePorts();
    stats.timestamp = time(nullptr) * 1000;
    return stats;
}

float SystemMonitor::getCpuUsage() {
    static long prevIdle = 0, prevTotal = 0;

    std::ifstream statFile("/proc/stat");
    if (!statFile.is_open()) {
        return 0.0f;
    }

    std::string line;
    std::getline(statFile, line);

    std::istringstream iss(line);
    std::string cpu;
    long user, nice, system, idle, iowait, irq, softirq;

    iss >> cpu >> user >> nice >> system >> idle >> iowait >> irq >> softirq;

    long idleTime = idle + iowait;
    long totalTime = user + nice + system + idle + iowait + irq + softirq;

    long diffIdle = idleTime - prevIdle;
    long diffTotal = totalTime - prevTotal;

    prevIdle = idleTime;
    prevTotal = totalTime;

    if (diffTotal == 0) {
        return 0.0f;
    }

    return (1.0f - (float)diffIdle / diffTotal) * 100.0f;
}

std::vector<long> SystemMonitor::getMemoryInfo() {
    std::vector<long> info(3);
    info[0] = getTotalMemory();
    info[1] = getUsedMemory();
    info[2] = getFreeMemory();
    return info;
}

std::vector<long> SystemMonitor::getNetworkStats() {
    std::vector<long> stats(2);
    stats[0] = getNetworkBytesSent();
    stats[1] = getNetworkBytesReceived();
    return stats;
}

long SystemMonitor::getTotalMemory() {
    std::ifstream meminfo("/proc/meminfo");
    if (!meminfo.is_open()) {
        return 0;
    }

    std::string line;
    while (std::getline(meminfo, line)) {
        if (line.find("MemTotal:") == 0) {
            std::istringstream iss(line);
            std::string label;
            long value;
            iss >> label >> value;
            return value * 1024;
        }
    }

    return 0;
}

long SystemMonitor::getUsedMemory() {
    long total = getTotalMemory();
    long free = getFreeMemory();
    return total - free;
}

long SystemMonitor::getFreeMemory() {
    std::ifstream meminfo("/proc/meminfo");
    if (!meminfo.is_open()) {
        return 0;
    }

    std::string line;
    while (std::getline(meminfo, line)) {
        if (line.find("MemAvailable:") == 0) {
            std::istringstream iss(line);
            std::string label;
            long value;
            iss >> label >> value;
            return value * 1024;
        }
    }

    return 0;
}

long SystemMonitor::getNetworkBytesSent() {
    std::ifstream netdev("/proc/net/dev");
    if (!netdev.is_open()) {
        return 0;
    }

    std::string line;
    long totalSent = 0;

    std::getline(netdev, line);
    std::getline(netdev, line);

    while (std::getline(netdev, line)) {
        std::istringstream iss(line);
        std::string iface;
        long rxBytes, rxPackets, rxErrs, rxDrop, rxFifo, rxFrame, rxCompressed, rxMulticast;
        long txBytes;

        iss >> iface >> rxBytes >> rxPackets >> rxErrs >> rxDrop >> rxFifo
            >> rxFrame >> rxCompressed >> rxMulticast >> txBytes;

        if (iface.find("lo:") == std::string::npos) {
            totalSent += txBytes;
        }
    }

    return totalSent;
}

long SystemMonitor::getNetworkBytesReceived() {
    std::ifstream netdev("/proc/net/dev");
    if (!netdev.is_open()) {
        return 0;
    }

    std::string line;
    long totalReceived = 0;

    std::getline(netdev, line);
    std::getline(netdev, line);

    while (std::getline(netdev, line)) {
        std::istringstream iss(line);
        std::string iface;
        long rxBytes;

        iss >> iface >> rxBytes;

        if (iface.find("lo:") == std::string::npos) {
            totalReceived += rxBytes;
        }
    }

    return totalReceived;
}

std::vector<int> SystemMonitor::getActivePorts() {
    std::vector<int> ports;

    std::ifstream tcp("/proc/net/tcp");
    if (tcp.is_open()) {
        std::string line;
        std::getline(tcp, line);

        while (std::getline(tcp, line)) {
            std::istringstream iss(line);
            std::string sl, localAddr, remAddr;
            iss >> sl >> localAddr >> remAddr;

            size_t colonPos = localAddr.find(':');
            if (colonPos != std::string::npos) {
                std::string portHex = localAddr.substr(colonPos + 1);
                int port = std::stoi(portHex, nullptr, 16);
                ports.push_back(port);
            }
        }
    }

    return ports;
}
