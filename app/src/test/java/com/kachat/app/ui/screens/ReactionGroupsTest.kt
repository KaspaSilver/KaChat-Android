package com.kachat.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS ReactionsSheet's `grouped`: one section per emoji, most-used first. */
class ReactionGroupsTest {
    private data class R(val emoji: String, val who: String)

    @Test
    fun `most used emoji first, reactors in their own order`() {
        val reactions = listOf(R("👍", "a"), R("❤️", "b"), R("❤️", "c"), R("😂", "d"), R("❤️", "e"))
        val groups = reactionGroups(reactions) { it.emoji }
        assertEquals(listOf("❤️", "👍", "😂"), groups.map { it.first })
        assertEquals(listOf("b", "c", "e"), groups.first().second.map { it.who })
    }

    @Test
    fun `equal counts keep first-appearance order`() {
        val reactions = listOf(R("😮", "a"), R("🙏", "b"))
        assertEquals(listOf("😮", "🙏"), reactionGroups(reactions) { it.emoji }.map { it.first })
    }

    @Test
    fun `no reactions, no sections`() {
        assertEquals(emptyList<Pair<String, List<R>>>(), reactionGroups(emptyList<R>()) { it.emoji })
    }
}
