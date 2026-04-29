package com.example.trafykamerasikotlin.data.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Burn the AI overlay into a video using a Surface-to-Surface GL pipeline.
 *
 * Architecture:
 *   - The MediaCodec decoder writes its output frames directly into a GPU
 *     SurfaceTexture (no CPU YUV→ARGB conversion).
 *   - One shared EGL context renders that external texture, plus the
 *     overlay quad, onto the encoder's input surface.
 *   - AI inference runs on a separate coroutine: every Nth source frame the
 *     GL thread reads back a small RGBA snapshot via `glReadPixels`, hands
 *     it to the inference coroutine, and updates the overlay texture
 *     whenever a new [TrackedScene] is published.
 *
 * The whole GL section is pinned to a dedicated single thread so the EGL
 * context never migrates across coroutines.
 */
@OptIn(DelicateCoroutinesApi::class)
class OfflineVideoProcessor(
    private val context: Context,
    /** Run inference on every Nth decoded frame. */
    private val inferenceEveryN: Int = 3,
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

    /** Convenience entry point for file inputs. */
    suspend fun process(inputFile: File, outputMp4: File) =
        runGlPipeline(outputMp4) { SurfaceVideoDecoder.open(inputFile) }

    /** Convenience entry point for content-URI inputs (Storage Access Framework). */
    suspend fun process(uri: Uri, outputMp4: File) =
        runGlPipeline(outputMp4) { SurfaceVideoDecoder.open(context, uri) }

    /**
     * AVI/MJPEG entry point — used by GeneralPlus dashcams whose downloaded
     * .avi files Android's MediaCodec can't decode directly. Each JPEG
     * frame is decoded to ARGB on the CPU, then uploaded to the GL pipeline
     * via [GlOverlayPipeline.uploadSourceBitmap]. AI inference runs directly
     * on the source bitmap (no FBO snapshot needed).
     */
    suspend fun processAvi(inputAvi: File, outputMp4: File) = coroutineScope {
        _state.value = State.WarmingUp
        val reader = AviMjpegReader(inputAvi)
        val srcW = reader.width and 1.inv()
        val srcH = reader.height and 1.inv()
        val fps  = if (reader.microsPerFrame > 0L)
            (1_000_000L / reader.microsPerFrame).toInt().coerceAtLeast(15).coerceAtMost(60)
        else 30
        val totalFrames = reader.totalFrames

        val vehicle = NcnnVehicleDetector(context, NcnnVehicleDetector.DEFAULT_YOLO11N_SOURCE, useGpu = true)
            .also { it.initialize() }
        val plate   = NcnnPlateDetector(context, useGpu = true).also { it.initialize() }
        val ocr     = OnnxPlateOcr(context).also { it.initialize() }
        Log.i(TAG, "AVI detectors warm")

        val encoder  = OverlayVideoEncoder(
            outputFile = outputMp4,
            width      = srcW,
            height     = srcH,
            frameRate  = fps,
        )
        val renderer = OverlayBitmapRenderer(context, srcW, srcH)

        val sceneRef = AtomicReference<TrackedScene?>(null)
        val sceneGen = AtomicInteger(0)
        val inferChannel = Channel<Pair<Bitmap, Long>>(
            capacity = 2,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val tracker  = ByteTracker()
        val voteBook = PlateVoteBook()

        val inferJob = launch(Dispatchers.Default) {
            for ((bmp, ptsUs) in inferChannel) {
                try {
                    val scene = runOneInference(
                        snapshot   = bmp,
                        ptsUs      = ptsUs,
                        sourceSize = Size(bmp.width, bmp.height),
                        tracker    = tracker,
                        voteBook   = voteBook,
                        vehicle    = vehicle,
                        plate      = plate,
                        ocr        = ocr,
                    )
                    sceneRef.set(scene)
                    sceneGen.incrementAndGet()
                } catch (t: Throwable) {
                    Log.w(TAG, "AVI inference failure (continuing)", t)
                } finally {
                    try { bmp.recycle() } catch (_: Throwable) {}
                }
            }
        }

        @Suppress("DEPRECATION")
        val glDispatcher: ExecutorCoroutineDispatcher =
            newSingleThreadContext("offline-gl-avi-${System.nanoTime()}")
        var pipeline: GlOverlayPipeline? = null
        var encoded = 0L

        try {
            withContext(glDispatcher) {
                val gl = GlOverlayPipeline(
                    encoderInputSurface = encoder.inputSurface,
                    width  = srcW,
                    height = srcH,
                )
                pipeline = gl
                Log.i(TAG, "AVI processing → ${outputMp4.name}  ${srcW}x$srcH @ ${fps}fps  totalFrames=$totalFrames")
                _state.value = State.Processing(0f, 0L, totalFrames)

                var lastUploadedGen = -1
                for (avi in reader.frames()) {
                    val raw = android.graphics.BitmapFactory.decodeByteArray(avi.jpeg, 0, avi.jpeg.size) ?: continue
                    val argb = if (raw.config == Bitmap.Config.ARGB_8888 && raw.width == srcW && raw.height == srcH) raw
                        else {
                            val resized = Bitmap.createScaledBitmap(raw, srcW, srcH, true)
                            if (resized !== raw) raw.recycle()
                            if (resized.config == Bitmap.Config.ARGB_8888) resized
                            else resized.copy(Bitmap.Config.ARGB_8888, false).also { resized.recycle() }
                        }

                    gl.uploadSourceBitmap(argb)

                    // Hand the bitmap to inference on every Nth frame; otherwise recycle.
                    val isInferFrame = avi.frameIndex % inferenceEveryN.toLong() == 0L
                    if (isInferFrame) {
                        if (!inferChannel.trySend(argb to avi.presentationTimeUs).isSuccess) {
                            try { argb.recycle() } catch (_: Throwable) {}
                        }
                    } else {
                        try { argb.recycle() } catch (_: Throwable) {}
                    }

                    val gen = sceneGen.get()
                    if (gen != lastUploadedGen) {
                        renderer.render(sceneRef.get())
                        gl.uploadOverlay(renderer.bitmap)
                        lastUploadedGen = gen
                    }

                    gl.compositeFromBitmap(avi.presentationTimeUs * 1_000L)
                    encoder.drain(endOfStream = false)
                    encoded++

                    if (encoded % 8L == 0L && totalFrames > 0L) {
                        _state.value = State.Processing(
                            (encoded.toFloat() / totalFrames).coerceIn(0f, 1f),
                            encoded, totalFrames,
                        )
                    }
                }

                Log.i(TAG, "AVI EOS, encoded=$encoded; finalising encoder")
                encoder.finish()
            }

            inferChannel.close()
            inferJob.cancelAndJoin()
            _state.value = State.Done(outputMp4, encoded)
        } catch (t: Throwable) {
            Log.e(TAG, "AVI process failed", t)
            _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            try { outputMp4.delete() } catch (_: Throwable) {}
            throw t
        } finally {
            try {
                withContext(glDispatcher) {
                    try { pipeline?.release() } catch (_: Throwable) {}
                    try { renderer.release() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
            glDispatcher.close()
            try { reader.close() } catch (_: Throwable) {}
            try { vehicle.release() } catch (_: Throwable) {}
            try { plate.release()   } catch (_: Throwable) {}
            try { ocr.release()     } catch (_: Throwable) {}
        }
    }

    private suspend fun runGlPipeline(
        outputMp4: File,
        decoderFactory: () -> SurfaceVideoDecoder,
    ) = coroutineScope {
        _state.value = State.WarmingUp

        // Open the extractor first so we know the source dimensions before
        // building the encoder. This is cheap — no decoder configured yet.
        val decoder = decoderFactory()
        val srcW = decoder.width and 1.inv()
        val srcH = decoder.height and 1.inv()
        val fps  = decoder.frameRate.coerceAtLeast(15).coerceAtMost(60)
        val totalFrames = decoder.totalFrames

        // Pre-warm detectors before opening the encoder. On a cold start the
        // vehicle model takes ~5 s on Vulkan — paying that inside the
        // encode loop would yield an overlay-less prefix.
        val vehicle = NcnnVehicleDetector(context, NcnnVehicleDetector.DEFAULT_YOLO11N_SOURCE, useGpu = true)
            .also { it.initialize() }
        val plate   = NcnnPlateDetector(context, useGpu = true).also { it.initialize() }
        val ocr     = OnnxPlateOcr(context).also { it.initialize() }
        Log.i(TAG, "detectors warm: vehicle + plate + OCR ready")

        val encoder  = OverlayVideoEncoder(
            outputFile = outputMp4,
            width      = srcW,
            height     = srcH,
            frameRate  = fps,
        )
        val renderer = OverlayBitmapRenderer(context, srcW, srcH)

        // AI inference state shared with the GL thread via atomic refs.
        val sceneRef = AtomicReference<TrackedScene?>(null)
        val sceneGen = AtomicInteger(0)
        val inferChannel = Channel<Pair<Bitmap, Long>>(
            capacity = 2,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val tracker  = ByteTracker()
        val voteBook = PlateVoteBook()

        val inferJob = launch(Dispatchers.Default) {
            for ((snapshot, ptsUs) in inferChannel) {
                try {
                    val scene = runOneInference(
                        snapshot   = snapshot,
                        ptsUs      = ptsUs,
                        sourceSize = Size(snapshot.width, snapshot.height),
                        tracker    = tracker,
                        voteBook   = voteBook,
                        vehicle    = vehicle,
                        plate      = plate,
                        ocr        = ocr,
                    )
                    sceneRef.set(scene)
                    sceneGen.incrementAndGet()
                } catch (t: Throwable) {
                    Log.w(TAG, "inference failure (continuing)", t)
                } finally {
                    try { snapshot.recycle() } catch (_: Throwable) {}
                }
            }
        }

        // Pin the entire GL section to one thread — EGL context state can't
        // safely migrate across coroutines on Dispatchers.Default's pool.
        @Suppress("DEPRECATION")
        val glDispatcher: ExecutorCoroutineDispatcher =
            newSingleThreadContext("offline-gl-${System.nanoTime()}")
        var pipeline: GlOverlayPipeline? = null
        var encoded  = 0L

        try {
            withContext(glDispatcher) {
                val gl = GlOverlayPipeline(
                    encoderInputSurface = encoder.inputSurface,
                    width  = srcW,
                    height = srcH,
                    // Full source resolution for the AI snapshot — keeps
                    // plate crops sharp for OCR even on distant plates.
                    snapshotWidth = srcW,
                )
                pipeline = gl
                decoder.start(gl.decoderSurface)

                Log.i(TAG, "processing → ${outputMp4.name}  ${srcW}x$srcH @ ${fps}fps  totalFrames=$totalFrames")
                _state.value = State.Processing(0f, 0L, totalFrames)

                var lastUploadedGen = -1
                decoder.pump { ptsUs, idx ->
                    if (!gl.awaitNewFrame(2_000L)) {
                        Log.w(TAG, "decoder timed out at frame $idx, pts=$ptsUs us")
                        return@pump false
                    }

                    // Every Nth frame: snapshot for AI. Bitmap ownership
                    // transfers to the inference coroutine on send.
                    if (idx % inferenceEveryN.toLong() == 0L) {
                        val snap = gl.snapshotForInference()
                        if (!inferChannel.trySend(snap to ptsUs).isSuccess) {
                            try { snap.recycle() } catch (_: Throwable) {}
                        }
                    }

                    // Re-render + re-upload overlay only when scene changed.
                    val gen = sceneGen.get()
                    if (gen != lastUploadedGen) {
                        renderer.render(sceneRef.get())
                        gl.uploadOverlay(renderer.bitmap)
                        lastUploadedGen = gen
                    }

                    gl.composite(ptsUs * 1_000L)
                    encoder.drain(endOfStream = false)
                    encoded++

                    if (encoded % 8L == 0L && totalFrames > 0L) {
                        _state.value = State.Processing(
                            fractionDone = (encoded.toFloat() / totalFrames).coerceIn(0f, 1f),
                            frameIndex   = encoded,
                            totalFrames  = totalFrames,
                        )
                    }
                    true
                }

                Log.i(TAG, "decoder EOS, encoded=$encoded; finalising encoder")
                encoder.finish()
            }

            inferChannel.close()
            inferJob.cancelAndJoin()
            _state.value = State.Done(outputMp4, encoded)
        } catch (t: Throwable) {
            Log.e(TAG, "process failed", t)
            _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            try { outputMp4.delete() } catch (_: Throwable) {}
            throw t
        } finally {
            // GL deletes must run on the GL thread that owns the EGL context.
            try {
                withContext(glDispatcher) {
                    try { pipeline?.release()  } catch (_: Throwable) {}
                    try { renderer.release()  } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
            glDispatcher.close()
            try { decoder.close() } catch (_: Throwable) {}
            try { vehicle.release() } catch (_: Throwable) {}
            try { plate.release()   } catch (_: Throwable) {}
            try { ocr.release()     } catch (_: Throwable) {}
        }
    }

    /**
     * Synchronous run of vehicle → plate → OCR → tracker → voting on one
     * snapshot bitmap. Boxes are emitted in [snapshot]-pixel coordinates;
     * the renderer scales them to encoder resolution at draw time.
     */
    private suspend fun runOneInference(
        snapshot:   Bitmap,
        ptsUs:      Long,
        sourceSize: Size,
        tracker:    ByteTracker,
        voteBook:   PlateVoteBook,
        vehicle:    NcnnVehicleDetector,
        plate:      NcnnPlateDetector,
        ocr:        OnnxPlateOcr,
    ): TrackedScene {
        val frame = Frame(bitmap = snapshot, timestampNanos = ptsUs * 1_000L)
        val vehicles: List<Detection> = vehicle.detect(frame)
        val tracks:   List<TrackedDetection> = tracker.update(vehicles)
        voteBook.prune(tracker.activeTrackIds())

        val plates: List<PlateDetection> = if (tracks.isNotEmpty()) {
            tracks.flatMap { t ->
                val x = t.bbox.left.toInt()
                val y = t.bbox.top.toInt()
                val w = (t.bbox.right  - t.bbox.left).toInt()
                val h = (t.bbox.bottom - t.bbox.top ).toInt()
                val raw = runCatching { plate.detectInCrop(snapshot, t.trackId, x, y, w, h) }
                    .getOrElse { emptyList() }
                raw.map { p -> p.copy(parentTrackId = t.trackId) }
            }
        } else emptyList()

        val plated: List<PlateDetection> = plates.map { p ->
            val tid = p.parentTrackId
            val crop = cropBitmap(snapshot, p.bbox) ?: return@map p
            val recog = runCatching { ocr.recognize(crop) }.getOrNull()
            crop.recycle()
            if (tid != null && recog != null && recog.text.isNotEmpty()) {
                voteBook.record(tid, recog.text)
            }
            val voted = tid?.let { voteBook.bestText(it) }
            p.copy(recognition = recog, votedText = voted)
        }

        return TrackedScene(
            sourceFrameSize    = sourceSize,
            detections         = vehicles,
            timestampNanos     = ptsUs * 1_000L,
            inferenceLatencyMs = 0,  // not tracked offline
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

    companion object { private const val TAG = "Trafy.OfflineVideo" }
}
