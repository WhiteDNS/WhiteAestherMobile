package com.whitedns.whiteaesther.ui

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

/** Settings > Advanced > [page], where [page] is an `AdvancedGroup` name in lower case. */
internal fun SemanticsNodeInteractionsProvider.openAdvanced(page: String) {
    onNodeWithTag("tab-settings").performClick()
    onNodeWithTag("settings-advanced").performScrollTo().performClick()
    onNodeWithTag("advanced-$page").performScrollTo().performClick()
}
