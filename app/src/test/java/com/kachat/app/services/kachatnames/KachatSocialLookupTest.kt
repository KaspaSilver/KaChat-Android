package com.kachat.app.services.kachatnames

import com.kachat.app.services.kachatnames.KachatSocialImageResolver.PageVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a social profile page's answer is read (iOS 683d311): only a page that carries profile
 * tags is a profile; a page with none (a login wall, a challenge or a script shell) is "couldn't
 * look it up", never an empty answer that would read as "this account has no avatar".
 */
class KachatSocialLookupTest {
    private val profilePage = """
        <html><head>
        <meta property="og:image" content="https://pbs.twimg.com/profile_images/1/a_200x200.jpg">
        <meta property="og:description" content="Building on Kaspa">
        </head><body></body></html>
    """.trimIndent()

    @Test
    fun noAnswerIsNotRead() {
        assertEquals(PageVerdict.NotRead("page no answer"), KachatSocialImageResolver.pageVerdict(null, null))
    }

    @Test
    fun goneAccountIsAnEmptyAnswer() {
        assertEquals(PageVerdict.Gone, KachatSocialImageResolver.pageVerdict(404, ""))
        assertEquals(PageVerdict.Gone, KachatSocialImageResolver.pageVerdict(410, "gone"))
    }

    @Test
    fun errorStatusIsNotRead() {
        assertEquals(PageVerdict.NotRead("page HTTP 429"), KachatSocialImageResolver.pageVerdict(429, profilePage))
        assertEquals(PageVerdict.NotRead("page HTTP 403"), KachatSocialImageResolver.pageVerdict(403, ""))
    }

    @Test
    fun pageWithoutProfileTagsIsNotRead() {
        val shell = "<html><head><title>X</title></head><body><script src=\"/main.js\"></script></body></html>"
        val verdict = KachatSocialImageResolver.pageVerdict(200, shell)
        assertTrue(verdict is PageVerdict.NotRead)
        assertTrue((verdict as PageVerdict.NotRead).reason.startsWith("page has no profile tags"))
    }

    @Test
    fun pageWithProfileTagsIsAProfile() {
        assertEquals(PageVerdict.Profile, KachatSocialImageResolver.pageVerdict(200, profilePage))
        // the description alone is enough: an account without an avatar
        val bioOnly = "<meta name=\"description\" content=\"just a bio\">"
        assertEquals(PageVerdict.Profile, KachatSocialImageResolver.pageVerdict(200, bioOnly))
    }
}
