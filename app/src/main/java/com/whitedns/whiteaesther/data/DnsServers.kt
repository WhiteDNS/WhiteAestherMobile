package com.whitedns.whiteaesther.data

/**
 * The resolvers the user typed, as addresses the interface can be given.
 *
 * The field is free text and its own description promises that "entries that
 * are not an address are ignored", so this filters rather than refuses: a
 * mistyped entry costs that entry and nothing else.
 *
 * Validation is done here rather than by handing the string to the platform,
 * because `InetAddress.getByName` resolves anything that is not a literal --
 * a typed hostname would become a DNS lookup on the thread bringing the tunnel
 * up, on a network where DNS is the thing that does not work.
 */
object DnsServers {
    /**
     * More than this many is a mistake rather than a preference.
     *
     * Android asks every resolver on the interface in turn, so a long list is a
     * long wait for the name at the end of it.
     */
    private const val LIMIT = 4

    fun parse(entered: String): List<String> =
        entered.split(',', ' ', ';', '\n')
            .map { it.trim() }
            .filter { isAddress(it) }
            .distinct()
            .take(LIMIT)

    /** Whether [value] is an IPv4 or IPv6 literal, without asking the network. */
    fun isAddress(value: String): Boolean =
        isIpv4(value) || isIpv6(value)

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            // "01" and "1e2" are rejected: a leading zero means octal to some
            // parsers and decimal to others, and an interface should never be
            // given an address whose meaning depends on who reads it.
            part.isNotEmpty() &&
                part.length <= 3 &&
                part.all { it in '0'..'9' } &&
                (part.length == 1 || part[0] != '0') &&
                part.toInt() <= 255
        }
    }

    private fun isIpv6(value: String): Boolean {
        // No zone index: a scope like %wlan0 belongs to the interface it names,
        // and this one is about to be created.
        if (value.isEmpty() || '%' in value || '.' in value) return false
        val groups = value.split(':')
        if (groups.size < 3 || groups.size > 9) return false
        val elisions = Regex("::").findAll(value).count()
        if (elisions > 1) return false
        if (elisions == 0 && groups.size != 8) return false
        // An elision splits into at most two empty strings -- "::1" gives two,
        // "fe80::1" gives one. Three means a third colon that belongs to
        // nothing, as in ":::1", which reads as an address and is not one.
        if (groups.count { it.isEmpty() } > if (elisions == 1) 2 else 0) return false
        return groups.all { group ->
            group.isEmpty() || (group.length <= 4 && group.all { it.isHex() })
        }
    }

    private fun Char.isHex(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
