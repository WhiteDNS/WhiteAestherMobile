package com.whitedns.whiteaesther.core

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Carrying an identity across a reinstall, through the real bridge.
 *
 * The engine's own tests cover the format; this covers the part between Kotlin
 * and Rust, where a wrong path or a mangled string would be invisible until a
 * user tried it after already uninstalling.
 */
class IdentityPortabilityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val configPath: String
        get() = File(context.filesDir, "aether.toml").absolutePath

    @Before
    fun requireBridge() {
        assertTrue("the engine did not load", NativeAetherBridge.isLoaded)
    }

    @Test
    fun anIdentityCanBeCarriedOutAndBackIn() {
        val identity = File(configPath)
        org.junit.Assume.assumeTrue(
            "no identity on this device yet; connect once first",
            identity.exists(),
        )
        val before = identity.readText()

        val payload = NativeAetherBridge.exportIdentity(configPath).getOrNull()
        assertNotNull("nothing was exported", payload)
        assertTrue("the export does not carry a format version", payload!!.contains("version"))

        // What a reinstall looks like from the engine's side.
        identity.delete()
        assertFalse(identity.exists())

        val restored = NativeAetherBridge.importIdentity(configPath, payload)
        assertTrue("the import failed: ${restored.error}", restored.ok)

        // Byte-identical, because it is the same file written by the same
        // encoder -- not a re-registration that happens to look similar.
        assertTrue("the restored identity differs from the original", identity.readText() == before)
    }

    @Test
    fun somethingThatIsNotAnIdentityIsRefusedWithoutDamage() {
        val identity = File(configPath)
        org.junit.Assume.assumeTrue("no identity to protect", identity.exists())
        val before = identity.readText()

        for (junk in listOf("", "hello", "version = 1", "<html></html>")) {
            val result = NativeAetherBridge.importIdentity(configPath, junk)
            assertFalse("accepted junk: $junk", result.ok)
            assertNotNull("refused without saying why", result.error)
        }

        // The failure that would matter: a refused import that still destroyed
        // the identity the device was using.
        assertTrue("a refused import damaged the identity in use", identity.readText() == before)
    }

    @Test
    fun exportingWithNoIdentityFailsInsteadOfWritingAnEmptyFile() {
        val scratch = File(context.cacheDir, "no-identity/aether.toml")
        scratch.parentFile?.deleteRecursively()

        val result = NativeAetherBridge.exportIdentity(scratch.absolutePath)

        // A file named as though it held an identity, that does not, is worse
        // than no file -- the user only finds out after they need it.
        assertTrue("exported something from nothing", result.isFailure)
    }
}
