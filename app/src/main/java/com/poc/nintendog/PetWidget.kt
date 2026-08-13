package com.poc.nintendog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget: mood face, two need meters and one-tap care actions,
 * so the pet keeps asking for attention without opening the app.
 */
class PetWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Pet.tick(ctx)
        val views = build(ctx)
        for (id in ids) mgr.updateAppWidget(id, views)
    }

    override fun onEnabled(ctx: Context) {
        TickReceiver.schedule(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action != ACTION) return
        Pet.load(ctx)
        Pet.tick(ctx)
        if (!Pet.created || Pet.gone) { updateAll(ctx); return }
        Pet.lastMessage = when (intent.getStringExtra(EXTRA)) {
            "feed" -> Pet.feed()
            "water" -> Pet.water()
            "pet" -> {
                if (Pet.asleep) "${Pet.name} is asleep."
                else { Pet.stroke(6); "You give ${Pet.name} a good scratch behind the ears." }
            }
            else -> ""
        }
        Pet.save(ctx)
        updateAll(ctx)
    }

    companion object {
        const val ACTION = "com.poc.nintendog.WIDGET_ACTION"
        private const val EXTRA = "what"

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PetWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(ctx)
            for (id in ids) mgr.updateAppWidget(id, views)
        }

        private fun build(ctx: Context): RemoteViews {
            Pet.load(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget)

            if (!Pet.created) {
                v.setTextViewText(R.id.w_mood, "🐶")
                v.setTextViewText(R.id.w_name, "No pup yet")
                v.setTextViewText(R.id.w_status, "Tap to adopt one")
                v.setProgressBar(R.id.w_food, 100, 0, false)
                v.setProgressBar(R.id.w_happy, 100, 0, false)
            } else {
                v.setTextViewText(R.id.w_mood, Pet.moodEmoji())
                v.setTextViewText(R.id.w_name, "${Pet.name} · ${Pet.stage}")
                v.setTextViewText(R.id.w_status,
                    Pet.lastMessage.ifBlank { Pet.statusLine() })
                v.setProgressBar(R.id.w_food, 100, Pet.fullness.toInt(), false)
                v.setProgressBar(R.id.w_happy, 100, Pet.happiness.toInt(), false)
            }

            v.setOnClickPendingIntent(R.id.w_mood, openApp(ctx))
            v.setOnClickPendingIntent(R.id.w_name, openApp(ctx))
            v.setOnClickPendingIntent(R.id.w_status, openApp(ctx))
            v.setOnClickPendingIntent(R.id.w_feed, action(ctx, "feed", 11))
            v.setOnClickPendingIntent(R.id.w_water, action(ctx, "water", 12))
            v.setOnClickPendingIntent(R.id.w_pet, action(ctx, "pet", 13))
            return v
        }

        private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 10, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags())

        private fun action(ctx: Context, what: String, code: Int): PendingIntent {
            val i = Intent(ctx, PetWidget::class.java)
            i.action = ACTION
            i.putExtra(EXTRA, what)
            return PendingIntent.getBroadcast(ctx, code, i, flags())
        }
    }
}
