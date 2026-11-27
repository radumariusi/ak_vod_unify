package ro.ak.vodimport

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.Button
import android.widget.TextView

/** Aceleași culori și același focus ca în ecranele Compose din AK VOD Unify. */
object AkWidgets {
    val background = Color.rgb(12, 16, 23)
    val surface = Color.rgb(21, 29, 42)
    val ink = Color.rgb(20, 32, 47)
    val foreground = Color.rgb(232, 234, 237)
    val muted = Color.rgb(187, 198, 214)
    private val accent = Color.rgb(229, 9, 20)

    private val focused = intArrayOf(android.R.attr.state_focused)
    private val idle = intArrayOf()

    fun text(context: Context, label: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = label
        textSize = size
        setTextColor(AkWidgets.foreground)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, context.dp(6), 0, context.dp(6))
    }

    fun heading(context: Context, label: String) = text(context, label, 24f, bold = true).apply {
        val mark = GradientDrawable().apply {
            setColor(accent)
            cornerRadius = context.dp(2).toFloat()
            setBounds(0, 0, context.dp(4), context.dp(24))
        }
        setCompoundDrawablesRelative(mark, null, null, null)
        compoundDrawablePadding = context.dp(12)
    }

    fun button(context: Context, label: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        isFocusable = true
        setTypeface(typeface, Typeface.BOLD)
        setPadding(context.dp(18), context.dp(8), context.dp(18), context.dp(8))
        minHeight = context.dp(48)
        val shape = StateListDrawable().apply {
            addState(focused, pill(context, Color.WHITE))
            addState(idle, pill(context, if (primary) 0x42FFFFFF else 0x1FFFFFFF))
        }
        background = RippleDrawable(ColorStateList.valueOf(0x44000000), shape, pill(context, Color.WHITE))
        setTextColor(ColorStateList(arrayOf(focused, idle), intArrayOf(ink, AkWidgets.foreground)))
        setOnClickListener { action() }
    }

    fun panelFocus(view: View, focused: Boolean) {
        view.background = if (focused) GradientDrawable().apply {
            setColor(surface)
            cornerRadius = view.context.dp(8).toFloat()
            setStroke(view.context.dp(2), foreground)
        } else null
    }

    private fun pill(context: Context, color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(32).toFloat()
    }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
