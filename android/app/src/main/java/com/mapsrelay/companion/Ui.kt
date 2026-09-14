package com.mapsrelay.companion

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

/**
 * Palette and view helpers.
 *
 * Lifted from the KnogScout console so the two tools read as one: a dark
 * ground, hairline-stroked cards, monospace for anything that is data, and one
 * accent. Only the accent differs — amber here rather than teal, because this
 * app lives beside OsmAnd and that is the colour OsmAnd puts on its own
 * notification (0xFFFF8F00).
 */
object Ui {

    const val BG = "#0E1416"
    const val SURFACE = "#161E21"
    const val SURFACE2 = "#1D2629"
    const val LINE = "#2A3639"
    const val TEXT = "#E6EDEC"
    const val DIM = "#8B9B9D"
    const val ACCENT = "#FF8F00"
    const val ON_ACCENT = "#1B1200"

    /** State colours, in the same roles KnogScout uses them. */
    const val S_OFF = "#7C8A8C"
    const val S_WARN = "#E0A63C"
    const val S_OK = "#4FBE7C"
    const val S_BAD = "#F0574A"

    fun col(hex: String): Int = Color.parseColor(hex)

    fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density
    fun dpi(ctx: Context, v: Float): Int = dp(ctx, v).toInt()

    fun cardBg(ctx: Context, stroke: String = LINE, fill: String = SURFACE): GradientDrawable =
        GradientDrawable().apply {
            setColor(col(fill))
            cornerRadius = dp(ctx, 14f)
            setStroke(dpi(ctx, 1f), col(stroke))
        }

    fun label(ctx: Context, t: String): TextView = TextView(ctx).apply {
        text = t
        textSize = 11f
        letterSpacing = 0.12f
        typeface = Typeface.MONOSPACE
        setTextColor(col(DIM))
    }

    fun mono(ctx: Context, t: String, size: Float, colour: String): TextView = TextView(ctx).apply {
        text = t
        textSize = size
        typeface = Typeface.MONOSPACE
        setTextColor(col(colour))
    }

    fun condensed(ctx: Context, t: String, size: Float, colour: String): TextView =
        TextView(ctx).apply {
            text = t
            textSize = size
            letterSpacing = 0.02f
            setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD))
            setTextColor(col(colour))
        }

    /** Outlined pill. Used for the watch indicator in the header. */
    fun pill(ctx: Context, text: String, colour: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 10f
        letterSpacing = 0.09f
        typeface = Typeface.MONOSPACE
        setTextColor(col(colour))
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(ctx, 999f)
            setStroke(dpi(ctx, 1f), col(colour))
        }
        setPadding(dpi(ctx, 10f), dpi(ctx, 6f), dpi(ctx, 10f), dpi(ctx, 6f))
    }

    fun setPill(v: TextView, ctx: Context, text: String, colour: String) {
        v.text = text
        v.setTextColor(col(colour))
        (v.background as GradientDrawable).setStroke(dpi(ctx, 1f), col(colour))
    }
}
