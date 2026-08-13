package com.poc.nintendog

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tiny synthesised sound effects. No audio assets — every bark, whine and
 * chime is generated as PCM on the fly, which is plenty for a POC and keeps
 * the APK asset-free.
 */
object Sfx {

    private const val RATE = 22050
    var enabled = true

    private fun play(samples: ShortArray) {
        if (!enabled) return
        try {
            val bytes = samples.size * 2
            @Suppress("DEPRECATION")
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC, RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(bytes, AudioTrack.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)),
                AudioTrack.MODE_STATIC
            )
            track.write(samples, 0, samples.size)
            track.setNotificationMarkerPosition(samples.size)
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) { try { t?.release() } catch (_: Exception) {} }
                    override fun onPeriodicNotification(t: AudioTrack?) {}
                })
            track.play()
        } catch (_: Exception) {
            // Audio is a nicety; never let it break the game.
        }
    }

    /** A bark: noisy formant burst with a fast attack and a growly tail. */
    fun bark(pitch: Float = 1f) {
        val n = (RATE * 0.22f).toInt()
        val s = ShortArray(n)
        val f0 = 210f * pitch
        var noise = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            val env = exp(-t * 16f) * (1f - exp(-t * 260f))
            val sweep = f0 * (1f + 0.6f * exp(-t * 30f))
            noise = noise * 0.6f + (Random.nextFloat() * 2f - 1f) * 0.4f
            val v = (sin(2.0 * PI * sweep * t).toFloat() * 0.55f +
                    sin(2.0 * PI * sweep * 2.02 * t).toFloat() * 0.25f +
                    noise * 0.35f) * env
            s[i] = (v * 10000).toInt().coerceIn(-32000, 32000).toShort()
        }
        play(s)
    }

    /** A rising whine — used when the dog wants something. */
    fun whine() {
        val n = (RATE * 0.5f).toInt()
        val s = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            val env = exp(-t * 3.4f) * (1f - exp(-t * 40f))
            val f = 420f + 340f * t
            val v = (sin(2.0 * PI * f * t).toFloat() * 0.7f +
                    sin(2.0 * PI * f * 1.5 * t).toFloat() * 0.2f) * env
            s[i] = (v * 8000).toInt().coerceIn(-32000, 32000).toShort()
        }
        play(s)
    }

    /** Happy two-note chime for rewards. */
    fun chime() {
        val n = (RATE * 0.45f).toInt()
        val s = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            val f = if (t < 0.15f) 880f else 1320f
            val lt = if (t < 0.15f) t else t - 0.15f
            val env = exp(-lt * 9f)
            val v = sin(2.0 * PI * f * lt).toFloat() * env
            s[i] = (v * 6500).toInt().coerceIn(-32000, 32000).toShort()
        }
        play(s)
    }

    /** Soft crunch for eating / drinking. */
    fun munch() {
        val n = (RATE * 0.3f).toInt()
        val s = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            val beat = ((t * 9f).toInt() % 2 == 0)
            val env = if (beat) exp(-((t * 9f) % 1f) * 14f) else 0f
            val v = (Random.nextFloat() * 2f - 1f) * env
            s[i] = (v * 5000).toInt().coerceIn(-32000, 32000).toShort()
        }
        play(s)
    }
}
