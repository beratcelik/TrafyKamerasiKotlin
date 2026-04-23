#!/usr/bin/env bash
#
# setup-ncnn.sh — fetch Tencent's prebuilt NCNN (Android, Vulkan) and drop the
# arm64-v8a libs + headers into app/src/main/cpp/ncnn/ where CMake expects them.
#
# Usage:
#   scripts/setup-ncnn.sh                # use pinned version, Vulkan prebuilt
#   NCNN_VERSION=20260113 scripts/setup-ncnn.sh
#   scripts/setup-ncnn.sh --build-from-source
#       builds NCNN at the pinned tag with -Wl,-z,max-page-size=16384 (needed
#       if Tencent's prebuilts fail the 16 KB page-size alignment check).
#
# Idempotent. Prints SHA256 of downloaded artifact. Exits non-zero on any error.

set -euo pipefail

NCNN_VERSION="${NCNN_VERSION:-20260113}"
BUILD_FROM_SOURCE=0

for arg in "$@"; do
  case "$arg" in
    --build-from-source) BUILD_FROM_SOURCE=1 ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEST_DIR="$REPO_ROOT/app/src/main/cpp/ncnn"
ABI="arm64-v8a"

# --- sanity checks ---------------------------------------------------------
need() { command -v "$1" >/dev/null 2>&1 || { echo "missing: $1" >&2; exit 3; }; }
need curl
need unzip
need shasum

if [[ "$BUILD_FROM_SOURCE" == "1" ]]; then
  need git
  need cmake
  # ANDROID_NDK / ANDROID_NDK_HOME / ANDROID_NDK_ROOT — accept any of the usuals.
  : "${ANDROID_NDK:=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
  if [[ -z "$ANDROID_NDK" ]]; then
    echo "ANDROID_NDK (or ANDROID_NDK_HOME / ANDROID_NDK_ROOT) must point to an NDK r28c install" >&2
    exit 4
  fi
  if [[ ! -f "$ANDROID_NDK/build/cmake/android.toolchain.cmake" ]]; then
    echo "NDK toolchain not found at $ANDROID_NDK/build/cmake/android.toolchain.cmake" >&2
    exit 4
  fi
fi

# --- already set up? -------------------------------------------------------
if [[ -f "$DEST_DIR/$ABI/lib/libncnn.a" && -f "$DEST_DIR/VERSION" ]]; then
  installed="$(cat "$DEST_DIR/VERSION")"
  if [[ "$installed" == "$NCNN_VERSION" ]]; then
    echo "NCNN $NCNN_VERSION already installed at $DEST_DIR — nothing to do."
    exit 0
  else
    echo "Replacing NCNN $installed → $NCNN_VERSION ..."
    rm -rf "$DEST_DIR"
  fi
fi

mkdir -p "$DEST_DIR"

# --- prebuilt path ---------------------------------------------------------
if [[ "$BUILD_FROM_SOURCE" == "0" ]]; then
  # Tencent names their android-vulkan prebuilt archive predictably per release.
  ZIP_NAME="ncnn-${NCNN_VERSION}-android-vulkan.zip"
  URL="https://github.com/Tencent/ncnn/releases/download/${NCNN_VERSION}/${ZIP_NAME}"
  TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "$TMP_DIR"' EXIT

  echo "Downloading $URL ..."
  curl -L --fail --progress-bar -o "$TMP_DIR/$ZIP_NAME" "$URL"

  SHA="$(shasum -a 256 "$TMP_DIR/$ZIP_NAME" | awk '{print $1}')"
  SIZE="$(stat -f%z "$TMP_DIR/$ZIP_NAME" 2>/dev/null || stat -c%s "$TMP_DIR/$ZIP_NAME")"
  echo "SHA256: $SHA"
  echo "Size:   $SIZE bytes"

  echo "Extracting $ABI into $DEST_DIR ..."
  # Archive top-level directory is typically ncnn-YYYYMMDD-android-vulkan/$ABI.
  unzip -q "$TMP_DIR/$ZIP_NAME" -d "$TMP_DIR/extracted"
  INNER="$(find "$TMP_DIR/extracted" -maxdepth 1 -mindepth 1 -type d | head -n1)"
  if [[ ! -d "$INNER/$ABI" ]]; then
    echo "Expected $INNER/$ABI to exist inside the archive — archive layout changed?" >&2
    exit 5
  fi
  mkdir -p "$DEST_DIR/$ABI"
  cp -R "$INNER/$ABI/"* "$DEST_DIR/$ABI/"
else
  # --- build-from-source path ---------------------------------------------
  SRC_DIR="$(mktemp -d)"
  trap 'rm -rf "$SRC_DIR"' EXIT
  echo "Cloning Tencent/ncnn at tag $NCNN_VERSION into $SRC_DIR ..."
  git clone --depth 1 --branch "$NCNN_VERSION" --recursive https://github.com/Tencent/ncnn.git "$SRC_DIR"

  BUILD_DIR="$SRC_DIR/build-android-$ABI"
  mkdir -p "$BUILD_DIR"
  pushd "$BUILD_DIR" >/dev/null
  echo "Configuring CMake (NDK at $ANDROID_NDK) ..."
  cmake \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-24 \
    -DNCNN_VULKAN=ON \
    -DNCNN_BUILD_EXAMPLES=OFF \
    -DNCNN_BUILD_TOOLS=OFF \
    -DNCNN_BUILD_BENCHMARK=OFF \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    -DCMAKE_MODULE_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    ..
  echo "Building ..."
  cmake --build . -j "$(nproc 2>/dev/null || sysctl -n hw.ncpu)"
  # Install into a directory layout that matches the prebuilt archive
  # (${DEST_DIR}/${ABI}/{lib,include,...}).
  cmake --install . --prefix "$DEST_DIR/$ABI"
  popd >/dev/null
fi

# --- record what we installed ---------------------------------------------
echo "$NCNN_VERSION" > "$DEST_DIR/VERSION"
cat > "$DEST_DIR/README.md" <<EOF
# NCNN prebuilt (generated)

This directory is populated by \`scripts/setup-ncnn.sh\`. Do **not** commit its
contents — they are binary blobs regenerated on demand.

- NCNN version: \`$NCNN_VERSION\`
- ABI: \`$ABI\`
- Source: Tencent/ncnn GitHub Releases (Android + Vulkan prebuilt)

Rebuild with \`scripts/setup-ncnn.sh\` or \`--build-from-source\` if Play Store's
16 KB page-size verification ever rejects the prebuilt.
EOF

echo ""
echo "NCNN $NCNN_VERSION ready at $DEST_DIR/$ABI"
echo "Next: run scripts/export-yolo-ncnn.py to drop YOLO26n weights into assets."
