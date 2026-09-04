package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import com.kachat.app.services.CacheManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Hands the singleton [CacheManager] to the Cache settings screen. The screen keeps its own
 *  measurement state (it is re-measured on every visit and after every clear), so this exists
 *  only to get the injected instance across the Compose boundary. */
@HiltViewModel
class CacheViewModel @Inject constructor(
    val cacheManager: CacheManager,
) : ViewModel()
