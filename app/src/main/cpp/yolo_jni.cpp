// JNI boundary for NcnnBridge.kt. One detector instance per process.
// The Kotlin layer serializes calls; this file assumes single-threaded access.

#include <jni.h>

#include <memory>
#include <mutex>
#include <vector>

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>

#include "yolo.h"
#include "util_log.h"

namespace {

std::unique_ptr<trafy::YoloDetector> g_detector;
std::mutex                           g_mu;

trafy::YoloDetector& ensure_detector() {
    if (!g_detector) g_detector = std::make_unique<trafy::YoloDetector>();
    return *g_detector;
}

jstring to_jstring(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

}  // namespace

extern "C" {

// --- probe: returns a short diagnostic string without loading any model -----
JNIEXPORT jstring JNICALL
Java_com_example_trafykamerasikotlin_data_vision_ncnn_NcnnBridge_nativeProbe(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lk(g_mu);
    return to_jstring(env, ensure_detector().probe());
}

// --- load a YOLO model from the APK's asset directory -----------------------
JNIEXPORT jboolean JNICALL
Java_com_example_trafykamerasikotlin_data_vision_ncnn_NcnnBridge_nativeLoadModel(
        JNIEnv* env, jclass,
        jobject asset_manager_jobj,
        jstring param_asset_name,
        jstring bin_asset_name,
        jboolean use_gpu) {
    std::lock_guard<std::mutex> lk(g_mu);

    AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager_jobj);
    if (!mgr) {
        LOGE("AAssetManager_fromJava returned null");
        return JNI_FALSE;
    }

    const char* param_c = env->GetStringUTFChars(param_asset_name, nullptr);
    const char* bin_c   = env->GetStringUTFChars(bin_asset_name,   nullptr);

    bool ok = ensure_detector().load(mgr, param_c, bin_c, use_gpu == JNI_TRUE);

    env->ReleaseStringUTFChars(param_asset_name, param_c);
    env->ReleaseStringUTFChars(bin_asset_name,   bin_c);

    return ok ? JNI_TRUE : JNI_FALSE;
}

// --- last error message from load() / detect() ------------------------------
JNIEXPORT jstring JNICALL
Java_com_example_trafykamerasikotlin_data_vision_ncnn_NcnnBridge_nativeLastError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lk(g_mu);
    return to_jstring(env, ensure_detector().last_error());
}

// --- run detection on an ARGB_8888 Android Bitmap --------------------------
// Returns a flat float array: [cls, conf, x1, y1, x2, y2, cls, conf, ...].
// Empty on failure — inspect nativeLastError() for the reason.
JNIEXPORT jfloatArray JNICALL
Java_com_example_trafykamerasikotlin_data_vision_ncnn_NcnnBridge_nativeDetectBitmap(
        JNIEnv* env, jclass,
        jobject bitmap,
        jfloat conf_threshold,
        jfloat iou_threshold) {
    std::lock_guard<std::mutex> lk(g_mu);

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed");
        return env->NewFloatArray(0);
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("bitmap must be ARGB_8888 (got format=%d)", info.format);
        return env->NewFloatArray(0);
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        LOGE("AndroidBitmap_lockPixels failed");
        return env->NewFloatArray(0);
    }

    std::vector<trafy::Detection> dets;
    dets.reserve(32);
    ensure_detector().detect(static_cast<const unsigned char*>(pixels),
                             static_cast<int>(info.width),
                             static_cast<int>(info.height),
                             conf_threshold,
                             iou_threshold,
                             dets);

    AndroidBitmap_unlockPixels(env, bitmap);

    const jsize len = static_cast<jsize>(dets.size() * 6);
    jfloatArray result = env->NewFloatArray(len);
    if (len == 0) return result;

    std::vector<jfloat> flat(len);
    for (size_t i = 0; i < dets.size(); ++i) {
        flat[i * 6 + 0] = static_cast<jfloat>(dets[i].cls);
        flat[i * 6 + 1] = dets[i].confidence;
        flat[i * 6 + 2] = dets[i].x1;
        flat[i * 6 + 3] = dets[i].y1;
        flat[i * 6 + 4] = dets[i].x2;
        flat[i * 6 + 5] = dets[i].y2;
    }
    env->SetFloatArrayRegion(result, 0, len, flat.data());
    return result;
}

// --- release detector (frees GPU resources) ---------------------------------
JNIEXPORT void JNICALL
Java_com_example_trafykamerasikotlin_data_vision_ncnn_NcnnBridge_nativeRelease(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_mu);
    g_detector.reset();
}

}  // extern "C"
