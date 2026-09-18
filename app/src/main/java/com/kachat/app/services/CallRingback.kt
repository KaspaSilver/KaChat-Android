package com.kachat.app.services

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * What the caller hears while the other phone rings: the standard ringback cadence, played into
 * the call's own audio so it comes out of the earpiece or the speaker like the rest of the call.
 * It starts the moment the opening message is out and stops as soon as the call stops ringing
 * out - answered, declined, or given up on.
 *
 * Android carries the tone itself ([ToneGenerator.TONE_SUP_RINGTONE] is the local ringback
 * supervisory tone, cadence and all), so there is nothing to synthesise and nothing to ship.
 */
class CallRingback {
    private var generator: ToneGenerator? = null

    /** Starts the tone if it is not already playing. Safe to call twice. */
    fun start() {
        if (generator != null) return
        runCatching {
            // The voice-call stream, so the tone follows the call's route and the in-call volume
            // rather than arriving as a notification over the top of it.
            val tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, VOLUME)
            tone.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            generator = tone
        }.onFailure { Log.w(TAG, "Ringback could not start: ${it.message}") }
    }

    fun stop() {
        val tone = generator ?: return
        generator = null
        runCatching {
            tone.stopTone()
            tone.release()
        }
    }

    companion object {
        private const val TAG = "CallRingback"
        /** Out of 100. Quieter than the voice that follows it, as a phone's own ringback is. */
        private const val VOLUME = 70
    }
}
