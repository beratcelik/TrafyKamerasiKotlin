#include "yolo.h"
#include "util_log.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <sstream>
#include <vector>

#include <gpu.h>

namespace trafy {

namespace {

// Read an asset's full contents into an owned buffer. For .param we also
// null-terminate so ncnn::Net::load_param_mem can treat it as a C string.
bool read_asset_all(AAssetManager* mgr,
                    const char*    name,
                    std::vector<unsigned char>& out,
                    bool           null_terminate) {
    AAsset* a = AAssetManager_open(mgr, name, AASSET_MODE_BUFFER);
    if (!a) return false;
    const off_t n = AAsset_getLength(a);
    if (n <= 0) { AAsset_close(a); return false; }
    out.resize(n + (null_terminate ? 1 : 0));
    const int got = AAsset_read(a, out.data(), static_cast<int>(n));
    AAsset_close(a);
    if (got != n) return false;
    if (null_terminate) out[n] = 0;
    return true;
}

// Non-maximum suppression on per-class detections (sorted by confidence desc).
void nms_sorted(const std::vector<Detection>& in,
                std::vector<Detection>&       out,
                float                         iou_threshold) {
    std::vector<float> areas(in.size());
    for (size_t i = 0; i < in.size(); ++i) {
        areas[i] = std::max(0.0f, in[i].x2 - in[i].x1) * std::max(0.0f, in[i].y2 - in[i].y1);
    }
    std::vector<int> kept;
    kept.reserve(in.size());
    for (size_t i = 0; i < in.size(); ++i) {
        bool keep = true;
        for (int j : kept) {
            const float ix = std::max(0.0f, std::min(in[i].x2, in[j].x2) - std::max(in[i].x1, in[j].x1));
            const float iy = std::max(0.0f, std::min(in[i].y2, in[j].y2) - std::max(in[i].y1, in[j].y1));
            const float inter = ix * iy;
            const float un    = areas[i] + areas[j] - inter;
            if (un > 0 && inter / un > iou_threshold) { keep = false; break; }
        }
        if (keep) kept.push_back(static_cast<int>(i));
    }
    out.clear();
    out.reserve(kept.size());
    for (int idx : kept) out.push_back(in[idx]);
}

// YOLOv8 / YOLO11 / YOLO26 (non-e2e export) share the same decode path:
// output is a single tensor of shape [1, num_classes + 4, num_anchors].
// Rows 0..3 are (cx, cy, w, h); rows 4..N are per-class logits.
void decode_yolo_output(const ncnn::Mat& out_blob,
                        int              letterbox_size,
                        int              src_w,
                        int              src_h,
                        float            scale,
                        int              pad_x,
                        int              pad_y,
                        float            conf_threshold,
                        std::vector<Detection>& raw) {
    // ncnn Mat is typically (w=num_anchors, h=num_classes+4, c=1) — treat as 2D.
    const int num_anchors = out_blob.w;
    const int num_channels = out_blob.h;
    const int num_classes  = num_channels - 4;
    if (num_classes <= 0) return;

    for (int i = 0; i < num_anchors; ++i) {
        const float cx = out_blob.row(0)[i];
        const float cy = out_blob.row(1)[i];
        const float w  = out_blob.row(2)[i];
        const float h  = out_blob.row(3)[i];

        // Pick best class for this anchor.
        int   best_cls  = -1;
        float best_conf = 0.0f;
        for (int c = 0; c < num_classes; ++c) {
            const float score = out_blob.row(4 + c)[i];
            if (score > best_conf) {
                best_conf = score;
                best_cls  = c;
            }
        }
        if (best_cls < 0 || best_conf < conf_threshold) continue;

        // Undo letterbox: coords are in 640×640 space, map back to src image.
        const float x1_lb = cx - w * 0.5f;
        const float y1_lb = cy - h * 0.5f;
        const float x2_lb = cx + w * 0.5f;
        const float y2_lb = cy + h * 0.5f;

        float x1 = (x1_lb - pad_x) / scale;
        float y1 = (y1_lb - pad_y) / scale;
        float x2 = (x2_lb - pad_x) / scale;
        float y2 = (y2_lb - pad_y) / scale;

        x1 = std::clamp(x1, 0.0f, static_cast<float>(src_w - 1));
        y1 = std::clamp(y1, 0.0f, static_cast<float>(src_h - 1));
        x2 = std::clamp(x2, 0.0f, static_cast<float>(src_w - 1));
        y2 = std::clamp(y2, 0.0f, static_cast<float>(src_h - 1));
        if (x2 <= x1 || y2 <= y1) continue;

        raw.push_back(Detection{best_cls, best_conf, x1, y1, x2, y2});
    }
}

}  // namespace

YoloDetector::YoloDetector() = default;

YoloDetector::~YoloDetector() {
    // IMPORTANT: do NOT call ncnn::destroy_gpu_instance() here.
    //
    // `destroy_gpu_instance()` is a process-wide singleton teardown that wipes
    // every Vulkan buffer pool, allocator, and device handle that any ncnn::Net
    // in the process might still own. Calling it from a per-instance destructor
    // — which is what happens on the "toggle Vulkan off" rebuild path in
    // VisionDebugViewModel — races against net_'s own buffer cleanup and
    // produces a SIGSEGV inside vkDestroyBuffer (seen on Adreno 618 / Android 13).
    //
    // Safer model: call create_gpu_instance() once per process and let the GPU
    // instance outlive individual detectors. NCNN does its own cleanup on
    // dlclose, which is the only clean shutdown path we need.
    net_.clear();
}

// Exposed to the file-scope one-shot diagnostics so they re-arm on each
// load() call (e.g. when the user flips the Vulkan toggle and we rebuild
// the detector). Without this reset we'd only ever see diagnostics from the
// very first backend configured during a process lifetime.
static bool s_debug_input_logged  = false;
static bool s_debug_shape_logged  = false;

bool YoloDetector::load(AAssetManager* mgr,
                        const char*    param_asset_name,
                        const char*    bin_asset_name,
                        bool           use_gpu) {
    last_error_.clear();
    if (!mgr) { last_error_ = "null AAssetManager"; return false; }

    s_debug_input_logged = false;
    s_debug_shape_logged = false;

    if (use_gpu) {
        ncnn::create_gpu_instance();
        const int gpu_count = ncnn::get_gpu_count();
        if (gpu_count > 0) {
            net_.opt.use_vulkan_compute = true;
            vulkan_active_ = true;
        } else {
            ncnn::destroy_gpu_instance();
            net_.opt.use_vulkan_compute = false;
            vulkan_active_ = false;
            LOGW("use_gpu requested but no Vulkan device available; falling back to CPU");
        }
    } else {
        net_.opt.use_vulkan_compute = false;
        vulkan_active_ = false;
    }

    net_.opt.num_threads = 4;

    // Load .param — text format, null-terminated, fed to load_param_mem.
    {
        std::vector<unsigned char> buf;
        if (!read_asset_all(mgr, param_asset_name, buf, /*null_terminate=*/true)) {
            last_error_ = std::string("could not read asset: ") + param_asset_name;
            return false;
        }
        if (net_.load_param_mem(reinterpret_cast<const char*>(buf.data())) != 0) {
            last_error_ = std::string("load_param_mem failed for: ") + param_asset_name;
            return false;
        }
    }
    // Load .bin — binary blob, fed to the in-memory load_model overload.
    {
        std::vector<unsigned char> buf;
        if (!read_asset_all(mgr, bin_asset_name, buf, /*null_terminate=*/false)) {
            last_error_ = std::string("could not read asset: ") + bin_asset_name;
            return false;
        }
        const int consumed = net_.load_model(buf.data());
        if (consumed == 0) {
            last_error_ = std::string("load_model returned 0 bytes for: ") + bin_asset_name;
            return false;
        }
    }

    loaded_ = true;
    LOGI("YOLO loaded: %s + %s (vulkan=%d)", param_asset_name, bin_asset_name, vulkan_active_ ? 1 : 0);
    return true;
}

int YoloDetector::detect(const unsigned char*   rgba,
                         int                    width,
                         int                    height,
                         float                  conf_threshold,
                         float                  iou_threshold,
                         std::vector<Detection>& out) {
    out.clear();
    if (!loaded_) { last_error_ = "detector not loaded"; return 0; }
    if (!rgba || width <= 0 || height <= 0) { last_error_ = "bad input"; return 0; }

    // Letterbox into a target_size_ × target_size_ square, preserving aspect.
    const int   ts    = target_size_;
    const float scale = std::min(static_cast<float>(ts) / width,
                                  static_cast<float>(ts) / height);
    const int   rw    = static_cast<int>(std::round(width * scale));
    const int   rh    = static_cast<int>(std::round(height * scale));
    const int   pad_x = (ts - rw) / 2;
    const int   pad_y = (ts - rh) / 2;

    ncnn::Mat in_resized = ncnn::Mat::from_pixels_resize(
        rgba, ncnn::Mat::PIXEL_RGBA2RGB, width, height, rw, rh);

    // Pad with 114 — matches Ultralytics' standard letterbox color.
    ncnn::Mat in(ts, ts, 3);
    in.fill(114.0f);
    ncnn::copy_make_border(in_resized, in, pad_y, ts - rh - pad_y,
                           pad_x, ts - rw - pad_x,
                           ncnn::BORDER_CONSTANT, 114.0f);
    in.substract_mean_normalize(mean_val_, norm_val_);

    // Log pixel stats once per (re)load so the CPU vs Vulkan flip both get
    // captured — previously static-local flags in a long-lived .so would have
    // suppressed everything after the first inference of the process.
    if (!s_debug_input_logged) {
        s_debug_input_logged = true;
        float mn = 1e9f, mx = -1e9f, sum = 0; int n = 0;
        for (int c = 0; c < in.c; ++c) {
            const float* p = in.channel(c);
            for (int i = 0; i < in.w * in.h; ++i) {
                mn = std::min(mn, p[i]);
                mx = std::max(mx, p[i]);
                sum += p[i];
                n++;
            }
        }
        LOGI("YOLO input post-normalize min=%.4f max=%.4f mean=%.4f w=%d h=%d c=%d",
             mn, mx, sum / std::max(1, n), in.w, in.h, in.c);
    }

    ncnn::Extractor ex = net_.create_extractor();
    // Ultralytics' NCNN export names the input blob "in0" (per the emitted
    // .param header: `Input  in0  0 1 in0`).
    const int input_rc = ex.input("in0", in);
    if (input_rc != 0) {
        last_error_ = "could not set input blob \"in0\"";
        return 0;
    }

    ncnn::Mat out_blob;
    if (ex.extract("output0", out_blob) != 0) {
        // Some ultralytics exports name the output differently — try a couple
        // of common aliases before giving up.
        if (ex.extract("output", out_blob) != 0 &&
            ex.extract("out0",   out_blob) != 0) {
            last_error_ = "could not extract output blob (tried output0/output/out0)";
            return 0;
        }
    }
    // One-shot diagnostic: log the output tensor shape + per-channel score
    // maxima so we can see whether any class anywhere in the 8400 anchors has
    // a non-trivial score. Box rows (0-3) should have pixel coordinates; class
    // rows (4-83) should have sigmoided scores in [0, 1]. Re-armed in load().
    if (!s_debug_shape_logged) {
        s_debug_shape_logged = true;
        LOGI("YOLO output shape: w=%d h=%d c=%d d=%d dims=%d elemsize=%d",
             out_blob.w, out_blob.h, out_blob.c, out_blob.d, out_blob.dims,
             (int)out_blob.elemsize);
        if (out_blob.dims >= 2 && out_blob.w > 0 && out_blob.h > 0) {
            int best_cls = -1; float best_cls_score = 0.0f; int best_anchor = -1;
            for (int c = 4; c < out_blob.h; ++c) {
                const float* row = out_blob.row(c);
                for (int i = 0; i < out_blob.w; ++i) {
                    if (row[i] > best_cls_score) {
                        best_cls_score = row[i]; best_cls = c - 4; best_anchor = i;
                    }
                }
            }
            LOGI("YOLO best class-score across all anchors: cls=%d anchor=%d score=%.6f",
                 best_cls, best_anchor, best_cls_score);
            // Dump the box regressions for the winning anchor — this is the
            // one whose detection we care about. If it's (0,0,0,0), we've
            // confirmed the box-head is broken on this backend.
            if (best_anchor >= 0 && best_anchor < out_blob.w) {
                LOGI("  winning anchor[%d] box=(%.2f,%.2f,%.2f,%.2f)",
                     best_anchor,
                     out_blob.row(0)[best_anchor],
                     out_blob.row(1)[best_anchor],
                     out_blob.row(2)[best_anchor],
                     out_blob.row(3)[best_anchor]);
            }
            // Also dump a range of score-row snapshots across the whole grid
            // to catch the case where only corner anchors are zero.
            const int anchors[] = {0, 2100, 4200, 6300, 8399};
            for (int idx : anchors) {
                if (idx >= out_blob.w) continue;
                float maxs = 0; int mcls = -1;
                for (int c = 4; c < out_blob.h; ++c) {
                    float v = out_blob.row(c)[idx];
                    if (v > maxs) { maxs = v; mcls = c - 4; }
                }
                LOGI("  anchor[%d] max-class=%d score=%.4f  box=(%.1f,%.1f,%.1f,%.1f)",
                     idx, mcls, maxs,
                     out_blob.row(0)[idx], out_blob.row(1)[idx],
                     out_blob.row(2)[idx], out_blob.row(3)[idx]);
            }
        }
    }

    std::vector<Detection> raw;
    raw.reserve(64);
    decode_yolo_output(out_blob, ts, width, height, scale, pad_x, pad_y, conf_threshold, raw);

    // Class-aware NMS: sort desc by conf, then NMS per class.
    std::sort(raw.begin(), raw.end(),
              [](const Detection& a, const Detection& b) { return a.confidence > b.confidence; });
    std::vector<int> classes;
    for (const auto& d : raw) if (std::find(classes.begin(), classes.end(), d.cls) == classes.end()) classes.push_back(d.cls);
    for (int c : classes) {
        std::vector<Detection> per_cls;
        for (const auto& d : raw) if (d.cls == c) per_cls.push_back(d);
        std::vector<Detection> kept;
        nms_sorted(per_cls, kept, iou_threshold);
        out.insert(out.end(), kept.begin(), kept.end());
    }

    return static_cast<int>(out.size());
}

std::string YoloDetector::probe() const {
    std::ostringstream ss;
    ss << "ncnn " << "(net ready=" << (loaded_ ? "yes" : "no") << ")";
    ss << " vulkan=" << (vulkan_active_ ? "on" : "off");
    if (vulkan_active_) {
        const int n = ncnn::get_gpu_count();
        ss << " gpus=" << n;
        if (n > 0) {
            const ncnn::GpuInfo& info = ncnn::get_gpu_info(0);
            ss << " name=\"" << info.device_name() << "\"";
        }
    }
    ss << " target=" << target_size_;
    return ss.str();
}

}  // namespace trafy
