package com.poc.nintendog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Shared colours and small view builders — the whole UI is built in code. */
object Ui {
    const val BG = 0xFF14161C.toInt()
    const val PANEL = 0xFF1E2230.toInt()
    const val ACCENT = 0xFFF2A03D.toInt()
    const val TEXT = 0xFFEDEFF5.toInt()
    const val DIM = 0xFF8E96AA.toInt()

    fun dp(ctx: Context, v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun button(ctx: Context, label: String, color: Int = PANEL, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(TEXT)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        b.setPadding(dp(ctx, 14f), dp(ctx, 8f), dp(ctx, 14f), dp(ctx, 8f))
        b.background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 12f).toFloat()
            setColor(color)
            setStroke(dp(ctx, 1f), 0x33FFFFFF)
        }
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f))
        b.layoutParams = lp
        return b
    }

    fun label(ctx: Context, s: String, size: Float = 14f, color: Int = TEXT): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextColor(color)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        return t
    }

    fun row(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    fun panel(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        l.background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 14f).toFloat()
            setColor(PANEL)
        }
        l.setPadding(dp(ctx, 10f), dp(ctx, 8f), dp(ctx, 10f), dp(ctx, 8f))
        return l
    }
}

/** A labelled need meter. Colour shifts to red as the need becomes urgent. */
class StatBar(ctx: Context, private val title: String) : View(ctx) {

    var value = 100f
        set(v) { field = v.coerceIn(0f, 100f); invalidate() }

    /** Some meters (like "needs to go") are bad when high. */
    var inverted = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT
        isFakeBoldText = true
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(context, 26f))
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        val barLeft = w * 0.34f
        t.textSize = h * 0.45f
        t.color = Ui.DIM
        c.drawText(title, 0f, h * 0.66f, t)

        val r = h * 0.22f
        p.color = 0xFF2C3245.toInt()
        c.drawRoundRect(RectF(barLeft, h * 0.28f, w, h * 0.72f), r, r, p)

        val level = if (inverted) 100f - value else value
        p.color = when {
            level > 55f -> 0xFF5DC98A.toInt()
            level > 28f -> 0xFFE8B84B.toInt()
            else -> 0xFFE0604F.toInt()
        }
        val fill = barLeft + (w - barLeft) * (value / 100f)
        c.drawRoundRect(RectF(barLeft, h * 0.28f, maxOf(fill, barLeft + r * 2), h * 0.72f), r, r, p)

        t.color = Color.WHITE
        t.textSize = h * 0.38f
        c.drawText("${value.toInt()}", w - t.measureText("100") - h * 0.15f, h * 0.63f, t)
    }
}
