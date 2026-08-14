package com.kachat.app.ui.screens

import com.kachat.app.R
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.models.GroupMember
import com.kachat.app.repository.ChatRepository
import com.kachat.app.repository.GroupMessage
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ChatTimeFormat
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.rememberCameraCaptureLauncher
import com.kachat.app.util.MessageReply
import com.kachat.app.util.TextLinkify
import com.kachat.app.util.VoiceMessage
import com.kachat.app.viewmodels.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Shared, reused across every parseGroupMembers call. Allocating a fresh Gson + TypeToken per call
// (the old behaviour) was measurable: parseGroupMembers runs inside chat-list item builders and per
// message bubble, i.e. on hot recomposition paths - see the memoized call sites.
private val groupMembersGson = com.google.gson.Gson()
private val groupMembersType = object : com.google.gson.reflect.TypeToken<List<GroupMember>>() {}.type

/** Parses a [com.kachat.app.models.GroupEntity]'s stored roster JSON, or empty on failure - shared by every screen below instead of each re-implementing the same try/catch. */
fun parseGroupMembers(group: com.kachat.app.models.GroupEntity): List<GroupMember> {
    return try {
        groupMembersGson.fromJson<List<GroupMember>>(group.membersJson, groupMembersType) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * `@mention` support for group chat - no protocol/wire-format change: a mention is embedded in
 * the plaintext as `@{fullKaspaAddress}` (unambiguous - real addresses always carry a
 * `kaspa:`/`kaspatest:` prefix, so this can't collide with someone typing a literal "@word"),
 * swapped for the mentioned member's resolved display name only at render time. Mirrors iOS's
 * `GroupMentionCodec` exactly.
 */
object GroupMentionCodec {
    fun encodeForSending(text: String, members: List<GroupMember>, resolveDisplayName: (String) -> String): String {
        var result = text
        // Longest name first, so e.g. "@Alice2" doesn't get partially clobbered by a "@Alice" replacement first.
        for (member in members.sortedByDescending { resolveDisplayName(it.address).length }) {
            val name = resolveDisplayName(member.address)
            if (name.isBlank()) continue
            result = result.replace("@$name", "@${member.address}")
        }
        return result
    }

    fun decodeForDisplay(text: String, members: List<GroupMember>, resolveDisplayName: (String) -> String): String {
        var result = text
        for (member in members) {
            val name = resolveDisplayName(member.address)
            if (name.isBlank()) continue
            result = result.replace("@${member.address}", "@$name")
        }
        return result
    }
}

/** More than this many "@" mention matches and the inline suggestion list scrolls instead of
 *  growing taller - see its usage below. */
private const val visibleMentionRows = 5
/** Approximate single-row height (14sp text + 8dp vertical padding on each side) used to size the
 *  mention list's scroll cap to exactly `visibleMentionRows` rows - doesn't need to be
 *  pixel-perfect, it's just clipping the scrollable area. */
private val mentionRowHeight = 38.dp

/**
 * Group chat thread — mirrors 1:1 chat's look (avatars, "+" send-mode menu, photo/audio bubbles
 * via the same [ImageBubble]/[AudioBubble]/[ImageMessage]/[VoiceMessage] components 1:1 chat
 * uses) with one deliberate difference: no in-thread payments (the group protocol has no
 * shared-wallet/escrow concept, same reason broadcast rooms don't support them - "Pay in Kaspa"
 * isn't in the "+" menu here). Kotlin/Compose port of iOS KaChat's `GroupChatDetailView.swift`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatThreadScreen(
    navController: NavController,
    groupId: String,
    chatViewModel: ChatViewModel = hiltViewModel(),
    walletViewModel: com.kachat.app.viewmodels.WalletViewModel = hiltViewModel(),
    settingsViewModel: com.kachat.app.viewmodels.SettingsViewModel = hiltViewModel(),
    connectionViewModel: com.kachat.app.viewmodels.ConnectionViewModel = hiltViewModel()
) {
    val dotColorHex by connectionViewModel.dotColorHex.collectAsState()
    val myAddress by walletViewModel.address.collectAsState()
    val myKnsProfile by walletViewModel.knsProfile.collectAsState()
    // Zero-balance funding gate — same behavior as the 1:1 chat thread (confirmed 0 KAS only,
    // on-entry refresh + 10s re-poll while gated); see GiftClaimUi.kt.
    val fundingGate = rememberZeroBalanceFundingGate()
    val groups by chatViewModel.groups.collectAsState()
    val group = groups.firstOrNull { it.groupId == groupId }
    val messages by chatViewModel.getGroupMessages(groupId).collectAsState(initial = emptyList())
    val groupReactions by chatViewModel.getGroupReactions(groupId).collectAsState(initial = emptyList())
    val groupReactionsByTxId = remember(groupReactions) { groupReactions.groupBy { it.targetTxId } }
    val groupReplyingTo by chatViewModel.groupReplyingTo.collectAsState()
    // Merged contact+KNS maps: group members are usually not saved contacts, so their avatar/KNS
    // name come from the address-keyed KNS cache, not just contact rows (see the VM's
    // groupMemberAvatarsByAddress/groupMemberNamesByAddress). This is what makes group chats show
    // avatars + KNS names instead of raw addresses.
    val contactAvatarsByAddress by chatViewModel.groupMemberAvatarsByAddress.collectAsState()
    val contactPhotoUrisByAddress by chatViewModel.contactPhotoUrisByAddress.collectAsState()
    val contactAliasesByAddress by chatViewModel.groupMemberNamesByAddress.collectAsState()
    val pendingPhotoUri by chatViewModel.groupPendingPhotoUri.collectAsState()
    val voiceRecordingState by chatViewModel.groupVoiceRecordingState.collectAsState()
    val showFeeEstimate by settingsViewModel.showFeeEstimate.collectAsState()
    val estimatedFeeRaw by chatViewModel.groupEstimatedFeeSompi.collectAsState()
    val estimatedFee = if (showFeeEstimate) estimatedFeeRaw else null
    val networkFeeRate by chatViewModel.networkFeeRate.collectAsState()
    val feeRateOverride by chatViewModel.feeRateOverride.collectAsState()
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showComposerMenu by remember { mutableStateOf(false) }
    var composerMenuAnchor by remember { mutableStateOf(Offset.Zero) }
    // "Send from Nextcloud" — only offered when a Nextcloud account is connected (Settings >
    // Storage > Nextcloud). Picking a file sends its public share link as a normal group text
    // message, which recipients' link-preview cards render as tappable media. Matches 1:1 chat.
    val nextcloudAccount by chatViewModel.nextcloud.account.collectAsState()
    var showNextcloudPicker by remember { mutableStateOf(false) }
    // Local-only multi-select for deleting individual messages (never the whole group - see
    // GroupChatInfoScreen's delete for that) - toggled from the top bar's "Select" action.
    var isSelectingMessages by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteMessagesConfirmation by remember { mutableStateOf(false) }
    // @mention inline autocomplete - the text typed after an unclosed "@" at the cursor, or null
    // when the cursor isn't currently in a mention context. See detectMentionQuery/mentionCandidates.
    var mentionQuery by remember { mutableStateOf<String?>(null) }
    val primaryKnsByAddress by chatViewModel.groupMemberPrimaryKnsByAddress.collectAsState()
    val groupMembers = remember(group?.membersJson) { group?.let(::parseGroupMembers) ?: emptyList() }
    val resolveDisplayName: (String) -> String = { address ->
        contactAliasesByAddress[address]?.takeIf { it.isNotBlank() }
            ?: groupMembers.firstOrNull { it.address == address }?.displayName?.takeIf { it.isNotBlank() }
            ?: address.takeLast(10)
    }
    // Members mentionable via the inline "@" autocomplete - only those with an explicit primary
    // KNS domain set (see groupMemberPrimaryKnsByAddress's doc comment for why this can't reuse
    // the general fallback-inclusive resolveDisplayName/knsProfiles), excluding self, filtered
    // by `query` (case-insensitive substring match against the domain) when non-empty.
    val mentionCandidates: (String) -> List<Pair<GroupMember, String>> = { query ->
        val normalizedQuery = query.trim().lowercase()
        groupMembers.mapNotNull { member ->
            if (member.address == myAddress) return@mapNotNull null
            val domain = primaryKnsByAddress[member.address]
            if (domain.isNullOrEmpty()) return@mapNotNull null
            if (normalizedQuery.isNotEmpty() && !domain.lowercase().contains(normalizedQuery)) return@mapNotNull null
            member to domain
        }
    }
    // Range of the unclosed "@query" run ending at the cursor (a collapsed selection), if any -
    // mirrors iOS's ComposerTextView.Coordinator.mentionTokenRange exactly.
    val detectMentionQuery: (TextFieldValue) -> String? = { value ->
        val cursor = value.selection
        if (!cursor.collapsed) {
            null
        } else {
            var start = cursor.start
            while (start > 0 && !value.text[start - 1].isWhitespace()) {
                start--
            }
            if (start < cursor.start && value.text[start] == '@') {
                value.text.substring(start + 1, cursor.start)
            } else {
                null
            }
        }
    }
    val insertMention: (String) -> Unit = { domain ->
        val cursor = draft.selection.start
        var start = cursor
        while (start > 0 && !draft.text[start - 1].isWhitespace()) {
            start--
        }
        val newText = draft.text.replaceRange(start, cursor, "@$domain ")
        draft = TextFieldValue(newText, TextRange(start + domain.length + 2))
        mentionQuery = null
    }
    var showFeeEditor by remember { mutableStateOf(false) }
    var feeEditorInput by remember { mutableStateOf("") }
    val effectiveRate = feeRateOverride?.toDouble() ?: networkFeeRate
    val openFeeEditor: (Long) -> Unit = { currentFeeSompi ->
        feeEditorInput = "%.8f".format(java.util.Locale.US, currentFeeSompi / 100_000_000.0)
        showFeeEditor = true
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    val jumpToReply: (String) -> Unit = { targetId ->
        val index = messages.indexOfFirst { it.txId == targetId }
        if (index >= 0) {
            coroutineScope.launch {
                listState.animateScrollToItem(index)
                highlightedMessageId = targetId
                delay(1200)
                if (highlightedMessageId == targetId) highlightedMessageId = null
            }
        }
    }

    // Swipe-left-to-reveal-timestamps (iMessage-style) — same implementation as 1:1/broadcast
    // rooms (see ChatThreadScreen in Screens.kt), kept in sync with it.
    val revealOffsetPx = remember { Animatable(0f) }
    val maxRevealOffsetPx = with(LocalDensity.current) { 64.dp.toPx() }

    LaunchedEffect(Unit) {
        chatViewModel.refreshUtxos()
        chatViewModel.markGroupRead(groupId)
    }
    DisposableEffect(groupId) {
        chatViewModel.setActiveGroup(groupId)
        onDispose { chatViewModel.setActiveGroup(null) }
    }
    LaunchedEffect(draft.text) {
        chatViewModel.setGroupMessageText(draft.text)
    }

    val micContext = LocalContext.current
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) chatViewModel.startGroupVoiceRecording(groupId)
    }
    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) chatViewModel.setGroupPendingPhoto(uri)
    }
    val startCameraCapture = rememberCameraCaptureLauncher { uri -> chatViewModel.setGroupPendingPhoto(uri) }
    val startVoiceRecordingIfPermitted = {
        if (chatViewModel.voiceRecordingSupported) {
            if (ContextCompat.checkSelfPermission(micContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                chatViewModel.startGroupVoiceRecording(groupId)
            } else {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // The very first population of the list (opening the group chat) jumps instantly instead of
    // animating - matches ChatThreadScreen's identical fix in Screens.kt (the LazyColumn otherwise
    // renders at the top first, and animating from there visibly scrolls through the whole
    // history before settling at the bottom). Only messages arriving while already open animate.
    var hasScrolledToInitialPosition by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (!hasScrolledToInitialPosition) {
                listState.scrollToItem(messages.size - 1)
                hasScrolledToInitialPosition = true
            } else {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    // KNS name/avatar for each member isn't fetched automatically - the roster's own
    // `displayName` is a one-time snapshot from add/join time, so refresh live contact
    // alias/avatar the same way the chat list does on appear (see contactAliasesByAddress/
    // contactAvatarsByAddress above, which this populates).
    LaunchedEffect(group?.groupId) {
        val addresses = group?.let(::parseGroupMembers)?.map { it.address } ?: return@LaunchedEffect
        chatViewModel.refreshKnsProfilesForGroupMembers(addresses)
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        group?.name ?: "Group",
                        color = LocalAppColors.current.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = LocalAppColors.current.textPrimary)
                    }
                },
                actions = {
                    if (isSelectingMessages) {
                        TextButton(onClick = {
                            isSelectingMessages = false
                            selectedMessageIds = emptySet()
                        }) {
                            Text("Cancel", color = KaspaTeal)
                        }
                        IconButton(
                            onClick = { showDeleteMessagesConfirmation = true },
                            enabled = selectedMessageIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = Color(0xFFFF3B30))
                        }
                    } else {
                        // Entry point into select mode is a message's long-press "Select" menu
                        // item, not a toolbar button.
                        val statusColor = Color(dotColorHex)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(LocalAppColors.current.surface, CircleShape)
                                .clickable { navController.navigate("connection_status") },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.size(10.dp).background(statusColor, CircleShape))
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { navController.navigate("group_chat_info/$groupId") }) {
                            Icon(Icons.Default.Info, contentDescription = stringResource(R.string.group_info), tint = LocalAppColors.current.textPrimary)
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        bottomBar = {
            // Composer dims and goes inert while the zero-balance funding gate is up — see
            // Modifier.zeroBalanceComposerGate in GiftClaimUi.kt.
            Column(modifier = Modifier.background(LocalAppColors.current.background).imePadding().navigationBarsPadding().zeroBalanceComposerGate(fundingGate.active)) {
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = Color(0xFFFF3B30),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (pendingPhotoUri != null) {
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            groupFeePill(estimatedFee, onClick = { openFeeEditor(estimatedFee ?: 0L) })
                            groupPhotoPreviewRow(
                                pendingPhotoUri = pendingPhotoUri,
                                onCancel = { chatViewModel.cancelGroupPendingPhoto() },
                                onSend = { chatViewModel.sendPendingGroupPhoto(groupId) }
                            )
                        }
                    } else if (voiceRecordingState.status == ChatViewModel.VoiceRecordingStatus.RECORDING) {
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            groupFeePill(estimatedFee, onClick = { openFeeEditor(estimatedFee ?: 0L) })
                            groupRecordingRow(
                                elapsedMs = voiceRecordingState.elapsedMs,
                                onCancel = { chatViewModel.cancelGroupVoiceRecording() },
                                onSend = { chatViewModel.stopAndSendGroupVoiceRecording(groupId) }
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                        groupReplyingTo?.let { reply ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .background(LocalAppColors.current.surface, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    val replyName = if (reply.senderAddress == myAddress) {
                                        "yourself"
                                    } else {
                                        val address = reply.senderAddress
                                        val member = group?.let(::parseGroupMembers)?.firstOrNull { it.address == address }
                                        contactAliasesByAddress[address]
                                            ?: member?.displayName?.takeIf { it.isNotBlank() }
                                            ?: address?.takeLast(10)
                                            ?: "Unknown"
                                    }
                                    Text(
                                        "Replying to $replyName",
                                        color = KaspaTeal,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        GroupMentionCodec.decodeForDisplay(
                                            VoiceMessage.parseOrNull(reply.content)?.let { "🎤 Audio message" }
                                                ?: ImageMessage.parseOrNull(reply.content)?.let { "📷 Photo" }
                                                ?: MessageReply.parseOrNull(reply.content)?.text
                                                ?: reply.content,
                                            groupMembers,
                                            resolveDisplayName
                                        ),
                                        color = LocalAppColors.current.textSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { chatViewModel.cancelGroupReply() }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel_reply), tint = LocalAppColors.current.textSecondary)
                                }
                            }
                        }
                        if (estimatedFee != null && draft.text.isNotEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                groupFeePill(
                                    estimatedFee,
                                    modifier = Modifier.align(Alignment.Center),
                                    onClick = { openFeeEditor(estimatedFee ?: 0L) }
                                )
                            }
                        }
                        mentionQuery?.let { query ->
                            val candidates = mentionCandidates(query)
                            if (candidates.isNotEmpty()) {
                                // Deliberately no fillMaxWidth() on the Column or each row's Text -
                                // that made the box (and every row) stretch to the full available
                                // width regardless of how short the names actually were. Removing
                                // it alone wasn't enough though: HorizontalDivider's own default
                                // modifier is `fillMaxWidth()` internally, so the divider between
                                // rows was still forcing the Column back out to full width on its
                                // own. `width(IntrinsicSize.Max)` is Compose's built-in mechanism
                                // for exactly this - it measures the Column's width from its
                                // widest child's own natural content width (the longest name),
                                // then proposes *that* width to every child during actual layout,
                                // so a `fillMaxWidth()` child like the divider fills that measured
                                // width instead of the screen.
                                //
                                // Height caps at exactly `visibleMentionRows` rows' worth, then
                                // scrolls for the rest - unlike SwiftUI's ScrollView, Compose's
                                // Column+verticalScroll already sizes to its actual content up to
                                // that cap rather than always growing to fill it, so this is safe
                                // to apply unconditionally (no empty-space regression for a short
                                // list, matching iOS's explicit >5-rows-only ScrollView gate).
                                Column(
                                    modifier = Modifier
                                        .width(IntrinsicSize.Max)
                                        .heightIn(max = mentionRowHeight * visibleMentionRows)
                                        .padding(bottom = 8.dp)
                                        .background(LocalAppColors.current.surface, RoundedCornerShape(14.dp))
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    candidates.forEachIndexed { index, (_, domain) ->
                                        Text(
                                            domain,
                                            color = LocalAppColors.current.textPrimary,
                                            fontSize = 14.sp,
                                            modifier = Modifier
                                                .clickable { insertMention(domain) }
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                        if (index != candidates.lastIndex) {
                                            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                        }
                                    }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            TextField(
                                value = draft,
                                onValueChange = { newValue ->
                                    draft = newValue
                                    mentionQuery = detectMentionQuery(newValue)
                                },
                                placeholder = { Text(stringResource(R.string.message), color = Color.DarkGray) },
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp)),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = LocalAppColors.current.surface,
                                    unfocusedContainerColor = LocalAppColors.current.surface,
                                    focusedTextColor = LocalAppColors.current.textPrimary,
                                    unfocusedTextColor = LocalAppColors.current.textPrimary,
                                    cursorColor = KaspaTeal,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                // Quick-access camera, replacing what used to be a "Camera" entry
                                // in the "+" menu - living right in the message bubble instead
                                // since it's the most common non-text action. Matches 1:1 chat.
                                trailingIcon = {
                                    IconButton(onClick = { startCameraCapture() }) {
                                        Icon(
                                            Icons.Default.CameraAlt,
                                            contentDescription = stringResource(R.string.camera),
                                            tint = LocalAppColors.current.textSecondary
                                        )
                                    }
                                },
                                maxLines = 5
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            if (draft.text.isEmpty()) {
                                Box(
                                    modifier = Modifier.onGloballyPositioned { coords ->
                                        composerMenuAnchor = coords.positionInWindow()
                                    }
                                ) {
                                    ChatActionButton(Icons.Default.Add, onClick = { showComposerMenu = true })
                                }
                                if (showComposerMenu) {
                                    CenteredOptionsMenu(onDismissRequest = { showComposerMenu = false }, anchor = composerMenuAnchor) {
                                        PopupMenuRow(Icons.Default.Image, stringResource(R.string.send_photo_2)) {
                                            showComposerMenu = false
                                            photoPickerLauncher.launch("image/*")
                                        }
                                        if (nextcloudAccount != null) {
                                            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                            PopupMenuRow(Icons.Default.Cloud, "Send from Nextcloud") {
                                                showComposerMenu = false
                                                showNextcloudPicker = true
                                            }
                                        }
                                        HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                        PopupMenuRow(Icons.Default.Mic, stringResource(R.string.send_audio_message)) {
                                            showComposerMenu = false
                                            startVoiceRecordingIfPermitted()
                                        }
                                    }
                                }
                                if (showNextcloudPicker) {
                                    NextcloudPickerDialog(
                                        service = chatViewModel.nextcloud,
                                        onDismiss = { showNextcloudPicker = false },
                                        onPick = { link ->
                                            showNextcloudPicker = false
                                            // Stage the link in the composer for review instead of
                                            // auto-sending — the user presses send themselves.
                                            val current = draft.text.trim()
                                            val staged = if (current.isEmpty()) link else "$current $link"
                                            draft = TextFieldValue(staged, TextRange(staged.length))
                                        }
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        val text = GroupMentionCodec.encodeForSending(draft.text.trim(), groupMembers) { address ->
                                            primaryKnsByAddress[address] ?: ""
                                        }
                                        if (text.isEmpty()) return@IconButton
                                        draft = TextFieldValue("")
                                        errorMessage = null
                                        chatViewModel.sendGroupMessage(text, groupId) { error -> errorMessage = error }
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(KaspaTeal, CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = stringResource(R.string.send),
                                        tint = Color.Black,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        coroutineScope.launch {
                            revealOffsetPx.snapTo((revealOffsetPx.value + delta).coerceIn(-maxRevealOffsetPx, 0f))
                        }
                    },
                    onDragStopped = { coroutineScope.launch { revealOffsetPx.animateTo(0f) } }
                )
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(messages, key = { _, msg -> msg.txId }) { index, message ->
                    if (index == 0 || !ChatTimeFormat.isSameDay(messages[index - 1].blockTimestamp, message.blockTimestamp)) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Surface(color = LocalAppColors.current.surface, shape = RoundedCornerShape(12.dp)) {
                                Text(
                                    ChatTimeFormat.formatDateDivider(message.blockTimestamp),
                                    color = LocalAppColors.current.textSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    Box {
                        GroupMessageBubble(
                            message = message,
                            group = group,
                            avatarUrl = message.senderAddress?.let { contactAvatarsByAddress[it] },
                            avatarPhotoUri = message.senderAddress?.let { contactPhotoUrisByAddress[it] },
                            liveAlias = message.senderAddress?.let { contactAliasesByAddress[it] },
                            myAddress = myAddress,
                            myAvatarUrl = myKnsProfile?.avatarUrl,
                            navController = navController,
                            onRetry = { chatViewModel.retryGroupMessage(groupId, message.content) },
                            onReply = { chatViewModel.startGroupReplyTo(message) },
                            reactions = groupReactionsByTxId[message.txId] ?: emptyList(),
                            onReact = { emoji ->
                                val existing = groupReactionsByTxId[message.txId]?.find { it.reactorAddress == myAddress }
                                val action = if (existing?.emoji == emoji) "remove" else "add"
                                chatViewModel.sendGroupReaction(groupId, message.txId, emoji, action)
                            },
                            onRetryReaction = { reaction ->
                                chatViewModel.retryGroupReaction(groupId, reaction.targetTxId, reaction.emoji, reaction.failedAction ?: "add")
                            },
                            onJumpToReply = jumpToReply,
                            isHighlighted = message.txId == highlightedMessageId,
                            resolveMentionName = resolveDisplayName,
                            isMuted = message.senderAddress?.let { chatViewModel.isGroupMemberMuted(groupId, it) } ?: false,
                            onMute = { address -> chatViewModel.muteGroupMember(groupId, address) },
                            onUnmute = { address -> chatViewModel.unmuteGroupMember(groupId, address) },
                            onHide = { address -> chatViewModel.hideGroupMember(groupId, address) },
                            revealOffsetPx = revealOffsetPx,
                            maxRevealOffsetPx = maxRevealOffsetPx,
                            onSelect = {
                                isSelectingMessages = true
                                selectedMessageIds = selectedMessageIds + message.txId
                            }
                        )
                        // Same selection-mode tap catcher as 1:1 chat's message list.
                        if (isSelectingMessages) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable {
                                        selectedMessageIds = if (message.txId in selectedMessageIds) {
                                            selectedMessageIds - message.txId
                                        } else {
                                            selectedMessageIds + message.txId
                                        }
                                    }
                            ) {
                                Icon(
                                    imageVector = if (message.txId in selectedMessageIds) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (message.txId in selectedMessageIds) KaspaTeal else Color.Gray
                                )
                            }
                        }
                    }
                }
            }

            // Zero-balance funding gate card — same as the 1:1 thread: composer dimmed below,
            // received messages stay readable around it, gone the moment the chatting balance
            // confirms as > 0.
            if (fundingGate.active) {
                ZeroBalanceFundingCard(
                    walletAddress = fundingGate.chattingAddress,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            }
        }
    }

    if (showFeeEditor) {
        AlertDialog(
            onDismissRequest = { showFeeEditor = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.adjust_network_fee), color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.if_the_network_is_busy_a),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = feeEditorInput,
                        onValueChange = { feeEditorInput = it },
                        label = { Text(stringResource(R.string.fee_kas)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.textSecondary,
                            focusedLabelColor = KaspaTeal,
                            unfocusedLabelColor = LocalAppColors.current.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val kas = feeEditorInput.toDoubleOrNull()
                    val currentFeeSompi = estimatedFeeRaw ?: 0L
                    if (kas != null && kas > 0 && currentFeeSompi > 0 && effectiveRate > 0) {
                        val impliedMass = currentFeeSompi / effectiveRate
                        val desiredFeeSompi = Math.round(kas * 100_000_000.0)
                        chatViewModel.setFeeRateOverride(kotlin.math.ceil(desiredFeeSompi / impliedMass).toLong())
                    } else {
                        chatViewModel.setFeeRateOverride(null)
                    }
                    showFeeEditor = false
                }) {
                    Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { chatViewModel.setFeeRateOverride(null); showFeeEditor = false }) {
                        Text(stringResource(R.string.use_default), color = LocalAppColors.current.textSecondary)
                    }
                    TextButton(onClick = { showFeeEditor = false }) {
                        Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                    }
                }
            }
        )
    }

    if (showDeleteMessagesConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteMessagesConfirmation = false },
            containerColor = LocalAppColors.current.surface,
            title = {
                Text(
                    "Delete ${selectedMessageIds.size} Message${if (selectedMessageIds.size == 1) "" else "s"}?",
                    color = LocalAppColors.current.textPrimary
                )
            },
            text = {
                Text(
                    "This only deletes the message from this device - other members still have their own copy, and the encrypted transaction remains permanently on the Kaspa blockchain, visible to anyone but unreadable without your keys. This cannot be undone.",
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    chatViewModel.deleteGroupMessages(groupId, selectedMessageIds)
                    showDeleteMessagesConfirmation = false
                    isSelectingMessages = false
                    selectedMessageIds = emptySet()
                }) {
                    Text("Delete", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteMessagesConfirmation = false }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}

/** "fee: N KAS" pill above the composer, matching 1:1/broadcast's identical display - tappable to adjust, same "Adjust Network Fee" dialog as 1:1/broadcast (see [GroupChatThreadScreen]'s showFeeEditor state). */
@Composable
private fun groupFeePill(feeSompi: Long?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    if (feeSompi == null) return
    Surface(
        color = LocalAppColors.current.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.padding(bottom = 8.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }
    ) {
        Text(
            text = "fee: ${ChatRepository.formatKas(feeSompi)} KAS",
            color = KaspaTeal,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textDecoration = if (onClick != null) androidx.compose.ui.text.style.TextDecoration.Underline else null,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun groupPhotoPreviewRow(pendingPhotoUri: android.net.Uri?, onCancel: () -> Unit, onSend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(LocalAppColors.current.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cancel_photo), tint = Color(0xFFFF3B30))
        }
        val thumbnailContext = LocalContext.current
        val thumbnail = remember(pendingPhotoUri) {
            pendingPhotoUri?.let { uri ->
                try {
                    thumbnailContext.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        }
        Text(stringResource(R.string.photo), color = LocalAppColors.current.textPrimary, modifier = Modifier.weight(1f))
        IconButton(
            onClick = onSend,
            modifier = Modifier.size(32.dp).background(KaspaTeal, CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.send_photo),
                tint = Color.Black,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun groupRecordingRow(elapsedMs: Long, onCancel: () -> Unit, onSend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(LocalAppColors.current.surface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cancel_recording), tint = Color(0xFFFF3B30))
        }
        Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
        Text(
            text = "Recording... ${formatRecordingElapsed(elapsedMs)}",
            color = LocalAppColors.current.textPrimary,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onSend,
            modifier = Modifier.size(40.dp).background(KaspaTeal, CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.send),
                tint = Color.Black,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupMessageBubble(
    message: GroupMessage,
    group: com.kachat.app.models.GroupEntity?,
    avatarUrl: String?,
    /** Sender's device address-book photo, when they're a linked phone contact — the no-KNS-avatar fallback. */
    avatarPhotoUri: String? = null,
    liveAlias: String?,
    myAddress: String?,
    myAvatarUrl: String?,
    navController: NavController,
    onRetry: () -> Unit,
    onReply: () -> Unit = {},
    reactions: List<com.kachat.app.models.ReactionEntity> = emptyList(),
    onReact: (String) -> Unit = {},
    /** Retries the local user's failed reaction on this message (see its `failedAction`). */
    onRetryReaction: (com.kachat.app.models.ReactionEntity) -> Unit = {},
    /** Tapping the reply quote (if any) jumps to and highlights the original message. */
    onJumpToReply: (String) -> Unit = {},
    isHighlighted: Boolean = false,
    resolveMentionName: (String) -> String = { it.takeLast(10) },
    isMuted: Boolean = false,
    onMute: (String) -> Unit = {},
    onUnmute: (String) -> Unit = {},
    onHide: (String) -> Unit = {},
    revealOffsetPx: Animatable<Float, AnimationVector1D>,
    maxRevealOffsetPx: Float,
    /** Enters the chat's message multi-select mode with this message pre-selected - null disables
     *  the "Select" long-press menu option entirely. Mirrors [MessageBubble]'s onSelect. */
    onSelect: (() -> Unit)? = null
) {
    val isSent = message.isOutgoing
    // Prefers the live contact alias (kept current by refreshKnsProfilesForGroupMembers, e.g. a
    // KNS name resolved after the member was added) over the roster's own `displayName`, which is
    // only ever a one-time snapshot taken at add/join time and never updated afterward.
    // Parse the roster ONCE per bubble, memoized on the roster JSON (not the whole GroupEntity,
    // which gets a new instance on every group update e.g. lastActivity - that invalidated all
    // three lookups on every incoming message). senderName/replySenderName reuse this instead of
    // each re-parsing.
    val groupMembersForMentions = remember(group?.membersJson) { group?.let(::parseGroupMembers) ?: emptyList() }
    val senderName = remember(message.senderAddress, groupMembersForMentions, liveAlias) {
        val address = message.senderAddress ?: return@remember "Unknown"
        if (!liveAlias.isNullOrBlank()) return@remember liveAlias
        val member = groupMembersForMentions.firstOrNull { it.address == address }
        member?.displayName?.takeIf { it.isNotBlank() } ?: address.takeLast(10)
    }
    val replyContent = remember(message.content) { MessageReply.parseOrNull(message.content) }
    val displayContent = remember(replyContent, message.content, groupMembersForMentions) {
        GroupMentionCodec.decodeForDisplay(replyContent?.text ?: message.content, groupMembersForMentions, resolveMentionName)
    }
    val replySenderName = remember(replyContent, groupMembersForMentions, myAddress) {
        val reply = replyContent ?: return@remember null
        if (reply.replyToSender == myAddress) return@remember "You"
        val member = groupMembersForMentions.firstOrNull { it.address == reply.replyToSender }
        member?.displayName?.takeIf { it.isNotBlank() } ?: reply.replyToSender.takeLast(10)
    }
    val voiceContent = remember(displayContent) { VoiceMessage.parseOrNull(displayContent) }
    val imageContent = remember(displayContent) { if (voiceContent == null) ImageMessage.parseOrNull(displayContent) else null }
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    var showMenu by remember { mutableStateOf(false) }
    var showQuickReactionBar by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    val canRetry = isSent && message.deliveryStatus == "failed"
    val highlightColor by animateColorAsState(
        if (isHighlighted) KaspaTeal.copy(alpha = 0.18f) else Color.Transparent,
        label = "messageHighlight"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(highlightColor, RoundedCornerShape(12.dp))
    ) {
        Text(
            text = remember(message.blockTimestamp) { ChatTimeFormat.formatMessageTime(message.blockTimestamp) },
            color = LocalAppColors.current.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier
                .align(if (isSent) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 12.dp)
                .alpha((-revealOffsetPx.value / maxRevealOffsetPx).coerceIn(0f, 1f))
        )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(revealOffsetPx.value.toInt(), 0) },
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isSent) {
            groupAvatarButton(
                address = message.senderAddress,
                avatarUrl = avatarUrl,
                photoUri = avatarPhotoUri,
                fallbackText = senderName,
                navController = navController,
                isMuted = isMuted,
                onMute = onMute,
                onUnmute = onUnmute,
                onHide = onHide
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isSent) Alignment.End else Alignment.Start,
            modifier = Modifier.onGloballyPositioned { coords ->
                menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
            }
        ) {
            // Always shown now (own messages say "You"), matching broadcast rooms - previously
            // only incoming messages got a name label at all, so an outgoing message had no
            // sender indicator next to its avatar.
            Text(
                text = if (isSent) "You" else senderName,
                color = KaspaTeal,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 2.dp)
            )

            if (replyContent != null) {
                Surface(
                    color = LocalAppColors.current.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .widthIn(max = 240.dp)
                        .clickable { onJumpToReply(replyContent.replyToId) }
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            replySenderName ?: replyContent.replyToSender.takeLast(10),
                            color = KaspaTeal,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            GroupMentionCodec.decodeForDisplay(replyContent.replyToPreview, groupMembersForMentions, resolveMentionName),
                            color = LocalAppColors.current.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            when {
                voiceContent != null -> AudioBubble(voiceContent = voiceContent, isSent = isSent, onLongPress = { showMenu = true }, onDoubleClick = { showQuickReactionBar = true })
                imageContent != null -> ImageBubble(imageContent = imageContent, isSent = isSent, onLongPress = { showMenu = true }, onDoubleClick = { showQuickReactionBar = true }, senderDisplayName = senderName)
                else -> {
                    var groupTextLayoutResult by remember(displayContent) { mutableStateOf<TextLayoutResult?>(null) }
                    // Sent bubbles are teal with black text/links for contrast - matches 1:1 chat's
                    // MessageBubble (Screens.kt) treatment of the same case.
                    val groupLinkColor = if (isSent) Color.Black else KaspaTeal
                    val annotatedGroupBody = remember(displayContent, isSent) {
                        buildAnnotatedString {
                            append(displayContent)
                            for (match in TextLinkify.findUrls(displayContent)) {
                                addStyle(SpanStyle(color = groupLinkColor, textDecoration = TextDecoration.Underline), match.range.first, match.range.last + 1)
                                addStringAnnotation("URL", match.uri, match.range.first, match.range.last + 1)
                            }
                        }
                    }
                    Surface(
                        color = if (isSent) KaspaTeal else LocalAppColors.current.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Text(
                            text = annotatedGroupBody,
                            color = if (isSent) Color.Black else LocalAppColors.current.textPrimary,
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .pointerInput(annotatedGroupBody) {
                                    detectTapGestures(
                                        onLongPress = { showMenu = true },
                                        onDoubleTap = { showQuickReactionBar = true },
                                        onTap = { offset ->
                                            val layout = groupTextLayoutResult ?: return@detectTapGestures
                                            val charOffset = layout.getOffsetForPosition(offset)
                                            annotatedGroupBody.getStringAnnotations("URL", charOffset, charOffset)
                                                .firstOrNull()?.let { uriHandler.openUri(it.item) }
                                        }
                                    )
                                },
                            onTextLayout = { groupTextLayoutResult = it }
                        )
                    }
                    TextLinkify.findUrls(displayContent).firstOrNull()?.let { match ->
                        LinkPreviewCard(url = match.uri, txId = message.txId, onSelect = onSelect, onDoubleTap = { showQuickReactionBar = true })
                    }
                }
            }

            if (isSent) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (message.deliveryStatus) {
                        "failed" -> {
                            Icon(Icons.Default.Error, contentDescription = stringResource(R.string.failed_to_send), tint = Color(0xFFFF3B30), modifier = Modifier.size(12.dp))
                            // Tappable "Retry" next to the red error icon (also in the long-press
                            // menu) so a failed send can be resent with one tap.
                            if (canRetry) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.retry),
                                    color = Color(0xFFFF3B30),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { onRetry() }
                                )
                            }
                        }
                        "pending" -> Icon(Icons.Default.Schedule, contentDescription = stringResource(R.string.sending), tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(12.dp))
                        else -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CD964), modifier = Modifier.size(12.dp))
                    }
                }
            }

            if (showMenu) {
                CenteredOptionsMenu(onDismissRequest = { showMenu = false }, anchor = menuAnchor) {
                    PopupMenuRow(Icons.AutoMirrored.Filled.Reply, stringResource(R.string.reply)) {
                        onReply()
                        showMenu = false
                    }
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(Icons.Default.ContentCopy, stringResource(R.string.copy_message)) {
                        clipboardManager.setText(AnnotatedString(displayContent))
                        showMenu = false
                    }
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(Icons.Default.Public, stringResource(R.string.view_in_explorer)) {
                        uriHandler.openUri(com.kachat.app.models.KaspaExplorer.default.txUrl(message.txId))
                        showMenu = false
                    }
                    if (canRetry) {
                        HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                        PopupMenuRow(Icons.Default.Refresh, stringResource(R.string.retry_send)) {
                            onRetry()
                            showMenu = false
                        }
                    }
                    if (onSelect != null) {
                        HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                        PopupMenuRow(Icons.Default.CheckCircle, "Select") {
                            onSelect()
                            showMenu = false
                        }
                    }
                }
            }

            if (showQuickReactionBar) {
                val settingsViewModel: com.kachat.app.viewmodels.SettingsViewModel = hiltViewModel()
                val quickReactionEmojis by settingsViewModel.quickReactionEmojis.collectAsState()
                QuickReactionBar(
                    onDismissRequest = { showQuickReactionBar = false },
                    anchor = menuAnchor,
                    onReact = onReact,
                    onReply = onReply,
                    emojis = quickReactionEmojis
                )
            }

            if (reactions.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    ReactionPill(
                        reactions = reactions,
                        myAddress = myAddress,
                        modifier = Modifier
                            .align(if (isSent) Alignment.CenterStart else Alignment.CenterEnd)
                            .offset(y = 10.dp)
                    )
                }
                // The pill is offset ~10dp down (offset reserves no layout space), so reserve it
                // here - otherwise the pill overlaps the content/next message below it.
                Spacer(modifier = Modifier.height(14.dp))
            }

            // A reaction (not the message) that failed to send: red "Retry" under the message,
            // paired with the error icon on the reaction pill. Shown for reactions on any message.
            reactions.firstOrNull { it.deliveryStatus == "failed" }?.let { failedReaction ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.retry),
                        color = Color(0xFFFF3B30),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(if (isSent) Alignment.CenterStart else Alignment.CenterEnd)
                            .padding(top = 2.dp)
                            .clickable { onRetryReaction(failedReaction) }
                    )
                }
            }
        }

        if (isSent) {
            Spacer(modifier = Modifier.width(8.dp))
            groupAvatarButton(address = myAddress, avatarUrl = myAvatarUrl, fallbackText = "You", navController = navController, isOwnMessage = true)
        }
    }
    }
}

/**
 * Avatar with the same View Profile / Open Chat / Pay in Kaspa / Copy Address menu
 * [BroadcastScreens.kt]'s avatar `CenteredOptionsMenu` offers for a tapped sender - group
 * members are always saved contacts, so this navigates straight to the existing chat/chat_info
 * routes instead of Broadcast's "create a contact for this anonymous sender first" step.
 */
@Composable
private fun groupAvatarButton(
    address: String?,
    avatarUrl: String?,
    photoUri: String? = null,
    fallbackText: String,
    navController: NavController,
    isOwnMessage: Boolean = false,
    isMuted: Boolean = false,
    onMute: (String) -> Unit = {},
    onUnmute: (String) -> Unit = {},
    onHide: (String) -> Unit = {}
) {
    if (address == null) {
        ContactAvatar(imageUrl = avatarUrl, deviceContactPhotoUri = photoUri, fallbackText = fallbackText, size = 32.dp)
        return
    }
    var showAvatarMenu by remember { mutableStateOf(false) }
    var avatarMenuAnchor by remember { mutableStateOf(Offset.Zero) }
    val clipboardManager = LocalClipboardManager.current

    Box(
        modifier = Modifier.onGloballyPositioned { coords ->
            avatarMenuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
        }
    ) {
        ContactAvatar(
            imageUrl = avatarUrl,
            deviceContactPhotoUri = photoUri,
            fallbackText = fallbackText,
            size = 32.dp,
            modifier = Modifier.clickable { showAvatarMenu = true }
        )
        if (showAvatarMenu) {
            CenteredOptionsMenu(onDismissRequest = { showAvatarMenu = false }, anchor = avatarMenuAnchor) {
                PopupMenuRow(Icons.Default.Person, stringResource(R.string.view_profile)) {
                    navController.navigate("chat_info/$address?fromBroadcast=true")
                    showAvatarMenu = false
                }
                if (!isOwnMessage) {
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.open_chat)) {
                        navController.navigate("chat/$address")
                        showAvatarMenu = false
                    }
                }
                HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                PopupMenuRow(Icons.Default.ContentCopy, stringResource(R.string.copy_address)) {
                    clipboardManager.setText(AnnotatedString(address))
                    showAvatarMenu = false
                }
                if (!isOwnMessage) {
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(painterResource(com.kachat.app.R.drawable.ic_kaspa_logo), stringResource(R.string.pay_in_kaspa), iconTint = Color.Unspecified) {
                        navController.navigate("chat/$address?paymentMode=true")
                        showAvatarMenu = false
                    }
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(
                        if (isMuted) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        if (isMuted) "Unmute User" else "Mute User"
                    ) {
                        if (isMuted) onUnmute(address) else onMute(address)
                        showAvatarMenu = false
                    }
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(Icons.Default.VisibilityOff, stringResource(R.string.hide_user), labelColor = Color(0xFFFF3B30), iconTint = Color(0xFFFF3B30)) {
                        onHide(address)
                        showAvatarMenu = false
                    }
                }
            }
        }
    }
}

/**
 * Group membership. Kept intentionally minimal, mirroring iOS KaChat's `GroupChatInfoView` —
 * member add/remove UI is a natural next step once this is in front of you. No invite-link/join
 * flow - every member is added directly by the admin (see `GroupRepository`'s class doc for why
 * the invite beacon was removed). Tapping a member opens their normal 1:1 "User Info" screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatInfoScreen(
    navController: NavController,
    groupId: String,
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val groups by chatViewModel.groups.collectAsState()
    val group = groups.firstOrNull { it.groupId == groupId }
    // Merged contact+KNS maps so the roster shows avatars + KNS names for non-contact members too
    // (see the group thread screen / VM's groupMemberAvatarsByAddress/groupMemberNamesByAddress).
    val contactAvatarsByAddress by chatViewModel.groupMemberAvatarsByAddress.collectAsState()
    val contactPhotoUrisByAddress by chatViewModel.contactPhotoUrisByAddress.collectAsState()
    val contactAliasesByAddress by chatViewModel.groupMemberNamesByAddress.collectAsState()
    val groupMentionsOnly by chatViewModel.groupMentionsOnly.collectAsState()
    val members = remember(group?.membersJson) {
        group?.let(::parseGroupMembers) ?: emptyList()
    }

    LaunchedEffect(group?.groupId) {
        val addresses = group?.let(::parseGroupMembers)?.map { it.address } ?: return@LaunchedEffect
        chatViewModel.refreshKnsProfilesForGroupMembers(addresses)
    }

    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showHiddenUsers by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.group_info), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = LocalAppColors.current.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Members (${members.size})",
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(LocalAppColors.current.surface)
            ) {
                members.forEachIndexed { index, member ->
                    val memberLabel = contactAliasesByAddress[member.address]?.takeIf { it.isNotBlank() }
                        ?: member.displayName?.takeIf { it.isNotBlank() }
                        ?: member.address.takeLast(10)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigate("chat_info/${member.address}?fromBroadcast=true") }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ContactAvatar(
                                imageUrl = contactAvatarsByAddress[member.address],
                                deviceContactPhotoUri = contactPhotoUrisByAddress[member.address],
                                fallbackText = memberLabel,
                                size = 32.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = memberLabel, color = LocalAppColors.current.textPrimary)
                        }
                        if (member.isAdmin) {
                            Text(stringResource(R.string.admin), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                        }
                    }
                    if (index < members.size - 1) {
                        HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.3f))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(LocalAppColors.current.surface)
                    .clickable { showHiddenUsers = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = LocalAppColors.current.textPrimary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.hidden_users), color = LocalAppColors.current.textPrimary)
            }

            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(LocalAppColors.current.surface)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.only_notify_if_i_m_mentioned), color = LocalAppColors.current.textPrimary)
                    Text(
                        stringResource(R.string.other_messages_still_show_up_in),
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = groupId in groupMentionsOnly,
                    onCheckedChange = { chatViewModel.setGroupMentionsOnly(groupId, it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = KaspaTeal, checkedTrackColor = KaspaTeal.copy(alpha = 0.5f))
                )
            }

            if (group?.isAdmin == true) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LocalAppColors.current.surface)
                        .clickable {
                            renameText = group.name
                            renameError = null
                            showRename = true
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = LocalAppColors.current.textPrimary)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.rename_group), color = LocalAppColors.current.textPrimary)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(LocalAppColors.current.surface)
                    .clickable { showDeleteConfirmation = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF3B30))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.delete_group), color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.rename_group), color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.every_member_will_see_the_new),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.group_name_2)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.textSecondary,
                            focusedLabelColor = KaspaTeal,
                            unfocusedLabelColor = LocalAppColors.current.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = renameText.trim()
                    if (trimmed.isEmpty()) return@TextButton
                    showRename = false
                    chatViewModel.renameGroup(groupId, trimmed) { error -> renameError = error }
                }) {
                    Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    if (renameError != null) {
        AlertDialog(
            onDismissRequest = { renameError = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.couldn_t_rename_group), color = LocalAppColors.current.textPrimary) },
            text = { Text(renameError ?: "", color = LocalAppColors.current.textSecondary) },
            confirmButton = {
                TextButton(onClick = { renameError = null }) {
                    Text(stringResource(R.string.ok), color = KaspaTeal)
                }
            }
        )
    }

    if (showHiddenUsers) {
        val hiddenMembers by chatViewModel.groupHiddenMembers.collectAsState()
        val hiddenAddresses = hiddenMembers.mapNotNull { entry ->
            val parts = entry.split("|", limit = 2)
            if (parts.size == 2 && parts[0] == groupId) parts[1] else null
        }
        AlertDialog(
            onDismissRequest = { showHiddenUsers = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.hidden_users), color = LocalAppColors.current.textPrimary) },
            text = {
                if (hiddenAddresses.isEmpty()) {
                    Text(stringResource(R.string.no_hidden_users_in_this_group), color = LocalAppColors.current.textSecondary)
                } else {
                    Column {
                        hiddenAddresses.forEach { address ->
                            val label = contactAliasesByAddress[address]?.takeIf { it.isNotBlank() }
                                ?: members.firstOrNull { it.address == address }?.displayName?.takeIf { it.isNotBlank() }
                                ?: address.takeLast(10)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, color = LocalAppColors.current.textPrimary)
                                TextButton(onClick = { chatViewModel.unhideGroupMember(groupId, address) }) {
                                    Text(stringResource(R.string.unhide), color = KaspaTeal)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHiddenUsers = false }) {
                    Text(stringResource(R.string.done), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Delete \"${group?.name ?: "this group"}\"", color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.this_removes_the_group_and_its),
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    chatViewModel.deleteGroupChat(groupId)
                    showDeleteConfirmation = false
                    navController.popBackStack("chats", inclusive = false)
                }) {
                    Text(stringResource(R.string.delete), color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}
