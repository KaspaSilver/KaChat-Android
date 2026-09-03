package com.kachat.app.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds every background sync while the setup wizard is on screen.
 *
 * Importing a seed starts a full from-genesis sync of everything the account has ever touched,
 * and that was running underneath the wizard - which is why typing through setup crawled. The
 * hold is taken the moment an import/create arms the wizard and released when the wizard
 * finishes, at which point the initial sync runs behind its own progress sheet.
 *
 * A gate that is never released would strand the account permanently, so every path that arms it
 * also releases it - including the failure paths.
 */
@Singleton
class OnboardingGate @Inject constructor() {

    private val _held = MutableStateFlow(false)
    val held: StateFlow<Boolean> = _held.asStateFlow()

    val isHeld: Boolean get() = _held.value

    fun hold() { _held.value = true }

    fun release() { _held.value = false }

    /** Suspends until the gate is open. Returns immediately when it already is. */
    suspend fun awaitOpen() {
        if (!_held.value) return
        _held.first { !it }
    }
}
