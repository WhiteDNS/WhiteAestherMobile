package com.whitedns.whiteaesther.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.whitedns.whiteaesther.WorkerSetupContent
import com.whitedns.whiteaesther.ui.theme.WhiteAestherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class WorkerSetupScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun openingAndEditingTokenDoesNotPersistItOrStartAnyOperation() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val storage = application.getSharedPreferences("worker_deployments", Context.MODE_PRIVATE)
        val before = storage.all.toMap()
        val model = WorkerSetupModel(application)
        compose.setContent { WhiteAestherTheme { WorkerSetupContent(model, {}, {}) } }
        compose.onNodeWithTag("worker-token").performTextInput("test_token_0123456789012345678901234567890")
        compose.runOnIdle {
            assertFalse(model.busy)
            assertEquals("test_token_0123456789012345678901234567890", model.token)
            assertEquals(before, storage.all)
            assertEquals("", WorkerSetupModel(application).token)
        }
    }
}
