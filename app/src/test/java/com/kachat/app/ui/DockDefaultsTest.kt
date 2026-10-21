package com.kachat.app.ui

import com.kachat.app.repository.AppSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS 7b39caf: a fresh install's dock is Kaspa Hub, Portfolio, Chats, KaPosts, Profile. */
class DockDefaultsTest {
    private val freshDock = listOf("kaspa_hub", "portfolio", "chats", "kaposts", "profile")

    @Test
    fun `the default dock is the owner's order`() {
        assertEquals(freshDock, AppSettingsRepository.DEFAULT_DOCK_TABS)
        assertEquals(freshDock, resolveDock(AppSettingsRepository.DEFAULT_DOCK_TABS, emptySet()).map { it.route })
    }

    @Test
    fun `the one-time placement on a fresh install lands on the default dock`() {
        // Mirrors AppSettingsRepository.applyPlacementDefaultsIfNeeded with nothing stored.
        @Suppress("DEPRECATION")
        val dock = resolveTabOrder(AppSettingsRepository.DEFAULT_TAB_ORDER, emptySet()).map { it.route }.toMutableList()
        for (route in PINNED_DOCK_ROUTES) {
            if (route !in dock) {
                val position = AppSettingsRepository.DEFAULT_DOCK_TABS.indexOf(route).takeIf { it >= 0 } ?: dock.size
                dock.add(minOf(position, dock.size), route)
            }
        }
        val placedDock = dock.take(MAX_DOCK_ITEMS)
        assertEquals(freshDock, placedDock)
        val placedHub = AppSettingsRepository.DEFAULT_TAB_ORDER.filter { it in ASSIGNABLE_TAB_ROUTES && it !in placedDock }
        assertEquals("cold_storage", placedHub.first())
    }

    @Test
    fun `Cold Storage starts first in Kaspa Hub`() {
        val hub = kaspaHubSections(AppSettingsRepository.DEFAULT_DOCK_TABS, AppSettingsRepository.DEFAULT_HUB_TABS, emptySet())
        assertEquals(Screen.ColdStorage, hub.first())
        assertEquals(false, Screen.KaPosts in hub)
    }

    @Test
    fun `Simple Mode hides KaPosts so the default dock shows four`() {
        assertEquals(
            listOf("kaspa_hub", "portfolio", "chats", "profile"),
            resolveDock(AppSettingsRepository.DEFAULT_DOCK_TABS, emptySet(), childMode = true).map { it.route },
        )
    }

    @Test
    fun `a saved arrangement is never reset to the default`() {
        val saved = listOf("cold_storage", "portfolio", "chats", "kaspa_hub", "profile")
        assertEquals(saved, resolveDock(saved, emptySet()).map { it.route })
    }
}
