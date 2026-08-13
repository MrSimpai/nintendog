package com.poc.nintendog

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The walk. Like Nintendogs, you draw a route on the neighbourhood map with
 * your finger and then follow it — the length of the route decides how long
 * the walk lasts and how much you find along the way.
 */
class WalkActivity : Activity() {

    private lateinit var map: MapView
    private lateinit var log: TextView
    private lateinit var info: TextView

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        Pet.load(this)
        Pet.tick(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        val pad = Ui.dp(this, 12f)
        root.setPadding(pad, Ui.dp(this, 26f), pad, pad)

        val t = Ui.label(this, "Walk ${Pet.name}", 20f)
        t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        root.addView(t)
        info = Ui.label(this, "Drag to draw a route through the neighbourhood.", 13f, Ui.DIM)
        root.addView(info)

        map = MapView(this)
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))

        log = Ui.label(this, "", 13f, Ui.TEXT)
        log.gravity = Gravity.CENTER
        log.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
        log.minLines = 3
        root.addView(log)

        val buttons = Ui.row(this)
        buttons.gravity = Gravity.CENTER
        buttons.addView(Ui.button(this, "Clear route") { map.clear(); log.text = "" })
        buttons.addView(Ui.button(this, "Start walk", Ui.ACCENT) { map.start() })
        buttons.addView(Ui.button(this, "Back") { finish() })
        root.addView(buttons)

        setContentView(root)
    }

    private fun finished(minutes: Int, coins: Int, events: List<String>) {
        val m = Pet.walked(minutes, coins)
        Pet.save(this)
        Sfx.chime()
        PetWidget.updateAll(this)
        log.text = (events.takeLast(2) + m).joinToString("\n")
        info.text = "Walk complete. Draw another route or head home."
    }

    // ------------------------------------------------------------- map view

    inner class MapView(ctx: Context) : View(ctx) {

        private val pts = ArrayList<FloatArray>()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private var walking = false
        private var progress = 0f       // distance travelled along the route, px
        private var totalLen = 0f
        private var coins = 0
        private val events = ArrayList<String>()
        private var nextEventAt = 0f
        private var lastFrame = 0L

        /** Landmarks: x, y (0..1), kind 0 park, 1 pond, 2 shop, 3 tree */
        private val marks = listOf(
            floatArrayOf(0.22f, 0.24f, 0f), floatArrayOf(0.75f, 0.30f, 1f),
            floatArrayOf(0.55f, 0.62f, 0f), floatArrayOf(0.18f, 0.72f, 2f),
            floatArrayOf(0.82f, 0.78f, 3f), floatArrayOf(0.45f, 0.14f, 3f)
        )

        fun clear() { pts.clear(); walking = false; progress = 0f; events.clear(); coins = 0; invalidate() }

        fun start() {
            if (pts.size < 2) { info.text = "Draw a route first."; return }
            if (Pet.asleep) { info.text = "${Pet.name} is asleep."; return }
            if (Pet.energy < 12f) { info.text = "${Pet.name} is too tired for a walk."; return }
            totalLen = 0f
            for (i in 1 until pts.size)
                totalLen += hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
            walking = true; progress = 0f; coins = 0
            events.clear()
            nextEventAt = totalLen * (0.12f + Random.nextFloat() * 0.12f)
            lastFrame = 0L
            info.text = "Walking..."
            Sfx.bark(1.05f)
            postOnAnimation(tickRunner)
        }

        private val tickRunner = object : Runnable {
            override fun run() {
                if (!walking) return
                val now = System.nanoTime()
                val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1e9f).coerceIn(0.001f, 0.05f)
                lastFrame = now
                // The whole route takes about 12 seconds regardless of length.
                progress += totalLen / 12f * dt
                if (progress >= nextEventAt) {
                    fireEvent()
                    nextEventAt = progress + totalLen * (0.12f + Random.nextFloat() * 0.18f)
                }
                if (progress >= totalLen) {
                    walking = false
                    val minutes = (totalLen / (width.coerceAtLeast(1)) * 9f).toInt().coerceIn(5, 45)
                    finished(minutes, coins, events)
                } else postOnAnimation(this)
                invalidate()
            }
        }

        private fun fireEvent() {
            when (Random.nextInt(6)) {
                0 -> { val c = 5 + Random.nextInt(20); coins += c; events.add("💰 Found $c coins on the path!") ; Sfx.chime() }
                1 -> { events.add("🐕 Met another dog — ${Pet.name} wagged like mad."); Sfx.bark(0.95f) }
                2 -> events.add("🌳 Sniffed every inch of a very interesting tree.")
                3 -> { val c = 10 + Random.nextInt(15); coins += c; events.add("🎁 A neighbour handed you $c coins.") }
                4 -> { events.add("💧 Stopped at the pond for a drink."); Pet.hydration = minOf(100f, Pet.hydration + 12f) }
                else -> { events.add("💩 ${Pet.name} did their business. Good thing you brought bags."); Pet.bowel = 0f }
            }
            log.text = events.takeLast(3).joinToString("\n")
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (walking) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pts.clear(); pts.add(floatArrayOf(e.x, e.y)) }
                MotionEvent.ACTION_MOVE -> {
                    val last = pts.lastOrNull()
                    if (last == null || hypot(e.x - last[0], e.y - last[1]) > Ui.dp(context, 6f))
                        pts.add(floatArrayOf(e.x, e.y))
                }
                MotionEvent.ACTION_UP -> {
                    var len = 0f
                    for (i in 1 until pts.size) len += hypot(pts[i][0] - pts[i-1][0], pts[i][1] - pts[i-1][1])
                    val minutes = (len / width.coerceAtLeast(1) * 9f).toInt().coerceIn(5, 45)
                    info.text = if (pts.size < 2) "Draw a longer route." else "Route: about $minutes minutes."
                }
            }
            invalidate()
            return true
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.color = 0xFF243024.toInt()
            c.drawRoundRect(RectF(0f, 0f, w, h), Ui.dp(context, 14f).toFloat(),
                Ui.dp(context, 14f).toFloat(), p)

            // street grid
            p.color = 0xFF3A4A3A.toInt()
            p.strokeWidth = Ui.dp(context, 10f).toFloat()
            for (i in 1..3) {
                c.drawLine(0f, h * i / 4f, w, h * i / 4f, p)
                c.drawLine(w * i / 4f, 0f, w * i / 4f, h, p)
            }

            // landmarks
            for (m in marks) {
                val x = m[0] * w; val y = m[1] * h
                val r = Ui.dp(context, 13f).toFloat()
                p.color = when (m[2].toInt()) {
                    0 -> 0xFF4E8A46.toInt(); 1 -> 0xFF3E6E9E.toInt()
                    2 -> 0xFF9E7C3E.toInt(); else -> 0xFF356B35.toInt()
                }
                c.drawCircle(x, y, r, p)
            }

            // route
            if (pts.size > 1) {
                path.reset()
                path.moveTo(pts[0][0], pts[0][1])
                for (i in 1 until pts.size) path.lineTo(pts[i][0], pts[i][1])
                p.color = 0x99F2A03D.toInt()
                p.style = Paint.Style.STROKE
                p.strokeWidth = Ui.dp(context, 5f).toFloat()
                p.strokeCap = Paint.Cap.ROUND
                c.drawPath(path, p)
                p.style = Paint.Style.FILL
            }

            // the pup, walking the route
            if (walking && pts.size > 1) {
                var acc = 0f
                var px = pts[0][0]; var py = pts[0][1]
                for (i in 1 until pts.size) {
                    val seg = hypot(pts[i][0] - pts[i-1][0], pts[i][1] - pts[i-1][1])
                    if (acc + seg >= progress) {
                        val f = if (seg <= 0f) 0f else (progress - acc) / seg
                        px = pts[i-1][0] + (pts[i][0] - pts[i-1][0]) * f
                        py = pts[i-1][1] + (pts[i][1] - pts[i-1][1]) * f
                        break
                    }
                    acc += seg
                    px = pts[i][0]; py = pts[i][1]
                }
                p.color = Pet.breed.coat
                val r = Ui.dp(context, 9f).toFloat()
                c.drawCircle(px, py, r, p)
                p.color = Color.WHITE
                c.drawCircle(px + r * 0.35f, py - r * 0.3f, r * 0.22f, p)
            }
        }
    }
}
