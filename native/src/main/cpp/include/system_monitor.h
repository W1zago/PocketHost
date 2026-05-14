#ifndef ANSERVER_SYSTEM_MONITOR_H
#define ANSERVER_SYSTEM_MONITOR_H

#include <vector>
#include <string>

struct SystemStatsNative {
    float cpuUsagePercent;
    long totalMemory;
    long usedMemory;
    long freeMemory;
    long networkSent;
    long networkReceived;
    std::vector<int> activePorts;
    long timestamp;
};

class SystemMonitor {
public:
    static SystemStatsNative getSystemStats();
    static float getCpuUsage();
    static std::vector<long> getMemoryInfo();
    static std::vector<long> getNetworkStats();

private:
    static long getTotalMemory();
    static long getUsedMemory();
    static long getFreeMemory();
    static long getNetworkBytesSent();
    static long getNetworkBytesReceived();
    static std::vector<int> getActivePorts();
};

#endif
