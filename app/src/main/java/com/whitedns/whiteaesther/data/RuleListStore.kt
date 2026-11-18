package com.whitedns.whiteaesther.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** One imported list as the user sees it. Its rules live in a file beside the index. */
data class RuleList(
    val id: String,
    val name: String,
    val action: RuleAction,
    val importedAt: Long,
    val count: Int,
    val enabled: Boolean,
)

/**
 * Imported rule lists, as files in app-private storage.
 *
 * Not DataStore: a list can be thousands of lines, and settings travel to the
 * VPN service inside an Intent, where a large string is a crash. The service
 * reads the enabled lists from here itself, through [mergeInto] and
 * [withImported]; settings carry only [signature], so a change still
 * reconnects.
 */
class RuleListStore(private val directory: File) {
    private val index: File get() = File(directory, "index.json")

    private fun rules(id: String) = File(directory, "$id.txt")

    @Synchronized
    fun lists(): List<RuleList> = runCatching {
        val array = JSONArray(index.readText())
        (0 until array.length()).mapNotNull { i ->
            val json = array.getJSONObject(i)
            val action = RuleAction.entries.firstOrNull { it.wireName == json.optString("action") } ?: return@mapNotNull null
            RuleList(
                id = json.getString("id"),
                name = json.optString("name"),
                action = action,
                importedAt = json.optLong("importedAt"),
                count = json.optInt("count"),
                enabled = json.optBoolean("enabled", true),
            ).takeIf { rules(it.id).isFile }
        }
    }.getOrDefault(emptyList())

    /**
     * Saves [entries] as a new enabled list, or refuses when that would take
     * the enabled total past [MAX_TOTAL].
     */
    @Synchronized
    fun add(name: String, action: RuleAction, entries: List<String>, now: Long = System.currentTimeMillis()): RuleList? {
        if (entries.isEmpty() || enabledTotal() + entries.size > MAX_TOTAL) return null
        directory.mkdirs()
        val list = RuleList(UUID.randomUUID().toString(), name.take(80), action, now, entries.size, true)
        writeAtomically(rules(list.id), entries.joinToString("\n"))
        save(lists() + list)
        return list
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val all = lists()
        val target = all.firstOrNull { it.id == id } ?: return false
        if (enabled && !target.enabled && enabledTotal() + target.count > MAX_TOTAL) return false
        save(all.map { if (it.id == id) it.copy(enabled = enabled) else it })
        return true
    }

    @Synchronized
    fun delete(id: String) {
        save(lists().filterNot { it.id == id })
        rules(id).delete()
    }

    fun enabledTotal(): Int = lists().filter { it.enabled }.sumOf { it.count }

    /** Every enabled rule for [action], one per line. */
    @Synchronized
    fun active(action: RuleAction): String = lists()
        .filter { it.enabled && it.action == action }
        .mapNotNull { runCatching { rules(it.id).readText() }.getOrNull() }
        .joinToString("\n")

    /** Changes whenever what the engines would be given changes. */
    fun signature(): String {
        val material = lists().filter { it.enabled }.joinToString("|") { "${it.id}:${it.action.wireName}:${it.count}" }
        if (material.isEmpty()) return ""
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
    }

    /** The engine config [configJson] with the enabled lists after the user's own rules. */
    fun mergeInto(configJson: String): String = runCatching {
        val block = active(RuleAction.BLOCK)
        val direct = active(RuleAction.DIRECT)
        if (block.isEmpty() && direct.isEmpty()) return configJson
        val json = JSONObject(configJson)
        json.put("routeBlock", join(json.optString("routeBlock"), block))
        json.put("routeDirect", join(json.optString("routeDirect"), direct))
        json.toString()
    }.getOrDefault(configJson)

    fun withImported(settings: ChainSettings): ChainSettings {
        val block = active(RuleAction.BLOCK)
        val direct = active(RuleAction.DIRECT)
        if (block.isEmpty() && direct.isEmpty()) return settings
        return settings.copy(routeBlock = join(settings.routeBlock, block), routeDirect = join(settings.routeDirect, direct))
    }

    private fun save(lists: List<RuleList>) {
        directory.mkdirs()
        val array = JSONArray()
        lists.forEach {
            array.put(
                JSONObject().put("id", it.id).put("name", it.name).put("action", it.action.wireName)
                    .put("importedAt", it.importedAt).put("count", it.count).put("enabled", it.enabled),
            )
        }
        writeAtomically(index, array.toString())
    }

    private fun writeAtomically(target: File, text: String) {
        val partial = File(target.parentFile, target.name + ".part")
        partial.writeText(text)
        if (!partial.renameTo(target)) {
            target.delete()
            check(partial.renameTo(target)) { "could not save ${target.name}" }
        }
    }

    companion object {
        // shortcut: inline rules; past this, mihomo rule-provider files are the upgrade.
        const val MAX_TOTAL = 10_000

        fun of(context: Context) = RuleListStore(File(context.applicationContext.filesDir, "rule-lists"))

        private fun join(own: String, imported: String) =
            listOf(own, imported).filter(String::isNotBlank).joinToString("\n")
    }
}

/** Which rule a destination meets, worked out here from the rules alone. */
data class RuleMatch(val action: RuleAction?, val rule: String?)

object RuleMatcher {
    private val PRIVATE = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "127.0.0.0/8",
        "100.64.0.0/10", "fc00::/7", "fe80::/10", "::1/128",
    )

    /**
     * Block first, then direct, as both engines order them. A null action is
     * the tunnel. Port rules need a port, which a name alone does not have, so
     * they never match here.
     */
    fun match(destination: String, block: String, direct: String): RuleMatch {
        val target = RouteRule.hostOfDestination(destination) ?: return RuleMatch(null, null)
        lines(block).firstOrNull { matches(it, target) }?.let { return RuleMatch(RuleAction.BLOCK, it) }
        lines(direct).firstOrNull { matches(it, target) }?.let { return RuleMatch(RuleAction.DIRECT, it) }
        return RuleMatch(null, null)
    }

    private fun lines(raw: String) = RouteRule.entries(raw)

    /** [rule] as the user wrote it, matched through its canonical form so the answer is the engines' answer. */
    private fun matches(rule: String, target: String): Boolean {
        val canonical = RouteRule.canonical(rule) ?: return false
        if (canonical == "private") return isPrivate(target, IpCidr.ipv4(target) ?: IpCidr.ipv6(target))
        val kind = canonical.substringBefore(':')
        val value = canonical.substringAfter(':')
        val address = IpCidr.ipv4(target) ?: IpCidr.ipv6(target)
        return when (kind) {
            "domain" -> address == null && suffix(value, target)
            "full" -> address == null && target == value
            "keyword" -> address == null && target.contains(value)
            "regexp" -> address == null && runCatching { Regex(value).containsMatchIn(target) }.getOrDefault(false)
            "cidr" -> address != null && inNet(value, address)
            else -> false // port: a name alone has no port
        }
    }

    private fun suffix(value: String, target: String) =
        value.isNotEmpty() && (target == value || target.endsWith(".$value"))

    private fun isPrivate(target: String, address: ByteArray?) =
        target == "localhost" || (address != null && PRIVATE.any { inNet(it, address) })

    private fun inNet(cidr: String, address: ByteArray): Boolean {
        val host = cidr.substringBefore('/')
        val network = IpCidr.ipv4(host) ?: IpCidr.ipv6(host) ?: return false
        val prefix = if (cidr.contains('/')) cidr.substringAfter('/').toIntOrNull() ?: return false else network.size * 8
        return IpCidr.contains(network, prefix, address)
    }
}
