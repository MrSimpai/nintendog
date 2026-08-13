package com.poc.nintendog

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Pet shop. Coins come from walks and disc contests. */
class ShopActivity : Activity() {

    private lateinit var coins: TextView
    private lateinit var stock: TextView
    private lateinit var msg: TextView

    private data class Item(val key: String, val name: String, val cost: Int, val desc: String)

    private val items = listOf(
        Item("food", "Kibble ×3", 20, "Three full bowls."),
        Item("treats", "Treats ×5", 15, "The fastest way to teach a trick."),
        Item("shampoo", "Shampoo ×2", 25, "Two baths' worth."),
        Item("medicine", "Medicine ×1", 60, "Cures illness."),
        Item("frisbee", "Flying disc", 90, "Unlocks the disc contest.")
    )

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        Pet.load(this)
        Pet.tick(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        val pad = Ui.dp(this, 16f)
        root.setPadding(pad, Ui.dp(this, 30f), pad, pad)

        val t = Ui.label(this, "Pet Shop", 22f)
        t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        root.addView(t)
        coins = Ui.label(this, "", 16f, Ui.ACCENT)
        root.addView(coins)
        stock = Ui.label(this, "", 13f, Ui.DIM)
        root.addView(stock)

        for (it in items) {
            val card = Ui.panel(this)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, Ui.dp(this, 10f), 0, 0)
            card.layoutParams = lp

            val row = Ui.row(this)
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val n = Ui.label(this, it.name, 16f)
            n.setTypeface(n.typeface, android.graphics.Typeface.BOLD)
            col.addView(n)
            col.addView(Ui.label(this, it.desc, 12f, Ui.DIM))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Ui.button(this, "🪙 ${it.cost}", Ui.ACCENT) {
                val m = Pet.buy(it.key)
                Pet.save(this)
                Sfx.chime()
                msg.text = m
                refresh()
                PetWidget.updateAll(this)
            }.also { b -> b.setTextColor(android.graphics.Color.BLACK) })
            card.addView(row)
            root.addView(card)
        }

        msg = Ui.label(this, "", 13f, Ui.TEXT)
        msg.gravity = Gravity.CENTER
        msg.setPadding(0, Ui.dp(this, 14f), 0, Ui.dp(this, 6f))
        root.addView(msg)
        root.addView(Ui.button(this, "Back") { finish() })

        refresh()
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun refresh() {
        coins.text = "🪙 ${Pet.coins} coins"
        stock.text = "You have: ${Pet.food} kibble · ${Pet.treats} treats · " +
                "${Pet.shampoo} shampoo · ${Pet.medicine} medicine" +
                if (Pet.hasFrisbee) " · disc ✓" else ""
    }
}
