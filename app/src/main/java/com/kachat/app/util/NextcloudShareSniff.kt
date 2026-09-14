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

    /** The preview a link-bearing message gets in the chat and group lists. */
    const val SENT_A_LINK = "📎 Sent a link"

    /**
     * The preview a message gets in the chat and group lists: never a link. Any message carrying
     * a web link previews as [SENT_A_LINK], whatever else it says; a message with none previews
     * as itself.
     *
     * A raw URL in a list row is noise at best, and for Nextcloud media it was worse - the
     * message IS a public share link, so the row showed the address of someone's photo to anyone
     * glancing at the phone. Web links only: a message that is a `kaspa:` address must keep
     * reading as one, and an in-app `kachat://` link is not a web address either. Same rule as
     * iOS's `LinkSafePreview`.
     */
    fun linkSafePreview(text: String): String =
        if (containsWebLink(text)) SENT_A_LINK else text

    private fun containsWebLink(text: String): Boolean =
        TextLinkify.findUrls(text).any { !it.uri.startsWith("kachat://", ignoreCase = true) }
}
