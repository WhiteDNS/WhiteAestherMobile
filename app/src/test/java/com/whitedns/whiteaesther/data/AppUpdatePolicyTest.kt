package com.whitedns.whiteaesther.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AppUpdatePolicyTest {
    private fun installed(
        version: String = "1.8.1",
        code: Long = 100_026,
        signers: Set<String> = setOf("aa"),
        variant: ApkVariant? = ApkVariant.Arm64V8a,
    ) = ApkIdentity("com.whitedns.whiteaesther", version, code, signers, variant)

    private fun release(version: String = "1.9.0") =
        AppRelease(version, "https://github.com/${AppUpdatePolicy.REPOSITORY}/releases/tag/$version")

    /**
     * The one rule that is a guard against an attack rather than a mistake.
     *
     * An app that installs its own updates is a delivery channel for whatever
     * it is pointed at. A key we already trust is the only thing separating our
     * release from an APK substituted along the way — everything else here can
     * be forged by whoever forged the rest.
     */
    @Test
    fun anApkSignedByAnotherKeyIsRefused() {
        val error = assertThrows(IOException::class.java) {
            AppUpdatePolicy.validate(
                candidate = installed(version = "1.9.0", code = 100_027, signers = setOf("bb")),
                installed = installed(),
                release = release(),
            )
        }
        assertTrue(error.message!!, error.message!!.contains("different key"))
    }

    /** An unsigned APK is not a match for anything, including another unsigned one. */
    @Test
    fun anUnsignedApkIsNeverAMatch() {
        assertThrows(IOException::class.java) {
            AppUpdatePolicy.validate(
                candidate = installed(version = "1.9.0", code = 100_027, signers = emptySet()),
                installed = installed(signers = emptySet()),
                release = release(),
            )
        }
    }

    /** Only forwards, and only to the version the release actually published. */
    @Test
    fun anApkThatIsNotTheNewerReleaseIsRefused() {
        // Older.
        assertThrows(IOException::class.java) {
            AppUpdatePolicy.validate(
                installed(version = "1.7.0", code = 100_020),
                installed(),
                release("1.7.0"),
            )
        }
        // The same version code, which is what an installer would refuse anyway.
        assertThrows(IOException::class.java) {
            AppUpdatePolicy.validate(
                installed(version = "1.9.0", code = 100_026),
                installed(),
                release(),
            )
        }
        // An APK whose version is not the one the release claims.
        assertThrows(IOException::class.java) {
            AppUpdatePolicy.validate(
                installed(version = "2.0.0", code = 100_027),
                installed(),
                release("1.9.0"),
            )
        }
    }

    /**
     * The wrong architecture is refused, not installed and found out later.
     *
     * At best it fails to install. At worst it installs and the app is missing
     * the native libraries that are the entire engine.
     */
    @Test
    fun anApkForAnotherArchitectureIsRefused() {
        assertThrows(IOException::class.java) {
            AppUpdatePolicy.validate(
                installed(version = "1.9.0", code = 100_027, variant = ApkVariant.X86_64),
                installed(variant = ApkVariant.Arm64V8a),
                release(),
            )
        }
    }

    @Test
    fun aMatchingNewerApkIsAccepted() {
        AppUpdatePolicy.validate(
            installed(version = "1.9.0", code = 100_027),
            installed(),
            release(),
        )
    }

    /** The architecture is read off the engine every build of this app ships. */
    @Test
    fun theArchitectureIsReadFromTheEngineItShips() {
        assertEquals(
            ApkVariant.Arm64V8a,
            AppUpdatePolicy.detectVariant(
                sequenceOf("lib/arm64-v8a/libwhiteaesther_core.so", "classes.dex"),
            ),
        )
        assertEquals(
            ApkVariant.Universal,
            AppUpdatePolicy.detectVariant(
                ApkVariant.entries.filter { it != ApkVariant.Universal }
                    .map { "lib/${it.suffix}/libwhiteaesther_core.so" }
                    .asSequence(),
            ),
        )
        // Not this app: no engine, so no answer rather than a guess.
        assertNull(AppUpdatePolicy.detectVariant(sequenceOf("lib/arm64-v8a/libsomething.so")))
        // Some but not all: neither a split nor a universal.
        assertNull(
            AppUpdatePolicy.detectVariant(
                sequenceOf(
                    "lib/arm64-v8a/libwhiteaesther_core.so",
                    "lib/x86_64/libwhiteaesther_core.so",
                ),
            ),
        )
    }

    /**
     * A prerelease is not offered unless it was asked for.
     *
     * It is how a build reaches a few people for testing without going to
     * everyone, and an updater that took it anyway would undo exactly that.
     */
    @Test
    fun aPrereleaseIsSkippedUnlessItIsWanted() {
        val json = JSONObject(releaseJson(prerelease = true))
        assertThrows(IOException::class.java) {
            AppUpdatePolicy.releaseFrom(json, ApkVariant.Arm64V8a, acceptPrereleases = false)
        }
        val taken = AppUpdatePolicy.releaseFrom(json, ApkVariant.Arm64V8a, acceptPrereleases = true)
        assertEquals("1.9.0", taken.version)
        assertTrue(taken.downloadable)
    }

    /** A draft is never offered, however it is asked for. */
    @Test
    fun aDraftIsNeverOffered() {
        val json = JSONObject(releaseJson(draft = true))
        assertThrows(IOException::class.java) {
            AppUpdatePolicy.releaseFrom(json, ApkVariant.Arm64V8a, acceptPrereleases = true)
        }
    }

    /**
     * A download may only come from this project's releases.
     *
     * The URL arrives in the same answer as everything else, so it is exactly
     * as trustworthy as the thing it is meant to authenticate. Checking the
     * host and the whole path is what stops an answer sending the app somewhere
     * else for the bytes.
     */
    @Test
    fun aDownloadUrlPointingElsewhereIsRefused() {
        for (url in listOf(
            "https://example.com/WhiteDNS/WhiteAestherMobile/releases/download/1.9.0/x.apk",
            "http://github.com/WhiteDNS/WhiteAestherMobile/releases/download/1.9.0/x.apk",
            "https://github.com/someone/else/releases/download/1.9.0/x.apk",
            "https://github.com/WhiteDNS/WhiteAestherMobile/releases/download/1.9.0/x.apk?to=elsewhere",
            "https://user@github.com/WhiteDNS/WhiteAestherMobile/releases/download/1.9.0/x.apk",
        )) {
            assertThrows(url, IOException::class.java) {
                AppUpdatePolicy.requireDownloadUrl(url, "1.9.0", "x.apk")
            }
        }
        AppUpdatePolicy.requireDownloadUrl(
            "https://github.com/WhiteDNS/WhiteAestherMobile/releases/download/1.9.0/x.apk",
            "1.9.0",
            "x.apk",
        )
    }

    /** One line names the asset, or the file is not usable. */
    @Test
    fun aChecksumHasToNameTheAssetExactlyOnce() {
        val hash = "a".repeat(64)
        assertEquals(
            hash,
            AppUpdatePolicy.checksumFor("$hash  WhiteAestherMobile-1.9.0-arm64-v8a.apk\n", "WhiteAestherMobile-1.9.0-arm64-v8a.apk"),
        )
        // Named twice: malformed rather than ambiguous. Choosing either would be
        // choosing which of two answers to trust about something with one.
        assertThrows(IOException::class.java) {
            AppUpdatePolicy.checksumFor("$hash  x.apk\n$hash  x.apk\n", "x.apk")
        }
        // Not named at all.
        assertThrows(IOException::class.java) {
            AppUpdatePolicy.checksumFor("$hash  other.apk\n", "x.apk")
        }
        // Not a checksum file.
        assertThrows(IOException::class.java) {
            AppUpdatePolicy.checksumFor("nonsense\n", "x.apk")
        }
    }

    /** A tag that is not a version cannot become a download path. */
    @Test
    fun aTagThatIsNotAVersionIsRefused() {
        assertEquals("1.9.0", AppUpdatePolicy.normalizedVersion("v1.9.0"))
        assertEquals("1.9", AppUpdatePolicy.normalizedVersion(" 1.9 "))
        for (bad in listOf("", "latest", "1.9.0-rc1", "../../etc", "1.9.0/x", "v")) {
            assertEquals(bad, "", AppUpdatePolicy.normalizedVersion(bad))
        }
    }

    /** A version the user said no to is not offered again. */
    @Test
    fun aRefusedVersionIsNotOfferedAgain() {
        assertTrue(AppUpdatePolicy.shouldOffer("1.9.0", "1.8.1", skipped = null))
        assertFalse(AppUpdatePolicy.shouldOffer("1.9.0", "1.8.1", skipped = "1.9.0"))
        // But the one after it is.
        assertTrue(AppUpdatePolicy.shouldOffer("1.9.1", "1.8.1", skipped = "1.9.0"))
    }

    /**
     * The download only happens where the tunnel actually carries it.
     *
     * The downloader is a system service, outside this process, so the app's
     * own proxy does not cover it. Fetching a release outside the tunnel from a
     * censored address is an announcement that this device runs a circumvention
     * tool — louder than the check, because it is forty megabytes of a file
     * whose name says what it is.
     */
    @Test
    fun aReleaseIsOnlyFetchedUnderAWholeDeviceTunnel() {
        assertTrue(
            AppUpdatePolicy.mayDownload(
                selfUpdates = true,
                coverage = Coverage.WholeDevice,
                connected = true,
            ),
        )
        // Connected, but the tunnel does not carry the downloader.
        for (partial in listOf(
            Coverage.ProxyOnly,
            Coverage.NothingChosen,
            Coverage.OnlySome(3),
            Coverage.AllExcept(3),
        )) {
            assertFalse(
                partial.toString(),
                AppUpdatePolicy.mayDownload(true, partial, connected = true),
            )
        }
        // Whole device, but nothing is up: the request would leave in the clear.
        assertFalse(AppUpdatePolicy.mayDownload(true, Coverage.WholeDevice, connected = false))
        // An F-Droid build is updated by F-Droid. Two updaters for one app means
        // whichever runs first wins, and the user is left on a version neither
        // of them thinks it installed.
        assertFalse(AppUpdatePolicy.mayDownload(false, Coverage.WholeDevice, connected = true))
    }

    private fun releaseJson(prerelease: Boolean = false, draft: Boolean = false): String {
        val base = "https://github.com/${AppUpdatePolicy.REPOSITORY}/releases"
        val apk = "WhiteAestherMobile-1.9.0-arm64-v8a.apk"
        return """
            {
              "tag_name": "1.9.0",
              "draft": $draft,
              "prerelease": $prerelease,
              "html_url": "$base/tag/1.9.0",
              "assets": [
                {"name":"$apk","state":"uploaded","size":47326829,
                 "browser_download_url":"$base/download/1.9.0/$apk"},
                {"name":"SHA256SUMS","state":"uploaded","size":531,
                 "browser_download_url":"$base/download/1.9.0/SHA256SUMS"}
              ]
            }
        """.trimIndent()
    }
}
