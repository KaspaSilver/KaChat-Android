package com.kachat.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Sends that belong to the screen that started them, as each of iOS's send sheets owns its
 * `isSending` and `sentTransaction`. A send screen takes an id for as long as it is presented
 * ([open]), starts its sends under it, and reads back only its own: whether one is in flight
 * ([inFlight]) and how it ended ([results]).
 *
 * Closing the screen mid-send ([close]) leaves the send running - it goes out, or fails, in the
 * background exactly as an iOS send does once its sheet is gone - but its outcome is dropped
 * when it lands, so it can never surface on a later send screen as if it were that screen's own.
 */
class PresentationSends<T> {
    private val open = mutableSetOf<String>()
    private val _inFlight = MutableStateFlow<Set<String>>(emptySet())
    private val _results = MutableStateFlow<Map<String, Result<T>>>(emptyMap())

    /** Ids with a send under way. */
    val inFlight: StateFlow<Set<String>> = _inFlight.asStateFlow()

    /** The finished sends of the screens still presented, by id, until each one [consume]s its. */
    val results: StateFlow<Map<String, Result<T>>> = _results.asStateFlow()

    /** A send screen is up under [id]. */
    @Synchronized
    fun open(id: String) {
        open += id
    }

    /** The screen [id] is gone: anything it started still runs, but its outcome is not kept. */
    @Synchronized
    fun close(id: String) {
        open -= id
        _results.update { it - id }
    }

    /** A send under [id] has started. */
    @Synchronized
    fun start(id: String) {
        _inFlight.update { it + id }
    }

    /** The send under [id] ended with [result]; kept only while its screen is still up. */
    @Synchronized
    fun finish(id: String, result: Result<T>) {
        _inFlight.update { it - id }
        if (id in open) _results.update { it + (id to result) }
    }

    /** The screen [id] has shown its outcome. */
    @Synchronized
    fun consume(id: String) {
        _results.update { it - id }
    }
}
