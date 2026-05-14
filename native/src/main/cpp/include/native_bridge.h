#ifndef ANSERVER_NATIVE_BRIDGE_H
#define ANSERVER_NATIVE_BRIDGE_H

#include <jni.h>
#include <string>
#include <vector>

extern "C" {

JNIEXPORT jint JNICALL
Java_com_anserver_native_NativeBridge_startProcess(
    JNIEnv* env, jobject thiz, jstring command, jstring workingDir, jobjectArray environment);

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_stopProcess(JNIEnv* env, jobject thiz, jint pid);

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_killProcess(JNIEnv* env, jobject thiz, jint pid);

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_isProcessAlive(JNIEnv* env, jobject thiz, jint pid);

JNIEXPORT jobject JNICALL
Java_com_anserver_native_NativeBridge_getProcessInfo(JNIEnv* env, jobject thiz, jint pid);

JNIEXPORT jobject JNICALL
Java_com_anserver_native_NativeBridge_getSystemStats(JNIEnv* env, jobject thiz);

JNIEXPORT jfloat JNICALL
Java_com_anserver_native_NativeBridge_getCpuUsage(JNIEnv* env, jobject thiz);

JNIEXPORT jlongArray JNICALL
Java_com_anserver_native_NativeBridge_getMemoryInfo(JNIEnv* env, jobject thiz);

JNIEXPORT jlongArray JNICALL
Java_com_anserver_native_NativeBridge_getNetworkStats(JNIEnv* env, jobject thiz);

JNIEXPORT jintArray JNICALL
Java_com_anserver_native_NativeBridge_getActivePorts(JNIEnv* env, jobject thiz);

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_bindPort(JNIEnv* env, jobject thiz, jint port, jstring protocol);

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_unbindPort(JNIEnv* env, jobject thiz, jint port);

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_isPortOpen(JNIEnv* env, jobject thiz, jint port);

JNIEXPORT jstring JNICALL
Java_com_anserver_native_NativeBridge_executeCommand(JNIEnv* env, jobject thiz, jstring command);

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_setEnvironmentVariable(
    JNIEnv* env, jobject thiz, jstring key, jstring value);

JNIEXPORT jstring JNICALL
Java_com_anserver_native_NativeBridge_getEnvironmentVariable(JNIEnv* env, jobject thiz, jstring key);

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_initPRoot(JNIEnv* env, jobject thiz, jstring rootPath);

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_mountDirectory(
    JNIEnv* env, jobject thiz, jstring source, jstring target);

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_unmountDirectory(JNIEnv* env, jobject thiz, jstring target);

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_changeRoot(JNIEnv* env, jobject thiz, jstring newRoot);

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_createIsolatedEnvironment(
    JNIEnv* env, jobject thiz, jstring name, jstring rootPath);

JNIEXPORT jboolean JNICALL
Java_com_anserver_native_NativeBridge_destroyIsolatedEnvironment(
    JNIEnv* env, jobject thiz, jstring name);

}

#endif
