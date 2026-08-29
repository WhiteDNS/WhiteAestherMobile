package com.whitedns.whiteaesther.ui

import android.content.res.Configuration
import androidx.compose.ui.unit.dp

/** The small set of presentation decisions that differ on a television. */
internal object TvUiPolicy {
    val safeHorizontalInset = 48.dp
    val safeVerticalInset = 27.dp
    val maxShellWidth = 1_120.dp

    fun isTelevision(uiMode: Int): Boolean =
        uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
}
