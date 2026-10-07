package com.kachat.app.services.kachatnames

import com.kachat.app.services.kachatnames.KachatSocialImageResolver.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FxTwitter's "User not found" no longer hides a real X account (iOS 6ef968a): FxTwitter only
 * supplies a profile; any other answer falls through to X's own page and unavatar.io.
 */
class KachatFxTwitterAnswerTest {
    private val found = """
        {"code":200,"message":"OK","user":{"screen_name":"kaspa",
        "avatar_url":"https://pbs.twimg.com/profile_images/1/a_normal.jpg",
        "banner_url":"https://pbs.twimg.com/profile_banners/1/2",
        "description":"Building on Kaspa"}}
    """.trimIndent()

    @Test
    fun aFoundAccountIsTheAnswer() {
        val p = KachatSocialImageResolver.fxTwitterAnswer(200, found)
        assertEquals("https://pbs.twimg.com/profile_images/1/a_400x400.jpg", p?.avatar)
        assertEquals("https://pbs.twimg.com/profile_banners/1/2/1500x500", p?.banner)
        assertEquals("Building on Kaspa", p?.bio)
    }

    @Test
    fun userNotFoundFallsThroughToXsPage() {
        // FxTwitter says this for existing accounts too (@Curiousbeing99, 2026-10-07)
        assertNull(KachatSocialImageResolver.fxTwitterAnswer(404, """{"code":404,"message":"User not found"}"""))
        assertNull(KachatSocialImageResolver.fxTwitterAnswer(200, """{"code":404,"message":"User not found"}"""))
    }

    @Test
    fun aProfileWithNothingInItFallsThrough() {
        assertNull(KachatSocialImageResolver.fxTwitterAnswer(200, """{"code":200,"user":{"screen_name":"kaspa"}}"""))
    }

    @Test
    fun errorsAndNoAnswerFallThrough() {
        assertNull(KachatSocialImageResolver.fxTwitterAnswer(null, null))
        assertNull(KachatSocialImageResolver.fxTwitterAnswer(429, found))
        assertNull(KachatSocialImageResolver.fxTwitterAnswer(500, "oops"))
        assertNull(KachatSocialImageResolver.fxTwitterAnswer(200, "<html>challenge</html>"))
    }

    @Test
    fun emptyAnswersOfEarlierBuildsAreLookedUpAgain() {
        val kept = Entry(SocialProfile(avatar = "https://pbs.twimg.com/a.jpg"), 1L)
        val cached = mapOf(
            "https://x.com/curiousbeing99" to Entry(SocialProfile(), 2L),
            "https://x.com/kaspa" to kept,
        )
        assertEquals(mapOf("https://x.com/kaspa" to kept), KachatSocialImageResolver.withoutEmptyAnswers(cached))
    }
}
