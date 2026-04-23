#!/usr/bin/env bash
#
# verify-16kb-alignment.sh — fail if any packaged .so has a LOAD segment
# with an alignment below 16 KB (0x4000). Invoked from the `verify16KbAlignment`
# gradle task after the native libs are merged.
#
# Usage:
#   scripts/verify-16kb-alignment.sh <merged-native-libs-dir>
#
# Google Play requires 16 KB alignment for 64-bit libs on Android 15+ starting
# Nov 2025 (one-time extension to May 31 2026). This script is a safety net
# against a regression in a bundled prebuilt.

set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <merged-native-libs-dir>" >&2
  exit 2
fi

TARGET_DIR="$1"
if [[ ! -d "$TARGET_DIR" ]]; then
  echo "not a directory: $TARGET_DIR" >&2
  exit 2
fi

# Prefer llvm-readelf (bundled with the NDK); fall back to readelf. If the NDK
# ships the tool, try there before falling back to PATH. This keeps the check
# useful on macOS dev machines where neither binary is on PATH by default.
READELF=""
if command -v llvm-readelf >/dev/null 2>&1; then
  READELF="llvm-readelf"
elif command -v readelf >/dev/null 2>&1; then
  READELF="readelf"
elif [[ -n "${ANDROID_NDK:-}${ANDROID_NDK_HOME:-}${ANDROID_NDK_ROOT:-}" ]]; then
  # NDK llvm-readelf location: <ndk>/toolchains/llvm/prebuilt/<host>/bin/llvm-readelf
  NDK_ROOT="${ANDROID_NDK:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
  for host in darwin-x86_64 linux-x86_64 windows-x86_64; do
    candidate="$NDK_ROOT/toolchains/llvm/prebuilt/$host/bin/llvm-readelf"
    if [[ -x "$candidate" ]]; then READELF="$candidate"; break; fi
  done
fi

if [[ -z "$READELF" ]]; then
  msg="verify-16kb-alignment: llvm-readelf / readelf not found — skipping (set ANDROID_NDK or add to PATH to enforce)"
  if [[ "${STRICT_VERIFY:-0}" == "1" ]]; then
    echo "$msg" >&2
    exit 3
  else
    echo "$msg"
    exit 0
  fi
fi

MIN_ALIGN_HEX="0x4000"  # 16 KB
PASSED=0
FAILED=0
FAILED_FILES=()

while IFS= read -r -d '' so; do
  rel="${so#$TARGET_DIR/}"
  # Pull "Align" column from LOAD program headers. Output lines look like:
  #   LOAD 0x000000 0x... 0x... 0x... 0x... R E 0x10000
  aligns="$("$READELF" -l "$so" 2>/dev/null | awk '/^  LOAD/ { print $NF }' | tr -d ',')"
  if [[ -z "$aligns" ]]; then
    echo "WARN: no LOAD segments found in $rel (skipping)" >&2
    continue
  fi
  ok=1
  while read -r a; do
    # $a is hex like 0x10000 or 0x1000.
    dec=$((a))
    if (( dec < 0x4000 )); then
      echo "FAIL: $rel has LOAD alignment $a (< $MIN_ALIGN_HEX)" >&2
      ok=0
    fi
  done <<< "$aligns"
  if (( ok == 1 )); then
    PASSED=$((PASSED + 1))
  else
    FAILED=$((FAILED + 1))
    FAILED_FILES+=("$rel")
  fi
done < <(find "$TARGET_DIR" -type f -name '*.so' -print0)

if (( FAILED > 0 )); then
  echo ""
  echo "verify-16kb-alignment: $FAILED library/libraries below 16 KB alignment:"
  for f in "${FAILED_FILES[@]}"; do
    echo "  - $f"
  done
  echo ""
  echo "Google Play will reject an upload with these libs. Options:"
  echo "  - Rebuild NCNN from source: scripts/setup-ncnn.sh --build-from-source"
  echo "  - Upgrade the offending dependency to a 16 KB-aligned release"
  exit 1
fi

echo "verify-16kb-alignment: $PASSED library/libraries OK"
