package com.kachat.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS 8ffa130 `countText`: the number on a Post Activity card. */
class PostActivityCountTest {
    private val dash = "–"

    @Test
    fun `counter kinds take the larger of counter and loaded, 0 included`() {
        assertEquals("7", postActivityCount(counter = 7, loaded = 3, ready = true, hasMore = true))
        assertEquals("5", postActivityCount(counter = 2, loaded = 5, ready = true, hasMore = false))
        assertEquals("0", postActivityCount(counter = 0, loaded = 0, ready = true, hasMore = false))
    }

    @Test
    fun `counter kinds show the counter before the first page, a dash when it is 0`() {
        assertEquals("4", postActivityCount(counter = 4, loaded = 0, ready = false, hasMore = false))
        assertEquals(dash, postActivityCount(counter = 0, loaded = 0, ready = false, hasMore = false))
    }

    @Test
    fun `list kinds count the list, with a plus while more pages remain`() {
        assertEquals(dash, postActivityCount(counter = null, loaded = 0, ready = false, hasMore = true))
        assertEquals("3+", postActivityCount(counter = null, loaded = 3, ready = true, hasMore = true))
        assertEquals("3", postActivityCount(counter = null, loaded = 3, ready = true, hasMore = false))
        assertEquals("0", postActivityCount(counter = null, loaded = 0, ready = true, hasMore = true))
    }
}
