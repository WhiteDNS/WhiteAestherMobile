package com.whitedns.whiteaesther.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.whitedns.whiteaesther.R
import com.whitedns.whiteaesther.WorkerField
import com.whitedns.whiteaesther.data.*

@Composable
internal fun WorkerSettingsForm(deployment: WorkerDeployment, busy: Boolean, onSave: (WorkerOptions) -> Unit, onError: (String) -> Unit) {
    var expanded by remember(deployment.key) { mutableStateOf(false) }
    var draft by remember(deployment.key, deployment.options) { mutableStateOf(deployment.options) }
    var clean by remember(deployment.key, deployment.options) { mutableStateOf(draft.cleanAddresses.joinToString("\n")) }
    var ports by remember(deployment.key, deployment.options) { mutableStateOf(draft.ports.joinToString(", ")) }
    var cdn by remember(deployment.key, deployment.options) { mutableStateOf(draft.cdnAddresses.joinToString("\n")) }
    var proxy by remember(deployment.key, deployment.options, deployment.relay) { mutableStateOf((draft.proxyIps + listOf(deployment.relay).filter(String::isNotBlank)).distinct().joinToString("\n")) }
    var interval by remember(deployment.key, deployment.options) { mutableStateOf(draft.probeInterval.toString()) }
    var tlsExpanded by remember(deployment.key) { mutableStateOf(false) }
    AdvancedSection(stringResource(R.string.worker_settings), expanded, { expanded = !expanded }, tag = "worker-settings-${deployment.name}") {
        Column {
            CardHead(stringResource(R.string.proxy_protocol), "VLESS · WebSocket · TLS")
            Note(stringResource(R.string.worker_vless_only))
            WorkerMode.entries.forEach { mode ->
                OptionRow(null, stringResource(mode.label), "", draft.mode == mode, tag = "worker-mode-${mode.name.lowercase()}") {
                    if (!busy) draft = draft.copy(mode = mode)
                }
            }
            WorkerField(draft.remoteDns, { draft = draft.copy(remoteDns = it) }, R.string.worker_remote_dns, "worker-remote-dns", busy)
            if (draft.mode == WorkerMode.BROWSER) Note(stringResource(R.string.worker_browser_explanation)) else {
                WorkerField(clean, { clean = it }, R.string.worker_clean_addresses, "worker-clean-addresses", busy, multiline = true)
                WorkerField(ports, { ports = it }, R.string.worker_ports, "worker-ports", busy)
                Note(stringResource(R.string.worker_ports_summary))
                WorkerField(interval, { interval = it }, R.string.worker_probe_interval, "worker-probe-interval", busy)
                ToggleSettingRow(stringResource(R.string.worker_tcp_fast_open), "", draft.tcpFastOpen, { if (!busy) draft = draft.copy(tcpFastOpen = it) })
                AdvancedSection(stringResource(R.string.worker_fingerprint), tlsExpanded, { tlsExpanded = !tlsExpanded }) {
                    WorkerOptions.FINGERPRINTS.forEach { fingerprint ->
                        OptionRow(null, fingerprint, "", draft.fingerprint == fingerprint) { if (!busy) draft = draft.copy(fingerprint = fingerprint) }
                    }
                }
                if (draft.mode == WorkerMode.NORMAL) {
                    ToggleSettingRow(stringResource(R.string.worker_ech), "", draft.ech, { if (!busy) draft = draft.copy(ech = it) })
                    if (draft.ech) WorkerField(draft.echDns, { draft = draft.copy(echDns = it) }, R.string.worker_ech_dns, "worker-ech-dns", busy)
                    ToggleSettingRow(stringResource(R.string.worker_custom_cdn), "", draft.customCdn, { if (!busy) draft = draft.copy(customCdn = it) })
                    if (draft.customCdn) {
                        WorkerField(cdn, { cdn = it }, R.string.worker_cdn_addresses, "worker-cdn-addresses", busy, multiline = true)
                        WorkerField(draft.cdnHost, { draft = draft.copy(cdnHost = it) }, R.string.worker_cdn_host, "worker-cdn-host", busy)
                        WorkerField(draft.cdnSni, { draft = draft.copy(cdnSni = it) }, R.string.worker_cdn_sni, "worker-cdn-sni", busy)
                    }
                    WorkerField(draft.upstreamProxy, { draft = draft.copy(upstreamProxy = it) }, R.string.worker_upstream, "worker-upstream", busy)
                    Note(stringResource(R.string.worker_upstream_summary))
                }
                if (draft.mode == WorkerMode.FRAGMENT) {
                    Note(stringResource(R.string.worker_fragment_summary))
                    draft.fragments.forEachIndexed { index, fragment ->
                        fun change(value: WorkerFragment) { draft = draft.copy(fragments = draft.fragments.mapIndexed { i, old -> if (i == index) value else old }) }
                        WorkerField(fragment.packets, { change(fragment.copy(packets = it)) }, R.string.worker_fragment_packets, "worker-fragment-packets-$index", busy)
                        WorkerField(fragment.lengths, { change(fragment.copy(lengths = it)) }, R.string.worker_fragment_lengths, "worker-fragment-lengths-$index", busy)
                        WorkerField(fragment.delays, { change(fragment.copy(delays = it)) }, R.string.worker_fragment_delays, "worker-fragment-delays-$index", busy)
                        WorkerField(fragment.maxSplit, { change(fragment.copy(maxSplit = it)) }, R.string.worker_fragment_max_split, "worker-fragment-max-split-$index", busy)
                        if (draft.fragments.size > 1) OutlineButton(stringResource(R.string.worker_fragment_remove), enabled = !busy) { draft = draft.copy(fragments = draft.fragments.filterIndexed { i, _ -> i != index }) }
                    }
                    if (draft.fragments.size < 8) OutlineButton(stringResource(R.string.worker_fragment_add), enabled = !busy) { draft = draft.copy(fragments = draft.fragments + WorkerFragment()) }
                }
            }
            WorkerField(proxy, { proxy = it }, R.string.worker_proxy_ips, "worker-proxy-ips", busy, multiline = true)
            WorkerField(draft.fallbackUrl, { draft = draft.copy(fallbackUrl = it) }, R.string.worker_fallback, "worker-fallback", busy)
            Note(stringResource(R.string.worker_save_summary))
            PrimaryButton(stringResource(R.string.worker_save_settings), Modifier.fillMaxWidth(), enabled = !busy) {
                fun entries(text: String) = text.split(Regex("[\\s,;]+")).filter(String::isNotBlank)
                runCatching {
                    draft.copy(cleanAddresses = entries(clean), ports = entries(ports).map(String::toInt),
                        cdnAddresses = entries(cdn), proxyIps = entries(proxy), probeInterval = interval.toInt()).also { it.validate() }
                }.onSuccess(onSave).onFailure { onError(it.message ?: "Invalid Worker settings") }
            }
        }
    }
}
