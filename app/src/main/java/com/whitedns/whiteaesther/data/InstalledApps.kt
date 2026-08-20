package com.whitedns.whiteaesther.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * An app the user could plausibly want routed, or kept out of the tunnel.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    /** Shipped with the device rather than installed by the user. */
    val system: Boolean,
)

object InstalledApps {
    /**
     * Everything with a launcher entry, by name.
     *
     * Deliberately not every installed package. `QUERY_ALL_PACKAGES` would
     * return several hundred services and providers on a normal phone, is a
     * restricted permission on Play, and none of it is anything the user
     * recognises or would knowingly route. A launcher entry is a good proxy for
     * "an app the user thinks of as an app" -- on a test device here that is 38
     * entries against 248 packages.
     *
     * Sorted by name, user-installed first: the apps somebody wants to exclude
     * are almost always ones they installed themselves.
     */
    fun launchable(context: Context): List<InstalledApp> {
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        return runCatching {
            manager.queryIntentActivities(intent, 0)
                .asSequence()
                .mapNotNull { it.activityInfo?.applicationInfo }
                // A package can contribute more than one launcher entry.
                .distinctBy { it.packageName }
                .map { info ->
                    InstalledApp(
                        packageName = info.packageName,
                        label = runCatching { manager.getApplicationLabel(info).toString() }
                            .getOrDefault(info.packageName),
                        icon = runCatching { manager.getApplicationIcon(info) }.getOrNull(),
                        system = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
                .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
                .toList()
        }.getOrDefault(emptyList())
    }
}
