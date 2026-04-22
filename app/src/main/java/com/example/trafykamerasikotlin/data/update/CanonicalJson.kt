package com.example.trafykamerasikotlin.data.update

import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical JSON serializer matching the server-side rule in services/apkSigner.js.
 *
 * Rules:
 *  - Object keys sorted by UTF-16 code units (== [String.compareTo] == JS default sort).
 *  - No whitespace: no spaces after `:` or `,`, no newlines, no indentation.
 *  - UTF-8 output. Non-ASCII code points pass through as raw UTF-8 bytes (NOT \uXXXX-escaped).
 *  - String escaping: \\, \", \b, \f, \n, \r, \t, plus \u00XX for control chars U+0000..U+001F
 *    and for lone surrogates. Forward slashes are NOT escaped.
 *  - Numbers: integers output as plain decimal, no exponent, no trailing .0.
 *  - Booleans / null: literal true / false / null.
 *
 * DO NOT use [JSONObject.toString] — it doesn't sort keys and differs on escape forms.
 */
object CanonicalJson {

    /**
     * Canonicalizes [obj] to UTF-8 bytes. Caller is responsible for removing any
     * fields that shouldn't be part of the signed payload (e.g. `signature`).
     */
    fun canonicalize(obj: JSONObject): ByteArray {
        val sb = StringBuilder()
        writeObject(sb, obj)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> sb.append("null")
            is Boolean            -> sb.append(if (value) "true" else "false")
            is JSONObject         -> writeObject(sb, value)
            is JSONArray          -> writeArray(sb, value)
            is String             -> writeString(sb, value)
            is Number             -> writeNumber(sb, value)
            else                  -> throw IllegalArgumentException(
                "Unsupported JSON value type: ${value.javaClass.name}"
            )
        }
    }

    private fun writeObject(sb: StringBuilder, obj: JSONObject) {
        sb.append('{')
        val keys = obj.keys().asSequence().toMutableList()
        keys.sort()
        var first = true
        for (k in keys) {
            if (!first) sb.append(',')
            writeString(sb, k)
            sb.append(':')
            write(sb, obj.opt(k))
            first = false
        }
        sb.append('}')
    }

    private fun writeArray(sb: StringBuilder, arr: JSONArray) {
        sb.append('[')
        for (i in 0 until arr.length()) {
            if (i > 0) sb.append(',')
            write(sb, arr.opt(i))
        }
        sb.append(']')
    }

    private fun writeNumber(sb: StringBuilder, n: Number) {
        when (n) {
            is Int, is Long, is Short, is Byte -> sb.append(n.toString())
            is Double -> {
                if (!n.isFinite()) {
                    throw IllegalArgumentException("Non-finite number: $n")
                }
                val asLong = n.toLong()
                if (asLong.toDouble() == n) {
                    sb.append(asLong.toString())
                } else {
                    // No guarantee this matches JS number serialization for fractional
                    // values. The manifest only contains integers, so we refuse rather
                    // than silently produce a mismatched canonical form.
                    throw IllegalArgumentException("Non-integer number not supported in manifest: $n")
                }
            }
            else -> sb.append(n.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '\\'             -> sb.append("\\\\")
                c == '"'              -> sb.append("\\\"")
                c.code == 0x08        -> sb.append("\\b")
                c.code == 0x0C        -> sb.append("\\f")
                c.code == 0x0A        -> sb.append("\\n")
                c.code == 0x0D        -> sb.append("\\r")
                c.code == 0x09        -> sb.append("\\t")
                c.code < 0x20         -> appendUnicodeEscape(sb, c.code)
                Character.isHighSurrogate(c) -> {
                    val next = if (i + 1 < n) s[i + 1] else ' '
                    if (i + 1 < n && Character.isLowSurrogate(next)) {
                        // valid pair — pass both code units through as-is
                        sb.append(c)
                        sb.append(next)
                        i++
                    } else {
                        // lone high surrogate — escape
                        appendUnicodeEscape(sb, c.code)
                    }
                }
                Character.isLowSurrogate(c) -> appendUnicodeEscape(sb, c.code)
                else                  -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    /** Emit `\uXXXX` with lowercase hex to match Node's JSON.stringify. */
    private fun appendUnicodeEscape(sb: StringBuilder, code: Int) {
        sb.append("\\u")
        sb.append(HEX[(code shr 12) and 0xF])
        sb.append(HEX[(code shr 8)  and 0xF])
        sb.append(HEX[(code shr 4)  and 0xF])
        sb.append(HEX[code          and 0xF])
    }

    private val HEX = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
    )
}
