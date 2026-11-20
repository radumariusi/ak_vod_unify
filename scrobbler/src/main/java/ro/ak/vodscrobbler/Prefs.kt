package ro.ak.vodscrobbler

import android.content.Context

/** Setările mici ale scrobblerului. */
object Prefs {
    private const val FILE = "settings"
    private const val DOT = "dot"

    fun dotEnabled(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(DOT, true)

    fun setDotEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(DOT, value).apply()
    }
}
