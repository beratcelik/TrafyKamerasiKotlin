package com.example.trafykamerasikotlin.data.vision.ocr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * ONNX Runtime-backed OCR for the fast-plate-ocr `cct-s-v2-global-model`.
 *
 * Model I/O (verified by inspecting the downloaded ONNX):
 *  - Input `input`: UINT8 tensor, shape [1, 64, 128, 3], layout HWC (RGB).
 *    Preprocessing is "just resize to 128×64 and pass raw pixels" — the
 *    model does its own normalization internally.
 *  - Output `plate`: float tensor, shape [1, 10, 37]. Ten character slots,
 *    each a logit vector over the 37-char alphabet.
 *  - Output `region`: float tensor, shape [1, 66]. Country classifier.
 *
 * Decode: softmax each slot, pick argmax, join chars, strip trailing `_` pads.
 * Mean confidence is the geometric average of the non-pad slots' max-probs.
 *
 * Runtime choice: ONNX Runtime instead of NCNN because the CCT's transformer
 * attention ops don't convert cleanly via PNNX in the NCNN release we pin
 * (same class of bug we hit on YOLO26's CPU backend).
 */
class OnnxPlateOcr(
    private val context: Context,
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET,
    private val metaAssetPath:  String = DEFAULT_META_ASSET,
) : PlateOcr {

    private var env:     OrtEnvironment? = null
    private var session: OrtSession?     = null

    private var alphabet: String      = DEFAULT_ALPHABET
    private var padChar:  Char        = DEFAULT_PAD_CHAR
    private var inputH:   Int         = 64
    private var inputW:   Int         = 128
    private var inputC:   Int         = 3
    private var padR:     Int         = 114
    private var padG:     Int         = 114
    private var padB:     Int         = 114

    // Per-call scratch reused across recognize() invocations. The pipeline
    // serializes calls on a single inference coroutine, so plain mutable
    // fields are safe — no concurrency. Allocated once in initialize().
    private var pixelScratch:  IntArray?   = null
    private var inputBuffer:   ByteBuffer? = null
    private var resizeScratch: Bitmap?     = null
    private var resizeCanvas:  Canvas?     = null
    private val resizePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val srcRectScratch = Rect()
    private val dstRectScratch = Rect()

    override suspend fun initialize() {
        withContext(Dispatchers.Default) {
            loadMetaFromAssets()
            val bytes = context.assets.open(modelAssetPath).use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(bytes, opts)
            this@OnnxPlateOcr.env = env
            this@OnnxPlateOcr.session = session
            // Scratch buffers — sized from the meta (inputW × inputH × inputC).
            pixelScratch = IntArray(inputW * inputH)
            inputBuffer  = ByteBuffer
                .allocateDirect(inputH * inputW * inputC)
                .order(ByteOrder.nativeOrder())
            resizeScratch = Bitmap.createBitmap(inputW, inputH, Bitmap.Config.ARGB_8888)
            resizeCanvas  = Canvas(resizeScratch!!)
            dstRectScratch.set(0, 0, inputW, inputH)
            Log.i(TAG, "initialized: model=$modelAssetPath " +
                    "inputs=${session.inputNames} outputs=${session.outputNames} " +
                    "alphabet=${alphabet.length}")
        }
    }

    override suspend fun recognize(plateCrop: Bitmap): PlateRecognition = withContext(Dispatchers.Default) {
        val s = session ?: return@withContext emptyResult()
        val e = env     ?: return@withContext emptyResult()
        val resized = resizeScratch ?: return@withContext emptyResult()
        val canvas  = resizeCanvas  ?: return@withContext emptyResult()
        val pixels  = pixelScratch  ?: return@withContext emptyResult()
        val buf     = inputBuffer   ?: return@withContext emptyResult()

        // Preprocess: ARGB_8888 bitmap -> inputW × inputH RGB uint8 in HWC.
        // The scratch Bitmap + Canvas are reused across calls; we just clear
        // and redraw. fast-plate-ocr's config has keep_aspect_ratio=False, so
        // a straight stretched draw matches the trained preprocessing.
        canvas.drawColor(Color.rgb(padR, padG, padB))
        srcRectScratch.set(0, 0, plateCrop.width, plateCrop.height)
        canvas.drawBitmap(plateCrop, srcRectScratch, dstRectScratch, resizePaint)
        resized.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)

        // Pack ARGB ints → (R, G, B) bytes into the reused direct buffer.
        buf.clear()
        var i = 0
        val total = inputW * inputH
        while (i < total) {
            val p = pixels[i]
            buf.put(((p shr 16) and 0xFF).toByte())
            buf.put(((p shr 8)  and 0xFF).toByte())
            buf.put(( p         and 0xFF).toByte())
            i++
        }
        buf.rewind()

        val inputName = s.inputNames.first()
        val tensor = OnnxTensor.createTensor(
            e, buf,
            longArrayOf(1, inputH.toLong(), inputW.toLong(), inputC.toLong()),
            OnnxJavaType.UINT8,
        )
        val outputs = try {
            s.run(mapOf(inputName to tensor))
        } finally {
            tensor.close()
        }

        // plate head: [1, 10, 37]
        @Suppress("UNCHECKED_CAST")
        val plate3d = outputs.get("plate").get().value as Array<Array<FloatArray>>
        val slots = plate3d[0]  // [numSlots][alphaSize]

        val numSlots = slots.size
        val chars = StringBuilder(numSlots)
        val perSlotConf = FloatArray(numSlots)
        for (slotIdx in 0 until numSlots) {
            val logits = slots[slotIdx]
            // Argmax on raw logits — softmax is monotonic, so the winning
            // index is identical. Saves a FloatArray + 37 exp() calls per slot.
            var bestIdx = 0
            var bestLogit = logits[0]
            for (k in 1 until logits.size) {
                if (logits[k] > bestLogit) { bestLogit = logits[k]; bestIdx = k }
            }
            chars.append(alphabet.getOrElse(bestIdx) { padChar })
            // Confidence (softmax probability of the winner) is only needed
            // for slots that actually contribute to the displayed text. Pad
            // slots get 0f without computing exp().
            perSlotConf[slotIdx] = if (alphabet.getOrNull(bestIdx) == padChar) {
                0f
            } else {
                softmaxOfWinner(logits, bestIdx)
            }
        }
        // Strip trailing pads from the text.
        val text = chars.toString().trimEnd(padChar)
        // Mean confidence over the kept (non-pad) slots.
        var sum = 0f
        var count = 0
        for (k in 0 until text.length) {
            sum += perSlotConf[k]
            count++
        }
        val meanConf = if (count == 0) 0f else sum / count

        outputs.close()
        PlateRecognition(
            text = text,
            meanConfidence = meanConf,
            perSlotConfidence = perSlotConf,
            region = null,  // region head wiring deferred; not needed for Chunk 3
        )
    }

    override fun release() {
        try { session?.close() } catch (_: Throwable) {}
        // Do not close OrtEnvironment — it's a shared singleton.
        session = null
        env = null
        try { resizeScratch?.recycle() } catch (_: Throwable) {}
        resizeScratch = null
        resizeCanvas = null
        pixelScratch = null
        inputBuffer = null
    }

    private fun loadMetaFromAssets() {
        try {
            val lines = context.assets.open(metaAssetPath).bufferedReader().readLines()
            val kv = lines
                .mapNotNull { it.takeIf { !it.startsWith("#") && "=" in it } }
                .associate { line -> line.substringBefore('=') to line.substringAfter('=') }
            alphabet = kv["alphabet"] ?: alphabet
            padChar  = kv["pad_char"]?.firstOrNull() ?: padChar
            inputH   = kv["input_h"]?.toIntOrNull() ?: inputH
            inputW   = kv["input_w"]?.toIntOrNull() ?: inputW
            inputC   = kv["input_channels"]?.toIntOrNull() ?: inputC
            kv["padding_color"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.let {
                if (it.size == 3) { padR = it[0]; padG = it[1]; padB = it[2] }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not read $metaAssetPath, falling back to hardcoded defaults", t)
        }
    }

    /**
     * Softmax probability of the winning logit only — avoids allocating a
     * full exp-output array. We need this for the displayed confidence
     * (single number per slot), not the full distribution.
     *
     * P(winner) = exp(L_winner − Lmax) / Σ exp(L_k − Lmax)
     *           = 1 / Σ exp(L_k − L_winner)   (since L_winner == Lmax)
     */
    private fun softmaxOfWinner(logits: FloatArray, winnerIdx: Int): Float {
        val winnerLogit = logits[winnerIdx]
        var sum = 0.0
        for (k in logits.indices) {
            sum += exp((logits[k] - winnerLogit).toDouble())
        }
        return (1.0 / sum).toFloat()
    }

    private fun emptyResult() = PlateRecognition(
        text = "", meanConfidence = 0f, perSlotConfidence = FloatArray(0), region = null,
    )

    companion object {
        private const val TAG = "Trafy.PlateOcr"
        const val DEFAULT_MODEL_ASSET = "models/plate_ocr/cct_s_v2_global.onnx"
        const val DEFAULT_META_ASSET  = "models/plate_ocr/meta.txt"

        private const val DEFAULT_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_"
        private const val DEFAULT_PAD_CHAR = '_'
    }
}
