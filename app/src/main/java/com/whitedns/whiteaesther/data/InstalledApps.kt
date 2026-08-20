package com.whitedns.whiteaesther.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable

/**
 * An app the user could plausibly want routed, or kept out of the tunnel.
 *
 * Deliberately no icon. Loading one costs a drawable inflate and, for adaptive
 * icons, rasterising two layers -- times every app on the phone, which is the
 * difference between a list that appears and a list that arrives after a stall.
 * Icons are fetched per row, for rows that are actually on screen.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    /** Shipped with the device rather than installed by the user. */
    val system: Boolean,
)

object InstalledApps {
    /**
     * Everything with a launcher entry, by name.
     *
     * Not every installed package: `QUERY_ALL_PACKAGES` is a restricted
     * permission on Play and would return several hundred services and
     * providers, none of which the user recognises or would knowingly route. A
     * launcher entry is a good proxy for "an app the user thinks of as an app".
     *
     * Touches the package manager once per entry, so it belongs off the main
     * thread on a phone with a lot of apps installed.
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
                        system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
                .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
                .toList()
        }.getOrDefault(emptyList())
    }

    /** One app's launcher icon. Expensive enough to be worth doing off the main thread. */
    fun icon(context: Context, packageName: String): Drawable? =
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
}
