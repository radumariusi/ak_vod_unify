package ro.ak.vodscrobbler

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/** Setările mici ale scrobblerului. */
object Prefs {
    private const val FILE = "settings"
    private const val DOT = "dot"
    private const val DOT_SIZE = "dot_size"
    private const val DOT_CORNER = "dot_corner"
    private const val DOT_ALPHA = "dot_alpha"
    private const val COLOR_IDLE = "dot_color_idle"
    private const val COLOR_PLAYING = "dot_color_playing"
    private const val COLOR_OFF = "dot_color_off"

    /** O culoare din lista bulinei. */
    data class DotColor(val name: String, val value: Int)

    val COLORS = listOf(
        DotColor("Verde", Color.rgb(46, 204, 113)),
        DotColor("Roșu", Color.rgb(229, 9, 20)),
        DotColor("Gri", Color.rgb(140, 140, 140)),
        DotColor("Albastru", Color.rgb(0, 150, 255)),
        DotColor("Galben", Color.rgb(255, 214, 0)),
        DotColor("Portocaliu", Color.rgb(255, 140, 0)),
        DotColor("Mov", Color.rgb(155, 89, 182)),
        DotColor("Roz", Color.rgb(255, 64, 160)),
        DotColor("Turcoaz", Color.rgb(0, 206, 209)),
        DotColor("Alb", Color.rgb(255, 255, 255)),
    )

    /** Mărimile bulinei, în dp. */
    val SIZES = listOf(8 to "Mică", 12 to "Medie", 18 to "Mare", 26 to "Foarte mare")

    /** Colțul ecranului în care stă bulina. */
    enum class Corner(val label: String) { TOP_END("dreapta sus"), TOP_START("stânga sus"), BOTTOM_END("dreapta jos"), BOTTOM_START("stânga jos") }

    /** Cât de vizibilă e bulina (procent). */
    val ALPHAS = listOf(100, 85, 60, 40)

    /** Ce arată bulina; fiecare stare are culoarea ei, aleasă din [COLORS]. */
    enum class DotState(val key: String, val label: String, val default: Int) {
        IDLE(COLOR_IDLE, "activ, nu rulează nimic", 0),
        PLAYING(COLOR_PLAYING, "urmărește acum o redare", 1),
        OFF(COLOR_OFF, "fără acces la sesiunile media", 2),
    }

    fun observe(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun stopObserving(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun dotEnabled(context: Context) = prefs(context).getBoolean(DOT, true)

    fun setDotEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(DOT, value).apply()
    }

    fun dotColorIndex(context: Context, state: DotState): Int =
        prefs(context).getInt(state.key, state.default).coerceIn(0, COLORS.lastIndex)

    fun dotColor(context: Context, state: DotState): DotColor = COLORS[dotColorIndex(context, state)]

    /** Trece la culoarea următoare din listă pentru [state]. */
    fun nextDotColor(context: Context, state: DotState) {
        prefs(context).edit().putInt(state.key, (dotColorIndex(context, state) + 1) % COLORS.size).apply()
    }

    fun dotSizeIndex(context: Context): Int = prefs(context).getInt(DOT_SIZE, 1).coerceIn(0, SIZES.lastIndex)

    fun dotSizeDp(context: Context): Int = SIZES[dotSizeIndex(context)].first

    fun nextDotSize(context: Context) {
        prefs(context).edit().putInt(DOT_SIZE, (dotSizeIndex(context) + 1) % SIZES.size).apply()
    }

    fun dotCorner(context: Context): Corner =
        Corner.entries.getOrElse(prefs(context).getInt(DOT_CORNER, 0)) { Corner.TOP_END }

    fun nextDotCorner(context: Context) {
        prefs(context).edit().putInt(DOT_CORNER, (dotCorner(context).ordinal + 1) % Corner.entries.size).apply()
    }

    fun dotAlphaIndex(context: Context): Int = prefs(context).getInt(DOT_ALPHA, 1).coerceIn(0, ALPHAS.lastIndex)

    fun dotAlpha(context: Context): Int = ALPHAS[dotAlphaIndex(context)]

    fun nextDotAlpha(context: Context) {
        prefs(context).edit().putInt(DOT_ALPHA, (dotAlphaIndex(context) + 1) % ALPHAS.size).apply()
    }
}
