package com.whitedns.whiteaesther.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.whitedns.whiteaesther.R
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.Coverage
import com.whitedns.whiteaesther.data.RuleCounts

/**
 * The one-line summaries, in whatever language the app is set to.
 *
 * Separated from the settings that produce them because only one half of a
 * summary is the same in every language. What is counted, and which case a
 * setup falls into, belong with the data; how that reads belongs here, where
 * there is a resource table to read it from.
 *
 * Keeping them together meant the tests asserted on English sentences, which
 * made them tests of the wording rather than of the logic underneath.
 */
@Composable
fun AppSettings.coverageSummary(): String = when (val coverage = coverage()) {
    Coverage.ProxyOnly -> stringResource(R.string.coverage_proxy_only)
    Coverage.WholeDevice -> stringResource(R.string.coverage_whole_device)
    Coverage.NothingChosen -> stringResource(R.string.coverage_nothing_chosen)
    is Coverage.OnlySome -> stringResource(R.string.coverage_only_some, coverage.count)
    is Coverage.AllExcept -> stringResource(R.string.coverage_all_except, coverage.count)
}

@Composable
fun AppSettings.routingSummary(): String {
    val counts: RuleCounts = routingCounts()
    return when {
        counts.blocked == 0 && counts.direct == 0 ->
            stringResource(R.string.routing_everything_through)
        counts.direct == 0 -> stringResource(R.string.routing_blocked_only, counts.blocked)
        counts.blocked == 0 -> stringResource(R.string.routing_direct_only, counts.direct)
        else -> stringResource(R.string.routing_both, counts.blocked, counts.direct)
    }
}
