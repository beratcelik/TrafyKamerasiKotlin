package tr.trafy.kamera.data.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import tr.trafy.kamera.data.vision.TrackedScene
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.tan

/**
 * H.264 encoder + MP4 muxer that writes one composited frame at a time.
 *
 * The composition = dashcam video frame (MJPEG → Bitmap) with the AI
 * overlay (vehicle boxes, plate boxes, voted plate text) painted on top.
 * We do the composition on a regular Android [Canvas] targeting a staging
 * bitmap, then upload that into a GL texture and draw it to the MediaCodec
 * input surface. Slower than a pure-GL overlay path but a lot less code,
 * and the Canvas API gives us exact parity with the live overlay's
 * [tr.trafy.kamera.ui.components.BoundingBoxOverlay].
 *
 * Thread model: single-threaded. Caller feeds frames from their loop.
 * [finish] drains the encoder and closes the muxer.
 */
class OverlayVideoEncoder(
    private val outputFile: File,
    private val width:      Int,
    private val height:     Int,
    private val frameRate:  Int = 30,
    /**
     * Pre-computed best plate crops per parent track id, taken from a
     * first-pass scan of the entire video. When supplied, the billboard
     * uses these from frame 1, so the viewer sees the sharpest sample of
     * each plate even at the start of the clip where the car was still
     * tiny in the distance. The encoder takes ownership and recycles them
     * on [finish].
     */
    initialPlateCrops: Map<Int, Bitmap> = emptyMap(),
    /**
     * Bitrate = 0.10 bits-per-pixel-per-second. Lower than the 0.15 "quality"
     * default but plenty for dashcam footage (scene content is mostly static
     * road with small moving objects, which H.264 handles efficiently). The
     * Adreno 618 hardware encoder starts stalling above ~8 Mbps on 720p —
     * that stall presents as `eglSwapBuffers failed` because the input
     * surface's buffer queue can't drain fast enough.
     */
    bitrateBps: Int = (width * height * frameRate * 0.10f).toInt(),
    /**
     * Keyframe interval in seconds. Lower = quicker seeking but fatter files
     * and more encoder work. 2 s is the default for phone-encoded video.
     */
    keyFrameIntervalSec: Int = 2,
    /**
     * Optional GPS / sensor track aligned to this clip's recording window.
     * When non-null, the HUD widgets consume real values from it instead
     * of the synthetic `HudEgoEstimator`. When the track has no fix at a
     * particular frame, that frame's widgets render blank — honest about
     * coverage gaps rather than silently faking values.
     *
     * Source: phone GPS log via [tr.trafy.kamera.data.sensors.GpsLogStore],
     * built at the call site (see `OfflineVideoProcessor.process`).
     */
    private val gpsTrack: tr.trafy.kamera.data.sensors.GpsTrack? = null,
    /**
     * Wall-clock epoch (milliseconds) corresponding to `presentationTimeUs
     * == 0`. Required when [gpsTrack] is non-null — the encoder maps each
     * frame's relative timestamp into an absolute epoch via
     * `clipStartEpochMs + presentationTimeUs / 1000`.
     */
    private val clipStartEpochMs: Long = 0L,
) {

    private val encoder: MediaCodec
    private val inputSurface: android.view.Surface
    private val egl: InputSurfaceEgl
    private val blitter: FullScreenQuadBlitter

    private val muxer: MediaMuxer
    private var muxerStarted = false
    private var videoTrackIndex = -1
    private val bufferInfo = MediaCodec.BufferInfo()

    private val compositeBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val compositeCanvas: Canvas = Canvas(compositeBitmap)

    /**
     * Locked plate crops, keyed by parent vehicle track id. Once a track's
     * OCR vote tally clears [PLATE_LOCK_AGREEMENT] / [PLATE_LOCK_MIN_VOTES]
     * we snapshot the source bitmap at the plate bbox into a standalone
     * [Bitmap] and reuse it for every subsequent frame the track lives on
     * — even after the plate becomes too small or too motion-blurred for
     * the OCR to recover. Stops the billboard from flickering between
     * mutually-confident-but-slightly-different crops and survives the
     * car driving away from the camera.
     */
    private val lockedPlateCrops = mutableMapOf<Int, Bitmap>().apply {
        putAll(initialPlateCrops)
    }

    /**
     * Synthetic ego-state estimator for the HUD chrome (speed dial, altitude
     * pill, compass needle). See [HudEgoEstimator]. Per-clip seed comes from
     * the output filename so reruns of the same clip yield the same base
     * altitude / compass — reproducibility for the reviewer.
     */
    private val hudEstimator = HudEgoEstimator(seed = outputFile.absolutePath.hashCode().toLong())

    /** Encoded-frame counter, used to drive the estimator's slow drift terms. */
    private var encodedFrameIndex = 0L
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec)

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()

        egl = InputSurfaceEgl(inputSurface)
        egl.makeCurrent()
        blitter = FullScreenQuadBlitter()
        encoder.start()

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        Log.i(TAG, "configured: ${width}x${height}@${frameRate} bitrate=$bitrateBps → ${outputFile.name}")
    }

    /**
     * Encode one frame. `frameBitmap` must match [width] × [height]. `scene`
     * supplies the boxes + labels to paint on top. `presentationTimeUs` is
     * the output timestamp — monotonic strictly increasing per call.
     */
    fun encodeFrame(frameBitmap: Bitmap, scene: TrackedScene?, presentationTimeUs: Long) {
        val ego = hudEstimator.update(frameBitmap, encodedFrameIndex)
        encodedFrameIndex++
        composite(frameBitmap, scene, presentationTimeUs, ego)
        // Draw to the GL texture and advance the MediaCodec surface.
        blitter.draw(compositeBitmap)
        egl.setPresentationTime(presentationTimeUs * 1000L)  // nanos
        egl.swapBuffers()
        drain(endOfStream = false)
    }

    /** Call once at the end of input. Drains remaining buffers + closes muxer. */
    fun finish() {
        encoder.signalEndOfInputStream()
        drain(endOfStream = true)
        try { encoder.stop() } catch (_: Throwable) {}
        encoder.release()
        egl.release()
        blitter.release()
        try { inputSurface.release() } catch (_: Throwable) {}
        compositeBitmap.recycle()
        lockedPlateCrops.values.forEach { runCatching { it.recycle() } }
        lockedPlateCrops.clear()
        if (muxerStarted) {
            try { muxer.stop() } catch (_: Throwable) {}
        }
        try { muxer.release() } catch (_: Throwable) {}
        Log.i(TAG, "finished → ${outputFile.absolutePath}")
    }

    // ── composition ────────────────────────────────────────────────────────

    /** Draw the source frame, then the scene's boxes + labels on top. */
    private fun composite(
        src: Bitmap,
        scene: TrackedScene?,
        presentationTimeUs: Long,
        ego: HudEgoEstimator.EgoState,
    ) {
        compositeCanvas.drawBitmap(
            src,
            Rect(0, 0, src.width, src.height),
            Rect(0, 0, width, height),
            null,
        )
        if (scene == null) {
            // HUD chrome still animates even on AI-skipped frames.
            drawHudChrome(presentationTimeUs, ego)
            return
        }

        // Scale factor from scene's source-frame coords to output frame coords.
        // scene.sourceFrameSize is the bitmap we ran inference on (= src size).
        val sx = width  / scene.sourceFrameSize.width.toFloat()
        val sy = height / scene.sourceFrameSize.height.toFloat()

        // Vehicles drawn as semi-transparent green fills (no stroke, no class
        // label) so the detection feels like an "AR slab" over the vehicle
        // rather than a labelled bounding box. Matches the reference dashcam
        // screenshot where the box itself communicates "tracked" and the only
        // text inside is the live distance. We keep the plate billboard above
        // for actual identity; the class name added no information.
        val vehicleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = VEHICLE_FILL_ARGB
        }
        val vehicleBoxes: List<RectF> = scene.tracks?.map { t ->
            RectF(t.bbox.left * sx, t.bbox.top * sy, t.bbox.right * sx, t.bbox.bottom * sy)
        } ?: scene.detections.map { d ->
            RectF(d.bbox.left * sx, d.bbox.top * sy, d.bbox.right * sx, d.bbox.bottom * sy)
        }
        vehicleBoxes.forEach { compositeCanvas.drawRect(it, vehicleFill) }

        // Plate "billboard": crop each detected plate out of the source
        // bitmap, enlarge it, and pin it above the parent vehicle so the
        // viewer reads the real pixels themselves — moving misread risk off
        // the OCR algorithm and onto the human who reviews the clip. The
        // raw yellow plate rect + OCR text label that used to live here was
        // removed; the billboard alone communicates the plate.
        drawPlateBillboards(src, scene, sx, sy)

        // Distance numbers painted INSIDE each vehicle's translucent slab —
        // matches the reference dashcam screenshot (13 M / 14 M overlaid on
        // the green box). Distance comes from the parent vehicle's matching
        // plate detection; we skip vehicles without a usable plate read.
        drawDistancesOnVehicles(scene, sx, sy)

        // HUD chrome on top of everything — corners are far from typical
        // bbox content, so this rarely occludes anything we drew above.
        drawHudChrome(presentationTimeUs, ego)
    }

    /**
     * Renders the four corner HUD widgets. Positions/sizes scale gently
     * with the output frame so the overlay degrades on smaller outputs.
     *
     * The synthetic [HudEgoEstimator] runs every frame to keep its EMA
     * warm, but the values we *paint* come from [resolveEgo] — which
     * consults the real GPS track first and falls back to synthetic only
     * when no track is supplied. When a track IS supplied and has no fix
     * at this presentation time, the resolved fields are null and the
     * widgets simply don't render that frame (honest about gaps).
     */
    private fun drawHudChrome(presentationTimeUs: Long, synthetic: HudEgoEstimator.EgoState) {
        val resolved = resolveEgo(presentationTimeUs, synthetic)

        val margin = (minOf(width, height) * 0.035f).coerceAtLeast(14f)
        // Top-right: clip timer pill (always shown, derived from frame time).
        HudWidgets.drawClipTimerPill(
            canvas      = compositeCanvas,
            rightX      = width - margin,
            topY        = margin,
            timestampUs = presentationTimeUs,
        )
        val dialR = (minOf(width, height) * 0.045f).coerceIn(40f, 70f)
        // Bottom-left: compass disc.
        resolved.compassDeg?.let { headingDeg ->
            HudWidgets.drawCompassDisc(
                canvas     = compositeCanvas,
                cx         = margin + dialR,
                cy         = height - margin - dialR,
                radius     = dialR,
                headingDeg = headingDeg,
            )
        }
        // Bottom-right: speed dial.
        resolved.speedKmh?.let { speedKmh ->
            HudWidgets.drawSpeedDial(
                canvas   = compositeCanvas,
                cx       = width - margin - dialR,
                cy       = height - margin - dialR,
                radius   = dialR,
                speedKmh = speedKmh,
            )
        }
        // Bottom-right (left of speed): altitude pill.
        resolved.altitudeM?.let { altitudeM ->
            HudWidgets.drawAltitudePill(
                canvas    = compositeCanvas,
                rightX    = width - margin - dialR * 2f - 16f,
                centerY   = height - margin - dialR,
                altitudeM = altitudeM,
            )
        }
    }

    /**
     * Picks the values to feed the HUD widgets for this frame:
     *
     *  - No [gpsTrack] supplied → return [synthetic] verbatim. This is the
     *    pre-feature path: the user never opted into GPS logging, so we
     *    keep showing the same fancy-but-fake numbers the encoder has
     *    drawn since the HUD shipped.
     *  - [gpsTrack] supplied + fix at this frame → build an [EgoState]
     *    from the fix; all non-null.
     *  - [gpsTrack] supplied + no fix (within tolerance) → return an all-
     *    null EgoState. Widget calls fall through their `?.let { … }`
     *    gates and skip rendering — honest about the coverage gap.
     */
    private fun resolveEgo(
        presentationTimeUs: Long,
        synthetic: HudEgoEstimator.EgoState,
    ): HudEgoEstimator.EgoState {
        val track = gpsTrack ?: return synthetic
        val frameEpochMs = clipStartEpochMs + presentationTimeUs / 1_000L
        val fix = track.fixAt(frameEpochMs) ?: return hudEstimator.blankState
        return HudEgoEstimator.EgoState(
            speedKmh   = fix.speedMs?.let { (it * 3.6f).toInt().coerceIn(0, 300) },
            altitudeM  = fix.altitudeM,
            compassDeg = fix.headingDeg,
            accelG     = fix.accelG,
        )
    }

    /**
     * Per-frame billboard pass. Two flavours of billboard are drawn:
     *
     *   1. **Live crop** — for tracks whose plate is currently detected but
     *      the OCR vote tally isn't yet confident enough to lock. Uses the
     *      bbox in the current source frame; redraws every call.
     *   2. **Locked snapshot** — for tracks whose plate has been read
     *      confidently in a past frame. Reuses the captured [Bitmap] and
     *      pins it above the vehicle's *current* position. Survives the
     *      plate becoming unreadable (motion blur, distance, occlusion) and
     *      keeps showing as long as the track is alive.
     *
     * The locked image NEVER updates after capture, so the billboard reads
     * the same character set across the whole clip — the responsibility
     * for interpreting it shifts from the OCR onto whoever reviews the
     * footage, mirroring the OEM-dashcam pattern the user asked for.
     */
    private fun drawPlateBillboards(
        src: Bitmap,
        scene: TrackedScene,
        sx: Float,
        sy: Float,
    ) {
        val plates = scene.plates
        val tracks = scene.tracks
        val tracksByTrackId: Map<Int, tr.trafy.kamera.data.vision.tracker.TrackedDetection> =
            tracks?.associateBy { it.trackId } ?: emptyMap()

        // Track ids handled via the current-frame pass — used to skip the
        // "ghost" billboard pass for tracks whose plate is still visible.
        val handled = HashSet<Int>(plates?.size ?: 0)

        plates?.forEach { p ->
            // Skip plates too small to lock or to crop usefully.
            if (p.bbox.width() < 20f || p.bbox.height() < 8f) return@forEach

            val trackId = p.parentTrackId
            val anchor  = parentBboxOf(p, scene.detections, tracks) ?: p.bbox
            if (trackId != null) handled += trackId

            // Existing lock takes precedence — once locked the source-frame
            // crop never changes for this track.
            val locked = trackId?.let { lockedPlateCrops[it] }
            if (locked != null) {
                drawBillboard(locked, srcRect = null, anchor = anchor, sx = sx, sy = sy)
            } else {
                // No lock yet. Maybe this frame meets the lock criterion?
                val srcRect = clampedCropRect(p.bbox, src) ?: return@forEach
                if (trackId != null && shouldLockPlate(p)) {
                    val crop = Bitmap.createBitmap(
                        src,
                        srcRect.left, srcRect.top,
                        srcRect.width(), srcRect.height(),
                    )
                    lockedPlateCrops[trackId] = crop
                    drawBillboard(crop, srcRect = null, anchor = anchor, sx = sx, sy = sy)
                } else {
                    drawBillboard(src, srcRect = srcRect, anchor = anchor, sx = sx, sy = sy)
                }
            }
        }

        // "Ghost" pass: tracks that have a locked plate but no current-frame
        // plate detection. Keeps the billboard glued to the vehicle as it
        // drives away.
        for ((trackId, lockedBmp) in lockedPlateCrops) {
            if (trackId in handled) continue
            val track = tracksByTrackId[trackId] ?: continue
            drawBillboard(lockedBmp, srcRect = null, anchor = track.bbox, sx = sx, sy = sy)
        }
    }

    /**
     * Draws a single billboard with yellow/black double border. Returns the
     * destination rect (in output-frame px) so the caller can pin a distance
     * pill or other annotation directly underneath it. Returns `null` when
     * the source is too degenerate to draw.
     */
    private fun drawBillboard(
        bmp: Bitmap,
        srcRect: Rect?,
        anchor: RectF,
        sx: Float,
        sy: Float,
    ): RectF? {
        val srcW = srcRect?.width() ?: bmp.width
        val srcH = srcRect?.height() ?: bmp.height
        if (srcW < 4 || srcH < 4) return null

        val anchorWidthOut = (anchor.right - anchor.left) * sx
        val targetWidth = (anchorWidthOut * BILLBOARD_VEHICLE_WIDTH_FRAC)
            .coerceAtLeast(BILLBOARD_MIN_WIDTH_PX)
            .coerceAtMost(width * BILLBOARD_MAX_FRAME_FRAC)
        val aspect = srcH.toFloat() / srcW
        val targetHeight = targetWidth * aspect

        val anchorCenterXOut = ((anchor.left + anchor.right) * 0.5f) * sx
        val anchorTopOut     = anchor.top * sy
        var outLeft = anchorCenterXOut - targetWidth * 0.5f
        var outTop  = anchorTopOut - targetHeight - BILLBOARD_GAP_PX
        if (outTop  < 0f) outTop  = 0f
        if (outLeft < 0f) outLeft = 0f
        if (outLeft + targetWidth > width) outLeft = width - targetWidth

        val dstRectF = RectF(outLeft, outTop, outLeft + targetWidth, outTop + targetHeight)
        compositeCanvas.drawBitmap(bmp, srcRect, dstRectF, null)

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.BLACK
        }
        compositeCanvas.drawRect(dstRectF, border)
        border.strokeWidth = 2f
        border.color = Color.YELLOW
        compositeCanvas.drawRect(dstRectF, border)
        return dstRectF
    }

    /**
     * Paints each vehicle's estimated distance inside its translucent green
     * slab. Distance comes from the plate detection associated with the
     * vehicle (via parent track id, else by detection index). Vehicles
     * without a usable plate detection just get the empty slab — better
     * than a misleading number.
     *
     * Each label is sized relative to the vehicle's on-screen box so a
     * tiny faraway car gets a small "8 M" and a close car gets a large
     * "3 M". Single-shadow black, no border — matches the reference.
     */
    private fun drawDistancesOnVehicles(scene: TrackedScene, sx: Float, sy: Float) {
        val plates = scene.plates ?: return
        if (plates.isEmpty()) return
        val focalPx = scene.sourceFrameSize.width.toFloat() /
            (2f * tan(Math.toRadians(ASSUMED_DASHCAM_FOV_DEG / 2.0)).toFloat())

        // Pre-index plates by parent track id and by detection index so we
        // can find the matching plate for each vehicle in one pass.
        val plateByTrackId = HashMap<Int, tr.trafy.kamera.data.vision.PlateDetection>(plates.size)
        val plateByDetIdx  = HashMap<Int, tr.trafy.kamera.data.vision.PlateDetection>(plates.size)
        for (p in plates) {
            val tid = p.parentTrackId
            if (tid != null) plateByTrackId[tid] = p
            val di = p.parentVehicleIndex
            if (di >= 0) plateByDetIdx[di] = p
        }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF0FFFFFF.toInt()
            isFakeBoldText = true
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }

        val tracks = scene.tracks
        if (tracks != null) {
            tracks.forEach { t ->
                val plate = plateByTrackId[t.trackId] ?: return@forEach
                if (plate.bbox.width() < DISTANCE_MIN_PLATE_PX) return@forEach
                val distM = (PLATE_PHYSICAL_WIDTH_MM * focalPx / plate.bbox.width() / 1000f).toInt()
                if (distM !in 1..DISTANCE_MAX_M) return@forEach
                drawDistanceCentered(
                    boxOut = RectF(t.bbox.left * sx, t.bbox.top * sy, t.bbox.right * sx, t.bbox.bottom * sy),
                    distM  = distM,
                    paint  = labelPaint,
                )
            }
        } else {
            scene.detections.forEachIndexed { idx, d ->
                val plate = plateByDetIdx[idx] ?: return@forEachIndexed
                if (plate.bbox.width() < DISTANCE_MIN_PLATE_PX) return@forEachIndexed
                val distM = (PLATE_PHYSICAL_WIDTH_MM * focalPx / plate.bbox.width() / 1000f).toInt()
                if (distM !in 1..DISTANCE_MAX_M) return@forEachIndexed
                drawDistanceCentered(
                    boxOut = RectF(d.bbox.left * sx, d.bbox.top * sy, d.bbox.right * sx, d.bbox.bottom * sy),
                    distM  = distM,
                    paint  = labelPaint,
                )
            }
        }
    }

    /** Paints `<N>` over `M` centred in [boxOut]. */
    private fun drawDistanceCentered(boxOut: RectF, distM: Int, paint: Paint) {
        val cx = boxOut.centerX()
        val numText = distM.toString()
        // Big number sized to ~28% of the shortest box edge, capped to keep
        // it readable on full-screen vehicles.
        val numSize = (minOf(boxOut.width(), boxOut.height()) * 0.32f).coerceIn(28f, 96f)
        paint.textSize = numSize
        val numW = paint.measureText(numText)
        val baselineY = boxOut.centerY() + numSize * 0.10f
        compositeCanvas.drawText(numText, cx - numW / 2, baselineY, paint)
        // "M" below, smaller and dimmer.
        val unitSize = numSize * 0.38f
        paint.textSize = unitSize
        val unitW = paint.measureText("M")
        compositeCanvas.drawText("M", cx - unitW / 2, baselineY + unitSize * 1.1f, paint)
    }

    /**
     * Lock criterion. The vote book has agreed on the same text across
     * at least [PLATE_LOCK_MIN_VOTES] frames with [PLATE_LOCK_AGREEMENT]
     * agreement — strong enough that snapshotting the current crop is a
     * commitment we won't regret. (Single-frame "confident" recognitions
     * are *not* enough; they flicker between candidates frame-to-frame.)
     */
    private fun shouldLockPlate(p: tr.trafy.kamera.data.vision.PlateDetection): Boolean {
        val voted = p.votedText ?: return false
        return voted.votes >= PLATE_LOCK_MIN_VOTES &&
               voted.agreement >= PLATE_LOCK_AGREEMENT &&
               voted.text.isNotEmpty()
    }

    /** Source-frame crop rect clamped to [src]'s bounds, or null if degenerate. */
    private fun clampedCropRect(bbox: RectF, src: Bitmap): Rect? {
        val left   = bbox.left.toInt().coerceAtLeast(0)
        val top    = bbox.top.toInt().coerceAtLeast(0)
        val right  = bbox.right.toInt().coerceAtMost(src.width)
        val bottom = bbox.bottom.toInt().coerceAtMost(src.height)
        if (right - left < 4 || bottom - top < 4) return null
        return Rect(left, top, right, bottom)
    }

    /** Resolves the plate's parent vehicle bbox, in source-frame coords. */
    private fun parentBboxOf(
        p: tr.trafy.kamera.data.vision.PlateDetection,
        detections: List<tr.trafy.kamera.data.vision.Detection>,
        tracks: List<tr.trafy.kamera.data.vision.tracker.TrackedDetection>?,
    ): RectF? {
        // Prefer the track id when present — it survives re-IDs better than
        // the per-frame detection index, which can shift between frames.
        val byTrack = p.parentTrackId?.let { id -> tracks?.firstOrNull { it.trackId == id } }
        if (byTrack != null) return byTrack.bbox
        val idx = p.parentVehicleIndex
        if (idx in detections.indices) return detections[idx].bbox
        return null
    }

    // ── encode-side bookkeeping ────────────────────────────────────────────

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val outIx = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
            when {
                outIx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // Keep polling until we see BUFFER_FLAG_END_OF_STREAM.
                }
                outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "encoder format changed twice" }
                    val newFormat = encoder.outputFormat
                    videoTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                    Log.i(TAG, "muxer started, trackIndex=$videoTrackIndex")
                }
                outIx >= 0 -> {
                    val buf: ByteBuffer = encoder.getOutputBuffer(outIx)
                        ?: error("null output buffer at $outIx")
                    // Skip codec-config buffers; format change already delivered them.
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(videoTrackIndex, buf, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.i(TAG, "EOS received from encoder")
                        return
                    }
                }
                else -> Log.w(TAG, "unexpected dequeue result: $outIx")
            }
        }
    }

    companion object {
        private const val TAG = "Trafy.OverlayEnc"

        // Billboard sizing — chosen to look roughly like the OEM dashcam
        // "plate clones" without dominating the frame. All values in output
        // pixels. See [drawPlateBillboards].
        private const val BILLBOARD_VEHICLE_WIDTH_FRAC = 0.35f
        private const val BILLBOARD_MIN_WIDTH_PX       = 160f
        private const val BILLBOARD_MAX_FRAME_FRAC     = 0.40f
        private const val BILLBOARD_GAP_PX             = 10f

        /**
         * Plate-lock thresholds. We lock the crop once the OCR vote book has
         * accumulated [PLATE_LOCK_MIN_VOTES] readings that agree
         * [PLATE_LOCK_AGREEMENT] of the time. Tighter than the live overlay's
         * 0.30 "isConfident" because lock is permanent for the rest of the
         * video — a wrong lock means showing the wrong plate for minutes.
         */
        private const val PLATE_LOCK_MIN_VOTES  = 3
        private const val PLATE_LOCK_AGREEMENT  = 0.70f

        // Distance-pill constants. We don't actually know the dashcam's
        // focal length so we assume a typical 70° horizontal FOV — error
        // bars on the resulting metres are ±20%, fine for a HUD readout.
        private const val ASSUMED_DASHCAM_FOV_DEG = 70.0
        private const val PLATE_PHYSICAL_WIDTH_MM = 520f   // TR standard plate
        private const val DISTANCE_MIN_PLATE_PX   = 10f    // below this distance is nonsense
        private const val DISTANCE_MAX_M          = 80     // clamp to keep the pill 2-digit

        // Semi-transparent green slab over each detected vehicle. Lower
        // alpha keeps the underlying car visible while still calling out
        // "tracked." Matches the reference dashcam aesthetic.
        private const val VEHICLE_FILL_ARGB       = 0x6633D158.toInt()
    }
}
