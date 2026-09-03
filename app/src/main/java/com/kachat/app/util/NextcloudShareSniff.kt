package com.kachat.app.util

/**
 * Recognises a Nextcloud public-share URL in message text, so previews can describe it instead of
 * printing the sender's server address.
 *
 * A share link points at the sender's own server, so a raw URL in a chat-list row or a
 * notification body leaks that address to anyone glancing at the phone - and keeps leaking it
 * after the share is revoked, when the link does not even work any more. The bubble already
 * renders these as media rather than as a URL; previews now agree.
 *
 * Deliberately shape-based (any host, `/s/<token>`): there is no list of known servers to check
 * against, and self-hosting is the whole point.
 */
object NextcloudShareSniff {

    private val PATTERN = Regex("""https://[^\s]+/(?:index\.php/)?s/[A-Za-z0-9_-]{10,}/?""")

    /** The share URL in [text], if it holds one. */
    fun shareUrl(text: String): String? = PATTERN.find(text)?.value

    /**
     * Preview label for text carrying a share link, or null when there is none. Text with a
     * caption keeps the caption and just drops the URL, so the sender's own words survive.
     */
    fun previewLabel(text: String): String? {
        val url = shareUrl(text) ?: return null
        val caption = text.replace(url, "").trim()
        return caption.ifEmpty { "📎 Shared a file" }
    }
}
