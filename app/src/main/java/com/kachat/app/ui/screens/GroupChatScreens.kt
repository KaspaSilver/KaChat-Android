package com.kachat.app.ui.screens

import com.kachat.app.util.UserFacingError
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
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
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
import com.kachat.app.models.liveNameFor
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
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaUnit
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.VisibilityOff

// Shared, reused across every parseGroupMembers call. Allocating a fresh Gson + TypeToken per call
// (the old behaviour) was measurable: parseGroupMembers runs inside chat-list item builders and per
// message bubble, i.e. on hot recomposition paths - see the memoized call sites.
private val groupMembersGson = com.google.gson.Gson()
private val groupMembersType = object : com.google.gson.reflect.TypeToken<List<GroupMember>>() {}.type

private val groupPhotoCache = android.util.LruCache<String, android.graphics.Bitmap>(32)

/** Decodes an admin-set group photo (hex of a compressed JPEG) into a Bitmap, or null. */
fun decodeGroupPhotoHex(photoHex: String?): android.graphics.Bitmap? {
    if (photoHex.isNullOrEmpty()) return null
    return try {
        val bytes = ByteArray(photoHex.length / 2) { i ->
            ((Character.digit(photoHex[i * 2], 16) shl 4) + Character.digit(photoHex[i * 2 + 1], 16)).toByte()
        }
        // Any admin sets this photo, so its claimed size is not trusted (see SafeBitmapDecode).
        com.kachat.app.util.SafeBitmapDecode.decode(bytes, maxDimension = 512)
    } catch (e: Exception) { null }
}

/** Group avatar: the admin-set photo when present, else the generic Groups glyph. Used in the
 *  group list, the thread header, and Group Info so a group looks the same everywhere.
 *  [initialOf] (Group Info's header) draws iOS's fallback there instead: the name's initial in
 *  the accent on a 20% accent disc. */
@Composable
fun GroupAvatar(photoHex: String?, size: Dp, modifier: Modifier = Modifier, initialOf: String? = null) {
    // Off the main thread and kept app-wide, like contact backup photos (see ContactAvatar).
    val decoded by produceState(initialValue = photoHex?.let { groupPhotoCache.get(it) }, photoHex) {
        val hex = photoHex
        if (hex.isNullOrEmpty()) { value = null; return@produceState }
        groupPhotoCache.get(hex)?.let { value = it; return@produceState }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { decodeGroupPhotoHex(hex) }
            ?.also { groupPhotoCache.put(hex, it) }
    }
    val bitmap = decoded
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (initialOf != null && bitmap == null) KaspaTeal.copy(alpha = 0.2f) else LocalAppColors.current.surface),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (initialOf != null) {
            // iOS: String(group.name.prefix(1)).uppercased() at 32 semibold for the 76 header.
            Text(
                text = initialOf.take(1).uppercase(),
                color = KaspaTeal,
                fontSize = (size.value * 32f / 76f).sp,
                fontWeight = FontWeight.SemiBold,
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
        // Every mention is "@kaspa:..." / "@kaspatest:...". Rows decode often, sometimes a photo
        // or voice note's base64: one scan, not a name lookup and a full replace per member
        // (iOS 85c7967).
        if (!text.contains("@kaspa")) return text
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
    val myAddress by walletViewModel.address.collectAsState()
    val myKnsProfile by walletViewModel.knsProfile.collectAsState()
    // Zero-balance funding gate — same behavior as the 1:1 chat thread (confirmed 0 KAS only,
    // on-entry refresh + 10s re-poll while gated); see ZeroBalanceFundingUi.kt.
    val fundingGate = rememberZeroBalanceFundingGate()
    val groups by chatViewModel.groups.collectAsState()
    val group = groups.firstOrNull { it.groupId == groupId }
    val messages by chatViewModel.getGroupMessages(groupId).collectAsState(initial = emptyList())
    val groupReactions by chatViewModel.getGroupReactions(groupId).collectAsState(initial = emptyList())
    val groupReactionsByTxId = remember(groupReactions) { groupReactions.groupBy { it.targetTxId } }
    val groupReplyingTo by chatViewModel.groupReplyingTo.collectAsState()
    // The newest edit per message (see MessageEdit) - applied to the bubble as it is drawn.
    val groupEdits by chatViewModel.getGroupEdits(groupId).collectAsState(initial = emptyList())
    val acceptedContacts by chatViewModel.acceptedContactAddresses.collectAsState()
    val groupEditsByTxId = remember(groupEdits) { groupEdits.associateBy { it.targetTxId } }
    val groupEditingMessage by chatViewModel.groupEditingMessage.collectAsState()
    // Merged contact+KNS maps: group members are usually not saved contacts, so their avatar/KNS
    // name come from the address-keyed KNS cache, not just contact rows (see the VM's
    // groupMemberAvatarsByAddress/groupMemberNamesByAddress). This is what makes group chats show
    // avatars + KNS names instead of raw addresses.
    val contactAvatarsByAddress by chatViewModel.groupMemberAvatarsByAddress.collectAsState()
    val contactAliasesByAddress by chatViewModel.groupMemberNamesByAddress.collectAsState()
    val pendingPhotoUri by chatViewModel.groupPendingPhotoUri.collectAsState()
    val voiceRecordingState by chatViewModel.groupVoiceRecordingState.collectAsState()
    val showFeeEstimate by settingsViewModel.showFeeEstimate.collectAsState()
    val estimatedFeeRaw by chatViewModel.groupEstimatedFeeSompi.collectAsState()
    val estimatedFee = if (showFeeEstimate) estimatedFeeRaw else null
    val isEstimatingFee by chatViewModel.groupIsEstimatingFee.collectAsState()
    val networkFeeRate by chatViewModel.networkFeeRate.collectAsState()
    val feeRateOverride by chatViewModel.feeRateOverride.collectAsState()
    // What was typed here last time, exactly as 1:1 chats do (iOS 360e5d2).
    val draftState = remember(groupId) {
        val saved = chatViewModel.groupDraft(groupId)
        mutableStateOf(TextFieldValue(saved, TextRange(saved.length)))
    }
    // Written freely, but read in only three kinds of place, so a keystroke doesn't recompose
    // this screen (message list and header included - iOS 0977a5b): the text field, inside
    // ComposerTextScope; lambdas that run on a tap; and draftIsEmpty, which changes on the first
    // and last character, not on every one. The draft/fee sync below reads it in a snapshotFlow.
    var draft by draftState
    val draftIsEmpty by remember(draftState) { derivedStateOf { draftState.value.text.isEmpty() } }
    // The text the field itself last reported, so the sync below can tell a keystroke (the fee
    // preview waits for a pause) from text set by code (priced at once).
    val lastTypedDraft = remember(groupId) { arrayOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showComposerMenu by remember { mutableStateOf(false) }
    // The "+" sheet's second step (on chain or via Nextcloud), or null for the options - iOS
    // GroupChatDetailView.plusSheetMediaStep (8b13460).
    var composerMediaStep by remember { mutableStateOf<ComposerMediaKind?>(null) }
    var composerMenuAnchor by remember { mutableStateOf(Offset.Zero) }
    // The sender whose avatar was tapped. One sheet serves every row - the parent presents it
    // for whichever sender was tapped, rather than a menu attached to each avatar in the list.
    var senderSheetTarget by remember { mutableStateOf<SenderSheetTarget?>(null) }
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
        contactAliasesByAddress.liveNameFor(address)
            ?: groupMembers.firstOrNull { it.address == address }?.displayName?.takeIf { it.isNotBlank() }
            ?: KaspaAddress.shortDisplay(address)
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
        // The shown fee may lag the text by a pause (see TypingFeeGate): price the exact text
        // now, so the rate Save derives from the fee matches what is being sent.
        chatViewModel.refreshGroupFeePreview()
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
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

    LaunchedEffect(Unit) {
        chatViewModel.refreshUtxos()
        chatViewModel.markGroupRead(groupId)
        // Expired membership lines are already filtered out of the thread; this clears them from
        // the database too, so they never accumulate.
        chatViewModel.pruneExpiredGroupSystemMessages(groupId)
    }
    // Voice-note playback is owned per bubble; the thread going away is what ends it.
    DisposableEffect(Unit) { onDispose { VoicePlayback.stopAllAfterChildrenDispose() } }
    DisposableEffect(groupId) {
        chatViewModel.setActiveGroup(groupId)
        onDispose { chatViewModel.setActiveGroup(null) }
    }
    // A snapshotFlow, not LaunchedEffect(draft.text): reading the text here made every keystroke
    // recompose the whole screen.
    LaunchedEffect(draftState) {
        snapshotFlow { draftState.value.text }.collect { text ->
            chatViewModel.setGroupMessageText(text, fromTyping = text == lastTypedDraft[0])
            chatViewModel.saveGroupDraft(groupId, text)
        }
    }

    val micContext = LocalContext.current
    // See the 1:1 composer: where the media chosen in the "+" sheet goes - on chain or via
    // Nextcloud - has to survive the permission prompt and the camera/picker round trip.
    var voiceViaNextcloud by remember { mutableStateOf(false) }
    var cameraViaNextcloud by remember { mutableStateOf(false) }
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) chatViewModel.startGroupVoiceRecording(groupId, viaNextcloud = voiceViaNextcloud)
    }
    // Photo on chain: images only - no on-chain path fits a video.
    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) chatViewModel.setGroupPendingPhoto(uri)
    }
    // "Send Photo or Video via Nextcloud": videos too, sent as an upload-and-share link (iOS a890102).
    val nextcloudMediaPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (micContext.contentResolver.getType(uri)?.startsWith("video/") == true) {
            // A failure shows above the composer, as iOS's group chat shows it (not a toast).
            errorMessage = null
            chatViewModel.sendGroupNextcloudVideo(groupId, uri) { errorMessage = it }
        } else {
            chatViewModel.setGroupPendingPhoto(uri, viaNextcloud = true)
        }
    }
    val startCameraCapture = rememberCameraCaptureLauncher { uri -> chatViewModel.setGroupPendingPhoto(uri, viaNextcloud = cameraViaNextcloud) }
    val startVoiceRecordingIfPermitted = { viaNextcloud: Boolean ->
        voiceViaNextcloud = viaNextcloud
        if (chatViewModel.voiceRecordingSupported) {
            if (ContextCompat.checkSelfPermission(micContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                chatViewModel.startGroupVoiceRecording(groupId, viaNextcloud = viaNextcloud)
            } else {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    val startMedia: (ComposerMediaKind, Boolean) -> Unit = { kind, viaNextcloud ->
        when (kind) {
            ComposerMediaKind.CAMERA -> {
                cameraViaNextcloud = viaNextcloud
                startCameraCapture()
            }
            ComposerMediaKind.PHOTO -> if (viaNextcloud) {
                nextcloudMediaPickerLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            } else {
                photoPickerLauncher.launch("image/*")
            }
            ComposerMediaKind.VOICE -> startVoiceRecordingIfPermitted(viaNextcloud)
        }
    }
    // Asks on chain or via Nextcloud when a server is connected, otherwise goes straight on chain.
    val chooseMedia: (ComposerMediaKind) -> Unit = { kind ->
        if (nextcloudAccount != null) {
            composerMediaStep = kind
        } else {
            showComposerMenu = false
            startMedia(kind, false)
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
    // A row that keeps growing after it lands (long text, then its link preview, then the image)
    // keeps the chat pinned to its bottom (iOS aa26c35).
    KeepChatPinnedWhileRowsGrow(listState)

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
            val headerTapBand = remember { ChatHeaderTapBandState() }
            // iOS: the messages scroll on up behind the bar - a fade, not an opaque band (as the
            // public room's).
            val headerBackground = LocalAppColors.current.background
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to headerBackground.copy(alpha = 0.95f),
                            0.55f to headerBackground.copy(alpha = 0.7f),
                            1f to Color.Transparent,
                        )
                    )
                    // Photo and name open Group Info - and so does the 24dp either side of them;
                    // the space further out jumps to the very first message (iOS 1d5a555). The
                    // back button, the connection dot and the card keep their own taps.
                    .chatHeaderTapBand(
                        headerTapBand,
                        onChip = { navController.navigate("group_chat_info/$groupId") },
                        onBand = { coroutineScope.launch { listState.scrollToItem(0) } },
                    )
            ) {
            // iOS 26's bar, as in 1:1: Back on a 44pt glass disc, and trailing the connection
            // dot on its own disc - or Cancel/Delete on one piece of glass while selecting.
            ChatNavBar(onBack = { navController.popBackStack() }) {
                if (isSelectingMessages) {
                    ToolbarGlassGroup {
                        ToolbarTextButton(stringResource(R.string.cancel), onClick = {
                            isSelectingMessages = false
                            selectedMessageIds = emptySet()
                        })
                        Box(Modifier.alpha(if (selectedMessageIds.isNotEmpty()) 1f else 0.4f)) {
                            ToolbarIconButton(
                                Icons.Default.Delete,
                                stringResource(R.string.delete),
                                onClick = { if (selectedMessageIds.isNotEmpty()) showDeleteMessagesConfirmation = true },
                                tint = LocalAppColors.current.danger,
                            )
                        }
                    }
                } else {
                    // No info button: tapping the header opens Group Info, exactly as tapping
                    // the 1:1 header opens Chat Info - which leaves the trailing slot for the
                    // connection dot (iOS ConnectionStatusIndicator: a 44pt glass disc).
                    ConnectionDotButton(onClick = { ConnectionStatusOverlayState.open() })
                }
            }
            GroupChatHeaderCard(
                photoHex = group?.photoHex,
                name = group?.name ?: "Group",
                onClick = { navController.navigate("group_chat_info/$groupId") },
                // statusBarsPadding, because the app bar applies its own inset and this card does
                // not sit inside it - without this the photo draws up behind the camera cutout.
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .chatHeaderChip(headerTapBand)
                    .statusBarsPadding(),
            )
            }
        },
        bottomBar = {
            // Composer dims and goes inert while the zero-balance funding gate is up — see
            // Modifier.zeroBalanceComposerGate in ZeroBalanceFundingUi.kt.
            // iOS: the zero-balance card sits right above the composer (padded 16 either side,
            // 4 below) - never over the thread - and stays usable while the composer dims.
            // With the dock showing it rests just above the dock (LocalDockInset, which already
            // holds the system bar under the dock) and the messages run on under the dock; with
            // the keyboard up the dock is behind it, the inset is 0 and nothing changes here.
            // The chain stays fixed (no navigationBarsPadding() swapped in with the keyboard), as
            // on the public room, where that swap froze the app: the dock inset or the IME inset
            // already covers the navigation bar.
            val dockInset = LocalDockInset.current
            Column(
                modifier = Modifier
                    .padding(bottom = dockInset)
                    // iOS's composer floats over the thread: the messages show through, fading
                    // under it, as on the public room (not an opaque band).
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to LocalAppColors.current.background.copy(alpha = 0.85f),
                        )
                    )
                    .imePadding()
            ) {
            if (fundingGate.active) {
                ZeroBalanceFundingCard(
                    walletAddress = fundingGate.chattingAddress,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                )
            }
            Column(modifier = Modifier.zeroBalanceComposerGate(fundingGate.active)) {
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = LocalAppColors.current.danger,
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
                        // Editing and replying are mutually exclusive - the banner says which one
                        // is in progress (iOS GroupChatDetailView's inputBar).
                        groupEditingMessage?.let { editing ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .background(LocalAppColors.current.surface, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Editing message", color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        GroupMentionCodec.decodeForDisplay(
                                            groupEditsByTxId[editing.txId]?.text
                                                ?: com.kachat.app.util.MessageEdit.unwrappedText(editing.content),
                                            groupMembers,
                                            resolveDisplayName
                                        ),
                                        color = LocalAppColors.current.textSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        chatViewModel.cancelGroupEditing()
                                        draft = TextFieldValue("")
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel editing", tint = LocalAppColors.current.textSecondary)
                                }
                            }
                        }
                        if (groupEditingMessage == null) groupReplyingTo?.let { reply ->
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
                                        address?.let { contactAliasesByAddress.liveNameFor(it) }
                                            ?: member?.displayName?.takeIf { it.isNotBlank() }
                                            ?: address?.let { KaspaAddress.shortDisplay(it) }
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
                        // The fee, or - while a re-price waits for its pause - iOS's shimmering
                        // placeholder (TypingFeeGate).
                        if (showFeeEstimate && !draftIsEmpty && (estimatedFee != null || isEstimatingFee)) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                groupFeePill(
                                    estimatedFee,
                                    modifier = Modifier.align(Alignment.Center),
                                    estimating = isEstimatingFee,
                                    onClick = { openFeeEditor(estimatedFee ?: 0L) }
                                )
                            }
                        }
                        mentionQuery?.takeIf { com.kachat.app.util.MentionsFeature.ENABLED }?.let { query ->
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
                            // The one place the text itself is read while composing - see draft.
                            ComposerTextScope {
                            // iOS textRow: the same ComposerTextView bubble as 1:1 - body text
                            // padded 12 by 8 on glassBackground(cornerRadius: 20).
                            androidx.compose.foundation.text.BasicTextField(
                                value = draft,
                                onValueChange = { newValue ->
                                    // Someone's payment request pasted in opens Send Kaspa instead
                                    // of landing in the message (iOS c6851d6).
                                    if (PaymentRequestPaste.interceptInsert(draft.text, newValue.text)) return@BasicTextField
                                    lastTypedDraft[0] = newValue.text
                                    draft = newValue
                                    mentionQuery = detectMentionQuery(newValue)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .chatComposerFieldModifier(),
                                textStyle = chatComposerTextStyle(),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(KaspaTeal),
                                // The input bubble is the text field only: the camera moved
                                // into the "+" sheet, as in 1:1 chats (iOS 8b13460).
                                maxLines = 5,
                                decorationBox = { inner ->
                                    ChatComposerFieldDecoration(showPlaceholder = draft.text.isEmpty(), innerTextField = inner)
                                },
                            )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (draftIsEmpty) {
                                Box(
                                    modifier = Modifier.onGloballyPositioned { coords ->
                                        composerMenuAnchor = coords.positionInWindow()
                                    }
                                ) {
                                    ChatComposerPlusButton(onClick = { showComposerMenu = true })
                                }
                                if (showComposerMenu && composerMediaStep == null) {
                                    // A sheet, not a popup - matches the 1:1 composer and iOS,
                                    // minus Pay in Kaspa and chess (a group has no single
                                    // recipient for either).
                                    // Square tiles, three to a row, as in 1:1 (iOS d645d78).
                                    TileActionSheet(
                                        title = stringResource(R.string.send),
                                        onDismiss = { showComposerMenu = false },
                                        // iOS: camera, photo, voice, plus file with Nextcloud.
                                        height = ActionSheetTileMetrics.sheetHeight(tiles = if (nextcloudAccount != null) 4 else 3),
                                    ) {
                                            // Camera, Photo and Voice Message each ask on chain or via
                                            // Nextcloud when a server is connected (that choice
                                            // replaced the "Send Media via Nextcloud" setting), and go
                                            // straight on chain when none is.
                                            ActionSheetRow(
                                                icon = Icons.Default.CameraAlt,
                                                title = stringResource(R.string.camera),
                                                subtitle = stringResource(R.string.group_composer_camera_subtitle),
                                            ) { chooseMedia(ComposerMediaKind.CAMERA) }
                                            ActionSheetRow(
                                                icon = Icons.Default.Image,
                                                title = stringResource(R.string.photo),
                                                subtitle = stringResource(R.string.group_composer_photo_subtitle),
                                            ) { chooseMedia(ComposerMediaKind.PHOTO) }
                                            ActionSheetRow(
                                                icon = Icons.Default.Mic,
                                                title = stringResource(R.string.composer_voice_message),
                                                subtitle = stringResource(R.string.group_composer_voice_subtitle),
                                            ) { chooseMedia(ComposerMediaKind.VOICE) }
                                            if (nextcloudAccount != null) {
                                                ActionSheetRow(
                                                    // iOS "doc": a plain page, no text lines
                                                    icon = Icons.Outlined.InsertDriveFile,
                                                    title = stringResource(R.string.composer_file),
                                                    subtitle = stringResource(R.string.composer_file_subtitle),
                                                ) {
                                                    showComposerMenu = false
                                                    showNextcloudPicker = true
                                                }
                                            }
                                    }
                                }
                                composerMediaStep?.let { kind ->
                                    ComposerMediaRouteSheet(
                                        kind = kind,
                                        onChoose = { viaNextcloud ->
                                            composerMediaStep = null
                                            showComposerMenu = false
                                            startMedia(kind, viaNextcloud)
                                        },
                                        onBack = { composerMediaStep = null },
                                        onDismiss = {
                                            composerMediaStep = null
                                            showComposerMenu = false
                                        },
                                    )
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
                                        // Off until mentions are rebuilt on .kachat (MentionsFeature):
                                        // an "@name" typed now is sent as the plain text it is.
                                        val text = if (!com.kachat.app.util.MentionsFeature.ENABLED) draft.text.trim()
                                        else GroupMentionCodec.encodeForSending(draft.text.trim(), groupMembers) { address ->
                                            primaryKnsByAddress[address] ?: ""
                                        }
                                        if (text.isEmpty()) return@IconButton
                                        // Editing: the composer's text replaces the message being
                                        // edited - one edit transaction, no new bubble.
                                        val editing = groupEditingMessage
                                        draft = TextFieldValue("")
                                        errorMessage = null
                                        if (editing != null) {
                                            chatViewModel.sendGroupEdit(groupId, editing.txId, text) { error -> errorMessage = error }
                                        } else {
                                            chatViewModel.sendGroupMessage(text, groupId) { error -> errorMessage = error }
                                        }
                                    },
                                    // iOS: arrow.up.circle.fill at 30 in the accent colour, bare.
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ArrowCircleUp,
                                        contentDescription = stringResource(R.string.send),
                                        tint = KaspaTeal,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                        }
                        }
                    }
                }
            }
            }
        }
    ) { padding ->
        // Drawn to the bottom of the screen, NOT padded by the composer: the messages run on
        // underneath it and the dock (iOS), and the list takes the composer's height (with the
        // dock's, see the bottomBar) as content padding, so the newest message rests just above it.
        // Drawn from the top of the screen too: the messages run on up behind the header, the
        // list taking its height as content padding.
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            senderSheetTarget?.let { target ->
                val address = target.address
                val sheetClipboard = LocalClipboardManager.current
                val sheetContext = LocalContext.current
                SenderActionsSheet(
                    address = address,
                    displayName = resolveDisplayName(address),
                    isOwnMessage = target.isOwnMessage,
                    onDismiss = { senderSheetTarget = null },
                    // Group members are always saved contacts, so these navigate straight to the
                    // existing chat/chat_info routes - no "create a contact first" step as in
                    // broadcast rooms.
                    onViewProfile = { navController.navigate("chat_info/$address") },
                    onOpenChat = { navController.navigate("chat/$address") },
                    onPayInKaspa = { navController.navigate("chat/$address?paymentMode=true") },
                    onCopyAddress = {
                        sheetClipboard.setText(AnnotatedString(address))
                        com.kachat.app.util.showAddressCopiedToast(sheetContext, address, haptic = false)
                    },
                    muteState = chatViewModel.isGroupMemberMuted(groupId, address),
                    onToggleMute = {
                        if (chatViewModel.isGroupMemberMuted(groupId, address)) chatViewModel.unmuteGroupMember(groupId, address)
                        else chatViewModel.muteGroupMember(groupId, address)
                    },
                    onHide = { chatViewModel.hideGroupMember(groupId, address) },
                    hideSubtitle = "Stop seeing their messages in this group. Undo it in Group Info.",
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .closesQuickReactionBar()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            ) {
                itemsIndexed(messages, key = { _, msg -> msg.txId }) { index, message ->
                    // An edited message reads with its newest text; the stored row is untouched.
                    // The editor-sent-it check happens at ingest; whether the original was text at
                    // all can only be judged here, where the content is decrypted.
                    val editForRow = groupEditsByTxId[message.txId]
                        ?.takeIf { com.kachat.app.util.MessageEdit.isEditable(message.content) }
                    @Suppress("NAME_SHADOWING")
                    val message = remember(message, editForRow) {
                        if (editForRow == null) message
                        else message.copy(content = com.kachat.app.util.MessageEdit.apply(editForRow.text, message.content))
                    }
                    if (index == 0 || !ChatTimeFormat.isSameDay(messages[index - 1].blockTimestamp, message.blockTimestamp)) {
                        // iOS daySeparator: 10 above and 2 below, in a list spaced 8 as this one
                        // is - so 2 + 8 below it here, where it shares the message's row.
                        ChatDaySeparator(
                            message.blockTimestamp,
                            modifier = Modifier.padding(top = 10.dp, bottom = 10.dp),
                        )
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
                            linkPreviewAutoFetch = message.senderAddress?.let { it == myAddress || it in acceptedContacts } ?: false,
                            liveAlias = message.senderAddress?.let { contactAliasesByAddress.liveNameFor(it) },
                            myAddress = myAddress,
                            myAvatarUrl = myKnsProfile?.avatarUrl,
                            navController = navController,
                            onRetry = { chatViewModel.retryGroupMessage(groupId, message.content, failedTxId = message.txId) },
                            onReply = { chatViewModel.startGroupReplyTo(message) },
                            isEdited = editForRow != null,
                            // Your own delivered text messages only - the same gate 1:1 uses.
                            onEdit = if (message.isOutgoing && message.deliveryStatus == "sent" &&
                                com.kachat.app.util.MessageEdit.isEditable(message.content)) {
                                {
                                    chatViewModel.startGroupEditing(message)
                                    // Mentions read as names while editing, exactly as they are
                                    // typed - encodeForSending puts the wire form back on send.
                                    draft = TextFieldValue(
                                        GroupMentionCodec.decodeForDisplay(
                                            com.kachat.app.util.MessageEdit.unwrappedText(message.content),
                                            groupMembers,
                                            resolveDisplayName
                                        )
                                    ).let { TextFieldValue(it.text, TextRange(it.text.length)) }
                                }
                            } else null,
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
                            onAvatarTap = { address -> senderSheetTarget = SenderSheetTarget(address, isOwnMessage = address == myAddress) },
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
                                    tint = if (message.txId in selectedMessageIds) KaspaTeal else LocalAppColors.current.textSecondary
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
                        // Above the composer the list runs under.
                        .padding(bottom = padding.calculateBottomPadding())
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

            // The zero-balance funding card is in the bottom bar, right above the dimmed
            // composer, as on iOS.
        }
    }

    if (showFeeEditor) {
        com.kachat.app.ui.theme.IosAlertDialog(
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
                    com.kachat.app.ui.theme.IosTextField(
                        value = feeEditorInput,
                        onValueChange = { feeEditorInput = it },
                        label = { Text(KaspaUnit.label(stringResource(R.string.fee_kas))) },
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
                    // The one exact parser for typed KAS (iOS 16b64bc): "1,5" or "1.5", never a crash.
                    val desiredFeeSompi = KaspaUnit.sompiFromUserText(feeEditorInput)
                    val currentFeeSompi = estimatedFeeRaw ?: 0L
                    if (desiredFeeSompi != null && desiredFeeSompi > 0 && currentFeeSompi > 0 && effectiveRate > 0) {
                        val impliedMass = currentFeeSompi / effectiveRate
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
        com.kachat.app.ui.theme.IosAlertDialog(
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
                    Text("Delete", color = LocalAppColors.current.danger, fontWeight = FontWeight.Bold)
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
private fun groupFeePill(feeSompi: Long?, modifier: Modifier = Modifier, estimating: Boolean = false, onClick: (() -> Unit)? = null) {
    if (feeSompi == null && !estimating) return
    // iOS's feeBubble, the same pill as 1:1 and public chats.
    ComposerFeePill(
        feeSompi = feeSompi,
        estimating = estimating,
        onTap = { onClick?.invoke() },
        modifier = modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun groupPhotoPreviewRow(pendingPhotoUri: android.net.Uri?, onCancel: () -> Unit, onSend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            // iOS photoPreviewRow / recordingRow: glassBackground(cornerRadius: 20)
            .chatComposerFieldModifier()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cancel_photo), tint = LocalAppColors.current.danger)
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
            // iOS photoPreviewRow / recordingRow: glassBackground(cornerRadius: 20)
            .chatComposerFieldModifier()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cancel_recording), tint = LocalAppColors.current.danger)
        }
        Icon(Icons.Default.Mic, contentDescription = null, tint = LocalAppColors.current.danger, modifier = Modifier.size(18.dp))
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
    /** Load link previews without a tap: your own messages and accepted contacts only. Anyone
     *  else's link waits for a tap, so reading the group tells no stranger's server your IP
     *  (iOS 680cff3, the 1:1 rule). */
    linkPreviewAutoFetch: Boolean = false,
    liveAlias: String?,
    myAddress: String?,
    myAvatarUrl: String?,
    navController: NavController,
    onRetry: () -> Unit,
    onReply: () -> Unit = {},
    /** The text shown is an edit of what was sent (the caller already swapped `content`); a small
     *  "edited" sits under the bubble. [onEdit] opens the composer on this message's text - passed
     *  only for the local user's own editable text messages. Mirrors [MessageBubble]'s pair. */
    isEdited: Boolean = false,
    onEdit: (() -> Unit)? = null,
    reactions: List<com.kachat.app.models.ReactionEntity> = emptyList(),
    onReact: (String) -> Unit = {},
    /** Retries the local user's failed reaction on this message (see its `failedAction`). */
    onRetryReaction: (com.kachat.app.models.ReactionEntity) -> Unit = {},
    /** Tapping the reply quote (if any) jumps to and highlights the original message. */
    onJumpToReply: (String) -> Unit = {},
    isHighlighted: Boolean = false,
    resolveMentionName: (String) -> String = { KaspaAddress.shortDisplay(it) },
    /** Address → explicit-primary KNS domain, so @mentions render as the domain (what the user asked). */
    mentionDomains: Map<String, String?> = emptyMap(),
    /** Tapping an avatar. The parent presents the sender half sheet for this address (see
     *  [SenderActionsSheet]); the row itself no longer owns a menu. */
    onAvatarTap: (String) -> Unit = {},
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
        member?.displayName?.takeIf { it.isNotBlank() } ?: KaspaAddress.shortDisplay(address)
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
        member?.displayName?.takeIf { it.isNotBlank() } ?: KaspaAddress.shortDisplay(reply.replyToSender)
    }
    val voiceContent = remember(displayContent) { VoiceMessage.parseOrNull(displayContent) }
    val imageContent = remember(displayContent) { if (voiceContent == null) ImageMessage.parseOrNull(displayContent) else null }
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    var showMenu by remember { mutableStateOf(false) }
    // Who reacted to this message, when asked from the long-press menu.
    var showReactions by remember { mutableStateOf(false) }
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isSent) {
            groupAvatarButton(
                address = message.senderAddress,
                avatarUrl = avatarUrl,
                fallbackText = senderName,
                onTap = onAvatarTap
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isSent) Alignment.End else Alignment.Start,
            modifier = Modifier.onGloballyPositioned { coords ->
                menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
            }
        ) {
            // The double-tap bar sits directly above the message, as iOS's does.
            InlineQuickReactionBar(messageId = message.txId, onReact = onReact, onReply = onReply)
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
                            replySenderName ?: KaspaAddress.shortDisplay(replyContent.replyToSender),
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
                voiceContent != null -> AudioBubble(voiceContent = voiceContent, isSent = isSent, onLongPress = { showMenu = true }, onDoubleClick = { QuickReactionBarState.activeId = message.txId })
                imageContent != null -> ImageBubble(imageContent = imageContent, isSent = isSent, onLongPress = { showMenu = true }, onDoubleClick = { QuickReactionBarState.activeId = message.txId }, senderDisplayName = senderName)
                else -> {
                    var groupTextLayoutResult by remember(displayContent) { mutableStateOf<TextLayoutResult?>(null) }
                    // Sent bubbles are teal with black text/links for contrast - matches 1:1 chat's
                    // MessageBubble (Screens.kt) treatment of the same case.
                    val groupLinkColor = if (isSent) LocalAppColors.current.onOutgoingBubble else KaspaTeal
                    // A link back into KaChat (shared KaPosts post / broadcast-room invite) is
                    // claimed before the generic link path, exactly as in 1:1 and broadcast
                    // rooms: the universal-link form is an ordinary https URL, so without this a
                    // shared post is scraped over the network instead of previewing as the post.
                    val groupInternalLink = remember(displayContent) { KaChatLink.findFirst(displayContent) }
                    // The card is the WHOLE message wherever a KaChat link appears - see 1:1's
                    // identical rule. Copy keeps the full text.
                    val isEntirelyInternalLinkGroup = groupInternalLink != null
                    val annotatedGroupBody = remember(displayContent, isSent, groupLinkColor, groupMembersForMentions, mentionDomains) {
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
                                    addStyle(SpanStyle(color = groupLinkColor), idx, idx + token.length)
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
                            onDoubleTap = { QuickReactionBarState.activeId = message.txId }
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
                            onDoubleTap = { QuickReactionBarState.activeId = message.txId },
                            isOutgoing = isSent,
                            autoFetch = isSent || linkPreviewAutoFetch
                        )
                    } else {
                        Surface(
                            color = if (isSent) LocalAppColors.current.outgoingBubble else LocalAppColors.current.incomingBubble,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.widthIn(max = 280.dp)
                        ) {
                            Text(
                                text = annotatedGroupBody,
                                color = if (isSent) LocalAppColors.current.onOutgoingBubble else LocalAppColors.current.textPrimary,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .pointerInput(annotatedGroupBody) {
                                        detectTapGestures(
                                            onLongPress = { showMenu = true },
                                            onDoubleTap = { QuickReactionBarState.activeId = message.txId },
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
                            LinkPreviewCard(url = match.uri, txId = message.txId, onSelect = onSelect, onDoubleTap = { QuickReactionBarState.activeId = message.txId }, isOutgoing = isSent, autoFetch = isSent || linkPreviewAutoFetch)
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

            // The time under every message, and your own messages' status after it (iOS
            // fc6aec6). The failed label is itself the retry button (DeliveryStatusLabel).
            MessageTimeLine(
                timestampMs = message.blockTimestamp,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                if (isSent) {
                    DeliveryStatusLabel(
                        status = deliveryStatusOf(message.deliveryStatus),
                        onRetry = if (canRetry) onRetry else null,
                    )
                }
            }

            // "edited" under the bubble - the mark every platform shows on an edited message.
            if (isEdited) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "edited",
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(if (isSent) Alignment.CenterStart else Alignment.CenterEnd)
                            .padding(top = 2.dp)
                    )
                }
            }

            if (showMenu) {
                // The app's half sheet, as everywhere else: what the message is, then a row
                // per action with a line on what it does (iOS 1bc6ba6).
                MessageActionsSheet(
                    title = when {
                        imageContent != null -> "Photo"
                        voiceContent != null -> "Voice Message"
                        else -> "Message"
                    },
                    preview = displayContent.takeIf { imageContent == null && voiceContent == null },
                    onDismiss = { showMenu = false },
                ) {
                    ActionSheetRow(
                        icon = Icons.AutoMirrored.Filled.Reply,
                        title = stringResource(R.string.reply),
                        subtitle = "Quotes this message above your reply.",
                    ) {
                        onReply()
                        showMenu = false
                    }
                    if (onEdit != null) {
                        ActionSheetRow(
                            icon = Icons.Default.Edit,
                            title = "Edit",
                            subtitle = "Changes the text. Everyone sees the new version.",
                        ) {
                            onEdit()
                            showMenu = false
                        }
                    }
                    ActionSheetRow(
                        icon = Icons.Default.ContentCopy,
                        title = stringResource(R.string.copy_message),
                        subtitle = "Copies the text to your clipboard.",
                    ) {
                        clipboardManager.setText(AnnotatedString(displayContent))
                        showMenu = false
                        // iOS GroupMessageBubbleRow's Copy Message: onCopy(displayContent, .success)
                        // into the group's showToast - the toast is the copied text itself, for
                        // 3 s, with no haptic.
                        IosToasts.show(displayContent, durationMs = IosToastDuration.LONG)
                    }
                    ActionSheetRow(
                        icon = Icons.Default.Public,
                        title = stringResource(R.string.view_in_explorer),
                        subtitle = "Opens this transaction in the block explorer.",
                    ) {
                        uriHandler.openUri(com.kachat.app.models.KaspaExplorer.default.txUrl(message.txId))
                        showMenu = false
                    }
                    // The pill shows WHICH emoji are on the bubble; it has no room to say how
                    // many or from whom. In a group that is the interesting question.
                    if (reactions.isNotEmpty()) {
                        ActionSheetRow(
                            icon = Icons.Default.Favorite,
                            title = "Reactions (${reactions.size})",
                            subtitle = "Who reacted, and with what.",
                        ) {
                            showMenu = false
                            showReactions = true
                        }
                    }
                    if (canRetry) {
                        ActionSheetRow(
                            icon = Icons.Default.Refresh,
                            title = stringResource(R.string.retry_send),
                            subtitle = "Sends this message again.",
                        ) {
                            onRetry()
                            showMenu = false
                        }
                    }
                    if (onSelect != null) {
                        ActionSheetRow(
                            icon = Icons.Default.CheckCircle,
                            title = "Select",
                            subtitle = "Pick several messages at once.",
                        ) {
                            onSelect()
                            showMenu = false
                        }
                    }
                }
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
                        color = LocalAppColors.current.danger,
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
            groupAvatarButton(address = myAddress, avatarUrl = myAvatarUrl, fallbackText = "You", onTap = onAvatarTap)
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
 * The sender's avatar. Tapping it opens the sender half sheet - View Profile / Open Chat /
 * Pay in Kaspa / Copy Address / Mute / Hide - presented by the parent (see
 * [SenderActionsSheet]), the same shape as the broadcast room's. This was a popup menu of bare
 * labels; the sheet has room to say what each option does, and one sheet serves every row.
 */
@Composable
private fun groupAvatarButton(
    address: String?,
    avatarUrl: String?,
    fallbackText: String,
    onTap: (String) -> Unit
) {
    ContactAvatar(
        imageUrl = avatarUrl,
        fallbackText = fallbackText,
        size = 32.dp,
        modifier = if (address != null) Modifier.clickable { onTap(address) } else Modifier,
        address = address
    )
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
                    com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
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
                    groupPhotoError = UserFacingError.message(e, "Could not set group photo")
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
        "\n\nEstimated network fee ≈ ${ChatRepository.formatKas(totalSompi)} ${KaspaUnit.symbol} across $n transaction${if (n == 1) "" else "s"}."
    }

    // iOS presents Group Info as a sheet holding a Form (GroupChatDetailView's `.sheet`): the
    // grouped sheet palette (#1C1C1E behind #2C2C2E cards in dark), its rounded top 10 below the
    // status bar, "Group Info" inline with Done on glass - the same frame User Info uses (see
    // ChatInfoScreen / IosSheetPage). Android's system Back still closes it.
    IosSheetPage(grouped = true) {
        SheetNavBar(
            title = stringResource(R.string.group_info),
            trailing = { SheetBarButton(stringResource(R.string.done), onClick = { navController.popBackStack() }) },
        )
        // iOS Form, measured on the iPhone: the first section 29 under the bar, 35 between
        // sections, cards 16 in from each side.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            Spacer(modifier = Modifier.height(29.dp))
            // Header section (a Form row on a clear background): the avatar - a PhotosPicker for
            // admins, with the pencil badge - the name in title3, the count in caption, then
            // "Remove photo" for an admin when there is a photo. 8 apart, as iOS's VStack.
            val isGroupAdmin = group?.isAdmin == true
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = if (isGroupAdmin) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { groupPhotoPicker.launch("image/*") } else Modifier
                ) {
                    // The photo when set, else the name's initial on the accent at 20% (iOS).
                    GroupAvatar(photoHex = group?.photoHex, size = 76.dp, initialOf = group?.name ?: "")
                    if (isGroupAdmin) {
                        // SF pencil.circle.fill at 22 in the accent, over a disc of the page colour.
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(LocalAppColors.current.background),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(KaspaTeal),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Change group photo",
                                    tint = LocalAppColors.current.background,
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                        }
                    }
                }
                Text(
                    text = group?.name ?: "Group",
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.group_info_member_count, members.size),
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 12.sp
                )
                if (isGroupAdmin && !group?.photoHex.isNullOrEmpty()) {
                    // iOS: Button("Remove photo", role: .destructive) in caption.
                    Text(
                        text = stringResource(R.string.group_info_remove_photo),
                        color = LocalAppColors.current.danger,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { showRemovePhotoConfirm = true }
                    )
                }
            }

            // Members: iOS's DisclosureGroup - one row with a chevron that turns down to open
            // the roster in the same card, collapsed by default.
            Spacer(modifier = Modifier.height(35.dp))
            GroupInfoSection {
                GroupInfoDisclosureRow(
                    label = stringResource(R.string.group_info_members_count, members.size),
                    expanded = membersExpanded,
                    onClick = { membersExpanded = !membersExpanded },
                )
                if (membersExpanded) {
                    members.forEachIndexed { index, member ->
                        // The separator above a row starts under the text of the row above it.
                        GroupInfoDivider(start = if (index == 0) 16.dp else 60.dp)
                        val memberLabel = contactAliasesByAddress.liveNameFor(member.address)
                            ?: member.displayName?.takeIf { it.isNotBlank() }
                            ?: KaspaAddress.shortDisplay(member.address)
                        // iOS memberRow: 32 avatar, the name, "Admin" in caption, and for an admin
                        // the resend / remove buttons (each confirmed first), 12 apart.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { navController.navigate("chat_info/${member.address}") }
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ContactAvatar(
                                imageUrl = contactAvatarsByAddress[member.address],
                                fallbackText = memberLabel,
                                size = 32.dp,
                                address = member.address
                            )
                            Text(
                                text = memberLabel,
                                color = LocalAppColors.current.textPrimary,
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (member.isAdmin) {
                                Text(stringResource(R.string.admin), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                            }
                            if (group?.isAdmin == true && !member.isAdmin) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .clickable { memberToResend = member },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Resend invite", tint = KaspaTeal, modifier = Modifier.size(20.dp))
                                }
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .clickable { memberToRemove = member },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Remove from group", tint = LocalAppColors.current.danger, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }

            // Hidden Users, a card of its own.
            Spacer(modifier = Modifier.height(35.dp))
            GroupInfoSection {
                GroupInfoRow(Icons.Outlined.VisibilityOff, stringResource(R.string.hidden_users)) { showHiddenUsers = true }
            }

            // Refresh Messages and the notification switches share one card.
            Spacer(modifier = Modifier.height(35.dp))
            GroupInfoSection {
                GroupInfoRow(
                    icon = Icons.Default.Refresh,
                    label = stringResource(R.string.group_info_refresh_messages),
                    enabled = groupId !in refreshingGroupIds,
                    onClick = { chatViewModel.refreshGroup(groupId) },
                )
                GroupInfoDivider(start = 54.dp)
                GroupInfoSwitchRow(
                    label = stringResource(R.string.group_info_silent_group_chat),
                    checked = groupId in groupSilent,
                    onCheckedChange = { chatViewModel.setGroupSilent(groupId, it) },
                )
                if (com.kachat.app.util.MentionsFeature.ENABLED) {
                    GroupInfoDivider(start = 16.dp)
                    GroupInfoSwitchRow(
                        label = stringResource(R.string.only_notify_if_i_m_mentioned),
                        checked = groupId in groupMentionsOnly,
                        // Silent already means "never", so the finer rule underneath it is moot.
                        enabled = groupId !in groupSilent,
                        onCheckedChange = { chatViewModel.setGroupMentionsOnly(groupId, it) },
                    )
                }
            }

            if (group?.isAdmin == true) {
                Spacer(modifier = Modifier.height(35.dp))
                GroupInfoSection {
                    GroupInfoRow(Icons.Outlined.Edit, stringResource(R.string.rename_group)) {
                        renameText = group.name
                        renameError = null
                        showRename = true
                    }
                    GroupInfoDivider(start = 54.dp)
                    GroupInfoRow(Icons.Default.Refresh, stringResource(R.string.group_info_resend_invites_to_all)) { showResendAllConfirm = true }
                    GroupInfoDivider(start = 54.dp)
                    GroupInfoRow(Icons.Outlined.PersonAdd, stringResource(R.string.ab_add_members)) { showAddMembers = true }
                }
            }

            // Delete Group: iOS's destructive Label - the title red in the regular weight, the
            // trash in the accent like every other row's symbol.
            Spacer(modifier = Modifier.height(35.dp))
            GroupInfoSection {
                GroupInfoRow(
                    icon = Icons.Outlined.Delete,
                    label = stringResource(R.string.delete_group),
                    textColor = LocalAppColors.current.danger,
                ) { showDeleteConfirmation = true }
            }
            Spacer(modifier = Modifier.height(35.dp))
        }
    }

    if (showRename) {
        com.kachat.app.ui.theme.IosAlertDialog(
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
                    com.kachat.app.ui.theme.IosTextField(
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
        com.kachat.app.ui.theme.IosAlertDialog(
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
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { resendMessage = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Resend Invites", color = LocalAppColors.current.textPrimary) },
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
        val label = contactAliasesByAddress.liveNameFor(member.address)
            ?: member.displayName?.takeIf { it.isNotBlank() }
            ?: KaspaAddress.shortDisplay(member.address)
        com.kachat.app.ui.theme.IosAlertDialog(
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
        val label = contactAliasesByAddress.liveNameFor(member.address)
            ?: member.displayName?.takeIf { it.isNotBlank() }
            ?: KaspaAddress.shortDisplay(member.address)
        com.kachat.app.ui.theme.IosAlertDialog(
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
                    Text("Yes", color = LocalAppColors.current.danger, fontWeight = FontWeight.Bold)
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
        com.kachat.app.ui.theme.IosAlertDialog(
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
        val feeLine = "\n\nEstimated network fee ≈ ${ChatRepository.formatKas(perPhoto * others)} ${KaspaUnit.symbol} across $others transaction${if (others == 1) "" else "s"}."
        com.kachat.app.ui.theme.IosAlertDialog(
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
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { showRemovePhotoConfirm = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Remove group photo", color = LocalAppColors.current.textPrimary) },
            text = { Text("Remove the group photo for everyone?${groupFeeText((members.size - 1).coerceAtLeast(0), 0)}", color = LocalAppColors.current.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    chatViewModel.setGroupPhoto(groupId, "") { err -> groupPhotoError = err }
                    showRemovePhotoConfirm = false
                }) { Text("Remove", color = LocalAppColors.current.danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRemovePhotoConfirm = false }) { Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary) }
            }
        )
    }

    // Confirm resending invites to everyone (Cancel / Send).
    if (showResendAllConfirm) {
        com.kachat.app.ui.theme.IosAlertDialog(
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
        // "Adding 2 of 3" rather than a frozen dialog - see addGroupMembers for why this can
        // legitimately take minutes. Two ints, not a Pair: destructuring one inside a composable
        // lambda collides with Compose's own component1/component2.
        var addDone by remember { mutableStateOf(0) }
        var addTotal by remember { mutableStateOf(0) }
        var addError by remember { mutableStateOf<String?>(null) }
        var showAddFeeConfirm by remember { mutableStateOf(false) }
        var addChosen by remember { mutableStateOf<List<String>>(emptyList()) }
        val existingAddresses = remember(members) { members.map { it.address }.toSet() }
        val query = addSearch.trim().lowercase()

        // A group invite is encrypted to a public key decoded from the address itself - there is
        // no handshake or prior chat to require. Limiting this to saved contacts made it look
        // like there was; the only thing missing was somewhere to put an address.
        // Address a typed domain resolved to (.kachat first, like everywhere else an address is
        // accepted), the name it resolved as, and every service's answer for "Other domains"
        // (iOS 6ac48a7).
        var addResolvedDomain by remember { mutableStateOf<String?>(null) }
        var addResolvedName by remember { mutableStateOf<String?>(null) }
        var addResolutions by remember { mutableStateOf<List<com.kachat.app.services.NameResolution>>(emptyList()) }
        var addSelectedTld by remember { mutableStateOf<com.kachat.app.services.NameServiceTLD?>(null) }
        var addDomainNotFound by remember { mutableStateOf<String?>(null) }
        var addResolvingDomain by remember { mutableStateOf(false) }
        val addSelectResolution: (com.kachat.app.services.NameResolution) -> Unit = { resolution ->
            resolution.address?.let {
                addResolvedDomain = it
                addResolvedName = resolution.display
                addSelectedTld = resolution.tld
                addDomainNotFound = null
            }
        }
        val candidates = remember(conversations, existingAddresses, query) {
            conversations.map { it.contact }
                .distinctBy { it.id }
                .filter { it.id !in existingAddresses }
                .sortedBy { it.displayName.lowercase() }
                .filter { query.isEmpty() || it.displayName.lowercase().contains(query) || it.id.lowercase().contains(query) }
        }
        val addContext = androidx.compose.ui.platform.LocalContext.current
        // Resolves a typed name in the background on every service, .kachat first (the ending
        // typed, else .kachat only - the others wait under Other domains). Anything that is
        // already an address, or too short to be a name, is left alone.
        LaunchedEffect(addSearch) {
            addResolvedDomain = null
            addResolvedName = null
            addResolutions = emptyList()
            addSelectedTld = null
            addDomainNotFound = null
            val trimmed = addSearch.trim().lowercase()
            if (trimmed.isEmpty() || com.kachat.app.util.KaspaAddress.isValid(trimmed) ||
                !com.kachat.app.services.NameServicesClient.looksLikeName(trimmed) || trimmed.length < 2
            ) {
                addResolvingDomain = false
                return@LaunchedEffect
            }
            addResolvingDomain = true
            // debounce typing: the search box changes on every keystroke
            kotlinx.coroutines.delay(300)
            val results = chatViewModel.resolveEverywhere(trimmed)
            addResolutions = results
            addResolvingDomain = false
            val primary = com.kachat.app.services.NameServicesClient.primary(results, trimmed)
            if (primary != null) {
                addSelectResolution(primary)
            } else if (candidates.isEmpty()) {
                // it matched no contacts by name either: a failed lookup
                addDomainNotFound = com.kachat.app.services.NameServicesClient.notFoundMessage(addContext, trimmed, results)
            }
        }
        // Hidden when the address is already listed as a contact below, so it is never offered twice.
        // Only the active network's addresses: the invite is encrypted to the key the address carries.
        val typedAddress = remember(addSearch, addResolvedDomain, existingAddresses, candidates) {
            val trimmed = addSearch.trim()
            val candidate = if (com.kachat.app.util.KaspaAddress.isValidOnActiveNetwork(trimmed)) trimmed else addResolvedDomain
            candidate?.takeIf { addr ->
                com.kachat.app.util.KaspaAddress.isValidOnActiveNetwork(addr) && addr !in existingAddresses && candidates.none { it.id == addr }
            }
        }
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { if (!addBusy) showAddMembers = false },
            containerColor = LocalAppColors.current.surface,
            title = {
                Column {
                    Text(stringResource(R.string.ab_add_members), color = LocalAppColors.current.textPrimary)
                    if (addBusy) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            com.kachat.app.ui.theme.IosActivityIndicator(
                                color = KaspaTeal,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (addTotal > 1) "Adding ${(addDone + 1).coerceAtMost(addTotal)} of $addTotal"
                                else "Adding member",
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Each member re-keys the group on chain, so this can take a minute. Leave the app open.",
                            color = LocalAppColors.current.textSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            },
            text = {
                Column {
                    TextField(
                        value = addSearch,
                        onValueChange = { addSearch = it },
                        placeholder = { Text(stringResource(R.string.search_contacts_or_address_or_domain), color = LocalAppColors.current.textTertiary) },
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
                            com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.looking_up_domain), color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                    } else if (addResolvedDomain != null && addResolvedName != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = LocalAppColors.current.success, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.resolved_name, addResolvedName ?: ""), color = LocalAppColors.current.success, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        MiddleEllipsisText(
                            addResolvedDomain ?: "",
                            color = LocalAppColors.current.textSecondary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    } else if (addDomainNotFound != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Cancel, contentDescription = null, tint = LocalAppColors.current.danger, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(addDomainNotFound ?: "", color = LocalAppColors.current.danger, fontSize = 12.sp)
                        }
                    }
                    if (!addResolvingDomain) {
                        OtherDomainsDropdown(addResolutions, addSelectedTld, addSelectResolution)
                    }
                    if (!addResolvingDomain && (addResolvedDomain != null || addDomainNotFound != null || addResolutions.isNotEmpty())) {
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
                            // Who this is - avatar and name - the same card as create-chat.
                            AddressResolutionCard(
                                address = typedAddress,
                                domain = if (addResolvedDomain == typedAddress) addResolvedName else null,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                if (typedSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (typedSelected) KaspaTeal else LocalAppColors.current.textSecondary
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (conversations.isEmpty() && typedAddress == null) {
                        Text(stringResource(R.string.no_contacts_yet_paste_address_or_domain),
                            color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                    } else if (candidates.isEmpty() && typedAddress == null) {
                        Text(if (query.isEmpty()) "Everyone in your contacts is already in this group." else stringResource(R.string.no_contacts_match_paste_address_or_domain),
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
                                        backupPhotoBase64 = contact.backupPhotoBase64,
                                        fallbackText = contact.avatarFallbackText,
                                        size = 36.dp,
                                        address = contact.id
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
                        Text(it, color = LocalAppColors.current.danger, style = MaterialTheme.typography.bodySmall)
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
            com.kachat.app.ui.theme.IosAlertDialog(
                onDismissRequest = { showAddFeeConfirm = false },
                containerColor = LocalAppColors.current.surface,
                title = { Text("Add members", color = LocalAppColors.current.textPrimary) },
                text = { Text("Add $k member${if (k == 1) "" else "s"} to the group?${groupFeeText(k * (2 * finalOthers + 1), if (hasPhoto) k * finalOthers else 0)}", color = LocalAppColors.current.textSecondary) },
                confirmButton = {
                    TextButton(onClick = {
                        showAddFeeConfirm = false
                        addBusy = true
                        addError = null
                        addDone = 0
                        addTotal = addChosen.size
                        chatViewModel.addGroupMembers(
                            addChosen,
                            groupId,
                            onProgress = { done, total -> addDone = done; addTotal = total },
                        ) { added, failed ->
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

    // iOS: a sheet of its own (HiddenGroupMembersView in a NavigationStack) - "Hidden Users"
    // inline with Done, a Form listing each hidden member with Unhide, or a single grey line when
    // there are none.
    if (showHiddenUsers) {
        val hiddenMembers by chatViewModel.groupHiddenMembers.collectAsState()
        val hiddenAddresses = hiddenMembers.mapNotNull { entry ->
            val parts = entry.split("|", limit = 2)
            if (parts.size == 2 && parts[0] == groupId) parts[1] else null
        }
        IosFullSheet(
            onDismissed = { showHiddenUsers = false },
            swipeToDismiss = true,
            grouped = true,
        ) { close ->
            Column(modifier = Modifier.fillMaxSize()) {
                SheetNavBar(
                    title = stringResource(R.string.hidden_users),
                    trailing = { SheetBarButton(stringResource(R.string.done), onClick = close) },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (hiddenAddresses.isEmpty()) {
                        Spacer(modifier = Modifier.height(29.dp))
                        GroupInfoSection {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(stringResource(R.string.no_hidden_users_in_this_group), color = LocalAppColors.current.textSecondary, fontSize = 17.sp)
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(16.dp))
                        SettingsSection(title = stringResource(R.string.hidden_users)) {
                            hiddenAddresses.forEachIndexed { index, address ->
                                if (index > 0) GroupInfoDivider(start = 60.dp)
                                val label = contactAliasesByAddress.liveNameFor(address)
                                    ?: members.firstOrNull { it.address == address }?.displayName?.takeIf { it.isNotBlank() }
                                    ?: KaspaAddress.shortDisplay(address)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ContactAvatar(
                                        imageUrl = contactAvatarsByAddress[address],
                                        fallbackText = label,
                                        size = 32.dp,
                                        address = address
                                    )
                                    Text(
                                        text = label,
                                        color = LocalAppColors.current.textPrimary,
                                        fontSize = 17.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = stringResource(R.string.unhide),
                                        color = KaspaTeal,
                                        fontSize = 17.sp,
                                        modifier = Modifier.clickable { chatViewModel.unhideGroupMember(groupId, address) }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(35.dp))
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        com.kachat.app.ui.theme.IosAlertDialog(
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
                    Text(stringResource(R.string.delete), color = LocalAppColors.current.danger, fontWeight = FontWeight.Bold)
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
    ChatHeaderChipLayout(name = name, onClick = onClick, modifier = modifier) {
        GroupAvatar(photoHex = photoHex, size = 46.dp)
    }
}

// MARK: - Group Info rows

/**
 * One iOS Form section: a 26pt-cornered card in the sheet's card colour holding related rows,
 * divided rather than spaced.
 */
@Composable
private fun GroupInfoSection(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(LocalAppColors.current.surface),
        content = content,
    )
}

/** iOS's inset separator: from under the row's text ([start]) to 16 short of the card's edge. */
@Composable
private fun GroupInfoDivider(start: Dp) {
    HorizontalDivider(
        color = LocalAppColors.current.divider,
        modifier = Modifier.padding(start = start, end = 16.dp),
    )
}

/**
 * A tappable Group Info row - iOS's `Button { } label: { Label(title, systemImage:) }` in a Form:
 * 52 tall, the symbol in the accent centred in a 26 frame 16 in, the 17pt title 12 after it in
 * [textColor] (the accent; red for Delete Group). Disabled, both go grey as iOS's do.
 */
@Composable
private fun GroupInfoRow(
    icon: ImageVector,
    label: String,
    textColor: Color = KaspaTeal,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val disabledColor = LocalAppColors.current.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (enabled) KaspaTeal else disabledColor, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = if (enabled) textColor else disabledColor,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** iOS's DisclosureGroup row in a Form: the 17pt label, and a chevron that turns from right to
 *  down as the group opens. */
@Composable
private fun GroupInfoDisclosureRow(label: String, expanded: Boolean, onClick: () -> Unit) {
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "disclosureChevron",
    )
    val chevronColor = LocalAppColors.current.textPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = LocalAppColors.current.textPrimary,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        // SF chevron.right, semibold, in the label colour.
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer { rotationZ = rotation }
        ) {
            val stroke = 2.2.dp.toPx()
            val w = size.height * 0.55f
            val left = (size.width - w) / 2f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(left + stroke / 2f, stroke / 2f)
                lineTo(left + w - stroke / 2f, size.height / 2f)
                lineTo(left + stroke / 2f, size.height - stroke / 2f)
            }
            drawPath(
                path,
                color = chevronColor,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
        }
    }
}

/** A Group Info toggle row - iOS's Toggle in a Form: the 17pt label, the switch trailing, 52 tall. */
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
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (enabled) LocalAppColors.current.textPrimary else LocalAppColors.current.textSecondary,
            fontSize = 17.sp,
            modifier = Modifier.weight(1f),
        )
        com.kachat.app.ui.theme.IosSwitch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}
