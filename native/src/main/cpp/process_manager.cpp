#include "process_manager.h"
#include <sys/wait.h>
#include <signal.h>
#include <fcntl.h>
#include <unistd.h>
#include <fstream>
#include <sstream>
#include <cstring>
#include <android/log.h>
#include <thread>
#include <mutex>

#define LOG_TAG "ProcessManager"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

std::map<int, std::string> ProcessManager::processMap;
std::map<int, ProcessOutput> ProcessManager::processOutputMap;
static std::map<int, std::string> outputBuffers;
static std::mutex outputMutex;

int ProcessManager::startProcess(const std::string& command,
                                 const std::string& workingDir,
                                 const std::vector<std::string>& environment) {
    int pipefd[2];
    if (pipe(pipefd) != 0) {
        LOGE("Failed to create pipe");
        return -1;
    }
    int stdinPipe[2];
    if (pipe(stdinPipe) != 0) {
        LOGE("Failed to create stdin pipe");
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    }

    pid_t pid = fork();

    if (pid < 0) {
        LOGE("Failed to fork process");
        close(pipefd[0]);
        close(pipefd[1]);
        close(stdinPipe[0]);
        close(stdinPipe[1]);
        return -1;
    }

    if (pid == 0) {
        // Child process
        close(pipefd[0]); // Close read end of stdout pipe
        close(stdinPipe[1]); // Close write end of stdin pipe

        if (!workingDir.empty()) {
            if (chdir(workingDir.c_str()) != 0) {
                LOGE("Failed to change directory to %s", workingDir.c_str());
                exit(1);
            }
        }

        // Redirect stdin from pipe
        dup2(stdinPipe[0], STDIN_FILENO);
        close(stdinPipe[0]);

        // Redirect stdout and stderr to pipe
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[1]);

        for (const auto& env : environment) {
            size_t pos = env.find('=');
            if (pos != std::string::npos) {
                std::string key = env.substr(0, pos);
                std::string value = env.substr(pos + 1);
                setenv(key.c_str(), value.c_str(), 1);
            }
        }

        execl("/system/bin/sh", "sh", "-c", command.c_str(), nullptr);
        LOGE("Failed to execute command: %s", command.c_str());
        exit(1);
    }

    // Parent process
    close(pipefd[1]); // Close write end of stdout pipe
    close(stdinPipe[0]); // Close read end of stdin pipe

    // Store pipe fds and start reader thread
    ProcessOutput output;
    output.pid = pid;
    output.pipeFd = pipefd[0];
    output.stdinFd = stdinPipe[1];
    output.readerThread = new std::thread(readOutputThread, pid, pipefd[0]);
    output.readerThread->detach();

    processOutputMap[pid] = output;

    LOGI("Started process with PID: %d", pid);
    return pid;
}

bool ProcessManager::stopProcess(int pid) {
    if (kill(pid, SIGTERM) == 0) {
        int status;
        int timeout = 10;
        while (timeout > 0 && waitpid(pid, &status, WNOHANG) == 0) {
            sleep(1);
            timeout--;
        }

        if (timeout == 0) {
            return killProcess(pid);
        }
        return true;
    }
    return false;
}

bool ProcessManager::killProcess(int pid) {
    return kill(pid, SIGKILL) == 0;
}

bool ProcessManager::isProcessAlive(int pid) {
    return kill(pid, 0) == 0;
}

ProcessInfoNative ProcessManager::getProcessInfo(int pid) {
    ProcessInfoNative info;
    info.pid = pid;
    info.isAlive = isProcessAlive(pid);

    if (!info.isAlive) {
        return info;
    }

    auto it = processMap.find(pid);
    if (it != processMap.end()) {
        info.serverId = it->second;
    }

    info.cpuUsage = getProcessCpuUsage(pid);
    info.memoryUsage = getProcessMemoryUsage(pid);
    info.threadCount = getProcessThreadCount(pid);

    std::string statPath = "/proc/" + std::to_string(pid) + "/stat";
    std::ifstream statFile(statPath);
    if (statFile.is_open()) {
        std::string line;
        std::getline(statFile, line);
        std::istringstream iss(line);
        std::string token;
        for (int i = 0; i < 22; i++) {
            iss >> token;
            if (i == 21) {
                info.startTime = std::stol(token);
            }
        }
    }

    return info;
}

float ProcessManager::getProcessCpuUsage(int pid) {
    std::string statPath = "/proc/" + std::to_string(pid) + "/stat";
    std::ifstream statFile(statPath);

    if (!statFile.is_open()) {
        return 0.0f;
    }

    std::string line;
    std::getline(statFile, line);
    std::istringstream iss(line);

    std::string token;
    long utime = 0, stime = 0;

    for (int i = 0; i < 15; i++) {
        iss >> token;
        if (i == 13) utime = std::stol(token);
        if (i == 14) stime = std::stol(token);
    }

    long totalTime = utime + stime;
    long hertz = sysconf(_SC_CLK_TCK);

    return (float)totalTime / hertz;
}

long ProcessManager::getProcessMemoryUsage(int pid) {
    std::string statusPath = "/proc/" + std::to_string(pid) + "/status";
    std::ifstream statusFile(statusPath);

    if (!statusFile.is_open()) {
        return 0;
    }

    std::string line;
    while (std::getline(statusFile, line)) {
        if (line.find("VmRSS:") == 0) {
            std::istringstream iss(line);
            std::string label;
            long value;
            iss >> label >> value;
            return value * 1024;
        }
    }

    return 0;
}

int ProcessManager::getProcessThreadCount(int pid) {
    std::string statusPath = "/proc/" + std::to_string(pid) + "/status";
    std::ifstream statusFile(statusPath);

    if (!statusFile.is_open()) {
        return 0;
    }

    std::string line;
    while (std::getline(statusFile, line)) {
        if (line.find("Threads:") == 0) {
            std::istringstream iss(line);
            std::string label;
            int value;
            iss >> label >> value;
            return value;
        }
    }

    return 0;
}

std::string ProcessManager::executeCommand(const std::string& command) {
    FILE* pipe = popen(command.c_str(), "r");
    if (!pipe) {
        return "";
    }

    char buffer[128];
    std::string result;

    while (fgets(buffer, sizeof(buffer), pipe) != nullptr) {
        result += buffer;
    }

    pclose(pipe);
    return result;
}

void ProcessManager::registerProcess(int pid, const std::string& serverId) {
    processMap[pid] = serverId;
}

void ProcessManager::unregisterProcess(int pid) {
    processMap.erase(pid);

    // Clean up output resources
    auto it = processOutputMap.find(pid);
    if (it != processOutputMap.end()) {
        close(it->second.pipeFd);
        close(it->second.stdinFd);
        delete it->second.readerThread;
        processOutputMap.erase(it);
    }

    std::lock_guard<std::mutex> lock(outputMutex);
    outputBuffers.erase(pid);
}

void ProcessManager::readOutputThread(int pid, int pipeFd) {
    char buffer[4096];
    ssize_t bytesRead;

    // Set non-blocking mode
    int flags = fcntl(pipeFd, F_GETFL, 0);
    fcntl(pipeFd, F_SETFL, flags | O_NONBLOCK);

    while (true) {
        bytesRead = read(pipeFd, buffer, sizeof(buffer) - 1);

        if (bytesRead > 0) {
            buffer[bytesRead] = '\0';

            // Log to Android logcat
            __android_log_print(ANDROID_LOG_INFO, "ServerProcess", "[PID:%d] %s", pid, buffer);

            // Store in buffer for retrieval
            std::lock_guard<std::mutex> lock(outputMutex);
            outputBuffers[pid] += std::string(buffer, bytesRead);

            // Keep buffer size reasonable (max 100KB)
            if (outputBuffers[pid].size() > 100000) {
                outputBuffers[pid] = outputBuffers[pid].substr(outputBuffers[pid].size() - 100000);
            }
        } else if (bytesRead == 0) {
            // EOF - process closed pipe
            LOGI("Process %d closed output pipe", pid);
            break;
        } else {
            // EAGAIN or error
            if (errno != EAGAIN && errno != EWOULDBLOCK) {
                LOGE("Error reading from pipe for PID %d: %s", pid, strerror(errno));
                break;
            }
            usleep(100000); // Sleep 100ms
        }

        // Check if process is still alive
        if (!isProcessAlive(pid)) {
            // Process exited: switch to blocking reads and drain the remaining
            // output until EOF so final error messages are not lost.
            int currentFlags = fcntl(pipeFd, F_GETFL, 0);
            fcntl(pipeFd, F_SETFL, currentFlags & ~O_NONBLOCK);

            while ((bytesRead = read(pipeFd, buffer, sizeof(buffer) - 1)) > 0) {
                buffer[bytesRead] = '\0';

                __android_log_print(ANDROID_LOG_INFO, "ServerProcess", "[PID:%d] %s", pid, buffer);

                std::lock_guard<std::mutex> lock(outputMutex);
                outputBuffers[pid] += std::string(buffer, bytesRead);
                if (outputBuffers[pid].size() > 100000) {
                    outputBuffers[pid] = outputBuffers[pid].substr(outputBuffers[pid].size() - 100000);
                }
            }

            LOGI("Process %d is no longer alive, stopping output reader", pid);
            break;
        }
    }

    close(pipeFd);
}

std::string ProcessManager::readProcessOutput(int pid) {
    std::lock_guard<std::mutex> lock(outputMutex);

    auto it = outputBuffers.find(pid);
    if (it != outputBuffers.end()) {
        std::string output = it->second;
        it->second.clear(); // Clear buffer after reading
        return output;
    }

    return "";
}

bool ProcessManager::writeProcessInput(int pid, const std::string& data) {
    auto it = processOutputMap.find(pid);
    if (it == processOutputMap.end()) {
        LOGE("writeProcessInput: no process with PID %d", pid);
        return false;
    }
    int fd = it->second.stdinFd;
    const char* buf = data.c_str();
    size_t remaining = data.size();
    while (remaining > 0) {
        ssize_t written = write(fd, buf, remaining);
        if (written < 0) {
            if (errno == EINTR) continue;
            LOGE("writeProcessInput failed for PID %d: %s", pid, strerror(errno));
            return false;
        }
        buf += written;
        remaining -= written;
    }
    return true;
}
