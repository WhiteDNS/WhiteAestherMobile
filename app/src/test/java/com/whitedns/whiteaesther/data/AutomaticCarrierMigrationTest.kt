package com.whitedns.whiteaesther.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Automatic switch, as it is stored.
 *
 * The key it used to live in was written with every save, so it held whatever
 * the switch showed at the time rather than anything somebody chose -- and on
 * 1.6.1 the switch showed off. Read as a choice, that kept those phones on one
 * carrier for good.
 */
class AutomaticCarrierMigrationTest {
    private val legacy = SettingsRepository.LEGACY_CARRIER_AUTOMATIC
    private val choice = SettingsRepository.CARRIER_AUTOMATIC

    /** As saved to disk; the name is the contract, so it is spelled out here. */
    private val secondCarrier = stringPreferencesKey("second_carrier")

    @Test
    fun anOffLeftBehindByAnOldBuildIsNotAChoice() = runTest {
        val before = preferencesOf(legacy to false)

        assertTrue(AutomaticCarrierMigration.shouldMigrate(before))
        val after = AutomaticCarrierMigration.migrate(before)

        assertTrue(SettingsRepository.automaticCarrierOf(after))
        assertFalse(legacy in after)
        assertFalse("nothing was chosen, so nothing is stored", choice in after)
    }

    @Test
    fun aChainIsKeptBecauseAutomaticCannotBuildOne() = runTest {
        val before = preferencesOf(legacy to false, secondCarrier to "PSIPHON")

        val after = AutomaticCarrierMigration.migrate(before)

        assertFalse(SettingsRepository.automaticCarrierOf(after))
        assertEquals("PSIPHON", after[secondCarrier])
        assertFalse(legacy in after)
    }

    @Test
    fun anOnStaysOn() = runTest {
        val after = AutomaticCarrierMigration.migrate(preferencesOf(legacy to true))

        assertTrue(SettingsRepository.automaticCarrierOf(after))
        assertFalse(legacy in after)
    }

    @Test
    fun itRunsOnce() = runTest {
        val after = AutomaticCarrierMigration.migrate(preferencesOf(legacy to false))

        assertFalse(AutomaticCarrierMigration.shouldMigrate(after))
        assertFalse(AutomaticCarrierMigration.shouldMigrate(preferencesOf()))
    }

    @Test
    fun aChoiceAlreadyMadeIsNotOverwritten() = runTest {
        val before = preferencesOf(legacy to false, secondCarrier to "TOR", choice to true)

        val after = AutomaticCarrierMigration.migrate(before)

        assertTrue(SettingsRepository.automaticCarrierOf(after))
    }

    @Test
    fun savingSomethingElseRecordsNoChoice() {
        val stored = mutablePreferencesOf()

        // What save() does for every setting the screen holds, most of which
        // have nothing to do with this switch.
        with(SettingsRepository) { stored.recordAutomaticCarrier(true) }

        assertFalse(choice in stored)
        assertTrue(SettingsRepository.automaticCarrierOf(stored))
    }

    @Test
    fun switchingItOffStaysOffUntilItIsSwitchedBack() {
        val stored = mutablePreferencesOf()

        with(SettingsRepository) {
            stored.recordAutomaticCarrier(false)
            assertFalse(automaticCarrierOf(stored))

            // A later save of something else carries the same value along.
            stored.recordAutomaticCarrier(false)
            assertFalse(automaticCarrierOf(stored))

            stored.recordAutomaticCarrier(true)
            assertTrue(automaticCarrierOf(stored))
        }
    }
}
