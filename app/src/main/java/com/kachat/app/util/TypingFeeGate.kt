package com.kachat.app.util

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * How a chat's live fee preview follows the composer while the user types (iOS 0977a5b).
 *
 * The fee follows the payload size, so it doesn't need re-pricing per keystroke - and every new
 * price redrew the composer. While typing, the size the preview prices updates only once the
 * user pauses for [PAUSE_MS], and not at all while the message stays within [MIN_BYTE_DELTA]
 * bytes of the size already priced. Emptying the composer, the first text after empty, and a
 * forced change (code setting the text, a reply, a fee override, a staged photo or recording,
 * the Nextcloud route...) are priced at once. Preview only: a send always measures its real bytes.
 *
 * One gate per composer. Not thread-safe: [payloadBytes] drives it from one coroutine scope.
 */
class TypingFeeGate(private val minByteDelta: Int = MIN_BYTE_DELTA) {

    sealed interface Decision {
        /** Price [bytes] now, dropping any pause being waited out. */
        data class Now(val bytes: Int) : Decision
        /** Wait for a pause (restarting any pause already being waited out), then [onPause]. */
        data object AfterPause : Decision
        /** Leave the shown fee, and any pause already being waited out, as they are. */
        data object Keep : Decision
    }

    /** The byte size the shown fee is for; null while none is shown (the composer is empty). */
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
            forced || priced == null -> {
                pricedBytes = bytes
                Decision.Now(bytes)
            }
            abs(bytes - priced) < minByteDelta -> Decision.Keep
            else -> Decision.AfterPause
        }
    }

    /** The pause ran out with the composer at [bytes] bytes: that size is what gets priced. */
    fun onPause(bytes: Int): Int {
        pricedBytes = bytes.takeIf { it > 0 }
        return bytes
    }

    /** One composer state: its text, and a key that, when it changes, forces a re-price. */
    data class Input(val text: String, val forceKey: Any?)

    companion object {
        const val PAUSE_MS = 600L
        const val MIN_BYTE_DELTA = 24

        private val UNSET = Any()

        /**
         * The UTF-8 byte size of the composer's text, as the fee preview should price it - see
         * [TypingFeeGate]. The first input is always priced at once.
         */
        fun payloadBytes(inputs: Flow<Input>, pauseMs: Long = PAUSE_MS): Flow<Int> = channelFlow {
            val gate = TypingFeeGate()
            var latestBytes = 0
            var lastForceKey: Any? = UNSET
            var pending: Job? = null
            inputs.collect { input ->
                val bytes = input.text.toByteArray().size
                latestBytes = bytes
                val forced = input.forceKey != lastForceKey
                lastForceKey = input.forceKey
                when (val decision = gate.onText(bytes, forced)) {
                    is Decision.Now -> {
                        pending?.cancel()
                        pending = null
                        send(decision.bytes)
                    }
                    Decision.AfterPause -> {
                        pending?.cancel()
                        pending = launch {
                            delay(pauseMs)
                            ensureActive()
                            // The text as it stands now, not as it was when the pause began.
                            send(gate.onPause(latestBytes))
                        }
                    }
                    Decision.Keep -> Unit
                }
            }
        }.distinctUntilChanged()
    }
}
