package com.netmonitor.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Plays the alert tones on the ALARM stream. This does not depend on notification-channel
 * sound settings (which many phone makers silence), so the tone is heard whenever the
 * alarm volume is up.
 */
object AlarmPlayer {
    private const val MAX_LOOP_MS = 10 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private val stopper = Runnable { stop() }
    private var player: MediaPlayer? = null

    @Synchronized
    fun play(ctx: Context, res: Int, loop: Boolean) {
        stop()
        val app = ctx.applicationContext
        ensureAudible(app)
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            app.resources.openRawResourceFd(res).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
            mp.isLooping = loop
            mp.setOnCompletionListener { m -> release(m) }
            mp.setOnErrorListener { m, _, _ -> release(m); true }
            mp.prepare()
            mp.start()
            player = mp
            if (loop) main.postDelayed(stopper, MAX_LOOP_MS)
        } catch (_: Exception) {
            player = null
        }
    }

    @Synchronized
    private fun release(m: MediaPlayer) {
        try { m.release() } catch (_: Exception) { }
        if (player === m) player = null
    }

    @Synchronized
    fun stop() {
        main.removeCallbacks(stopper)
        player?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        player = null
    }

    /** An alarm at volume 0 is useless; in that case only, raise it to ~70%. */
    private fun ensureAudible(app: Context) {
        try {
            val am = app.getSystemService(AudioManager::class.java)
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                val v = (am.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.7).toInt()
                am.setStreamVolume(AudioManager.STREAM_ALARM, v.coerceAtLeast(1), 0)
            }
        } catch (_: Exception) { }
    }
}
