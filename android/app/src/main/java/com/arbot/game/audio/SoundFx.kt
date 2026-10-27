package com.arbot.game.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.EnumMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

enum class Sfx { COIN, ENERGY, POWER_UP, HIT, BOT_DOWN, GAME_OVER, CLICK, PURCHASE, SHUFFLE }

/**
 * Retro sound effects synthesised at start-up (no audio assets, no licensing
 * issues). Each effect is a static AudioTrack that is rewound and replayed.
 */
class SoundFx {
    @Volatile
    var enabled = true

    private val rate = 44_100
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "sfx").apply { isDaemon = true } }
    private val tracks = EnumMap<Sfx, AudioTrack>(Sfx::class.java)

    init {
        executor.execute {
            for (s in Sfx.entries) runCatching { tracks[s] = build(synth(s)) }
        }
    }

    fun play(s: Sfx) {
        if (!enabled) return
        executor.execute {
            val t = tracks[s] ?: return@execute
            runCatching {
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
                t.reloadStaticData()
                t.play()
            }
        }
    }

    fun release() {
        executor.execute {
            tracks.values.forEach { runCatching { it.release() } }
            tracks.clear()
        }
        executor.shutdown()
    }

    private fun build(pcm: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    private class Tone(val f0: Double, val f1: Double, val dur: Double, val square: Boolean = false, val noise: Double = 0.0)

    private fun synth(s: Sfx): ShortArray = when (s) {
        Sfx.COIN -> seq(Tone(988.0, 988.0, 0.05, square = true), Tone(1319.0, 1319.0, 0.11, square = true))
        Sfx.ENERGY -> seq(Tone(420.0, 1250.0, 0.22))
        Sfx.POWER_UP -> seq(
            Tone(523.0, 523.0, 0.07, true), Tone(659.0, 659.0, 0.07, true),
            Tone(784.0, 784.0, 0.07, true), Tone(1047.0, 1047.0, 0.18, true),
        )
        Sfx.HIT -> seq(Tone(160.0, 60.0, 0.35, noise = 0.7))
        Sfx.BOT_DOWN -> seq(Tone(950.0, 180.0, 0.32, square = true, noise = 0.15))
        Sfx.GAME_OVER -> seq(
            Tone(392.0, 392.0, 0.18, true), Tone(330.0, 330.0, 0.18, true),
            Tone(262.0, 262.0, 0.18, true), Tone(196.0, 150.0, 0.45, true),
        )
        Sfx.CLICK -> seq(Tone(1600.0, 1400.0, 0.03))
        Sfx.PURCHASE -> seq(
            Tone(784.0, 784.0, 0.06), Tone(988.0, 988.0, 0.06),
            Tone(1175.0, 1175.0, 0.06), Tone(1568.0, 1568.0, 0.25),
        )
        Sfx.SHUFFLE -> seq(Tone(200.0, 90.0, 0.25, noise = 0.35))
    }

    private fun seq(vararg tones: Tone): ShortArray {
        val out = ArrayList<Short>()
        val rnd = Random(7)
        for (t in tones) {
            val n = (t.dur * rate).toInt()
            var phase = 0.0
            for (i in 0 until n) {
                val p = i.toDouble() / n
                val f = t.f0 + (t.f1 - t.f0) * p
                phase += 2 * PI * f / rate
                val wave = if (t.square) (if (sin(phase) >= 0) 0.6 else -0.6) else sin(phase)
                val sample = wave * (1 - t.noise) + (rnd.nextDouble() * 2 - 1) * t.noise
                val attack = minOf(1.0, i / (rate * 0.004))
                val env = attack * exp(-3.2 * p)
                out += (sample * env * 0.32 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }
}
