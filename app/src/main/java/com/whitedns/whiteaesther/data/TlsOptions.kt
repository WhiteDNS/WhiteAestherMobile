package com.whitedns.whiteaesther.data

import com.whitedns.whiteaesther.R
import org.json.JSONObject
import java.util.Base64

enum class TlsFragmentMode(val wireName: String, val label: Int) {
    AUTOMATIC("auto", R.string.protocol_automatic),
    RECORDS("records", R.string.tls_records_preset),
    COMPACT("compact", R.string.tls_compact_preset),
    CUSTOM("custom", R.string.tls_custom),
}

/** Manual overrides use the same options as the engine's built-in recovery profiles. */
data class TlsOptions(
    val fragmentMode: TlsFragmentMode = TlsFragmentMode.AUTOMATIC,
    val fragmentSize: String = "16-32",
    val fragmentDelay: String = "2-10",
    val splitSni: Boolean = true,
    val customEch: Boolean = false,
    val echConfigList: String = "",
) {
    fun validationError(fragmentEnabled: Boolean, echEnabled: Boolean): Int? = when {
        fragmentEnabled && fragmentMode == TlsFragmentMode.CUSTOM &&
            !validRange(fragmentSize, 1, 16384) -> R.string.tls_fragment_size_error
        fragmentEnabled && fragmentMode == TlsFragmentMode.CUSTOM &&
            !validRange(fragmentDelay, 0, 100) -> R.string.tls_fragment_delay_error
        echEnabled && customEch && !validEchConfigList(echConfigList) -> R.string.tls_ech_config_error
        else -> null
    }

    fun applyTo(json: JSONObject) {
        val fragmentEnabled = json.optBoolean("fragmentTls")
        json.put("fragmentMode", if (fragmentEnabled) fragmentMode.wireName else "auto")
        json.put("helloShape", if (fragmentEnabled && fragmentMode in setOf(TlsFragmentMode.RECORDS, TlsFragmentMode.COMPACT)) fragmentMode.wireName else "")
        json.put("fragmentSize", if (fragmentEnabled && fragmentMode == TlsFragmentMode.CUSTOM) fragmentSize.trim() else "")
        json.put("fragmentDelay", if (fragmentEnabled && fragmentMode == TlsFragmentMode.CUSTOM) fragmentDelay.trim() else "")
        json.put("fragmentSni", splitSni)
        val echText = echConfigList.filterNot(Char::isWhitespace)
        val canonicalEch = runCatching { Base64.getEncoder().encodeToString(Base64.getDecoder().decode(echText)) }.getOrDefault(echText)
        json.put("echConfigList", if (json.optBoolean("encryptedHello") && customEch) canonicalEch else "")
        json.put("customEch", json.optBoolean("encryptedHello") && customEch)
    }

    fun encode(): String = JSONObject()
        .put("fragmentMode", fragmentMode.wireName).put("fragmentSize", fragmentSize)
        .put("fragmentDelay", fragmentDelay).put("splitSni", splitSni)
        .put("customEch", customEch).put("echConfigList", echConfigList).toString()

    companion object {
        fun decode(raw: String?): TlsOptions = runCatching {
            val json = JSONObject(raw ?: "{}")
            TlsOptions(
                fragmentMode = TlsFragmentMode.entries.firstOrNull { it.wireName == json.optString("fragmentMode") } ?: TlsFragmentMode.AUTOMATIC,
                fragmentSize = json.optString("fragmentSize", "16-32"),
                fragmentDelay = json.optString("fragmentDelay", "2-10"),
                splitSni = json.optBoolean("splitSni", true),
                customEch = json.optBoolean("customEch", false),
                echConfigList = json.optString("echConfigList"),
            )
        }.getOrDefault(TlsOptions())

        private fun validRange(raw: String, minimum: Int, maximum: Int): Boolean {
            val values = raw.trim().split('-')
            if (values.size !in 1..2) return false
            val low = values.first().toIntOrNull() ?: return false
            val high = values.last().toIntOrNull() ?: return false
            return low in minimum..maximum && high in low..maximum
        }

        fun validEchConfigList(raw: String): Boolean = runCatching {
            val bytes = Base64.getDecoder().decode(raw.filterNot(Char::isWhitespace))
            fun u16(offset: Int) = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
            if (bytes.size < 7 || bytes.size > 65537 || u16(0) != bytes.size - 2) return false
            var offset = 2
            while (offset < bytes.size) {
                if (offset + 4 > bytes.size) return false
                val length = u16(offset + 2)
                if (length == 0) return false
                offset += 4 + length
            }
            offset == bytes.size
        }.getOrDefault(false)
    }
}
