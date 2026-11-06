package com.whitedns.whiteaesther.data

import org.json.JSONArray

enum class RoutingPreset(val block: String = "", val direct: String = "") {
    IRANIAN_DOMAINS(direct = "domain:ir"),
    LOCAL_NETWORK(direct = "private"),
    COMMON_ADS(block = """
        doubleclick.net
        googlesyndication.com
        googleadservices.com
        google-analytics.com
        app-measurement.com
        adservice.google.com
        ads-twitter.com
        ads.yahoo.com
        adsrvr.org
        adroll.com
        criteo.com
        scorecardresearch.com
        taboola.com
        outbrain.com
    """.trimIndent()),
    ;

    companion object {
        fun encode(presets: Set<RoutingPreset>): String =
            JSONArray(entries.filter { it in presets }.map { it.name }).toString()

        fun decode(raw: String?): Set<RoutingPreset> = runCatching {
            val array = JSONArray(raw ?: "[]")
            entries.filter { preset -> (0 until array.length()).any { array.optString(it) == preset.name } }.toSet()
        }.getOrDefault(emptySet())
    }
}
