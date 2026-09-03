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
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import android.graphics.BitmapFactory
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Favorite
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
import com.kachat.app.models.displayName
import com.kachat.app.models.avatarFallbackText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.vector.ImageVector
import com.kachat.app.repository.ChatRepository
import com.kachat.app.repository.GroupMessage
import com.kachat.app.repository.isSystemMessage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kachat.app.util.ImagePrep
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight

// Shared, reused across every parseGroupMembers call. Allocating a fresh Gson + TypeToken per call
// (the old behaviour) was measurable: parseGroupMembers runs inside chat-list item builders and per
// message bubble, i.e. on hot recomposition paths - see the memoized call sites.
private val groupMembersGson = com.google.gson.Gson()
private val groupMembersType = object : com.google.gson.reflect.TypeToken<List<GroupMember>>() {}.type

/** Decodes an admin-set group photo (hex of a compressed JPEG) into a Bitmap, or null. */
fun decodeGroupPhotoHex(photoHex: String?): android.graphics.Bitmap? {
    if (photoHex.isNullOrEmpty()) return null
    return try {
        val bytes = ByteArray(photoHex.length / 2) { i ->
            ((Character.digit(photoHex[i * 2], 16) shl 4) + Character.digit(photoHex[i * 2 + 1], 16)).toByte()
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) { null }
}

/** Group avatar: the admin-set photo when present, else the generic Groups glyph. Used in the
 *  group list, the thread header, and Group Info so a group looks the same everywhere. */
@Composable
fun GroupAvatar(photoHex: String?, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(photoHex) { decodeGroupPhotoHex(photoHex) }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(LocalAppColors.current.surface),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Default.Groups, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(size * 0.5f))
        }
    }
}

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
    // Guards every snapTo so a straggler delta dispatched after release can't cancel the settle
    // animation and leave the reveal stuck — see ChatThreadScreen's identical block.
    val isRevealDragging = remember { mutableStateOf(false) }
    // Release ALWAYS springs the rows back; a vertical scroll stealing the gesture forces it too.
    LaunchedEffect(isRevealDragging.value, listState.isScrollInProgress) {
        if (listState.isScrollInProgress) isRevealDragging.value = false
        if (!isRevealDragging.value) revealOffsetPx.animateTo(0f)
    }

    LaunchedEffect(Unit) {
        chatViewModel.refreshUtxos()
        chatViewModel.markGroupRead(groupId)
        // Expired membership lines are already filtered out of the thread; this clears them from
        // the database too, so they never accumulate.
        chatViewModel.pruneExpiredGroupSystemMessages(groupId)
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
    // history before settling at the bottom). After that, an arrival only auto-scrolls when the
    // reader is already at (or within a row of) the bottom, or when the newest message is their
    // own send - scrolled up reading history, the viewport stays put and the scroll-to-latest
    // button is the way back down. Kept in sync with ChatThreadScreen's identical gate.
    val userIsDraggingList by listState.interactionSource.collectIsDraggedAsState()
    var hasScrolledToInitialPosition by remember { mutableStateOf(false) }
    // Measured against the count at the previous auto-scroll decision, not the new one - the
    // just-inserted rows haven't laid out when the effect fires, so the last visible index still
    // refers to the pre-insert list (see ChatThreadScreen for the full rationale).
    var autoScrollBaselineCount by remember { mutableStateOf(0) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (!hasScrolledToInitialPosition) {
                listState.scrollToItem(messages.size - 1)
                hasScrolledToInitialPosition = true
            } else if (messages.size > autoScrollBaselineCount) {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                val wasAtBottom = lastVisible >= autoScrollBaselineCount - 2
                // Sent on THIS device, just now - the group send path inserts its row under a
                // synthetic provisional txId before the broadcast returns, which no mirror
                // import or indexer sync can ever carry (same prefix literal as 1:1, see
                // MessageEntity.PROVISIONAL_ID_PREFIX). An own message mirrored in from
                // another device arrives under its real txId and goes through the at-bottom
                // gate like any other insert.
                val newest = messages.last()
                val sentFromThisDevice = newest.isOutgoing &&
                    com.kachat.app.models.MessageEntity.isProvisionalId(newest.txId)
                if (sentFromThisDevice || (wasAtBottom && !userIsDraggingList)) {
                    listState.animateScrollToItem(messages.size - 1)
                }
            }
            autoScrollBaselineCount = messages.size
        }
    }

    // Same deliberate-scroll-away detection as ChatThreadScreen's showScrollToBottom in
    // Screens.kt: not canScrollForward (which flips for a frame whenever the viewport merely
    // shrinks), and with the same 1-item tolerance for transient resizes.
    val showScrollToBottom by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            lastVisibleIndex != null && lastVisibleIndex < messages.lastIndex - 1
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
            // Same shape as the 1:1 chat header: the card and the bar occupy the SAME row, so the
            // Box takes the taller child's height and the photo rides level with the back button.
            // The bar's own title slot has a fixed height and clipped a photo this size, which is
            // why the group header was stuck with a smaller one than 1:1.
            Box(modifier = Modifier.fillMaxWidth()) {
            CenterAlignedTopAppBar(
                // Empty: the header card rides in the SAME row (see the Box above), so the bar
                // itself only carries the back button, the connection dot and select-mode actions.
                title = {},
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = KaspaTeal)
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
                        // No info button: tapping the header opens Group Info, exactly as tapping
                        // the 1:1 header opens Chat Info - which leaves the trailing slot for the
                        // connection dot, where 1:1 puts it too. The left of the bar belongs to
                        // navigation; the dot is status.
                        val statusColor = Color(dotColorHex)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(LocalAppColors.current.surface, CircleShape)
                                .clickable { ConnectionStatusOverlayState.open() },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.size(10.dp).background(statusColor, CircleShape))
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
            GroupChatHeaderCard(
                photoHex = group?.photoHex,
                name = group?.name ?: "Group",
                onClick = { navController.navigate("group_chat_info/$groupId") },
                // statusBarsPadding, because the app bar applies its own inset and this card does
                // not sit inside it - without this the photo draws up behind the camera cutout.
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding(),
            )
            }
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
                                    // A sheet, not a popup - matches the 1:1 composer and iOS.
                                    ActionSheetContainer(
                                        title = "Send",
                                        subtitle = null,
                                        onDismiss = { showComposerMenu = false },
                                    ) {
                                        ActionSheetRow(
                                            icon = Icons.Default.Image,
                                            title = stringResource(R.string.send_photo_2),
                                            subtitle = "Pick an image from your library.",
                                        ) {
                                            showComposerMenu = false
                                            photoPickerLauncher.launch("image/*")
                                        }
                                        if (nextcloudAccount != null) {
                                            ActionSheetRow(
                                                icon = Icons.Default.Cloud,
                                                title = "Send from Nextcloud",
                                                subtitle = "Pick a file from your connected server.",
                                            ) {
                                                showComposerMenu = false
                                                showNextcloudPicker = true
                                            }
                                        }
                                        ActionSheetRow(
                                            icon = Icons.Default.Mic,
                                            title = stringResource(R.string.send_audio_message),
                                            subtitle = "Record a voice message and send it to the group.",
                                        ) {
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
                            if (isRevealDragging.value) {
                                revealOffsetPx.snapTo((revealOffsetPx.value + delta).coerceIn(-maxRevealOffsetPx, 0f))
                            }
                        }
                    },
                    onDragStarted = { isRevealDragging.value = true },
                    // The settle LaunchedEffect above owns the spring-back — flipping the flag
                    // both triggers it and disarms any still-queued snapTo deltas.
                    onDragStopped = { isRevealDragging.value = false }
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
                    if (message.isSystemMessage()) {
                        // iMessage-style membership line — centered, no bubble/avatar.
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                            Text(
                                message.content,
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    } else
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
                            mentionDomains = primaryKnsByAddress,
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

            // Way back down after a deliberate scroll-up - same button as the 1:1 thread,
            // needed here for the same reason: arrivals no longer force-scroll the viewport.
            if (showScrollToBottom && messages.isNotEmpty()) {
                IconButton(
                    onClick = {
                        coroutineScope.launch { listState.animateScrollToItem(messages.size - 1) }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .size(44.dp)
                        .background(LocalAppColors.current.surface, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.scroll_to_latest),
                        tint = LocalAppColors.current.textPrimary
                    )
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
    /** Address → explicit-primary KNS domain, so @mentions render as the domain (what the user asked). */
    mentionDomains: Map<String, String?> = emptyMap(),
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
    // Mentions display as the person's KNS domain when known (what the user asked to see), else the
    // friendly name. Used for both the decoded body text and the clickable-mention annotation below.
    val resolveMentionLabel: (String) -> String = { addr ->
        mentionDomains[addr]?.takeIf { it.isNotBlank() } ?: resolveMentionName(addr)
    }
    val displayContent = remember(replyContent, message.content, groupMembersForMentions, mentionDomains) {
        GroupMentionCodec.decodeForDisplay(replyContent?.text ?: message.content, groupMembersForMentions, resolveMentionLabel)
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
    // Who reacted to this message, when asked from the long-press menu.
    var showReactions by remember { mutableStateOf(false) }
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
                // Always trailing-edge: sliding the row LEFT exposes the right side only, so a
                // CenterStart label on received rows stayed covered by the row drawn above it
                // (received messages appeared to have no timestamp). Matches the 1:1 thread's
                // MessageBubble in Screens.kt, which reveals both directions at CenterEnd.
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                // graphicsLayer, not alpha(): reading the Animatable inside this block defers it
                // to the draw phase, so dragging the row animates without recomposing every
                // visible bubble on every frame. `.offset { }` below defers the same way.
                .graphicsLayer { alpha = (-revealOffsetPx.value / maxRevealOffsetPx).coerceIn(0f, 1f) }
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

            Box {
            when {
                voiceContent != null -> AudioBubble(voiceContent = voiceContent, isSent = isSent, onLongPress = { showMenu = true }, onDoubleClick = { showQuickReactionBar = true })
                imageContent != null -> ImageBubble(imageContent = imageContent, isSent = isSent, onLongPress = { showMenu = true }, onDoubleClick = { showQuickReactionBar = true }, senderDisplayName = senderName)
                else -> {
                    var groupTextLayoutResult by remember(displayContent) { mutableStateOf<TextLayoutResult?>(null) }
                    // Sent bubbles are teal with black text/links for contrast - matches 1:1 chat's
                    // MessageBubble (Screens.kt) treatment of the same case.
                    val groupLinkColor = if (isSent) Color.Black else KaspaTeal
                    // A link back into KaChat (shared KaPosts post / broadcast-room invite) is
                    // claimed before the generic link path, exactly as in 1:1 and broadcast
                    // rooms: the universal-link form is an ordinary https URL, so without this a
                    // shared post is scraped over the network instead of previewing as the post.
                    val groupInternalLink = remember(displayContent) { KaChatLink.findFirst(displayContent) }
                    // The card is the WHOLE message wherever a KaChat link appears - see 1:1's
                    // identical rule. Copy keeps the full text.
                    val isEntirelyInternalLinkGroup = groupInternalLink != null
                    val annotatedGroupBody = remember(displayContent, isSent, groupMembersForMentions, mentionDomains) {
                        buildAnnotatedString {
                            append(displayContent)
                            // Clickable @mentions: link each member's @label run to their address (tap opens a 1:1).
                            for (member in groupMembersForMentions) {
                                if (member.address == myAddress) continue
                                val label = resolveMentionLabel(member.address)
                                if (label.isBlank()) continue
                                val token = "@$label"
                                var idx = displayContent.indexOf(token)
                                while (idx >= 0) {
                                    addStyle(SpanStyle(color = if (isSent) Color.Black else KaspaTeal), idx, idx + token.length)
                                    addStringAnnotation("MENTION", member.address, idx, idx + token.length)
                                    idx = displayContent.indexOf(token, idx + token.length)
                                }
                            }
                            for (match in TextLinkify.findUrls(displayContent)) {
                                addStyle(SpanStyle(color = groupLinkColor, textDecoration = TextDecoration.Underline), match.range.first, match.range.last + 1)
                                addStringAnnotation("URL", match.uri, match.range.first, match.range.last + 1)
                            }
                        }
                    }
                    val isEntirelyLinkGroup = remember(displayContent) { TextLinkify.isEntirelyLink(displayContent) }
                    if (isEntirelyInternalLinkGroup) {
                        // Nothing but an in-app KaChat link: the post/invite card IS the message.
                        KaChatInternalLinkCard(
                            ref = groupInternalLink!!.ref,
                            url = groupInternalLink.raw,
                            txId = message.txId,
                            onSelect = onSelect,
                            onDoubleTap = { showQuickReactionBar = true }
                        )
                    } else if (groupInternalLink == null && isEntirelyLinkGroup) {
                        // Bare-link message: the preview card replaces the text bubble (matches the
                        // 1:1 chat / iOS). fallbackText keeps the link visible/tappable if no preview
                        // data is ever found, so the message never renders as nothing.
                        LinkPreviewCard(
                            url = TextLinkify.findUrls(displayContent).first().uri,
                            txId = message.txId,
                            fallbackText = displayContent,
                            onSelect = onSelect,
                            onDoubleTap = { showQuickReactionBar = true }
                        )
                    } else {
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
                                                val mention = annotatedGroupBody.getStringAnnotations("MENTION", charOffset, charOffset).firstOrNull()
                                                if (mention != null) {
                                                    navController.navigate("chat/${mention.item}") // straight to a 1:1 with that person
                                                } else {
                                                    annotatedGroupBody.getStringAnnotations("URL", charOffset, charOffset)
                                                        .firstOrNull()?.let { annotation ->
                                                            // An in-app link opens the post or room in place; only a real
                                                            // web link leaves for the browser (see KaChatLink).
                                                            val internal = KaChatLink.parse(annotation.item)
                                                            if (internal != null) openKaChatLink(internal)
                                                            else uriHandler.openUri(annotation.item)
                                                        }
                                                }
                                            }
                                        )
                                    },
                                onTextLayout = { groupTextLayoutResult = it }
                            )
                        }
                        // Link within longer text: show the card beneath the text bubble. A
                        // KaChat link wins over an external one in the same message, so a shared
                        // post still previews natively.
                        // An internal link is always claimed above as the message itself, so
                        // only an external link can still want a card down here.
                        TextLinkify.findUrls(displayContent).firstOrNull()?.let { match ->
                            LinkPreviewCard(url = match.uri, txId = message.txId, onSelect = onSelect, onDoubleTap = { showQuickReactionBar = true })
                        }
                    }
                }
            }
                // Reaction pill anchored to the bubble's inner-bottom corner (overlaps ~10dp),
                // exactly like 1:1 chat - not floated off to the side of the row.
                if (reactions.isNotEmpty()) {
                    ReactionPill(
                        reactions = reactions,
                        myAddress = myAddress,
                        modifier = Modifier
                            .align(if (isSent) Alignment.BottomStart else Alignment.BottomEnd)
                            .offset(y = 10.dp)
                    )
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
                    // The pill shows WHICH emoji are on the bubble; it has no room to say how
                    // many or from whom. In a group that is the interesting question.
                    if (reactions.isNotEmpty()) {
                        HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                        PopupMenuRow(Icons.Default.Favorite, "Reactions (${reactions.size})") {
                            showMenu = false
                            showReactions = true
                        }
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

            // The pill (anchored to the bubble's inner-bottom corner above) is offset ~10dp down,
            // and offset reserves no layout space, so reserve it here - otherwise it overlaps the
            // content/next message below it. Matches 1:1 chat (Screens.kt).
            if (reactions.isNotEmpty()) {
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

    if (showReactions) {
        ChatReactionsSheet(
            reactions = reactions,
            isMe = { it == myAddress },
            nameFor = { resolveMentionName(it) },
            // This bubble only carries its own sender's avatar, so that is the one it can
            // answer for; everyone else falls back to initials until the roster is threaded
            // through here.
            avatarFor = { if (it == message.senderAddress) avatarUrl else if (it == myAddress) myAvatarUrl else null },
            onDismiss = { showReactions = false },
        )
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
    val menuContext = LocalContext.current

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
                    com.kachat.app.util.showAddressCopiedToast(menuContext, address)
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
    val groupSilent by chatViewModel.groupSilent.collectAsState()
    val refreshingGroupIds by chatViewModel.refreshingGroupIds.collectAsState()
    val groupRefreshState by chatViewModel.groupRefreshState.collectAsState()
    val members = remember(group?.membersJson) {
        group?.let(::parseGroupMembers) ?: emptyList()
    }

    LaunchedEffect(group?.groupId) {
        val addresses = group?.let(::parseGroupMembers)?.map { it.address } ?: return@LaunchedEffect
        chatViewModel.refreshKnsProfilesForGroupMembers(addresses)
    }

    // Blocking progress for "Refresh Messages". The repair walks the invite stream, then every
    // epoch key, then each member's message stream from the very beginning - real work that took
    // real time behind a spinner too small to mean anything, which is why the button read as
    // doing nothing even when it worked.
    val refreshState = groupRefreshState?.takeIf { it.groupId == groupId }
    if (refreshState != null) {
        val state = refreshState
        Dialog(
            onDismissRequest = { if (state.recovered != null) chatViewModel.clearGroupRefreshState() },
            properties = DialogProperties(dismissOnBackPress = state.recovered != null, dismissOnClickOutside = false)
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(LocalAppColors.current.surface)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val recovered = state.recovered
                if (recovered != null) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Refresh complete", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (recovered > 0) {
                            "Recovered $recovered message${if (recovered == 1) "" else "s"} this device had not been able to read."
                        } else {
                            "No new messages were recovered."
                        },
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    // Says WHICH wall the repair hit, so "nothing recovered" is diagnosable
                    // rather than just disappointing.
                    val rejects = state.rejections
                    if (rejects != null && !rejects.isEmpty) {
                        Spacer(Modifier.height(8.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            if (rejects.noRootForEpoch > 0) {
                                Text("${rejects.noRootForEpoch} from an epoch this device holds no key for", color = LocalAppColors.current.textSecondary, fontSize = 11.sp)
                            }
                            if (rejects.senderNotInRoster > 0) {
                                Text("${rejects.senderNotInRoster} from someone no longer in the group", color = LocalAppColors.current.textSecondary, fontSize = 11.sp)
                            }
                            if (rejects.decryptFailed > 0) {
                                Text("${rejects.decryptFailed} that failed to decrypt", color = LocalAppColors.current.textSecondary, fontSize = 11.sp)
                            }
                            if (rejects.epochRootsArchived > 0) {
                                Text("Recovered ${rejects.epochRootsArchived} older epoch key(s)", color = LocalAppColors.current.textSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { chatViewModel.clearGroupRefreshState() }) {
                        Text("Done", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                } else {
                    CircularProgressIndicator(color = KaspaTeal, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("Rebuilding this group", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(state.label, color = LocalAppColors.current.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Re-reading the whole group from the chain, the same way importing your seed phrase does. Leaving now would stop it partway.",
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showHiddenUsers by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var resendMessage by remember { mutableStateOf<String?>(null) }
    var showAddMembers by remember { mutableStateOf(false) }
    // Per-member confirmations (match iOS/desktop): resend one invite, or remove one member.
    var memberToResend by remember { mutableStateOf<com.kachat.app.models.GroupMember?>(null) }
    var memberToRemove by remember { mutableStateOf<com.kachat.app.models.GroupMember?>(null) }
    // Members list is a collapsed-by-default dropdown, not an always-open list.
    var membersExpanded by remember { mutableStateOf(false) }
    // Confirm before resending invites to everyone.
    var showResendAllConfirm by remember { mutableStateOf(false) }
    val conversations by chatViewModel.conversations.collectAsState()
    // Group photo (admin): pick an image, compress it, and push it to every member.
    val photoContext = LocalContext.current
    val photoScope = rememberCoroutineScope()
    var groupPhotoError by remember { mutableStateOf<String?>(null) }
    // Confirm (with fee) before committing a photo change.
    var pendingPhotoHex by remember { mutableStateOf<String?>(null) }
    var showRemovePhotoConfirm by remember { mutableStateOf(false) }
    val groupPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            photoScope.launch {
                try {
                    val hex = withContext(Dispatchers.IO) {
                        val prepared = ImagePrep.prepareForChatMessage(photoContext, uri, targetBytes = 10 * 1024)
                        prepared.bytes.joinToString("") { "%02x".format(it) }
                    }
                    // Stash it and confirm (with the estimated fee) before sending.
                    pendingPhotoHex = hex
                } catch (e: Exception) {
                    groupPhotoError = e.message ?: "Could not set group photo"
                }
            }
        }
    }
    // " Estimated network fee ≈ X KAS across N transactions." for a confirm dialog. Pure mass math.
    val groupFeeText: (Int, Int) -> String = { controlTx, photoTx ->
        val perControl = if (controlTx > 0) chatViewModel.estimateGroupControlTxFeeSompi(1600) else 0L
        val perPhoto = if (photoTx > 0) chatViewModel.estimateGroupControlTxFeeSompi(2 * ((group?.photoHex?.length ?: 0) + 300)) else 0L
        val totalSompi = perControl * controlTx + perPhoto * photoTx
        val n = controlTx + photoTx
        "\n\nEstimated network fee ≈ ${ChatRepository.formatKas(totalSompi)} KAS across $n transaction${if (n == 1) "" else "s"}."
    }

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            // Group header: avatar + name at the very top, showing what the group currently is.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val isGroupAdmin = group?.isAdmin == true
                Box(
                    modifier = Modifier
                        .then(if (isGroupAdmin) Modifier.clickable { groupPhotoPicker.launch("image/*") } else Modifier)
                ) {
                    GroupAvatar(photoHex = group?.photoHex, size = 88.dp)
                    if (isGroupAdmin) {
                        // Edit badge over the bottom-right of the avatar.
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(KaspaTeal),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Change group photo", tint = Color.Black, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = group?.name ?: "Group",
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "${members.size} members",
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 12.sp
                )
                if (isGroupAdmin && group?.photoHex != null) {
                    TextButton(onClick = { showRemovePhotoConfirm = true }) {
                        Text("Remove photo", color = Color(0xFFFF3B30))
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            // Members dropdown header — collapsed by default; tap to expand the list.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { membersExpanded = !membersExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Members (${members.size})",
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Icon(
                    imageVector = if (membersExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (membersExpanded) "Collapse members" else "Expand members",
                    tint = LocalAppColors.current.textSecondary
                )
            }
            if (membersExpanded) {
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
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                ContactAvatar(
                                    imageUrl = contactAvatarsByAddress[member.address],
                                    deviceContactPhotoUri = contactPhotoUrisByAddress[member.address],
                                    fallbackText = memberLabel,
                                    size = 32.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(text = memberLabel, color = LocalAppColors.current.textPrimary, maxLines = 1)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (member.isAdmin) {
                                    Text(stringResource(R.string.admin), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                                }
                                // Admins can re-send one member's invite (targeted retry) — confirmed first.
                                if (group?.isAdmin == true && !member.isAdmin) {
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { memberToResend = member },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Resend invite",
                                            tint = KaspaTeal,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    // Remove this member from the group — confirmed first.
                                    IconButton(
                                        onClick = { memberToRemove = member },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Remove from group",
                                            tint = Color(0xFFFF3B30),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (index < members.size - 1) {
                            HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.3f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            // Grouped like iOS's Form sections: related rows share one rounded container with
            // dividers, instead of every row being its own floating card. Labels only - the rows
            // say what they do.
            GroupInfoSection {
                GroupInfoRow(Icons.Default.VisibilityOff, stringResource(R.string.hidden_users)) { showHiddenUsers = true }
                GroupInfoDivider()
                GroupInfoRow(
                    icon = Icons.Default.Refresh,
                    label = "Refresh Messages",
                    enabled = groupId !in refreshingGroupIds,
                    onClick = { chatViewModel.refreshGroup(groupId) },
                    trailing = {
                        if (groupId in refreshingGroupIds) {
                            CircularProgressIndicator(strokeWidth = 2.dp, color = KaspaTeal, modifier = Modifier.size(18.dp))
                        }
                    },
                )
                GroupInfoDivider()
                GroupInfoSwitchRow(
                    label = "Silent Group Chat",
                    checked = groupId in groupSilent,
                    onCheckedChange = { chatViewModel.setGroupSilent(groupId, it) },
                )
                GroupInfoDivider()
                GroupInfoSwitchRow(
                    label = stringResource(R.string.only_notify_if_i_m_mentioned),
                    checked = groupId in groupMentionsOnly,
                    // Silent already means "never", so the finer rule underneath it is moot.
                    enabled = groupId !in groupSilent,
                    onCheckedChange = { chatViewModel.setGroupMentionsOnly(groupId, it) },
                )
            }

            if (group?.isAdmin == true) {
                Spacer(modifier = Modifier.height(24.dp))
                GroupInfoSection {
                    GroupInfoRow(Icons.Default.Edit, stringResource(R.string.rename_group)) {
                        renameText = group.name
                        renameError = null
                        showRename = true
                    }
                    GroupInfoDivider()
                    GroupInfoRow(Icons.Default.Refresh, "Resend invites to all") { showResendAllConfirm = true }
                    GroupInfoDivider()
                    GroupInfoRow(Icons.Default.PersonAdd, "Add members") { showAddMembers = true }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            GroupInfoSection {
                GroupInfoRow(
                    icon = Icons.Default.Delete,
                    label = stringResource(R.string.delete_group),
                    tint = Color(0xFFFF3B30),
                    bold = true,
                ) { showDeleteConfirmation = true }
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
                        stringResource(R.string.every_member_will_see_the_new) +
                            groupFeeText((members.size - 1).coerceAtLeast(0) + 1, 0),
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

    if (resendMessage != null) {
        AlertDialog(
            onDismissRequest = { resendMessage = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Resend invites", color = LocalAppColors.current.textPrimary) },
            text = { Text(resendMessage ?: "", color = LocalAppColors.current.textSecondary) },
            confirmButton = {
                TextButton(onClick = { resendMessage = null }) {
                    Text(stringResource(R.string.ok), color = KaspaTeal)
                }
            }
        )
    }

    // Confirm resending one member's invite (Cancel / Send).
    memberToResend?.let { member ->
        val label = contactAliasesByAddress[member.address]?.takeIf { it.isNotBlank() }
            ?: member.displayName?.takeIf { it.isNotBlank() }
            ?: member.address.takeLast(10)
        AlertDialog(
            onDismissRequest = { memberToResend = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Resend invite", color = LocalAppColors.current.textPrimary) },
            text = { Text("Resend the group invite to $label?${groupFeeText(1, 0)}", color = LocalAppColors.current.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    val addr = member.address
                    memberToResend = null
                    chatViewModel.resendGroupInviteToMember(groupId, addr) { msg -> resendMessage = msg }
                }) {
                    Text("Send", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToResend = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    // Confirm removing one member from the group (Cancel / Yes).
    memberToRemove?.let { member ->
        val label = contactAliasesByAddress[member.address]?.takeIf { it.isNotBlank() }
            ?: member.displayName?.takeIf { it.isNotBlank() }
            ?: member.address.takeLast(10)
        AlertDialog(
            onDismissRequest = { memberToRemove = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Remove member", color = LocalAppColors.current.textPrimary) },
            text = {
                val afterN = (members.size - 2).coerceAtLeast(0)
                val hasPhoto = group?.photoHex != null
                Text("Remove $label from the group chat? A fresh group key is issued to everyone who stays.${groupFeeText(2 * afterN + 1, if (hasPhoto) afterN else 0)}", color = LocalAppColors.current.textSecondary)
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = member
                    memberToRemove = null
                    chatViewModel.removeGroupMember(m, groupId) { ok, err ->
                        if (!ok) resendMessage = err ?: "Could not remove member. Please try again."
                    }
                }) {
                    Text("Yes", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToRemove = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    if (groupPhotoError != null) {
        AlertDialog(
            onDismissRequest = { groupPhotoError = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Group photo", color = LocalAppColors.current.textPrimary) },
            text = { Text(groupPhotoError ?: "", color = LocalAppColors.current.textSecondary) },
            confirmButton = { TextButton(onClick = { groupPhotoError = null }) { Text(stringResource(R.string.ok), color = KaspaTeal) } }
        )
    }

    // Confirm setting a NEW group photo (Send/Cancel), estimated from the photo's own size.
    pendingPhotoHex?.let { hex ->
        val others = (members.size - 1).coerceAtLeast(0)
        val perPhoto = if (others > 0) chatViewModel.estimateGroupControlTxFeeSompi(2 * (hex.length + 300)) else 0L
        val feeLine = "\n\nEstimated network fee ≈ ${ChatRepository.formatKas(perPhoto * others)} KAS across $others transaction${if (others == 1) "" else "s"}."
        AlertDialog(
            onDismissRequest = { pendingPhotoHex = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Set group photo", color = LocalAppColors.current.textPrimary) },
            text = { Text("Set this as the group photo for everyone?$feeLine", color = LocalAppColors.current.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    chatViewModel.setGroupPhoto(groupId, hex) { err -> groupPhotoError = err }
                    pendingPhotoHex = null
                }) { Text("Send", color = KaspaTeal, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPhotoHex = null }) { Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary) }
            }
        )
    }

    // Confirm removing the group photo (Remove/Cancel).
    if (showRemovePhotoConfirm) {
        AlertDialog(
            onDismissRequest = { showRemovePhotoConfirm = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Remove group photo", color = LocalAppColors.current.textPrimary) },
            text = { Text("Remove the group photo for everyone?${groupFeeText((members.size - 1).coerceAtLeast(0), 0)}", color = LocalAppColors.current.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    chatViewModel.setGroupPhoto(groupId, "") { err -> groupPhotoError = err }
                    showRemovePhotoConfirm = false
                }) { Text("Remove", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRemovePhotoConfirm = false }) { Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary) }
            }
        )
    }

    // Confirm resending invites to everyone (Cancel / Send).
    if (showResendAllConfirm) {
        AlertDialog(
            onDismissRequest = { showResendAllConfirm = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Resend invites to all", color = LocalAppColors.current.textPrimary) },
            text = {
                val others = (members.size - 1).coerceAtLeast(0)
                val hasPhoto = group?.photoHex != null
                Text("Resend the group invite to every member? Use this if someone didn't receive the group.${groupFeeText(others + 1, if (hasPhoto) others else 0)}", color = LocalAppColors.current.textSecondary)
            },
            confirmButton = {
                TextButton(onClick = {
                    showResendAllConfirm = false
                    chatViewModel.resendGroupInvites(groupId) { msg -> resendMessage = msg }
                }) {
                    Text("Send", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResendAllConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    if (showAddMembers) {
        var addSearch by remember { mutableStateOf("") }
        var addSelected by remember { mutableStateOf(setOf<String>()) }
        var addBusy by remember { mutableStateOf(false) }
        var addError by remember { mutableStateOf<String?>(null) }
        var showAddFeeConfirm by remember { mutableStateOf(false) }
        var addChosen by remember { mutableStateOf<List<String>>(emptyList()) }
        val existingAddresses = remember(members) { members.map { it.address }.toSet() }
        val query = addSearch.trim().lowercase()

        // A group invite is encrypted to a public key decoded from the address itself - there is
        // no handshake or prior chat to require. Limiting this to saved contacts made it look
        // like there was; the only thing missing was somewhere to put an address.
        var addResolvedDomain by remember { mutableStateOf<String?>(null) }
        var addResolvingDomain by remember { mutableStateOf(false) }
        LaunchedEffect(addSearch) {
            addResolvedDomain = null
            val trimmed = addSearch.trim()
            if (trimmed.isEmpty() || !com.kachat.app.services.KnsService.looksLikeDomain(trimmed)) {
                addResolvingDomain = false
                return@LaunchedEffect
            }
            addResolvingDomain = true
            kotlinx.coroutines.delay(500)
            addResolvedDomain = chatViewModel.resolveKnsDomain(trimmed)
            addResolvingDomain = false
        }
        val candidates = remember(conversations, existingAddresses, query) {
            conversations.map { it.contact }
                .distinctBy { it.id }
                .filter { it.id !in existingAddresses }
                .sortedBy { it.displayName.lowercase() }
                .filter { query.isEmpty() || it.displayName.lowercase().contains(query) || it.id.lowercase().contains(query) }
        }
        // Hidden when the address is already listed as a contact below, so it is never offered twice.
        val typedAddress = remember(addSearch, addResolvedDomain, existingAddresses, candidates) {
            val trimmed = addSearch.trim()
            val candidate = if (com.kachat.app.util.KaspaAddress.isValid(trimmed)) trimmed else addResolvedDomain
            candidate?.takeIf { addr ->
                com.kachat.app.util.KaspaAddress.isValid(addr) && addr !in existingAddresses && candidates.none { it.id == addr }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!addBusy) showAddMembers = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Add members", color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    TextField(
                        value = addSearch,
                        onValueChange = { addSearch = it },
                        placeholder = { Text("Search contacts, or paste an address", color = Color.DarkGray) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = LocalAppColors.current.textSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = LocalAppColors.current.background,
                            unfocusedContainerColor = LocalAppColors.current.background,
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            cursorColor = KaspaTeal,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    if (addResolvingDomain) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Looking up domain...", color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (typedAddress != null) {
                        val typedSelected = typedAddress in addSelected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    addSelected = if (typedSelected) addSelected - typedAddress else addSelected + typedAddress
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Add this address", color = LocalAppColors.current.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    typedAddress,
                                    color = LocalAppColors.current.textSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                if (typedSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (typedSelected) KaspaTeal else LocalAppColors.current.textSecondary
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (conversations.isEmpty() && typedAddress == null) {
                        Text("You have no contacts yet. Paste an address or a .kas domain above to invite someone.",
                            color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                    } else if (candidates.isEmpty() && typedAddress == null) {
                        Text(if (query.isEmpty()) "Everyone in your contacts is already in this group." else "No contacts match your search. Paste an address or a .kas domain to invite someone new.",
                            color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                    } else if (candidates.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(candidates, key = { it.id }) { contact ->
                                val selected = contact.id in addSelected
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            addSelected = if (selected) addSelected - contact.id else addSelected + contact.id
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ContactAvatar(
                                        imageUrl = contact.knsAvatarUrl,
                                        deviceContactPhotoUri = contact.systemContactPhotoUri,
                                        backupPhotoBase64 = contact.backupPhotoBase64,
                                        fallbackText = contact.avatarFallbackText,
                                        size = 36.dp
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(contact.displayName, color = LocalAppColors.current.textPrimary, maxLines = 1)
                                        Text(contact.id.takeLast(16), color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    }
                                    Icon(
                                        imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (selected) KaspaTeal else LocalAppColors.current.textSecondary
                                    )
                                }
                            }
                        }
                    }
                    addError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Color(0xFFFF3B30), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("New members can read messages from the moment they're added, not earlier history.",
                        color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = addSelected.isNotEmpty() && !addBusy,
                    onClick = {
                        // Confirm with the estimated fee first — each add rotates the group key.
                        addChosen = addSelected.toList()
                        showAddFeeConfirm = true
                    }
                ) {
                    Text(if (addSelected.isEmpty()) "Add" else "Add (${addSelected.size})", color = KaspaTeal)
                }
            },
            dismissButton = {
                TextButton(enabled = !addBusy, onClick = { showAddMembers = false }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
        // Fee confirmation shown after tapping Add — each added member rotates the group key.
        if (showAddFeeConfirm) {
            val k = addChosen.size
            val finalOthers = (members.size - 1).coerceAtLeast(0) + k
            val hasPhoto = group?.photoHex != null
            AlertDialog(
                onDismissRequest = { showAddFeeConfirm = false },
                containerColor = LocalAppColors.current.surface,
                title = { Text("Add members", color = LocalAppColors.current.textPrimary) },
                text = { Text("Add $k member${if (k == 1) "" else "s"} to the group?${groupFeeText(k * (2 * finalOthers + 1), if (hasPhoto) k * finalOthers else 0)}", color = LocalAppColors.current.textSecondary) },
                confirmButton = {
                    TextButton(onClick = {
                        showAddFeeConfirm = false
                        addBusy = true
                        addError = null
                        chatViewModel.addGroupMembers(addChosen, groupId) { added, failed ->
                            addBusy = false
                            if (failed == 0) showAddMembers = false
                            else addError = "$failed member(s) could not be added ($added added). Please try again."
                        }
                    }) { Text("Add", color = KaspaTeal, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showAddFeeConfirm = false }) { Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary) }
                }
            )
        }
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


/**
 * Group mirror of the 1:1 chat's `ChatHeaderCard`: the group photo over a capsule that tucks
 * under it, tapping through to Group Info. Same measurements, so the two headers read the same.
 */
@Composable
private fun GroupChatHeaderCard(
    photoHex: String?,
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    // Wraps its content - NOT fillMaxWidth. Filling the width would put a tap target over the
    // whole bar row, so the back button and the connection dot would open Group Info instead.
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(top = 2.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopCenter) {
            // The capsule tucks under the photo - offset by the overlap, then padded back out at
            // the top so the name still clears it. Reads as one piece rather than a stack.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .padding(top = 34.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .padding(start = 12.dp, end = 10.dp, top = 15.dp, bottom = 5.dp),
            ) {
                Text(
                    name,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
            GroupAvatar(photoHex = photoHex, size = 46.dp)
        }
    }
}

// MARK: - Group Info rows

/**
 * One rounded container holding related rows, divided rather than spaced - the shape iOS's Form
 * sections give Group Info. Replaces the old stack of individually-floating cards.
 */
@Composable
private fun GroupInfoSection(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LocalAppColors.current.surface),
        content = content,
    )
}

@Composable
private fun GroupInfoDivider() {
    HorizontalDivider(
        color = LocalAppColors.current.background,
        thickness = 1.dp,
        modifier = Modifier.padding(start = 48.dp),
    )
}

/** A tappable Group Info row: icon, label, optional trailing content. Label only - no subtitle. */
@Composable
private fun GroupInfoRow(
    icon: ImageVector,
    label: String,
    tint: Color = KaspaTeal,
    bold: Boolean = false,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = tint,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** A Group Info toggle row. Label only - no subtitle. */
@Composable
private fun GroupInfoSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = LocalAppColors.current.textPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = KaspaTeal, checkedTrackColor = KaspaTeal.copy(alpha = 0.5f)),
        )
    }
}
