package com.kachat.app.util

/**
 * @mentions are built on .kas (KNS) names - the autocomplete offers a person's primary .kas
 * domain, KaPosts resolves `@name.kas` to its owner, groups swap it for their address. 5.2 turns
 * them off app-wide until they are rebuilt on KaChat's own .kachat names (iOS 08dd836). Every
 * mention entry point checks this; flipping it back on restores them as they were. While off: no
 * @ suggestions, no mentions sent (posts carry none, group text is not encoded), no mention links
 * in posts, and no "only notify if I'm mentioned" (a stored choice is kept, not applied).
 * Mentions other clients already sent still display.
 */
object MentionsFeature {
    const val ENABLED = false
}
