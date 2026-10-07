package com.kachat.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IosToastStateTest {
    private val pending = mutableListOf<Pair<Long, () -> Unit>>()
    private val state = IosToastState { delay, block -> pending += delay to block }

    @Test
    fun `a toast shows and its timer takes it down`() {
        state.show("Exchange ID copied")
        assertEquals("Exchange ID copied", state.current.value?.message)
        assertEquals(IosToastStyle.Success, state.current.value?.style)
        assertEquals(IosToastDuration.STANDARD, pending.single().first)
        pending.single().second()
        assertNull(state.current.value)
    }

    @Test
    fun `a newer toast is not taken down by the older one's timer`() {
        state.show("First")
        state.show("Second", IosToastStyle.Error, IosToastDuration.LONG)
        pending[0].second()
        assertEquals("Second", state.current.value?.message)
        assertEquals(IosToastStyle.Error, state.current.value?.style)
        assertEquals(IosToastDuration.LONG, pending[1].first)
        pending[1].second()
        assertNull(state.current.value)
    }

    @Test
    fun `the host composed last is the one on top`() {
        val root = state.newHostId()
        val sheet = state.newHostId()
        state.addHost(root)
        state.addHost(sheet)
        assertEquals(sheet, state.hosts.value.last())
        state.removeHost(sheet)
        assertEquals(listOf(root), state.hosts.value)
        state.addHost(root)
        assertEquals(listOf(root), state.hosts.value)
    }

    @Test
    fun `a toast until dismissed has no timer and goes when dismissed`() {
        val id = state.show("That tournament has already started.", IosToastStyle.Error, IosToastDuration.UNTIL_DISMISSED)
        assertEquals(0, pending.size)
        state.dismiss(id + 1)
        assertEquals(id, state.current.value?.id)
        state.dismiss(id)
        assertNull(state.current.value)
    }
}
