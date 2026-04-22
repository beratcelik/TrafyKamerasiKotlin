package com.example.trafykamerasikotlin.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Fetches `update.json`, verifies its ed25519 signature, parses it, and — on demand —
 * downloads the APK and verifies its SHA-256 before handing off to the system installer.
 *
 * Signed manifest shape (see docs/apk-signing.md on the server):
 * ```
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1.0",
 *   "apkUrl": "https://trafy.tr/app/trafy-1.1.0-vc2.apk",
 *   "sha256": "abc123...",
 *   "fileSize": 12345678,
 *   "mandatory": false,
 *   "releaseNotes": { "en": "...", "tr": "..." },
 *   "signedAt": "2026-04-22T17:00:00.000Z",
 *   "signature": "base64-ed25519"
 * }
 * ```
 */
class UpdateRepository(private val context: Context) {

    companion object {
        private const val TAG = "Trafy.UpdateRepo"

        const val MANIFEST_URL = "https://trafy.tr/app/update.json"

        private const val PREFS = "trafy_update_prefs"
        private const val KEY_LAST_CHECK_MS = "last_check_ms"
        private const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24h

        private const val APK_FILENAME = "trafy-update.apk"
    }

    sealed class CheckResult {
        data class UpdateAvailable(val info: UpdateInfo) : CheckResult()
        data object UpToDate : CheckResult()
        data object NetworkError : CheckResult()
        data object BadSignature : CheckResult()
    }

    sealed class DownloadResult {
        data class Ok(val file: File) : DownloadResult()
        data object NetworkError : DownloadResult()
        data object ChecksumMismatch : DownloadResult()
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val installedVersionCode: Int
        get() = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrDefault(0)

    val installedVersionName: String
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""

    fun shouldAutoCheck(): Boolean {
        val last = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
        return System.currentTimeMillis() - last > AUTO_CHECK_INTERVAL_MS
    }

    fun markChecked() {
        prefs.edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply()
    }

    suspend fun checkForUpdate(): CheckResult {
        val raw = UpdateHttpClient.getString(MANIFEST_URL) ?: return CheckResult.NetworkError
        val verified = ManifestVerifier.verify(raw) ?: return CheckResult.BadSignature
        val info = parseVerifiedManifest(verified) ?: return CheckResult.BadSignature
        return if (info.versionCode > installedVersionCode) {
            CheckResult.UpdateAvailable(info)
        } else {
            CheckResult.UpToDate
        }
    }

    private fun parseVerifiedManifest(obj: JSONObject): UpdateInfo? = try {
        val notes = obj.optJSONObject("releaseNotes")
        UpdateInfo(
            versionCode    = obj.getInt("versionCode"),
            versionName    = obj.getString("versionName"),
            apkUrl         = obj.getString("apkUrl"),
            sha256         = obj.getString("sha256"),
            fileSize       = obj.getLong("fileSize"),
            mandatory      = obj.optBoolean("mandatory", false),
            releaseNotesEn = notes?.optString("en", "").orEmpty(),
            releaseNotesTr = notes?.optString("tr", "").orEmpty(),
            signedAt       = obj.getString("signedAt"),
        )
    } catch (e: Exception) {
        Log.e(TAG, "parseVerifiedManifest failed: ${e.message}")
        null
    }

    private fun apkFile(): File {
        val dir = File(context.getExternalFilesDir(null), "updates")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, APK_FILENAME)
    }

    /**
     * Downloads the APK and verifies its SHA-256 against the (signed) value in [info].
     * A checksum mismatch means the file-server was compromised or the download was
     * corrupted — either way, do NOT install. Progress is 0f..1f, NaN while total is unknown.
     */
    suspend fun downloadApk(
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): DownloadResult {
        val target = apkFile()
        if (target.exists()) target.delete()
        val ok = UpdateHttpClient.download(info.apkUrl, target) { read, total ->
            val fraction = if (total > 0) read.toFloat() / total.toFloat() else Float.NaN
            onProgress(fraction)
        }
        if (!ok) return DownloadResult.NetworkError

        val actual = sha256Hex(target)
        if (!actual.equals(info.sha256, ignoreCase = true)) {
            Log.w(TAG, "sha256 mismatch — expected=${info.sha256} actual=$actual")
            target.delete()
            return DownloadResult.ChecksumMismatch
        }
        return DownloadResult.Ok(target)
    }

    private suspend fun sha256Hex(file: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Hands the downloaded APK to the system installer via FileProvider. First time,
     * Android asks the user to grant "install unknown apps" for Trafy; afterwards it's
     * just a confirm tap. Android itself verifies the APK's signing cert matches the
     * installed app's — so cross-keystore upgrades fail loudly, no client-side pin needed.
     */
    fun installApk(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
