package com.kachat.app.util

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * How a chat's live fee preview follows the composer while the user types (iOS 0977a5b,
 * `scheduleFeeEstimate`).
 *
 * The fee follows the payload size, so it doesn't need re-pricing per keystroke - and every new
 * price redrew the composer. While typing, the size is priced only once the user pauses for
 * [PAUSE_MS] - the first text after an empty composer included - and not at all while the
 * message stays within [MIN_BYTE_DELTA] bytes of the size already priced. A forced change (code
 * setting the text, a reply, a fee override, a staged photo or recording, the Nextcloud route...)
 * is priced after a shorter [FORCED_PAUSE_MS]. While a pause is being waited out the pill shows
 * iOS's shimmering "fee: -------- KAS" placeholder ([Preview.estimating]). Emptying the composer
 * clears the fee at once. Preview only: a send always measures its real bytes.
 *
 * One gate per composer. Not thread-safe: [preview] drives it from one coroutine scope.
 */
class TypingFeeGate(private val minByteDelta: Int = MIN_BYTE_DELTA) {

    sealed interface Decision {
        /** Price [bytes] now, dropping any pause being waited out (the composer was emptied). */
        data class Now(val bytes: Int) : Decision
        /** Wait [pauseMs] (restarting any pause already being waited out), then [onPause]. */
        data class AfterPause(val pauseMs: Long) : Decision
        /** Leave the shown fee, and any pause already being waited out, as they are. */
        data object Keep : Decision
    }

    /** The byte size the shown fee is for; null while none is shown (empty, or the first price is
     *  still waiting for its pause). */
    var pricedBytes: Int? = null
        private set

    /** The composer now holds [bytes] bytes; [forced] when it wasn't a plain keystroke. */
    fun onText(bytes: Int, forced: Boolean): Decision {
        val priced = pricedBytes
        return when {
            bytes == 0 -> {
                pricedBytes = null
                Decision.Now(0)
            }
            forced -> Decision.AfterPause(FORCED_PAUSE_MS)
            // The first price waits for a pause too, as iOS's does: nothing is priced per keystroke.
            priced == null -> Decision.AfterPause(PAUSE_MS)
            abs(bytes - priced) < minByteDelta -> Decision.Keep
            else -> Decision.AfterPause(PAUSE_MS)
        }
    }

    /** The pause ran out with the composer at [bytes] bytes: that size is what gets priced. */
    fun onPause(bytes: Int): Int {
        pricedBytes = bytes.takeIf { it > 0 }
        return bytes
    }

    /** One composer state: its text, and a key that, when it changes, forces a re-price. */
    data class Input(val text: String, val forceKey: Any?)

    /** What the fee pill shows: the fee for [bytes], or - while [estimating] - the placeholder. */
    data class Preview(val bytes: Int, val estimating: Boolean)

    companion object {
        const val PAUSE_MS = 600L
        const val FORCED_PAUSE_MS = 200L
        const val MIN_BYTE_DELTA = 24

        private val UNSET = Any()

        /**
         * The fee preview's state as the composer changes - see [TypingFeeGate]. Sizes are the
         * UTF-8 bytes of the trimmed text, as iOS measures them. [pauseMs] stands in for both
         * pauses in tests (a forced change waits the shorter of the two).
         */
        fun preview(inputs: Flow<Input>, pauseMs: Long = PAUSE_MS): Flow<Preview> = channelFlow {
            val gate = TypingFeeGate()
            var latestBytes = 0
            var shownBytes = 0
            var lastForceKey: Any? = UNSET
            var pending: Job? = null
            inputs.collect { input ->
                val bytes = input.text.trim().toByteArray().size
                latestBytes = bytes
                val forced = input.forceKey != lastForceKey
                lastForceKey = input.forceKey
                when (val decision = gate.onText(bytes, forced)) {
                    is Decision.Now -> {
                        pending?.cancel()
                        pending = null
                        shownBytes = decision.bytes
                        send(Preview(decision.bytes, estimating = false))
                    }
                    is Decision.AfterPause -> {
                        pending?.cancel()
                        send(Preview(shownBytes, estimating = true))
                        val wait = if (decision.pauseMs == FORCED_PAUSE_MS) minOf(FORCED_PAUSE_MS, pauseMs) else pauseMs
                        pending = launch {
                            delay(wait)
                            ensureActive()
                            // The text as it stands now, not as it was when the pause began.
                            shownBytes = gate.onPause(latestBytes)
                            send(Preview(shownBytes, estimating = false))
                        }
                    }
                    Decision.Keep -> Unit
                }
            }
        }.distinctUntilChanged()

        /** The priced sizes alone, from [preview]: what the fee is worked out for. */
        fun payloadBytes(previews: Flow<Preview>): Flow<Int> =
            previews.filter { !it.estimating }.map { it.bytes }.distinctUntilChanged()

        /** Whether the pill shows the placeholder, from [preview]. */
        fun estimating(previews: Flow<Preview>): Flow<Boolean> =
            previews.map { it.estimating }.distinctUntilChanged()
    }
}
