package org.opentrafficmap.citstogo.drive.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.UUID
import org.opentrafficmap.citstogo.drive.profile.AlertPriority
import org.opentrafficmap.citstogo.drive.profile.AlertTone
import org.opentrafficmap.citstogo.drive.profile.DriverProfile
import org.opentrafficmap.citstogo.drive.safety.SafetyNotification

/**
 * Plays the optional audible warning for a safety alert.
 *
 * The player respects Android audio focus, uses a short non-looping tone per priority, and can speak
 * the alert through the platform TTS engine. Quiet mode and disabled audio are honoured before any
 * focus is requested, so the driver's music is only interrupted for an actual warning.
 */
class AlertAudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var focusRequest: AudioFocusRequest? = null
    private val abandonFocus = Runnable {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    fun initialize(speechEnabled: Boolean) {
        if (!speechEnabled) return
        if (textToSpeech == null) {
            textToSpeech = TextToSpeech(appContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) textToSpeech?.language = Locale.getDefault()
            }
        }
    }

    fun play(notification: SafetyNotification, profile: DriverProfile) {
        if (!profile.audioWarningsEnabled || profile.quietMode) return
        if (profile.alertTone == AlertTone.Silent && !profile.speechEnabled) return
        if (!requestFocus()) return
        val volume = (profile.alertVolume.coerceIn(0f, 1f) * 100).toInt().coerceAtLeast(10)
        if (profile.alertTone != AlertTone.Silent) {
            val tone = when (profile.alertTone) {
                AlertTone.Chime -> ToneGenerator.TONE_PROP_BEEP
                AlertTone.Urgent -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
                AlertTone.SystemDefault -> when (notification.priority) {
                    AlertPriority.Critical -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
                    AlertPriority.Advisory -> ToneGenerator.TONE_PROP_BEEP2
                    AlertPriority.Informational -> ToneGenerator.TONE_PROP_ACK
                }
                AlertTone.Silent -> ToneGenerator.TONE_PROP_ACK
            }
            val durationMs = if (notification.priority == AlertPriority.Critical) 700 else 350
            try {
                val generator = toneGenerator ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume)
                    .also { toneGenerator = it }
                generator.startTone(tone, durationMs)
            } catch (_: RuntimeException) {
                // A device without a tone generator still gets the notification and TTS.
            }
            handler.removeCallbacks(abandonFocus)
            handler.postDelayed(abandonFocus, durationMs.toLong() + 300L)
        }
        if (profile.speechEnabled) {
            initialize(speechEnabled = true)
            val distance = notification.event.distanceM?.let { "${it.toInt()} meters. " }.orEmpty()
            val text = "${notification.priority.name}. ${notification.event.title}. $distance"
            if (ttsReady) {
                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
            }
        }
    }

    fun stop() {
        textToSpeech?.stop()
        toneGenerator?.stopTone()
    }

    fun release() {
        stop()
        toneGenerator?.release()
        toneGenerator = null
        textToSpeech?.shutdown()
        textToSpeech = null
        handler.removeCallbacks(abandonFocus)
        abandonFocus.run()
    }

    private fun requestFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .build()
        val result = audioManager.requestAudioFocus(request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
            return true
        }
        return false
    }
}
