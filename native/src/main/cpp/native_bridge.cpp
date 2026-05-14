#include "native_bridge.h"
#include "process_manager.h"
#include "system_monitor.h"
#include "network_manager.h"
#include "proot_manager.h"
#include <android/log.h>

#define LOG_TAG "ANServer-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_anserver_native_NativeBridge_startProcess(
    JNIEnv* env, jobject thiz, jstring command, jstring workingDir, jobjectArray environment) {

    std::string cmd = jstringToString(env, command);
    std::string workDir = jstringToString(env, workingDir);

    std::vector<std::string> envVars;
    if (environment) {
        jsize len = env->GetArrayLength(environment);
        for (jsize i = 0; i < len; i++) {
            jstring jenv = (jstring)env->GetObjectArrayElement(environment, i);
            envVars.push_back(jstringToString(env, jenv));
            env->DeleteLocalRef(jenv);
        }
    }

    LOGI("Starting process: %s in %s", cmd.c_str(), workDir.c_str());
    return ProcessManager::startProcess(cmd, workDir, envVars);
}

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_stopProcess(JNIEnv* env, jobject thiz, jint pid) {
    LOGI("Stopping process: %d", pid);
    return ProcessManager::stopProcess(pid);
}

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_killProcess(JNIEnv* env, jobject thiz, jint pid) {
    LOGI("Killing process: %d", pid);
    return ProcessManager::killProcess(pid);
}

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_isProcessAlive(JNIEnv* env, jobject thiz, jint pid) {
    return ProcessManager::isProcessAlive(pid);
}

JNIEXPORT jobject JNICALL
Java_com_anserver_native_NativeBridge_getProcessInfo(JNIEnv* env, jobject thiz, jint pid) {
    auto info = ProcessManager::getProcessInfo(pid);
    if (!info.isAlive) {
        return nullptr;
    }

    jclass cls = env->FindClass("com/anserver/core/model/ProcessInfo");
    jmethodID constructor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;JFJIZ)V");

    jstring serverId = env->NewStringUTF(info.serverId.c_str());

    return env->NewObject(cls, constructor,
        info.pid, serverId, info.startTime,
        info.cpuUsage, info.memoryUsage,
        info.threadCount, info.isAlive);
}

JNIEXPORT jobject JNICALL
Java_com_anserver_native_NativeBridge_getSystemStats(JNIEnv* env, jobject thiz) {
    auto stats = SystemMonitor::getSystemStats();

    jclass cls = env->FindClass("com/anserver/core/model/SystemStats");
    jmethodID constructor = env->GetMethodID(cls, "<init>", "(FJJJJJ[IJ)V");

    jintArray ports = env->NewIntArray(stats.activePorts.size());
    env->SetIntArrayRegion(ports, 0, stats.activePorts.size(), stats.activePorts.data());

    return env->NewObject(cls, constructor,
        stats.cpuUsagePercent, stats.totalMemory, stats.usedMemory,
        stats.freeMemory, stats.networkSent, stats.networkReceived,
        ports, stats.timestamp);
}

JNIEXPORT jfloat JNICALL
Java_com_anserver_native_NativeBridge_getCpuUsage(JNIEnv* env, jobject thiz) {
    return SystemMonitor::getCpuUsage();
}

JNIEXPORT jlongArray JNICALL
Java_com_anserver_native_NativeBridge_getMemoryInfo(JNIEnv* env, jobject thiz) {
    auto memInfo = SystemMonitor::getMemoryInfo();
    jlongArray result = env->NewLongArray(3);
    std::vector<jlong> jlongMemInfo(memInfo.begin(), memInfo.end());
    env->SetLongArrayRegion(result, 0, 3, jlongMemInfo.data());
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_com_anserver_native_NativeBridge_getNetworkStats(JNIEnv* env, jobject thiz) {
    auto netStats = SystemMonitor::getNetworkStats();
    jlongArray result = env->NewLongArray(2);
    std::vector<jlong> jlongNetStats(netStats.begin(), netStats.end());
    env->SetLongArrayRegion(result, 0, 2, jlongNetStats.data());
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_anserver_native_NativeBridge_getActivePorts(JNIEnv* env, jobject thiz) {
    auto ports = NetworkManager::getActivePorts();
    jintArray result = env->NewIntArray(ports.size());
    env->SetIntArrayRegion(result, 0, ports.size(), ports.data());
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_bindPort(JNIEnv* env, jobject thiz, jint port, jstring protocol) {
    std::string proto = jstringToString(env, protocol);
    return NetworkManager::bindPort(port, proto);
}

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_unbindPort(JNIEnv* env, jobject thiz, jint port) {
    return NetworkManager::unbindPort(port);
}

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_isPortOpen(JNIEnv* env, jobject thiz, jint port) {
    return NetworkManager::isPortOpen(port);
}

JNIEXPORT jstring JNICALL
Java_com_anserver_native_NativeBridge_executeCommand(JNIEnv* env, jobject thiz, jstring command) {
    std::string cmd = jstringToString(env, command);
    std::string result = ProcessManager::executeCommand(cmd);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_anserver_native_NativeBridge_readProcessOutput(JNIEnv* env, jobject thiz, jint pid) {
    std::string output = ProcessManager::readProcessOutput(pid);
    return env->NewStringUTF(output.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_setEnvironmentVariable(
    JNIEnv* env, jobject thiz, jstring key, jstring value) {
    std::string k = jstringToString(env, key);
    std::string v = jstringToString(env, value);
    return setenv(k.c_str(), v.c_str(), 1) == 0;
}

JNIEXPORT jstring JNICALL
Java_com_anserver_native_NativeBridge_getEnvironmentVariable(JNIEnv* env, jobject thiz, jstring key) {
    std::string k = jstringToString(env, key);
    const char* value = getenv(k.c_str());
    return value ? env->NewStringUTF(value) : nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_initPRoot(JNIEnv* env, jobject thiz, jstring rootPath) {
    std::string path = jstringToString(env, rootPath);
    return PRootManager::initPRoot(path);
}

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_mountDirectory(
    JNIEnv* env, jobject thiz, jstring source, jstring target) {
    std::string src = jstringToString(env, source);
    std::string tgt = jstringToString(env, target);
    return PRootManager::mountDirectory(src, tgt);
}

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_unmountDirectory(JNIEnv* env, jobject thiz, jstring target) {
    std::string tgt = jstringToString(env, target);
    return PRootManager::unmountDirectory(tgt);
}

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_changeRoot(JNIEnv* env, jobject thiz, jstring newRoot) {
    std::string root = jstringToString(env, newRoot);
    return PRootManager::changeRoot(root);
}

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_createIsolatedEnvironment(
    JNIEnv* env, jobject thiz, jstring name, jstring rootPath) {
    std::string n = jstringToString(env, name);
    std::string path = jstringToString(env, rootPath);
    return PRootManager::createIsolatedEnvironment(n, path);
}

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_destroyIsolatedEnvironment(
    JNIEnv* env, jobject thiz, jstring name) {
    std::string n = jstringToString(env, name);
    return PRootManager::destroyIsolatedEnvironment(n);
}

}
