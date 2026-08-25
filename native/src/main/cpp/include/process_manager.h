#ifndef ANSERVER_PROCESS_MANAGER_H
#define ANSERVER_PROCESS_MANAGER_H

#include <string>
#include <vector>
#include <map>
#include <unistd.h>
#include <sys/types.h>
#include <thread>

struct ProcessInfoNative {
    int pid;
    std::string serverId;
    long startTime;
    float cpuUsage;
    long memoryUsage;
    int threadCount;
    bool isAlive;
};

struct ProcessOutput {
    int pid;
    int pipeFd;
    int stdinFd;
    std::thread* readerThread;
};

class ProcessManager {
public:
    static int startProcess(const std::string& command,
                           const std::string& workingDir,
                           const std::vector<std::string>& environment);

    static bool stopProcess(int pid);
    static bool killProcess(int pid);
    static bool isProcessAlive(int pid);
    static ProcessInfoNative getProcessInfo(int pid);
    static std::string executeCommand(const std::string& command);

    static void registerProcess(int pid, const std::string& serverId);
    static void unregisterProcess(int pid);

    static std::string readProcessOutput(int pid);
    static bool writeProcessInput(int pid, const std::string& data);

private:
    static std::map<int, std::string> processMap;
    static std::map<int, ProcessOutput> processOutputMap;
    static float getProcessCpuUsage(int pid);
    static long getProcessMemoryUsage(int pid);
    static int getProcessThreadCount(int pid);
    static void readOutputThread(int pid, int pipeFd);
};

#endif
