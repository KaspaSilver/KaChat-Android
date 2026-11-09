package com.kachat.app.services

import android.content.Intent
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Proof that an "answer this call" Intent came from KaChat's own ringing notification.
 *
 * [com.kachat.app.MainActivity] is exported, so any installed app can start it with any extras -
 * including the call id of a call that is ringing, which would pick the call up. The Answer
 * button's PendingIntent also carries a random token made once per process; another app never
 * sees that Intent, so it cannot know the token, and an answer without it is ignored. A call does
 * not outlive its process, so neither does the token.
 */
object CallAnswerToken {
    const val EXTRA_TOKEN = "kachat_answer_token"

    internal val token: String by lazy {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        bytes.joinToString("") { "%02x".format(it) }
    }

    /** Marks [intent] as answering [callId]: the call id [IncomingCallNotifier] always attached,
     *  and this process's token. */
    fun putAnswer(intent: Intent, callId: String) {
        intent.putExtra(IncomingCallNotifier.EXTRA_ANSWER_CALL_ID, callId)
        intent.putExtra(EXTRA_TOKEN, token)
    }

    /** Whether [candidate] is this process's token. Compared in constant time. */
    fun matches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray())
}
