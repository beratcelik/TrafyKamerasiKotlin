#!/usr/bin/env python3
"""
export-yolo-ncnn.py — export a YOLO detector to NCNN format and drop the
resulting .param + .bin + labels.txt into the Android app's assets so the
NcnnVehicleDetector can find them.

Default model is YOLO26n (Ultralytics 2025/2026). Pass --yolo11n for the
documented fallback if the YOLO26 export tooling misbehaves.

Usage:
    scripts/export-yolo-ncnn.py                # YOLO26n @ 640×640
    scripts/export-yolo-ncnn.py --yolo11n      # YOLO11n @ 640×640
    scripts/export-yolo-ncnn.py --imgsz 416    # smaller input (live mode later)

Requirements:
    pip install ultralytics
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSET_MODELS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "models"

# COCO classes — kept in lockstep with VehicleClass.kt's fromCocoIndex() on the
# Kotlin side. Line order = class index.
COCO_CLASSES = [
    "person", "bicycle", "car", "motorcycle", "airplane",
    "bus", "train", "truck", "boat", "traffic light",
    "fire hydrant", "stop sign", "parking meter", "bench", "bird",
    "cat", "dog", "horse", "sheep", "cow",
    "elephant", "bear", "zebra", "giraffe", "backpack",
    "umbrella", "handbag", "tie", "suitcase", "frisbee",
    "skis", "snowboard", "sports ball", "kite", "baseball bat",
    "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
    "wine glass", "cup", "fork", "knife", "spoon",
    "bowl", "banana", "apple", "sandwich", "orange",
    "broccoli", "carrot", "hot dog", "pizza", "donut",
    "cake", "chair", "couch", "potted plant", "bed",
    "dining table", "toilet", "tv", "laptop", "mouse",
    "remote", "keyboard", "cell phone", "microwave", "oven",
    "toaster", "sink", "refrigerator", "book", "clock",
    "vase", "scissors", "teddy bear", "hair drier", "toothbrush",
]


def sha256_prefix(path: Path, n: int = 16) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()[:n]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    variant = ap.add_mutually_exclusive_group()
    variant.add_argument("--yolo26n", dest="model", action="store_const", const="yolo26n",
                        help="export YOLO26n (default)")
    variant.add_argument("--yolo11n", dest="model", action="store_const", const="yolo11n",
                        help="export YOLO11n (fallback)")
    ap.add_argument("--imgsz", type=int, default=640, help="export input size (default 640)")
    ap.add_argument("--half", action="store_true", help="export FP16 weights (smaller, marginal accuracy loss)")
    ap.set_defaults(model="yolo26n")
    args = ap.parse_args()

    try:
        from ultralytics import YOLO  # type: ignore
    except ImportError:
        print("ultralytics not installed. Run: pip install ultralytics", file=sys.stderr)
        return 2

    model_name = args.model
    weights = f"{model_name}.pt"

    print(f"Loading {weights} (will auto-download if missing) ...")
    yolo = YOLO(weights)

    print(f"Exporting to NCNN (imgsz={args.imgsz}, half={args.half}) ...")
    # Ultralytics writes the NCNN artifacts into a directory next to the .pt
    # file, typically named like "yolo26n_ncnn_model/".
    export_path = yolo.export(format="ncnn", imgsz=args.imgsz, half=args.half)
    export_dir = Path(export_path)
    if not export_dir.is_dir():
        # Some versions return a file path — use its parent.
        export_dir = export_dir.parent
    print(f"Exported to: {export_dir}")

    # Locate .param and .bin — filenames vary slightly across ultralytics versions.
    param_src = next(export_dir.glob("*.param"), None)
    bin_src = next(export_dir.glob("*.bin"), None)
    if param_src is None or bin_src is None:
        print(f"Could not find .param / .bin in {export_dir}", file=sys.stderr)
        print("Export succeeded but artifact layout is unexpected — check ultralytics version.", file=sys.stderr)
        return 3

    target_dir = ASSET_MODELS_DIR / model_name
    target_dir.mkdir(parents=True, exist_ok=True)

    param_dst = target_dir / f"{model_name}.param"
    bin_dst = target_dir / f"{model_name}.bin"
    labels_dst = target_dir / "labels.txt"

    shutil.copyfile(param_src, param_dst)
    shutil.copyfile(bin_src, bin_dst)
    labels_dst.write_text("\n".join(COCO_CLASSES) + "\n", encoding="utf-8")

    print()
    print(f"Installed into {target_dir.relative_to(REPO_ROOT)}/")
    for p in (param_dst, bin_dst, labels_dst):
        size = p.stat().st_size
        if p.suffix in (".param", ".bin"):
            print(f"  {p.name:<24} {size:>10} bytes  sha256:{sha256_prefix(p)}")
        else:
            print(f"  {p.name:<24} {size:>10} bytes")

    # Ultralytics leaves the intermediate NCNN export directory and the .pt
    # weights in the current working directory. We've copied what we need into
    # the app's assets; clean the scratch up so git status stays tidy.
    scratch_dirs = [Path.cwd() / f"{model_name}_ncnn_model"]
    scratch_files = [Path.cwd() / f"{model_name}.pt"]
    for d in scratch_dirs:
        if d.is_dir():
            shutil.rmtree(d, ignore_errors=True)
    for f in scratch_files:
        try: f.unlink()
        except FileNotFoundError: pass

    print()
    print(f"Next: ./gradlew assembleDebug — {model_name} will be bundled as an app asset.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
