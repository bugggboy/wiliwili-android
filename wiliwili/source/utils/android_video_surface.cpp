//
// Android 视频 Surface 桥接
//
// 作用：接收 Java 侧（WiliwiliActivity）创建的视频专用 SurfaceView 的 Surface，
// 转成 ANativeWindow 交给 MPVCore，让 mpv 用 vo=mediacodec_embed 直接零拷贝渲染。
// 这是 armv7 老电视（Mali-450）流畅播 4K 的唯一途径——绕开 render API 的
// 「硬解→拷回内存→上传 GL」拷贝瓶颈。
//

#if defined(__ANDROID__)

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>

#include "view/mpv_core.hpp"

// ffmpeg 的 mediacodec 硬解需要先注册 JavaVM（MediaCodec 是 Java API）。
// libavcodec.so 导出 av_jni_set_java_vm，用 dlsym 动态取，避免链接顺序问题。
typedef int (*av_jni_set_java_vm_t)(void* vm, void* log_ctx);

static JavaVM* g_jvm             = nullptr;
static jclass g_activityClass    = nullptr;   // GlobalRef
static jmethodID g_updateLayout  = nullptr;

static void wili_registerJavaVM(JNIEnv* env) {
    static bool done = false;
    if (done) return;
    if (env->GetJavaVM(&g_jvm) != JNI_OK || g_jvm == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "WILI-VIDEO", "GetJavaVM failed");
        return;
    }
    // 缓存 WiliwiliActivity 类和 updateVideoSurfaceLayout 方法，供渲染线程同步窗口位置
    jclass local = env->FindClass("com/bugggboy/wiliwili/WiliwiliActivity");
    if (local != nullptr) {
        g_activityClass = (jclass)env->NewGlobalRef(local);
        g_updateLayout = env->GetStaticMethodID(g_activityClass, "updateVideoSurfaceLayout", "(IIII)V");
        env->DeleteLocalRef(local);
    }

    static av_jni_set_java_vm_t set_vm = nullptr;
    if (set_vm == nullptr) {
        void* h = dlopen("libavcodec.so", RTLD_NOW | RTLD_GLOBAL);
        if (h == nullptr) h = dlopen("libmpv.so", RTLD_NOW | RTLD_GLOBAL);
        if (h != nullptr) set_vm = (av_jni_set_java_vm_t)dlsym(h, "av_jni_set_java_vm");
    }
    if (set_vm != nullptr) {
        int rc = set_vm(g_jvm, nullptr);
        done = true;
        __android_log_print(ANDROID_LOG_INFO, "WILI-VIDEO", "av_jni_set_java_vm rc=%d (jvm registered)", rc);
    } else {
        __android_log_print(ANDROID_LOG_ERROR, "WILI-VIDEO", "av_jni_set_java_vm not found");
    }
}

// 供 MPVCore 在 setFrameSize 里调用：把视频窗口的屏幕坐标（物理像素）同步给 Java，
// 让视频 SurfaceView 只占播放窗口那一块，而不是全屏盖住 UI。
extern "C" void wili_updateVideoLayout(int x, int y, int width, int height) {
    if (g_jvm == nullptr || g_activityClass == nullptr || g_updateLayout == nullptr) return;
    JNIEnv* env    = nullptr;
    bool attached  = false;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    if (env != nullptr) {
        env->CallStaticVoidMethod(g_activityClass, g_updateLayout, x, y, width, height);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (attached) g_jvm->DetachCurrentThread();
}

// 由 sdl_video.cpp 的 clear() 调用：控制视频 SurfaceView 可见性。
// 非播放页必须隐藏，否则首页等界面会残留视频画面。
extern "C" void wili_setVideoSurfaceVisible(bool visible) {
    if (g_jvm == nullptr || g_activityClass == nullptr) return;
    static jmethodID setVisible = nullptr;
    JNIEnv* env                 = nullptr;
    bool attached               = false;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    if (env != nullptr) {
        if (setVisible == nullptr) {
            setVisible = env->GetStaticMethodID(g_activityClass, "setVideoSurfaceVisible", "(Z)V");
        }
        if (setVisible != nullptr) {
            env->CallStaticVoidMethod(g_activityClass, setVisible, visible ? JNI_TRUE : JNI_FALSE);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (attached) g_jvm->DetachCurrentThread();
}

extern "C" {

// 当前视频 Surface 的全局引用（mpv 的 wid 需要 jobject，不是 ANativeWindow）
static jobject g_videoSurfaceRef = nullptr;

// Java: com.bugggboy.wiliwili.WiliwiliActivity.onNativeVideoSurface
JNIEXPORT void JNICALL
Java_com_bugggboy_wiliwili_WiliwiliActivity_onNativeVideoSurface(JNIEnv* env, jclass /*cls*/, jobject surface) {
    // 必须在 mediacodec 解码器初始化前注册 JVM，否则 ffmpeg 报
    // "h264_mediacodec: No Java virtual machine has been registered"，硬解失败回退软解。
    wili_registerJavaVM(env);

    // 关键：mpv-android 的 wid 在 Android 上要的是 Java Surface 的 jobject（全局引用），
    // mpv 内部自己调 ANativeWindow_fromSurface 转换。传 ANativeWindow 指针会被当成
    // 非法 jobject，触发 "JNI DETECTED ERROR: use of invalid jobject" 直接 abort。
    if (g_videoSurfaceRef != nullptr) {
        env->DeleteGlobalRef(g_videoSurfaceRef);
        g_videoSurfaceRef = nullptr;
    }
    if (surface != nullptr) {
        g_videoSurfaceRef = env->NewGlobalRef(surface);
    }
    __android_log_print(ANDROID_LOG_INFO, "WILI-VIDEO", "onNativeVideoSurface ref=%p", g_videoSurfaceRef);
    MPVCore::instance().setNativeWindow(g_videoSurfaceRef);
}

// Java: com.bugggboy.wiliwili.WiliwiliActivity.onNativeVideoSurfaceDestroyed
JNIEXPORT void JNICALL
Java_com_bugggboy_wiliwili_WiliwiliActivity_onNativeVideoSurfaceDestroyed(JNIEnv* env, jclass /*cls*/) {
    __android_log_print(ANDROID_LOG_INFO, "WILI-VIDEO", "onNativeVideoSurfaceDestroyed");
    MPVCore::instance().setNativeWindow(nullptr);
    if (g_videoSurfaceRef != nullptr) {
        env->DeleteGlobalRef(g_videoSurfaceRef);
        g_videoSurfaceRef = nullptr;
    }
}

}  // extern "C"

#endif  // __ANDROID__
