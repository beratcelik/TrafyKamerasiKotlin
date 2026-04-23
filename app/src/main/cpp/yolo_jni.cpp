// JNI boundary for NcnnBridge.kt.
//
// We keep multiple YoloDetector instances keyed by an integer id so the
// app can hold a long-lived vehicle detector and a long-lived plate
// detector side by side — the alternative (one global detector whose
// model gets reloaded on every frame) loses the 5-second Vulkan shader
// warm-up every time. The Kotlin layer decides what each id means
// (VehicleDetectorId=0, PlateDetectorId=1, etc.).
//
// Single-threaded access per id: the Kotlin layer serializes calls on
// each detector via one inference coroutine. The mutex below exists to
// protect the map itself (lookups/inserts), not the individual nets.

#include <jni.h>

#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>

#include "yolo.h"
#include "util_log.h"

namespace {

struct DetectorSlot {
    std::unique_ptr<trafy::YoloDetector> detector;
    std::mutex                           mu;  // serializes load/detect/release for this slot
};

std::unordered_map<int, std::unique_ptr<DetectorSlot>> g_slots;
std::mutex                                              g_slots_mu;

DetectorSlot& ensure_slot(int id, const char* debug_tag) {
    std::lock_guard<std::mutex> lk(g_slots_mu);
    auto it = g_slots.find(id);
    if (it == g_slots.end()) {
        auto slot = std::make_unique<DetectorSlot>();
        slot->detector = std::make_unique<trafy::YoloDetector>(debug_tag);
        it = g_slots.emplace(id, std::move(slot)).first;
    }
    return *it->second;
}

// Debug tag for logs. Passing the Kotlin-side name keeps logcat readable
// when two detectors log around the same time.
std::string tag_for_id(int id) {
    switch (id) {
        case 0:  return "YoloVeh";
        case 1:  return "YoloPlt";
        default: return "Yolo#" + std::to_string(id);
    }
}

jstring to_jstring(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_example_trafykamerasikotlin_data_vision_ncnn_NcnnBridge_nativeProbe(
        JNIEnv* env, jclass, jint id) {
    DetectorSlot& slot = ensure_slot(id, tag_for_id(id).c_str());
    std::lock_guard<std::mutex> lk(slot.mu);
    return to_jstring(env, slot.detector->probe());
}

JNIEXPORT jboolean JNICALL
Java_com_example_trafykamerasikotlin_data_vision_ncnn_NcnnBridge_nativeLoadModel(
        JNIEnv* env, jclass,
        jint id,
        jobject asset_manager_jobj,
        jstring param_asset_name,
        jstring bin_asset_name,
        jboolean use_gpu,
        jint target_size) {
    DetectorSlot& slot = ensure_slot(id, tag_for_id(id).c_str());
    std::lock_guard<std::mutex> lk(slot.mu);

    AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager_jobj);
    if (!mgr) {
        LOGE("AAssetManager_fromJava returned null");
        return JNI_FALSE;
    }

    const char* param_c = env->GetStringUTFChars(param_asset_name, nullptr);
    const char* bin_c   = env->GetStringUTFChars(bin_asset_name,   nullptr);

    bool ok = slot.detector->load(mgr, param_c, bin_c, use_gpu == JNI_TRUE, target_size);

    env->ReleaseStringUTFChars(param_asset_name, param_c);
    env->ReleaseStringUTFChars(bin_asset_name,   bin_c);

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_example_trafykamerasikotlin_data_vision_ncnn_NcnnBridge_nativeLastError(
        JNIEnv* env, jclass, jint id) {
    DetectorSlot& slot = ensure_slot(id, tag_for_id(id).c_str());
    std::lock_guard<std::mutex> lk(slot.mu);
    return to_jstring(env, slot.detector->last_error());
}

// Returns a flat float array: [cls, conf, x1, y1, x2, y2, cls, conf, ...].
// Empty on failure — inspect nativeLastError() for the reason.
JNIEXPORT jfloatArray JNICALL
Java_com_example_trafykamerasikotlin_data_vision_ncnn_NcnnBridge_nativeDetectBitmap(
        JNIEnv* env, jclass,
        jint id,
        jobject bitmap,
        jfloat conf_threshold,
        jfloat iou_threshold) {
    DetectorSlot& slot = ensure_slot(id, tag_for_id(id).c_str());
    std::lock_guard<std::mutex> lk(slot.mu);

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
    slot.detector->detect(static_cast<const unsigned char*>(pixels),
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

JNIEXPORT void JNICALL
Java_com_example_trafykamerasikotlin_data_vision_ncnn_NcnnBridge_nativeRelease(
        JNIEnv*, jclass, jint id) {
    std::lock_guard<std::mutex> lk(g_slots_mu);
    g_slots.erase(id);
}

}  // extern "C"
