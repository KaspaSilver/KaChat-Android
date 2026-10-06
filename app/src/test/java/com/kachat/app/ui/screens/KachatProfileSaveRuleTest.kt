package com.kachat.app.ui.screens

import com.kachat.app.services.kachatnames.SocialSource
import com.kachat.app.ui.screens.KachatProfileSaveRule.Field
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edit KaChat Profile's save rule (iOS 5cac6af): only a malformed handle or Linktree username
 * blocks a save; a link whose lookup is still running, unreachable or empty is saved as entered,
 * and the editor notes it.
 */
class KachatProfileSaveRuleTest {
    private val x = KachatSourceInput(SocialSource.Platform.X, "kaspacurrency")
    private val empty = KachatSourceInput()

    private fun fields(avatar: KachatSourceInput, lookup: KachatSocialLookup) = listOf(
        Field(avatar, SocialSource.Kind.AVATAR, lookup),
        Field(empty, SocialSource.Kind.BANNER, KachatSocialLookup.NONE),
        Field(empty, SocialSource.Kind.BIO, KachatSocialLookup.NONE),
    )

    @Test
    fun uncheckedLinksDoNotBlockSaving() {
        for (lookup in listOf(KachatSocialLookup.LOOKING, KachatSocialLookup.UNREACHABLE, KachatSocialLookup.EMPTY, KachatSocialLookup.NONE)) {
            val f = fields(x, lookup)
            assertFalse("$lookup blocked", KachatProfileSaveRule.blocked(f, badLinktree = false))
            assertTrue("$lookup not noted", KachatProfileSaveRule.hasUncheckedLinks(f))
        }
    }

    @Test
    fun foundLinksAreChecked() {
        val f = fields(x, KachatSocialLookup.FOUND)
        assertFalse(KachatProfileSaveRule.blocked(f, badLinktree = false))
        assertFalse(KachatProfileSaveRule.hasUncheckedLinks(f))
    }

    @Test
    fun emptyFieldsAreNeitherBlockedNorUnchecked() {
        val f = fields(empty, KachatSocialLookup.NONE)
        assertFalse(KachatProfileSaveRule.blocked(f, badLinktree = false))
        assertFalse(KachatProfileSaveRule.hasUncheckedLinks(f))
    }

    @Test
    fun malformedHandleBlocks() {
        val bad = KachatSourceInput(SocialSource.Platform.X, "not a handle!")
        assertTrue(bad.isBad(SocialSource.Kind.AVATAR))
        assertTrue(KachatProfileSaveRule.blocked(fields(bad, KachatSocialLookup.NONE), badLinktree = false))
    }

    @Test
    fun malformedLinktreeBlocks() {
        assertTrue(KachatProfileSaveRule.blocked(fields(x, KachatSocialLookup.FOUND), badLinktree = true))
    }
}
