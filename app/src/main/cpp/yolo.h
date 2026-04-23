#pragma once

#include <string>
#include <vector>

#include <android/asset_manager.h>
#include <net.h>

namespace trafy {

// One detection in ORIGINAL frame coordinates (pre-letterbox undo has been
// applied). Caller owns the life of this vector.
struct Detection {
    int   cls;
    float confidence;
    float x1;
    float y1;
    float x2;
    float y2;
};

// Wrapper around an ncnn::Net configured as a YOLO object detector. Thread
// model: single-threaded. Callers must serialize detect() calls externally.
//
// Multiple instances can live side-by-side — the JNI layer uses one instance
// per "detector role" (vehicle, plate, etc.) so we don't thrash model loads
// every frame.
class YoloDetector {
public:
    // `debug_tag` prefixes any diagnostic logcat line so multi-detector logs
    // stay readable (e.g. "YoloVeh" vs "YoloPlt"). Purely cosmetic.
    explicit YoloDetector(std::string debug_tag = "Yolo");
    ~YoloDetector();

    // Load model from a .param/.bin pair stored in the APK's assets/.
    // `use_gpu` enables the Vulkan backend if the device supports it; otherwise
    // falls back to CPU automatically.
    // `target_size` is the letterboxed square inference resolution (640 for
    // YOLO11n, plate uses 640 too). Must match the model's export size.
    // Returns true on success. On failure, inspect last_error().
    bool load(AAssetManager* mgr,
              const char*    param_asset_name,
              const char*    bin_asset_name,
              bool           use_gpu,
              int            target_size = 640);

    // Detect on an RGBA8888 buffer (Android Bitmap.Config.ARGB_8888 is RGBA in
    // memory order). Returns count of detections written to `out`.
    int detect(const unsigned char* rgba,
               int                  width,
               int                  height,
               float                conf_threshold,
               float                iou_threshold,
               std::vector<Detection>& out);

    // Short human-readable summary for debug UIs.
    std::string probe() const;

    // If load() / detect() returned failure, this explains why.
    const std::string& last_error() const { return last_error_; }

    bool loaded() const { return loaded_; }
    bool vulkan_active() const { return vulkan_active_; }

private:
    ncnn::Net   net_;
    std::string debug_tag_;
    bool        loaded_         = false;
    bool        vulkan_active_  = false;
    int         target_size_    = 640;
    float       norm_val_[3]    = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
    float       mean_val_[3]    = {0.0f, 0.0f, 0.0f};
    std::string last_error_;
    // Per-instance one-shot diagnostic flags — re-armed on each load() so the
    // user flipping Vulkan/CPU or swapping models gets a fresh set of logs.
    bool        debug_input_logged_ = false;
    bool        debug_shape_logged_ = false;
};

}  // namespace trafy
