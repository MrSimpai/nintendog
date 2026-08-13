package com.poc.nintendog

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Keeps the pet alive in the background: advances the simulation, refreshes
 * the widget, and nags you when the dog actually needs something.
 */
class TickReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        Pet.load(ctx)
        Pet.tick(ctx)
        PetWidget.updateAll(ctx)
        if (Pet.created && !Pet.gone) maybeNag(ctx)
        schedule(ctx)
    }

    private fun maybeNag(ctx: Context) {
        val need = Pet.neediest() ?: return
        val prefs = ctx.getSharedPreferences("nintendog", Context.MODE_PRIVATE)
        val last = prefs.getLong("lastNag", 0L)
        val now = System.currentTimeMillis()
        // One reminder every two hours at most — a pet, not a spammer.
        if (now - last < 2 * 60 * 60 * 1000L) return
        prefs.edit().putLong("lastNag", now).apply()

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Pet needs", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Reminders when your dog needs care" })
        }
        val tap = PendingIntent.getActivity(
            ctx, 20, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)

        val n = builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("${Pet.moodEmoji()} ${Pet.name}")
            .setContentText(need)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try { nm.notify(1, n) } catch (_: SecurityException) { /* permission not granted */ }
    }

    companion object {
        private const val CHANNEL = "pet_needs"

        /** Re-arms the ~20 minute heartbeat. Inexact keeps it battery-friendly. */
        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, 30, Intent(ctx, TickReceiver::class.java).setAction("com.poc.nintendog.TICK"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val next = System.currentTimeMillis() + 20 * 60 * 1000L
            am.set(AlarmManager.RTC, next, pi)
        }
    }
}
