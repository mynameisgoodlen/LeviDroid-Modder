// In-process log capture for the Logcat overlay's "App-Level Logging" option.
// Xiaomi's logd drops app writes to the main/system buffers, so logcat stays
// empty for the launcher and the game; hooking __android_log_buf_write (where
// android.util.Log and native __android_log_print both funnel into) captures
// every line at the source before it ever reaches logd.
#include <jni.h>
#include <android/log.h>
#include <shadowhook.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <atomic>
#include <cstdio>
#include <fcntl.h>
#include <mutex>
#include <string>
#include <thread>

namespace {

std::mutex g_mutex;
std::atomic<bool> g_active{false};
void *g_stub = nullptr;
JavaVM *g_vm = nullptr;
jclass g_sink = nullptr;
jmethodID g_on_log = nullptr;

JNIEnv *attached_env() {
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    return g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK ? env : nullptr;
}

// int __android_log_buf_write(int bufID, int prio, const char *tag, const char *msg)
int log_buf_write_proxy(int bufID, int prio, const char *tag, const char *msg) {
    SHADOWHOOK_STACK_SCOPE();
    if (g_active.load(std::memory_order_relaxed)) {
        if (JNIEnv *env = attached_env()) {
            jstring jtag = tag ? env->NewStringUTF(tag) : nullptr;
            jstring jmsg = msg ? env->NewStringUTF(msg) : nullptr;
            if (!env->ExceptionCheck()) {
                env->CallStaticVoidMethod(g_sink, g_on_log, static_cast<jint>(prio),
                                          static_cast<jlong>(syscall(SYS_gettid)), jtag, jmsg);
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (jtag) env->DeleteLocalRef(jtag);
            if (jmsg) env->DeleteLocalRef(jmsg);
        }
    }
    return SHADOWHOOK_CALL_PREV(log_buf_write_proxy, bufID, prio, tag, msg);
}

void forward_console_line(const std::string &line) {
    int prio = ANDROID_LOG_INFO;
    if (line.rfind("ERROR", 0) == 0 || line.rfind("FATAL", 0) == 0) prio = ANDROID_LOG_ERROR;
    else if (line.rfind("WARNING", 0) == 0) prio = ANDROID_LOG_WARN;
    else if (line.rfind("DEBUG", 0) == 0) prio = ANDROID_LOG_DEBUG;
    __android_log_print(prio, "MCPE", "%s", line.c_str());
}

void pump_stdio_pipe(int fd) {
    std::string pending;
    char buf[8192];
    while (true) {
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n <= 0) break;
        pending.append(buf, static_cast<size_t>(n));
        size_t pos;
        while ((pos = pending.find('\n')) != std::string::npos) {
            std::string line = pending.substr(0, pos);
            pending.erase(0, pos + 1);
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (!line.empty()) forward_console_line(line);
        }
        if (pending.size() > 64 * 1024) {
            forward_console_line(pending);
            pending.clear();
        }
    }
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("org/levimc/launcher/core/logging/AppLevelLogSink");
    if (!cls) return JNI_ERR;
    g_on_log = env->GetStaticMethodID(cls, "onNativeLog", "(IJLjava/lang/String;Ljava/lang/String;)V");
    if (!g_on_log) return JNI_ERR;
    g_sink = static_cast<jclass>(env->NewGlobalRef(cls));
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_levimc_launcher_core_logging_AppLevelLogSink_installNative(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_active.load(std::memory_order_relaxed)) return 0;
    // Idempotent inside shadowhook; only the first init takes effect.
    if (shadowhook_init(SHADOWHOOK_MODE_SHARED, false) != 0) return -1;
    void *orig = nullptr;
    void *stub = shadowhook_hook_sym_name("liblog.so", "__android_log_buf_write",
                                          reinterpret_cast<void *>(log_buf_write_proxy), &orig);
    if (!stub) return -2;
    g_stub = stub;
    g_active.store(true, std::memory_order_relaxed);
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_levimc_launcher_core_logging_AppLevelLogSink_installStdioNative(JNIEnv *, jclass) {
    static std::atomic<bool> installed{false};
    bool expected = false;
    if (!installed.compare_exchange_strong(expected, true)) return JNI_TRUE;
    int fds[2];
    if (pipe(fds) != 0) {
        installed.store(false);
        return JNI_FALSE;
    }
    if (dup2(fds[1], STDOUT_FILENO) != 0 || dup2(fds[1], STDERR_FILENO) != 0) {
        int devnull = open("/dev/null", O_WRONLY);
        if (devnull >= 0) {
            dup2(devnull, STDOUT_FILENO);
            dup2(devnull, STDERR_FILENO);
            close(devnull);
        }
        close(fds[0]);
        close(fds[1]);
        installed.store(false);
        return JNI_FALSE;
    }
    close(fds[1]);
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    std::thread(pump_stdio_pipe, fds[0]).detach();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_levimc_launcher_core_logging_AppLevelLogSink_uninstallNative(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_active.store(false, std::memory_order_relaxed);
    if (!g_stub) return JNI_TRUE;
    void *stub = g_stub;
    g_stub = nullptr;
    return shadowhook_unhook(stub) == 0 ? JNI_TRUE : JNI_FALSE;
}
