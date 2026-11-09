package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.models.liveNameFor
import com.kachat.app.repository.GroupConversation
import com.kachat.app.repository.GroupMessage
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.MessageReply
import com.kachat.app.viewmodels.ChatViewModel

// A group's row in the Chats list and a pinned group's half sheet (iOS GroupChatRow,
// ChatListView.groupActionSheet). Groups share that list since iOS d1844582: see ChatsScreen.

/** A pinned group's long-press half sheet (iOS d1844582 `groupActionSheet(for:)`): read state, pin,
 *  notifications and delete, as square tiles. */
@Composable
internal fun GroupChatActionSheet(
    groupId: String,
    isPinned: Boolean,
    groupConversations: List<GroupConversation>,
    chatViewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    val silentGroups by chatViewModel.groupSilent.collectAsState()
    val convo = groupConversations.firstOrNull { it.group.groupId == groupId } ?: return
    val isSilent = groupId in silentGroups
    TileActionSheet(title = convo.group.name, onDismiss = onDismiss, height = ActionSheetTileMetrics.sheetHeight(tiles = 4)) {
        if (convo.unreadCount > 0) {
            ActionSheetRow(
                icon = Icons.Outlined.MarkEmailRead,
                title = stringResource(R.string.mark_as_read),
                subtitle = stringResource(R.string.clears_the_unread_badge_on_this_group),
            ) {
                onDismiss()
                chatViewModel.markGroupsAsRead(listOf(groupId))
            }
        } else {
            ActionSheetRow(
                icon = Icons.Outlined.MarkEmailUnread,
                title = stringResource(R.string.mark_as_unread),
                subtitle = stringResource(R.string.puts_the_unread_badge_back_so_you),
            ) {
                onDismiss()
                chatViewModel.markGroupsAsUnread(listOf(groupId))
            }
        }
        ActionSheetRow(
            icon = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
            title = stringResource(if (isPinned) R.string.chats_circle_unpin else R.string.pin_to_top_row),
            subtitle = stringResource(if (isPinned) R.string.groups_unpin_top_hint else R.string.groups_pin_top_hint),
        ) {
            onDismiss()
            onTogglePin()
        }
        ActionSheetRow(
            icon = if (isSilent) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
            title = if (isSilent) stringResource(R.string.unsilence) else stringResource(R.string.silence),
            subtitle = if (isSilent) {
                stringResource(R.string.notifications_from_this_group_resume_including_menti)
            } else {
                stringResource(R.string.no_notification_from_this_group_mentions_included)
            },
        ) {
            onDismiss()
            chatViewModel.setGroupSilent(groupId, !isSilent)
        }
        ActionSheetRow(
            icon = Icons.Outlined.Delete,
            title = stringResource(R.string.delete),
            subtitle = stringResource(R.string.removes_this_group_and_its_messages_from),
            tint = LocalAppColors.current.danger,
        ) {
            onDismiss()
            // After the sheet: the alert can't present while it is going down.
            onDelete()
        }
    }
}

/**
 * One group in the Chats list (iOS `GroupChatRow`): photo, name, the latest message (or reaction)
 * and the unread count. Laid out as the chat rows ([ConversationRow]).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun GroupChatRow(
    convo: GroupConversation,
    chatViewModel: ChatViewModel,
    myAddress: String?,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val silentGroups by chatViewModel.groupSilent.collectAsState()
    val latestReactionByGroup by chatViewModel.latestReactionByGroup.collectAsState()
    val memberNamesByAddress by chatViewModel.groupMemberNamesByAddress.collectAsState()
    val group = convo.group
    // Memoized on the roster JSON so scrolling doesn't re-parse the member list per row.
    val members = remember(group.membersJson) { parseGroupMembers(group) }
    // The app's one name rule: your name for them, else their .kachat name, else the short address.
    val resolveName: (String) -> String = { address ->
        memberNamesByAddress.liveNameFor(address)
            ?: members.firstOrNull { it.address == address }?.displayName?.takeIf { it.isNotBlank() }
            ?: com.kachat.app.util.KaspaAddress.shortDisplay(address)
    }
    // A reaction newer than the last message becomes the row's preview - reactions never become
    // messages (they render as a corner pill), so without this the row would keep showing an
    // older message as if nothing happened.
    val reactionPreview = latestReactionByGroup[group.groupId]?.let { reaction ->
        if (convo.lastMessage != null && convo.lastMessage.blockTimestamp >= reaction.blockTimestamp) return@let null
        val targetIsMine = reaction.targetIsOutgoing == true
        if (reaction.reactorAddress == myAddress) {
            if (targetIsMine) stringResource(R.string.you_reacted_to_your_message) else stringResource(R.string.you_reacted_to_a_message)
        } else {
            val name = resolveName(reaction.reactorAddress)
            if (targetIsMine) stringResource(R.string.reacted_to_your_message_2, name) else stringResource(R.string.reacted_to_a_message, name)
        }
    }
    val previewContext = androidx.compose.ui.platform.LocalContext.current
    val preview = remember(convo.lastMessage?.txId, members, memberNamesByAddress) {
        groupMessagePreviewText(previewContext, convo.lastMessage, members, resolveName)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 19.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GroupListAvatar(photoHex = group.photoHex, size = 50.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.name,
                        fontSize = 17.sp,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // Silenced: no banner from this group, mentioned or not.
                    if (group.groupId in silentGroups) {
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = stringResource(R.string.silenced),
                            tint = colors.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                convo.lastMessage?.let { last ->
                    Spacer(Modifier.width(8.dp))
                    Text(chatListDate(last.blockTimestamp, stringResource(R.string.chat_day_yesterday)), fontSize = 12.sp, color = colors.textSecondary, maxLines = 1)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val memberCount = members.size
                Text(
                    text = reactionPreview ?: preview ?: if (memberCount == 1) stringResource(R.string.one_member) else stringResource(R.string.group_info_member_count, memberCount),
                    fontSize = 15.sp,
                    fontStyle = if (reactionPreview == null && preview == null) FontStyle.Italic else FontStyle.Normal,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The rest of the row, so the badge sits at its end without halving the text.
                    modifier = Modifier.weight(1f),
                )
                if (convo.unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    // caption2 bold white on the accent, 6 by 2 padding, a capsule.
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 18.dp)
                            .background(KaspaTeal, CircleShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(convo.unreadCount.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** A group's photo in a circle, or the group symbol on the accent tint when it has none (iOS
 *  `GroupAvatar`). */
@Composable
internal fun GroupListAvatar(photoHex: String?, size: Dp) {
    if (!photoHex.isNullOrEmpty()) {
        GroupAvatar(photoHex = photoHex, size = size)
    } else {
        Box(
            modifier = Modifier.size(size).background(KaspaTeal.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Groups, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(size * 0.46f))
        }
    }
}

/**
 * A one-line preview of a group message (iOS `GroupChatRow`: the reply text, never a link,
 * mentions as names). Media shows as what it is, as the chat rows do.
 */
private fun groupMessagePreviewText(
    context: android.content.Context,
    message: GroupMessage?,
    members: List<com.kachat.app.models.GroupMember>,
    resolve: (String) -> String,
): String? {
    // A reply previews as its own text (iOS MessageReplyCodec.previewText unwraps it).
    val body = message?.content?.let { MessageReply.parseOrNull(it)?.text ?: it } ?: return null
    com.kachat.app.util.InlineMediaSniff.mimeType(body)?.let { mime ->
        return when {
            mime.startsWith("audio/") -> context.getString(R.string.audio_message)
            mime.startsWith("image/") -> context.getString(R.string.photo_2)
            mime.startsWith("video/") -> context.getString(R.string.video_emoji)
            else -> context.getString(R.string.file_emoji)
        }
    }
    return GroupMentionCodec.decodeForDisplay(com.kachat.app.util.NextcloudShareSniff.linkSafePreview(body), members, resolve)
}
