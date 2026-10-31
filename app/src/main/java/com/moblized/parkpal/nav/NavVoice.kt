package com.moblized.parkpal.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.math.roundToInt

/** Distances the way the banner shows them and the way they're spoken (US units). */
object NavText {
    private const val FT_PER_M = 3.28084
    private const val M_PER_MI = 1609.344

    /** "300 ft", "0.4 mi", "12 mi" */
    fun short(meters: Double): String {
        val miles = meters / M_PER_MI
        return when {
            miles < 0.1 -> {
                val ft = meters * FT_PER_M
                val step = if (ft < 300) 50 else 100
                "${((ft / step).roundToInt() * step).coerceAtLeast(step)} ft"
            }
            miles < 10 -> String.format(Locale.US, "%.1f mi", miles)
            else -> "${miles.roundToInt()} mi"
        }
    }

    /** "500 feet", "a quarter mile", "1.5 miles" */
    fun spoken(meters: Double): String {
        val ft = meters * FT_PER_M
        val miles = meters / M_PER_MI
        return when {
            ft < 1000 -> "${((ft / 100).roundToInt() * 100).coerceAtLeast(100)} feet"
            miles < 0.35 -> "a quarter mile"
            miles < 0.6 -> "half a mile"
            miles < 0.85 -> "three quarters of a mile"
            miles < 1.25 -> "1 mile"
            miles < 10 -> String.format(Locale.US, "%.1f miles", miles).replace(".0 ", " ")
            else -> "${miles.roundToInt()} miles"
        }
    }

    /** "18 min", "1 hr 5 min" */
    fun duration(seconds: Double): String {
        val min = (seconds / 60).roundToInt().coerceAtLeast(1)
        return if (min < 60) "$min min" else "${min / 60} hr" + (if (min % 60 > 0) " ${min % 60} min" else "")
    }

    fun speed(metersPerSecond: Float): String = "${(metersPerSecond * 2.23694f).roundToInt()}"
}

/**
 * Speaks directions over music and podcasts, which duck while it talks (like Google Maps), through the
 * navigation audio stream so the car's Bluetooth plays it.
 */
class NavVoice(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var ready = false
    private var pending: String? = null
    private var focus: AudioFocusRequest? = null
    private var counter = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            setUp()
            pending?.let { speak(it) }
            pending = null
        }
    }

    private fun setUp() {
        tts.setAudioAttributes(attributes)
        val locale = Locale.getDefault()
        if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale else tts.language = Locale.US
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (!tts.isSpeaking) releaseFocus()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                releaseFocus()
            }
        })
    }

    fun speak(text: String) {
        if (!ready) {
            pending = text
            return
        }
        requestFocus()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav-${counter++}")
    }

    fun stop() {
        if (ready) tts.stop()
        releaseFocus()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .build()
                .also { focus = it }
            audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun releaseFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focus?.let { audio.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(null)
        }
    }
}
