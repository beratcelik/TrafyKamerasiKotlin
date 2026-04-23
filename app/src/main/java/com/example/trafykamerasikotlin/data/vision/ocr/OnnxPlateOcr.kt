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
            Log.i(TAG, "initialized: model=$modelAssetPath " +
                    "inputs=${session.inputNames} outputs=${session.outputNames} " +
                    "alphabet=${alphabet.length}")
        }
    }

    override suspend fun recognize(plateCrop: Bitmap): PlateRecognition = withContext(Dispatchers.Default) {
        val s = session ?: return@withContext emptyResult()
        val e = env     ?: return@withContext emptyResult()

        // Preprocess: ARGB_8888 bitmap -> 128×64 RGB uint8 in HWC order.
        // Spec says "no keep_aspect_ratio" — a straight resize with bilinear
        // interpolation, padded to inputH×inputW with (114, 114, 114).
        val resized = resizeWithPadding(plateCrop, inputW, inputH, padR, padG, padB)
        val buf = ByteBuffer.allocateDirect(1 * inputH * inputW * inputC).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputH * inputW)
        resized.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        for (p in pixels) {
            // ARGB_8888 → (R, G, B) bytes
            buf.put(((p shr 16) and 0xFF).toByte())
            buf.put(((p shr 8)  and 0xFF).toByte())
            buf.put(( p         and 0xFF).toByte())
        }
        buf.rewind()
        resized.recycle()

        val inputName = s.inputNames.first()
        val tensor = OnnxTensor.createTensor(e, buf, longArrayOf(1, inputH.toLong(), inputW.toLong(), inputC.toLong()), OnnxJavaType.UINT8)
        val outputs = try {
            s.run(mapOf(inputName to tensor))
        } finally {
            tensor.close()
        }

        // plate head: [1, 10, 37]
        @Suppress("UNCHECKED_CAST")
        val plate3d = outputs.get("plate").get().value as Array<Array<FloatArray>>
        val slots = plate3d[0]  // [10][37]

        val numSlots = slots.size
        val alphabetSize = slots[0].size
        val chars = StringBuilder(numSlots)
        val perSlotConf = FloatArray(numSlots)
        val padIdx = alphabet.indexOf(padChar).let { if (it < 0) alphabet.length - 1 else it }
        for (i in 0 until numSlots) {
            val logits = slots[i]
            val probs = softmax(logits)
            var bestIdx = 0
            var bestP = probs[0]
            for (k in 1 until probs.size) if (probs[k] > bestP) { bestP = probs[k]; bestIdx = k }
            perSlotConf[i] = bestP
            chars.append(alphabet.getOrElse(bestIdx) { padChar })
        }
        // Strip trailing pads.
        val text = chars.toString().trimEnd(padChar)
        val meaningful = perSlotConf.withIndex()
            .filter { (i, _) -> i < text.length }
            .map { it.value }
        val meanConf = if (meaningful.isEmpty()) 0f else meaningful.average().toFloat()

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

    private fun resizeWithPadding(src: Bitmap, w: Int, h: Int, r: Int, g: Int, b: Int): Bitmap {
        // fast-plate-ocr's config has `keep_aspect_ratio=False`, so just stretch.
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(r, g, b))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        c.drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, w, h), paint)
        return out
    }

    private fun softmax(logits: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v
        var sum = 0.0
        val exps = FloatArray(logits.size)
        for (i in logits.indices) { exps[i] = exp((logits[i] - max).toDouble()).toFloat(); sum += exps[i] }
        val inv = (1.0 / sum).toFloat()
        for (i in exps.indices) exps[i] *= inv
        return exps
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
