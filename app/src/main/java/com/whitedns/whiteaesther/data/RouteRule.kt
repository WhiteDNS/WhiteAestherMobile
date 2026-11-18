package com.whitedns.whiteaesther.data

/**
 * The routing-rule grammar, in one place.
 *
 * The engine, mihomo and the rule lookup each used to read a rule on their own,
 * and they disagreed: `*.example.com` and a bare `1.2.3.4` worked in the engine
 * and silently never matched in mihomo, and a pasted `https://site/` or a bare
 * IPv6 address was dropped by all three while the screen still counted it. Every
 * rule now passes through [canonical] first, and each consumer only has to
 * understand the canonical forms:
 *
 * `domain:<name>`, `full:<name>`, `keyword:<text>`, `regexp:<pattern>`,
 * `cidr:<address>/<prefix>`, `port:<low>-<high>` and `private`.
 */
object RouteRule {
    private val KINDS = setOf(
        "domain", "suffix", "full", "exact", "keyword", "regexp", "regex",
        "ip", "cidr", "port", "geoip", "geosite",
    )

    /** The entries of a rule list, without blanks and `#` notes. Separators are newlines, `,` and `;`. */
    fun entries(raw: String): List<String> =
        raw.split('\n', ',', ';').map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }

    /** [raw] rewritten in canonical form, one rule per line, with what cannot be read left out. */
    fun normalize(raw: String): String = entries(raw).mapNotNull(::canonical).distinct().joinToString("\n")

    /** The entries of [raw] that are not rules, as the user wrote them. */
    fun invalid(raw: String): List<String> = entries(raw).filter { canonical(it) == null }

    /** How many entries of [raw] are rules. */
    fun count(raw: String): Int = entries(raw).count { canonical(it) != null }

    /** One entry in canonical form, or null when it is not a rule. */
    fun canonical(entry: String): String? {
        val text = entry.trim()
        if (text.isEmpty() || text.startsWith("#")) return null
        val prefix = text.substringBefore(':', "").trim().lowercase()
        if (prefix !in KINDS) return bare(text)
        val value = text.substringAfter(':').trim()
        if (value.isEmpty()) return null
        return when (prefix) {
            "domain", "suffix" -> name(hostOf(value))?.let { "domain:$it" }
            "full", "exact" -> name(hostOf(value))?.let { "full:$it" }
            "keyword" -> value.lowercase().takeIf { it.none(Char::isWhitespace) }?.let { "keyword:$it" }
            "regexp", "regex" -> value.takeIf { pattern ->
                pattern.none(Char::isWhitespace) && runCatching { Regex(pattern) }.isSuccess
            }?.let { "regexp:$it" }
            "ip", "cidr" -> network(value)?.let { "cidr:$it" }
            "port" -> ports(value)?.let { "port:$it" }
            else -> if (value.equals("private", true) || value.equals("lan", true)) "private" else null
        }
    }

    /**
     * The host a destination names, for the lookup: a name in canonical case, or
     * an address. Takes what people paste -- a URL, a name with a port.
     */
    fun hostOfDestination(text: String): String? {
        val host = hostOf(text.trim())
        if (host.isEmpty()) return null
        if (IpCidr.ipv4(host) != null || IpCidr.ipv6(host) != null) return host.lowercase()
        return name(host)
    }

    /** An entry with no kind: `private`, an address or network, a URL, or a name. */
    private fun bare(text: String): String? {
        if (text.equals("private", true)) return "private"
        if (!text.contains("://")) network(text)?.let { return "cidr:$it" }
        val host = hostOf(text)
        network(host)?.let { return "cidr:$it" }
        return name(host)?.let { "domain:$it" }
    }

    /** The host part of a URL or of `host:port`; anything else unchanged. */
    private fun hostOf(value: String): String {
        var host = value.trim()
        if (host.contains("://")) host = host.substringAfter("://")
        host = host.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        if (host.startsWith("[")) return host.substringAfter('[').substringBefore(']')
        // One colon is a port; more than one is an IPv6 address.
        if (host.count { it == ':' } == 1 && host.substringAfter(':').all(Char::isDigit)) host = host.substringBefore(':')
        return host
    }

    private fun name(value: String): String? {
        val trimmed = value.trim().lowercase().trimEnd('.')
            .removePrefix("*.").removePrefix("+.").removePrefix(".")
        if (trimmed.isEmpty()) return null
        val ascii = runCatching { java.net.IDN.toASCII(trimmed) }.getOrNull()?.lowercase() ?: return null
        return ascii.takeIf { RuleImport.isDomain(it) }
    }

    /** `address/prefix` with the prefix spelled out, or null for anything that is not one. */
    private fun network(value: String): String? {
        val text = value.trim().removePrefix("[").replace("]", "")
        val host = text.substringBefore('/')
        val bits = when {
            IpCidr.ipv4(host) != null -> 32
            IpCidr.ipv6(host) != null -> 128
            else -> return null
        }
        val prefix = if (text.contains('/')) text.substringAfter('/').toIntOrNull() ?: return null else bits
        if (prefix !in 0..bits) return null
        return "${host.lowercase()}/$prefix"
    }

    private fun ports(value: String): String? {
        val parts = value.split('-').map { it.trim().toIntOrNull() ?: return null }
        val (low, high) = when (parts.size) {
            1 -> parts[0] to parts[0]
            2 -> minOf(parts[0], parts[1]) to maxOf(parts[0], parts[1])
            else -> return null
        }
        if (low !in 1..65535 || high !in 1..65535) return null
        return if (low == high) "$low" else "$low-$high"
    }
}
