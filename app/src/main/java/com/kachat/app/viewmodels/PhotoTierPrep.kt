package com.kachat.app.viewmodels

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kachat.app.util.ImagePrep
import com.kachat.app.util.PhotoPolicy
import com.kachat.app.util.PhotoTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A composer's pending on-chain photo compressed for every [PhotoTier], off the main thread, so
 * the quality sheet can show each one's real fee and the send that follows doesn't compress
 * again (iOS 7c5c22e3 `preparePhotoTiers` / `photoTierFees`). Once per photo.
 */
class PhotoTierPrep(
    private val scope: CoroutineScope,
    private val context: Context,
    private val forGroup: Boolean,
) {
    private val _fees = MutableStateFlow<Map<PhotoTier, Long>>(emptyMap())
    /** The fee each tier's compressed photo will cost, once known (the sheet shows the budget
     *  until then). */
    val fees: StateFlow<Map<PhotoTier, Long>> = _fees.asStateFlow()

    private var uri: Uri? = null
    private var data: Map<PhotoTier, ByteArray> = emptyMap()
    private var job: Job? = null

    /** Starts compressing [photo] for every tier, unless that is already done or running. */
    fun prepare(photo: Uri) {
        if (uri == photo) return
        job?.cancel()
        uri = photo
        data = emptyMap()
        _fees.value = emptyMap()
        val budgets = PhotoTier.values().associateWith { it.maxBytes(forGroup) }
        job = scope.launch {
            val results = try {
                withContext(Dispatchers.Default) { ImagePrep.prepareJpegForTiers(context, photo, budgets.values) }
            } catch (e: Exception) {
                Log.w("PhotoTierPrep", "Could not compress the photo for its tiers", e)
                return@launch
            }
            if (uri != photo) return@launch
            val byTier = budgets.mapNotNull { (tier, budget) -> results[budget]?.let { tier to it } }.toMap()
            data = byTier
            _fees.value = byTier.mapValues { PhotoPolicy.estimatedFeeSompi(it.value.size, forGroup) }
        }
    }

    /** [photo] at [tier]: compressed already for the sheet, else now (off the main thread). */
    suspend fun dataFor(photo: Uri, tier: PhotoTier): ByteArray {
        if (uri == photo) data[tier]?.let { return it }
        return withContext(Dispatchers.Default) { ImagePrep.prepareForTier(context, photo, tier, forGroup).bytes }
    }

    fun clear() {
        job?.cancel()
        job = null
        uri = null
        data = emptyMap()
        _fees.value = emptyMap()
    }
}
