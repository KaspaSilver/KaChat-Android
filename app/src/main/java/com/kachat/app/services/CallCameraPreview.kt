package com.kachat.app.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The caller's own camera while a video call rings out, before there is a call to carry it.
 *
 * A video call shows both tiles from the first second: the contact up top with "calling…", and
 * your own camera below. Until the two phones are in the Talk room there is no WebRTC and so no
 * camera - this stands in for it, driven by the call screen through CameraX.
 *
 * Only one thing may hold the camera at a time, so the handoff is explicit: [stop] does not
 * return until the screen says it has let go, and only then does the call open the camera for
 * real. Without that the picture could be lost for the whole call.
 */
@Singleton
class CallCameraPreview @Inject constructor() {
    private val _active = MutableStateFlow(false)
    /** Whether the call screen should be showing the stand-in camera. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val released = MutableStateFlow(true)

    /** Ring-out on a video call: show the camera. */
    fun start() {
        _active.value = true
    }

    /** Called by the call screen when it actually takes the camera. Until it does there is
     *  nothing to wait for - a call ringing out with the app in the background never showed a
     *  preview at all, and must not be held up pretending otherwise. */
    fun noteHeld() {
        released.value = false
    }

    /**
     * Hands the camera back before the call takes it. Waits for the screen to confirm, and gives
     * up after a moment rather than holding a call up - a call with no local picture is better
     * than a call that never starts.
     */
    suspend fun stop() {
        val wasActive = _active.value
        _active.value = false
        if (!wasActive || released.value) {
            released.value = true
            return
        }
        withTimeoutOrNull(RELEASE_TIMEOUT_MS) { released.first { it } }
        // Letting go is not instant: the camera device closes a moment after the screen unbinds
        // from it, and opening it again too early is how a call ends up with no picture at all.
        kotlinx.coroutines.delay(SETTLE_MS)
        released.value = true
    }

    /** Called by the call screen once it has actually let the camera go. */
    fun noteReleased() {
        released.value = true
    }

    private companion object {
        const val RELEASE_TIMEOUT_MS = 2_000L
        const val SETTLE_MS = 250L
    }
}
