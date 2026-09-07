package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import com.kachat.app.services.GlobalNotificationCenterStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Thin Compose bridge to the global notification center (the Profile screen's bell). */
@HiltViewModel
class NotificationCenterViewModel @Inject constructor(
    val store: GlobalNotificationCenterStore,
    /** KaPosts keeps its own count - see [com.kachat.app.services.KaPostsUnseenStore]. */
    val kaPostsUnseen: com.kachat.app.services.KaPostsUnseenStore,
) : ViewModel() {
    init {
        store.reloadIfNeeded()
        kaPostsUnseen.reloadIfNeeded()
    }
}
