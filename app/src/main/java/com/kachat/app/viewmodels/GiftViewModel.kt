package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import com.kachat.app.services.GiftClaimState
import com.kachat.app.services.GiftManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Thin Compose-facing wrapper over the singleton [GiftManager]. Every screen that shows the gift
 * button (Profile row, setup-wizard funding step) gets its own instance via `hiltViewModel()`, but
 * they all observe the same [GiftManager.state], so claiming in one place updates all - matching
 * iOS's shared `GiftService.shared`.
 */
@HiltViewModel
class GiftViewModel @Inject constructor(
    private val giftManager: GiftManager
) : ViewModel() {

    val state: StateFlow<GiftClaimState> = giftManager.state

    fun checkEligibility() = giftManager.checkEligibility()

    /** The email to send, with [walletAddress] filled in. */
    fun requestBody(walletAddress: String): String = GiftManager.requestBody(walletAddress)

    /** The mail app was opened with the request: this device's one request is used. */
    fun markRequested() = giftManager.markRequested()

    /** No mail app could take it; the request text was copied instead. */
    fun markNoMailApp() = giftManager.markNoMailApp()
}
