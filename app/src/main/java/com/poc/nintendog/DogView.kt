package com.poc.nintendog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The living room / yard. Draws the dog procedurally (no art assets) and owns
 * every direct interaction: petting, washing, fetch, trick training and the
 * disc contest.
 *
 * The scene is 2.5D — the floor is a band, and a "depth" value from 0 (far) to
 * 1 (near) picks the screen Y and the draw scale. That is what gives the
 * Nintendogs feel of a pup trotting around a room towards you.
 */
class DogView(ctx: Context) : View(ctx) {

    enum class Mode { HOME, WASH, FETCH, TRAIN, DISC }

    interface Listener {
        fun onMessage(msg: String)
        fun onChanged()
        /** The dog successfully struck the pose — the praise window opens. */
        fun onTrickPose(trick: String)
        fun onContestDone(score: Int)
        fun onWashComplete()
    }

    var listener: Listener? = null
    var mode = Mode.HOME
        set(v) {
            field = v
            resetForMode()
            invalidate()
        }

    /** Trick the training mode is currently drilling. */
    var trainingTrick = "Sit"

    // ------------------------------------------------------------ dog state

    private enum class Act { IDLE, WANDER, SIT, LIE, SLEEP, GOTO, CARRY, EAT, TRICK, SCRATCH, BEG }

    private var act = Act.IDLE
    private var actTime = 0f          // seconds spent in the current act
    private var actLimit = 2f

    private var dx = 0.5f             // horizontal position, 0..1 of width
    private var dd = 0.55f            // depth, 0 far .. 1 near
    private var tx = 0.5f             // move target
    private var td = 0.55f
    private var facing = 1f
    private var speed = 0f

    // pose blend values, all 0..1 unless noted
    private var sit = 0f
    private var lie = 0f
    private var runAmt = 0f
    private var legT = 0f
    private var tailT = 0f
    private var blink = 0f
    private var blinkTimer = 2f
    private var mouth = 0f
    private var pawUp = 0f
    private var begAmt = 0f
    private var rollAmt = 0f          // 0..1 -> full 360 body roll
    private var spinAmt = 0f          // 0..1 -> full 360 turn on the spot
    private var hop = 0f              // pixels of vertical lift
    private var headTurn = 0f         // -1..1, looks toward the finger
    private var lookX = 0.5f
    private var lookD = 1f

    private var trickHold = 0f        // seconds the dog holds a performed pose
    private var pendingTrick: String? = null

    // ------------------------------------------------------------- objects

    private var ballX = 0.5f
    private var ballD = 0.6f
    private var ballZ = 0f            // height above the floor, in "unit" space
    private var bvx = 0f
    private var bvd = 0f
    private var bvz = 0f
    private var ballActive = false
    private var ballHeld = false      // in the dog's mouth
    private var discMode = false

    private var throwsLeft = 0
    private var catches = 0
    private var contestRunning = false

    // wash
    private var suds = 0f             // 0..100 scrub progress
    private val bubbles = ArrayList<FloatArray>()   // x, y, r, life

    // particles: x, y, vx, vy, life, kind (0 heart, 1 note, 2 sparkle, 3 zzz, 4 drop)
    private val fx = ArrayList<FloatArray>()

    // ------------------------------------------------------------- petting

    private var touching = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var strokeAccum = 0f
    private var petGlow = 0f

    // gesture tracking for training
    private val gx = ArrayList<Float>()
    private val gy = ArrayList<Float>()

    // ---------------------------------------------------------------- paint

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private var lastFrame = 0L

    private var running = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLoop()
    }

    /** Don't burn a frame loop while another screen is on top. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startLoop() else stopLoop()
    }

    private fun startLoop() {
        if (running) return
        running = true
        lastFrame = 0L
        postOnAnimation(frame)
    }

    private fun stopLoop() {
        running = false
        removeCallbacks(frame)
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            val dt = if (lastFrame == 0L) 0.016f
            else ((now - lastFrame) / 1e9f).coerceIn(0.001f, 0.05f)
            lastFrame = now
            update(dt)
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun resetForMode() {
        fx.clear(); bubbles.clear()
        ballActive = false; ballHeld = false
        contestRunning = false
        suds = 0f
        act = Act.IDLE; actTime = 0f; actLimit = 1.5f
        when (mode) {
            Mode.FETCH -> { discMode = false; listener?.onMessage("Fling the ball across the yard!") }
            Mode.DISC -> {
                discMode = true; throwsLeft = 6; catches = 0; contestRunning = true
                listener?.onMessage("Disc contest! 6 throws — swipe to launch.")
            }
            Mode.WASH -> listener?.onMessage("Scrub ${Pet.name} all over with your finger.")
            Mode.TRAIN -> listener?.onMessage(gestureHint(trainingTrick))
            Mode.HOME -> {}
        }
    }

    fun gestureHint(trick: String): String = when (trick) {
        "Sit" -> "Lure downward: swipe DOWN over your pup."
        "Lie Down" -> "Swipe DOWN twice — a long slow drag to the floor."
        "Shake" -> "Swipe SIDEWAYS toward the front paw."
        "Roll Over" -> "Draw a big CIRCLE around your pup."
        "Speak" -> "Swipe UP sharply to excite them."
        "Spin" -> "Draw a small quick CIRCLE."
        else -> "Swipe to lure your pup."
    }

    // ============================================================ SIMULATION

    private fun update(dt: Float) {
        val awake = !Pet.asleep && !Pet.gone
        tailT += dt * (2f + Pet.happiness / 22f + petGlow * 6f)
        legT += dt * (2f + speed * 26f)
        petGlow = max(0f, petGlow - dt * 1.6f)

        // blinking
        blinkTimer -= dt
        if (blinkTimer <= 0f) { blink = 1f; blinkTimer = 1.8f + Random.nextFloat() * 3.5f }
        blink = max(0f, blink - dt * 7f)

        if (!awake) {
            approach({ sit }, { sit = it }, 0f, dt, 3f)
            approach({ lie }, { lie = it }, 1f, dt, 2f)
            approach({ runAmt }, { runAmt = it }, 0f, dt, 5f)
            speed = 0f
            if (Random.nextFloat() < dt * 0.7f) spawn(dx * width, floorY(dd) - unit() * 1.5f, 3)
            stepFx(dt)
            return
        }

        when (mode) {
            Mode.HOME, Mode.WASH -> updateHome(dt)
            Mode.FETCH, Mode.DISC -> updateFetch(dt)
            Mode.TRAIN -> updateTrain(dt)
        }
        stepFx(dt)
    }

    // -------------------------------------------------------------- ai: home

    private fun updateHome(dt: Float) {
        actTime += dt
        trickHold = max(0f, trickHold - dt)

        // A miserable or exhausted dog does not bounce around the room.
        val vigour = (Pet.happiness * 0.5f + Pet.energy * 0.5f) / 100f

        if (act == Act.GOTO || act == Act.WANDER) moveToward(dt, 0.16f + 0.22f * vigour)

        if (actTime > actLimit && trickHold <= 0f) pickIdleAct(vigour)

        // Petting overrides: come sit near the finger and enjoy it.
        if (touching && mode == Mode.HOME) {
            act = Act.IDLE
            headTurn = ((lookX - dx) * 3f).coerceIn(-1f, 1f)
        } else if (act != Act.TRICK) {
            approach({ headTurn }, { headTurn = it }, 0f, dt, 3f)
        }

        val wantSit = act == Act.SIT || act == Act.BEG || (touching && petGlow > 0.2f)
        approach({ sit }, { sit = it }, if (wantSit) 1f else 0f, dt, 3.2f)
        approach({ lie }, { lie = it }, if (act == Act.LIE) 1f else 0f, dt, 2.2f)
        approach({ begAmt }, { begAmt = it }, if (act == Act.BEG) 1f else 0f, dt, 3f)
        approach({ runAmt }, { runAmt = it }, if (speed > 0.12f) 1f else 0f, dt, 6f)
        approach({ mouth }, { mouth = it },
            if (Pet.happiness > 60f || petGlow > 0.1f || Pet.energy < 30f) 1f else 0f, dt, 3f)
        approach({ pawUp }, { pawUp = it }, if (act == Act.SCRATCH) 1f else 0f, dt, 8f)

        if (mode == Mode.WASH && suds > 0f) {
            // Bubbles drift up off a soapy dog.
            if (Random.nextFloat() < dt * suds * 0.25f)
                bubbles.add(floatArrayOf(
                    dx * width + (Random.nextFloat() - 0.5f) * unit() * 2f,
                    floorY(dd) - unit() * (0.4f + Random.nextFloat()),
                    unit() * (0.06f + Random.nextFloat() * 0.10f), 1f))
        }
        for (b in bubbles) { b[1] -= dt * unit() * 0.9f; b[3] -= dt * 0.55f }
        bubbles.removeAll { it[3] <= 0f }
    }

    private fun pickIdleAct(vigour: Float) {
        actTime = 0f
        val r = Random.nextFloat()
        act = when {
            Pet.energy < 25f -> if (r < 0.6f) Act.LIE else Act.SIT
            vigour > 0.55f && r < 0.45f -> Act.WANDER
            r < 0.6f -> Act.IDLE
            r < 0.75f -> Act.SIT
            r < 0.85f -> Act.SCRATCH
            r < 0.93f -> Act.LIE
            else -> Act.BEG
        }
        actLimit = when (act) {
            Act.WANDER -> 1.5f + Random.nextFloat() * 2f
            Act.LIE -> 4f + Random.nextFloat() * 6f
            Act.SCRATCH -> 1.2f
            else -> 1.6f + Random.nextFloat() * 3f
        }
        if (act == Act.WANDER) { tx = 0.15f + Random.nextFloat() * 0.7f; td = 0.25f + Random.nextFloat() * 0.7f }
        if (act == Act.WANDER && Random.nextFloat() < 0.12f) Sfx.bark(0.9f + Random.nextFloat() * 0.3f)
    }

    private fun moveToward(dt: Float, spd: Float) {
        val ddx = tx - dx
        val ddd = td - dd
        val dist = hypot(ddx, ddd * 0.5f)
        if (dist < 0.02f) { speed = 0f; if (act == Act.WANDER) { act = Act.IDLE; actTime = 0f }; return }
        val v = spd * Pet.breed.zoom
        dx += ddx / dist * v * dt
        dd = (dd + ddd / dist * v * dt * 0.7f).coerceIn(0.12f, 1f)
        dx = dx.coerceIn(0.08f, 0.92f)
        if (abs(ddx) > 0.01f) facing = if (ddx > 0) 1f else -1f
        speed = v
    }

    // ------------------------------------------------------------ ai: fetch

    private fun updateFetch(dt: Float) {
        val g = if (discMode) 5.5f else 16f
        if (ballActive && !ballHeld) {
            ballX += bvx * dt
            ballD = (ballD + bvd * dt).coerceIn(0.12f, 1f)
            ballZ += bvz * dt
            bvz -= g * dt
            if (discMode) { bvx *= (1f - dt * 0.35f); bvd *= (1f - dt * 0.35f) }
            if (ballX < 0.04f || ballX > 0.96f) { bvx = -bvx * 0.6f; ballX = ballX.coerceIn(0.04f, 0.96f) }
            if (ballZ <= 0f) {
                ballZ = 0f
                if (abs(bvz) > 1.2f) { bvz = -bvz * 0.45f } else { bvz = 0f }
                bvx *= 0.72f; bvd *= 0.72f
            }
            if (ballZ == 0f && abs(bvx) < 0.02f && abs(bvd) < 0.02f) { bvx = 0f; bvd = 0f }
        }

        if (ballActive) {
            if (ballHeld) {
                // Bring it back to the player, who stands at the bottom centre.
                tx = 0.5f; td = 1f
                act = Act.CARRY
                moveToward(dt, 0.42f)
                ballX = dx + facing * 0.05f
                ballD = dd
                ballZ = 0.55f
                if (hypot(dx - 0.5f, (dd - 1f) * 0.5f) < 0.06f) {
                    ballHeld = false; ballActive = false
                    hop = unit() * 0.35f
                    Sfx.bark(1.15f)
                    burst(dx * width, floorY(dd) - unit() * 1.6f, 0, 6)
                    listener?.onMessage(Pet.retrieved())
                    listener?.onChanged()
                    if (contestRunning) nextThrowOrFinish()
                }
            } else {
                tx = ballX; td = ballD
                act = Act.GOTO
                moveToward(dt, 0.5f)
                val near = hypot(dx - ballX, (dd - ballD) * 0.5f) < 0.055f
                if (discMode && ballZ > 0.6f && ballZ < 2.2f && near) {
                    // Leaping catch.
                    hop = unit() * (0.5f + Random.nextFloat() * 0.3f)
                    ballHeld = true
                    catches++
                    Sfx.chime()
                    burst(dx * width, floorY(dd) - unit() * 2f, 2, 8)
                } else if (near && ballZ < 0.5f) {
                    // Picked up off the ground — in a contest that is a miss.
                    ballHeld = true
                    Sfx.bark(1.05f)
                }
            }
        } else if (contestRunning && throwsLeft <= 0) {
            act = Act.IDLE
        } else {
            actTime += dt
            if (actTime > 1.6f) { pickIdleAct(0.7f); }
            if (act == Act.WANDER || act == Act.GOTO) moveToward(dt, 0.2f)
        }

        approach({ runAmt }, { runAmt = it }, if (speed > 0.12f) 1f else 0f, dt, 8f)
        approach({ sit }, { sit = it }, if (act == Act.SIT) 1f else 0f, dt, 3f)
        approach({ lie }, { lie = it }, 0f, dt, 3f)
        approach({ mouth }, { mouth = it }, if (ballHeld) 1f else 0.6f, dt, 4f)
        hop = max(0f, hop - dt * unit() * 2.2f)
    }

    private fun nextThrowOrFinish() {
        throwsLeft--
        if (throwsLeft <= 0) {
            contestRunning = false
            listener?.onContestDone(catches)
        } else {
            listener?.onMessage("Catches: $catches — ${throwsLeft} throw(s) left.")
        }
    }

    // ------------------------------------------------------------ ai: train

    private fun updateTrain(dt: Float) {
        trickHold = max(0f, trickHold - dt)
        actTime += dt
        speed = 0f
        // Face the trainer and wait attentively.
        approach({ headTurn }, { headTurn = it }, 0f, dt, 3f)

        val performing = trickHold > 0f && pendingTrick != null
        val t = pendingTrick
        approach({ sit }, { sit = it },
            if (performing && (t == "Sit" || t == "Shake" || t == "Speak")) 1f else 0f, dt, 4f)
        approach({ lie }, { lie = it }, if (performing && t == "Lie Down") 1f else 0f, dt, 3f)
        approach({ pawUp }, { pawUp = it }, if (performing && t == "Shake") 1f else 0f, dt, 7f)
        approach({ mouth }, { mouth = it }, if (performing && t == "Speak") 1f else 0.3f, dt, 8f)
        approach({ runAmt }, { runAmt = it }, 0f, dt, 6f)

        if (performing && t == "Roll Over") rollAmt = (rollAmt + dt * 0.9f) % 1f else rollAmt = 0f
        if (performing && t == "Spin") spinAmt = (spinAmt + dt * 1.4f) % 1f else spinAmt = 0f
    }

    /** Called when the player's lure gesture is recognised. */
    private fun attemptTrick(trick: String) {
        if (Pet.asleep) { listener?.onMessage("${Pet.name} is asleep."); return }
        if (Pet.energy < 8f) { listener?.onMessage("${Pet.name} is too tired to train."); return }
        val mastery = Pet.trickMastery(trick)
        val chance = (0.40f + mastery * 0.005f + Pet.affection * 0.002f).coerceAtMost(0.97f)
        if (Random.nextFloat() < chance) {
            pendingTrick = trick
            trickHold = 3.2f
            if (trick == "Speak") Sfx.bark(1.2f)
            burst(dx * width, floorY(dd) - unit() * 2f, 2, 5)
            listener?.onTrickPose(trick)
        } else {
            listener?.onMessage("${Pet.name} tilts their head, confused. Try again.")
            Sfx.whine()
            headTurn = if (Random.nextBoolean()) 0.8f else -0.8f
        }
    }

    fun clearTrickPose() { pendingTrick = null; trickHold = 0f }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                lastTouchX = e.x; lastTouchY = e.y
                gx.clear(); gy.clear(); gx.add(e.x); gy.add(e.y)
                strokeAccum = 0f
                if (mode == Mode.HOME && Pet.asleep && onDog(e.x, e.y)) {
                    listener?.onMessage("${Pet.name} stirs but stays asleep.")
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val d = hypot(e.x - lastTouchX, e.y - lastTouchY)
                lookX = e.x / width.toFloat()
                gx.add(e.x); gy.add(e.y)
                when (mode) {
                    Mode.HOME -> if (onDog(e.x, e.y) && !Pet.asleep) doPet(d, e.x, e.y)
                    Mode.WASH -> if (onDog(e.x, e.y)) doScrub(d, e.x, e.y)
                    else -> {}
                }
                lastTouchX = e.x; lastTouchY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                when (mode) {
                    Mode.FETCH, Mode.DISC -> throwFromGesture()
                    Mode.TRAIN -> recogniseGesture()
                    Mode.WASH -> if (suds >= 100f) {
                        listener?.onWashComplete(); suds = 0f
                    }
                    Mode.HOME -> {}
                }
            }
        }
        return true
    }

    private fun doPet(dist: Float, x: Float, y: Float) {
        strokeAccum += dist
        petGlow = 1f
        val u = unit()
        if (strokeAccum > u * 0.55f) {
            strokeAccum = 0f
            Pet.stroke(1)
            spawn(x, y, 0)
            listener?.onChanged()
            if (Random.nextFloat() < 0.10f) Sfx.bark(1.1f)
        }
    }

    private fun doScrub(dist: Float, x: Float, y: Float) {
        if (Pet.shampoo <= 0) { listener?.onMessage("You need shampoo from the shop."); return }
        val add = dist / (unit() * 12f) * 100f
        if (add <= 0f) return
        suds = min(100f, suds + add)
        Pet.scrub(add * 0.35f)
        if (Random.nextFloat() < 0.5f)
            bubbles.add(floatArrayOf(x, y, unit() * (0.05f + Random.nextFloat() * 0.10f), 1f))
        listener?.onChanged()
        if (suds >= 100f) listener?.onMessage("All lathered up — lift your finger to rinse!")
    }

    private fun throwFromGesture() {
        if (gx.size < 3) return
        val n = gx.size
        val k = max(0, n - 6)
        val vx = (gx[n - 1] - gx[k]) / width
        val vy = (gy[n - 1] - gy[k]) / height
        val power = hypot(vx, vy)
        if (power < 0.03f) return
        if (discMode && (!contestRunning || throwsLeft <= 0)) return
        if (discMode && ballActive) return
        if (!discMode && ballActive && !ballHeld) return

        // Everything is thrown from where you stand: bottom centre of the yard.
        ballActive = true; ballHeld = false
        ballX = 0.5f
        ballD = 1f
        ballZ = 0.5f
        bvx = vx * 3.2f
        bvd = (vy * 2.6f).coerceAtMost(-0.25f)   // upward swipe = away from you
        bvz = (5.5f + power * 9f).coerceAtMost(if (discMode) 9f else 12f)
        if (discMode) { bvz *= 0.55f; bvd *= 1.5f }
        Sfx.munch()
        listener?.onMessage(if (discMode) "Nice throw!" else "Go get it!")
    }

    /** Turns the drawn path into one of the lure gestures. */
    private fun recogniseGesture() {
        if (gx.size < 4) return
        val n = gx.size
        val dxTot = gx[n - 1] - gx[0]
        val dyTot = gy[n - 1] - gy[0]
        var pathLen = 0f
        for (i in 1 until n) pathLen += hypot(gx[i] - gx[i - 1], gy[i] - gy[i - 1])
        if (pathLen < unit() * 0.6f) return

        // Total angle swept around the path centroid detects a circle.
        var cx = 0f; var cy = 0f
        for (i in 0 until n) { cx += gx[i]; cy += gy[i] }
        cx /= n; cy /= n
        var swept = 0f
        var prev = atan2(gy[0] - cy, gx[0] - cx)
        for (i in 1 until n) {
            val a = atan2(gy[i] - cy, gx[i] - cx)
            var d = a - prev
            while (d > Math.PI) d -= (2 * Math.PI).toFloat()
            while (d < -Math.PI) d += (2 * Math.PI).toFloat()
            swept += d
            prev = a
        }
        val sweptDeg = abs(swept) * 180f / Math.PI.toFloat()

        val g = when {
            sweptDeg > 260f && pathLen > unit() * 4f -> "Roll Over"
            sweptDeg > 260f -> "Spin"
            abs(dyTot) > abs(dxTot) * 1.3f && dyTot > 0 -> if (pathLen > unit() * 3.5f) "Lie Down" else "Sit"
            abs(dyTot) > abs(dxTot) * 1.3f && dyTot < 0 -> "Speak"
            else -> "Shake"
        }
        if (g == trainingTrick) attemptTrick(g)
        else listener?.onMessage("That looked like a \"$g\" lure. ${gestureHint(trainingTrick)}")
    }

    private fun onDog(x: Float, y: Float): Boolean {
        val u = unit()
        val cx = dx * width
        val cy = floorY(dd) - u * 0.9f
        return abs(x - cx) < u * 1.3f && abs(y - cy) < u * 1.3f
    }

    // ------------------------------------------------------------- geometry

    private fun horizon() = height * 0.34f
    private fun floorTop() = height * 0.42f
    private fun floorY(depth: Float) = floorTop() + depth * (height - floorTop()) * 0.80f
    private fun depthScale(depth: Float) = 0.62f + depth * 0.55f
    /** Base body unit in pixels for the dog at its current depth. */
    private fun unit() = height * 0.115f * Pet.breed.size * depthScale(dd)

    // ------------------------------------------------------------ particles

    private fun spawn(x: Float, y: Float, kind: Int) {
        fx.add(floatArrayOf(x, y, (Random.nextFloat() - 0.5f) * 60f, -60f - Random.nextFloat() * 70f,
            1f, kind.toFloat()))
    }

    private fun burst(x: Float, y: Float, kind: Int, count: Int) {
        repeat(count) { spawn(x + (Random.nextFloat() - .5f) * unit(), y, kind) }
    }

    private fun stepFx(dt: Float) {
        for (f in fx) { f[0] += f[2] * dt; f[1] += f[3] * dt; f[3] += 25f * dt; f[4] -= dt * 0.75f }
        fx.removeAll { it[4] <= 0f }
        if (fx.size > 90) fx.subList(0, fx.size - 90).clear()
    }

    // ================================================================ RENDER

    private var bgShader: Shader? = null
    private var bgH = 0

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawRoom(c, w, h)
        drawPoops(c)

        // Objects behind the dog draw first.
        val ballBehind = ballActive && ballD < dd
        if (ballBehind) drawBall(c)

        drawShadow(c)
        drawDog(c)

        if (ballActive && !ballBehind) drawBall(c)
        drawBubbles(c)
        drawFx(c)
        drawHud(c, w, h)
    }

    private fun drawRoom(c: Canvas, w: Float, h: Float) {
        if (bgShader == null || bgH != height) {
            bgH = height
            val outdoors = mode == Mode.FETCH || mode == Mode.DISC
            bgShader = LinearGradient(0f, 0f, 0f, floorTop(),
                if (outdoors) 0xFF7EC0EE.toInt() else 0xFF3B4A63.toInt(),
                if (outdoors) 0xFFCFE9F7.toInt() else 0xFF5C6E8C.toInt(),
                Shader.TileMode.CLAMP)
        }
        p.shader = bgShader
        c.drawRect(0f, 0f, w, floorTop(), p)
        p.shader = null

        val outdoors = mode == Mode.FETCH || mode == Mode.DISC
        // Floor
        p.color = if (outdoors) 0xFF5FA355.toInt() else 0xFF8A6A45.toInt()
        c.drawRect(0f, floorTop(), w, h, p)

        // Perspective floor lines: cheap depth cue, reads as boards or grass rows.
        p.color = if (outdoors) 0x2233691E else 0x22000000
        p.strokeWidth = h * 0.004f
        var d = 0.1f
        while (d <= 1.05f) {
            val y = floorY(d)
            c.drawLine(0f, y, w, y, p)
            d += 0.18f
        }

        if (mode == Mode.WASH) {
            p.color = 0x3355C8FF
            c.drawRect(0f, floorTop(), w, h, p)
        }

        if (Pet.asleep) {
            p.color = 0x88000018.toInt()
            c.drawRect(0f, 0f, w, h, p)
        }

        // Food and water bowls sit in the room so the space feels lived in.
        if (!outdoors) {
            drawBowl(c, w * 0.13f, floorY(0.30f), 0xFFD05A4A.toInt(), Pet.fullness < 60f)
            drawBowl(c, w * 0.26f, floorY(0.30f), 0xFF4A8FD0.toInt(), Pet.hydration < 60f)
        }
    }

    private fun drawBowl(c: Canvas, x: Float, y: Float, col: Int, empty: Boolean) {
        val r = height * 0.035f
        p.color = 0x33000000
        c.drawOval(RectF(x - r, y - r * 0.28f, x + r, y + r * 0.28f), p)
        p.color = col
        c.drawArc(RectF(x - r, y - r * 0.9f, x + r, y + r * 0.5f), 0f, 180f, true, p)
        if (!empty) {
            p.color = 0xFFB4823C.toInt()
            c.drawArc(RectF(x - r * 0.7f, y - r * 0.55f, x + r * 0.7f, y + r * 0.25f), 0f, 180f, true, p)
        }
    }

    private fun drawPoops(c: Canvas) {
        if (Pet.poops <= 0) return
        p.color = 0xFF5A4326.toInt()
        for (i in 0 until Pet.poops) {
            val px = width * (0.18f + (i * 0.137f % 0.68f))
            val d = 0.30f + (i * 0.19f % 0.6f)
            val py = floorY(d)
            val r = height * 0.016f * depthScale(d)
            c.drawOval(RectF(px - r * 1.4f, py - r * 0.5f, px + r * 1.4f, py + r * 0.5f), p)
            c.drawOval(RectF(px - r, py - r * 1.2f, px + r, py - r * 0.2f), p)
            c.drawOval(RectF(px - r * 0.6f, py - r * 1.8f, px + r * 0.6f, py - r * 0.9f), p)
        }
    }

    private fun drawShadow(c: Canvas) {
        val u = unit()
        p.color = 0x44000000
        val y = floorY(dd)
        c.drawOval(RectF(dx * width - u * 1.1f, y - u * 0.16f,
            dx * width + u * 1.1f, y + u * 0.16f), p)
    }

    // -------------------------------------------------------------- the dog

    private fun drawDog(c: Canvas) {
        if (Pet.gone) {
            text.textSize = height * 0.05f
            text.color = 0xFFEEEEEE.toInt()
            c.drawText("${Pet.name} was taken to the shelter.", width / 2f, height * 0.5f, text)
            text.textSize = height * 0.032f
            c.drawText("Tap ADOPT to start again.", width / 2f, height * 0.57f, text)
            return
        }

        val u = unit()
        val coat = Pet.breed.coat
        val patch = Pet.breed.patch
        val dark = mul(coat, 0.78f)

        c.save()
        c.translate(dx * width, floorY(dd) - hop)
        c.scale(facing, 1f)
        if (spinAmt > 0f) c.scale(cos(spinAmt * 2f * Math.PI).toFloat().let {
            if (abs(it) < 0.15f) 0.15f * (if (it < 0) -1f else 1f) else it }, 1f)

        // Sitting drops the rear and tips the chest up; lying flattens everything.
        val rearDrop = u * (0.34f * sit + 0.66f * lie)
        val tilt = -16f * sit - 4f * lie + 8f * begAmt
        val bodyY = -u * (0.95f - 0.18f * sit - 0.62f * lie) + sin(legT * 2f) * u * 0.02f * runAmt

        if (rollAmt > 0f) c.rotate(rollAmt * 360f, 0f, bodyY)

        // ---- tail
        c.save()
        c.translate(-u * 0.62f, bodyY - u * 0.10f + rearDrop * 0.5f)
        val wag = sin(tailT * 3.4f) * (0.25f + Pet.happiness / 190f + petGlow * 0.5f)
        val tail = Path()
        val fl = Pet.breed.fluffTail
        tail.moveTo(0f, 0f)
        tail.quadTo(-u * 0.45f, -u * (0.25f + 0.5f * fl) + wag * u * 0.5f,
            -u * (0.30f + 0.25f * fl), -u * (0.75f + 0.35f * fl) + wag * u)
        p.color = coat
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = u * (0.16f + 0.22f * fl)
        c.drawPath(tail, p)
        p.style = Paint.Style.FILL
        c.restore()

        // ---- far legs (drawn darker so the near pair pops)
        drawLegs(c, u, bodyY, rearDrop, dark, 0.5f)

        // ---- body
        c.save()
        c.rotate(tilt, u * 0.35f, bodyY)
        p.color = coat
        val body = RectF(-u * 0.70f, bodyY - u * 0.34f, u * 0.62f, bodyY + u * 0.34f + rearDrop * 0.35f)
        c.drawRoundRect(body, u * 0.34f, u * 0.34f, p)
        // chest/belly patch
        p.color = patch
        c.drawRoundRect(RectF(-u * 0.10f, bodyY - u * 0.02f, u * 0.58f, bodyY + u * 0.33f),
            u * 0.22f, u * 0.22f, p)
        c.restore()

        // ---- near legs
        drawLegs(c, u, bodyY, rearDrop, coat, 1f)

        // ---- head
        val headX = u * (0.72f + 0.06f * sit)
        val headY = bodyY - u * (0.46f + 0.18f * sit - 0.30f * lie)
        c.save()
        c.translate(headX, headY)
        c.rotate(headTurn * 14f + (if (act == Act.SCRATCH) sin(legT * 14f) * 5f else 0f))

        // ears
        p.color = dark
        if (Pet.breed.floppyEars) {
            c.drawRoundRect(RectF(-u * 0.34f, -u * 0.16f, -u * 0.02f, u * 0.42f), u * 0.16f, u * 0.16f, p)
            c.drawRoundRect(RectF(u * 0.04f, -u * 0.20f, u * 0.30f, u * 0.30f), u * 0.14f, u * 0.14f, p)
        } else {
            val ear = Path()
            ear.moveTo(-u * 0.30f, -u * 0.10f); ear.lineTo(-u * 0.20f, -u * 0.62f); ear.lineTo(0f, -u * 0.18f)
            c.drawPath(ear, p)
            val ear2 = Path()
            ear2.moveTo(u * 0.02f, -u * 0.14f); ear2.lineTo(u * 0.16f, -u * 0.60f); ear2.lineTo(u * 0.30f, -u * 0.16f)
            c.drawPath(ear2, p)
        }

        p.color = coat
        c.drawCircle(0f, 0f, u * 0.36f, p)

        // muzzle
        p.color = patch
        c.drawRoundRect(RectF(u * 0.16f, -u * 0.02f, u * 0.60f, u * 0.28f), u * 0.14f, u * 0.14f, p)
        p.color = 0xFF25201C.toInt()
        c.drawCircle(u * 0.56f, u * 0.06f, u * 0.075f, p)

        // mouth / tongue
        if (mouth > 0.4f && !Pet.asleep) {
            p.color = 0xFF2A2320.toInt()
            c.drawRoundRect(RectF(u * 0.26f, u * 0.14f, u * 0.52f, u * 0.24f), u * 0.05f, u * 0.05f, p)
            p.color = 0xFFE87A8B.toInt()
            val tl = u * (0.10f + 0.16f * mouth) * (0.7f + 0.3f * sin(legT * 6f))
            c.drawRoundRect(RectF(u * 0.32f, u * 0.18f, u * 0.46f, u * 0.18f + tl), u * 0.06f, u * 0.06f, p)
        }

        // eyes
        val shut = Pet.asleep || blink > 0.35f
        p.color = 0xFF17140F.toInt()
        if (shut) {
            p.style = Paint.Style.STROKE; p.strokeWidth = u * 0.045f
            c.drawLine(-u * 0.02f, -u * 0.10f, u * 0.16f, -u * 0.10f, p)
            c.drawLine(-u * 0.30f, -u * 0.10f, -u * 0.14f, -u * 0.10f, p)
            p.style = Paint.Style.FILL
        } else {
            c.drawCircle(u * 0.09f, -u * 0.10f, u * 0.075f, p)
            c.drawCircle(-u * 0.20f, -u * 0.10f, u * 0.065f, p)
            p.color = Color.WHITE
            c.drawCircle(u * 0.11f, -u * 0.13f, u * 0.026f, p)
            c.drawCircle(-u * 0.18f, -u * 0.13f, u * 0.022f, p)
        }

        // Sick pups get a little compress on the forehead.
        if (Pet.sick) {
            p.color = 0xFFEFEFEF.toInt()
            c.drawRoundRect(RectF(-u * 0.26f, -u * 0.40f, u * 0.20f, -u * 0.26f), u * 0.05f, u * 0.05f, p)
        }
        c.restore()

        // ---- carried ball
        if (ballHeld) {
            p.color = if (discMode) 0xFFEB5C5C.toInt() else 0xFFF2C14E.toInt()
            c.drawCircle(headX + u * 0.62f, headY + u * 0.18f, u * 0.16f, p)
        }
        c.restore()

        if (mode == Mode.WASH && suds > 0f) {
            p.color = (0x66FFFFFF or ((suds * 1.5f).toInt().coerceAtMost(120) shl 24))
            c.drawCircle(dx * width, floorY(dd) - u * 0.9f, u * 1.15f, p)
        }
    }

    private fun drawLegs(c: Canvas, u: Float, bodyY: Float, rearDrop: Float, col: Int, alpha: Float) {
        p.color = if (alpha < 1f) mul(col, 0.8f) else col
        val off = if (alpha < 1f) -u * 0.12f else u * 0.08f
        val swing = sin(legT * 3.2f) * (0.16f + 0.42f * runAmt) * u
        val swing2 = sin(legT * 3.2f + Math.PI.toFloat()) * (0.16f + 0.42f * runAmt) * u
        val legW = u * 0.20f
        val down = -bodyY - u * 0.20f   // distance from body bottom to floor

        // front pair
        if (pawUp > 0.5f) {
            // Shake: one paw lifted forward.
            c.save(); c.translate(u * 0.44f + off, bodyY + u * 0.16f); c.rotate(-52f)
            c.drawRoundRect(RectF(-legW / 2, 0f, legW / 2, down * 0.8f), legW / 2, legW / 2, p)
            c.restore()
        } else {
            legRect(c, u * 0.44f + off + swing * (1f - sit), bodyY + u * 0.16f, legW, down * (1f - 0.10f * sit))
        }
        legRect(c, u * 0.30f + off + swing2 * (1f - sit), bodyY + u * 0.16f, legW, down * (1f - 0.10f * sit))

        // rear pair — folded when sitting or lying
        val rearLen = down * (1f - 0.55f * sit - 0.85f * lie)
        if (sit > 0.45f || lie > 0.3f) {
            p.color = if (alpha < 1f) mul(col, 0.8f) else col
            c.drawRoundRect(RectF(-u * 0.72f + off, bodyY + rearDrop * 0.2f,
                -u * 0.24f + off, bodyY + rearDrop * 0.2f + max(rearLen, u * 0.30f)),
                u * 0.16f, u * 0.16f, p)
        } else {
            legRect(c, -u * 0.42f + off + swing2, bodyY + u * 0.16f + rearDrop * 0.3f, legW, rearLen)
            legRect(c, -u * 0.56f + off + swing, bodyY + u * 0.16f + rearDrop * 0.3f, legW, rearLen)
        }
    }

    private fun legRect(c: Canvas, x: Float, y: Float, w: Float, len: Float) {
        c.drawRoundRect(RectF(x - w / 2, y, x + w / 2, y + max(len, w)), w / 2, w / 2, p)
    }

    // ------------------------------------------------------------- overlays

    private fun drawBall(c: Canvas) {
        val sc = depthScale(ballD)
        val r = height * 0.028f * sc
        val bx = ballX * width
        val by = floorY(ballD) - ballZ * height * 0.10f
        p.color = 0x33000000
        c.drawOval(RectF(bx - r, floorY(ballD) - r * 0.35f, bx + r, floorY(ballD) + r * 0.35f), p)
        if (discMode) {
            p.color = 0xFFEB5C5C.toInt()
            c.drawOval(RectF(bx - r * 1.5f, by - r * 0.45f, bx + r * 1.5f, by + r * 0.45f), p)
            p.color = 0xFFFFD4D4.toInt()
            c.drawOval(RectF(bx - r * 0.8f, by - r * 0.22f, bx + r * 0.8f, by + r * 0.22f), p)
        } else {
            p.color = 0xFFF2C14E.toInt()
            c.drawCircle(bx, by, r, p)
            p.color = 0xFFCF9A2E.toInt()
            c.drawCircle(bx - r * 0.3f, by - r * 0.3f, r * 0.28f, p)
        }
    }

    private fun drawBubbles(c: Canvas) {
        p.color = 0x88FFFFFF.toInt()
        p.style = Paint.Style.STROKE
        p.strokeWidth = height * 0.003f
        for (b in bubbles) c.drawCircle(b[0], b[1], b[2], p)
        p.style = Paint.Style.FILL
    }

    private fun drawFx(c: Canvas) {
        for (f in fx) {
            val a = (f[4].coerceIn(0f, 1f) * 255).toInt()
            val s = height * 0.032f
            when (f[5].toInt()) {
                0 -> { // heart
                    p.color = (a shl 24) or 0x00FF5C7A
                    val x = f[0]; val y = f[1]
                    val path = Path()
                    path.moveTo(x, y + s * 0.4f)
                    path.cubicTo(x - s * 0.9f, y - s * 0.2f, x - s * 0.3f, y - s * 0.8f, x, y - s * 0.25f)
                    path.cubicTo(x + s * 0.3f, y - s * 0.8f, x + s * 0.9f, y - s * 0.2f, x, y + s * 0.4f)
                    c.drawPath(path, p)
                }
                2 -> { // sparkle
                    p.color = (a shl 24) or 0x00FFE27A
                    c.drawCircle(f[0], f[1], s * 0.18f, p)
                }
                3 -> { // zzz
                    text.color = (a shl 24) or 0x00FFFFFF
                    text.textSize = s * 1.2f
                    c.drawText("z", f[0], f[1], text)
                }
            }
        }
    }

    private fun drawHud(c: Canvas, w: Float, h: Float) {
        text.color = 0xCCFFFFFF.toInt()
        text.textSize = h * 0.036f
        when (mode) {
            Mode.WASH -> {
                text.color = Color.WHITE
                c.drawText("Suds ${suds.toInt()}%", w / 2f, h * 0.07f, text)
            }
            Mode.DISC -> c.drawText(
                if (contestRunning) "Throw ${7 - throwsLeft}/6   Catches: $catches"
                else "Final: $catches catches", w / 2f, h * 0.07f, text)
            Mode.FETCH -> c.drawText("Swipe to throw the ball", w / 2f, h * 0.07f, text)
            Mode.TRAIN -> {
                c.drawText("Training: $trainingTrick (${Pet.trickMastery(trainingTrick).toInt()}%)",
                    w / 2f, h * 0.07f, text)
                text.textSize = h * 0.028f
                text.color = 0x99FFFFFF.toInt()
                c.drawText(gestureHint(trainingTrick), w / 2f, h * 0.115f, text)
            }
            Mode.HOME -> {
                if (Pet.asleep) c.drawText("${Pet.name} is asleep — tap SLEEP to wake", w / 2f, h * 0.07f, text)
                else if (petGlow > 0.05f) c.drawText("${Pet.name} loves that", w / 2f, h * 0.07f, text)
            }
        }
    }

    // ----------------------------------------------------------------- util

    private inline fun approach(get: () -> Float, set: (Float) -> Unit, target: Float, dt: Float, rate: Float) {
        val cur = get()
        set(cur + (target - cur) * (1f - Math.exp((-rate * dt).toDouble()).toFloat()))
    }

    private fun mul(c: Int, f: Float): Int {
        val a = (c ushr 24) and 0xFF
        val r = (((c shr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * f).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Bounce / celebrate — used when an action makes the dog happy. */
    fun celebrate() {
        hop = unit() * 0.45f
        burst(dx * width, floorY(dd) - unit() * 1.8f, 0, 5)
        Sfx.bark(1.15f)
    }

    fun eatAnimation() {
        tx = 0.13f; td = 0.30f
        act = Act.GOTO
        actTime = 0f; actLimit = 3f
        Sfx.munch()
    }
}
