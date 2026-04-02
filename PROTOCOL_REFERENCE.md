---
name: Protocol Reference
description: Full HTTP/RTSP/TCP protocol details for HiDVR, Easytech, and GeneralPlus dashcam chipsets — connect, live, media, settings
type: reference
---

# Trafy Dashcam Communication Protocol Reference

> **Scope:** HiDVR / HiSilicon (Trafy Uno Pro), Easytech / Allwinner (Trafy Tres Pro, Trafy Dos Pro), GeneralPlus (Trafy Uno).
> All three chipsets are fully implemented and working as of 2026-04-02.
> Source: reverse-engineered GoLook APK (golook-jadx / damoa-smali) and Viidure APK (viidure-jadx), confirmed with PcapDroid packet captures.

---

## 1. HiDVR (HiSilicon)

**Device IP:** `192.168.0.1`
**Base CGI path:** `/cgi-bin/hisnet`
**Reference models:** Trafy Uno Pro
**Response format:** JavaScript-style key=value — `var key="value";`
**Transport:** Plain HTTP GET, no authentication.

---

### 1.1 Connect (Handshake)

Two-step sequence. Both steps must succeed.

| Step | Request | Success condition |
|------|---------|-------------------|
| 1 — Register client | `GET /cgi-bin/hisnet/client.cgi?&-operation=register&-ip={clientIp}` | HTTP 2xx |
| 2 — Device attributes | `GET /cgi-bin/hisnet/getdeviceattr.cgi?` | Response contains `softversion` or `model` key |

**Step 2 response format:**
```
var softversion="1.0.1.2";
var model="Hi3518EV300-CARRECORDER-GC2053";
var product="TRAFY";
```

Parsed by stripping `var ` prefix and `";` suffix. Fields: `softversion`, `model`, `product`, `boardversion`, `adasid`.

---

### 1.2 Live Stream

Recording must be stopped before the RTSP stream can be opened; the encoder cannot serve both recording and live preview simultaneously.

| Step | Request | Notes |
|------|---------|-------|
| 1. Stop recording | `GET /cgi-bin/hisnet/workmodecmd.cgi?&-cmd=stop` | |
| 2. Wait | `delay(1000 ms)` | Give camera time to release encoder |
| 3. Register client | `GET /cgi-bin/hisnet/client.cgi?&-operation=register&-ip={clientIp}` | |
| 4. Stream | `rtsp://192.168.0.1:554/livestream/1` | H.264 over RTSP/RTP |
| 5. Unregister client | `GET /cgi-bin/hisnet/client.cgi?&-operation=unregister&-ip={clientIp}` | On exit |
| 6. Resume recording | `GET /cgi-bin/hisnet/workmodecmd.cgi?&-cmd=start` | On exit |

**Player:** IjkMediaPlayer (FFmpeg-based). Standard Android players (ExoPlayer, MediaPlayer, libVLC) abort on HTTP 400 returned by the camera's RTSP OPTIONS response.

**Key IjkPlayer options:**
| Category | Option | Value | Reason |
|---|---|---|---|
| format | `rtsp_transport` | `tcp` | Avoids UDP packet loss on dashcam WiFi |
| format | `stimeout` | `5000000` | 5-second socket timeout |
| format | `analyzeduration` | `100000` | Fast stream analysis (0.1 s) |
| format | `probesize` | `1048576` | Probe up to 1 MB |
| format | `fflags` | `nobuffer` | Disables FFmpeg input buffering for live streams |
| codec | `skip_loop_filter` | `48` | Reduces CPU load |
| player | `mediacodec` | `1` | Use hardware decoder |
| player | `mediacodec-auto-rotate` | `1` | Auto-rotate for hardware decoder |
| player | `start-on-prepared` | `1` | Auto-start on prepare |
| player | `packet-buffering` | `0` | Start rendering without waiting for buffer watermark |

**Single camera:** HiDVR devices have one camera — no camera switching.

---

### 1.3 Media Browse

Recording must be stopped for the duration of browsing.

**Pre-condition:** `GET /cgi-bin/hisnet/workmodecmd.cgi?&-cmd=stop`
**Post-condition:** `GET /cgi-bin/hisnet/workmodecmd.cgi?&-cmd=start`

**SD card directories:**

| Directory key | Contents |
|---|---|
| `norm` | Normal loop recordings |
| `emr` | Event / locked recordings |
| `photo` | Still photos |

**File listing — two-step per directory:**

```
Step 1: GET /cgi-bin/hisnet/getdirfilecount.cgi?&-dir={dir}
        Response: var count="N";
        (Also sets the active directory server-side for step 2)

Step 2: GET /cgi-bin/hisnet/getdirfilelist.cgi?&-dir={dir}&-start=0&-end={N-1}
        Response: semicolon-separated SD-card paths
        e.g. "sd//norm/2024_01_15_120000.MP4;sd//norm/2024_01_15_121000.MP4;"
```

**URL construction from a file path:**

| Purpose | URL |
|---|---|
| Stream / download | `http://192.168.0.1/{path}` |
| Thumbnail | `http://192.168.0.1/{path without extension}.thm` (auto-generated `.thm` sidecars) |
| Photos | thumbnail URL = httpUrl (no .thm for photos) |

**Supported file types:** `.mp4`, `.h264`, `.mov`, `.avi` (video); `.jpg`, `.jpeg` (photo). Sidecar files `.thm` and `.txt` are filtered out from display.

**Delete:**
```
GET /cgi-bin/hisnet/deletefile.cgi?&-name={path}
```
`path` = the raw SD-card path from the file list (e.g. `sd//norm/file.MP4`), no `http://ip` prefix.

---

### 1.4 Settings

**Settings definition (available options):**
```
GET /app/bin/cammenu.xml
```
XML structure:
```xml
<camera>
  <menu id="MEDIAMODE" title="Video Resolution">
    <item id="1080P30" content="1080P 30fps"/>
    <item id="720P30"  content="720P 30fps"/>
  </menu>
  <menu id="AUDIO" title="Audio">
    <item id="0" content="Off"/>
    <item id="1" content="On"/>
  </menu>
</camera>
```
Menus with no `<item>` children are action-type (Format SD, etc.) — skip them in the settings UI.

**Fetch current value — two CGI paths depending on key type:**

*"Cam" type keys* (`MIRROR`, `WATERMARKID`, `MD_SENSITIVITY`, `ENC_PAYLOAD_TYPE`, `FLIP`, `Rec_Split_Time`, `MEDIAMODE`, `ENABLEWATERMARK`, `ANTIFLICKER`):
```
GET /cgi-bin/hisnet/getcamparam.cgi?&-workmode=NORM_REC&-type={key}
```

*All other keys ("comm" type):*
```
GET /cgi-bin/hisnet/getcommparam.cgi?&-type={key}
```

**Response format:** `var value="1080P30";`

**Apply a setting:**
```
1. GET /cgi-bin/hisnet/workmodecmd.cgi?&-cmd=stop         (stop recording)
2a. cam type:  GET /cgi-bin/hisnet/setcamparam.cgi?&-workmode=NORM_REC&-type={key}&-value={value}
2b. comm type: GET /cgi-bin/hisnet/setcommparam.cgi?&-type={key}&-value={value}
3. GET /cgi-bin/hisnet/workmodecmd.cgi?&-cmd=start         (resume recording)
```
HTTP 200 = success.

---

### 1.5 WiFi Settings

```
GET /cgi-bin/hisnet/getwifi.cgi?
Response: var ssid="WiFiName"; var password="WifiPassword";

SET: GET /cgi-bin/hisnet/setwifissid.cgi?&-ssid={ssid}&-password={newPassword}
```

---

### 1.6 System Operations

| Operation | Endpoint | Notes |
|---|---|---|
| Format SD | `GET /cgi-bin/hisnet/sdcommand.cgi?&-format` | Must wait 8s after stopRecording |
| Take Photo | `GET /cgi-bin/hisnet/workmodecmd.cgi?&-cmd=trigger` | |
| Set Time | `GET /cgi-bin/hisnet/setsystime.cgi?&-time=YYYYMMDDHHmmss` | |
| SD Status | `GET /cgi-bin/hisnet/getsdstatus.cgi?` | Returns `sdstatus`, `sdfreespace`, `sdtotalspace` |

---

### 1.7 Timing Requirements

| Operation | Delay | Purpose |
|---|---|---|
| Stop recording → Register client | 1000 ms | Allow encoder to release |
| Unregister client → Start recording | 1000 ms | Clean state transition |
| Stop recording → Format SD | 8000 ms | Device-side requirement |

---
---

## 2. Easytech / Allwinner

**Device IP:** `192.168.169.1`
**Base path:** `/app/`
**Reference models:** Trafy Tres Pro (1–3 cameras, WiFi prefix `TrafyTresPro`), Trafy Dos Pro (WiFi prefix `HisDVR`)
**Response format:** JSON — `{"result":0, "info": ...}` (`result=0` = success)
**Transport:** Plain HTTP GET, no authentication.

---

### 2.1 Connect (Handshake)

Single request with a mandatory pre-delay (from reference implementation).

```
delay(500 ms)
GET /app/capability
Response: {"result":0,"info":{...}}
```

`result=0` = device present and ready. Any other value or network error = not this chipset.

> **Note:** Newer firmware returns `{"result":0,"info":{"value":"30100110020"}}`. The `info` body varies by firmware version; only `result` is used for handshake success detection.

---

### 2.2 Live Stream

| Step | Request | Notes |
|------|---------|-------|
| Enter | `GET /app/enterrecorder` | HTTP 200 = registered. Must be called before RTSP. |
| Camera count | See below | Determines how many camera tabs to show |
| Switch camera | `GET /app/setparamvalue?param=switchcam&value={0\|1\|2}` | Called each time user changes camera |
| Stream | `rtsp://192.168.169.1:554` | Single URL for all cameras |
| Exit | `GET /app/exitrecorder` | Must be called when leaving Live screen |

**No recording stop/start required** — live streaming is independent of recording.

**Camera count detection — two strategies (first one that succeeds wins):**

```
Strategy 1: GET /app/capability
  → look for "camnum" field at root level: {"camnum": 3, ...}
  → or inside "info" object:              {"info": {"camnum": 3, ...}}

Strategy 2 (newer firmware fallback): GET /app/getparamitems?param=all
  → find the "switchcam" entry in the "info" array
  → camera count = number of items in its "items" array
  → if "switchcam" key is absent → single-camera device (count = 1)
```

**Camera index mapping (3-camera device, from EeasytechProtocol.smali getRtsps()):**

| switchcam value | Camera |
|---|---|
| 0 | Camera 1 (front) |
| 1 | Camera 2 (inside) |
| 2 | Camera 3 (back) |

**Architecture note:** The Easytech chipset has ONE RTSP output. `switchcam` changes which physical camera feeds into that stream. It is **not possible** to display multiple cameras simultaneously — the device is hardware-limited to one stream at a time.

**Player:** IjkMediaPlayer with same options as HiDVR (see section 1.2).

**Critical player note:** IjkPlayer requires `packet-buffering=0` and `fflags=nobuffer`. Without these, the player gets stuck in `BUFFERING_START` indefinitely because the Easytech AAC audio stream has an invalid sampling rate index (13), which breaks audio codec initialisation and prevents the player's buffer watermark from being reached.

---

### 2.3 Media Browse

**Pre-condition:** `GET /app/playback?param=enter`
**Post-condition:** `GET /app/playback?param=exit`

**SD card folders:**

| Folder key | Contents |
|---|---|
| `loop` | Normal loop recordings |
| `emr` | Event / locked recordings |
| `event` | Still photos (named `event` on-device, not `photo`) |

**File listing — paginated:**

```
GET /app/getfilelist?folder={folder}&start={start}&end={end}
```

Page size: **49 files per request** (GoLook's `getMaxFileCount()`).
`end = start + 48` (0-based inclusive).

**Response:**
```json
{
  "result": 0,
  "info": [{
    "files": [
      {
        "name": "/loop/20240115_120000_F.ts",
        "type": 2,
        "size": "4096",
        "createtime": 1705320000
      }
    ],
    "count": 42,
    "folder": "loop"
  }]
}
```

| Field | Meaning |
|---|---|
| `name` | Full SD-card path, used directly in all URLs |
| `type` | `1` = photo, `2` = video |
| `size` | File size in bytes (string) |
| `createtime` | Unix timestamp (seconds) |
| `count` | **Total** files in the folder (used for pagination: loop until `fetched >= count`) |

**URL construction from `name`:**

| Purpose | URL |
|---|---|
| Stream / download | `http://192.168.169.1{name}` e.g. `http://192.168.169.1/loop/file.ts` |
| Thumbnail | `http://192.168.169.1/app/getthumbnail?file={name}` |

**Delete:**
```
GET /app/deletefile?file={name}
```
`name` = the value from the JSON `name` field (e.g. `/loop/20240115_120000_F.ts`). HTTP 200 = success.

**Multi-camera file identification from filename suffix:**

| Suffix before extension | Camera |
|---|---|
| `_F` or `_f` | Front |
| `_B` or `_b` | Back |
| `_I` or `_i` | Inside |

Files for all cameras are returned together in a single folder request. Camera separation is done client-side by inspecting the filename suffix.

---

### 2.4 Settings

**Pre-condition:** `delay(1000 ms)` then `GET /app/setting?param=enter`
**Post-condition:** `GET /app/setting?param=exit`

**Fetch available options:**
```
GET /app/getparamitems?param=all
```
Response:
```json
{
  "info": [
    {
      "name": "rec_resolution",
      "items": ["1920x1080x30", "1280x720x30"],
      "index": [0, 1]
    },
    {
      "name": "mic",
      "items": ["on", "off"],
      "index": [0, 1]
    }
  ]
}
```
`items` = human-readable display strings.
`index` = integer indices — **these are the values used in `setparamvalue` and returned by `getparamvalue`**, not the display strings.

**Fetch current values (all at once):**
```
GET /app/getparamvalue?param=all
```
Response:
```json
{
  "result": 0,
  "info": [
    {"name": "rec_resolution", "value": "1"},
    {"name": "mic",            "value": "0"}
  ]
}
```
`value` = the index string (matches the `index` array from `getparamitems`).

**Apply a setting:**
```
GET /app/setparamvalue?param={key}&value={index}
```
HTTP 200 = success. **No recording stop/start required** (unlike HiDVR).

**Excluded keys** (action-type — skip in settings UI):

| Key | Action |
|---|---|
| `format` | Format SD card |
| `SD0` | SD card operation |
| `reset_to_default` | Factory reset |
| `Net.WIFI_AP` | WiFi AP toggle |
| `Net.WIFI_AP.SSID` | WiFi SSID |
| `Net.WIFI_AP.CryptoKey` | WiFi password |
| `switchcam` | Camera switch (live stream only) |
| `rec` | Recording on/off |

**Key reference (confirmed from EeasytechConst.java):**

| Display name | Camera key |
|---|---|
| Video Resolution | `rec_resolution` |
| Clip Length | `rec_split_duration` |
| Audio | `mic` |
| Anti-Flicker | `light_fre` |
| Video Codec | `encodec` |
| Flip & Mirror | `video_flip` |
| G-Sensor | `gsr_sensitivity` |
| Parking Mode | `parking_mode` |
| Exposure | `ev` |
| Volume | `speaker` |
| Screen Sleep | `screen_standby` |

---
---

## 3. GeneralPlus

**Device IP:** `192.168.25.1`
**Control port:** `8081` (TCP, binary GPSOCKET protocol)
**Streaming port:** `8080` (RTSP, MJPEG over RTP)
**Reference models:** Trafy Uno (manufacturer HUAXIN, firmware HX2247)
**WiFi SSID:** `HX_YZJ` (default password `12345678`)
**Transport:** Custom binary TCP protocol ("GPSOCKET"), no HTTP.
**Source:** Reverse-engineered Viidure APK (viidure-jadx) + PcapDroid captures.

---

### 3.1 Binary Packet Format

#### Command Packet (Client → Camera)

```
Offset  Size  Field         Description
------  ----  -----         -----------
0       8     Magic         "GPSOCKET" (ASCII)
8       1     Type          0x01 = CMD
9       1     CMDIndex      Monotonically increasing uint8 sequence number (wraps at 255)
10      1     Mode          MODE_* constant (see below)
11      1     CMDID         Command ID within the mode
12+     0-N   Param/Data    Optional parameter (variable length)
```

**Packet sizes by command type:**
| Type | Total size | Notes |
|---|---|---|
| Regular command | 12 bytes | No extra data |
| Extended command | 16 bytes | 4-byte parameter |
| SetMode | 13 bytes | 1-byte device mode |
| PlaybackCmd | 14 bytes | 2-byte fileIndex (uint16 LE) |
| Stop | 13 bytes | 1-byte stop code |
| GetNameList | 15 bytes | 1-byte type + 2-byte startIdx |
| SetParameter (enum) | 17 bytes | 4-byte settingId + 1-byte value |
| SetParameter (string) | 17 + N bytes | 4-byte settingId + 1-byte length + N-byte value |

#### Response Packet (Camera → Client)

```
Offset  Size  Field         Description
------  ----  -----         -----------
0       8     Magic         "GPSOCKET" (ASCII)
8       1     Type          0x02 = ACK (success), 0x03 = NAK (error)
9       1     CMDIndex      Mirrors the command index
10      1     Mode          Mirrors the command mode
11      1     CMDID         Mirrors the command CMDID
12      2     DataSize      uint16 LE — number of payload bytes that follow
14+     N     Data          DataSize bytes of response payload
```

Minimum response: 14 bytes (DataSize == 0).
With payload: 14 + DataSize bytes.

**NAK responses:** Bytes 12-13 contain an error code (0xFFFE or 0xFFFF observed), NOT a data size. Do not attempt to read a payload after NAK.

**Example — AuthDevice ACK (20 bytes):**
```
47 50 53 4f 43 4b 45 54  02 00 00 05  06 00  f2 18 11 19 b0 43
G  P  S  O  C  K  E  T  ACK idx mode cmd  size=6  [6-byte payload]
```

---

### 3.2 Modes and Command IDs

**Modes (byte 10):**
| Value | Name | Purpose |
|---|---|---|
| 0x00 | MODE_GENERAL | Device control, auth, streaming |
| 0x01 | MODE_RECORD | Recording control |
| 0x02 | MODE_CAPTURE | Picture capture |
| 0x03 | MODE_PLAYBACK | File operations |
| 0x04 | MODE_MENU | Settings get/set |
| 0xFF | MODE_VENDOR | Vendor-specific (time sync) |

#### GENERAL mode (0x00) commands:

| CMDID | Name | Packet size | Response |
|---|---|---|---|
| 0x00 | SetMode | 13B (+ 1B device mode) | 14B ACK |
| 0x01 | GetDeviceStatus | 12B | 27-byte status blob |
| 0x02 | GetParameterFile | 12B | Chunked XML (242B per chunk) |
| 0x03 | PowerOff | 12B | ACK |
| 0x04 | RestartStreaming | 12B | ACK (takes ~5 seconds) |
| 0x05 | AuthDevice | 16B (+ 4B token) | ACK + 6-byte token |

**Device modes for SetMode:**
| Value | Name |
|---|---|
| 0x00 | DEVICE_MODE_RECORD (normal recording) |
| 0x02 | DEVICE_MODE_PLAYBACK (file browsing / download) |

#### MENU mode (0x04) commands:

| CMDID | Name | Packet size | Response |
|---|---|---|---|
| 0x00 | GetParameter | 16B (+ 4B settingId LE) | ACK + value index |
| 0x01 | SetParameter (enum) | 17B (+ 4B settingId LE + 1B value) | 14B ACK |
| 0x01 | SetParameter (string) | 17+N B (+ 4B settingId LE + 1B len + NB value) | 14B ACK |

#### PLAYBACK mode (0x03) commands:

| CMDID | Name | Packet size | Response |
|---|---|---|---|
| 0x00 | StartPlayback | 14B (+ 2B fileIndex LE) | ACK or NAK |
| 0x01 | Pause | 12B | ACK |
| 0x02 | GetFileCount | 12B | ACK + 2B count (uint16 LE) |
| 0x03 | GetNameList | 15B (+ 1B type + 2B startIdx LE) | ACK + entry list |
| 0x04 | GetThumbnail | 14B (+ 2B fileIndex LE) | JPEG chunks + empty ACK end-marker |
| 0x05 | GetRawData | 14B (+ 2B fileIndex LE) | Raw file data in 61440B chunks |
| 0x06 | Stop | 13B (+ 1B stop code, typically 0x41) | ACK |
| 0x07 | GetSpecificName | 14B (+ 2B fileIndex LE) | Filename string |
| 0x08 | DeleteFile | 14B (+ 2B fileIndex LE) | ACK (success) or NAK (failure) |

#### RECORD mode (0x01) commands:

| CMDID | Name | Packet size |
|---|---|---|
| 0x00 | StartRecord | 12B |

---

### 3.3 Connect (Handshake)

**TCP socket:** `192.168.25.1:8081`
**Connect timeout:** 4000 ms
**Read timeout:** 8000 ms (RestartStreaming ACK can take ~5s)

**Handshake — AuthDevice (must be first packet):**
```
Send: GPSOCKET 01 00 00 05  77 07 8c 12
                type idx mode cmd  [4-byte auth token]

Recv: GPSOCKET 02 00 00 05  06 00  [6-byte response token]
                ACK  idx mode cmd  size=6
```
Auth token is fixed: `77 07 8c 12`.
Success = Type == ACK (0x02). Failure = NAK (0x03) or no response.

**Network binding:** Uses `Network.socketFactory` from Android's `ConnectivityManager` to ensure TCP socket routes through dashcam WiFi, not cellular. Falls back to plain `Socket()` if bound network is stale.

**Sequence counter:** Global `AtomicInteger`, wraps at 255 (uint8). Persists across held session reuse.

---

### 3.4 Live Stream

**Protocol:** RTSP with MJPEG over RTP (payload type 26, JPEG/90000, 30fps).
**RTSP URL:** `rtsp://192.168.25.1:8080/?action=stream`
**Player:** Custom `MjpegRtspPlayer` (IjkPlayer's FFmpeg build lacks the MJPEG decoder).

**Sequence:**
```
1. Open TCP session to 192.168.25.1:8081 (holdOpen=true)
2. AuthDevice handshake
3. Send RestartStreaming (CMD 0x04, MODE_GENERAL) → ACK (~5s)
4. Keep TCP control session alive (camera drops RTSP when it closes)
5. Open RTSP to rtsp://192.168.25.1:8080/?action=stream
6. Receive MJPEG frames via RTP/UDP

On exit:
7. Release held TCP session (camera auto-resumes recording)
```

**RTSP exchange (from PCAP):**
```
OPTIONS  rtsp://192.168.25.1:8080/?action=stream RTSP/1.0
DESCRIBE rtsp://192.168.25.1:8080/?action=stream RTSP/1.0
  Accept: application/sdp

SDP Response:
  v=0
  o=- 10069888 0 IN IP4 192.168.25.58
  i=goplus
  m=video 0 RTP/AVP 26
  a=rtpmap:26 JPEG/90000
  a=control:streamid=0
  a=framerate:30
  m=audio 1 RTP/AVP 8
  a=rtpmap:0 PCMA/8000
  a=control:streamid=1

SETUP video → Transport: RTP/AVP/UDP;unicast;client_port=P-P+1
  Server responds with server_port=10850-10851
SETUP audio → same server ports
PLAY  → 200 OK → RTP streaming begins
TEARDOWN → on exit
```

**RTSP quirks:**
- Camera returns `RTSP/1,0` (comma instead of period) in response headers
- SDP `Content-Base` uses `rtsp://0.0.0.0:8080/` — must replace `0.0.0.0` with actual host in SETUP URLs
- Camera has internal IP `192.168.25.58` (in SDP o= and c= lines) but is accessed at `192.168.25.1`

**MjpegRtspPlayer details (RFC 2435 JPEG over RTP):**

RTP JPEG header (8 bytes after 12-byte RTP header):
```
Byte 0:      Reserved (0x00)
Bytes 1-3:   Fragment offset (uint24 BE) — byte offset in JPEG data
Byte 4:      JPEG type (0=4:2:2, 1=4:2:0)
Byte 5:      Quality (1-127 static tables, ≥128 includes quantization tables)
Byte 6:      Width / 8
Byte 7:      Height / 8
```

Frame assembly:
1. Collect all RTP packets with same timestamp
2. Sort by fragmentOffset
3. Concatenate payload data
4. If data starts with `FF D8` (SOI): raw JPEG — append `FF D9` (EOI) if missing
5. If data starts with `FF DB` (DQT) or `FF C0` (SOF): prepend `FF D8`, append `FF D9`
6. Otherwise: RFC 2435 header synthesis with standard Huffman tables (JPEG spec Annex K)
7. Decode via `BitmapFactory.decodeByteArray()` → render to `Surface`

> **GeneralPlus quirk:** Camera sends quality=1 but embeds complete JPEG data (SOI + DQT + SOF + DHT + SOS + entropy + EOI) inside RTP fragments. Raw concatenation works; RFC 2435 synthesis is only needed as a fallback.

**UDP timeouts:** 5000 ms socket timeout, max 6 consecutive timeouts (30s) before giving up.

---

### 3.5 Media Browse

All file operations use PLAYBACK mode (0x03) commands over the GPSOCKET TCP session on port 8081.

#### Fetch file list

**Sequence (single TCP session):**
```
1. SetMode(PLAYBACK=0x02) → ACK
2. GetFileCount → ACK + uint16 LE count
3. GetNameList(type=0x01, startIdx=0) → ACK + binary entry list
4. For each file: GetThumbnail(fileIndex) → JPEG chunks + empty ACK end-marker
```

**GetNameList response format:**
```
Byte 0:     Count (uint8) — number of entries

Per entry (13 bytes each):
  Byte 0:      Type code (char)
  Bytes 1-2:   File index (uint16 LE)
  Byte 3:      Year - 2000 (e.g. 0x1A = 26 → 2026)
  Byte 4:      Month
  Byte 5:      Day
  Byte 6:      Hour
  Byte 7:      Minute
  Byte 8:      Second
  Bytes 9-12:  Reserved (observed: 0x00 0xe8 0x03 0x00)
```

**Type code → file extension mapping:**
| Type code | Extension | Description |
|---|---|---|
| `A`, `V` | `.avi` | Standard AVI video |
| `J` | `.jpg` | JPEG photo |
| `L`, `K` | `.avi` | Locked / event recording |
| `S`, `O` | `.avi` | SOS recording |
| `T` | `.mov` | Time-lapse |
| `M`, `C`, `P` | `.mov` | MOV variants |
| `m`, `l`, `s`, `t` | `.mp4` | MP4 variants |

**Filename construction:** `YYYY_MM_DD_HHMMSS.ext` (e.g. `2026_04_01_181126.avi`)

**File path format:** `gp://{fileIndex}` — a virtual path scheme since GeneralPlus uses index-based access, not filesystem paths.

#### Thumbnail fetch

```
Send: PlaybackCmd(CMD_PLAYBACK_GET_THUMB=0x04, fileIndex)
Receive loop:
  chunk = receive(CMD_PLAYBACK_GET_THUMB)
  if chunk.data is empty → break (end marker)
  thumbData += chunk.data
Write thumbData as JPEG
```
Camera sends data chunk(s) followed by an empty ACK end-marker. The end-marker must be drained so it doesn't pollute the next request.

#### Download (GetRawData)

**Sequence (separate session):**
```
1. SetMode(PLAYBACK) → ACK
2. Stop(0x41) → ACK  (must stop any active RTSP before GetRawData)
3. GetRawData(fileIndex) → streaming ACK chunks (max 61440 bytes each)
```

**RIFF file size detection:**
If first chunk starts with `RIFF` (bytes `52 49 46 46`):
- Bytes 4-7: data size (uint32 LE)
- Total file size = data_size + 8
- Stop when `totalReceived >= totalExpected`

**Non-RIFF fallback:** If a chunk is smaller than 61440 bytes and no RIFF size is known, treat it as EOF. (First chunk can be < 61440; this heuristic is only used as a last resort.)

#### Delete

```
1. SetMode(PLAYBACK) → ACK
2. Stop(0x41) → ACK  (file must not be streaming)
3. DeleteFile(fileIndex) → ACK (success) or NAK (failure)
```

#### Playback (RTSP file playback)

**Sequence (held session):**
```
preparePlayback(fileIndex):
  1. SetMode(PLAYBACK) → ACK
  2. RestartStreaming → ACK (~5s, reinitialises RTSP server)
  3. StartPlayback(fileIndex) → ACK/NAK
  TCP connection held open (camera stops stream if it drops)
  
Player opens RTSP: rtsp://192.168.25.1:8080/?action=stream
  → MjpegRtspPlayer renders MJPEG frames

exitPlayback():
  1. Stop(0x41) → ACK
  2. SetMode(RECORD=0x00) → ACK
  3. StartRecord (MODE_RECORD, cmd=0x00) → ACK
  4. RestartStreaming → ACK (restart live RTSP)
  5. Release held TCP session
```

> **Critical:** Do NOT send Stop(0x41) before RestartStreaming during playback setup. The PCAP confirms the Viidure app omits it. Sending Stop causes RestartStreaming to NAK.

---

### 3.6 Settings

**Fetch full settings XML:**
```
Send: GetParameterFile (CMD 0x02, MODE_GENERAL)
Receive: Multiple ACK responses, each containing 242 bytes of XML data
Concatenate all chunks until a chunk with DataSize < 242 (last chunk)
```

**XML structure (Menu.xml):**
```xml
<Menu version="1.0">
  <Categories>
    <Category>
      <Name>系统设置</Name>
      <Settings>
        <Setting>
          <Name>分辨率</Name>
          <ID>0x0000</ID>
          <Type>0x00</Type>       <!-- 0x00=enum, 0x01=action, 0x02=string, 0x03=readonly -->
          <Default>0x00</Default>
          <Values>
            <Value>
              <Name>1080FHD 1920x1080</Name>
              <ID>0x00</ID>
            </Value>
          </Values>
        </Setting>
      </Settings>
    </Category>
  </Categories>
</Menu>
```

**Setting types:**
| Type | Meaning | UI behaviour |
|---|---|---|
| 0x00 | Enum | Dropdown / picker with Value options |
| 0x01 | Action | Button (e.g. Format SD, Factory Reset). Has optional `<Reflash>0x01</Reflash>` tag |
| 0x02 | String | Text input (e.g. WiFi SSID, password) |
| 0x03 | Readonly | Display-only (e.g. firmware version) |

**Get current value:**
```
Send: GetParameter (MODE_MENU, CMD 0x00, settingId as uint32 LE)
Recv: ACK with 1-byte value index (for enum) or multi-byte string
```

**Set value (enum/action):**
```
Send: SetParameter (MODE_MENU, CMD 0x01, settingId uint32 LE, newValueIdx byte)
Recv: 14-byte ACK (DataSize=0)
```

**Set value (string, e.g. WiFi password):**
```
Send: SetParameter (MODE_MENU, CMD 0x01, settingId uint32 LE, length byte, UTF-8 value)
Recv: 14-byte ACK
```

**Known settings (from PCAP XML dump, firmware HX2247-20241115 V1.0):**

| Setting | ID | Type | Values |
|---|---|---|---|
| Resolution | 0x0000 | enum | 1080FHD (0x00), 1080P (0x01), 720P (0x02) |
| Exposure Comp | 0x0001 | enum | +2.0 to -2.0 in 1/3 EV steps |
| Loop Recording | 0x0003 | enum | 1 min (0x00), 2 min (0x01), 3 min (0x02) |
| Audio On/Off | 0x0005 | enum | Off (0x00), On (0x01) |
| G-Sensor | 0x0007 | enum | Off/Low/Medium/High |
| Volume | 0x0008 | enum | Off/Low/Medium/High |
| ACC Off Behavior | 0x0009 | enum | Shutdown/12h/24h/48h |
| Frame Rate | 0x000A | enum | Various FPS settings |
| Language | 0x0203 | enum | English/繁體中文/简体中文 |
| Format SD | 0x0207 | action | Button |
| Factory Reset | 0x0208 | action | Button (has Reflash tag) |
| Firmware Version | 0x0209 | readonly | e.g. "HX2247-20241115 V1.0" |
| WiFi Name | 0x0300 | string | e.g. "HX_YZJ" |
| WiFi Password | 0x0301 | string | e.g. "12345678" |
| Manufacturer | 0x9001 | readonly | e.g. "HUAXIN" |

---

### 3.7 Vendor Mode — Time Sync (CMD 0xFF)

```
Packet structure:
  GPSOCKET(8) + type(1) + cmdIdx(1) + mode=0xFF(1) + cmdId(1)
  totalLen (2B BE) = 0x0011 (17)
  padding (1B) = 0x00
  "GPVENDOR" (8B ASCII)
  padding (2B) = 0x00 0x00
  year (2B LE)
  month (1B)
  day (1B)
  hour (1B)
  minute (1B)
  second (1B)
```

---

### 3.8 Timing Requirements

| Operation | Duration | Notes |
|---|---|---|
| TCP connect timeout | 4000 ms | |
| Socket read timeout | 8000 ms | RestartStreaming ACK can take ~5s |
| RestartStreaming ACK | ~5000 ms | Critical: do not use a shorter timeout |
| AuthDevice ACK | ~100 ms | |
| UDP RTP timeout | 5000 ms per attempt | Max 6 consecutive timeouts |
| GetRawData chunks | 61440 bytes max | Stream until RIFF size reached |

---

### 3.9 Connection Flow Summary (from PCAP)

Complete app startup sequence observed in PCAPdroid:
```
 1. TCP connect → 192.168.25.1:8081
 2. AuthDevice (4-byte token 77 07 8c 12)
 3. GetStatus (CMD 0x01) — recording state
 4. GetParameter × 5 (settings 0x0002, 0x0003, 0x0004, 0x0009, 0x0300=device name)
 5. GetParameterFile (CMD 0x02) — full XML settings (~6KB, chunked at 242B)
 6. SetTime via GPVENDOR (CMD 0xFF) — sync phone time to camera
 7. GetParameter (0x0006)
 8. GetSDCardInfo (CMD 0x07) — 4-byte param, 4-byte response
 9. SetMode (CMD 0x00)
10. GetFileList (CMD 0x08) — NAK if SD empty
11. RestartStreaming (CMD 0x04) — trigger RTSP server
12. TCP connect → 192.168.25.1:8080 (RTSP)
13. RTSP OPTIONS/DESCRIBE/SETUP/PLAY — MJPEG over RTP/UDP
14. Continuous GetStatus polling on port 8081 (~1s interval)
```

---
---

## 4. Common Implementation Notes

### 4.1 HTTP Client (HiDVR & Easytech)

All requests are plain HTTP GET. The dashcam acts as a server on its local WiFi AP. No authentication.
Implementation: `DashcamHttpClient` (`OkHttp`-based) with a short timeout (≤10 s).
Success = HTTP 200. Any other status or exception = failure.

### 4.2 Network Binding (All chipsets)

Android 10+ devices with both WiFi and cellular connections require explicit network binding to ensure traffic routes through the dashcam's WiFi network:

- **HiDVR / Easytech:** `ConnectivityManager.bindProcessToNetwork(network)` — binds entire process so OkHttp + IjkPlayer native sockets route through WiFi.
- **GeneralPlus:** `Network.socketFactory.createSocket()` for TCP control; `network.bindSocket(udp)` for RTP receive; `ConnectivityManager.bindProcessToNetwork(network)` as global fallback.

### 4.3 RTSP Players

| Chipset | Player | Codec | Reason |
|---|---|---|---|
| HiDVR | IjkMediaPlayer | H.264 | Standard RTSP, works with FFmpeg |
| Easytech | IjkMediaPlayer | H.264 | Standard RTSP, works with FFmpeg |
| GeneralPlus | MjpegRtspPlayer (custom) | MJPEG | IjkPlayer FFmpeg build lacks MJPEG decoder (codec id 7) |

**Why IjkPlayer for H.264 streams:** ExoPlayer, Android MediaPlayer, and libVLC all fail against dashcam RTSP because they abort when the camera returns HTTP 400 to the RTSP OPTIONS request. IjkPlayer's FFmpeg RTSP client ignores the OPTIONS failure and proceeds directly to DESCRIBE.

### 4.4 Video Display

Dashcam video is landscape (16:9). Display with `Modifier.fillMaxWidth().aspectRatio(16f/9f)` to avoid stretching on a portrait phone screen.

---

## 5. Protocol Comparison Matrix

| Feature | HiDVR (HiSilicon) | Easytech (Allwinner) | GeneralPlus |
|---|---|---|---|
| **Device IP** | 192.168.0.1 | 192.168.169.1 | 192.168.25.1 |
| **Control protocol** | HTTP GET (CGI) | HTTP GET (REST JSON) | TCP binary (GPSOCKET :8081) |
| **Response format** | `var key="value";` | `{"result":0,"info":...}` | Binary packets |
| **Live RTSP URL** | `rtsp://ip:554/livestream/1` | `rtsp://ip:554` | `rtsp://ip:8080/?action=stream` |
| **Live codec** | H.264 | H.264 | MJPEG (JPEG/90000) |
| **Live player** | IjkMediaPlayer | IjkMediaPlayer | MjpegRtspPlayer |
| **Stop recording for live** | Yes | No | No (uses RestartStreaming) |
| **Multi-camera** | No (single) | Yes (up to 3) | No (single) |
| **Media browse** | HTTP file list (CGI) | HTTP JSON (paginated) | Binary GetNameList over TCP |
| **File access** | HTTP URL | HTTP URL | RTSP (playback) / TCP chunks (download) |
| **Thumbnail** | `.thm` sidecar URL | `/app/getthumbnail?file=` | Binary GetThumbnail over TCP |
| **Delete** | HTTP CGI | HTTP REST | Binary DeleteFile over TCP |
| **Settings format** | XML (`cammenu.xml`) | JSON (`getparamitems`) | XML (chunked over TCP) |
| **Set setting** | HTTP CGI (stop/start rec) | HTTP REST (no stop needed) | Binary SetParameter over TCP |
| **Auth** | Client IP registration | None | 4-byte token handshake |
| **Session** | Stateless HTTP | Stateless HTTP | Stateful TCP (held for RTSP) |
