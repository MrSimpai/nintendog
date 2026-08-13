package com.poc.nintendog

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The kennel: pick a breed, name the pup, take it home. */
class OnboardActivity : Activity() {

    private var chosen = 0
    private val cards = ArrayList<View>()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        Pet.load(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        val pad = Ui.dp(this, 18f)
        root.setPadding(pad, Ui.dp(this, 36f), pad, pad)

        val title = Ui.label(this, "Choose your puppy", 24f)
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        root.addView(title)
        root.addView(Ui.label(this,
            "They will need feeding, water, exercise and company — every single day.",
            13f, Ui.DIM))

        Breed.ALL.forEachIndexed { i, b ->
            val card = breedCard(i, b)
            cards.add(card)
            root.addView(card)
        }

        root.addView(Ui.label(this, "Name", 14f, Ui.DIM).also {
            it.setPadding(0, Ui.dp(this, 14f), 0, Ui.dp(this, 4f))
        })
        val nameField = EditText(this)
        nameField.setText(Pet.name.ifBlank { "Buddy" })
        nameField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        nameField.setTextColor(Ui.TEXT)
        nameField.setHintTextColor(Ui.DIM)
        nameField.background = GradientDrawable().apply {
            cornerRadius = Ui.dp(this@OnboardActivity, 10f).toFloat()
            setColor(Ui.PANEL)
        }
        nameField.setPadding(Ui.dp(this, 12f), Ui.dp(this, 12f), Ui.dp(this, 12f), Ui.dp(this, 12f))
        root.addView(nameField)

        val go = Ui.button(this, "Take them home", Ui.ACCENT) {
            Pet.adopt(this, nameField.text.toString().trim(), chosen)
            Sfx.chime()
            PetWidget.updateAll(this)
            TickReceiver.schedule(this)
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
        go.setTextColor(Color.BLACK)
        go.setPadding(Ui.dp(this, 20f), Ui.dp(this, 14f), Ui.dp(this, 20f), Ui.dp(this, 14f))
        root.addView(go)

        select(0)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun breedCard(index: Int, b: Breed): View {
        val card = Ui.row(this)
        card.setPadding(Ui.dp(this, 12f), Ui.dp(this, 10f), Ui.dp(this, 12f), Ui.dp(this, 10f))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 8f), 0, 0)
        card.layoutParams = lp

        val swatch = View(this)
        swatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(b.coat)
            setStroke(Ui.dp(this@OnboardActivity, 3f), b.patch)
        }
        card.addView(swatch, LinearLayout.LayoutParams(Ui.dp(this, 42f), Ui.dp(this, 42f)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 12f), 0, 0, 0)
        val n = Ui.label(this, b.label, 16f)
        n.setTypeface(n.typeface, android.graphics.Typeface.BOLD)
        col.addView(n)
        col.addView(Ui.label(this, traitLine(b), 12f, Ui.DIM))
        card.addView(col)

        card.setOnClickListener { select(index) }
        return card
    }

    private fun traitLine(b: Breed): String {
        val energy = when { b.zoom > 1.1f -> "very energetic"; b.zoom > 0.98f -> "energetic"; else -> "calm" }
        val smart = when { b.smart > 1.1f -> "learns fast"; b.smart > 0.94f -> "steady learner"; else -> "stubborn" }
        val ears = if (b.floppyEars) "floppy ears" else "pointed ears"
        return "$energy · $smart · $ears"
    }

    private fun select(i: Int) {
        chosen = i
        cards.forEachIndexed { idx, v ->
            v.background = GradientDrawable().apply {
                cornerRadius = Ui.dp(this@OnboardActivity, 14f).toFloat()
                setColor(if (idx == i) 0xFF2C3446.toInt() else Ui.PANEL)
                setStroke(Ui.dp(this@OnboardActivity, 2f),
                    if (idx == i) Ui.ACCENT else Color.TRANSPARENT)
            }
        }
    }

    override fun onBackPressed() {
        // No pet yet means there is nothing to go back to.
        if (Pet.created) super.onBackPressed() else finishAffinity()
    }
}
