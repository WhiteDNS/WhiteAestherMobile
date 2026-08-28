package com.whitedns.whiteaesther.ui

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvUiPolicyTest {
    @Test
    fun televisionModeIsDetectedWithoutTreatingLandscapeAsTv() {
        assertTrue(TvUiPolicy.isTelevision(Configuration.UI_MODE_TYPE_TELEVISION))
        assertFalse(TvUiPolicy.isTelevision(Configuration.UI_MODE_TYPE_NORMAL))
        assertFalse(TvUiPolicy.isTelevision(Configuration.UI_MODE_TYPE_DESK))
    }
}
