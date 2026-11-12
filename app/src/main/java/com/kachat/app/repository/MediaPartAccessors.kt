package com.kachat.app.repository

import com.kachat.app.models.GroupMessageEntity
import com.kachat.app.models.MessageEntity
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.VoicePartGrouping

/**
 * How the long voice note / multi-part photo grouping (iOS 7c5c22e3 `VoicePartMessage`) reads
 * each kind of row. Parts belong together by owner, tag id and count: the sender in 1:1; the
 * group id and the sender id in a group (`(group id, sender, voicePart.id)`).
 */
val ChatMessagePartAccessors = VoicePartGrouping.Accessors<MessageEntity>(
    txId = { it.id },
    content = { it.plaintextBody },
    owner = { if (it.direction == "sent") "self" else it.contactId.lowercase() },
    // Never a handshake or a payment.
    canBePart = { it.type == MessageProtocol.TYPE_COMM },
    status = { it.deliveryStatus },
    isOutgoing = { it.direction == "sent" },
)

/** `senderIdHex` (SHA-256 of the sender's address) is always there; the address can be
 *  unresolved mid-rotation. */
val GroupMessagePartAccessors = VoicePartGrouping.Accessors<GroupMessage>(
    txId = { it.txId },
    content = { it.content },
    owner = { "${it.groupId.lowercase()}|${it.senderIdHex.lowercase()}" },
    canBePart = { it.senderAddress != GROUP_SYSTEM_SENDER },
    status = { it.deliveryStatus },
    isOutgoing = { it.isOutgoing },
)

/** Stored group rows, with [content] decrypting each (cached) - for unread counts. */
fun groupPartAccessors(content: (GroupMessageEntity) -> String?) = VoicePartGrouping.Accessors<GroupMessageEntity>(
    txId = { it.txId },
    content = content,
    owner = { "${it.groupId.lowercase()}|${it.senderIdHex.lowercase()}" },
    canBePart = { it.senderAddress != GROUP_SYSTEM_SENDER },
    status = { it.deliveryStatus },
    isOutgoing = { it.isOutgoing },
)
