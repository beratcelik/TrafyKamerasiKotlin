#!/usr/bin/env python3
"""
fetch-plate-ocr.py — download the fast-plate-ocr CCT-S v2 global model
into the app's assets.

The model is hosted inside the `fast-plate-ocr` PyPI package's model hub,
which fetches + caches it on the first `LicensePlateRecognizer(...)` call.
We piggyback on that so the file-hosting is someone else's problem.

Usage:
    scripts/fetch-plate-ocr.py

Requirements:
    pip install fast-plate-ocr
"""

from __future__ import annotations

import hashlib
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSET_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "models" / "plate_ocr"


def sha_prefix(path: Path, n: int = 16) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()[:n]


def main() -> int:
    try:
        from fast_plate_ocr import LicensePlateRecognizer  # type: ignore
    except ImportError:
        print("fast-plate-ocr not installed. Run: pip install fast-plate-ocr",
              file=sys.stderr)
        return 2

    # Trigger the download; the recognizer caches the .onnx in a stable path.
    print("Resolving cct-s-v2-global-model (downloads if not cached) ...")
    r = LicensePlateRecognizer("cct-s-v2-global-model")
    onnx_path = Path(r.model._model_path) if hasattr(r.model, "_model_path") else None
    # Fallback: read the in-process model bytes via the session.
    # fast-plate-ocr stores under ~/.cache/fast-plate-ocr/<model>/<file>
    if onnx_path is None or not onnx_path.exists():
        cache = Path.home() / ".cache" / "fast-plate-ocr" / "cct-s-v2-global-model"
        onnx_path = cache / "cct_s_v2_global.onnx"
    if not onnx_path.exists():
        print(f"Could not locate the downloaded model at {onnx_path}", file=sys.stderr)
        return 3

    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    target = ASSET_DIR / "cct_s_v2_global.onnx"
    shutil.copyfile(onnx_path, target)

    # Emit the alphabet + max-slots + input shape as a sidecar so the Android
    # code doesn't have to hardcode them in two places. Kotlin reads this at
    # model-load time.
    meta = ASSET_DIR / "meta.txt"
    meta.write_text(
        "# fast-plate-ocr cct-s-v2-global metadata\n"
        "alphabet=0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_\n"
        "pad_char=_\n"
        "max_plate_slots=10\n"
        "input_h=64\n"
        "input_w=128\n"
        "input_channels=3\n"
        "input_dtype=uint8\n"
        "padding_color=114,114,114\n",
        encoding="utf-8",
    )

    print()
    print(f"Installed into {ASSET_DIR.relative_to(REPO_ROOT)}/")
    print(f"  cct_s_v2_global.onnx  {target.stat().st_size:>10} bytes  sha256:{sha_prefix(target)}")
    print(f"  meta.txt              {meta.stat().st_size:>10} bytes")
    print()
    print("Next: ./gradlew assembleDebug — OCR model will be bundled as an app asset.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
