package com.example.trafykamerasikotlin.data.update

import com.google.crypto.tink.subtle.Ed25519Verify
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * Test vector supplied by the server author. If this test passes, our canonicalizer
 * produces the same bytes the server produced, and the embedded public key verifies
 * the server's signature — proving end-to-end interop before we ship.
 *
 * If this fails, DO NOT SHIP. Diff the computed canonical string against [EXPECTED_CANONICAL]
 * to find the mismatch — most likely whitespace, key sort order, or escape form.
 */
class ManifestVerificationTest {

    private val PUBLIC_KEY_B64 = "kxlWdxBAtnkApChfK/PifgozKzSm3oHU5UK0U3wr+yo="

    private val TEST_MANIFEST = """
        {
          "versionCode": 2,
          "versionName": "1.1.0",
          "apkUrl": "https://trafy.tr/app/trafy-1.1.0-vc2.apk",
          "sha256": "abc123def456",
          "fileSize": 12345678,
          "mandatory": false,
          "releaseNotes": {
            "en": "Faster media loading",
            "tr": "Daha hızlı medya yükleme"
          },
          "signedAt": "2026-04-22T17:00:00.000Z",
          "signature": "+s+wpFi1jbLZdk/wdj14lGtoEuiZ//AXgxXxNVslJ2duKlYoEx6q4y08CHQdLzn7M6+TlkcjN446kt9hoMWjDw=="
        }
    """.trimIndent()

    private val EXPECTED_CANONICAL =
        """{"apkUrl":"https://trafy.tr/app/trafy-1.1.0-vc2.apk","fileSize":12345678,"mandatory":false,"releaseNotes":{"en":"Faster media loading","tr":"Daha hızlı medya yükleme"},"sha256":"abc123def456","signedAt":"2026-04-22T17:00:00.000Z","versionCode":2,"versionName":"1.1.0"}"""

    private val EXPECTED_HEX =
        "7b2261706b55726c223a2268747470733a2f2f74726166792e74722f6170702f74726166792d312e312e302d7663322e61706b222c2266696c6553697a65223a31323334353637382c226d616e6461746f7279223a66616c73652c2272656c656173654e6f746573223a7b22656e223a22466173746572206d65646961206c6f6164696e67222c227472223a22446168612068c4b17a6cc4b1206d656479612079c3bc6b6c656d65227d2c22736861323536223a22616263313233646566343536222c227369676e65644174223a22323032362d30342d32325431373a30303a30302e3030305a222c2276657273696f6e436f6465223a322c2276657273696f6e4e616d65223a22312e312e30227d"

    @Test
    fun `canonical bytes match server test vector exactly`() {
        val obj = JSONObject(TEST_MANIFEST)
        obj.remove("signature")
        val canonical = CanonicalJson.canonicalize(obj)

        assertEquals(
            "canonical string mismatch — server sig will never verify until this matches byte-for-byte",
            EXPECTED_CANONICAL,
            canonical.toString(Charsets.UTF_8),
        )
        assertArrayEquals(
            "canonical bytes differ from server reference",
            hexToBytes(EXPECTED_HEX),
            canonical,
        )
        // Expected byte count of the supplied hex vector (the number in the user's
        // message mentioning "273 bytes" was an off-by-count — the hex decodes to 271).
        assertEquals("byte count differs from server reference", 271, canonical.size)
    }

    @Test
    fun `embedded public key verifies server signature over canonical bytes`() {
        val obj = JSONObject(TEST_MANIFEST)
        val sigBase64 = obj.getString("signature")
        obj.remove("signature")
        val canonical = CanonicalJson.canonicalize(obj)

        val sig = Base64.getDecoder().decode(sigBase64)
        val pub = Base64.getDecoder().decode(PUBLIC_KEY_B64)

        // Throws GeneralSecurityException on mismatch — we let it propagate so JUnit shows it.
        Ed25519Verify(pub).verify(sig, canonical)
    }

    @Test(expected = java.security.GeneralSecurityException::class)
    fun `tampered manifest fails verification`() {
        val obj = JSONObject(TEST_MANIFEST)
        val sigBase64 = obj.getString("signature")
        obj.remove("signature")
        // flip versionCode — signature must now fail
        obj.put("versionCode", 3)
        val canonical = CanonicalJson.canonicalize(obj)

        val sig = Base64.getDecoder().decode(sigBase64)
        val pub = Base64.getDecoder().decode(PUBLIC_KEY_B64)
        Ed25519Verify(pub).verify(sig, canonical)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or
                      Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
