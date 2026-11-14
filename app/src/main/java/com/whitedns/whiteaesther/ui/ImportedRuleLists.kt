package com.whitedns.whiteaesther.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.whitedns.whiteaesther.R
import com.whitedns.whiteaesther.ui.theme.AetherTheme
import com.whitedns.whiteaesther.data.AppSettings
import com.whitedns.whiteaesther.data.IgnoredBecause
import com.whitedns.whiteaesther.data.RuleAction
import com.whitedns.whiteaesther.data.RuleImport
import com.whitedns.whiteaesther.data.RuleListStore
import com.whitedns.whiteaesther.data.RuleMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the last import did, in the counts it is reported by. */
private sealed interface ImportOutcome {
    data class Added(val name: String, val count: Int, val ignored: Map<IgnoredBecause, Int>) : ImportOutcome
    data class NothingUsable(val name: String, val ignored: Map<IgnoredBecause, Int>) : ImportOutcome
    data object TooMany : ImportOutcome
    data object Unreadable : ImportOutcome
}

// Two megabytes is far past MAX_TOTAL rules; anything larger is not a rule list.
private const val MAX_FILE_BYTES = 2 * 1024 * 1024

/**
 * Imported lists: from a file the user picks, never from the network. Each is
 * on or off on its own, and the import says what it left out and why.
 */
@Composable
internal fun ImportedRuleLists(settings: AppSettings, onSettingsChange: (AppSettings) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { RuleListStore.of(context) }
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf(store.lists()) }
    var outcome by remember { mutableStateOf<ImportOutcome?>(null) }
    var pendingAction by remember { mutableStateOf(RuleAction.BLOCK) }

    fun changed() {
        lists = store.lists()
        onSettingsChange(settings.copy(importedRulesVersion = store.signature()))
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val action = pendingAction
        scope.launch {
            outcome = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val name = runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull() ?: "rules"
                val text = runCatching {
                    resolver.openInputStream(uri)?.use { input ->
                        // Bounded by hand: readNBytes needs API 33 and this app runs from 26.
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (out.size() <= MAX_FILE_BYTES) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                        if (out.size() > MAX_FILE_BYTES) null else out.toString(Charsets.UTF_8.name())
                    }
                }.getOrNull() ?: return@withContext ImportOutcome.Unreadable
                val report = RuleImport.parse(text)
                when {
                    report.accepted.isEmpty() -> ImportOutcome.NothingUsable(name, report.ignored)
                    store.add(name, action, report.accepted) == null -> ImportOutcome.TooMany
                    else -> ImportOutcome.Added(name, report.accepted.size, report.ignored)
                }
            }
            changed()
        }
    }

    AetherCard {
        CardHead(stringResource(R.string.imported_lists), stringResource(R.string.imported_lists_summary))
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleAction.entries.forEach { action ->
                    OutlineButton(
                        text = stringResource(if (action == RuleAction.BLOCK) R.string.import_block_list else R.string.import_direct_list),
                        modifier = Modifier.weight(1f).testTag("import-${action.wireName}-list"),
                    ) {
                        pendingAction = action
                        picker.launch(arrayOf("text/*", "application/x-yaml", "application/yaml", "application/octet-stream"))
                    }
                }
            }
            outcome?.let { ImportReport(it) }
            lists.forEach { list ->
                ToggleSettingRow(
                    title = list.name,
                    subtitle = stringResource(
                        R.string.imported_list_line,
                        list.count,
                        stringResource(if (list.action == RuleAction.BLOCK) R.string.never_connect else R.string.skip_the_tunnel),
                    ),
                    checked = list.enabled,
                    onCheckedChange = { enabled ->
                        if (!store.setEnabled(list.id, enabled)) outcome = ImportOutcome.TooMany
                        changed()
                    },
                    modifier = Modifier.testTag("imported-list-${list.id}"),
                )
                OutlineButton(stringResource(R.string.delete_imported_list, list.name)) {
                    store.delete(list.id)
                    changed()
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    RuleLookup(settings, store)
}

@Composable
private fun ImportReport(outcome: ImportOutcome) {
    val lines = buildList {
        when (outcome) {
            is ImportOutcome.Added -> {
                add(stringResource(R.string.rule_import_added, outcome.count, outcome.name))
                addAll(reasons(outcome.ignored))
            }
            is ImportOutcome.NothingUsable -> {
                add(stringResource(R.string.rule_import_nothing_usable, outcome.name))
                addAll(reasons(outcome.ignored))
            }
            ImportOutcome.TooMany -> add(stringResource(R.string.rule_import_too_many, RuleListStore.MAX_TOTAL))
            ImportOutcome.Unreadable -> add(stringResource(R.string.rule_import_unreadable))
        }
    }
    Text(lines.joinToString("\n"), style = AetherTheme.type.Small, color = AetherTheme.colors.text2, modifier = Modifier.testTag("rule-import-report"))
}

@Composable
private fun reasons(ignored: Map<IgnoredBecause, Int>): List<String> = ignored.filterValues { it > 0 }.map { (reason, count) ->
    stringResource(
        when (reason) {
            IgnoredBecause.UNSUPPORTED -> R.string.rule_ignored_unsupported
            IgnoredBecause.NEEDS_DATABASE -> R.string.rule_ignored_database
            IgnoredBecause.TOO_BROAD -> R.string.rule_ignored_too_broad
            IgnoredBecause.DUPLICATE -> R.string.rule_ignored_duplicate
            IgnoredBecause.OVER_LIMIT -> R.string.rule_ignored_over_limit
        },
        count,
    )
}

/** "Which rule does this destination meet?", worked out here from the rules alone. */
@Composable
private fun RuleLookup(settings: AppSettings, store: RuleListStore) {
    var query by remember { mutableStateOf("") }
    val block = remember(settings.importedRulesVersion, settings.effectiveRouteBlock()) {
        listOf(settings.effectiveRouteBlock(), store.active(RuleAction.BLOCK)).joinToString("\n")
    }
    val direct = remember(settings.importedRulesVersion, settings.effectiveRouteDirect()) {
        listOf(settings.effectiveRouteDirect(), store.active(RuleAction.DIRECT)).joinToString("\n")
    }
    AetherCard {
        CardHead(stringResource(R.string.rule_lookup), stringResource(R.string.rule_lookup_summary))
        Column(Modifier.padding(11.dp)) {
            PlainField(query, "rule-lookup-field") { query = it.take(253) }
            if (query.isNotBlank()) {
                val match = RuleMatcher.match(query, block, direct)
                Spacer(Modifier.height(8.dp))
                Text(
                    when (match.action) {
                        RuleAction.BLOCK -> stringResource(R.string.rule_lookup_blocked, match.rule.orEmpty())
                        RuleAction.DIRECT -> stringResource(R.string.rule_lookup_direct, match.rule.orEmpty())
                        null -> stringResource(R.string.rule_lookup_tunnel)
                    },
                    style = AetherTheme.type.Small,
                    color = AetherTheme.colors.text2,
                    modifier = Modifier.testTag("rule-lookup-result"),
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}
