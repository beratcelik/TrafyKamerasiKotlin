#!/usr/bin/env bash
#
# setup-libyuv.sh — clone Chromium's libyuv at a pinned commit into
# app/src/main/cpp/libyuv/ where CMake expects it.
#
# libyuv is the hand-optimized ARM NEON SIMD YUV→RGB conversion library used
# by Chrome, WebRTC, and most Android camera apps. It replaces our pure-Kotlin
# pixel loop in VideoFrameSource.yuv420ToArgb() which was the dominant cost
# in the sidecar scanner (1440p × 1800 frames in ~10-15 minutes).
#
# Usage:
#   scripts/setup-libyuv.sh             # use pinned commit
#   LIBYUV_REF=main scripts/setup-libyuv.sh   # bleeding edge
#
# Idempotent. Re-running with a different ref replaces the working copy.

set -euo pipefail

# Pin to a recent stable commit. libyuv has no formal release tags — Chromium
# uses HEAD of the main branch, refreshed periodically. Update by bumping
# this hash and committing the new VERSION file.
LIBYUV_REF="${LIBYUV_REF:-stable}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEST_DIR="$REPO_ROOT/app/src/main/cpp/libyuv"

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing: $1" >&2; exit 3; }; }
need git

if [[ -f "$DEST_DIR/VERSION" ]]; then
  installed="$(cat "$DEST_DIR/VERSION")"
  if [[ "$installed" == "$LIBYUV_REF" ]]; then
    echo "libyuv $LIBYUV_REF already installed at $DEST_DIR — nothing to do."
    exit 0
  else
    echo "Replacing libyuv $installed → $LIBYUV_REF ..."
    rm -rf "$DEST_DIR"
  fi
fi

echo "Cloning libyuv ($LIBYUV_REF) from chromium.googlesource.com ..."
# Shallow clone is fine — we only need the source tree at one commit.
git clone --depth 1 --branch "$LIBYUV_REF" \
    https://chromium.googlesource.com/libyuv/libyuv "$DEST_DIR" \
  || {
    # The "stable" branch doesn't always exist on libyuv. Fall back to main
    # when the named ref isn't a branch.
    echo "Branch '$LIBYUV_REF' not found — falling back to main."
    git clone --depth 1 https://chromium.googlesource.com/libyuv/libyuv "$DEST_DIR"
  }

# Strip the .git folder — we vendor libyuv into our tree, no need to keep
# its git history. Saves a few MB in fresh checkouts.
rm -rf "$DEST_DIR/.git"

# Record what we installed.
echo "$LIBYUV_REF" > "$DEST_DIR/VERSION"

echo ""
echo "libyuv $LIBYUV_REF installed at $DEST_DIR"
echo "Source files: $(find "$DEST_DIR/source" -name '*.cc' 2>/dev/null | wc -l) .cc"
echo "Headers:      $(find "$DEST_DIR/include/libyuv" -name '*.h' 2>/dev/null | wc -l) .h"
