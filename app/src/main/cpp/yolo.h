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
class YoloDetector {
public:
    YoloDetector();
    ~YoloDetector();

    // Load model from a .param/.bin pair stored in the APK's assets/.
    // `use_gpu` enables the Vulkan backend if the device supports it; otherwise
    // falls back to CPU automatically.
    // Returns true on success. On failure, inspect last_error().
    bool load(AAssetManager* mgr,
              const char*    param_asset_name,
              const char*    bin_asset_name,
              bool           use_gpu);

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
    bool        loaded_         = false;
    bool        vulkan_active_  = false;
    int         target_size_    = 640;
    float       norm_val_[3]    = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
    float       mean_val_[3]    = {0.0f, 0.0f, 0.0f};
    std::string last_error_;
};

}  // namespace trafy
