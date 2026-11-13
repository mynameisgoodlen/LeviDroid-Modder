#include <jni.h>
#include <EGL/egl.h>
#include <shadowhook.h>
#include <atomic>
#include <chrono>

namespace {
using EglSwapBuffers = EGLBoolean (EGLAPIENTRYP)(EGLDisplay, EGLSurface);
std::atomic<bool> g_initialized{false};
std::atomic<int> g_fps{0};
std::atomic<unsigned int> g_frames{0};
std::atomic<int64_t> g_window_start_ms{0};
void *g_stub = nullptr;

int64_t elapsed_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

EGLBoolean EGLAPIENTRY egl_swap_buffers_proxy(EGLDisplay display, EGLSurface surface) {
    SHADOWHOOK_STACK_SCOPE();
    g_frames.fetch_add(1, std::memory_order_relaxed);
    int64_t now = elapsed_ms();
    int64_t start = g_window_start_ms.load(std::memory_order_relaxed);
    if (start == 0) {
        g_window_start_ms.compare_exchange_strong(start, now, std::memory_order_relaxed);
    } else if (now - start >= 1000 &&
               g_window_start_ms.compare_exchange_strong(start, now, std::memory_order_relaxed)) {
        g_fps.store(static_cast<int>(g_frames.exchange(0, std::memory_order_relaxed)),
                    std::memory_order_relaxed);
    }
    return SHADOWHOOK_CALL_PREV(egl_swap_buffers_proxy, display, surface);
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_levimc_launcher_core_mods_inbuilt_nativemod_FpsMod_nativeInit(JNIEnv *, jclass) {
    if (g_initialized.load(std::memory_order_acquire)) return JNI_TRUE;
    if (shadowhook_init(SHADOWHOOK_MODE_SHARED, false) != 0) return JNI_FALSE;
    void *stub = shadowhook_hook_sym_name("libEGL.so", "eglSwapBuffers",
            reinterpret_cast<void *>(egl_swap_buffers_proxy), nullptr);
    if (!stub) return JNI_FALSE;
    g_stub = stub;
    g_window_start_ms.store(elapsed_ms(), std::memory_order_relaxed);
    g_initialized.store(true, std::memory_order_release);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_levimc_launcher_core_mods_inbuilt_nativemod_FpsMod_nativeGetFps(JNIEnv *, jclass) {
    return g_fps.load(std::memory_order_relaxed);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_levimc_launcher_core_mods_inbuilt_nativemod_FpsMod_nativeIsInitialized(JNIEnv *, jclass) {
    return g_initialized.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}
