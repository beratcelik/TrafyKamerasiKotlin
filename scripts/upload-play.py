#!/usr/bin/env python3
"""Upload the release AAB to Google Play via the Android Publisher API.

Usage:
  scripts/upload-play.py [--track production] [--status draft|completed|inProgress]
                         [--rollout 0.2] [--notes play-release-notes.txt]
                         [--aab app/build/outputs/bundle/release/app-release.aab]
                         [--dry-run]

Defaults: production track, status=draft. A draft is uploaded and attached to
the track but NOT sent for review — open Play Console → Publishing overview to
send it. Pass --status completed (or inProgress + --rollout) to submit directly.

Release notes file: the Play Console paste format, one block per locale:
  <tr-TR>
  Türkçe metin…
  </tr-TR>
  <en-US>
  English text…
  </en-US>

Credentials (standard library only; no pip installs):
  - Default, keyless: the lega.digital org blocks service-account key creation,
    so we impersonate play-publisher@trafy-play-publisher.iam.gserviceaccount.com
    with your own gcloud login (Service Account Token Creator on that account).
    One-time: `gcloud auth login beratcelik@lega.digital`.
    Override the account with PLAY_GCLOUD_ACCOUNT.
  - If a JSON key exists at PLAY_SA_KEY (default ~/.trafy/play-publisher.json),
    it is used instead (JWT signed with openssl).
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

PACKAGE = "tr.trafy.kamera"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + PACKAGE
UPLOAD_API = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/" + PACKAGE
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
PROJECT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


SERVICE_ACCOUNT = "play-publisher@trafy-play-publisher.iam.gserviceaccount.com"
GCLOUD_ACCOUNT = os.environ.get("PLAY_GCLOUD_ACCOUNT", "beratcelik@lega.digital")


def impersonated_token() -> str:
    """Short-lived SA token minted with the user's gcloud login (no key file)."""
    try:
        user_token = subprocess.run(
            ["gcloud", "auth", "print-access-token", f"--account={GCLOUD_ACCOUNT}"],
            capture_output=True, text=True, check=True).stdout.strip()
    except (FileNotFoundError, subprocess.CalledProcessError) as e:
        detail = getattr(e, "stderr", "") or str(e)
        sys.exit(f"✗ gcloud token for {GCLOUD_ACCOUNT} failed — run: gcloud auth login {GCLOUD_ACCOUNT}\n{detail}")
    url = ("https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/"
           f"{SERVICE_ACCOUNT}:generateAccessToken")
    return call(user_token, "POST", url, {"scope": [SCOPE], "lifetime": "3600s"})["accessToken"]


def access_token(key_path: str) -> str:
    with open(key_path) as f:
        key = json.load(f)
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(json.dumps({
        "iss": key["client_email"], "scope": SCOPE, "aud": key["token_uri"],
        "iat": now, "exp": now + 3600,
    }).encode())
    signing_input = f"{header}.{claims}".encode()
    with tempfile.NamedTemporaryFile("w", delete=False) as pem:
        pem.write(key["private_key"])
    try:
        os.chmod(pem.name, 0o600)
        sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", pem.name],
                             input=signing_input, capture_output=True, check=True).stdout
    finally:
        os.unlink(pem.name)
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": f"{signing_input.decode()}.{b64url(sig)}",
    }).encode()
    with urllib.request.urlopen(key["token_uri"], body) as r:
        return json.load(r)["access_token"]


def call(token, method, url, payload=None, data=None, content_type="application/json"):
    if payload is not None:
        data = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    if data is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        sys.exit(f"✗ {method} {url}\n  HTTP {e.code}: {e.read().decode(errors='replace')}")


def parse_notes(path):
    text = open(path, encoding="utf-8").read()
    notes = [{"language": lang, "text": body.strip()}
             for lang, body in re.findall(r"<([a-z]{2}-[A-Z]{2})>(.*?)</\1>", text, re.S)]
    for n in notes:
        if len(n["text"]) > 500:
            sys.exit(f"✗ {n['language']} release notes are {len(n['text'])} chars; Play allows 500")
    return notes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--aab", default=os.path.join(PROJECT_DIR, "app/build/outputs/bundle/release/app-release.aab"))
    ap.add_argument("--track", default="production")
    ap.add_argument("--status", default="draft", choices=["draft", "completed", "inProgress", "halted"])
    ap.add_argument("--rollout", type=float, help="userFraction for --status inProgress, e.g. 0.2")
    ap.add_argument("--notes", default=os.path.join(PROJECT_DIR, "play-release-notes.txt"))
    ap.add_argument("--dry-run", action="store_true", help="validate the edit, then delete it instead of committing")
    args = ap.parse_args()

    key_path = os.environ.get("PLAY_SA_KEY", os.path.expanduser("~/.trafy/play-publisher.json"))
    if not os.path.exists(args.aab):
        sys.exit(f"✗ AAB not found: {args.aab}  (run ./gradlew :app:bundleRelease)")
    if args.status == "inProgress" and not args.rollout:
        sys.exit("✗ --status inProgress needs --rollout")
    notes = parse_notes(args.notes) if os.path.exists(args.notes) else []
    if not notes:
        print(f"! no release notes ({args.notes}) — uploading without 'What's new'")

    token = access_token(key_path) if os.path.exists(key_path) else impersonated_token()
    edit = call(token, "POST", f"{API}/edits", {})
    edit_id = edit["id"]
    print(f"→ edit {edit_id}")

    size_mb = os.path.getsize(args.aab) / 1e6
    print(f"→ uploading {os.path.basename(args.aab)} ({size_mb:.1f} MB)…")
    with open(args.aab, "rb") as f:
        bundle = call(token, "POST", f"{UPLOAD_API}/edits/{edit_id}/bundles?uploadType=media",
                      data=f.read(), content_type="application/octet-stream")
    vc = bundle["versionCode"]
    print(f"✓ bundle versionCode={vc} sha256={bundle.get('sha256', '?')[:16]}…")

    release = {"versionCodes": [str(vc)], "status": args.status}
    if notes:
        release["releaseNotes"] = notes
    if args.rollout:
        release["userFraction"] = args.rollout
    call(token, "PUT", f"{API}/edits/{edit_id}/tracks/{args.track}",
         {"track": args.track, "releases": [release]})
    print(f"✓ track {args.track}: vc{vc} status={args.status}")

    call(token, "POST", f"{API}/edits/{edit_id}:validate")
    if args.dry_run:
        call(token, "DELETE", f"{API}/edits/{edit_id}")
        print("✓ validated; dry run — edit deleted, nothing published")
        return
    call(token, "POST", f"{API}/edits/{edit_id}:commit")
    print(f"✓ committed. {'Draft: send it for review in Play Console.' if args.status == 'draft' else 'Sent to Play.'}")


if __name__ == "__main__":
    main()
