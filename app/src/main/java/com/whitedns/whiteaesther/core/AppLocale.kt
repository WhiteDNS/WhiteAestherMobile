package com.whitedns.whiteaesther.core

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.edit
import com.whitedns.whiteaesther.data.AppLanguage
import java.util.Locale

/**
 * Applies the chosen language to a context.
 *
 * Android has a per-app language API, but only from API 33; below that the
 * platform offers nothing and the usual answer is to add AppCompat for the one
 * call that backports it. This app is Compose throughout and uses no AppCompat
 * views, so that would be a whole library carried for a single method. Wrapping
 * the base context is what AppCompat does underneath anyway, and it works
 * unchanged from API 26.
 *
 * The choice lives in DataStore with every other setting, but DataStore is
 * asynchronous and `attachBaseContext` is not: it runs before the activity
 * exists and cannot suspend. So the tag is mirrored into a plain
 * SharedPreferences file that can be read synchronously at that moment.
 * DataStore stays the source of truth and this mirror is written whenever the
 * setting is saved.
 */
object AppLocale {
    private const val MIRROR = "whiteaesther_locale"
    private const val KEY = "tag"

    private fun mirror(context: Context): SharedPreferences =
        context.getSharedPreferences(MIRROR, Context.MODE_PRIVATE)

    /**
     * Records the choice where [wrap] can read it without suspending.
     *
     * Called on every save rather than only on change: a write of the same
     * value costs nothing, and the alternative is a mirror that silently
     * disagrees with DataStore after any path that did not think to update it.
     */
    fun remember(context: Context, language: AppLanguage) {
        mirror(context).edit { putString(KEY, language.tag) }
    }

    /** What [wrap] will use, for a caller deciding whether a restart is needed. */
    fun current(context: Context): String = mirror(context).getString(KEY, "").orEmpty()

    /**
     * The same context, speaking the chosen language.
     *
     * Returns the context untouched when the choice is to follow the phone,
     * which leaves Android's own resource resolution in charge -- including the
     * user's ordered list of preferred languages, which a single forced locale
     * would flatten.
     */
    fun wrap(context: Context): Context {
        val tag = current(context)
        if (tag.isEmpty()) return context
        return wrap(context, Locale.forLanguageTag(tag))
    }

    private fun wrap(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        // Layout direction does not follow the locale on its own here. Without
        // this a Persian interface lays out left to right, which is the one
        // thing a reader notices before any of the words.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }
}
