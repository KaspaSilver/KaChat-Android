package com.kachat.app.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How a .kachat name is shown: "alice.kachat" by default, or "alice" when Settings >
 * Customization > Show .kachat Endings is off (iOS 94ed3a35 `KachatNameLabel`). Display only:
 * typing, copying and anything sent keep working with either form (the codec strips the ending).
 * A name you gave someone yourself (an alias, an Address Book name) never has the ending.
 */
object KachatNameLabel {
    /**
     * Mirrors AppSettingsRepository's KEY_SHOW_KACHAT_NAME_ENDINGS (collected in
     * KaChatApplication, set straight away by the switch). Compose snapshot state, so every name
     * read through [display] redraws when the switch moves (iOS posts settingsDidChange).
     */
    var showsEnding: Boolean by mutableStateOf(true)

    fun display(name: String): String = if (showsEnding) "$name.kachat" else name
}
