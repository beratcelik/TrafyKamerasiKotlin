#!/usr/bin/env bash
# Build, verify, and upload a signed release APK to https://trafy.tr
#
# Usage:
#   scripts/upload-release.sh [--mandatory] [--skip-build] [--dry-run]
#
# Flags:
#   --mandatory   Set mandatory=true (strips the user's "Later" button; use only
#                 for security-critical fixes)
#   --skip-build  Don't re-run assembleRelease; upload whatever APK already exists
#   --dry-run     Do everything except the final POST. Useful for sanity-checking.
#
# Prerequisites:
#   1. Bump versionCode + versionName in app/build.gradle.kts.
#   2. Put release notes in:
#        release-notes.en.txt   (English, shown to EN users)
#        release-notes.tr.txt   (Turkish, shown to TR users)
#   3. Secrets at ~/.trafy/release-secrets.env  (or export TRAFY_APK_TOKEN).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# ── Flags ────────────────────────────────────────────────────────────────────
MANDATORY=false
SKIP_BUILD=false
DRY_RUN=false
for arg in "$@"; do
  case "$arg" in
    --mandatory)  MANDATORY=true ;;
    --skip-build) SKIP_BUILD=true ;;
    --dry-run)    DRY_RUN=true ;;
    -h|--help)    sed -n '2,14p' "$0"; exit 0 ;;
    *)            echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

# ── Load TRAFY_APK_TOKEN from env or ~/.trafy/release-secrets.env ────────────
SECRETS_FILE="${TRAFY_SECRETS_FILE:-$HOME/.trafy/release-secrets.env}"
if [ -z "${TRAFY_APK_TOKEN:-}" ] && [ -f "$SECRETS_FILE" ]; then
  # shellcheck disable=SC1090
  set -a; source "$SECRETS_FILE"; set +a
fi
if [ -z "${TRAFY_APK_TOKEN:-}" ]; then
  echo "error: TRAFY_APK_TOKEN not set (looked in env and $SECRETS_FILE)" >&2
  exit 1
fi

# ── Parse versionCode + versionName from app/build.gradle.kts ────────────────
GRADLE=app/build.gradle.kts
VERSION_CODE=$(grep -E '^\s*versionCode\s*=\s*[0-9]+' "$GRADLE" | grep -oE '[0-9]+' | head -1)
VERSION_NAME=$(grep -E '^\s*versionName\s*=\s*"' "$GRADLE" | cut -d'"' -f2 | head -1)
if [ -z "$VERSION_CODE" ] || [ -z "$VERSION_NAME" ]; then
  echo "error: could not parse versionCode/versionName from $GRADLE" >&2
  exit 1
fi

# ── Require release notes files (gitignored) ─────────────────────────────────
NOTES_EN_FILE=release-notes.en.txt
NOTES_TR_FILE=release-notes.tr.txt
for f in "$NOTES_EN_FILE" "$NOTES_TR_FILE"; do
  if [ ! -f "$f" ]; then
    cat >&2 <<EOF
error: release notes file missing: $f

Create both of these in the project root (they're gitignored):
  $NOTES_EN_FILE
  $NOTES_TR_FILE

Format: plain text, bullets like "- Bug fix" render nicely in the in-app dialog.
Keep it short — the dialog's not big.
EOF
    exit 1
  fi
done
NOTES_EN=$(cat "$NOTES_EN_FILE")
NOTES_TR=$(cat "$NOTES_TR_FILE")

# ── Build ────────────────────────────────────────────────────────────────────
APK=app/build/outputs/apk/release/app-release.apk
if [ "$SKIP_BUILD" = false ]; then
  echo "──> ./gradlew :app:assembleRelease (versionCode=$VERSION_CODE, versionName=$VERSION_NAME)"
  ./gradlew :app:assembleRelease
fi
[ -f "$APK" ] || { echo "error: $APK not found" >&2; exit 1; }

# ── Local apksigner verify — catches debug-signed / cert-drift before upload ─
APKSIGNER=$(find "$HOME/Library/Android/sdk/build-tools" -name apksigner 2>/dev/null | sort -r | head -1 || true)
[ -z "$APKSIGNER" ] && APKSIGNER=$(find "/Applications/Android Studio.app" -name apksigner 2>/dev/null | head -1 || true)

if [ -n "$APKSIGNER" ]; then
  echo
  echo "──> local apksigner verify"
  VERIFY_OUT=$("$APKSIGNER" verify --print-certs "$APK" 2>&1)
  echo "$VERIFY_OUT" | grep -E "Verified using|certificate SHA-256" || true
  LOCAL_CERT=$(echo "$VERIFY_OUT" | grep "Signer #1 certificate SHA-256" | awk '{print $NF}')
  if [ -n "${SIGNER_CERT_SHA256_HEX:-}" ] && [ "$LOCAL_CERT" != "$SIGNER_CERT_SHA256_HEX" ]; then
    echo "error: APK cert does not match pinned cert in $SECRETS_FILE" >&2
    echo "       expected: $SIGNER_CERT_SHA256_HEX" >&2
    echo "       got:      $LOCAL_CERT" >&2
    echo "       did you rebuild with a different keystore?" >&2
    exit 1
  fi
else
  echo "warn: apksigner not found — skipping local verify (server will still verify)"
fi

APK_SIZE=$(wc -c < "$APK" | tr -d ' ')
APK_SHA=$(shasum -a 256 "$APK" | awk '{print $1}')
echo
echo "APK:       $APK"
echo "size:      $APK_SIZE bytes"
echo "sha256:    $APK_SHA"

# ── Preflight ────────────────────────────────────────────────────────────────
echo
echo "──> preflight"
PREFLIGHT=$(curl -sf https://trafy.tr/api/app/upload/preflight \
  -H "X-APK-Upload-Token: $TRAFY_APK_TOKEN")
echo "$PREFLIGHT" | python3 -m json.tool

echo "$PREFLIGHT" | grep -q '"ok":true' || { echo "error: preflight not ok" >&2; exit 1; }

# Reject if versionCode conflicts with what's already published
CURRENT_VC=$(echo "$PREFLIGHT" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('currentVersionCode') or '')")
if [ -n "$CURRENT_VC" ] && [ "$VERSION_CODE" -le "$CURRENT_VC" ]; then
  echo "error: versionCode $VERSION_CODE must be greater than currently-published $CURRENT_VC" >&2
  echo "       bump versionCode in $GRADLE and re-run" >&2
  exit 1
fi

# ── Confirm + upload ─────────────────────────────────────────────────────────
echo
echo "About to upload:"
echo "  versionCode:  $VERSION_CODE"
echo "  versionName:  $VERSION_NAME"
echo "  mandatory:    $MANDATORY"
echo "  apk sha256:   $APK_SHA"
echo "  notes (en):   $(echo "$NOTES_EN" | head -3 | sed 's/^/                /')"
echo "  notes (tr):   $(echo "$NOTES_TR" | head -3 | sed 's/^/                /')"
echo

if [ "$DRY_RUN" = true ]; then
  echo "[dry-run] skipping actual upload"
  exit 0
fi

read -rp "Proceed? (y/N) " CONFIRM
CONFIRM_LC=$(echo "$CONFIRM" | tr '[:upper:]' '[:lower:]')
if [ "$CONFIRM_LC" != "y" ]; then echo "aborted"; exit 1; fi

echo
echo "──> uploading"
RESPONSE=$(curl -s -X POST https://trafy.tr/api/app/upload \
  -H "X-APK-Upload-Token: $TRAFY_APK_TOKEN" \
  -F "apk=@$APK" \
  -F "versionCode=$VERSION_CODE" \
  -F "versionName=$VERSION_NAME" \
  -F "mandatory=$MANDATORY" \
  -F "releaseNotesEn=$NOTES_EN" \
  -F "releaseNotesTr=$NOTES_TR")

echo "$RESPONSE" | python3 -m json.tool

echo "$RESPONSE" | grep -q '"success":true' || { echo "error: upload failed" >&2; exit 1; }

echo
echo "✓ live at https://trafy.tr/app/update.json"
echo "  next 24h auto-check (or immediate manual check) will pick it up"
