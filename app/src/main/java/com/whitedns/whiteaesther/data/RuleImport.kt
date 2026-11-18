package com.whitedns.whiteaesther.data

/** What an imported list does with the destinations it names. */
enum class RuleAction(val wireName: String) { BLOCK("block"), DIRECT("direct") }

/** Why a line of an imported list was left out. Counted, never hidden. */
enum class IgnoredBecause {
    /** A rule type this app has no equivalent for. */
    UNSUPPORTED,

    /** geosite and geoip other than `private` need a database this build does not ship. */
    NEEDS_DATABASE,

    /**
     * Wide enough to take everything, or the ports everything uses. In a
     * direct list it would send the whole phone around the tunnel; in a block
     * list it would cut the connection off.
     */
    TOO_BROAD,
    DUPLICATE,
    OVER_LIMIT,
}

class RuleImportReport(val accepted: List<String>, val ignored: Map<IgnoredBecause, Int>) {
    val ignoredTotal: Int get() = ignored.values.sum()
}

/**
 * Turns another client's rule list into this app's grammar (see
 * AppSettings.routeBlock): plain names, hosts-file lines, addresses and CIDRs,
 * and Clash classical lines, alone or in a rule-provider payload. Nothing is
 * fetched and nothing is guessed: a line this cannot translate is counted.
 */
object RuleImport {
    // shortcut: rules are rendered inline into both engines' configs; a list past
    // this belongs in a mihomo rule-provider file, the planned second phase.
    const val MAX_ENTRIES = 5_000

    private val DOMAIN = Regex("^(?=.{1,253}$)([a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?\\.)*[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val HOSTS_SINK = setOf("0.0.0.0", "127.0.0.1", "::", "::1")
    private val ESSENTIAL_PORTS = setOf(53, 80, 443)
    private val SAFE_REGEX = Regex("^[A-Za-z0-9.^$*+?()\\[\\]|\\\\-]+$")

    fun isDomain(host: String): Boolean = DOMAIN.matches(host)

    fun parse(text: String): RuleImportReport {
        val accepted = LinkedHashSet<String>()
        val ignored = sortedMapOf<IgnoredBecause, Int>()
        fun ignore(reason: IgnoredBecause) { ignored[reason] = (ignored[reason] ?: 0) + 1 }
        text.lineSequence().forEach { raw ->
            val line = clean(raw) ?: return@forEach
            when (val result = translate(line)) {
                is Translation.Rule -> when {
                    result.rule in accepted -> ignore(IgnoredBecause.DUPLICATE)
                    accepted.size >= MAX_ENTRIES -> ignore(IgnoredBecause.OVER_LIMIT)
                    else -> accepted += result.rule
                }
                is Translation.Ignored -> ignore(result.reason)
            }
        }
        return RuleImportReport(accepted.toList(), ignored)
    }

    /** A rule line without list markup, or null for blanks, notes and headers. */
    private fun clean(raw: String): String? {
        var line = raw.substringBefore(" #").trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || line.startsWith("!")) return null
        if (line.endsWith(":") && !line.contains(',')) return null // `payload:` and other YAML keys
        if (line.startsWith("- ")) line = line.removePrefix("- ").trim()
        line = line.removeSurrounding("'").removeSurrounding("\"").trim()
        return line.ifEmpty { null }
    }

    private sealed interface Translation {
        data class Rule(val rule: String) : Translation
        data class Ignored(val reason: IgnoredBecause) : Translation
    }

    private fun rule(value: String) = Translation.Rule(value)
    private fun ignored(reason: IgnoredBecause) = Translation.Ignored(reason)

    private fun translate(line: String): Translation {
        // A hosts file: "0.0.0.0 ads.example.com".
        val words = line.split(Regex("\\s+"))
        if (words.size >= 2 && words[0] in HOSTS_SINK) return name(words[1])
        if (line.contains(',')) return classical(line.split(',').map(String::trim))
        address(line)?.let { return it }
        val kind = line.substringBefore(':', "").lowercase()
        val value = line.substringAfter(':', "").trim()
        return when (kind) {
            "domain", "suffix" -> name(value)
            "full", "exact" -> exact(value)
            "keyword" -> keyword(value)
            "regexp", "regex" -> regex(value)
            "ip", "cidr" -> address(value) ?: ignored(IgnoredBecause.UNSUPPORTED)
            "port" -> port(value)
            "geosite", "geoip" -> geo(value)
            "" -> if (line.equals("private", true)) rule("private") else name(line)
            else -> ignored(IgnoredBecause.UNSUPPORTED)
        }
    }

    private fun classical(fields: List<String>): Translation {
        val value = fields.getOrNull(1).orEmpty()
        return when (fields[0].uppercase()) {
            "DOMAIN-SUFFIX" -> name(value)
            "DOMAIN" -> exact(value)
            "DOMAIN-KEYWORD" -> keyword(value)
            "DOMAIN-REGEX" -> regex(value)
            "IP-CIDR", "IP-CIDR6" -> address(value) ?: ignored(IgnoredBecause.UNSUPPORTED)
            "DST-PORT" -> port(value)
            "GEOSITE", "GEOIP" -> geo(value)
            else -> ignored(IgnoredBecause.UNSUPPORTED)
        }
    }

    private fun name(value: String): Translation {
        val host = value.lowercase().trimEnd('.').removePrefix("+.").removePrefix("*.").removePrefix(".")
        return if (DOMAIN.matches(host)) rule(host) else ignored(IgnoredBecause.UNSUPPORTED)
    }

    private fun exact(value: String): Translation {
        val host = value.lowercase().trimEnd('.')
        return if (DOMAIN.matches(host)) rule("full:$host") else ignored(IgnoredBecause.UNSUPPORTED)
    }

    private fun keyword(value: String): Translation = when {
        value.isBlank() || value.contains(' ') -> ignored(IgnoredBecause.UNSUPPORTED)
        // Two letters is inside half the names on the internet.
        value.length < 3 -> ignored(IgnoredBecause.TOO_BROAD)
        else -> rule("keyword:${value.lowercase()}")
    }

    private fun regex(value: String): Translation {
        // Both engines split a list on `,` and `;`, and mihomo's rules are unquoted
        // YAML, so only patterns that survive both are taken.
        if (!SAFE_REGEX.matches(value)) return ignored(IgnoredBecause.UNSUPPORTED)
        val pattern = runCatching { Regex(value) }.getOrNull() ?: return ignored(IgnoredBecause.UNSUPPORTED)
        // A pattern that takes two unrelated names takes anything.
        if (pattern.containsMatchIn("zq7.example") && pattern.containsMatchIn("www.wikipedia.org")) {
            return ignored(IgnoredBecause.TOO_BROAD)
        }
        return rule("regexp:$value")
    }

    private fun port(value: String): Translation {
        val (low, high) = value.split('-').map { it.trim().toIntOrNull() }.let {
            when (it.size) {
                1 -> it[0] to it[0]
                2 -> it[0] to it[1]
                else -> null to null
            }
        }
        if (low == null || high == null || low !in 1..65535 || high !in low..65535) return ignored(IgnoredBecause.UNSUPPORTED)
        if (ESSENTIAL_PORTS.any { it in low..high }) return ignored(IgnoredBecause.TOO_BROAD)
        return rule("port:${if (low == high) "$low" else "$low-$high"}")
    }

    private fun geo(value: String): Translation =
        if (value.equals("private", true) || value.equals("lan", true)) rule("private")
        else ignored(IgnoredBecause.NEEDS_DATABASE)

    /** An address or a CIDR as `cidr:`, or null for anything that is not one. */
    private fun address(value: String): Translation? {
        val host = value.substringBefore('/')
        val v4 = IpCidr.ipv4(host)
        val v6 = if (v4 == null) IpCidr.ipv6(host) else null
        if (v4 == null && v6 == null) return null
        val bits = if (v4 != null) 32 else 128
        val prefix = if (value.contains('/')) value.substringAfter('/').toIntOrNull() else bits
        if (prefix == null || prefix !in 0..bits) return ignored(IgnoredBecause.UNSUPPORTED)
        // Shorter than a /8 or a /16 is a continent, not a service.
        if (prefix < if (v4 != null) 8 else 16) return ignored(IgnoredBecause.TOO_BROAD)
        return rule("cidr:$host/$prefix")
    }
}

/** Literal address parsing that never touches a resolver. */
internal object IpCidr {
    fun ipv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        return ByteArray(4) { i ->
            val n = parts[i].toIntOrNull()?.takeIf { parts[i].length in 1..3 && it in 0..255 } ?: return null
            n.toByte()
        }
    }

    fun ipv6(text: String): ByteArray? {
        if (!text.contains(':') || text.any { !(it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) return null
        // Only literals reach this point, so the platform parser cannot resolve anything.
        return runCatching { java.net.InetAddress.getByName(text) }.getOrNull()
            ?.takeIf { it is java.net.Inet6Address }?.address
    }

    fun contains(network: ByteArray, prefix: Int, address: ByteArray): Boolean {
        if (network.size != address.size) return false
        var bits = prefix
        for (i in network.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((network[i].toInt() and mask) != (address[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }
}
