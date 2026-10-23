package me.csepregi.pkmrec.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A published release that contains an installable APK. */
data class ReleaseInfo(
    val version: AppVersion,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val sha256Url: String?,
)

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
internal data class GitHubAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String,
)

/**
 * Reads the latest release of the app's GitHub repository (public, no token needed)
 * and downloads its APK.
 */
class GitHubReleaseClient(
    private val repo: String,
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the latest release with an APK asset, or null if there is none. */
    fun fetchLatest(): ReleaseInfo? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return null // no release published yet
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val release = json.decodeFromString<GitHubRelease>(response.body!!.string())
            return parseRelease(release)
        }
    }

    /** Downloads [url] into [target], reporting progress as a 0..1 fraction (or -1 if unknown). */
    fun download(url: String, target: File, onProgress: (Float) -> Unit) {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body!!
            val total = body.contentLength()
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
        }
    }

    /** Downloads a small text file (the published SHA-256 checksum). */
    fun downloadText(url: String): String {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body!!.string()
        }
    }

    companion object {
        internal fun parseRelease(release: GitHubRelease): ReleaseInfo? {
            if (release.draft || release.prerelease) return null
            val version = AppVersion.parse(release.tagName) ?: return null
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
            val sha = release.assets.firstOrNull { it.name == apk.name + ".sha256" }
            return ReleaseInfo(
                version = version,
                tag = release.tagName,
                notes = release.body.orEmpty().trim(),
                apkUrl = apk.downloadUrl,
                apkName = apk.name,
                apkSize = apk.size,
                sha256Url = sha?.downloadUrl,
            )
        }

        /** The checksum file has the `sha256sum` format: "<hex>  <file name>". */
        fun parseSha256File(text: String): String? =
            text.trim().split(Regex("\\s+")).firstOrNull()
                ?.lowercase()
                ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
