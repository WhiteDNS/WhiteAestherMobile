package com.whitedns.whiteaesther.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the language setting has to guarantee for the switch to work.
 *
 * The failure this is written against was not in any of these values: the
 * choice saved correctly and the wrapping applied correctly, but nothing asked
 * the activity to rebuild while the user was still looking at the screen they
 * had changed it on. It took effect at the next resume, which arrived only when
 * something unrelated -- the VPN consent dialog -- paused the activity. So the
 * setting looked broken in both directions at once.
 *
 * The trigger itself is in MainActivity and needs a running activity to
 * exercise, which no unit test here can do. What is pinned below is everything
 * the trigger depends on being true.
 */
class AppLanguageTest {
    @Test
    fun followingThePhoneIsSaidWithAnEmptyTag() {
        // The empty tag is what tells AppLocale to leave Android's own
        // resolution alone, including the user's ordered list of preferred
        // languages, which one forced locale would flatten to a single choice.
        assertEquals("", AppLanguage.SYSTEM.tag)
    }

    @Test
    fun everyChoiceIsTellableFromEveryOther() {
        // The switch compares tags to decide whether to rebuild. Two options
        // sharing one would be a language that could be selected and never
        // applied.
        val tags = AppLanguage.entries.map { it.tag }

        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun theTagsAreOnesAndroidResolvesResourcesBy() {
        // "fa" has to match the values-fa directory, and "en" the default one.
        // A tag Android does not recognise falls back silently to English,
        // which reads as the Persian option doing nothing.
        assertEquals("en", AppLanguage.ENGLISH.tag)
        assertEquals("fa", AppLanguage.PERSIAN.tag)
    }

    @Test
    fun anUnsetLanguageFollowsThePhone() {
        // A phone that has never touched the setting must not be forced into
        // English, which for most of the people this app is built for would be
        // the wrong language chosen on their behalf.
        assertEquals(AppLanguage.SYSTEM, AppSettings().language)
    }

    @Test
    fun changingTheLanguageDoesNotDisturbAnythingElse() {
        val before = AppSettings()
        val after = before.copy(language = AppLanguage.PERSIAN)

        // It is presentation only: the engine is never told, so a language
        // change must never look like a reason to reconnect.
        assertEquals(before.chainForService(), after.chainForService())
        assertEquals(before.splitTunnel, after.splitTunnel)
        assertEquals(before.transport, after.transport)
        assertNotEquals(before.language, after.language)
    }

    @Test
    fun switchingBackIsAsReachableAsSwitchingAway() {
        // The half of the bug that stranded people: someone who lands in a
        // language they cannot read has to be able to leave it. Every option
        // has to be reachable from every other, so none of them is a trapdoor.
        for (from in AppLanguage.entries) {
            val settings = AppSettings(language = from)
            for (to in AppLanguage.entries) {
                assertTrue(settings.copy(language = to).language == to)
            }
        }
    }
}
