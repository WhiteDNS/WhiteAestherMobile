package com.whitedns.whiteaesther.data

import java.io.File
import java.io.IOException
import java.net.URI
import java.util.zip.ZipFile

/**
 * Which APK a phone is running, and therefore which one it may install over it.
 *
 * A release publishes one per architecture plus a universal build. Installing
 * the wrong one fails at best; at worst it succeeds and the app is missing the
 * native libraries that are the whole engine.
 */
enum class ApkVariant(val suffix: String) {
    Universal("universal"),
    ArmeabiV7a("armeabi-v7a"),
    Arm64V8a("arm64-v8a"),
    X86_64("x86_64"),
}

data class ReleaseAsset(val name: String, val url: String, val size: Long)

data class AppRelease(
    /** The tag, as published. */
    val version: String,
    val url: String,
    val apk: ReleaseAsset? = null,
    val checksums: ReleaseAsset? = null,
) {
    val downloadable: Boolean get() = apk != null && checksums != null
}

data class ApkIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    /** SHA-256 of each signing certificate, lower-case hex. */
    val signers: Set<String>,
    val variant: ApkVariant?,
)

/**
 * What may be installed over what.
 *
 * An app that downloads and installs its own updates is a delivery channel for
 * whatever it is pointed at. Every rule here exists because without it that
 * channel carries someone else's APK — so they are kept apart from the code
 * that does the downloading, where they can be read in one place and tested
 * without a network or a phone.
 */
object AppUpdatePolicy {
    /** The repository this app will accept a release from, and nowhere else. */
    const val REPOSITORY = "WhiteDNS/WhiteAestherMobile"

    /** Assets are named for the app, the version and the architecture. */
    fun apkNameFor(version: String, variant: ApkVariant): String =
        "WhiteAestherMobile-$version-${variant.suffix}.apk"

    /**
     * A version, or empty when it is not one.
     *
     * Deliberately narrow: digits and dots. A tag is used to build a download
     * path, and anything that is not a version has no business in one.
     */
    fun normalizedVersion(version: String): String {
        val value = version.trim().removePrefix("v").removePrefix("V")
        return value.takeIf {
            it.length <= 100 && it.matches(Regex("[0-9]+(?:\\.[0-9]+){0,3}"))
        }.orEmpty()
    }

    fun isNewer(candidate: String, installed: String): Boolean {
        val next = parts(candidate) ?: return false
        val now = parts(installed) ?: return false
        for (index in 0 until maxOf(next.size, now.size)) {
            val order = next.getOrElse(index) { 0L }.compareTo(now.getOrElse(index) { 0L })
            if (order != 0) return order > 0
        }
        return false
    }

    /** Whether to offer [latest], given what the user has already refused. */
    fun shouldOffer(latest: String, installed: String, skipped: String?): Boolean =
        isNewer(latest, installed) &&
            (skipped == null || normalizedVersion(latest) != normalizedVersion(skipped))

    /**
     * Whether a release may be fetched by the app itself, rather than opened in
     * a browser.
     *
     * The update *check* is already made only inside the tunnel, because a
     * plain request to GitHub from a censored address announces that this
     * device runs a circumvention tool. A release download is the same
     * announcement several thousand times louder, and it does not go through
     * the app's own proxy: the downloader is a system service, outside this
     * process, so only a whole-device tunnel carries it.
     *
     * So the answer is no unless the whole device is covered and the tunnel is
     * up. Anywhere else the user is sent to the release page, which is what
     * they had before this existed.
     */
    fun mayDownload(selfUpdates: Boolean, coverage: Coverage, connected: Boolean): Boolean =
        selfUpdates && connected && coverage == Coverage.WholeDevice

    /**
     * The architecture an APK carries, from the engine it ships.
     *
     * Read off `libwhiteaesther_core.so`, which every build of this app has and
     * no other file is named. A universal APK carries every architecture; a
     * split carries one. Anything else — none, or a mixture that is neither —
     * is not an APK this app recognises, and is refused rather than guessed at.
     */
    fun detectVariant(entries: Sequence<String>): ApkVariant? {
        val engine = Regex("^lib/([^/]+)/libwhiteaesther_core\\.so$")
        val found = entries.mapNotNull { engine.matchEntire(it)?.groupValues?.get(1) }.toList()
        // The same architecture twice is a malformed archive, not a universal.
        if (found.size != found.toSet().size) return null
        val abis = found.toSet()
        val splits = ApkVariant.entries.filter { it != ApkVariant.Universal }
        return when {
            abis.isEmpty() -> null
            abis == splits.map { it.suffix }.toSet() -> ApkVariant.Universal
            abis.size == 1 -> splits.singleOrNull { it.suffix == abis.single() }
            else -> null
        }
    }

    fun variantOf(file: File): ApkVariant? = runCatching {
        ZipFile(file).use { archive ->
            detectVariant(archive.entries().asSequence().filterNot { it.isDirectory }.map { it.name })
        }
    }.getOrNull()

    /**
     * Refuses anything that is not this app, newer, and signed by the same key.
     *
     * The signing check is the one that matters. Everything else here is a
     * guard against a mistake; that one is the guard against an attack, because
     * a key we already trust is the only thing distinguishing our release from
     * an APK somebody substituted along the way.
     */
    fun validate(candidate: ApkIdentity, installed: ApkIdentity, release: AppRelease) {
        if (candidate.packageName.isBlank() || candidate.packageName != installed.packageName) {
            throw IOException("that APK is not this app")
        }

        val published = normalizedVersion(release.version)
        if (published.isEmpty() ||
            normalizedVersion(candidate.versionName) != published ||
            !isNewer(release.version, installed.versionName) ||
            candidate.versionCode <= installed.versionCode
        ) {
            throw IOException("that APK is not the newer release it claims to be")
        }

        if (candidate.signers.isEmpty() ||
            installed.signers.isEmpty() ||
            candidate.signers != installed.signers
        ) {
            throw IOException("that APK is signed by a different key than this app")
        }

        if (candidate.variant == null || candidate.variant != installed.variant) {
            throw IOException("that APK is built for a different architecture")
        }
    }

    /**
     * The release, read out of what GitHub answered.
     *
     * Prereleases are skipped unless asked for. They are how a build reaches a
     * few people for testing without being offered to everyone, and an updater
     * that took them anyway would undo that.
     */
    fun releaseFrom(
        json: org.json.JSONObject,
        variant: ApkVariant?,
        acceptPrereleases: Boolean,
    ): AppRelease {
        if (json.optBoolean("draft")) throw IOException("that release is a draft")
        if (json.optBoolean("prerelease") && !acceptPrereleases) {
            throw IOException("that release is a prerelease")
        }

        val tag = json.getString("tag_name")
        val version = normalizedVersion(tag)
        if (version.isEmpty() || tag != tag.trim()) throw IOException("that release has no version")

        val wanted = variant?.let { apkNameFor(version, it) }
        val assets = json.getJSONArray("assets")
        val picked = mutableMapOf<String, ReleaseAsset>()
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.getString("name")
            if (name != wanted && name != "SHA256SUMS") continue
            if (picked.containsKey(name)) throw IOException("that release lists $name twice")
            if (asset.optString("state") != "uploaded") continue
            val size = asset.optLong("size")
            if (size <= 0) throw IOException("that release's $name has no size")
            val url = asset.getString("browser_download_url")
            requireDownloadUrl(url, tag, name)
            picked[name] = ReleaseAsset(name, url, size)
        }

        val page = json.getString("html_url")
        requireRepositoryPath(page, "/$REPOSITORY/releases/tag/$tag")
        return AppRelease(tag, page, wanted?.let { picked[it] }, picked["SHA256SUMS"])
    }

    /**
     * The hash the release published for one asset.
     *
     * One line, exactly. A file naming an asset twice is malformed rather than
     * ambiguous, and picking either would be choosing which of two answers to
     * trust about something that must have only one.
     *
     * A single leading `./` is dropped, because that is what the release
     * workflow writes: it runs `sha256sum` over a relative glob inside the
     * assets directory, so every published line names the file as
     * `./WhiteAestherMobile-1.9.0-arm64-v8a.apk`.
     * Only that exact prefix and only one of it — a deeper path names a
     * different file and is left to fail the comparison. Both sides are
     * compared after the prefix is gone, so a file naming both forms of one
     * asset is still ambiguous and still refused.
     */
    fun checksumFor(text: String, assetName: String): String {
        val line = Regex("^([a-fA-F0-9]{64}) [ *](.+)$")
        val wanted = bareName(assetName)
        val found = text.lineSequence()
            .filter { it.isNotBlank() }
            .map { line.matchEntire(it.removeSuffix("\r")) ?: throw IOException("SHA256SUMS is malformed") }
            .filter { bareName(it.groupValues[2]) == wanted }
            .map { it.groupValues[1].lowercase() }
            .toList()
        return found.singleOrNull() ?: throw IOException("SHA256SUMS does not name $assetName exactly once")
    }

    private fun bareName(entry: String): String = entry.removePrefix("./")

    /** Where a release asset may be downloaded from, and nowhere else. */
    fun requireDownloadUrl(url: String, tag: String, name: String) {
        requireRepositoryPath(url, "/$REPOSITORY/releases/download/$tag/$name")
    }

    private fun requireRepositoryPath(url: String, path: String) {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: throw IOException("that release URL is not a URL")
        if (uri.scheme != "https" || uri.host != "github.com" ||
            uri.rawPath != path || uri.rawQuery != null || uri.rawUserInfo != null
        ) {
            throw IOException("that release URL does not point at this project")
        }
    }

    private fun parts(version: String): List<Long>? = normalizedVersion(version)
        .takeIf { it.isNotEmpty() }
        ?.split('.')
        ?.mapNotNull { it.toLongOrNull() }
}
