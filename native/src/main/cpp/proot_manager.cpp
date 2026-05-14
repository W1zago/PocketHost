#include "proot_manager.h"
#include <sys/stat.h>
#include <sys/mount.h>
#include <unistd.h>
#include <fstream>
#include <android/log.h>

#define LOG_TAG "PRootManager"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

std::string PRootManager::currentRoot;
std::map<std::string, std::string> PRootManager::mountPoints;
std::map<std::string, std::string> PRootManager::environments;

bool PRootManager::initPRoot(const std::string& rootPath) {
    struct stat st;
    if (stat(rootPath.c_str(), &st) != 0) {
        LOGE("Root path does not exist: %s", rootPath.c_str());
        return false;
    }

    if (!S_ISDIR(st.st_mode)) {
        LOGE("Root path is not a directory: %s", rootPath.c_str());
        return false;
    }

    currentRoot = rootPath;
    LOGI("Initialized PRoot with root: %s", rootPath.c_str());
    return true;
}

bool PRootManager::mountDirectory(const std::string& source, const std::string& target) {
    struct stat st;
    if (stat(source.c_str(), &st) != 0) {
        LOGE("Source directory does not exist: %s", source.c_str());
        return false;
    }

    std::string fullTarget = currentRoot + target;

    if (mkdir(fullTarget.c_str(), 0755) != 0 && errno != EEXIST) {
        LOGE("Failed to create target directory: %s", fullTarget.c_str());
        return false;
    }

    mountPoints[target] = source;
    LOGI("Mounted %s to %s", source.c_str(), target.c_str());
    return true;
}

bool PRootManager::unmountDirectory(const std::string& target) {
    auto it = mountPoints.find(target);
    if (it == mountPoints.end()) {
        LOGE("Mount point not found: %s", target.c_str());
        return false;
    }

    mountPoints.erase(it);
    LOGI("Unmounted %s", target.c_str());
    return true;
}

bool PRootManager::changeRoot(const std::string& newRoot) {
    struct stat st;
    if (stat(newRoot.c_str(), &st) != 0) {
        LOGE("New root path does not exist: %s", newRoot.c_str());
        return false;
    }

    if (!S_ISDIR(st.st_mode)) {
        LOGE("New root path is not a directory: %s", newRoot.c_str());
        return false;
    }

    currentRoot = newRoot;
    LOGI("Changed root to: %s", newRoot.c_str());
    return true;
}

bool PRootManager::createIsolatedEnvironment(const std::string& name, const std::string& rootPath) {
    if (environments.find(name) != environments.end()) {
        LOGE("Environment already exists: %s", name.c_str());
        return false;
    }

    if (mkdir(rootPath.c_str(), 0755) != 0 && errno != EEXIST) {
        LOGE("Failed to create environment directory: %s", rootPath.c_str());
        return false;
    }

    std::string binDir = rootPath + "/bin";
    std::string libDir = rootPath + "/lib";
    std::string etcDir = rootPath + "/etc";
    std::string tmpDir = rootPath + "/tmp";
    std::string homeDir = rootPath + "/home";

    mkdir(binDir.c_str(), 0755);
    mkdir(libDir.c_str(), 0755);
    mkdir(etcDir.c_str(), 0755);
    mkdir(tmpDir.c_str(), 0755);
    mkdir(homeDir.c_str(), 0755);

    environments[name] = rootPath;
    LOGI("Created isolated environment: %s at %s", name.c_str(), rootPath.c_str());
    return true;
}

bool PRootManager::destroyIsolatedEnvironment(const std::string& name) {
    auto it = environments.find(name);
    if (it == environments.end()) {
        LOGE("Environment not found: %s", name.c_str());
        return false;
    }

    environments.erase(it);
    LOGI("Destroyed isolated environment: %s", name.c_str());
    return true;
}
