package com.example.trafykamerasikotlin.data.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import android.util.Size
import com.example.trafykamerasikotlin.data.vision.Detection
import com.example.trafykamerasikotlin.data.vision.Frame
import com.example.trafykamerasikotlin.data.vision.PlateDetection
import com.example.trafykamerasikotlin.data.vision.TrackedScene
import com.example.trafykamerasikotlin.data.vision.detectors.NcnnPlateDetector
import com.example.trafykamerasikotlin.data.vision.detectors.NcnnVehicleDetector
import com.example.trafykamerasikotlin.data.vision.ocr.OnnxPlateOcr
import com.example.trafykamerasikotlin.data.vision.tracker.ByteTracker
import com.example.trafykamerasikotlin.data.vision.tracker.TrackedDetection
import com.example.trafykamerasikotlin.data.vision.voting.PlateVoteBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Decode an AVI → run AI on every frame → encode an MP4 with the overlay
 * burned into every pixel.
 *
 * Unlike [com.example.trafykamerasikotlin.data.vision.pipeline.LiveVisionPipeline]
 * this runs **synchronously**: each frame is detected, tracked, OCR'd and
 * voted BEFORE it's handed to the encoder. That's the right trade-off
 * for offline processing — we have all the wall-clock time we need, and
 * in exchange every output frame carries the AI output for exactly that
 * frame (no "the scene is still for the previous frame because inference
 * is 3 frames behind" ambiguity the live path has).
 *
 * Models are pre-warmed before the encode loop starts, so short clips
 * (a few seconds) don't miss the overlay entirely waiting for model load.
 */
class OfflineVideoProcessor(
    private val context: Context,
    /** Run inference on every Nth frame. 1 = every frame (max quality). */
    private val inferenceEveryN: Int = 3,
    /** OCR on every Nth _plate_ detection (plates don't move much frame-to-frame). */
    private val ocrEveryN: Int = 1,
) {

    sealed class State {
        object Idle : State()
        object WarmingUp : State()
        data class Processing(val fractionDone: Float, val frameIndex: Long, val totalFrames: Long) : State()
        data class Done(val outputFile: File, val frameCount: Long) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Legacy entry point: wraps the input AVI in an [AviMjpegVideoSource]
     * and forwards to [process]. Kept so `MediaViewModel.downloadWithOverlay`
     * doesn't need to know about the source abstraction.
     */
    suspend fun process(inputAvi: File, outputMp4: File) {
        val reader = AviMjpegReader(inputAvi)
        process(AviMjpegVideoSource(reader), outputMp4)
    }

    /**
     * Generic entry point. [source] supplies decoded bitmaps + timing info;
     * we run inference on each frame and re-encode with the overlay burned
     * into every pixel.
     *
     * Blocks the calling coroutine — run on Dispatchers.Default or similar.
     * [source] is closed on exit regardless of outcome.
     */
    suspend fun process(source: VideoFrameSource, outputMp4: File) {
        _state.value = State.WarmingUp
        var encoder: OverlayVideoEncoder? = null
        var vehicle: NcnnVehicleDetector? = null
        var plate:   NcnnPlateDetector? = null
        var ocr:     OnnxPlateOcr? = null

        try {
            val srcW = source.width.takeIf { it > 0 } ?: 1920
            val srcH = source.height.takeIf { it > 0 } ?: 1080
            val fps  = if (source.microsPerFrame > 0) (1_000_000L / source.microsPerFrame).toInt() else 30
            val (outW, outH) = scaleToMaxWidth(srcW, srcH, maxWidth = 1280)

            // Pre-warm detectors BEFORE we open the encoder. On a cold start
            // the vehicle model takes ~5 s on Vulkan — if we let that fire
            // inside the encode loop, short videos finish first and we
            // produce an overlay-less output.
            vehicle = NcnnVehicleDetector(context, NcnnVehicleDetector.DEFAULT_YOLO11N_SOURCE, useGpu = true)
            vehicle.initialize()
            plate   = NcnnPlateDetector(context, useGpu = true)
            plate.initialize()
            ocr     = OnnxPlateOcr(context)
            ocr.initialize()
            Log.i(TAG, "detectors warm: vehicle + plate + OCR ready")

            encoder = OverlayVideoEncoder(
                outputFile = outputMp4,
                width = outW,
                height = outH,
                frameRate = fps.coerceAtLeast(15).coerceAtMost(60),
            )
            Log.i(TAG, "processing → ${outputMp4.name}  src=${srcW}x${srcH} out=${outW}x${outH}@${fps}fps totalFrames=${source.totalFrames}")

            val tracker  = ByteTracker()
            val voteBook = PlateVoteBook()
            val sourceSize = Size(srcW, srcH)
            var lastScene: TrackedScene? = null

            val totalFrames = source.totalFrames.takeIf { it > 0 } ?: -1L
            var encoded = 0L

            for (frame in source.frames()) {
                val argb = frame.bitmap  // already ARGB_8888 per the source contract

                // Run inference on every Nth frame. On skipped frames we
                // re-use the previous scene (Chunk 5's tracker already
                // smooths motion between inference ticks).
                val runInference = (frame.frameIndex % inferenceEveryN == 0L)
                if (runInference) {
                    val scene = runSyncInference(
                        frameBitmap = argb,
                        frameSource = sourceSize,
                        tracker     = tracker,
                        voteBook    = voteBook,
                        vehicle     = vehicle,
                        plate       = plate,
                        ocr         = ocr,
                        timestampNs = frame.presentationTimeUs * 1000L,
                    )
                    lastScene = scene
                }

                encoder.encodeFrame(argb, lastScene, frame.presentationTimeUs)
                argb.recycle()
                encoded++

                if (encoded % 4L == 0L) {
                    val fraction = if (totalFrames > 0) (encoded.toFloat() / totalFrames).coerceIn(0f, 1f) else 0f
                    _state.value = State.Processing(fraction, encoded, totalFrames)
                }
            }

            encoder.finish()
            encoder = null
            source.close()
            _state.value = State.Done(outputMp4, encoded)
            Log.i(TAG, "done: ${encoded} frames encoded to ${outputMp4.name}")
        } catch (t: Throwable) {
            Log.e(TAG, "process failed", t)
            _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            try { outputMp4.delete() } catch (_: Throwable) {}
        } finally {
            try { encoder?.finish() } catch (_: Throwable) {}
            try { source.close()    } catch (_: Throwable) {}
            try { vehicle?.release() } catch (_: Throwable) {}
            try { plate?.release()   } catch (_: Throwable) {}
            try { ocr?.release()     } catch (_: Throwable) {}
        }
    }

    /**
     * Synchronous run of vehicle → plate → OCR → tracker → voting on one
     * frame. Returns the [TrackedScene] snapshot to pass to the encoder.
     * Much simpler than the live pipeline — we don't need channels or
     * sampling because there's no real-time deadline to miss.
     */
    private suspend fun runSyncInference(
        frameBitmap: Bitmap,
        frameSource: Size,
        tracker: ByteTracker,
        voteBook: PlateVoteBook,
        vehicle: NcnnVehicleDetector,
        plate:   NcnnPlateDetector,
        ocr:     OnnxPlateOcr,
        timestampNs: Long,
    ): TrackedScene {
        val frame = Frame(bitmap = frameBitmap, timestampNanos = timestampNs)
        val vehicles: List<Detection> = vehicle.detect(frame)
        val tracks: List<TrackedDetection> = tracker.update(vehicles)
        voteBook.prune(tracker.activeTrackIds())

        val plates: List<PlateDetection> = if (tracks.isNotEmpty()) {
            tracks.flatMap { t ->
                val x = t.bbox.left.toInt()
                val y = t.bbox.top.toInt()
                val w = (t.bbox.right  - t.bbox.left).toInt()
                val h = (t.bbox.bottom - t.bbox.top ).toInt()
                val raw = runCatching { plate.detectInCrop(frameBitmap, t.trackId, x, y, w, h) }
                    .getOrElse { emptyList() }
                raw.map { p -> p.copy(parentTrackId = t.trackId) }
            }
        } else emptyList()

        val plated: List<PlateDetection> = plates.map { p ->
            val trackId = p.parentTrackId
            val crop = cropBitmap(frameBitmap, p.bbox) ?: return@map p
            val recog = runCatching { ocr.recognize(crop) }.getOrNull()
            crop.recycle()
            if (trackId != null && recog != null && recog.text.isNotEmpty()) {
                voteBook.record(trackId, recog.text)
            }
            val voted = trackId?.let { voteBook.bestText(it) }
            p.copy(recognition = recog, votedText = voted)
        }

        return TrackedScene(
            sourceFrameSize    = frameSource,
            detections         = vehicles,
            timestampNanos     = timestampNs,
            inferenceLatencyMs = 0,  // not tracked in offline mode
            plates             = plated,
            tracks             = tracks,
        )
    }

    private fun cropBitmap(src: Bitmap, bbox: RectF): Bitmap? {
        val x = bbox.left.toInt().coerceIn(0, src.width - 1)
        val y = bbox.top.toInt().coerceIn(0, src.height - 1)
        val w = (bbox.right  - bbox.left).toInt().coerceAtLeast(1).coerceAtMost(src.width - x)
        val h = (bbox.bottom - bbox.top ).toInt().coerceAtLeast(1).coerceAtMost(src.height - y)
        if (w < 4 || h < 4) return null
        val argb = if (src.config == Bitmap.Config.ARGB_8888) src
            else src.copy(Bitmap.Config.ARGB_8888, false)
        return Bitmap.createBitmap(argb, x, y, w, h)
    }

    private fun scaleToMaxWidth(srcW: Int, srcH: Int, maxWidth: Int): Pair<Int, Int> {
        if (srcW <= maxWidth) {
            val w = if (srcW % 2 == 0) srcW else srcW - 1
            val h = if (srcH % 2 == 0) srcH else srcH - 1
            return w to h
        }
        val scale = maxWidth.toFloat() / srcW
        val w = maxWidth
        val rawH = (srcH * scale).toInt()
        val h = if (rawH % 2 == 0) rawH else rawH - 1
        return w to h
    }

    companion object { private const val TAG = "Trafy.OfflineVideo" }
}
