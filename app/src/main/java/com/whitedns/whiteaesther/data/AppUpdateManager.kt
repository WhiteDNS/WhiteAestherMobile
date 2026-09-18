package com.whitedns.whiteaesther.data

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import com.whitedns.whiteaesther.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Where a download has got to. */
sealed interface UpdateDownload {
    data object Idle : UpdateDownload
    data class Running(val done: Long, val total: Long) : UpdateDownload
    data class Ready(val apk: File, val release: AppRelease) : UpdateDownload
    data class Failed(val reason: String) : UpdateDownload
}

/**
 * Fetches a release, checks it, and hands the installer an APK.
 *
 * [AppUpdatePolicy] holds every rule about what may be installed; this holds
 * the parts that need a phone. The split is deliberate: the rules are what stop
 * this being a delivery channel for somebody else's APK, and they are worth
 * reading and testing without one.
 */
class AppUpdateManager(context: Context) {
    private val context = context.applicationContext
    private val preferences =
        this.context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val downloads = this.context.getSystemService(DownloadManager::class.java)
    private val folder get() = File(this.context.filesDir, "updates")
    private val apk get() = File(folder, "update.apk")
    private val lock = Mutex()

    /**
     * Whether this build updates itself at all.
     *
     * An F-Droid build is updated by F-Droid. Two updaters for one app means
     * whichever runs first wins, and the user is left with a version neither of
     * them thinks it installed.
     */
    val selfUpdates: Boolean get() = BuildConfig.SELF_UPDATE

    fun skipped(): String? = preferences.getString("skipped", null)

    fun skip(version: String) {
        AppUpdatePolicy.normalizedVersion(version)
            .takeIf { it.isNotEmpty() }
            ?.let { preferences.edit().putString("skipped", it).apply() }
    }

    /** Whether it is safe to pull forty megabytes from GitHub right now. */
    fun mayDownload(coverage: Coverage, connected: Boolean): Boolean =
        AppUpdatePolicy.mayDownload(selfUpdates, coverage, connected)

    /** The newest release this phone would install, or null. */
    suspend fun check(acceptPrereleases: Boolean): AppRelease? = withContext(Dispatchers.IO) {
        if (!selfUpdates) return@withContext null
        lock.withLock {
            val here = installed()
            val release = AppUpdatePolicy.releaseFrom(
                JSONObject(fetch(LATEST_RELEASE, MAX_JSON)),
                here.variant,
                acceptPrereleases,
            )
            release.takeIf {
                it.downloadable &&
                    AppUpdatePolicy.shouldOffer(it.version, here.versionName, skipped())
            }
        }
    }

    /** Starts the download, replacing anything already in flight. */
    suspend fun download(release: AppRelease): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val here = installed()
            val asset = release.apk ?: throw IOException("that release has no APK for this phone")
            if (!AppUpdatePolicy.isNewer(release.version, here.versionName)) {
                throw IOException("that release is not newer than what is installed")
            }
            // Fetched before the APK, and from the release rather than from the
            // same answer that named the APK: a checksum is only worth having
            // if it is harder to forge than the thing it checks.
            val expected = AppUpdatePolicy.checksumFor(
                fetch(release.checksums!!.url, MAX_CHECKSUMS),
                asset.name,
            )
            clear()
            val request = DownloadManager.Request(Uri.parse(asset.url))
                .setTitle(release.version)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setVisibleInDownloadsUi(false)
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationInExternalFilesDir(context, "updates", "download.apk")
            val id = downloads.enqueue(request)
            preferences.edit()
                .putLong("id", id)
                .putString("version", release.version)
                .putString("sha256", expected)
                .putLong("size", asset.size)
                .apply()
        }
    }

    /** Where the download has got to, verifying it once it has arrived. */
    suspend fun progress(release: AppRelease): UpdateDownload = withContext(Dispatchers.IO) {
        lock.withLock {
            val id = preferences.getLong("id", 0L)
            if (id == 0L) return@withLock UpdateDownload.Idle
            if (apk.isFile) {
                return@withLock runCatching { verified(release) }
                    .fold({ UpdateDownload.Ready(apk, release) }, { fail(it) })
            }
            val cursor = downloads.query(DownloadManager.Query().setFilterById(id))
                ?: return@withLock UpdateDownload.Idle
            cursor.use {
                if (!it.moveToFirst()) return@withLock UpdateDownload.Idle
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = it.getLong(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                )
                val total = it.getLong(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                )
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL ->
                        runCatching {
                            copyIn(id)
                            verified(release)
                        }.fold({ UpdateDownload.Ready(apk, release) }, { error -> fail(error) })

                    DownloadManager.STATUS_FAILED -> fail(IOException("the download did not finish"))
                    else -> UpdateDownload.Running(done, total)
                }
            }
        }
    }

    suspend fun cancel(): Unit = withContext(Dispatchers.IO) { lock.withLock { clear() } }

    /**
     * Copies the download somewhere only this app can read it.
     *
     * The downloader writes to external storage, which other apps can reach on
     * some versions of Android. An APK that something else can rewrite between
     * the check and the install is not one worth checking.
     */
    private suspend fun copyIn(id: Long) {
        val limit = preferences.getLong("size", 0L)
        if (limit <= 0L) throw IOException("that download has no expected size")
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("cannot store the update")
        val partial = File(folder, "update.apk.part")
        try {
            downloads.openDownloadedFile(id).use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            copied += read
                            if (copied > limit) throw IOException("that download is larger than the release says")
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
            }
            if (!partial.renameTo(apk)) throw IOException("cannot store the update")
        } finally {
            partial.delete()
        }
    }

    /** Throws unless the file on disk is the release, and installable over this app. */
    private suspend fun verified(release: AppRelease) {
        val expected = preferences.getString("sha256", null)
            ?: throw IOException("that download has no checksum to check against")
        val size = preferences.getLong("size", 0L)
        if (!apk.isFile || apk.length() != size) {
            throw IOException("that download is not the size the release says")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        if (hex(digest.digest()) != expected) {
            throw IOException("that download does not match the release checksum")
        }

        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apk.path, signatureFlags())
            ?: throw IOException("that download is not readable as an APK")
        AppUpdatePolicy.validate(identityOf(info, apk), installed(), release)
    }

    private fun fail(error: Throwable): UpdateDownload {
        if (error is CancellationException) throw error
        clear()
        return UpdateDownload.Failed(error.message ?: "the update could not be verified")
    }

    private fun clear() {
        preferences.getLong("id", 0L).takeIf { it != 0L }?.let { downloads.remove(it) }
        preferences.edit().remove("id").remove("sha256").remove("size").remove("version").apply()
        apk.delete()
    }

    @Suppress("DEPRECATION")
    private fun installed(): ApkIdentity = identityOf(
        context.packageManager.getPackageInfo(context.packageName, signatureFlags()),
        File(context.applicationInfo.sourceDir),
    )

    @Suppress("DEPRECATION")
    private fun identityOf(info: PackageInfo, file: File): ApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return ApkIdentity(
            packageName = info.packageName,
            versionName = info.versionName.orEmpty(),
            versionCode = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            },
            signers = signatures.orEmpty()
                .map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
                .toSet(),
            variant = AppUpdatePolicy.variantOf(file),
        )
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun fetch(url: String, limit: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "WhiteAesther/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub answered ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.read(limit) }
            return body
        } finally {
            connection.disconnect()
        }
    }

    /** Reads at most [limit] characters, refusing anything longer. */
    private fun java.io.BufferedReader.read(limit: Int): String {
        val buffer = CharArray(limit + 1)
        var filled = 0
        while (filled <= limit) {
            val read = read(buffer, filled, buffer.size - filled)
            if (read == -1) break
            filled += read
        }
        if (filled > limit) throw IOException("GitHub's answer was larger than expected")
        return String(buffer, 0, filled)
    }

    private companion object {
        const val LATEST_RELEASE =
            "https://api.github.com/repos/${AppUpdatePolicy.REPOSITORY}/releases/latest"
        const val TIMEOUT_MS = 15_000
        const val MAX_JSON = 1_048_576
        const val MAX_CHECKSUMS = 65_536
    }
}
