package com.poc.nintendog

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The home screen: the dog, its needs, and everything you can do for it.
 * All layout is built in code so the POC ships with no XML plumbing.
 */
class MainActivity : Activity(), DogView.Listener {

    private lateinit var dog: DogView
    private lateinit var header: TextView
    private lateinit var coinsView: TextView
    private lateinit var msgView: TextView
    private lateinit var trickRow: LinearLayout
    private lateinit var praiseRow: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var actionRow: LinearLayout
    private val bars = LinkedHashMap<String, StatBar>()
    private val ui = Handler(Looper.getMainLooper())

    private var praiseDeadline = 0L
    private var praiseTrick: String? = null

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        Pet.load(this)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        TickReceiver.schedule(this)
    }

    // ------------------------------------------------------------- UI build

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        val pad = Ui.dp(this, 10f)
        root.setPadding(pad, Ui.dp(this, 24f), pad, pad)

        // --- header
        val head = Ui.row(this)
        header = Ui.label(this, "", 16f)
        header.setTypeface(header.typeface, android.graphics.Typeface.BOLD)
        head.addView(header, LinearLayout.LayoutParams(0, -2, 1f))
        coinsView = Ui.label(this, "", 15f, Ui.ACCENT)
        head.addView(coinsView)
        root.addView(head)

        // --- need meters, two columns
        val stats = Ui.row(this)
        stats.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 4f))
        val colA = LinearLayout(this); colA.orientation = LinearLayout.VERTICAL
        val colB = LinearLayout(this); colB.orientation = LinearLayout.VERTICAL
        for (n in listOf("Food", "Water", "Energy")) {
            val b = StatBar(this, n); bars[n] = b; colA.addView(b)
        }
        for (n in listOf("Clean", "Happy", "Bond")) {
            val b = StatBar(this, n); bars[n] = b; colB.addView(b)
        }
        stats.addView(colA, LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(colB, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(stats)

        // --- the dog
        dog = DogView(this)
        dog.listener = this
        root.addView(dog, LinearLayout.LayoutParams(-1, 0, 1f))

        // --- status ticker
        msgView = Ui.label(this, "", 13f, Ui.DIM)
        msgView.gravity = Gravity.CENTER
        msgView.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
        root.addView(msgView)

        // --- praise window (hidden until the dog performs)
        praiseRow = Ui.panel(this)
        val pr = Ui.row(this)
        pr.addView(Ui.label(this, "Reward? ", 14f))
        pr.addView(Ui.button(this, "Praise!", 0xFF2F6B4F.toInt()) { reward(praise = true, treat = false) })
        pr.addView(Ui.button(this, "Give treat", 0xFF6B542F.toInt()) { reward(praise = true, treat = true) })
        praiseRow.addView(pr)
        praiseRow.visibility = View.GONE
        root.addView(praiseRow)

        // --- trick picker (training mode only)
        trickRow = LinearLayout(this)
        trickRow.orientation = LinearLayout.HORIZONTAL
        for (t in Pet.tricks.keys) {
            trickRow.addView(Ui.button(this, t) {
                dog.trainingTrick = t
                dog.clearTrickPose()
                onMessage(dog.gestureHint(t))
            })
        }
        val trickScroll = HorizontalScrollView(this)
        trickScroll.isHorizontalScrollBarEnabled = false
        trickScroll.addView(trickRow)
        trickScroll.visibility = View.GONE
        root.addView(trickScroll)
        trickScrollRef = trickScroll

        // --- mode switcher
        modeRow = LinearLayout(this)
        modeRow.orientation = LinearLayout.HORIZONTAL
        modeRow.addView(Ui.button(this, "🏠 Room", 0xFF2A3145.toInt()) { setMode(DogView.Mode.HOME) })
        modeRow.addView(Ui.button(this, "🎾 Fetch", 0xFF2A3145.toInt()) { setMode(DogView.Mode.FETCH) })
        modeRow.addView(Ui.button(this, "🎓 Train", 0xFF2A3145.toInt()) { setMode(DogView.Mode.TRAIN) })
        modeRow.addView(Ui.button(this, "🛁 Bath", 0xFF2A3145.toInt()) { setMode(DogView.Mode.WASH) })
        modeRow.addView(Ui.button(this, "🥏 Contest", 0xFF2A3145.toInt()) {
            if (!Pet.hasFrisbee) onMessage("Buy a flying disc in the shop first.")
            else setMode(DogView.Mode.DISC)
        })
        val modeScroll = HorizontalScrollView(this)
        modeScroll.isHorizontalScrollBarEnabled = false
        modeScroll.addView(modeRow)
        root.addView(modeScroll)

        // --- care actions
        actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL
        actionRow.addView(Ui.button(this, "🍖 Feed") { act { Pet.feed() }.also { dog.eatAnimation() } })
        actionRow.addView(Ui.button(this, "💧 Water") { act { Pet.water() } })
        actionRow.addView(Ui.button(this, "🦴 Treat") { act { Pet.treat() }.also { dog.celebrate() } })
        actionRow.addView(Ui.button(this, "🧹 Clean") { act { Pet.cleanPoop() } })
        actionRow.addView(Ui.button(this, "💊 Medicine") { act { Pet.giveMedicine() } })
        actionRow.addView(Ui.button(this, "🌙 Sleep") { act { Pet.toggleSleep() } })
        actionRow.addView(Ui.button(this, "🐾 Walk") { startActivity(Intent(this, WalkActivity::class.java)) })
        actionRow.addView(Ui.button(this, "🛒 Shop") { startActivity(Intent(this, ShopActivity::class.java)) })
        actionRow.addView(Ui.button(this, "🏡 Adopt again", 0xFF3F2A2A.toInt()) {
            startActivity(Intent(this, OnboardActivity::class.java))
        })
        val actScroll = HorizontalScrollView(this)
        actScroll.isHorizontalScrollBarEnabled = false
        actScroll.addView(actionRow)
        root.addView(actScroll)

        return root
    }

    private var trickScrollRef: View? = null

    private fun setMode(m: DogView.Mode) {
        if (Pet.gone) { onMessage("Adopt a new pup first."); return }
        if (Pet.asleep && m != DogView.Mode.HOME) { onMessage("${Pet.name} is asleep."); return }
        dog.mode = m
        trickScrollRef?.visibility = if (m == DogView.Mode.TRAIN) View.VISIBLE else View.GONE
        praiseRow.visibility = View.GONE
    }

    /** Runs a care action, then persists + refreshes everything that shows it. */
    private inline fun act(body: () -> String) {
        val m = body()
        Pet.save(this)
        onMessage(m)
        refresh()
        PetWidget.updateAll(this)
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        Pet.load(this)
        if (!Pet.created) {
            startActivity(Intent(this, OnboardActivity::class.java))
            return
        }
        Pet.tick(this)
        refresh()
        ui.post(pulse)
        PetWidget.updateAll(this)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(pulse)
        Pet.save(this)
        PetWidget.updateAll(this)
    }

    /** Keeps the meters live while you watch, and closes the praise window. */
    private val pulse = object : Runnable {
        override fun run() {
            Pet.tick(this@MainActivity)
            if (praiseTrick != null && System.currentTimeMillis() > praiseDeadline) {
                reward(praise = false, treat = false)
            }
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    private fun refresh() {
        if (!Pet.created) return
        header.text = "${Pet.moodEmoji()}  ${Pet.name} · ${Pet.breed.label} · ${Pet.stage} " +
                "(${Pet.ageDays}d)${if (Pet.sick) " · SICK" else ""}"
        coinsView.text = "🪙 ${Pet.coins}"
        bars["Food"]?.value = Pet.fullness
        bars["Water"]?.value = Pet.hydration
        bars["Energy"]?.value = Pet.energy
        bars["Clean"]?.value = Pet.hygiene
        bars["Happy"]?.value = Pet.happiness
        bars["Bond"]?.value = Pet.affection
        if (msgView.text.isNullOrBlank()) msgView.text = Pet.statusLine()
    }

    // -------------------------------------------------------- DogView events

    override fun onMessage(msg: String) {
        msgView.text = msg
    }

    private var lastSave = 0L

    override fun onChanged() {
        // Petting fires this many times a second — persist at most twice a second.
        val now = System.currentTimeMillis()
        if (now - lastSave > 500) { lastSave = now; Pet.save(this) }
        refresh()
    }

    override fun onTrickPose(trick: String) {
        praiseTrick = trick
        praiseDeadline = System.currentTimeMillis() + 3200
        praiseRow.visibility = View.VISIBLE
        onMessage("${Pet.name} did it! Reward them quickly.")
    }

    private fun reward(praise: Boolean, treat: Boolean) {
        val t = praiseTrick ?: return
        praiseTrick = null
        praiseRow.visibility = View.GONE
        val useTreat = treat && Pet.treats > 0
        if (treat && !useTreat) onMessage("Out of treats! Praised instead.")
        val m = Pet.trained(t, praise, useTreat)
        if (praise) Sfx.chime()
        Pet.save(this)
        onMessage(m)
        refresh()
        dog.clearTrickPose()
        PetWidget.updateAll(this)
    }

    override fun onContestDone(score: Int) {
        val m = Pet.contestFinished(score)
        Pet.save(this)
        Sfx.chime()
        onMessage(m)
        refresh()
        PetWidget.updateAll(this)
    }

    override fun onWashComplete() {
        val m = Pet.finishWash()
        Pet.save(this)
        onMessage(m)
        dog.celebrate()
        refresh()
        setMode(DogView.Mode.HOME)
        PetWidget.updateAll(this)
    }
}
