// In-process JVM launcher for PocketHost.
//
// Port of MojoLauncher/PojavLauncher:
//   app_pojavlauncher/src/main/jni/jre_launcher/jre_launcher.c
//   app_pojavlauncher/src/main/jni/utils.c (convert_to_char_array, setLdLibraryPath, chdir)
//
// Differences (server use-case, headless):
//  - No graphics/LWJGL/AWT hooks, no bytehook, no mojoexec dependency.
//  - Persistent JavaVM: created once per app process (like Mojo's one-game-per-process
//    model). Sequential starts of the SAME classpath reuse the VM.
//  - System.exit() trap via setjmp/longjmp: the server's "stop" command calls
//    System.exit(0) which would otherwise kill the whole PocketHost app.
//    The exit/abort hooks longjmp back to nativeLoadJVM, which then detaches
//    the thread and returns the exit code to Kotlin instead of dying.

#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unwind.h>
#include <sys/prctl.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <signal.h>
#include <setjmp.h>
#include <stdarg.h>
#include <pthread.h>
#include <android/log.h>
#include <stdatomic.h>
#include <time.h>

#define LOG_TAG "PocketJvm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef jint (*JNI_CreateJavaVM_t)(JavaVM **p_vm, JNIEnv **p_env, void *vm_args);

// ---- persistent VM state (one JVM per app process, same as Mojo's model) ----
static void *g_jvm_handle = NULL;
static JavaVM *g_vm = NULL;
static pthread_mutex_t g_vm_lock = PTHREAD_MUTEX_INITIALIZER;

// setjmp buffer for the System.exit() trap. One server thread at a time,
// so a single global buffer is enough (guarded by g_vm_lock + Kotlin-side check).
static sigjmp_buf g_exit_jmp;
// RESTART-FIX 2026-09-06: cross-thread flags MUST be _Atomic, not plain
// volatile. volatile only stops compiler caching; on ARM64 the parking loop
// (mc-server thread, HotSpot-attached) could spin on a stale cached copy of
// g_stop_requested set by nativeRequestStop (Service thread) and never see
// the latch вЂ” exactly the 17:58 hang (2 parking iters then silence, no
// "grace over", futureDone=false 74s+). atomic_load/store give acquire/
// release visibility across cores.
static _Atomic int g_exit_code = 0;
static _Atomic int g_in_server_main = 0;
// Paperclip quirk: Paperclip.Main.main() spawns the server thread and returns
// immediately (code 0) while the server keeps running. The mc-server thread
// must NOT treat that return as death вЂ” it parks and waits for the real
// server threads or a trapped System.exit.
// Cross-thread exit: "stop" calls System.exit(0) from the Server thread,
// NOT from mc-server. longjmp across threads is UB, so the hook only
// longjmps when called on the same thread; otherwise it flags + parks.
static _Atomic int g_exit_requested = 0;
static pthread_t g_server_thread;
static int g_have_server_thread = 0;
// FAIL 17:44: Kotlin Stop (requestStop) while mc-server parks on lingering
// chunk workers (port free + Closing in log, but threads alive): the parking
// loop below only watched g_exit_requested (System.exit trap) and hung with
// futureDone=false 60s+. This flag (set via nativeRequestStop) gives the
// server a short grace to flush the world, then the loop returns anyway.
// _Atomic (see above): plain volatile hung the 17:58 restart.
static _Atomic int g_stop_requested = 0;

// FAIL 18:08: grace MUST NOT depend on the parking loop reaching stop_grace++
// AFTER getAllStackTraces. If the enum hangs (Threads_lock held by the exiting
// Server thread, dying chunk workers), the counter never advances and the
// forced-exit gate `stop_grace>=5` before enum never fires -> 30s+ silence,
// no iter 20+, no "server exit trapped", mc-server thread alive in ps.
// So nativeRequestStop stamps CLOCK_MONOTONIC here (single _Atomic long ms вЂ”
// torn-read-free across cores) and the parking loop computes elapsed BEFORE
// every enum. Forced exit at >=10s happens even if enum never returns.
static _Atomic long g_stop_latch_ms = 0; // 0 = not latched for this boot

static long mono_ms_now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)(ts.tv_sec * 1000L + ts.tv_nsec / 1000000L);
}

// Elapsed seconds since the stop latch, or -1 if not latched.
static double stop_elapsed_sec(void) {
    long latch = atomic_load(&g_stop_latch_ms);
    if (latch <= 0) return -1.0;
    return (mono_ms_now() - latch) / 1000.0;
}

// Last failure description for server.log (logcat alone is invisible in-app).
static char g_last_error[2048] = "";

static void throwRuntime(JNIEnv *env, const char *msg) {
    // Also stash into the last-error buffer so Kotlin can write it to server.log
    // (ExceptionDescribe below only goes to logcat, invisible in ConsoleActivity).
    if (msg) {
        snprintf(g_last_error, sizeof(g_last_error), "%s", msg);
    }
    jclass cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, msg);
}

static void set_last_error(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_last_error, sizeof(g_last_error), fmt, ap);
    va_end(ap);
    LOGE("%s", g_last_error);
}

// Copies pending JVM exception (toString + cause chain) into g_last_error.
// Must be called on vm_env while the exception is pending.
//
// ORDER MATTERS: ExceptionOccurred + GlobalRef FIRST, then Describe, then
// Clear, then the toString walk. (ExceptionDescribe clears the pending
// exception on HotSpot, so Occurred-after-Describe always yields NULL and
// the error text is lost вЂ” that bug made every -8 look "silent". Likewise
// CallObjectMethod is a no-op while an exception is pending, so the walk
// must run only after Clear.)
static void capture_vm_exception(JNIEnv *vm_env, const char *where) {
    if (!(*vm_env)->ExceptionCheck(vm_env)) return;
    jthrowable ex = (*vm_env)->ExceptionOccurred(vm_env);
    if (!ex) return;
    jthrowable gex = (jthrowable)(*vm_env)->NewGlobalRef(vm_env, ex);
    (*vm_env)->ExceptionDescribe(vm_env); // full stack to logcat while still pending
    (*vm_env)->ExceptionClear(vm_env);    // now JNI calls work again
    if (!gex) {
        set_last_error("%s: <no ref for exception>", where);
        return;
    }
    // Walk at most 3 causes, calling toString() on each.
    char buf[2048];
    size_t off = 0;
    off += snprintf(buf + off, sizeof(buf) - off, "%s: ", where);
    jobject cur = gex;
    for (int depth = 0; depth < 3 && cur != NULL; depth++) {
        jclass objCls = (*vm_env)->GetObjectClass(vm_env, cur);
        jmethodID toStr = (*vm_env)->GetMethodID(vm_env, objCls, "toString", "()Ljava/lang/String;");
        jstring s = (jstring) (*vm_env)->CallObjectMethod(vm_env, cur, toStr);
        if ((*vm_env)->ExceptionCheck(vm_env)) { (*vm_env)->ExceptionClear(vm_env); break; }
        const char *utf = s ? (*vm_env)->GetStringUTFChars(vm_env, s, NULL) : NULL;
        if (depth > 0) off += snprintf(buf + off, sizeof(buf) - off, " <- ");
        off += snprintf(buf + off, sizeof(buf) - off, "%s", utf ? utf : "?");
        if (s && utf) (*vm_env)->ReleaseStringUTFChars(vm_env, s, utf);
        if (s) (*vm_env)->DeleteLocalRef(vm_env, s);
        // getCause()
        jmethodID getCause = (*vm_env)->GetMethodID(vm_env, objCls, "getCause", "()Ljava/lang/Throwable;");
        if (!getCause) { (*vm_env)->ExceptionClear(vm_env); break; }
        jobject cause = (*vm_env)->CallObjectMethod(vm_env, cur, getCause);
        if ((*vm_env)->ExceptionCheck(vm_env)) { (*vm_env)->ExceptionClear(vm_env); break; }
        if (depth > 0) (*vm_env)->DeleteLocalRef(vm_env, cur);
        cur = cause;
        (*vm_env)->DeleteLocalRef(vm_env, objCls);
        if (cur == NULL) break;
    }
    if (cur != NULL && cur != gex) (*vm_env)->DeleteLocalRef(vm_env, cur);
    (*vm_env)->DeleteGlobalRef(vm_env, gex);
    (*vm_env)->ExceptionClear(vm_env);
    set_last_error("%s", buf);
}

// ---- exit/abort hooks passed as JavaVMOption (same mechanism as Mojo) ----
static void vm_exit_hook(int code) {
    atomic_store(&g_exit_code, code);
    atomic_store(&g_exit_requested, 1);
    int in_main = atomic_load(&g_in_server_main);
    int is_mc = (g_have_server_thread &&
        pthread_equal(pthread_self(), g_server_thread)) ? 1 : 0;
    LOGI("vm_exit_hook(%d) in_main=%d is_mc=%d self=%lu mc=%lu have=%d",
         code, in_main, is_mc,
         (unsigned long) pthread_self(),
         g_have_server_thread ? (unsigned long) g_server_thread : 0UL,
         g_have_server_thread);
    if (in_main && is_mc) {
        siglongjmp(g_exit_jmp, 1);
    }
    // Cross-thread System.exit (Paper "stop" from Server thread while
    // mc-server parks in the Paperclip wait below): just end this thread.
    // The parked mc-server loop polls g_exit_requested and returns the code.
    // Same-thread-less exit outside main (shouldn't happen): halt thread only.
    //
    // STOP->START FIX 2026-09-07 (20:56 hang): raw pthread_exit leaves the
    // HotSpot JavaThread object in Threads list in _thread_in_vm state,
    // possibly holding Threads_lock / blocking the next safepoint. The
    // parked mc-server then hangs on its first JNI after the trap
    // (ExceptionCheck/Detach need ThreadInVM transition) вЂ” "trapped during
    // stop-wait" appears, "nativeLoadJVM exit" never does, g_in_server_main
    // stays 1, every reuse refuses -11. Detach first so the VM drops the
    // dead Server thread cleanly; pthread_exit then only kills the pthread.
    if (g_vm != NULL) {
        LOGI("vm_exit_hook cross-thread: detaching exiting thread before pthread_exit...");
        (*g_vm)->DetachCurrentThread(g_vm);
        LOGI("vm_exit_hook cross-thread: detached, pthread_exit now");
    }
    pthread_exit(NULL);
}

static void vm_abort_hook(void) {
    atomic_store(&g_exit_code, 1);
    atomic_store(&g_exit_requested, 1);
    int is_mc = (atomic_load(&g_in_server_main) && g_have_server_thread &&
        pthread_equal(pthread_self(), g_server_thread)) ? 1 : 0;
    LOGI("vm_abort_hook in_main=%d is_mc=%d self=%lu",
         atomic_load(&g_in_server_main), is_mc,
         (unsigned long) pthread_self());
    if (is_mc) {
        siglongjmp(g_exit_jmp, 1);
    }
    if (g_vm != NULL) {
        LOGI("vm_abort_hook cross-thread: detaching before pthread_exit...");
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    pthread_exit(NULL);
}

// ---- Paperclip liveness: are HotSpot server threads still alive? ----
// Kotlin Thread.enumerate sees only ART threads, so this MUST live in native
// where vm_env belongs to HotSpot. Counts threads that prove a live server:
//  - any non-daemon, alive, non-current, non-system thread, OR
//  - any alive thread (even daemon, Netty pools are daemon) whose name
//    matches server keywords.
// RESTART-FIX 2026-09-06: split into HARD vs SOFT. Tuinity Chunk System
// Worker / pool- / worker threads can live MINUTES after Closing (world
// already saved, port free) and must NOT block parking forever. After
// stopRequested only HARD threads block the full 10s grace; soft-only exits
// after ~2s. Before stop both count as alive (normal run keeps parking).
// Returns 1 alive / 0 dead / -1 on JNI error (caller treats as dead).
// hardOut/softOut (nullable) receive breakdown counts for parking logs.
static int has_live_server_threads_ex(JNIEnv *vm_env, int *hardOut, int *softOut) {
    jclass threadCls = (*vm_env)->FindClass(vm_env, "java/lang/Thread");
    if (!threadCls || (*vm_env)->ExceptionCheck(vm_env)) {
        if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
        return -1;
    }
    jmethodID curMid = (*vm_env)->GetStaticMethodID(vm_env, threadCls, "currentThread",
                                                    "()Ljava/lang/Thread;");
    jmethodID allMid = (*vm_env)->GetStaticMethodID(vm_env, threadCls, "getAllStackTraces",
                                                    "()Ljava/util/Map;");
    jmethodID isAliveMid = (*vm_env)->GetMethodID(vm_env, threadCls, "isAlive", "()Z");
    jmethodID isDaemonMid = (*vm_env)->GetMethodID(vm_env, threadCls, "isDaemon", "()Z");
    jmethodID getNameMid = (*vm_env)->GetMethodID(vm_env, threadCls, "getName",
                                                  "()Ljava/lang/String;");
    if (!curMid || !allMid || !isAliveMid || !isDaemonMid || !getNameMid ||
        (*vm_env)->ExceptionCheck(vm_env)) {
        if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
        (*vm_env)->DeleteLocalRef(vm_env, threadCls);
        return -1;
    }
    jobject curThread = (*vm_env)->CallStaticObjectMethod(vm_env, threadCls, curMid);
    if ((*vm_env)->ExceptionCheck(vm_env)) {
        (*vm_env)->ExceptionClear(vm_env);
        curThread = NULL;
    }
    jobject mapObj = (*vm_env)->CallStaticObjectMethod(vm_env, threadCls, allMid);
    if (!mapObj || (*vm_env)->ExceptionCheck(vm_env)) {
        if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
        if (curThread) (*vm_env)->DeleteLocalRef(vm_env, curThread);
        (*vm_env)->DeleteLocalRef(vm_env, threadCls);
        return -1;
    }
    jclass mapCls = (*vm_env)->GetObjectClass(vm_env, mapObj);
    jmethodID keySetMid = mapCls ? (*vm_env)->GetMethodID(vm_env, mapCls, "keySet",
                                                          "()Ljava/util/Set;") : NULL;
    if (!keySetMid || (*vm_env)->ExceptionCheck(vm_env)) {
        if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
        if (curThread) (*vm_env)->DeleteLocalRef(vm_env, curThread);
        (*vm_env)->DeleteLocalRef(vm_env, mapObj);
        if (mapCls) (*vm_env)->DeleteLocalRef(vm_env, mapCls);
        (*vm_env)->DeleteLocalRef(vm_env, threadCls);
        return -1;
    }
    jobject setObj = (*vm_env)->CallObjectMethod(vm_env, mapObj, keySetMid);
    if (!setObj || (*vm_env)->ExceptionCheck(vm_env)) {
        if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
        if (curThread) (*vm_env)->DeleteLocalRef(vm_env, curThread);
        (*vm_env)->DeleteLocalRef(vm_env, mapObj);
        (*vm_env)->DeleteLocalRef(vm_env, mapCls);
        (*vm_env)->DeleteLocalRef(vm_env, threadCls);
        return -1;
    }
    jclass setCls = (*vm_env)->GetObjectClass(vm_env, setObj);
    jmethodID toArrayMid = setCls ? (*vm_env)->GetMethodID(vm_env, setCls, "toArray",
                                                           "()[Ljava/lang/Object;") : NULL;
    if (!toArrayMid || (*vm_env)->ExceptionCheck(vm_env)) {
        if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
        if (curThread) (*vm_env)->DeleteLocalRef(vm_env, curThread);
        (*vm_env)->DeleteLocalRef(vm_env, setObj);
        if (setCls) (*vm_env)->DeleteLocalRef(vm_env, setCls);
        (*vm_env)->DeleteLocalRef(vm_env, mapObj);
        (*vm_env)->DeleteLocalRef(vm_env, mapCls);
        (*vm_env)->DeleteLocalRef(vm_env, threadCls);
        return -1;
    }
    jobjectArray arr = (jobjectArray)(*vm_env)->CallObjectMethod(vm_env, setObj, toArrayMid);
    if (!arr || (*vm_env)->ExceptionCheck(vm_env)) {
        if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
        if (curThread) (*vm_env)->DeleteLocalRef(vm_env, curThread);
        (*vm_env)->DeleteLocalRef(vm_env, setObj);
        (*vm_env)->DeleteLocalRef(vm_env, setCls);
        (*vm_env)->DeleteLocalRef(vm_env, mapObj);
        (*vm_env)->DeleteLocalRef(vm_env, mapCls);
        (*vm_env)->DeleteLocalRef(vm_env, threadCls);
        return -1;
    }
    jsize n = (*vm_env)->GetArrayLength(vm_env, arr);
    int alive = 0;
    int hardCount = 0, softCount = 0;
    for (jsize i = 0; i < n; i++) {
        jobject t = (*vm_env)->GetObjectArrayElement(vm_env, arr, i);
        if (!t || (*vm_env)->ExceptionCheck(vm_env)) {
            if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
            if (t) (*vm_env)->DeleteLocalRef(vm_env, t);
            continue;
        }
        jboolean isAlive = (*vm_env)->CallBooleanMethod(vm_env, t, isAliveMid);
        if ((*vm_env)->ExceptionCheck(vm_env) || !isAlive) {
            if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
            (*vm_env)->DeleteLocalRef(vm_env, t);
            continue;
        }
        // Exclude ourselves (mc-server parked thread).
        if (curThread && (*vm_env)->IsSameObject(vm_env, t, curThread)) {
            (*vm_env)->DeleteLocalRef(vm_env, t);
            continue;
        }
        jstring jname = (jstring)(*vm_env)->CallObjectMethod(vm_env, t, getNameMid);
        char name[128] = "";
        if (jname && !(*vm_env)->ExceptionCheck(vm_env)) {
            const char *c = (*vm_env)->GetStringUTFChars(vm_env, jname, NULL);
            if (c) {
                snprintf(name, sizeof(name), "%s", c);
                (*vm_env)->ReleaseStringUTFChars(vm_env, jname, c);
            }
            (*vm_env)->DeleteLocalRef(vm_env, jname);
        } else {
            if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
            if (jname) (*vm_env)->DeleteLocalRef(vm_env, jname);
        }
        jboolean isDaemon = (*vm_env)->CallBooleanMethod(vm_env, t, isDaemonMid);
        if ((*vm_env)->ExceptionCheck(vm_env)) {
            (*vm_env)->ExceptionClear(vm_env);
            isDaemon = JNI_TRUE;
        }
        // Lowercase copy for keyword match.
        char lower[128];
        size_t k;
        for (k = 0; k < sizeof(lower) - 1 && name[k]; k++) {
            char ch = name[k];
            lower[k] = (ch >= 'A' && ch <= 'Z') ? (char)(ch + 32) : ch;
        }
        lower[k] = '\0';
        int isSystem =
            strcmp(name, "Reference Handler") == 0 ||
            strcmp(name, "Finalizer") == 0 ||
            strcmp(name, "Signal Dispatcher") == 0 ||
            strcmp(name, "Attach Listener") == 0 ||
            strcmp(name, "Common-Cleaner") == 0 ||
            strstr(name, "VM Thread") != NULL ||
            strstr(name, "VM Periodic") != NULL ||
            strstr(name, "Service Thread") != NULL ||
            strstr(name, "Sweeper") != NULL ||
            strstr(name, "CompilerThread") != NULL ||
            strstr(name, "GC Thread") != NULL ||
            strstr(name, "G1 ") != NULL ||
            strstr(name, "Surrogate Locker") != NULL;
        // SOFT = chunk pool that outlives Closing (Tuinity Chunk System
        // Worker, pool-*, ForkJoinPool, async helpers). HARD = everything
        // else that proves the server proper (Server thread, Netty, Console,
        // Scheduler...). "Craft Async Scheduler" has both craft+async ->
        // HARD (scheduler must die on stop; grace-over still forces exit).
        int isSoftKw =
            strstr(lower, "chunk") != NULL ||
            strstr(lower, "worker") != NULL ||
            strstr(lower, "pool-") != NULL ||
            strstr(lower, "pool ") != NULL ||
            strstr(lower, "forkjoin") != NULL ||
            strstr(lower, "commonpool") != NULL ||
            (strstr(lower, "async") != NULL && strstr(lower, "scheduler") == NULL);
        int isHardKw =
            strstr(lower, "server") != NULL ||
            strstr(lower, "craft") != NULL ||
            strstr(lower, "bukkit") != NULL ||
            strstr(lower, "paper") != NULL ||
            strstr(lower, "purpur") != NULL ||
            strstr(lower, "spigot") != NULL ||
            strstr(lower, "minecraft") != NULL ||
            strstr(lower, "netty") != NULL ||
            strstr(lower, "epoll") != NULL ||
            strstr(lower, "nio") != NULL ||
            strstr(lower, "scheduler") != NULL ||
            strstr(lower, "console") != NULL ||
            strstr(lower, "tps") != NULL;
        if (!isSystem) {
            if (isSoftKw && !isHardKw) {
                // Chunk/worker pool: soft even if non-daemon (Tuinity workers
                // may be non-daemon but still must not block restart).
                softCount++;
                alive = 1;
                LOGI("live soft server thread: '%s' daemon=%d", name, isDaemon ? 1 : 0);
            } else if (!isDaemon || isHardKw || isSoftKw) {
                hardCount++;
                alive = 1;
                LOGI("live server thread: '%s' daemon=%d", name, isDaemon ? 1 : 0);
            }
        }
        (*vm_env)->DeleteLocalRef(vm_env, t);
    }
    (*vm_env)->DeleteLocalRef(vm_env, arr);
    (*vm_env)->DeleteLocalRef(vm_env, setObj);
    (*vm_env)->DeleteLocalRef(vm_env, setCls);
    (*vm_env)->DeleteLocalRef(vm_env, mapObj);
    (*vm_env)->DeleteLocalRef(vm_env, mapCls);
    if (curThread) (*vm_env)->DeleteLocalRef(vm_env, curThread);
    (*vm_env)->DeleteLocalRef(vm_env, threadCls);
    if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
    if (hardOut) *hardOut = hardCount;
    if (softOut) *softOut = softCount;
    return alive;
}

// Backward-compat wrapper (old call sites without breakdown).
static int has_live_server_threads(JNIEnv *vm_env) {
    return has_live_server_threads_ex(vm_env, NULL, NULL);
}

// ---- helpers (from Mojo utils.c) ----
static const char **to_char_array(JNIEnv *env, jobjectArray arr) {
    int n = (*env)->GetArrayLength(env, arr);
    const char **out = (const char **) malloc(sizeof(char *) * (n > 0 ? n : 1));
    if (!out) return NULL;
    for (int i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        out[i] = s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
        if (s && !out[i]) {
            for (int j = 0; j < i; j++) {
                jstring ps = (jstring) (*env)->GetObjectArrayElement(env, arr, j);
                if (ps) (*env)->ReleaseStringUTFChars(env, ps, out[j]);
            }
            free(out);
            return NULL;
        }
    }
    return out;
}

static void free_char_array(JNIEnv *env, jobjectArray arr, const char **carr) {
    int n = (*env)->GetArrayLength(env, arr);
    for (int i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        if (s && carr[i]) (*env)->ReleaseStringUTFChars(env, s, carr[i]);
    }
    free((void *) carr);
}

static void reset_signal_handlers(void) {
    // Same as Mojo prepareSignalHandlers(): give the JVM a clean slate
    // for its own signal/crash handler detection.
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    for (int sig = SIGHUP; sig < NSIG; sig++) {
        if (sig == SIGSEGV) sa.sa_handler = SIG_IGN; // Android warns on SIG_DFL for SIGSEGV
        else sa.sa_handler = SIG_DFL;
        sigemptyset(&sa.sa_mask);
        sa.sa_flags = 0;
        sigaction(sig, &sa, NULL);
    }
}

// Our own SIGABRT trap: HotSpot does not handle SIGABRT and debuggerd often
// fails to dump a process hosting a multi-GB HotSpot heap, so a native abort
// (e.g. bionic "Pointer tag truncated" in free()) would leave zero evidence
// besides one logcat line. See abort_trap below.

// write() a /proc/self/maps snapshot (lib base addresses for offsets).
// Raw syscalls only: the crashing thread may hold the allocator lock
// (abort inside free()), so NO malloc/dladdr/logprint/unwind here.
static void dump_maps(int fd) {
    int mfd = open("/proc/self/maps", O_RDONLY);
    if (mfd < 0) return;
    char buf[2048];
    ssize_t r;
    while ((r = read(mfd, buf, sizeof(buf))) > 0) (void) write(fd, buf, (size_t) r);
    close(mfd);
}

static void abort_trap(int sig) {
    // Minimal by design: an earlier version with _Unwind_Backtrace segfaulted
    // inside the trap (HotSpot writes hs_err itself with the full Java+native
    // stack). Thread name + maps is safe and enough to correlate.
    char tname[64] = "?";
    prctl(PR_GET_NAME, (unsigned long) tname, 0, 0, 0);
    tname[sizeof(tname) - 1] = '\0';
    int fd = open("server.log", O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
        char head[256];
        int hl = snprintf(head, sizeof(head),
                          "\n[NATIVE ABORT on thread %s, see hs_err_pid*.log + logcat]\n",
                          tname);
        (void) write(fd, head, (size_t) hl);
        (void) write(fd, "--- maps (lib bases) ---\n", 27);
        dump_maps(fd);
        (void) write(fd, "--- end abort ---\n\n", 19);
        close(fd);
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_abort_trap(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = abort_trap;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    sigaction(SIGABRT, &sa, NULL);
}

// HotSpot is a separate VM: its System.out/err write to process fds 1/2,
// NOT to ART's System.out object that Kotlin TeeOutputStream wraps.
// Without this, ALL server output (Paperclip progress, Paper console, fatal
// errors) goes to /dev/null and server.log stays empty forever.
// cwd == workDir here (Kotlin did chdir before calling).
static void redirect_std_fds(void) {
    int fd = open("server.log", O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) {
        LOGE("stdout redirect: open server.log failed");
        return;
    }
    dup2(fd, 1);
    dup2(fd, 2);
    if (fd > 2) close(fd);
    LOGI("stdout/stderr -> server.log");
}

// Preload JRE native libs with absolute paths + RTLD_GLOBAL *before*
// JNI_CreateJavaVM.
//
// WHY: on modern Android (SDK 34+) the app lives in a fixed linker namespace
// (clns-N) whose search paths are frozen at creation вЂ” neither
// android_update_LD_LIBRARY_PATH nor environ LD_LIBRARY_PATH can extend it
// afterwards. When HotSpot lazily loads libnio.so, its DT_NEEDED "libnet.so"
// (bare soname) has nowhere to resolve from:
//   UnsatisfiedLinkError: libnio.so: dlopen failed: library "libnet.so"
//   not found ... in namespace clns-9
// which cascades into NoClassDefFoundError FileSystems$DefaultFileSystemHolder
// and the -8 "main class not found".
// An already-loaded object satisfies DT_NEEDED by soname with NO path search,
// so preloading the closure (in dependency order, after libjvm itself) fixes
// it regardless of namespace mechanics. Handles are intentionally kept.
static void preload_jre_libs(const char *jvm_path) {
    char libdir[4096];
    snprintf(libdir, sizeof(libdir), "%s", jvm_path);
    char *s = strrchr(libdir, '/');
    if (s) *s = '\0';                       // strip libjvm.so -> .../server
    s = strrchr(libdir, '/');
    if (s && strcmp(s + 1, "server") == 0) *s = '\0'; // -> .../lib (sibling libs live here)
    static const char *kPreload[] = {
        "libjli.so", "libjava.so", "libjimage.so", "libzip.so", "libverify.so",
        "libnet.so", "libnio.so", "libmanagement.so", "libinstrument.so",
        NULL
    };
    for (int i = 0; kPreload[i]; i++) {
        char p[4096];
        snprintf(p, sizeof(p), "%s/%s", libdir, kPreload[i]);
        void *h = dlopen(p, RTLD_NOW | RTLD_GLOBAL);
        if (h) LOGI("preload %s ok", kPreload[i]);
        else LOGI("preload %s: %s", kPreload[i], dlerror());
    }
}

// ---- diagnostics: what does the VM think java.class.path is? ----
// Proves whether -Djava.class.path passed via JNI options was honored.
// Bootstrap-only lookup (java/lang/System), so it works even when the
// app classloader is broken.
static void log_vm_classpath(JNIEnv *vm_env) {
    jclass sysCls = (*vm_env)->FindClass(vm_env, "java/lang/System");
    if (!sysCls || (*vm_env)->ExceptionCheck(vm_env)) {
        if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
        LOGI("VM java.class.path=<query failed>");
        return;
    }
    jmethodID getProp = (*vm_env)->GetStaticMethodID(vm_env, sysCls, "getProperty",
                                                     "(Ljava/lang/String;)Ljava/lang/String;");
    if (!getProp || (*vm_env)->ExceptionCheck(vm_env)) {
        if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
        LOGI("VM java.class.path=<getProperty missing>");
        (*vm_env)->DeleteLocalRef(vm_env, sysCls);
        return;
    }
    jstring key = (*vm_env)->NewStringUTF(vm_env, "java.class.path");
    jstring val = (jstring)(*vm_env)->CallStaticObjectMethod(vm_env, sysCls, getProp, key);
    if ((*vm_env)->ExceptionCheck(vm_env) || val == NULL) {
        if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
        LOGI("VM java.class.path=<null>");
    } else {
        const char *c = (*vm_env)->GetStringUTFChars(vm_env, val, NULL);
        LOGI("VM java.class.path=[%s]", c ? c : "(null)");
        if (c) (*vm_env)->ReleaseStringUTFChars(vm_env, val, c);
        (*vm_env)->DeleteLocalRef(vm_env, val);
    }
    if (key) (*vm_env)->DeleteLocalRef(vm_env, key);
    (*vm_env)->DeleteLocalRef(vm_env, sysCls);
}

// Load app class via explicit URLClassLoader built from colon-separated classpath.
//
// WHY: -Djava.class.path handed to JNI_CreateJavaVM as a JavaVMOption is
// silently IGNORED by this Bionic JRE (system FindClass misses with NO pending
// exception, hence empty g_last_error and bare -8). An explicit
// URLClassLoader over file:// URLs of the same jars does not depend on that
// property at all. Bonus: a fresh loader per start also lifts the
// "different jar needs app restart" limit of the fixed-at-creation property
// (the Kotlin-side guard stays as a safety net for property readers).
static jclass load_class_via_url(JNIEnv *vm_env, const char *classpath, const char *dotted) {
    if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
    if (!classpath || !*classpath || !dotted || !*dotted) {
        LOGE("URL loader: empty classpath or class name");
        return NULL;
    }

    int n = 1;
    for (const char *p = classpath; *p; p++) if (*p == ':') n++;
    LOGI("URL loader: %d cp entries, class %s", n, dotted);

    jclass urlCls = (*vm_env)->FindClass(vm_env, "java/net/URL");
    jmethodID urlCtor = (urlCls && !(*vm_env)->ExceptionCheck(vm_env))
        ? (*vm_env)->GetMethodID(vm_env, urlCls, "<init>", "(Ljava/lang/String;)V") : NULL;
    jclass uclCls = (*vm_env)->FindClass(vm_env, "java/net/URLClassLoader");
    jmethodID uclCtor = (uclCls && !(*vm_env)->ExceptionCheck(vm_env))
        ? (*vm_env)->GetMethodID(vm_env, uclCls, "<init>",
                                 "([Ljava/net/URL;Ljava/lang/ClassLoader;)V") : NULL;
    jmethodID loadM = (uclCls && !(*vm_env)->ExceptionCheck(vm_env))
        ? (*vm_env)->GetMethodID(vm_env, uclCls, "loadClass",
                                 "(Ljava/lang/String;)Ljava/lang/Class;") : NULL;
    jclass clCls = (*vm_env)->FindClass(vm_env, "java/lang/ClassLoader");
    jmethodID getSys = (clCls && !(*vm_env)->ExceptionCheck(vm_env))
        ? (*vm_env)->GetStaticMethodID(vm_env, clCls, "getSystemClassLoader",
                                       "()Ljava/lang/ClassLoader;") : NULL;
    if (!urlCls || !urlCtor || !uclCls || !uclCtor || !loadM || !clCls || !getSys ||
        (*vm_env)->ExceptionCheck(vm_env)) {
        if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
        set_last_error("URLClassLoader plumbing missing (java.base broken?)");
        LOGE("URL loader: plumbing missing");
        return NULL;
    }

    jobjectArray urlArr = (*vm_env)->NewObjectArray(vm_env, n, urlCls, NULL);
    if (!urlArr || (*vm_env)->ExceptionCheck(vm_env)) {
        if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
        set_last_error("URL loader: NewObjectArray failed");
        return NULL;
    }
    char *cp = strdup(classpath);
    if (!cp) return NULL;
    int idx = 0;
    char *cur = cp;
    for (;;) {
        char *sep = strchr(cur, ':');
        if (sep) *sep = '\0';
        char urlbuf[4096];
        snprintf(urlbuf, sizeof(urlbuf), "file://%s", cur);
        LOGI("URL loader cp[%d]=%s", idx, urlbuf);
        jstring us = (*vm_env)->NewStringUTF(vm_env, urlbuf);
        jobject u = (us && !(*vm_env)->ExceptionCheck(vm_env))
            ? (*vm_env)->NewObject(vm_env, urlCls, urlCtor, us) : NULL;
        if (!u || (*vm_env)->ExceptionCheck(vm_env)) {
            capture_vm_exception(vm_env, "bad classpath URL");
            LOGE("URL loader: bad URL %s", urlbuf);
            if (us) (*vm_env)->DeleteLocalRef(vm_env, us);
            free(cp);
            return NULL;
        }
        (*vm_env)->SetObjectArrayElement(vm_env, urlArr, idx++, u);
        (*vm_env)->DeleteLocalRef(vm_env, u);
        if (us) (*vm_env)->DeleteLocalRef(vm_env, us);
        if (!sep) break;
        cur = sep + 1;
    }
    free(cp);

    jobject parent = (*vm_env)->CallStaticObjectMethod(vm_env, clCls, getSys);
    if (!parent || (*vm_env)->ExceptionCheck(vm_env)) {
        if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
        set_last_error("URL loader: getSystemClassLoader failed");
        LOGE("URL loader: no system loader");
        return NULL;
    }
    jobject ucl = (*vm_env)->NewObject(vm_env, uclCls, uclCtor, urlArr, parent);
    if (!ucl || (*vm_env)->ExceptionCheck(vm_env)) {
        capture_vm_exception(vm_env, "URLClassLoader.<init> failed");
        LOGE("URL loader: ctor failed");
        return NULL;
    }
    jstring dn = (*vm_env)->NewStringUTF(vm_env, dotted);
    jclass mainCls = (jclass)(*vm_env)->CallObjectMethod(vm_env, ucl, loadM, dn);
    if (!mainCls || (*vm_env)->ExceptionCheck(vm_env)) {
        capture_vm_exception(vm_env, "URLClassLoader.loadClass failed");
        LOGE("URL loader: loadClass %s failed", dotted);
        if (dn) (*vm_env)->DeleteLocalRef(vm_env, dn);
        (*vm_env)->DeleteLocalRef(vm_env, ucl);
        return NULL;
    }
    if (dn) (*vm_env)->DeleteLocalRef(vm_env, dn);
    LOGI("URL loader: loaded %s", dotted);
    // Mimic `java -jar`: the app loader is also the context loader
    // (Paperclip/plug-ins may consult it for resources).
    jclass thrCls = (*vm_env)->FindClass(vm_env, "java/lang/Thread");
    if (thrCls && !(*vm_env)->ExceptionCheck(vm_env)) {
        jmethodID curT = (*vm_env)->GetStaticMethodID(vm_env, thrCls, "currentThread",
                                                      "()Ljava/lang/Thread;");
        jmethodID setC = curT ? (*vm_env)->GetMethodID(vm_env, thrCls, "setContextClassLoader",
                                                       "(Ljava/lang/ClassLoader;)V") : NULL;
        if (curT && setC) {
            jobject t = (*vm_env)->CallStaticObjectMethod(vm_env, thrCls, curT);
            if (t && !(*vm_env)->ExceptionCheck(vm_env))
                (*vm_env)->CallVoidMethod(vm_env, t, setC, ucl);
            if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
            if (t) (*vm_env)->DeleteLocalRef(vm_env, t);
        } else if ((*vm_env)->ExceptionCheck(vm_env)) {
            (*vm_env)->ExceptionClear(vm_env);
        }
        (*vm_env)->DeleteLocalRef(vm_env, thrCls);
    } else if ((*vm_env)->ExceptionCheck(vm_env)) {
        (*vm_env)->ExceptionClear(vm_env);
    }
    // mainCls keeps the loader alive for the whole server run
    // (local ref frame lives until DetachCurrentThread after main returns).
    (*vm_env)->DeleteLocalRef(vm_env, ucl);
    return mainCls;
}

// ---------------------------------------------------------------------------
// public JNI
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_pockethost_app_jvm_PocketJvm_nativeLoadJVM(JNIEnv *env, jobject thiz,
        jstring vmpath, jobjectArray java_args,
        jstring mainClass, jobjectArray appArgs, jstring classpath) {
    pthread_mutex_lock(&g_vm_lock);
    // Fresh attempt: drop stale text from a previous server run so
    // server.log [РґРµС‚Р°Р»С–:] never shows yesterday's error.
    g_last_error[0] = '\0';

    const char *jvm_path = (*env)->GetStringUTFChars(env, vmpath, NULL);
    if (!jvm_path) { pthread_mutex_unlock(&g_vm_lock); return -100; }

    JNIEnv *vm_env = NULL;

    if (g_vm == NULL) {
        // ---- first start in this process: dlopen + create VM ----
        g_jvm_handle = dlopen(jvm_path, RTLD_NOW | RTLD_GLOBAL);
        if (!g_jvm_handle) {
            char msg[1024];
            snprintf(msg, sizeof(msg), "dlopen libjvm failed: %s", dlerror());
            (*env)->ReleaseStringUTFChars(env, vmpath, jvm_path);
            pthread_mutex_unlock(&g_vm_lock);
            throwRuntime(env, msg);
            return -1;
        }
        JNI_CreateJavaVM_t createVM = (JNI_CreateJavaVM_t) dlsym(g_jvm_handle, "JNI_CreateJavaVM");
        if (!createVM) {
            (*env)->ReleaseStringUTFChars(env, vmpath, jvm_path);
            pthread_mutex_unlock(&g_vm_lock);
            throwRuntime(env, "dlsym JNI_CreateJavaVM failed");
            return -2;
        }
        // Sibling libs must be visible before the VM lazily pulls them
        // (see preload_jre_libs). Same namespace as libjvm -> DT_NEEDED hits.
        preload_jre_libs(jvm_path);

        int userCount = (*env)->GetArrayLength(env, java_args);
        int totalCount = userCount + 2; // + exit + abort hooks
        JavaVMOption *opts = (JavaVMOption *) calloc(totalCount, sizeof(JavaVMOption));
        const char **uargs = to_char_array(env, java_args);
        if (!opts || !uargs) {
            (*env)->ReleaseStringUTFChars(env, vmpath, jvm_path);
            pthread_mutex_unlock(&g_vm_lock);
            throwRuntime(env, "OOM reading VM args");
            return -3;
        }
        for (int i = 0; i < userCount; i++) {
            LOGI("VM arg: %s", uargs[i] ? uargs[i] : "(null)");
            opts[i].optionString = (char *) uargs[i];
        }
        opts[userCount].optionString = "exit";
        opts[userCount].extraInfo = (void *) vm_exit_hook;
        opts[userCount + 1].optionString = "abort";
        opts[userCount + 1].extraInfo = (void *) vm_abort_hook;

        JavaVMInitArgs initArgs;
        initArgs.nOptions = totalCount;
        initArgs.options = opts;
        initArgs.ignoreUnrecognized = JNI_TRUE;
        initArgs.version = JNI_VERSION_1_6;

        reset_signal_handlers();
        jint rc = createVM(&g_vm, &vm_env, &initArgs);
        free_char_array(env, java_args, uargs);
        free(opts);
        (*env)->ReleaseStringUTFChars(env, vmpath, jvm_path);

        if (rc != JNI_OK || !g_vm || !vm_env) {
            dlclose(g_jvm_handle);
            g_jvm_handle = NULL; g_vm = NULL;
            char msg[128];
            snprintf(msg, sizeof(msg), "JNI_CreateJavaVM failed: %d", rc);
            pthread_mutex_unlock(&g_vm_lock);
            throwRuntime(env, msg);
            return -4;
        }
        LOGI("JVM created");
    } else {
        // ---- VM already exists: reuse it (same-process restart of same server).
        // NOTE: -Djava.class.path is fixed at creation time, so a DIFFERENT
        // server jar needs an app restart. Kotlin checks this before calling.
        // STOP->START FIX 2026-09-07: СЏРєС‰Рѕ СЃС‚Р°СЂРёР№ mc-РїРѕС‚С–Рє С‰Рµ РІСЃРµСЂРµРґРёРЅС–
        // main/parking (g_in_server_main==1), РЅРѕРІРёР№ РќР• СЃРјС–С” Р·Р°С…РѕРґРёС‚Рё:
        // СЃРєРёРґР°РЅРЅСЏ С„Р»Р°РіС–РІ РЅРёР¶С‡Рµ РїРѕРІРµСЂС‚Р°Р»Рѕ Р± СЃС‚Р°СЂРёР№ РїРѕС‚С–Рє Р· no-enum wait
        // РЅР°Р·Р°Рґ РІ enum (Threads_lock), Р° РІР»Р°СЃРЅРёР№ AttachCurrentThread РєР»РёРЅРёС‚СЊ
        // РЅР° С‚РѕРјСѓ Р¶ lock -> РѕР±РёРґРІР° РјРѕРІС‡РєРё РІРёСЃСЏС‚СЊ (РЅРµРјР° Reusing/calling main),
        // РІ server.log С‚РёС€Р°, С‡РµСЂРµР· 10СЃ liveness-poll РїРёС€Рµ "РџСЂРѕС†РµСЃ Р·Р°РІРµСЂС€РµРЅРѕ".
        // Р§РµСЃРЅР° РІС–РґРјРѕРІР° -11 Р‘Р•Р— throw (РєРѕРґ РјР°С” РґС–Р№С‚Рё РІ Kotlin СЏРє exitCode):
        // Kotlin РїРѕРєР°Р¶Рµ "Р·Р°С‡РµРєР°Р№, СЃС‚Р°СЂРёР№ РїРѕС‚С–Рє РІРёС…РѕРґРёС‚СЊ", СЂРµС‚СЂР°Р№ РїСЂРѕР№РґРµ С‡РёСЃС‚Рѕ.
        LOGI("reuse entry: prevInMain=%d", atomic_load(&g_in_server_main));
        if (atomic_load(&g_in_server_main)) {
            (*env)->ReleaseStringUTFChars(env, vmpath, jvm_path);
            pthread_mutex_unlock(&g_vm_lock);
            set_last_error("РїРѕРїРµСЂРµРґРЅС–Р№ СЃРµСЂРІРµСЂРЅРёР№ РїРѕС‚С–Рє С‰Рµ Р·Р°РІРµСЂС€СѓС”С‚СЊСЃСЏ вЂ” Р·Р°С‡РµРєР°Р№ РєС–Р»СЊРєР° СЃРµРєСѓРЅРґ С– С‚РёСЃРЅРё Start С‰Рµ СЂР°Р·");
            LOGW("reuse refused: prev thread still in main, -11 (no flags touched)");
            return -11;
        }
        (*env)->ReleaseStringUTFChars(env, vmpath, jvm_path);
        LOGI("reuse attach begin...");
        jint rc = (*g_vm)->AttachCurrentThread(g_vm, &vm_env, NULL);
        LOGI("reuse attach end rc=%d env=%p", rc, (void*)vm_env);
        if (rc != JNI_OK || !vm_env) {
            pthread_mutex_unlock(&g_vm_lock);
            throwRuntime(env, "AttachCurrentThread failed");
            return -5;
        }
        LOGI("Reusing existing JVM");
    }

    // What classpath did the VM actually pick up from -Djava.class.path?
    // (diagnostic gold for silent -8: honored vs ignored)
    log_vm_classpath(vm_env);
    // HotSpot ignores SIGABRT: trap it ourselves to capture the native stack
    // (debuggerd cannot dump a HotSpot-hosting process).
    install_abort_trap();
    // Server output must land in server.log (HotSpot bypasses ART System.out).
    redirect_std_fds();

    // Explicit classpath for the URLClassLoader fallback (see below).
    // strdup + immediate release: no pinned JNI string across the server run.
    char *cp_copy = NULL;
    if (classpath != NULL) {
        const char *tmp = (*env)->GetStringUTFChars(env, classpath, NULL);
        if (tmp) {
            cp_copy = strdup(tmp);
            (*env)->ReleaseStringUTFChars(env, classpath, tmp);
        }
    }
    LOGI("classpath param: %s", cp_copy ? cp_copy : "(null)");

    // ---- build app args array inside the VM ----
    int numAppArgs = (*env)->GetArrayLength(env, appArgs);
    const char **appChars = to_char_array(env, appArgs);
    if (!appChars && numAppArgs > 0) {
        free(cp_copy);
        (*g_vm)->DetachCurrentThread(g_vm);
        pthread_mutex_unlock(&g_vm_lock);
        throwRuntime(env, "OOM reading app args");
        return -6;
    }
    jclass strCls = (*vm_env)->FindClass(vm_env, "java/lang/String");
    jobjectArray vm_appArgs = (*vm_env)->NewObjectArray(vm_env, numAppArgs, strCls, NULL);
    for (int i = 0; i < numAppArgs; i++) {
        jstring s = (*vm_env)->NewStringUTF(vm_env, appChars[i] ? appChars[i] : "");
        (*vm_env)->SetObjectArrayElement(vm_env, vm_appArgs, i, s);
        (*vm_env)->DeleteLocalRef(vm_env, s);
    }
    free_char_array(env, appArgs, appChars);

    const char *mainUtf = (*env)->GetStringUTFChars(env, mainClass, NULL);
    char *slashed = NULL;
    char *dotted = NULL;
    if (mainUtf) {
        dotted = strdup(mainUtf);
        slashed = strdup(mainUtf);
        if (slashed) for (char *p = slashed; *p; p++) if (*p == '.') *p = '/';
        (*env)->ReleaseStringUTFChars(env, mainClass, mainUtf);
    }
    if (!slashed || !dotted) {
        free(slashed);
        free(dotted);
        free(cp_copy);
        (*g_vm)->DetachCurrentThread(g_vm);
        pthread_mutex_unlock(&g_vm_lock);
        throwRuntime(env, "OOM reading main class");
        return -7;
    }

    // From here the server main runs on THIS (background) thread.
    // Unlock so stop() can observe state; only one server thread is allowed
    // (Kotlin-side guard, same as Mojo's single-game model).
    pthread_mutex_unlock(&g_vm_lock);

    int result;
    atomic_store(&g_exit_code, 0);
    atomic_store(&g_exit_requested, 0);
    atomic_store(&g_stop_requested, 0);
    atomic_store(&g_stop_latch_ms, 0); // fresh boot: no stop latch yet
    LOGI("nativeLoadJVM enter: flags reset (stopReq=0 exitReq=0) thread=%lu",
         (unsigned long) pthread_self());
    g_server_thread = pthread_self();
    g_have_server_thread = 1;
    atomic_store(&g_in_server_main, 1);
    if (sigsetjmp(g_exit_jmp, 1) == 0) {
        LOGI("resolve main begin: %s", slashed);
        jclass mainCls = (*vm_env)->FindClass(vm_env, slashed);
        if (!mainCls || (*vm_env)->ExceptionCheck(vm_env)) {
            int hadEx = (*vm_env)->ExceptionCheck(vm_env) ? 1 : 0;
            capture_vm_exception(vm_env, "FindClass failed");
            LOGI("system FindClass miss for %s (hadException=%d), trying URLClassLoader",
                 slashed, hadEx);
            mainCls = load_class_via_url(vm_env, cp_copy, dotted);
        } else {
            LOGI("system FindClass hit for %s", slashed);
        }
        free(cp_copy);
        cp_copy = NULL;
        if (!mainCls || (*vm_env)->ExceptionCheck(vm_env)) {
            if (!(*vm_env)->ExceptionCheck(vm_env) && g_last_error[0] == '\0') {
                // Both loaders missed with no pending exception (the silent case).
                // Classpath itself is in logcat ("classpath param" line above).
                snprintf(g_last_error, sizeof(g_last_error),
                         "FindClass+URLClassLoader miss for %s, no exception (see logcat PocketJvm)",
                         slashed);
                LOGE("%s", g_last_error);
            } else {
                capture_vm_exception(vm_env, "class load failed");
            }
            LOGE("Main class not found: %s", slashed);
            result = -8;
        } else {
            jmethodID mainMid = (*vm_env)->GetStaticMethodID(vm_env, mainCls, "main", "([Ljava/lang/String;)V");
            if (!mainMid || (*vm_env)->ExceptionCheck(vm_env)) {
                capture_vm_exception(vm_env, "GetStaticMethodID(main) failed");
                LOGE("main() not found in %s", slashed);
                result = -9;
            } else {
                LOGI("Calling %s.main() with %d args", slashed, numAppArgs);
                (*vm_env)->CallStaticVoidMethod(vm_env, mainCls, mainMid, vm_appArgs);
                LOGI("main call done: exception=%d exitReq=%d", (*vm_env)->ExceptionCheck(vm_env) ? 1 : 0, atomic_load(&g_exit_requested));
                if ((*vm_env)->ExceptionCheck(vm_env)) {
                    capture_vm_exception(vm_env, "Exception in server main");
                    LOGE("Exception in server main");
                    result = -10;
                } else if (atomic_load(&g_exit_requested)) {
                    // Same-thread System.exit already flagged before we got here.
                    LOGI("Server exited during main (%d)", atomic_load(&g_exit_code));
                    result = atomic_load(&g_exit_code);
                } else {
                    // Paperclip quirk: Paperclip.Main.main() spawns the server
                    // thread (Paperclip.lambda$main$0 -> Thread.run) and returns
                    // 0 immediately while the server keeps running. Returning 0
                    // here would make Kotlin mark STOPPED + allow a second
                    // Paper on the locked world. Park while server threads live.
                    result = 0;
                    int appeared = 0;
                    for (int i = 0; i < 20 && !atomic_load(&g_exit_requested); i++) {
                        int r = has_live_server_threads(vm_env);
                        if (r > 0) { appeared = 1; break; }
                        if (r < 0) break; // JNI error: don't hang, report return
                        usleep(500 * 1000);
                    }
                    if (appeared && !atomic_load(&g_exit_requested)) {
                        LOGI("parking enter: stopReq=%d exitReq=%d (main returned, server threads alive)",
                             atomic_load(&g_stop_requested), atomic_load(&g_exit_requested));
                        // Stop grace: when Kotlin latched stopRequested (user pressed
                        // Stop / console "stop"), don't hang on straggler threads
                        // (Tuinity chunk workers) forever. The world save (Saving /
                        // Closing, port release) is already in progress on the Server
                        // thread вЂ” returning here does NOT kill it, it keeps saving
                        // in background. ~10s grace, then return anyway so future
                        // completes and restart can boot fresh.
                        // GUARANTEE (FAIL 18:08): after stop latch we exit in
                        // <= ~10s NO MATTER WHAT (even if has_live still reports
                        // alive). The grace is WALL-CLOCK (g_stop_latch_ms stamped
                        // in nativeRequestStop), evaluated BEFORE the expensive
                        // enum on every iteration AND inside the sleep ticks, so a
                        // hanging getAllStackTraces cannot block the forced exit.
                        // stop_grace counter is incremented BEFORE enum (not after)
                        // as a backstop. Soft-only (chunk/worker/pool) exits on the
                        // first stop iteration. Stop-fast path (System.exit trap
                        // -> g_exit_requested) exits within 200ms with an explicit
                        // wake log; exit is re-checked before AND after enum so the
                        // hook always wakes parking without depending on enum.
                        int stop_grace = 0;
                        int iter = 0;
                        LOGI("parking enter: time-based grace 10s (wall-clock latch), enum-independent forced exit");
                        while (!atomic_load(&g_exit_requested)) {
                            int stopReq = atomic_load(&g_stop_requested);
                            double sel = stopReq ? stop_elapsed_sec() : -1.0;
                            // Forced-exit gate FIRST, TIME-BASED: never call
                            // has_live when grace is already over (protects
                            // against a hanging enum вЂ” the 18:08 hang where
                            // stop_grace++ after enum never ran).
                            if (stopReq && (stop_grace >= 5 || sel >= 10.0)) {
                                LOGI("parking iter %d: stopReq=1 grace over (elapsed %.1fs count %d/5), finishing anyway (forced exit code 0)",
                                     iter, sel, stop_grace);
                                break;
                            }
                            // Exit re-check BEFORE the expensive enum: the hook
                            // may have fired during sleep; skipping enum avoids
                            // a Threads_lock deadlock with the exiting thread.
                            if (atomic_load(&g_exit_requested)) {
                                LOGI("parking iter %d: server exit trapped before enum (code=%d), breaking",
                                     iter, atomic_load(&g_exit_code));
                                break;
                            }
                            // STUCK-STOPPING FIX 2026-09-07: РїС–СЃР»СЏ stop latch Р’Р—РђР“РђР›Р†
                            // РЅРµ С‡С–РїР°С”РјРѕ Threads_lock. Р’РјРёСЂР°СЋС‡РёР№ Server-РїРѕС‚С–Рє,
                            // СѓР±РёС‚РёР№ С‡РµСЂРµР· pthread_exit Сѓ vm_exit_hook, РјС–Рі
                            // С‚СЂРёРјР°С‚Рё Р№РѕРіРѕ вЂ” getAllStackTraces РєР»РёРЅРёРІ РЅР°Р·Р°РІР¶РґРё,
                            // parking РЅРµ РґРѕС…РѕРґРёРІ РґРѕ grace-gate, future РІС–С‡РЅРѕ
                            // !done, Р° СЂРµСЃС‚Р°СЂС‚Рё РІС–С‡РЅРѕ РІС–РґРјРѕРІР»СЏР»Рё (STOPPING +
                            // stopReq stuck). РџС–СЃР»СЏ stop Р¶РёРІС–СЃС‚СЊ РЅРµ РІР°Р¶Р»РёРІР°:
                            // РїСЂРѕСЃС‚Рѕ С‡РµРєР°С”РјРѕ exit-РїР°СЃС‚РєСѓ Р°Р±Рѕ wall-clock grace.
                            if (stopReq) {
                                stop_grace++;
                                double selNow = stop_elapsed_sec();
                                LOGI("parking iter %d: stopReq=1 grace %d/5 elapsed=%.1fs, waiting for exit/grace (no enum)...",
                                     iter, stop_grace, selNow < 0 ? 0.0 : selNow);
                                iter++;
                                int woke_exit0 = 0, woke_grace0 = 0;
                                for (int s = 0; s < 10; s++) {
                                    if (atomic_load(&g_exit_requested)) { woke_exit0 = 1; break; }
                                    if (stop_elapsed_sec() >= 10.0) { woke_grace0 = 1; break; }
                                    usleep(200 * 1000);
                                }
                                if (woke_exit0) {
                                    LOGI("parking iter %d: server exit trapped during stop-wait (code=%d), exiting",
                                         iter, atomic_load(&g_exit_code));
                                } else if (woke_grace0) {
                                    LOGI("parking iter %d: stop grace over during stop-wait (elapsed %.1fs), exiting",
                                         iter, stop_elapsed_sec());
                                }
                                continue;
                            }
                            LOGI("parking iter %d: stopReq=%d grace=%d/5 elapsed=%.1fs, enumerating threads...",
                                 iter, stopReq, stop_grace, sel < 0 ? 0.0 : sel);
                            // Pre-enum progress: grace advances even if enum is
                            // slow вЂ” the 18:08 bug was ++ only AFTER enum.
                            if (stopReq) stop_grace++;
                            int hard = 0, soft = 0;
                            int r = has_live_server_threads_ex(vm_env, &hard, &soft);
                            LOGI("parking iter %d: stopReq=%d live hard=%d soft=%d enum=%d",
                                 iter, stopReq, hard, soft, r);
                            // Post-enum checks FIRST: the hook may have fired
                            // while enum ran (it can take seconds), and elapsed
                            // kept ticking вЂ” re-evaluate before any waiting.
                            if (atomic_load(&g_exit_requested)) {
                                LOGI("parking iter %d: server exit trapped after enum (code=%d), breaking",
                                     iter, atomic_load(&g_exit_code));
                                break;
                            }
                            if (stopReq) {
                                double sel2 = stop_elapsed_sec();
                                if (sel2 >= 10.0) {
                                    LOGI("parking iter %d: stopReq=1 grace over after enum (elapsed %.1fs), finishing anyway (forced exit code 0)",
                                         iter, sel2);
                                    break;
                                }
                            }
                            if (r == 0) {
                                LOGI("parking iter %d: server threads died, finishing wait", iter);
                                break;
                            }
                            if (r < 0) {
                                LOGI("parking iter %d: thread enum failed, finishing wait", iter);
                                break;
                            }
                            if (stopReq) {
                                if (hard == 0 && soft > 0) {
                                    // Only chunk/worker/pool left after stop: world
                                    // saved already (Kotlin shortcut returned on
                                    // portFree+Closing). Don't burn full 10s.
                                    if (stop_grace >= 1) {
                                        LOGI("parking iter %d: only soft (chunk/worker/pool) threads left after stop (soft=%d), grace over, finishing wait anyway",
                                             iter, soft);
                                        break;
                                    }
                                    LOGI("parking iter %d: only soft threads left, grace %d/5", iter, stop_grace);
                                } else {
                                    LOGI("parking iter %d: stopReq=1 grace %d/5 elapsed=%.1fs, hard=%d soft=%d, waiting...",
                                         iter, stop_grace, stop_elapsed_sec(), hard, soft);
                                }
                                if (stop_grace >= 5) {
                                    LOGI("parking iter %d: stop requested, grace over (count %d/5 elapsed %.1fs), finishing wait anyway",
                                         iter, stop_grace, stop_elapsed_sec());
                                    break;
                                }
                            } else {
                                LOGI("parking iter %d: waiting (hard=%d soft=%d)...", iter, hard, soft);
                            }
                            iter++;
                            // Interruptible 2s sleep: 10 x 200ms so the exit latch
                            // is seen within 200ms, not 2s solid. EVERY tick checks
                            // the exit flag AND the wall-clock grace вЂ” the sleep
                            // NEVER blocks the forced exit (18:08: vm_exit_hook
                            // during sleep must wake us here with an explicit log).
                            int woke_exit = 0, woke_grace = 0;
                            for (int s = 0; s < 10; s++) {
                                if (atomic_load(&g_exit_requested)) { woke_exit = 1; break; }
                                if (stopReq && stop_elapsed_sec() >= 10.0) { woke_grace = 1; break; }
                                usleep(200 * 1000);
                            }
                            if (woke_exit) {
                                LOGI("parking iter %d: server exit trapped during sleep (code=%d), exiting sleep",
                                     iter, atomic_load(&g_exit_code));
                            } else if (woke_grace) {
                                LOGI("parking iter %d: grace over during sleep (elapsed %.1fs), exiting sleep",
                                     iter, stop_elapsed_sec());
                            }
                        }
                        // Parking loop exited: step logs on EVERY exit step so logcat
                        // shows exactly where a stuck exit stops (20:56 hang:
                        // "trapped during stop-wait" appeared, "nativeLoadJVM
                        // exit" never did, flag stayed 1, reuses refused -11).
                        LOGI("parking exit begin: exitReq=%d stopReq=%d iter=%d grace=%d/5",
                             atomic_load(&g_exit_requested),
                             atomic_load(&g_stop_requested), iter, stop_grace);
                        if (atomic_load(&g_exit_requested)) {
                            // NO JNI HERE (no ExceptionCheck/Clear): that call
                            // needs a ThreadInVM transition which blocks on a
                            // safepoint/Threads_lock the pthread_exit'd Server
                            // thread may hold вЂ” the exact pre-fix hang. The
                            // exception state is irrelevant on exit-trap path.
                            result = atomic_load(&g_exit_code);
                            LOGI("server exit trapped during wait: %d (skipped ExceptionCheck, immediate flag clear)", result);
                        } else {
                            result = 0;
                            if ((*vm_env)->ExceptionCheck(vm_env))
                                (*vm_env)->ExceptionClear(vm_env);
                            if (atomic_load(&g_stop_requested))
                                LOGI("stop-requested parking exit, code 0 (grace used %d, elapsed %.1fs)", stop_grace, stop_elapsed_sec());
                            else
                                LOGI("parking exit: threads died naturally, code 0");
                        }
                    } else if (!appeared) {
                        LOGI("main returned, no server threads (truly stopped)");
                    }
                }
            }
        }
    } else {
        // System.exit()/abort from inside the server -> trapped, app survives.
        int ec = atomic_load(&g_exit_code);
        LOGI("Server called System.exit(%d), trapped", ec);
        if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
        result = ec;
    }
    // Shared exit: flags cleared BEFORE Detach so even a slow/hung Detach
    // cannot leave prevInMain=1 (the -11 stuck flag). g_vm_lock is already
    // free here (unlocked before main/parking at enter); no lock is held
    // across parking, so the next reuse's lock + attach can proceed.
    LOGI("clearing in_main: prev=%d -> 0 (have_server=%d) thread=%lu",
         atomic_load(&g_in_server_main), g_have_server_thread,
         (unsigned long) pthread_self());
    atomic_store(&g_in_server_main, 0);
    g_have_server_thread = 0;
    LOGI("nativeLoadJVM exit: code=%d stopReq=%d exitReq=%d", result,
         atomic_load(&g_stop_requested), atomic_load(&g_exit_requested));
    free(slashed);
    free(dotted);
    free(cp_copy);

    // Keep the VM alive for fast restart of the same server (classpath is
    // fixed at creation). Do NOT DestroyJavaVM here: re-creation in the same
    // process returns JNI_EEXISTS on HotSpot.
    LOGI("before detach thread=%lu code=%d", (unsigned long) pthread_self(), result);
    (*g_vm)->DetachCurrentThread(g_vm);
    LOGI("after detach code=%d", result);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_pockethost_app_jvm_PocketJvm_nativeChdir(JNIEnv *env, jobject thiz, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return -1;
    int r = chdir(p);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return r;
}

JNIEXPORT void JNICALL
Java_com_pockethost_app_jvm_PocketJvm_nativeSetLdLibraryPath(JNIEnv *env, jobject thiz, jstring path) {
    // Same as Mojo utils.c: poke the Android linker so libjvm's sibling
    // libs (libjava/libnio/libnet/...) resolve after we change LD_LIBRARY_PATH.
    void *libdl = dlopen("libdl.so", RTLD_LAZY);
    if (!libdl) return;
    void (*update)(const char *) = (void (*)(const char *)) dlsym(libdl, "android_update_LD_LIBRARY_PATH");
    if (!update) update = (void (*)(const char *)) dlsym(libdl, "__loader_android_update_LD_LIBRARY_PATH");
    if (!update) { dlclose(libdl); return; }
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (p) {
        LOGI("LD_LIBRARY_PATH=%s", p);
        update(p);
        (*env)->ReleaseStringUTFChars(env, path, p);
    }
    dlclose(libdl);
}

JNIEXPORT jboolean JNICALL
Java_com_pockethost_app_jvm_PocketJvm_nativeIsJvmCreated(JNIEnv *env, jobject thiz) {
    return (g_vm != NULL) ? JNI_TRUE : JNI_FALSE;
}

// FAIL 17:44 wake-up: Kotlin Stop latches this so the Paperclip parking loop
// above exits after a short grace instead of hanging on straggler threads.
// RESTART-FIX: LOG the latch so Observer can prove JNI arrived (was %d),
// and use atomic_store for cross-core visibility (plain volatile hung 17:58).
JNIEXPORT void JNICALL
Java_com_pockethost_app_jvm_PocketJvm_nativeRequestStop(JNIEnv *env, jobject thiz) {
    int was = atomic_load(&g_stop_requested);
    // Stamp FIRST, flag second: parking computes elapsed only when it sees
    // stopReq=1, and then the timestamp is already visible (same-thread release
    // sequence). Wall-clock grace => forced exit no longer depends on the loop
    // reaching stop_grace++ after a possibly-hanging enum (FAIL 18:08).
    atomic_store(&g_stop_latch_ms, mono_ms_now());
    atomic_store(&g_stop_requested, 1);
    LOGI("nativeRequestStop: latched g_stop_requested=1 (was %d) wall-clock grace started", was);
}

JNIEXPORT jstring JNICALL
Java_com_pockethost_app_jvm_PocketJvm_nativeGetLastError(JNIEnv *env, jobject thiz) {
    return (*env)->NewStringUTF(env, g_last_error);
}
