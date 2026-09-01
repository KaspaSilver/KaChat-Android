package com.kachat.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.Gravity
import android.view.WindowManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.SubcomposeAsyncImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.kachat.app.R
import com.kachat.app.models.Conversation
import com.kachat.app.models.MessageEntity
import com.kachat.app.models.ReactionEntity
import com.kachat.app.repository.ChatRepository
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.services.KnsInscriptionEngine
import com.kachat.app.services.KnsService
import com.kachat.app.services.UtxoEntry
import com.kachat.app.ui.theme.KaspaBlue
import com.kachat.app.ui.theme.KaspaSubtext
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ChatTimeFormat
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.rememberCameraCaptureLauncher
import com.kachat.app.util.authenticateWithDeviceCredential
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.ImagePrep
import com.kachat.app.util.MessageReply
import com.kachat.app.util.TextLinkify
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.copyPrivateKeyWithAutoWipe
import com.kachat.app.util.VoiceMessage
import com.kachat.app.util.VoiceMessageContent
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.viewmodels.ConnectionStatus as ConnStatus
import com.kachat.app.viewmodels.ConnectionViewModel
import com.kachat.app.viewmodels.NodeInfo
import com.kachat.app.viewmodels.SettingsViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.kachat.app.util.showAddressCopiedToast

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatThreadScreen(
    navController: NavController,
    contactId: String,
    chatViewModel: ChatViewModel = hiltViewModel(),
    connectionViewModel: ConnectionViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    portfolioViewModel: com.kachat.app.viewmodels.PortfolioViewModel = hiltViewModel(),
    startInPaymentMode: Boolean = false
) {
    val showFeeEstimate by settingsViewModel.showFeeEstimate.collectAsState()
    val conversations by chatViewModel.conversations.collectAsState()
    val conversation = conversations.find { it.contact.id == contactId }
    val allMessages by chatViewModel.getMessages(contactId).collectAsState(initial = emptyList())
    // "Sent via another device" rows are backup fill-in slots, never UI (matches iOS and the
    // chess mini chat); legacy restores from pre-1d236ba builds can still hold such rows.
    val messages = remember(allMessages) { allMessages.filterNot { it.isSentPlaceholder } }
    val reactions by chatViewModel.getReactions(contactId).collectAsState(initial = emptyList())
    val reactionsByTxId = remember(reactions) { reactions.groupBy { it.targetTxId } }
    val handshakeSendInFlight by chatViewModel.handshakeSendInFlight.collectAsState()
    val revealedPhotoTxIds by chatViewModel.revealedPhotoTxIds.collectAsState()

    val dotColorHex by connectionViewModel.dotColorHex.collectAsState()
    val spendingBalance by walletViewModel.spendingBalance.collectAsState()
    val spendingBalanceSompi by walletViewModel.spendingBalanceSompi.collectAsState()
    val myKnsProfile by walletViewModel.knsProfile.collectAsState()
    val myAddress by walletViewModel.address.collectAsState()
    val paymentAmount by chatViewModel.paymentAmount.collectAsState()
    val fiatPriceInCurrency by portfolioViewModel.currentPriceUsd.collectAsState()
    val fiatCurrencyCode by portfolioViewModel.currency.collectAsState()
    val estimatedFee by chatViewModel.estimatedFeeSompi.collectAsState()
    val messageText by chatViewModel.messageText.collectAsState()
    val voiceRecordingState by chatViewModel.voiceRecordingState.collectAsState()
    val pendingPhotoUri by chatViewModel.pendingPhotoUri.collectAsState()
    val replyingTo by chatViewModel.replyingTo.collectAsState()
    val kaspaExplorer by chatViewModel.kaspaExplorer.collectAsState()
    val networkFeeRate by chatViewModel.networkFeeRate.collectAsState()
    val feeRateOverride by chatViewModel.feeRateOverride.collectAsState()
    // Zero-balance funding gate — active only for a *confirmed* 0 KAS chatting balance
    // (unknown/still-loading never trips it); the helper also owns the on-entry refresh and
    // the 10s re-poll that dismisses the gate once funds arrive. Shared with group chat,
    // broadcast rooms and KaPosts — see GiftClaimUi.kt.
    val fundingGate = rememberZeroBalanceFundingGate()

    // rememberSaveable (not remember) so payment mode survives a push to Manage Spending
    // Addresses from the Available pill and back — iOS keeps payment mode alive by presenting
    // that screen as a sheet; on Android the nav round-trip must not silently drop the mode.
    var paymentMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(startInPaymentMode) }
    val paymentPrivacyOn by chatViewModel.chatsPaymentPrivacyOn.collectAsState()
    val paysToFreshPoolAddress by chatViewModel.paysToFreshPoolAddress.collectAsState()
    val identityFullBalance by walletViewModel.fullBalance.collectAsState()
    val identityBalanceSompi by walletViewModel.balanceSompi.collectAsState()
    var showComposerMenu by remember { mutableStateOf(false) }
    var composerMenuAnchor by remember { mutableStateOf(Offset.Zero) }
    // Second-step menu after tapping "Play Chess": pick a time control (3|2, 2|1, 1|1) or a
    // casual untimed game (the pre-timer behavior). Same CenteredOptionsMenu style, same anchor.
    var showChessTimeControlMenu by remember { mutableStateOf(false) }
    // "Send from Nextcloud" — only offered when a Nextcloud account is connected (Settings >
    // Storage > Nextcloud). Picking a file sends its public share link as a normal text message,
    // which the recipient's link-preview feature renders as tappable media.
    val nextcloudAccount by chatViewModel.nextcloud.account.collectAsState()
    var showNextcloudPicker by remember { mutableStateOf(false) }
    // Local-only multi-select for deleting individual messages (never the whole chat - see
    // ChatsScreen's own delete for that) - toggled from the top bar's "Select" action.
    var isSelectingMessages by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteMessagesConfirmation by remember { mutableStateOf(false) }
    var showFeeEditor by remember { mutableStateOf(false) }
    var feeEditorInput by remember { mutableStateOf("") }
    // The live fee preview already reflects whatever's currently being composed (text/photo/voice/
    // payment, each a different mass) — dividing it back out by the rate that produced it recovers
    // that mass without duplicating any of estimatedFeeSompi's own calculation here.
    val effectiveRate = feeRateOverride?.toDouble() ?: networkFeeRate
    val openFeeEditor: (Long) -> Unit = { currentFeeSompi ->
        feeEditorInput = "%.8f".format(java.util.Locale.US, currentFeeSompi / 100_000_000.0)
        showFeeEditor = true
    }
    val micContext = LocalContext.current
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) chatViewModel.startVoiceRecording(contactId)
    }
    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) chatViewModel.setPendingPhoto(uri)
    }
    val startCameraCapture = rememberCameraCaptureLauncher { uri -> chatViewModel.setPendingPhoto(uri) }
    val startVoiceRecordingIfPermitted = {
        if (chatViewModel.voiceRecordingSupported) {
            if (ContextCompat.checkSelfPermission(micContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                chatViewModel.startVoiceRecording(contactId)
            } else {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // Saving a photo to the gallery needs WRITE_EXTERNAL_STORAGE only on API 28 and below (scoped
    // storage makes MediaStore inserts permission-free from API 29 on) — the pending bytes/name
    // survive the async permission prompt in this state, then get saved once it resolves.
    var pendingPhotoSave by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    val writeStoragePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingPhotoSave
        pendingPhotoSave = null
        if (granted && pending != null) {
            val saved = ImagePrep.saveToGallery(micContext, pending.first, pending.second)
            Toast.makeText(micContext, if (saved) micContext.getString(R.string.photo_saved) else micContext.getString(R.string.could_not_save_photo), Toast.LENGTH_SHORT).show()
        }
    }
    val savePhotoIfPermitted = { bytes: ByteArray, fileName: String ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(micContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            val saved = ImagePrep.saveToGallery(micContext, bytes, fileName)
            Toast.makeText(micContext, if (saved) micContext.getString(R.string.photo_saved) else micContext.getString(R.string.could_not_save_photo), Toast.LENGTH_SHORT).show()
        } else {
            pendingPhotoSave = bytes to fileName
            writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    LaunchedEffect(contactId) {
        chatViewModel.markAsRead(contactId)
    }

    // Keeps this contact's `knsAvatarUrl` (read by `contactAvatarUrl` below) current - it's
    // otherwise only ever refreshed from ChatInfoScreen, so a contact whose Chat Info was never
    // opened would never get their real KNS avatar here, even mid-conversation. Matches iOS's
    // fetchKNSDomainsForAllContacts, which keeps the avatar cache warm unconditionally.
    LaunchedEffect(contactId) {
        chatViewModel.refreshKnsProfile(contactId)
    }

    DisposableEffect(contactId) {
        chatViewModel.setActiveContact(contactId)
        onDispose { chatViewModel.setActiveContact(null) }
    }

    // System-share intake (see ShareIntake/MainActivity.handleShareIntent): an UNTARGETED share
    // (user tapped the plain "KaChat" target, then picked this chat from the Chats list's "choose
    // a chat" banner) now has its destination — hand it to the in-place compose sheet
    // (ShareComposeSheet, rendered by KaChatApp) rather than silently pre-filling this composer.
    // Direct-share picks never reach here: they already carry their conversation and go straight
    // to the sheet from KaChatApp.
    val pendingShareContent by com.kachat.app.services.ShareIntake.pending.collectAsState()
    LaunchedEffect(pendingShareContent, contactId) {
        val share = pendingShareContent ?: return@LaunchedEffect
        if (share.targetContactId != null && share.targetContactId != contactId) return@LaunchedEffect
        com.kachat.app.services.ShareIntake.pending.value = null
        if (share.isExpired()) return@LaunchedEffect
        // A share always lands in the message composer flow, never the payment-entry UI.
        paymentMode = false
        com.kachat.app.services.ShareIntake.compose.value = com.kachat.app.services.ShareCompose(
            contactId = contactId,
            text = share.text,
            imageUris = share.imageUris
        )
    }

    LaunchedEffect(paymentMode) {
        if (paymentMode) {
            chatViewModel.refreshSpendingUtxos()
            walletViewModel.refreshSpendingAddress()
            // Privacy OFF funds payments from the chatting address — keep its balance fresh too.
            walletViewModel.refreshBalance()
            chatViewModel.refreshFreshPoolIndicator(contactId)
        }
    }

    // The Available pill tracks rotation change landing after a private-mode send: whenever an
    // own-address balance change involves the current spending address (AddressActivityNotifier's
    // always-on UI event, separate from its notification decision), re-fetch the shown balance.
    LaunchedEffect(paymentMode) {
        if (!paymentMode) return@LaunchedEffect
        chatViewModel.ownAddressUtxoActivityEvents.collect { involved ->
            val currentSpending = walletViewModel.spendingAddress.value
            if (currentSpending == null || involved.contains(currentSpending)) {
                walletViewModel.refreshSpendingAddress()
                chatViewModel.refreshSpendingUtxos()
            }
        }
    }

    // Drives the toolbar's quick-access chess icon - null hides it entirely. Computed up here
    // (rather than reusing the content lambda's own `chessSourceMessages` below) since `topBar`
    // is a sibling Composable lambda, not nested inside the content lambda, so that one isn't in
    // scope here.
    val activeChessGame = remember(messages, myAddress) {
        val address = myAddress ?: return@remember null
        val sourceMessages = messages.map {
            com.kachat.app.util.ChessGameEngine.SimpleChessSourceMessage(
                id = it.id,
                plaintextBody = it.plaintextBody,
                isOutgoing = it.direction == "sent",
                blockTimestamp = it.blockTimestamp
            )
        }
        com.kachat.app.util.ChessGameEngine.activeGame(sourceMessages, address, contactId)
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            navController.navigate("chat_info/$contactId")
                        }
                    ) {
                        ContactAvatar(
                            imageUrl = conversation?.contact?.knsAvatarUrl,
                            deviceContactPhotoUri = conversation?.contact?.systemContactPhotoUri,
                            fallbackText = conversation?.contact?.alias ?: contactId.takeLast(8),
                            size = 36.dp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = conversation?.contact?.alias ?: com.kachat.app.util.KaspaAddress.shortDisplay(contactId),
                            color = LocalAppColors.current.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                },
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                        }
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
                    } else if (activeChessGame != null) {
                        // Entry point into select mode is a message's long-press "Select" menu
                        // item, not a toolbar button - this only shows the chess shortcut now.
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(LocalAppColors.current.surface, CircleShape)
                                .clickable { navController.navigate("chess_game/$contactId/${activeChessGame.gameId}") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Apps, stringResource(R.string.play_chess), tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        bottomBar = {
            // navigationBarsPadding() keeps the mic/send row clear of the system nav bar
            // (gesture pill or 3-button bar) when the keyboard is closed — its height varies
            // a lot across devices/manufacturers, so a fixed dp padding isn't enough on every
            // phone. imePadding() on the Scaffold above already handles the keyboard-open case.
            // Zero-balance funding gate: the whole composer (text field, camera, "+" menu, send,
            // mic, payment entry) dims and stops responding until the chatting address is funded
            // — see Modifier.zeroBalanceComposerGate in GiftClaimUi.kt.
            Column(modifier = Modifier.background(LocalAppColors.current.background).navigationBarsPadding().padding(8.dp).zeroBalanceComposerGate(fundingGate.active)) {
                // Above everything else in the composer stack (including payment mode's fee /
                // available pills) so it never collides with them, and visible the instant the
                // chat opens rather than only once the user starts typing.
                if (ChatViewModel.shouldShowUnnotifiedWarning(messages)) {
                    // Same guard the composer menu's "Send Handshake" row uses — once the
                    // handshake is complete there's nothing left to ping, so the action drops
                    // away while the banner itself stays until the chat is actually reciprocated.
                    val canSendHandshake = conversation?.contact?.handshakeComplete != true
                    val handshakeAwaitingReply = ChatViewModel.hasUnansweredOutgoingHandshake(messages)
                    UnnotifiedMessageBanner(
                        onSendHandshake = if (canSendHandshake && !handshakeAwaitingReply) {
                            ({ chatViewModel.sendHandshake(contactId) })
                        } else null,
                        isSendingHandshake = contactId in handshakeSendInFlight,
                        // The request is out and unanswered: say so rather than offering a second
                        // 0.2 KAS send that the unchanged banner makes look necessary.
                        awaitingReply = canSendHandshake && handshakeAwaitingReply
                    )
                }
                if (paymentMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (showFeeEstimate && estimatedFee != null) {
                                Surface(
                                    color = LocalAppColors.current.surface,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.clickable { openFeeEditor(estimatedFee ?: 0L) }
                                ) {
                                    Text(
                                        text = "fee: ${ChatRepository.formatKas(estimatedFee ?: 0L)} KAS",
                                        color = KaspaTeal,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            
                            // Available pill: primary spending balance when Chats Payment Privacy
                            // is ON (underlined + tappable, opens Manage Spending Addresses),
                            // chatting balance when OFF (plain, not tappable). The fresh-address
                            // indicator is merged into this pill (a small accent arrow) so the
                            // helper row never clips on narrow screens; the pill itself absorbs
                            // any squeeze by tail-truncating.
                            Surface(
                                color = LocalAppColors.current.surface,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .then(
                                            if (paymentPrivacyOn) Modifier.clickable { navController.navigate("manage_addresses") }
                                            else Modifier
                                        )
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "available: ${if (paymentPrivacyOn) spendingBalance else identityFullBalance}",
                                        color = LocalAppColors.current.textSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textDecoration = if (paymentPrivacyOn) androidx.compose.ui.text.style.TextDecoration.Underline else null
                                    )
                                    if (paymentPrivacyOn && paysToFreshPoolAddress) {
                                        Icon(
                                            Icons.Filled.ArrowForward,
                                            contentDescription = "Payment goes to a fresh address this contact shared, so it cannot be linked to their chat address on-chain",
                                            tint = KaspaTeal,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val fiatAmountState = com.kachat.app.util.rememberKaspaFiatAmountState(
                                onKasTextChange = { chatViewModel.setPaymentAmount(it) }
                            )
                            TextField(
                                value = fiatAmountState.displayText,
                                onValueChange = { fiatAmountState.onDisplayTextChange(it, fiatPriceInCurrency) },
                                placeholder = {
                                    Text(
                                        if (fiatAmountState.isFiatMode) fiatCurrencyCode.uppercase() else stringResource(R.string.amount_kas),
                                        color = Color.DarkGray
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .clip(RoundedCornerShape(25.dp)),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = LocalAppColors.current.surface,
                                    unfocusedContainerColor = LocalAppColors.current.surface,
                                    focusedTextColor = LocalAppColors.current.textPrimary,
                                    unfocusedTextColor = LocalAppColors.current.textPrimary,
                                    cursorColor = KaspaTeal,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                // Toggles KAS/fiat entry mode, matching Cold Storage's send flow -
                                // the leading icon is the toggle now, so the conversion label in
                                // trailingIcon below is purely informational.
                                leadingIcon = {
                                    IconButton(onClick = { fiatAmountState.toggleMode(fiatPriceInCurrency) }) {
                                        if (fiatAmountState.isFiatMode) {
                                            Text(
                                                com.kachat.app.util.currencySymbolFor(fiatCurrencyCode),
                                                color = KaspaTeal,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        } else {
                                            Icon(
                                                painterResource(R.drawable.ic_kaspa_logo),
                                                stringResource(R.string.switch_between_kas_and_fiat),
                                                tint = Color.Unspecified,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                },
                                trailingIcon = {
                                    // Spending-chain UTXOs (what a payment actually spends from),
                                    // not chatViewModel.currentUtxos (the identity address's) -
                                    // using the wrong set here made "Max" compute against the
                                    // wrong balance whenever the two addresses' UTXOs differed.
                                    val spendingUtxos by chatViewModel.spendingUtxos.collectAsState()
                                    val networkFeeRate by chatViewModel.networkFeeRate.collectAsState()
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        fiatAmountState.conversionLabelText(fiatPriceInCurrency, fiatCurrencyCode)?.let { label ->
                                            Text(
                                                label,
                                                color = LocalAppColors.current.textSecondary,
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(end = 8.dp)
                                            )
                                        }
                                        TextButton(onClick = {
                                            // Mirror KaspaWalletEngine's own fee calculation exactly
                                            // (real Kaspa mass model, assuming a recipient + change
                                            // output) so the amount filled in here is always actually
                                            // sendable — the previous naive formula (300 + count*100)
                                            // didn't match the real fee, so "Max" sends kept failing
                                            // with "insufficient funds".
                                            val mass = com.kachat.app.util.KaspaMass.calculateMass(
                                                numInputs = spendingUtxos.size.coerceAtLeast(1),
                                                outputScriptLens = listOf(34, 34),
                                                payloadSize = 0
                                            )
                                            val fee = com.kachat.app.util.KaspaMass.calculateFee(mass, networkFeeRate.toLong())

                                            // Same source the send will use: primary spending
                                            // balance with privacy ON, chatting balance with
                                            // privacy OFF (spendingUtxos already tracks the same
                                            // funding source - see refreshSpendingUtxos).
                                            val sourceBalanceSompi = if (paymentPrivacyOn) spendingBalanceSompi else identityBalanceSompi
                                            val maxSendableSompi = (sourceBalanceSompi - fee).coerceAtLeast(0L)
                                            val maxSendableKas = maxSendableSompi.toDouble() / 100_000_000.0
                                            fiatAmountState.setMaxKas(maxSendableKas, fiatPriceInCurrency)
                                        }) {
                                            Text(stringResource(R.string.max), color = KaspaTeal, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                            
                            // Deliberately no mic in payment mode (matches iOS): the message
                            // button is the only mode exit — audio stays reachable through
                            // message mode's "+" menu as usual.
                            ChatActionButton(Icons.AutoMirrored.Filled.Chat, onClick = {
                                paymentMode = false
                                chatViewModel.setPaymentAmount("")
                            })
                        }

                        Button(
                            onClick = {
                                if (paymentAmount.isNotEmpty()) {
                                    chatViewModel.sendPayment(contactId, paymentAmount)
                                    chatViewModel.setPaymentAmount("")
                                    paymentMode = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.send_payment), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (pendingPhotoUri != null) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (showFeeEstimate && estimatedFee != null) {
                            Surface(
                                color = LocalAppColors.current.surface,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(bottom = 8.dp)
                                    .clickable { openFeeEditor(estimatedFee ?: 0L) }
                            ) {
                                Text(
                                    text = "fee: ${ChatRepository.formatKas(estimatedFee ?: 0L)} KAS",
                                    color = KaspaTeal,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }
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
                            IconButton(onClick = { chatViewModel.cancelPendingPhoto() }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cancel_photo), tint = Color(0xFFFF3B30))
                            }
                            val thumbnailContext = LocalContext.current
                            // Fixed downsample for a quick composition-time thumbnail decode — the real
                            // compression (ImagePrep.prepareForChatMessage) happens off the main thread
                            // in the ViewModel when Send is tapped, this is display-only.
                            // Decode off the main thread (contentResolver I/O + BitmapFactory) so
                            // attaching a photo can't hitch composition; matches the incoming-photo
                            // produceState pattern used elsewhere in this file.
                            val thumbnailState = produceState<android.graphics.Bitmap?>(initialValue = null, pendingPhotoUri) {
                                value = pendingPhotoUri?.let { uri ->
                                    withContext(Dispatchers.IO) {
                                        try {
                                            thumbnailContext.contentResolver.openInputStream(uri)?.use {
                                                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
                                            }
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }
                                }
                            }
                            val thumbnail = thumbnailState.value
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
                                onClick = { chatViewModel.sendPendingPhoto(contactId) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(KaspaTeal, CircleShape)
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
                } else if (voiceRecordingState.status == ChatViewModel.VoiceRecordingStatus.RECORDING) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (showFeeEstimate && estimatedFee != null) {
                            Surface(
                                color = LocalAppColors.current.surface,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(bottom = 8.dp)
                                    .clickable { openFeeEditor(estimatedFee ?: 0L) }
                            ) {
                                Text(
                                    text = "fee: ${ChatRepository.formatKas(estimatedFee ?: 0L)} KAS",
                                    color = KaspaTeal,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }
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
                            IconButton(onClick = { chatViewModel.cancelVoiceRecording() }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cancel_recording), tint = Color(0xFFFF3B30))
                            }
                            Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
                            Text(
                                text = "Recording... ${formatRecordingElapsed(voiceRecordingState.elapsedMs)}",
                                color = LocalAppColors.current.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { chatViewModel.stopAndSendVoiceRecording(contactId) },
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
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        replyingTo?.let { reply ->
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
                                    Text(
                                        "Replying to ${if (reply.direction == "sent") "yourself" else (conversation?.contact?.alias ?: com.kachat.app.util.KaspaAddress.shortDisplay(contactId))}",
                                        color = KaspaTeal,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        VoiceMessage.parseOrNull(reply.plaintextBody)?.let { "🎤 Audio message" }
                                            ?: ImageMessage.parseOrNull(reply.plaintextBody)?.let { "📷 Photo" }
                                            ?: MessageReply.parseOrNull(reply.plaintextBody)?.text
                                            ?: (reply.plaintextBody ?: ""),
                                        color = LocalAppColors.current.textSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { chatViewModel.cancelReply() }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel_reply), tint = LocalAppColors.current.textSecondary)
                                }
                            }
                        }
                        if (showFeeEstimate && estimatedFee != null && messageText.isNotEmpty()) {
                            Surface(
                                color = LocalAppColors.current.surface,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(bottom = 8.dp)
                                    .clickable { openFeeEditor(estimatedFee ?: 0L) }
                            ) {
                                Text(
                                    text = "fee: ${ChatRepository.formatKas(estimatedFee ?: 0L)} KAS",
                                    color = KaspaTeal,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextField(
                                value = messageText,
                                onValueChange = { chatViewModel.setMessageText(it) },
                                placeholder = { Text(stringResource(R.string.message), color = LocalAppColors.current.textSecondary) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 40.dp)
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
                                // since it's the most common non-text action. The Kaspa logo
                                // beside it jumps straight into payment mode - the exact same
                                // switch the "+" menu's Send Kaspa entry flips, one tap instead
                                // of two (iOS places the same shortcut inside its input bubble).
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { startCameraCapture() }) {
                                            Icon(
                                                Icons.Default.CameraAlt,
                                                contentDescription = stringResource(R.string.camera),
                                                tint = LocalAppColors.current.textSecondary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                        IconButton(onClick = { startVoiceRecordingIfPermitted() }) {
                                            Icon(
                                                Icons.Default.Mic,
                                                contentDescription = stringResource(R.string.send_audio_message),
                                                tint = LocalAppColors.current.textSecondary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                        IconButton(onClick = { paymentMode = true }) {
                                            Icon(
                                                painterResource(R.drawable.ic_kaspa_logo),
                                                contentDescription = stringResource(R.string.send_kaspa),
                                                tint = Color.Unspecified,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                },
                                maxLines = 4
                            )

                            if (messageText.isEmpty()) {
                                Box(
                                    modifier = Modifier.onGloballyPositioned { coords ->
                                        // Top edge, not bottom — this button sits near the bottom of the
                                        // screen, so the menu opens upward from just above it instead of
                                        // downward over the message field/tab bar.
                                        composerMenuAnchor = coords.positionInWindow()
                                    }
                                ) {
                                    // Slightly smaller than the stock 40dp so the input bubble gets the width.
                                    ChatActionButton(Icons.Default.Add, onClick = { showComposerMenu = true }, size = 34.dp, iconSize = 18.dp)
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
                                        // Send Kaspa left this menu: the Kaspa logo inside the
                                        // input bubble is the one entry point to payment mode now.
                                        HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                        PopupMenuRow(Icons.Default.Apps, stringResource(R.string.play_chess)) {
                                            showComposerMenu = false
                                            showChessTimeControlMenu = true
                                        }
                                        if (conversation?.contact?.handshakeComplete != true &&
                                            !ChatViewModel.hasUnansweredOutgoingHandshake(messages)
                                        ) {
                                            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                            PopupMenuRow(Icons.Default.BackHand, stringResource(R.string.send_handshake)) {
                                                showComposerMenu = false
                                                chatViewModel.sendHandshake(contactId)
                                            }
                                        }
                                    }
                                }
                                if (showChessTimeControlMenu) {
                                    // Timed options carry the clock icon; the untimed row keeps
                                    // the chess menu's own icon so "Casual" reads as today's
                                    // plain game. Minutes/increment pairs match iOS exactly.
                                    CenteredOptionsMenu(onDismissRequest = { showChessTimeControlMenu = false }, anchor = composerMenuAnchor) {
                                        for ((minutes, increment) in listOf(3 to 2, 2 to 1, 1 to 1)) {
                                            PopupMenuRow(Icons.Default.Timer, "$minutes | $increment") {
                                                showChessTimeControlMenu = false
                                                chatViewModel.startChessGame(contactId, tcMinutes = minutes, tcIncSeconds = increment)
                                            }
                                            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                        }
                                        PopupMenuRow(Icons.Default.Apps, "Casual (no timer)") {
                                            showChessTimeControlMenu = false
                                            chatViewModel.startChessGame(contactId)
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
                                            val current = messageText.trim()
                                            chatViewModel.setMessageText(if (current.isEmpty()) link else "$current $link")
                                        }
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        chatViewModel.sendMessage(contactId, messageText)
                                        chatViewModel.setMessageText("")
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
    ) { padding ->
        val scrollState = rememberLazyListState()
        val coroutineScope = rememberCoroutineScope()
        var highlightedMessageId by remember { mutableStateOf<String?>(null) }
        val jumpToReply: (String) -> Unit = { targetId ->
            val index = messages.indexOfFirst { it.id == targetId }
            if (index >= 0) {
                coroutineScope.launch {
                    scrollState.animateScrollToItem(index)
                    highlightedMessageId = targetId
                    delay(1200)
                    if (highlightedMessageId == targetId) highlightedMessageId = null
                }
            } else {
                Toast.makeText(micContext, micContext.getString(R.string.original_message_not_available), Toast.LENGTH_SHORT).show()
            }
        }

        // Auto-scroll to bottom when new messages arrive. The very first population of the list
        // (opening the chat) jumps instantly instead of animating - the LazyColumn otherwise
        // renders at the top first, and animating from there visibly scrolls through the whole
        // history before settling at the bottom. After that, an arrival only auto-scrolls when
        // the reader is already at (or within a row of) the bottom, or when the newest message
        // is their own send. Scrolled up reading history, the viewport stays put - LazyColumn
        // keeps its own anchor when items are appended below the fold, and the scroll-to-latest
        // button is the way back down. (The 2s open-chat poll and the live mirror both land
        // here, so the old unconditional scroll yanked the viewport on every insert.)
        val userIsDraggingList by scrollState.interactionSource.collectIsDraggedAsState()
        var hasScrolledToInitialPosition by remember { mutableStateOf(false) }
        // Message count as of the previous auto-scroll decision. "Was at bottom" is measured
        // against THIS count, not the new one - when the effect fires the just-inserted rows
        // haven't laid out yet, so the last visible index still refers to the pre-insert list,
        // and a multi-message catch-up batch would otherwise fail the gate for a reader who
        // was genuinely pinned to the end.
        var autoScrollBaselineCount by remember { mutableStateOf(0) }
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                if (!hasScrolledToInitialPosition) {
                    scrollState.scrollToItem(messages.size - 1)
                    hasScrolledToInitialPosition = true
                } else if (messages.size > autoScrollBaselineCount) {
                    val lastVisible = scrollState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    // Same 1-item tolerance as showScrollToBottom below - keyboard/fee-row
                    // resizes can hide the very last row without any real scroll-up.
                    val wasAtBottom = lastVisible >= autoScrollBaselineCount - 2
                    // Sent on THIS device, just now - not merely direction=="sent". Every
                    // optimistic local send (text, voice, photo, chess, payment) inserts its
                    // row under a synthetic provisional id before the broadcast returns, and
                    // that id is transient device-local state a mirror import can never carry
                    // (see MessageEntity.PROVISIONAL_ID_PREFIX). An own message mirrored in
                    // from another device arrives under its real txId, so it goes through the
                    // at-bottom gate like any other insert instead of yanking the viewport
                    // away from history.
                    val newest = messages.last()
                    val sentFromThisDevice = newest.direction == "sent" &&
                        MessageEntity.isProvisionalId(newest.id)
                    // Never fight an active finger drag - starting a programmatic animated
                    // scroll mid-drag both stutters and steals the gesture. Local sends are
                    // exempt: they come from the send button, not from a drag.
                    if (sentFromThisDevice || (wasAtBottom && !userIsDraggingList)) {
                        scrollState.animateScrollToItem(messages.size - 1)
                    }
                }
                autoScrollBaselineCount = messages.size
            }
        }

        // Also re-pin to the bottom when the keyboard's own inset animation finishes (not
        // just when messageText changes — the IME resize is system-driven and can complete
        // after Compose's own recomposition, so keying on messageText alone raced with it
        // and left the latest message hidden behind the keyboard without a real scroll-up).
        // Gated the same way as arrivals: only re-pin when already at (or within a row of)
        // the bottom - opening the keyboard while scrolled up reading history must not yank
        // the viewport down.
        val imeVisible = WindowInsets.isImeVisible
        LaunchedEffect(imeVisible, messageText.isEmpty()) {
            if (messages.isNotEmpty()) {
                val lastVisible = scrollState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                if (lastVisible >= messages.lastIndex - 1) {
                    scrollState.animateScrollToItem(messages.size - 1)
                }
            }
        }

        // Not scrollState.canScrollForward — that flips true for a frame or two whenever
        // the viewport merely shrinks (keyboard opening, the fee-estimate row appearing),
        // even though the user never scrolled and is still logically at the latest
        // message. Only show the button once the last message isn't even partially
        // among the visible items — a real, deliberate scroll away from the bottom.
        // Tolerate a 1-item gap — the keyboard/fee-row resize can leave the second-to-last
        // message as the last fully visible one for a moment even when the re-pin effect
        // above hasn't finished animating yet. A genuine scroll-up to read history moves
        // by much more than one item, so this threshold still catches real cases.
        val showScrollToBottom by remember {
            derivedStateOf {
                val lastVisibleIndex = scrollState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                lastVisibleIndex != null && lastVisibleIndex < messages.lastIndex - 1
            }
        }

        // Swipe-left-to-reveal-timestamps (iMessage-style) — see the matching implementation in
        // BroadcastScreens.kt for the full rationale; kept in sync with it.
        val revealOffsetPx = remember { Animatable(0f) }
        val maxRevealOffsetPx = with(LocalDensity.current) { 64.dp.toPx() }
        // True only while a finger is actively dragging the reveal. Guards every snapTo so a
        // straggler delta (launched during the drag but dispatched after release) can never
        // cancel the settle animation — Animatable mutations are mutually exclusive, and that
        // cancellation is exactly what used to leave the reveal stuck mid-swipe.
        val isRevealDragging = remember { mutableStateOf(false) }
        // Release ALWAYS springs the rows back; a vertical scroll stealing the gesture (which
        // can end the drag without a clean stop) forces the same settle.
        LaunchedEffect(isRevealDragging.value, scrollState.isScrollInProgress) {
            if (scrollState.isScrollInProgress) isRevealDragging.value = false
            if (!isRevealDragging.value) revealOffsetPx.animateTo(0f)
        }

        // Computed here (not inside the LazyColumn content below) - LazyListScope's item-builder
        // lambda isn't a real @Composable context, so a bare remember() call in it fails to
        // compile ("@Composable invocations can only happen from the context of a @Composable
        // function").
        val chessSourceMessages = remember(messages) {
            messages.map {
                com.kachat.app.util.ChessGameEngine.SimpleChessSourceMessage(
                    id = it.id,
                    plaintextBody = it.plaintextBody,
                    isOutgoing = it.direction == "sent",
                    blockTimestamp = it.blockTimestamp
                )
            }
        }

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
                state = scrollState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.no_messages_yet),
                                color = LocalAppColors.current.textSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    itemsIndexed(messages, key = { _, msg -> msg.id }) { index, msg ->
                        if (index == 0 || !ChatTimeFormat.isSameDay(messages[index - 1].blockTimestamp, msg.blockTimestamp)) {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Surface(color = LocalAppColors.current.surface, shape = RoundedCornerShape(12.dp)) {
                                    Text(
                                        ChatTimeFormat.formatDateDivider(msg.blockTimestamp),
                                        color = LocalAppColors.current.textSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        val chessEnvelopeForRow = remember(msg.plaintextBody) {
                            com.kachat.app.util.ChessMessage.parseOrNull(
                                MessageReply.parseOrNull(msg.plaintextBody)?.text ?: msg.plaintextBody
                            )
                        }
                        val chessSummaryForRow = remember(chessEnvelopeForRow, chessSourceMessages, myAddress) {
                            val address = myAddress
                            if (chessEnvelopeForRow != null && address != null) {
                                com.kachat.app.util.ChessGameEngine.summarize(chessEnvelopeForRow.gameId, chessSourceMessages, address, contactId)
                            } else {
                                null
                            }
                        }
                        val isLatestChessForRow = remember(chessEnvelopeForRow, chessSourceMessages) {
                            chessEnvelopeForRow != null && com.kachat.app.util.ChessGameEngine.isLatestChessMessage(
                                com.kachat.app.util.ChessGameEngine.SimpleChessSourceMessage(
                                    id = msg.id,
                                    plaintextBody = msg.plaintextBody,
                                    isOutgoing = msg.direction == "sent",
                                    blockTimestamp = msg.blockTimestamp
                                ),
                                chessSourceMessages
                            )
                        }
                        Box {
                            MessageBubble(
                                message = msg,
                                chessSummary = chessSummaryForRow,
                                isLatestChessMessage = isLatestChessForRow,
                                onRespondToChessInvite = { accepted ->
                                    val gameId = chessEnvelopeForRow?.gameId
                                    if (gameId != null) {
                                        chatViewModel.respondToChessInvite(contactId, gameId, accepted)
                                    }
                                },
                                onOpenChessGame = { gameId -> navController.navigate("chess_game/$contactId/$gameId") },
                                contactAvatarUrl = conversation?.contact?.knsAvatarUrl,
                                contactPhotoUri = conversation?.contact?.systemContactPhotoUri,
                                contactAvatarFallback = conversation?.contact?.alias ?: contactId.takeLast(8),
                                myAvatarUrl = myKnsProfile?.avatarUrl,
                                myAvatarFallback = myAddress?.takeLast(8) ?: "",
                                isPendingRequest = msg.type == MessageProtocol.TYPE_HANDSHAKE &&
                                    msg.direction == "received" &&
                                    conversation?.contact?.conversationStatus == "pending",
                                isHandshakeComplete = conversation?.contact?.conversationStatus == "active",
                                onAccept = { chatViewModel.acceptHandshake(contactId) },
                                onDecline = { chatViewModel.declineHandshake(contactId) },
                                onRetry = { chatViewModel.retrySendMessage(msg) },
                                onReply = { chatViewModel.startReplyTo(msg) },
                                reactions = reactionsByTxId[msg.id] ?: emptyList(),
                                myReactorAddress = myAddress,
                                onReact = { emoji ->
                                    val existing = reactionsByTxId[msg.id]?.find { it.reactorAddress == myAddress }
                                    val action = if (existing?.emoji == emoji) "remove" else "add"
                                    chatViewModel.sendReaction(contactId, msg.id, emoji, action)
                                },
                                onRetryReaction = { reaction ->
                                    chatViewModel.retryReaction(contactId, reaction.targetTxId, reaction.emoji, reaction.failedAction ?: "add")
                                },
                                onSavePhoto = savePhotoIfPermitted,
                                revealOffsetPx = revealOffsetPx,
                                maxRevealOffsetPx = maxRevealOffsetPx,
                                photosBlocked = !com.kachat.app.repository.ChatRepository.shouldAutoDisplayPhotos(
                                    conversation?.contact
                                ),
                                isPhotoRevealed = msg.id in revealedPhotoTxIds,
                                onRevealPhoto = { chatViewModel.revealPhoto(msg.id) },
                                kaspaExplorer = kaspaExplorer,
                                onJumpToReply = jumpToReply,
                                isHighlighted = msg.id == highlightedMessageId,
                                onSelect = {
                                    isSelectingMessages = true
                                    selectedMessageIds = selectedMessageIds + msg.id
                                }
                            )
                            // Selection-mode tap catcher - sits on top (Box's later children draw
                            // over earlier ones, and Compose dispatches touches to the topmost
                            // hit-testable composable), so it intercepts taps instead of the
                            // bubble's own gestures (links, double-tap-to-react, long-press menu)
                            // while selecting.
                            if (isSelectingMessages) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clickable {
                                            selectedMessageIds = if (msg.id in selectedMessageIds) {
                                                selectedMessageIds - msg.id
                                            } else {
                                                selectedMessageIds + msg.id
                                            }
                                        }
                                ) {
                                    Icon(
                                        imageVector = if (msg.id in selectedMessageIds) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (msg.id in selectedMessageIds) KaspaTeal else Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showScrollToBottom && messages.isNotEmpty()) {
                IconButton(
                    onClick = {
                        coroutineScope.launch { scrollState.animateScrollToItem(messages.size - 1) }
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

            // Zero-balance funding gate card — floats over the (usually still empty) thread
            // while the composer below sits dimmed and inert. Deliberately not full-screen:
            // received messages stay readable and scrollable around it, only sending is gated.
            // Gone reactively the moment the chatting balance confirms as > 0.
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
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                    val currentFeeSompi = estimatedFee ?: 0L
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
                    "This only deletes the message from this device - the recipient still has their own copy, and the encrypted transaction remains permanently on the Kaspa blockchain, visible to anyone but unreadable without your keys. This cannot be undone.",
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    chatViewModel.deleteMessages(selectedMessageIds)
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

/**
 * The new-chat handshake warning, matching desktop's `handshake-warning-banner` word for word.
 *
 * Sits directly above the composer and is visible from the moment a 1:1 chat opens (it is not
 * gated on typing) until the conversation is genuinely reciprocated — see
 * [ChatViewModel.shouldShowUnnotifiedWarning] for the exact rule. Styled off the theme's own
 * surface/warning tokens like the app's other inline banners, rather than the hardcoded orange
 * this used before.
 */
@Composable
private fun UnnotifiedMessageBanner(
    /** Null hides the action — nothing to offer once the handshake is already complete. */
    onSendHandshake: (() -> Unit)? = null,
    isSendingHandshake: Boolean = false,
    /** A handshake is already out and unanswered: show what happened, offer no second send. */
    awaitingReply: Boolean = false
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = colors.warning,
                modifier = Modifier.size(16.dp).padding(top = 2.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.the_recipient_wont_see_your_messages),
                color = colors.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (onSendHandshake != null || awaitingReply) {
            Spacer(Modifier.height(8.dp))
            // Compact accent pill on its own row rather than beside the text — the copy runs to
            // three lines, so an end-aligned button would squeeze it badly on narrow screens.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    color = if (isSendingHandshake || awaitingReply) colors.accent.copy(alpha = 0.4f) else colors.accent,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.clickable(
                        enabled = !isSendingHandshake && onSendHandshake != null,
                        onClick = onSendHandshake ?: {},
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSendingHandshake) {
                            CircularProgressIndicator(
                                color = colors.textOnAccent,
                                strokeWidth = 1.5.dp,
                                modifier = Modifier.size(12.dp)
                            )
                        } else {
                            Icon(
                                imageVector = if (awaitingReply) Icons.Default.Check else Icons.Default.BackHand,
                                contentDescription = null,
                                tint = colors.textOnAccent,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                if (awaitingReply) R.string.handshake_sent else R.string.send_handshake
                            ),
                            color = colors.textOnAccent,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/** See the truncation check inside [MessageBubble]'s plain-text branch for why this exists. Also used by BroadcastScreens.kt's room bubble. */
const val MESSAGE_TEXT_TRUNCATION_THRESHOLD = 2_000
const val MESSAGE_TEXT_PREVIEW_LENGTH = 500

/**
 * Splits a payment bubble's display text into (amountText, note) for the rich payment card —
 * ported from iOS's `paymentCardParts`. NOT regex/word-based, so it's locale-proof: the head
 * (before an optional " — " em-dash separator) contributes its first whitespace-separated token
 * that parses as a number (after `,` -> `.`); everything after the separator is the note.
 * Returns null (text-bubble fallback) for oversized or non-numeric content.
 */
internal fun parsePaymentCardParts(content: String?): Pair<String, String?>? {
    if (content == null || content.length > 512) return null
    val pieces = content.split(" — ")
    val head = pieces.first()
    val amountToken = head.split(Regex("\\s+")).firstOrNull { token ->
        token.replace(",", ".").toDoubleOrNull() != null
    } ?: return null
    val note = pieces.drop(1).joinToString(" — ").trim().ifEmpty { null }
    return amountToken to note
}

/**
 * Apple-Pay-style card for 1:1 payment messages — iOS's `paymentCardBubble` ported to Compose.
 * Sent: teal gradient background, Kaspa logo in a SOLID WHITE circle (translucent white made the
 * teal logo invisible on teal); received: neutral surface with teal accents. Used for detected
 * payments AND pool payment_notice bubbles (both are `type == "pay"`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PaymentCardBubble(
    amountText: String,
    note: String?,
    isSent: Boolean,
    isWarning: Boolean = false,
    onLongPress: () -> Unit = {},
    onDoubleClick: () -> Unit = {}
) {
    val shape = RoundedCornerShape(18.dp)
    val background = if (isSent) {
        Modifier.background(
            androidx.compose.ui.graphics.Brush.linearGradient(
                colors = listOf(KaspaTeal, KaspaTeal.copy(alpha = 0.78f))
            ),
            shape
        )
    } else {
        Modifier.background(LocalAppColors.current.surface, shape)
    }
    val strokeColor = if (isSent) Color.White.copy(alpha = 0.22f) else KaspaTeal.copy(alpha = 0.35f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .widthIn(min = 170.dp, max = 300.dp)
            .clip(shape)
            .then(background)
            .border(0.8.dp, strokeColor, shape)
            .combinedClickable(onClick = {}, onLongClick = onLongPress, onDoubleClick = onDoubleClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(if (isSent) Color.White else KaspaTeal.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_kaspa_logo),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(34.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (isSent) "Sent" else "Received",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isSent) Color.White.copy(alpha = 0.85f) else LocalAppColors.current.textSecondary
            )
            Text(
                text = "$amountText KAS",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isSent) Color.White else LocalAppColors.current.textPrimary
            )
            if (note != null) {
                Text(
                    text = note,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSent) Color.White.copy(alpha = 0.8f) else LocalAppColors.current.textSecondary
                )
            }
            if (isWarning) {
                // payment_notice chain verification found no output to the claimed address.
                Text(
                    text = "⚠︎ Unverified on-chain",
                    fontSize = 11.sp,
                    color = if (isSent) Color.White.copy(alpha = 0.9f) else Color(0xFFF39C12)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: MessageEntity,
    contactAvatarUrl: String? = null,
    /** Device address-book photo of this contact — the fallback when they have no KNS avatar. */
    contactPhotoUri: String? = null,
    contactAvatarFallback: String = "",
    myAvatarUrl: String? = null,
    myAvatarFallback: String = "",
    isPendingRequest: Boolean = false,
    isHandshakeComplete: Boolean = false,
    onAccept: () -> Unit = {},
    onDecline: () -> Unit = {},
    onRetry: () -> Unit = {},
    onReply: () -> Unit = {},
    /** This message's current reactions (one per reactor - see [ReactionEntity]), for the pill
     *  rendered on its corner and to know whether tapping an emoji in the quick-reaction bar
     *  should add/replace or remove the caller's own reaction. */
    reactions: List<ReactionEntity> = emptyList(),
    /** The local wallet's address, to find *my* reaction so the pill can show its status. */
    myReactorAddress: String? = null,
    onReact: (String) -> Unit = {},
    /** Retries the local user's failed reaction on this message (see its `failedAction`). */
    onRetryReaction: (ReactionEntity) -> Unit = {},
    onSavePhoto: (ByteArray, String) -> Unit = { _, _ -> },
    revealOffsetPx: Animatable<Float, AnimationVector1D> = remember { Animatable(0f) },
    maxRevealOffsetPx: Float = 1f,
    photosBlocked: Boolean = false,
    isPhotoRevealed: Boolean = false,
    onRevealPhoto: () -> Unit = {},
    kaspaExplorer: com.kachat.app.models.KaspaExplorer = com.kachat.app.models.KaspaExplorer.default,
    /** Tapping the reply quote (if any) jumps to and highlights the original message. */
    onJumpToReply: (String) -> Unit = {},
    isHighlighted: Boolean = false,
    /** Current game state for this message's chess envelope, if it has one - computed by the
     *  caller (needs the full conversation's messages, which this composable doesn't have). */
    chessSummary: com.kachat.app.util.ChessGameSummary? = null,
    /** True only for the most recent chess message belonging to its game - see
     *  ChessGameEngine.isLatestChessMessage. */
    isLatestChessMessage: Boolean = false,
    onRespondToChessInvite: (Boolean) -> Unit = {},
    onOpenChessGame: (String) -> Unit = {},
    /** Enters the chat's message multi-select mode with this message pre-selected - null disables
     *  the "Select" long-press menu option entirely (matches onReply's always-present convention,
     *  just optional since not every caller of this composable is inside a selectable chat thread). */
    onSelect: (() -> Unit)? = null
) {
    val isSent = message.direction == "sent"
    var showMenu by remember { mutableStateOf(false) }
    var showQuickReactionBar by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val replyContent = remember(message.plaintextBody) { MessageReply.parseOrNull(message.plaintextBody) }
    val displayBody = replyContent?.text ?: message.plaintextBody
    val imageContent = remember(displayBody) { ImageMessage.parseOrNull(displayBody) }
    val chessEnvelope = remember(displayBody) { com.kachat.app.util.ChessMessage.parseOrNull(displayBody) }
    // Only the plain, non-truncated text bubble ever shows a link preview - hoisted up here
    // (rather than computed inline where it's used) so `separateLinkPreviewUrl`'s card can be
    // placed as a sibling *after* the whole message-content Box below, matching iOS's structure:
    // otherwise Box (which stacks children at the same top-left origin, not a Column) would draw
    // the raw-link text bubble and the preview card on top of each other, and the reaction pill's
    // corner-anchor would end up sized against that overlapping mess instead of one clean shape.
    val bodyText = displayBody ?: ""
    val isPlainTextMessage = message.type != "pay" &&
        message.type != MessageProtocol.TYPE_HANDSHAKE &&
        chessEnvelope == null &&
        VoiceMessage.parseOrNull(displayBody) == null &&
        imageContent == null &&
        bodyText.length <= MESSAGE_TEXT_TRUNCATION_THRESHOLD
    val isEntirelyLinkMessage = remember(bodyText, isPlainTextMessage) {
        isPlainTextMessage && TextLinkify.isEntirelyLink(bodyText)
    }
    val separateLinkPreviewUrl = remember(bodyText, isPlainTextMessage, isEntirelyLinkMessage) {
        if (isPlainTextMessage && !isEntirelyLinkMessage) TextLinkify.findUrls(bodyText).firstOrNull()?.uri else null
    }
    // An in-app KaChat link (a shared KaPosts post, or a broadcast room invite) takes priority
    // over the generic link path: it previews from local data only - never a metadata fetch, so
    // never the stranger tap-to-load gate either - and tapping it routes inside the app. Detected
    // separately from TextLinkify because the kachat:// form isn't a web URL at all and so is
    // never linkified; the https form would otherwise be fetched like any external address.
    val internalLinkMatch = remember(bodyText, isPlainTextMessage) {
        if (isPlainTextMessage) KaChatLink.findFirst(bodyText) else null
    }
    // The card is the WHOLE message wherever a KaChat link appears, not only when the link is
    // alone: KaPosts' own share text quotes the post above the link, so keeping the bubble drew
    // the post twice - once as a truncated quote, once in full in the card underneath. Copy,
    // reply and forward still use bodyText, so nothing is lost from the message itself.
    val isEntirelyInternalLinkMessage = internalLinkMatch != null
    // Link previews auto-fetch only for accepted contacts (the same conversationStatus ==
    // "active" trust signal shouldAutoDisplayPhotos uses, passed in as isHandshakeComplete) and
    // for the user's own sent links; a non-accepted stranger's link gets a tap-to-load
    // placeholder instead, so no request leaves the device just by reading the message.
    // Matches iOS (2026-08 audit, decision 5A).
    val linkPreviewAutoFetch = isSent || isHandshakeComplete

    if (isPendingRequest) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Surface(
                color = LocalAppColors.current.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.request_to_communicate),
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Surface(
                color = LocalAppColors.current.surface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.str_3), fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.contact_has_requested_permission_to_communicate),
                            color = LocalAppColors.current.textPrimary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onAccept,
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.accept), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onDecline,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3C)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.decline), color = LocalAppColors.current.textSecondary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        return
    }

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
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
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
            ContactAvatar(imageUrl = contactAvatarUrl, deviceContactPhotoUri = contactPhotoUri, fallbackText = contactAvatarFallback, size = 32.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            horizontalAlignment = if (isSent) Alignment.End else Alignment.Start,
            modifier = Modifier.onGloballyPositioned { coords ->
                menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
            }
        ) {
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
                        if (replyContent.replyToSender == message.walletAddress) "You" else "Them",
                        color = KaspaTeal,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        replyContent.replyToPreview,
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Box {
            if (message.type == "pay") {
                // Apple-Pay-style payment card (ported from iOS's paymentCardBubble): teal
                // gradient with the Kaspa logo in a SOLID WHITE circle on the sent side, neutral
                // surface with teal accents for received, big bold amount, Sent/Received line
                // and optional note line. Unparseable content (legacy/foreign payments) falls
                // back to the old plain text bubble plus the orange "Payment" capsule — the
                // capsule only shows in that fallback, never alongside the card.
                val cardParts = remember(message.plaintextBody, isSent) { parsePaymentCardParts(message.plaintextBody) }
                if (cardParts != null) {
                    PaymentCardBubble(
                        amountText = cardParts.first,
                        note = cardParts.second,
                        isSent = isSent,
                        isWarning = message.deliveryStatus == "warning",
                        onLongPress = { showMenu = true },
                        onDoubleClick = { showQuickReactionBar = true }
                    )
                } else {
                    Column(horizontalAlignment = if (isSent) Alignment.End else Alignment.Start) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                            Icon(painterResource(R.drawable.ic_kaspa_logo), null, tint = Color.Unspecified, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.payment), color = Color(0xFFF39C12), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Surface(
                            color = if (isSent) KaspaTeal else LocalAppColors.current.surface,
                            shape = RoundedCornerShape(20.dp),
                            // Same off-screen-avatar risk as the plain text bubble — a long payment memo
                            // needs the same cap.
                            modifier = Modifier.widthIn(max = 280.dp).combinedClickable(onClick = {}, onLongClick = { showMenu = true })
                        ) {
                            Text(
                                text = message.plaintextBody ?: "Payment",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                color = if (isSent) Color.Black else LocalAppColors.current.textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else if (message.type == MessageProtocol.TYPE_HANDSHAKE) {
                // A message I sent is either my initial outreach ("Request to communicate",
                // frozen as-is - it stays a historical record of what I sent, doesn't
                // retroactively change) or my *response* accepting an incoming request, which
                // is inherently already complete the moment it's sent - WalletService.sendHandshake
                // stores that distinction in plaintextBody itself (see its isResponse branch).
                // Their message only flips to "completed" once the connection is actually live —
                // i.e. once I've received their side of it (isHandshakeComplete).
                val showCompleted = if (isSent) message.plaintextBody == "[Handshake completed]" else isHandshakeComplete
                val pillText = if (showCompleted) "🤝 Handshake completed" else "👋 Request to communicate"
                val bodyText = if (showCompleted) "[Handshake completed]" else "[Request to communicate]"
                Column(horizontalAlignment = if (isSent) Alignment.End else Alignment.Start) {
                    Surface(
                        color = LocalAppColors.current.surfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Text(
                            text = pillText,
                            color = LocalAppColors.current.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    Surface(
                        color = if (isSent) KaspaTeal else LocalAppColors.current.surface,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { showMenu = true })
                    ) {
                        Text(
                            text = bodyText,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            color = if (isSent) Color.Black else LocalAppColors.current.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else if (chessEnvelope != null) {
                ChessBubble(
                    envelope = chessEnvelope,
                    summary = chessSummary,
                    isLatest = isLatestChessMessage,
                    isSent = isSent,
                    messageId = message.id,
                    onRespond = onRespondToChessInvite,
                    onOpen = { onOpenChessGame(chessEnvelope.gameId) },
                    onLongPress = { showMenu = true }
                )
            } else if (VoiceMessage.parseOrNull(displayBody) != null) {
                AudioBubble(
                    voiceContent = VoiceMessage.parseOrNull(displayBody)!!,
                    isSent = isSent,
                    onLongPress = { showMenu = true },
                    onDoubleClick = { showQuickReactionBar = true }
                )
            } else if (ImageMessage.parseOrNull(displayBody) != null) {
                ImageBubble(
                    imageContent = ImageMessage.parseOrNull(displayBody)!!,
                    isSent = isSent,
                    onLongPress = { showMenu = true },
                    onDoubleClick = { showQuickReactionBar = true },
                    photosBlocked = !isSent && photosBlocked,
                    senderDisplayName = contactAvatarFallback,
                    isRevealed = isPhotoRevealed,
                    onReveal = onRevealPhoto
                )
            } else {
                // Above this, render a truncated tap-to-expand preview instead of laying out the
                // full text inline - matches iMessage's behavior for very long messages, and
                // specifically guards against a huge wall of text (e.g. raw base64 that ended up
                // as plain message content instead of being recognized as a file/image envelope)
                // making the whole chat scroll janky. Checked before running TextLinkify.findUrls
                // below, since scanning a huge string for links is itself wasted work here.
                if (bodyText.length > MESSAGE_TEXT_TRUNCATION_THRESHOLD) {
                    var showFullText by remember { mutableStateOf(false) }
                    Surface(
                        color = if (isSent) KaspaTeal else LocalAppColors.current.surface,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .combinedClickable(
                                onClick = { showFullText = true },
                                onLongClick = { showMenu = true },
                                onDoubleClick = { showQuickReactionBar = true }
                            )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(
                                text = bodyText.take(MESSAGE_TEXT_PREVIEW_LENGTH) + "…",
                                color = if (isSent) Color.Black else LocalAppColors.current.textPrimary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.show_more),
                                color = if (isSent) LocalAppColors.current.divider else KaspaTeal,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                    if (showFullText) {
                        FullMessageTextDialog(
                            text = bodyText,
                            onDismiss = { showFullText = false },
                            onCopy = { clipboardManager.setText(AnnotatedString(bodyText)) }
                        )
                    }
                } else if (isEntirelyInternalLinkMessage) {
                    // Nothing but an in-app KaChat link: the post/invite card IS the message.
                    KaChatInternalLinkCard(
                        ref = internalLinkMatch!!.ref,
                        url = internalLinkMatch.raw,
                        txId = message.id,
                        kaspaExplorer = kaspaExplorer,
                        onSelect = onSelect,
                        onDoubleTap = { showQuickReactionBar = true }
                    )
                } else if (isEntirelyLinkMessage) {
                    // Message is nothing but a link - the preview card replaces the plain-text
                    // bubble entirely (matches iMessage/iOS's `MessageBubbleView`) instead of
                    // showing both. `fallbackText` keeps the raw link visible/tappable if no
                    // preview data is ever found, rather than the message rendering as nothing.
                    LinkPreviewCard(
                        url = TextLinkify.findUrls(bodyText).first().uri,
                        txId = message.id,
                        kaspaExplorer = kaspaExplorer,
                        fallbackText = bodyText,
                        onSelect = onSelect,
                        onDoubleTap = { showQuickReactionBar = true },
                        autoFetch = linkPreviewAutoFetch
                    )
                } else {
                    var textLayoutResult by remember(bodyText) { mutableStateOf<TextLayoutResult?>(null) }
                    // Sent bubbles are teal (matching broadcast rooms' sent-message color) with black
                    // text/links for contrast — a teal link on a teal background would be unreadable.
                    val linkColor = if (isSent) Color.Black else KaspaTeal
                    val annotatedBody = remember(bodyText, isSent) {
                        buildAnnotatedString {
                            append(bodyText)
                            for (match in TextLinkify.findUrls(bodyText)) {
                                addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), match.range.first, match.range.last + 1)
                                addStringAnnotation("URL", match.uri, match.range.first, match.range.last + 1)
                            }
                            // kachat:// isn't a web URL, so TextLinkify never sees it - style and
                            // annotate it here so it's tappable inline too (the https form is
                            // already covered above and resolves to the same in-app route).
                            KaChatLink.findFirst(bodyText)?.let { internal ->
                                addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), internal.range.first, internal.range.last + 1)
                                addStringAnnotation("URL", internal.raw, internal.range.first, internal.range.last + 1)
                            }
                        }
                    }
                    Surface(
                        color = if (isSent) KaspaTeal else LocalAppColors.current.surface,
                        shape = RoundedCornerShape(20.dp),
                        // Without a cap, a long message claims the outer Row's full width before the
                        // avatar sibling ever gets measured, pushing the avatar off-screen entirely —
                        // matches the same 280.dp cap broadcast rooms' equivalent bubble already uses.
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Text(
                            text = annotatedBody,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .pointerInput(annotatedBody) {
                                    detectTapGestures(
                                        onLongPress = { showMenu = true },
                                        onDoubleTap = { showQuickReactionBar = true },
                                        onTap = { offset ->
                                            val layout = textLayoutResult ?: return@detectTapGestures
                                            val charOffset = layout.getOffsetForPosition(offset)
                                            annotatedBody.getStringAnnotations("URL", charOffset, charOffset)
                                                .firstOrNull()?.let { annotation ->
                                                    // An in-app KaChat link routes in-app; it
                                                    // must never be handed to a browser.
                                                    val internal = KaChatLink.parse(annotation.item)
                                                    if (internal != null) openKaChatLink(internal)
                                                    else uriHandler.openUri(annotation.item)
                                                }
                                        }
                                    )
                                },
                            onTextLayout = { textLayoutResult = it },
                            color = if (isSent) Color.Black else LocalAppColors.current.textPrimary
                        )
                    }
                }
            }

            if (showMenu) {
                CenteredOptionsMenu(onDismissRequest = { showMenu = false }, anchor = menuAnchor) {
                    PopupMenuRow(Icons.Default.ContentCopy, stringResource(R.string.copy_message)) {
                        clipboardManager.setText(AnnotatedString(displayBody ?: ""))
                        showMenu = false
                    }
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(Icons.AutoMirrored.Filled.Reply, stringResource(R.string.reply)) {
                        onReply()
                        showMenu = false
                    }
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(Icons.Default.Public, stringResource(R.string.view_in_explorer)) {
                        uriHandler.openUri(kaspaExplorer.txUrl(message.id))
                        showMenu = false
                    }
                    if (imageContent != null) {
                        HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                        PopupMenuRow(Icons.Default.Download, stringResource(R.string.save_photo)) {
                            try {
                                val bytes = android.util.Base64.decode(ImageMessage.base64Payload(imageContent), android.util.Base64.DEFAULT)
                                onSavePhoto(bytes, "kachat_${message.id}.jpg")
                            } catch (e: Exception) {
                                android.util.Log.e("MessageBubble", "Could not decode photo for saving", e)
                            }
                            showMenu = false
                        }
                    }
                    if (ChatViewModel.shouldShowRetryOption(message)) {
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
                ReactionPill(
                    reactions = reactions,
                    myAddress = myReactorAddress,
                    modifier = Modifier
                        .align(if (isSent) Alignment.BottomStart else Alignment.BottomEnd)
                        .offset(y = 10.dp)
                )
            }
        }

        // The reaction pill is offset ~10dp below the bubble Box above, and offset reserves no
        // layout space, so reserve it here - otherwise the pill overlaps the message below it.
        if (reactions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Placed as its own sibling (not inside the Box above) so it stacks cleanly below the
        // text bubble instead of overlapping it, and so the reaction pill's corner-anchor (which
        // attaches to that Box) sizes against just the text bubble, not this taller card too -
        // matches iOS's identical placement outside its equivalent `Group`.
        // An internal link is always claimed above as the message itself, so only an external
        // link can still want a card down here.
        separateLinkPreviewUrl?.takeIf { internalLinkMatch == null }?.let { url ->
            LinkPreviewCard(url = url, txId = message.id, kaspaExplorer = kaspaExplorer, onSelect = onSelect, onDoubleTap = { showQuickReactionBar = true }, autoFetch = linkPreviewAutoFetch)
        }

        if (isSent) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (message.deliveryStatus) {
                    "failed" -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = stringResource(R.string.failed_to_send),
                            tint = Color(0xFFFF3B30),
                            modifier = Modifier.size(12.dp)
                        )
                        // Tappable "Retry" next to the red error icon (also reachable via the
                        // long-press menu) so a failed send can be resent with one tap.
                        if (ChatViewModel.shouldShowRetryOption(message)) {
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
                    "pending" -> Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = stringResource(R.string.sending),
                        tint = LocalAppColors.current.textSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                    else -> Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CD964),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        // A reaction (not the message) that failed to send: red "Retry" under the message, paired
        // with the error icon shown on the reaction pill. Shown for reactions on any message (yours
        // or the contact's), so it isn't gated by isSent like the message-status row above.
        reactions.firstOrNull { it.deliveryStatus == "failed" }?.let { failedReaction ->
            Text(
                text = stringResource(R.string.retry),
                color = Color(0xFFFF3B30),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { onRetryReaction(failedReaction) }
            )
        }
        }
        if (isSent) {
            Spacer(Modifier.width(8.dp))
            ContactAvatar(imageUrl = myAvatarUrl, fallbackText = myAvatarFallback, size = 32.dp)
        }
        }
    }
}

/**
 * Full text of a message too long to render inline (see [MESSAGE_TEXT_TRUNCATION_THRESHOLD]) - a
 * full-screen scrollable, selectable text view, matching iMessage's "tap to see more" behavior.
 * Shared with BroadcastScreens.kt's room bubble, not just private-chat messages.
 */
@Composable
fun FullMessageTextDialog(text: String, onDismiss: () -> Unit, onCopy: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(LocalAppColors.current.background)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.done), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
                Text(stringResource(R.string.message), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                IconButton(onClick = onCopy) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy), tint = KaspaTeal)
                }
            }
            HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.5f))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                SelectionContainer {
                    Text(text, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * A voice message bubble — decodes the embedded base64 audio to a temp file once, then plays it
 * with [android.media.MediaPlayer] (which decodes WebM/Opus natively, no manual PCM handling
 * needed for playback). No waveform visualization — the [VoiceMessageContent] format doesn't
 * carry sample data, and re-decoding to PCM just to draw bars isn't worth the extra native-audio
 * surface area for a cosmetic detail; play/pause + duration covers the actual "does it work" bar.
 */
/**
 * "Play Chess" 1:1 feature - dispatches to an invite card (Accept/Decline, mirroring the
 * handshake pending-request card above), a live status card (board thumbnail + status, for the
 * most recent chess message in its game), or a compact one-line log entry (earlier moves in the
 * same game, so a long game doesn't repeat a full board on every message).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChessBubble(
    envelope: com.kachat.app.util.ChessEnvelope,
    summary: com.kachat.app.util.ChessGameSummary?,
    isLatest: Boolean,
    isSent: Boolean,
    messageId: String,
    onRespond: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    when (envelope) {
        is com.kachat.app.util.ChessEnvelope.Invite -> ChessInviteBubble(isSent, envelope.content, summary, onRespond, onOpen, onLongPress)
        else -> {
            if (isLatest && summary != null) {
                ChessLiveCard(summary, onOpen, onLongPress)
            } else {
                ChessLogEntry(envelope, summary, messageId, onOpen, onLongPress)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChessInviteBubble(
    isSent: Boolean,
    invite: com.kachat.app.util.ChessInviteContent,
    summary: com.kachat.app.util.ChessGameSummary?,
    onRespond: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    // Read the time control off the envelope itself (not just the summary, which can lag null
    // while messages are still loading) so "Chess - 3 | 2" shows the moment the bubble renders.
    val timeControlLabel = invite.tcMinutes?.let { "Chess - $it | ${invite.tcIncSeconds ?: 0}" }
    val showsResponseButtons = !isSent && summary?.status?.kind == com.kachat.app.util.ChessGameStatusKind.PENDING_RESPONSE
    Surface(
        color = LocalAppColors.current.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .widthIn(max = 280.dp)
            .then(
                if (!showsResponseButtons) {
                    Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                } else {
                    Modifier
                }
            )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.str_2), fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isSent) "Chess game invite sent" else "Invited you to a game of chess",
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 14.sp
                )
            }
            if (timeControlLabel != null) {
                Spacer(Modifier.height(4.dp))
                Text(timeControlLabel, color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            if (showsResponseButtons) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onRespond(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.accept), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onRespond(false) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3C)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.decline), color = LocalAppColors.current.textSecondary, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (summary != null) {
                Spacer(Modifier.height(4.dp))
                Text(summary.statusText, color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChessLiveCard(summary: com.kachat.app.util.ChessGameSummary, onOpen: () -> Unit, onLongPress: () -> Unit = {}) {
    Surface(
        color = LocalAppColors.current.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongPress)
    ) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ChessBoardThumbnail(board = summary.board)
            Spacer(Modifier.height(8.dp))
            Text(
                summary.statusText,
                color = if (summary.status.isGameOver) LocalAppColors.current.textSecondary else LocalAppColors.current.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            val timeControlLabel = summary.timeControlLabel
            if (timeControlLabel != null) {
                Spacer(Modifier.height(2.dp))
                Text("Chess - $timeControlLabel", color = LocalAppColors.current.textSecondary, fontSize = 11.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChessLogEntry(
    envelope: com.kachat.app.util.ChessEnvelope,
    summary: com.kachat.app.util.ChessGameSummary?,
    messageId: String,
    onOpen: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    // A plain Unicode glyph character embedded in text renders in the ambient text color (see
    // the see-through-white-pieces fix on ChessPiece.glyph), so it can't convey white vs black on
    // its own - a move's piece is rendered via a real ChessPieceGlyph composable (fill/outline
    // colored by piece.color) placed next to the text instead.
    val record = if (envelope is com.kachat.app.util.ChessEnvelope.Move) {
        summary?.moveHistory?.firstOrNull { it.messageId == messageId }
    } else {
        null
    }
    val text = when (envelope) {
        is com.kachat.app.util.ChessEnvelope.Move -> {
            val promo = envelope.content.promotion?.let { " (${it.uppercase()})" } ?: ""
            "${envelope.content.from} → ${envelope.content.to}$promo"
        }
        is com.kachat.app.util.ChessEnvelope.Resign ->
            if (envelope.content.reason == "timeout") "Lost on time" else "Resigned"
        is com.kachat.app.util.ChessEnvelope.Response -> if (envelope.content.accepted) "Accepted the game" else "Declined the game"
        is com.kachat.app.util.ChessEnvelope.Invite -> "Chess invite"
    }
    Surface(
        color = LocalAppColors.current.surfaceVariant,
        shape = RoundedCornerShape(50),
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongPress)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (record != null) {
                ChessPieceGlyph(
                    com.kachat.app.util.ChessPiece(record.pieceType, record.color),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            Text(text, color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
        }
    }
}

/** Small, non-interactive board render - shared by the in-chat live card and (with its own
 *  square size) the full-screen ChessGameScreen. */
@Composable
fun ChessBoardThumbnail(board: com.kachat.app.util.ChessBoard, sizeDp: Dp = 160.dp) {
    val squareSize = sizeDp / 8
    Column {
        for (rank in 7 downTo 0) {
            Row {
                for (file in 0..7) {
                    val isLight = (file + rank) % 2 != 0
                    Box(
                        modifier = Modifier
                            .size(squareSize)
                            .background(if (isLight) ChessLightSquareColor else ChessDarkSquareColor),
                        contentAlignment = Alignment.Center
                    ) {
                        val piece = board.piece(com.kachat.app.util.ChessSquare(file, rank))
                        if (piece != null) {
                            ChessPieceGlyph(piece, fontSize = (squareSize.value * 0.6f).sp)
                        }
                    }
                }
            }
        }
    }
}

/** Classic wood-tone board colors (matches chess.com/lichess's default theme) - shared by the
 *  in-chat thumbnail and the full-screen board in ChessGameScreen.kt. */
val ChessLightSquareColor = Color(0xFFEFD9B4)
val ChessDarkSquareColor = Color(0xFFB58863)

/** Renders a single chess piece glyph with an explicit white/black fill plus a crisp
 *  contrasting outline (four offset copies of the glyph drawn behind the fill, a standard
 *  lightweight text-stroke trick), rather than relying on the bare Unicode glyph's
 *  outline-vs-filled shape alone to distinguish sides - at typical board sizes that distinction
 *  was too subtle to read at a glance, especially on a same-toned square. */
@Composable
fun ChessPieceGlyph(piece: com.kachat.app.util.ChessPiece, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    val fillColor = if (piece.color == com.kachat.app.util.ChessColor.WHITE) Color(0xFFFCFCFC) else Color(0xFF121212)
    val outlineColor = if (piece.color == com.kachat.app.util.ChessColor.WHITE) Color(0xFF121212) else Color(0xFFFCFCFC)
    val d = 0.9.dp
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(piece.glyph, fontSize = fontSize, color = outlineColor, modifier = Modifier.offset(x = d))
        Text(piece.glyph, fontSize = fontSize, color = outlineColor, modifier = Modifier.offset(x = -d))
        Text(piece.glyph, fontSize = fontSize, color = outlineColor, modifier = Modifier.offset(y = d))
        Text(piece.glyph, fontSize = fontSize, color = outlineColor, modifier = Modifier.offset(y = -d))
        Text(piece.glyph, fontSize = fontSize, color = fillColor)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudioBubble(voiceContent: VoiceMessageContent, isSent: Boolean, onLongPress: () -> Unit, onDoubleClick: () -> Unit = {}) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0) }
    var isReady by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    DisposableEffect(voiceContent.content) {
        var tempFile: java.io.File? = null
        try {
            val bytes = android.util.Base64.decode(VoiceMessage.base64Payload(voiceContent), android.util.Base64.DEFAULT)
            val file = java.io.File(context.cacheDir, "voice_playback_${System.nanoTime()}.webm")
            file.writeBytes(bytes)
            tempFile = file
            val player = android.media.MediaPlayer()
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener {
                durationMs = it.duration
                isReady = true
            }
            player.setOnCompletionListener { isPlaying = false }
            player.prepareAsync()
            mediaPlayer = player
        } catch (e: Exception) {
            android.util.Log.e("AudioBubble", "Could not prepare voice message for playback", e)
        }
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
            tempFile?.delete()
        }
    }

    Surface(
        color = if (isSent) LocalAppColors.current.surfaceVariant else LocalAppColors.current.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress, onDoubleClick = onDoubleClick)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .widthIn(min = 150.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                enabled = isReady,
                onClick = {
                    val player = mediaPlayer ?: return@IconButton
                    if (isPlaying) {
                        player.pause()
                        isPlaying = false
                    } else {
                        if (player.currentPosition >= player.duration) player.seekTo(0)
                        player.start()
                        isPlaying = true
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (isReady) KaspaTeal else Color.Gray
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isReady) VoiceMessage.formatDuration(durationMs) else "...",
                color = LocalAppColors.current.textPrimary,
                fontSize = 13.sp
            )
        }
    }
}

/**
 * Small in-memory cache of already-decoded chat-photo bitmaps, keyed by the message's raw base64
 * content string - avoids re-decoding on every recomposition/scroll-back-into-view for a photo
 * that's already been shown once. Sized like iOS's equivalent `thumbnailCache` (24MB).
 */
private val incomingPhotoBitmapCache = object : android.util.LruCache<String, android.graphics.Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
}

/**
 * Bounds how many photo decodes run at once. Without this, opening a chat with many photos -
 * especially scrolling straight to the bottom of a long history, as happens when a notification
 * tap opens straight into a photo-heavy chat - decoded every visible bubble's bitmap at once. Each
 * decode is cheap individually, but a burst of them can still contend with the main thread's own
 * layout/scroll work for CPU time on a busy launch. Matches iOS's `ImageDecodeLimiter`.
 */
private val incomingPhotoDecodeLimiter = Semaphore(3)

/**
 * Decodes an incoming photo's raw bytes, trying [ImageDecoder] as a fallback if [BitmapFactory]
 * returns null. Both platforms only ever send plain JPEG chat photos now (see
 * `ImagePrep.prepareForChatMessage`'s doc comment for why AVIF was tried and removed), so this is
 * mostly future-proofing/defense-in-depth for any other format that could reach this decode path -
 * `ImageDecoder` has occasionally succeeded where `BitmapFactory` fails on the same bytes on some
 * OEM builds.
 */
private fun decodeIncomingPhotoBitmap(bytes: ByteArray): android.graphics.Bitmap? {
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return try {
        val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
        android.graphics.ImageDecoder.decodeBitmap(source)
    } catch (e: Exception) {
        null
    }
}

/**
 * A photo message bubble — decodes the embedded base64 image to a [Bitmap] once, renders it inline
 * capped to a max width, and opens a tap-to-dismiss full-screen viewer on tap. Same interaction
 * contract as [AudioBubble] (long-press for the context menu, double-click to reply).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageBubble(
    imageContent: VoiceMessageContent,
    isSent: Boolean,
    onLongPress: () -> Unit,
    onDoubleClick: () -> Unit = {},
    photosBlocked: Boolean = false,
    senderDisplayName: String = "",
    isRevealed: Boolean = false,
    onReveal: () -> Unit = {}
) {
    // Hidden behind a manual reveal - mirrors iOS's LazyImageBubble.hiddenBubble. Skips decoding
    // the bitmap entirely until revealed, matching the "don't auto-render" intent, not just
    // "don't show" - see ChatRepository.shouldAutoDisplayPhotos.
    if (photosBlocked && !isRevealed) {
        Surface(
            color = LocalAppColors.current.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 220.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp).widthIn(min = 180.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.VisibilityOff, null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    "$senderDisplayName sent a photo",
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onReveal,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.show_photo), color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }

    var showFullScreen by remember { mutableStateOf(false) }
    val cachedBitmap = remember(imageContent.content) { incomingPhotoBitmapCache.get(imageContent.content) }
    var isDecoding by remember(imageContent.content) { mutableStateOf(cachedBitmap == null) }
    // Decoded off the main thread and rate-limited (see incomingPhotoDecodeLimiter's doc comment)
    // - this used to be a plain `remember(imageContent.content) { ... }`, which runs its block
    // synchronously during composition on the main thread. Opening a chat with many photos at
    // once (e.g. scrolling straight to the bottom via a notification tap) decoded every visible
    // bubble's bitmap inline, one after another, blocking Compose's own layout/draw pass for as
    // long as all of them took combined - a guaranteed freeze, not just a contention risk.
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = cachedBitmap, key1 = imageContent.content) {
        if (value != null) {
            isDecoding = false
            return@produceState
        }
        value = incomingPhotoDecodeLimiter.withPermit {
            withContext(Dispatchers.Default) {
                incomingPhotoBitmapCache.get(imageContent.content)?.let { return@withContext it }
                try {
                    val bytes = android.util.Base64.decode(ImageMessage.base64Payload(imageContent), android.util.Base64.DEFAULT)
                    decodeIncomingPhotoBitmap(bytes)?.also {
                        incomingPhotoBitmapCache.put(imageContent.content, it)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ImageBubble", "Could not decode photo message", e)
                    null
                }
            }
        }
        isDecoding = false
    }

    if (isDecoding) {
        Surface(
            color = if (isSent) LocalAppColors.current.surfaceVariant else LocalAppColors.current.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.size(width = 220.dp, height = 160.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = KaspaTeal, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        }
        return
    }

    // A plain local val snapshot — `bitmap` itself is a delegated property (`by produceState`),
    // so Kotlin can't smart-cast it to non-null below just from the `== null` check on this line;
    // each read of a delegated property calls its getValue() again, and the compiler can't prove
    // it won't return a different (possibly null) value between the check and later reads.
    val resolvedBitmap = bitmap
    if (resolvedBitmap == null) {
        Surface(
            color = if (isSent) LocalAppColors.current.surfaceVariant else LocalAppColors.current.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress, onDoubleClick = onDoubleClick)
        ) {
            Text(
                text = stringResource(R.string.photo_unavailable),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = if (isSent) Color.Black else LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        return
    }

    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .widthIn(max = 220.dp)
            .combinedClickable(onClick = { showFullScreen = true }, onLongClick = onLongPress, onDoubleClick = onDoubleClick)
    ) {
        Image(
            bitmap = resolvedBitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.photo_message),
            modifier = Modifier.clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.FillWidth
        )
    }

    if (showFullScreen) {
        Dialog(onDismissRequest = { showFullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LocalAppColors.current.background)
                    .clickable { showFullScreen = false },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = resolvedBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.photo_message_full_screen),
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

@Composable
fun ChatActionButton(icon: ImageVector, onClick: () -> Unit = {}, size: Dp = 40.dp, iconSize: Dp = 20.dp) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .background(LocalAppColors.current.surface, CircleShape)
    ) {
        Icon(icon, null, tint = KaspaTeal, modifier = Modifier.size(iconSize))
    }
}

/** "0:07" — the composer's live recording timer, capped display-wise the same way the recording itself is capped at 10s. */
fun formatRecordingElapsed(elapsedMs: Long): String = VoiceMessage.formatDuration(elapsedMs.toInt())

/**
 * One-line body for a broadcast row in the in-app notification center (the Profile bell).
 *
 * The store records the raw on-chain broadcast content and truncates it to 90 characters, so a
 * photo/voice/chess envelope arrives here as a clipped JSON blob that no parser can read back.
 * The full-envelope cases go through the same mapping the shade uses
 * ([NotificationHelper.broadcastNotificationText]); anything still JSON-shaped after that is a
 * truncated envelope, recognized by its surviving type marker instead.
 */
private fun broadcastCenterBody(body: String): String {
    val humanized = com.kachat.app.services.NotificationHelper.broadcastNotificationText(body)
    if (humanized.trimStart().firstOrNull() != '{') return humanized
    val compact = humanized.replace(" ", "")
    return when {
        compact.contains("\"mimeType\":\"audio") -> "\uD83C\uDFA4 Audio message"
        compact.contains("\"mimeType\":\"image") -> "\uD83D\uDCF7 Photo"
        compact.contains("\"mimeType\":\"video") -> "\uD83C\uDFAC Video"
        compact.contains("\"type\":\"chess") -> "\u265F\uFE0F Chess game"
        compact.contains("\"type\":\"file") -> "\uD83D\uDCCE File"
        else -> "New message"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: WalletViewModel,
    navController: NavController,
    connectionViewModel: ConnectionViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
    portfolioViewModel: com.kachat.app.viewmodels.PortfolioViewModel = hiltViewModel()
) {
    val address by viewModel.address.collectAsState()
    val accountName by viewModel.accountName.collectAsState()
    val balance by viewModel.fullBalance.collectAsState()
    val identityBalanceSompi by viewModel.balanceSompi.collectAsState()
    val showSetupGuides by viewModel.showSetupGuides.collectAsState()
    val dotColorHex by connectionViewModel.dotColorHex.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    val spendingAddress by viewModel.spendingAddress.collectAsState()
    val spendingBalance by viewModel.spendingBalance.collectAsState()
    val manageAddresses by viewModel.manageAddresses.collectAsState()
    val primarySpendingEntry = manageAddresses.firstOrNull { it.isCurrent }
    var showFundIdentityQr by remember { mutableStateOf(false) }
    // Its own state (not shared with any other QR overlay) so its bigger green border can't
    // accidentally affect an unrelated overlay reusing the same flag.
    var showAcceptPaymentQr by remember { mutableStateOf(false) }
    var showWithdrawDialog by remember { mutableStateOf(false) }
    var showSpendingWithdrawDialog by remember { mutableStateOf(false) }
    var showLogoutConfirmation by remember { mutableStateOf(false) }

    val knsInscribeState by viewModel.knsInscribeState.collectAsState()
    val pendingKnsCommit by viewModel.pendingKnsCommit.collectAsState()

    val profileAssetId by viewModel.profileDomainAssetId.collectAsState()
    val knsProfile by viewModel.knsProfile.collectAsState()
    val activeProfileDomainName = viewModel.activeProfileDomainName.collectAsState().value
    val hasAnyProfileData = knsProfile != null && listOf(
        knsProfile?.bio, knsProfile?.x, knsProfile?.website, knsProfile?.telegram,
        knsProfile?.discord, knsProfile?.contactEmail, knsProfile?.github, knsProfile?.redirectUrl
    ).any { !it.isNullOrBlank() }

    LaunchedEffect(Unit) {
        viewModel.refreshBalance()
        viewModel.refreshOwnedDomains()
        viewModel.refreshSpendingAddress()
        viewModel.loadManageAddresses()
    }

    val pullRefreshState = rememberPullToRefreshState()
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.refreshBalanceAndAwait()
            viewModel.refreshSpendingBalanceAndAwait()
            pullRefreshState.endRefresh()
        }
    }

    // Re-tapping the Profile tab while already on it (e.g. to back out of a full-screen QR
    // overlay) doesn't re-navigate/recompose this screen — see WalletViewModel.notifyTabReselected.
    val tabReselectSignal by viewModel.tabReselectSignal.collectAsState()
    LaunchedEffect(tabReselectSignal) {
        if (tabReselectSignal.second == "profile") {
            showFundIdentityQr = false
            showAcceptPaymentQr = false
            showWithdrawDialog = false
        }
    }

    // In-place full-screen swap - not a nav route, not a dialog popup - mirroring
    // SpendingAddressTxHistoryScreen's own `if (showSendFlow) { ...; return }` idiom, so both of
    // Profile's quick-send entry points (Chatting Address, Spending Address) open the exact same
    // full-featured send screen (coin control, fee tier, KNS resolution) as everywhere else,
    // rather than the old bare-bones AlertDialog each used to show.
    if (showWithdrawDialog) {
        SpendingAddressSendFlow(
            fromAddress = address ?: "",
            balanceSompi = identityBalanceSompi,
            title = "Send Kaspa",
            viewModel = viewModel,
            portfolioViewModel = portfolioViewModel,
            onDone = { showWithdrawDialog = false }
        )
        return
    }

    if (showSpendingWithdrawDialog && primarySpendingEntry != null) {
        SpendingAddressSendFlow(
            fromAddress = primarySpendingEntry.address,
            balanceSompi = primarySpendingEntry.balanceSompi,
            title = "Send Kaspa",
            spendingIndex = primarySpendingEntry.index,
            viewModel = viewModel,
            portfolioViewModel = portfolioViewModel,
            onDone = { showSpendingWithdrawDialog = false }
        )
        return
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            Column(
                modifier = Modifier
                    .background(LocalAppColors.current.background)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                // Bold title + the global notification bell beside it (KaPosts activity, group
                // @mentions, live broadcasts) - mirrors iOS's Profile toolbar bell.
                val notifCenterVm: com.kachat.app.viewmodels.NotificationCenterViewModel = hiltViewModel()
                val notifEntries by notifCenterVm.store.entries.collectAsState()
                val notifLastSeen by notifCenterVm.store.lastSeenAt.collectAsState()
                var showNotifCenter by remember { mutableStateOf(false) }
                val notifUnread = notifEntries.count { it.timestampMs > notifLastSeen }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    Text(
                        stringResource(R.string.profile),
                        color = LocalAppColors.current.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                notifCenterVm.store.reloadIfNeeded()
                                showNotifCenter = true
                            }
                            .padding(6.dp),
                    ) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = "Notifications",
                            tint = LocalAppColors.current.textPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                        if (notifUnread > 0) {
                            // A plain red DOT (no count) - "there is something unread" is the signal.
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFFE0245E)),
                            )
                        }
                    }
                }
                if (showNotifCenter) {
                    AlertDialog(
                        onDismissRequest = {
                            notifCenterVm.store.markAllSeen()
                            showNotifCenter = false
                        },
                        containerColor = LocalAppColors.current.surface,
                        title = { Text("Notifications", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                        text = {
                            if (notifEntries.isEmpty()) {
                                Text(
                                    "KaPosts activity, group @mentions, and live broadcast messages show up here.",
                                    color = LocalAppColors.current.textSecondary,
                                )
                            } else {
                                androidx.compose.foundation.lazy.LazyColumn(
                                    modifier = Modifier.heightIn(max = 420.dp),
                                ) {
                                    items(notifEntries.size) { index ->
                                        val entry = notifEntries[index]
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                // Every row opens what it is about, the same way
                                                // the matching shade notification's tap does:
                                                // KaPosts rows deep-open the exact post/comment
                                                // via the deep-link flow, group @mentions open
                                                // the group's thread, broadcast rows open the
                                                // room. (Group and broadcast rows used to be
                                                // inert even though the entry already carried
                                                // the target id.)
                                                .let {
                                                    val open: (() -> Unit)? = when (entry.source) {
                                                        "kaposts" -> {
                                                            {
                                                                KaPostsDeepLink.pendingOpenNotifications.value = false
                                                                KaPostsDeepLink.pendingPostTxId.value = entry.targetId ?: ""
                                                            }
                                                        }
                                                        "group" -> entry.targetId?.takeIf { id -> id.isNotBlank() }?.let { id ->
                                                            { navController.navigate("group_chat/$id") }
                                                        }
                                                        "broadcast" -> entry.targetId
                                                            ?.let { raw -> KaChatLink.sanitizeChannelName(raw) }
                                                            ?.let { channel ->
                                                                { navController.navigate("broadcast_channel/$channel") }
                                                            }
                                                        else -> null
                                                    }
                                                    if (open != null) it.clickable {
                                                        notifCenterVm.store.markAllSeen()
                                                        showNotifCenter = false
                                                        open()
                                                    } else it
                                                }
                                                .padding(vertical = 6.dp),
                                        ) {
                                            Text(
                                                entry.title,
                                                color = LocalAppColors.current.textPrimary,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.5.sp,
                                            )
                                            // Broadcast rows carry the raw on-chain body, which is
                                            // a JSON envelope for anything but plain text - same
                                            // problem the shade had. See the helper below.
                                            val entryBody = remember(entry.id, entry.body, entry.source) {
                                                if (entry.source == "broadcast") broadcastCenterBody(entry.body) else entry.body
                                            }
                                            if (entryBody.isNotBlank()) {
                                                Text(
                                                    entryBody,
                                                    color = LocalAppColors.current.textSecondary,
                                                    fontSize = 12.sp,
                                                    maxLines = 2,
                                                )
                                            }
                                            Text(
                                                "${entry.source} · ${android.text.format.DateUtils.getRelativeTimeSpanString(entry.timestampMs)}",
                                                color = LocalAppColors.current.textSecondary,
                                                fontSize = 10.5.sp,
                                            )
                                        }
                                        HorizontalDivider(color = LocalAppColors.current.surfaceVariant)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                notifCenterVm.store.markAllSeen()
                                showNotifCenter = false
                            }) { Text("Done", color = KaspaTeal, fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = {
                            if (notifEntries.isNotEmpty()) {
                                TextButton(onClick = { notifCenterVm.store.clearAll() }) {
                                    Text("Clear All", color = LocalAppColors.current.textSecondary)
                                }
                            }
                        },
                    )
                }
                TopStatusBar(
                    balance = balance,
                    onStatusClick = { navController.navigate("connection_status") },
                    dotColorHex = dotColorHex,
                    showAddButton = false,
                    showSettingsButton = true,
                    onSettingsClick = { navController.navigate("settings") }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(pullRefreshState.nestedScrollConnection)
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                // Still scrollable as a safety net (smaller phones, larger system font scale),
                // but every element below is sized to comfortably fit a typical phone screen
                // without needing it.
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 4.0 Profile redesign (matches iOS): inline-editable account name up top, then a
            // KaPosts-style hero (KNS banner, overlapping avatar, display name, bio), then the
            // KNS profile editing entry - the old boxed Account section is gone.
            run {
                var isEditingName by remember { mutableStateOf(false) }
                var editedName by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isEditingName) {
                        OutlinedTextField(
                            value = editedName,
                            onValueChange = { editedName = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = LocalAppColors.current.textPrimary)
                        )
                        IconButton(onClick = {
                            val trimmed = editedName.trim()
                            isEditingName = false
                            if (trimmed.isNotEmpty() && trimmed != accountName) {
                                address?.let { viewModel.renameAccount(it, trimmed) }
                            }
                        }) {
                            Icon(Icons.Default.CheckCircle, null, tint = KaspaTeal)
                        }
                    } else {
                        Text(
                            accountName ?: stringResource(R.string.account_name),
                            color = LocalAppColors.current.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        IconButton(onClick = {
                            editedName = accountName ?: ""
                            isEditingName = true
                        }) {
                            Icon(Icons.Default.Edit, null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Surface(
                color = LocalAppColors.current.surface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    val bannerUrl = knsProfile?.bannerUrl
                    if (bannerUrl != null) {
                        SubcomposeAsyncImage(
                            model = bannerUrl,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                            loading = { Box(Modifier.fillMaxSize().background(KaspaTeal.copy(alpha = 0.25f))) },
                            error = { Box(Modifier.fillMaxSize().background(KaspaTeal.copy(alpha = 0.25f))) }
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth().height(140.dp).background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    listOf(KaspaTeal.copy(alpha = 0.55f), KaspaTeal.copy(alpha = 0.15f))
                                )
                            )
                        )
                    }
                    val heroName = activeProfileDomainName?.removeSuffix(".kas") ?: accountName ?: ""
                    // This card only ever renders the user's own profile (ProfileScreen is
                    // own-account only), so the edit/create entry is always shown here.
                    // Label follows the same reactive condition as the destination: no profile
                    // yet means "Create KNS Profile", and it flips to "Edit KNS Profile" the
                    // moment activeProfileDomainName lands - no app restart needed.
                    val hasKnsProfile = !activeProfileDomainName.isNullOrBlank()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(modifier = Modifier.padding(start = 16.dp).offset(y = (-38).dp)) {
                            Box(
                                modifier = Modifier.size(82.dp).clip(CircleShape).background(LocalAppColors.current.background),
                                contentAlignment = Alignment.Center
                            ) {
                                ContactAvatar(imageUrl = knsProfile?.avatarUrl, fallbackText = heroName, size = 76.dp)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(if (hasKnsProfile) R.string.edit_kns_profile else R.string.create_kns_profile),
                            color = KaspaTeal,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .padding(top = 8.dp, end = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (hasKnsProfile) {
                                        navController.navigate("edit_kns_profile")
                                    } else {
                                        navController.navigate("create_kns_profile")
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                    Column(modifier = Modifier.padding(horizontal = 16.dp).offset(y = (-26).dp)) {
                        Text(heroName, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        knsProfile?.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                            Spacer(Modifier.height(4.dp))
                            Text(bio, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            run {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)
                ) {
                    ProfileCircleAction(
                        icon = Icons.Default.QrCode,
                        label = stringResource(R.string.receive_kaspa),
                        modifier = Modifier.weight(1f)
                    ) {
                        showAcceptPaymentQr = true
                    }
                    ProfileCircleAction(
                        icon = Icons.Default.QrCode,
                        label = "Chatting Address",
                        modifier = Modifier.weight(1f)
                    ) {
                        showFundIdentityQr = true
                    }
                }
            }

            // Compact action rows (iOS parity: ContactsView.addressDropdownsSection), one per
            // address role. Each shows the role title with its balance, plus three icon-only
            // circle buttons: Copy, Send, Manage. Replaces the old expanding dropdown sections
            // that hid the exact same three actions behind a chevron tap; the rich management
            // screens stay reachable via Manage exactly as before.
            val addressCardClipboardManager = LocalClipboardManager.current
            ProfileAddressActionCard(
                title = "Chatting",
                address = address,
                balanceText = balance,
                onCopy = {
                    address?.let {
                        addressCardClipboardManager.setText(AnnotatedString(it))
                        showAddressCopiedToast(context, it)
                    }
                },
                onSend = { if (address != null) showWithdrawDialog = true },
                onManage = { if (address != null) navController.navigate("identity_address_detail") }
            )

            // Separate from the identity address above, purely for payment privacy — "Pay in
            // Kaspa" sends always come out of this address, never the identity one above. It
            // rotates to a freshly derived address after every send (see WalletManager's
            // spending-address doc comment), so this always shows whichever one is current.
            ProfileAddressActionCard(
                title = "Spending",
                address = spendingAddress,
                balanceText = spendingBalance,
                onCopy = {
                    spendingAddress?.let {
                        addressCardClipboardManager.setText(AnnotatedString(it))
                        showAddressCopiedToast(context, it)
                    }
                },
                onSend = { showSpendingWithdrawDialog = true },
                onManage = { navController.navigate("manage_addresses") }
            )

            if (pendingKnsCommit != null) {
                SettingsSection(title = null) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.a_kns_inscription_s_commit_transaction),
                            color = LocalAppColors.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.retryPendingKnsReveal() },
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                            enabled = knsInscribeState.status != WalletViewModel.KnsInscribeUiStatus.SUBMITTING_REVEAL
                        ) {
                            Text(stringResource(R.string.retry_inscription_reveal), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Curated Kaspa sites (4.0): its own page, matching iOS's ProfileAppsView -
            // Apps sits ABOVE Help, same as iOS.
            SettingsSection(title = null) {
                SettingsNavigationItem(stringResource(R.string.apps), Icons.Default.Apps, onClick = {
                    navController.navigate("kaspa_apps")
                })
            }

            SettingsSection(title = null) {
                SettingsNavigationItem(stringResource(R.string.help), Icons.AutoMirrored.Filled.HelpOutline, onClick = {
                    navController.navigate("help")
                })
            }

            GiftClaimProfileSection(walletAddress = address)

            // Bottom-most section on Profile - merges what used to be a separate "Info" section
            // (just "Created") with Settings' old "About" section (Version/Website/Support
            // Email/Donate), now reached without needing to open Settings at all.
            SettingsSection(title = null) {
                SettingsActionItem(stringResource(R.string.log_out), Icons.AutoMirrored.Filled.Logout, Color.Red) {
                    showLogoutConfirmation = true
                }
            }

            SettingsSection(title = stringResource(R.string.about)) {
                SettingsInfoItem(stringResource(R.string.created), "Apr 22, 2026 at 8:33 AM")
                SettingsDivider()
                SettingsInfoItem(stringResource(R.string.version), com.kachat.app.BuildConfig.VERSION_NAME)
                SettingsDivider()
                SettingsInfoItem(
                    stringResource(R.string.website),
                    "https://linktr.ee/Kachat_",
                    KaspaTeal,
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://linktr.ee/Kachat_")))
                        } catch (e: Exception) { /* no browser available */ }
                    }
                )
                SettingsDivider()
                SettingsInfoItem(
                    stringResource(R.string.support_email),
                    "kaspasilver@gmail.com",
                    KaspaTeal,
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:kaspasilver@gmail.com")))
                        } catch (e: Exception) { /* no email app available */ }
                    }
                )
                SettingsDivider()
                SettingsInfoItem(
                    stringResource(R.string.donate),
                    ChatViewModel.DONATION_KNS_DOMAIN,
                    KaspaTeal,
                    onClick = {
                        chatViewModel.startDonationChat(
                            onResolved = { donateAddress -> navController.navigate("chat/$donateAddress?paymentMode=true") },
                            onError = {
                                Toast.makeText(context, "Couldn't reach ${ChatViewModel.DONATION_KNS_DOMAIN} right now. Try again later", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                )
            }

            // Moved here from Settings > Actions - Profile is where the rest of the
            // account-level actions (address management, About) already live.

            Spacer(modifier = Modifier.height(80.dp))
        }

        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        if (showFundIdentityQr) {
            QrCodeOverlay(
                value = address ?: "",
                onDismiss = { showFundIdentityQr = false },
                message = "Just send 5-10 KAS at a time, that's plenty to cover chat fees for a while (about 500 messages per KAS)",
                borderColor = KaspaTeal,
                borderWidth = 4.dp
            )
        }
        if (showAcceptPaymentQr) {
            QrCodeOverlay(
                value = spendingAddress ?: "",
                onDismiss = { showAcceptPaymentQr = false },
                message = "Only accept Kaspa you intend to use as money to this address.",
                borderColor = KaspaTeal,
                borderWidth = 4.dp
            )
        }
        }
    }

    if (showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmation = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.log_out), color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.this_signs_out_of_your_account),
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirmation = false
                    viewModel.logout()
                }) {
                    Text(stringResource(R.string.log_out), color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmation = false }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}

/**
 * Dedicated KNS domain-management screen — the owned-domain list (star to mark primary, swap
 * icon to transfer), plus inscribing a new domain. Used to live inline as a collapsible section
 * on [ProfileScreen] itself; broken out once the list plus its two dialogs (inscribe/transfer)
 * made that screen too crowded to scan at a glance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnsDomainsScreen(viewModel: WalletViewModel, onBack: () -> Unit) {
    val ownedDomainAssets by viewModel.ownedDomainAssets.collectAsState()
    val primaryDomainName by viewModel.primaryDomainName.collectAsState()
    val setPrimaryState by viewModel.setPrimaryState.collectAsState()
    val domainPreview by viewModel.domainPreview.collectAsState()
    val knsInscribeState by viewModel.knsInscribeState.collectAsState()
    var showInscribeDialog by remember { mutableStateOf(false) }
    var domainLabelInput by remember { mutableStateOf("") }
    var selectedDomain by remember { mutableStateOf<com.kachat.app.services.KnsAsset?>(null) }
    var showSendScreen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshOwnedDomainsAndAwait()
    }

    val pullRefreshState = rememberPullToRefreshState()
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.refreshOwnedDomainsAndAwait()
            pullRefreshState.endRefresh()
        }
    }

    // Full-screen swap: domain detail (card, primary status, Send entry point) - reached by
    // tapping a card in the list below. No transfer-history section: KNS only exposes a
    // "currently owned assets" endpoint, and this app's own KNS-transfer tracking is a one-shot
    // chat notification, not a persisted per-domain log, so there's no reliable data source yet.
    selectedDomain?.let { domain ->
        if (!showSendScreen) {
            val isPrimary = domain.asset != null && domain.asset == primaryDomainName
            val settingThisOne = setPrimaryState.inFlight && setPrimaryState.assetId == domain.assetId
            KnsDomainDetailScreen(
                domain = domain,
                isPrimary = isPrimary,
                settingInFlight = settingThisOne,
                onSetPrimary = { domain.assetId?.let { viewModel.setPrimaryDomain(it) } },
                onSend = {
                    viewModel.resetTransferDomainState()
                    showSendScreen = true
                },
                onBack = { selectedDomain = null }
            )
            return
        } else {
            KnsDomainSendScreen(
                domain = domain,
                viewModel = viewModel,
                onDone = {
                    showSendScreen = false
                    selectedDomain = null
                },
                onBack = { showSendScreen = false }
            )
            return
        }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.kns_domains), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    domainLabelInput = ""
                    viewModel.clearDomainPreview()
                    viewModel.resetKnsInscribeState()
                    showInscribeDialog = true
                },
                // Deliberately NOT teal - the domain cards are teal-filled, so the action
                // button contrasts: surface background, teal text, teal outline.
                containerColor = LocalAppColors.current.surface,
                contentColor = KaspaTeal,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .height(56.dp)
                    .widthIn(min = 120.dp)
                    .border(1.5.dp, KaspaTeal, RoundedCornerShape(28.dp))
            ) {
                Text(
                    stringResource(R.string.inscribe_new_domain),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(pullRefreshState.nestedScrollConnection)
        ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(16.dp)) }
            if (ownedDomainAssets.isEmpty()) {
                item {
                    Text(text = stringResource(R.string.no_domains_yet), color = LocalAppColors.current.textSecondary, modifier = Modifier.padding(16.dp))
                }
            } else {
                items(ownedDomainAssets.filter { it.asset != null }, key = { it.assetId ?: it.asset ?: it.hashCode().toString() }) { domainAsset ->
                    val isPrimary = domainAsset.asset == primaryDomainName
                    KnsDomainCard(
                        domain = domainAsset,
                        isPrimary = isPrimary,
                        modifier = Modifier.clickable { selectedDomain = domainAsset }
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(100.dp)) }
        }

        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        }
    }

    if (showInscribeDialog) {
        val inFlight = knsInscribeState.status !in listOf(
            WalletViewModel.KnsInscribeUiStatus.IDLE,
            WalletViewModel.KnsInscribeUiStatus.SUCCESS,
            WalletViewModel.KnsInscribeUiStatus.FAILED
        )
        AlertDialog(
            onDismissRequest = { if (!inFlight) showInscribeDialog = false },
            title = {
                Text(
                    when (knsInscribeState.status) {
                        WalletViewModel.KnsInscribeUiStatus.SUCCESS -> "Domain Registered"
                        WalletViewModel.KnsInscribeUiStatus.FAILED -> "Inscription Failed"
                        else -> "Inscribe New Domain"
                    },
                    color = LocalAppColors.current.textPrimary
                )
            },
            containerColor = LocalAppColors.current.surface,
            text = {
                Column {
                    when (knsInscribeState.status) {
                        WalletViewModel.KnsInscribeUiStatus.IDLE -> {
                            OutlinedTextField(
                                value = domainLabelInput,
                                onValueChange = {
                                    domainLabelInput = it
                                    viewModel.checkDomainLabel(it)
                                },
                                label = { Text(stringResource(R.string.domain_name)) },
                                suffix = { Text(stringResource(R.string.kas)) },
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
                            Spacer(Modifier.height(12.dp))
                            domainPreview?.let { preview ->
                                when {
                                    preview.checking -> Text(stringResource(R.string.checking_availability), color = LocalAppColors.current.textSecondary)
                                    preview.errorMessage != null -> Text(preview.errorMessage, color = Color(0xFFFF3B30))
                                    preview.available == false -> Text("${preview.label}.kas is not available", color = Color(0xFFFF3B30))
                                    preview.available == true && preview.isReserved -> {
                                        Text("${preview.label}.kas is available", color = Color(0xFF4CD964), fontWeight = FontWeight.Bold)
                                        Text(stringResource(R.string.reserved_domain_no_registration_fee_only), color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                                    }
                                    preview.available == true -> {
                                        Text("${preview.label}.kas is available", color = Color(0xFF4CD964), fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(8.dp))
                                        val revealKas = preview.revealKas ?: 0.0
                                        val commitKas = preview.commitKas ?: 0.0
                                        Text(
                                            "Registration fee: ${"%.2f".format(revealKas)} KAS",
                                            color = LocalAppColors.current.textPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "You'll send ~${"%.2f".format(commitKas)} KAS total; ~${"%.2f".format((commitKas - revealKas).coerceAtLeast(0.0))} KAS comes back as change, the rest covers the fee and network costs.",
                                            color = LocalAppColors.current.textSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                        WalletViewModel.KnsInscribeUiStatus.CHECKING_AVAILABILITY -> InscribeProgressRow(stringResource(R.string.checking_availability))
                        WalletViewModel.KnsInscribeUiStatus.FETCHING_FEE -> InscribeProgressRow(stringResource(R.string.calculating_fee))
                        WalletViewModel.KnsInscribeUiStatus.SUBMITTING_COMMIT -> InscribeProgressRow(stringResource(R.string.submitting_commit_transaction))
                        WalletViewModel.KnsInscribeUiStatus.SUBMITTING_REVEAL -> InscribeProgressRow(stringResource(R.string.submitting_reveal_transaction))
                        WalletViewModel.KnsInscribeUiStatus.VERIFYING -> InscribeProgressRow(stringResource(R.string.verifying_on_chain_this_can_take))
                        WalletViewModel.KnsInscribeUiStatus.SUCCESS -> {
                            val result = knsInscribeState.result
                            Text("${result?.domain} is now yours.", color = Color(0xFF4CD964), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Commit tx: ${result?.commitTxId}", color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                            Text("Reveal tx: ${result?.revealTxId}", color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                            if (result?.verified == false) {
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.still_indexing_it_ll_show_up), color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        WalletViewModel.KnsInscribeUiStatus.FAILED -> {
                            Text(knsInscribeState.errorMessage ?: "Something went wrong", color = Color(0xFFFF3B30))
                        }
                    }
                }
            },
            confirmButton = {
                when (knsInscribeState.status) {
                    WalletViewModel.KnsInscribeUiStatus.IDLE -> {
                        val preview = domainPreview
                        TextButton(
                            onClick = { viewModel.inscribeDomain(preview?.label ?: domainLabelInput) },
                            enabled = preview?.available == true
                        ) {
                            val costLabel = preview?.commitKas?.let { " (pay ~${"%.2f".format(it)} KAS)" } ?: ""
                            Text("Inscribe$costLabel", color = if (preview?.available == true) KaspaTeal else Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }
                    WalletViewModel.KnsInscribeUiStatus.SUCCESS, WalletViewModel.KnsInscribeUiStatus.FAILED -> {
                        TextButton(onClick = { showInscribeDialog = false }) {
                            Text(stringResource(R.string.done), color = KaspaTeal, fontWeight = FontWeight.Bold)
                        }
                    }
                    else -> {}
                }
            },
            dismissButton = {
                if (!inFlight && knsInscribeState.status == WalletViewModel.KnsInscribeUiStatus.IDLE) {
                    TextButton(onClick = { showInscribeDialog = false }) {
                        Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                    }
                }
            }
        )
    }

}

/** Teal card matching the app's KNS domain branding - used both as the row style in [KnsDomainsScreen] and as the header of [KnsDomainDetailScreen]. */
@Composable
fun KnsDomainCard(domain: com.kachat.app.services.KnsAsset, isPrimary: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(KaspaTeal),
        contentAlignment = Alignment.Center
    ) {
        Text(
            domain.asset ?: "",
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        if (isPrimary) {
            Text(
                stringResource(R.string.primary),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Detail screen for a single owned domain - the card itself, primary/status info, and a
 * dedicated Send entry point. Reached by tapping a card in [KnsDomainsScreen]. No transfer
 * history section: KNS only exposes a "currently owned assets" endpoint, and this app's own
 * KNS-transfer tracking isn't a persisted per-domain log, so there's no reliable data source yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnsDomainDetailScreen(
    domain: com.kachat.app.services.KnsAsset,
    isPrimary: Boolean,
    settingInFlight: Boolean,
    onSetPrimary: () -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(domain.asset ?: "", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        bottomBar = {
            Column(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = onSend,
                    enabled = domain.assetId != null,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = KaspaTeal.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.send), color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            KnsDomainCard(domain = domain, isPrimary = isPrimary)
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(LocalAppColors.current.surface)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.asset_id), color = LocalAppColors.current.textPrimary, modifier = Modifier.weight(1f))
                    Text(
                        domain.assetId ?: "",
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
                HorizontalDivider(color = LocalAppColors.current.background)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!isPrimary && domain.assetId != null) Modifier.clickable(enabled = !settingInFlight) { onSetPrimary() }
                            else Modifier
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isPrimary) stringResource(R.string.primary_domain) else stringResource(R.string.set_as_primary),
                        color = LocalAppColors.current.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    if (settingInFlight) {
                        CircularProgressIndicator(color = KaspaTeal, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (isPrimary) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = null,
                            tint = KaspaTeal
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sends (transfers) a single KNS domain inscription to a recipient address or KNS domain - same
 * UX conventions as the app's KAS send flows (KNS-domain-aware recipient, editable network fee)
 * but with no amount field or coin control, since a domain transfer moves the whole inscription
 * rather than a chosen KAS amount.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnsDomainSendScreen(
    domain: com.kachat.app.services.KnsAsset,
    viewModel: WalletViewModel,
    onDone: () -> Unit,
    onBack: () -> Unit,
    /** Non-null: sign/fund the transfer from this spending-chain address index instead of the
     *  identity address (the spending-address detail screen's KNS Domains tab passes it). */
    fromSpendingAddressIndex: Int? = null,
    /** The source address matching [fromSpendingAddressIndex] — recipient validation checks the
     *  recipient differs from the SOURCE, not necessarily from the identity address. */
    sourceAddress: String? = null
) {
    val recipientPreview by viewModel.transferRecipientPreview.collectAsState()
    val transferState by viewModel.transferDomainState.collectAsState()
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    val uriHandler = LocalUriHandler.current

    var recipientInput by remember { mutableStateOf("") }
    var feeTier by remember { mutableStateOf(ColdFeeTier.NORMAL) }
    var customFeeSompi by remember { mutableStateOf<Long?>(null) }
    var isEditingFee by remember { mutableStateOf(false) }
    var customFeeText by remember { mutableStateOf("") }

    val domainName = domain.asset ?: ""
    val assetId = domain.assetId ?: ""
    val baseFeeSompi = KnsInscriptionEngine.REVEAL_PRIORITY_FEE_SOMPI
    val priorityFeeSompi = customFeeSompi ?: (baseFeeSompi * feeTier.multiplier)
    val inFlight = transferState.status !in listOf(
        WalletViewModel.KnsInscribeUiStatus.IDLE,
        WalletViewModel.KnsInscribeUiStatus.SUCCESS,
        WalletViewModel.KnsInscribeUiStatus.FAILED
    )
    val canSend = !inFlight && recipientPreview?.resolvedAddress != null

    fun trimmedKas(sompi: Long): String {
        var text = "%.8f".format(sompi / 100_000_000.0)
        while (text.endsWith("0")) text = text.dropLast(1)
        if (text.endsWith(".")) text = text.dropLast(1)
        return text
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.send_domain), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !inFlight) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        bottomBar = {
            if (transferState.status != WalletViewModel.KnsInscribeUiStatus.SUCCESS) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = { viewModel.transferDomain(domainName, assetId, priorityFeeSompi, fromSpendingAddressIndex) },
                        enabled = canSend,
                        colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = KaspaTeal.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        if (inFlight) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.send), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (transferState.status == WalletViewModel.KnsInscribeUiStatus.SUCCESS) {
                val result = transferState.result
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CD964), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.sent), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(20.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(LocalAppColors.current.surface)
                            .padding(16.dp)
                    ) {
                        Text(stringResource(R.string.to), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                        Text(result?.toAddress ?: "", color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(LocalAppColors.current.surface)
                            .clickable { result?.revealTxId?.let { uriHandler.openUri(kaspaExplorer.txUrl(it)) } }
                            .padding(16.dp)
                    ) {
                        Text("Transaction ID · tap to view in ${kaspaExplorer.displayName}", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                        Text(result?.revealTxId ?: "", color = KaspaTeal, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onDone,
                        colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(stringResource(R.string.done), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                return@Column
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(LocalAppColors.current.surface)
                    .padding(16.dp)
            ) {
                Text(domainName, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                Text(assetId, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }

            when (transferState.status) {
                WalletViewModel.KnsInscribeUiStatus.SUBMITTING_COMMIT -> InscribeProgressRow(stringResource(R.string.submitting_commit_transaction))
                WalletViewModel.KnsInscribeUiStatus.SUBMITTING_REVEAL -> InscribeProgressRow(stringResource(R.string.submitting_reveal_transaction))
                WalletViewModel.KnsInscribeUiStatus.VERIFYING -> InscribeProgressRow(stringResource(R.string.verifying_new_ownership_on_chain_this))
                WalletViewModel.KnsInscribeUiStatus.FAILED -> {
                    Text(transferState.errorMessage ?: stringResource(R.string.something_went_wrong), color = Color(0xFFFF3B30))
                }
                else -> {
                    Text(stringResource(R.string.recipient_address).uppercase(), color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = recipientInput,
                        onValueChange = {
                            recipientInput = it
                            viewModel.checkTransferRecipient(it, sourceAddress)
                        },
                        placeholder = { Text(stringResource(R.string.recipient_address_or_kas_name)) },
                        singleLine = true,
                        enabled = !inFlight,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    recipientPreview?.let { preview ->
                        Spacer(Modifier.height(4.dp))
                        when {
                            preview.checking -> Text(stringResource(R.string.resolving), color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                            preview.errorMessage != null -> Text(preview.errorMessage, color = Color(0xFFFF3B30), style = MaterialTheme.typography.bodySmall)
                            preview.resolvedAddress != null -> Text("Resolves to: ${preview.resolvedAddress}", color = Color(0xFF4CD964), style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("FEE", color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(LocalAppColors.current.surface)
                    ) {
                        ColdFeeTier.entries.forEach { tier ->
                            val selected = feeTier == tier && customFeeSompi == null
                            Text(
                                tier.label,
                                color = if (selected) Color.Black else LocalAppColors.current.textSecondary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) KaspaTeal else Color.Transparent)
                                    .clickable(enabled = !inFlight) {
                                        feeTier = tier
                                        customFeeSompi = null
                                        isEditingFee = false
                                    }
                                    .padding(vertical = 10.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.network_fee), color = LocalAppColors.current.textPrimary, modifier = Modifier.weight(1f))
                        if (isEditingFee) {
                            OutlinedTextField(
                                value = customFeeText,
                                onValueChange = { customFeeText = it },
                                singleLine = true,
                                modifier = Modifier.width(110.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = LocalAppColors.current.textPrimary,
                                    unfocusedTextColor = LocalAppColors.current.textPrimary
                                )
                            )
                            IconButton(onClick = {
                                customFeeText.toDoubleOrNull()?.let { kas ->
                                    customFeeSompi = (kas * 100_000_000).toLong().coerceAtLeast(0)
                                }
                                isEditingFee = false
                            }) {
                                Icon(Icons.Default.Check, null, tint = KaspaTeal)
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable(enabled = !inFlight) {
                                    customFeeText = trimmedKas(priorityFeeSompi)
                                    isEditingFee = true
                                }
                            ) {
                                Text("${trimmedKas(priorityFeeSompi)} KAS", color = KaspaTeal, textDecoration = TextDecoration.Underline)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.Edit, null, tint = KaspaTeal, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }

        }
    }
}

/**
 * Every spending-chain address derived so far, plus the identity address shown first (grayed
 * out, tapping it warns rather than lets you copy it) — since paying the identity address
 * instead of the current spending address defeats the whole point of keeping them separate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageAddressesScreen(
    viewModel: WalletViewModel,
    onBack: () -> Unit,
    onNavigateToTxHistory: (Int) -> Unit = {},
    onNavigateToHidden: () -> Unit = {},
    onNavigateToVisibility: () -> Unit = {},
    onAddressPicked: ((com.kachat.app.services.WalletService.SpendingAddressEntry) -> Unit)? = null
) {
    val identityAddress by viewModel.address.collectAsState()
    val addresses by viewModel.manageAddresses.collectAsState()
    val loading by viewModel.manageAddressesLoading.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showIdentityWarning by remember { mutableStateOf(false) }
    var activateIndex by remember { mutableStateOf<Int?>(null) }
    var qrAddress by remember { mutableStateOf<String?>(null) }
    var renamingEntry by remember { mutableStateOf<com.kachat.app.services.WalletService.SpendingAddressEntry?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var showActionsMenu by remember { mutableStateOf(false) }
    var actionsMenuAnchor by remember { mutableStateOf(Offset.Zero) }
    var showConsolidateConfirm by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()
    val consolidateState by viewModel.consolidateState.collectAsState()

    // Ordering (matches iOS ManageAddressesView): the primary address first, then addresses with
    // a balance OR an owned KNS domain (funded before domain-only, newest index first within each
    // group), then fresh addresses. Domain knowledge fills in asynchronously, so rows may
    // re-partition once the batched KNS lookups land.
    val domainOwningAddresses by viewModel.domainOwningAddresses.collectAsState()
    // "Chat privacy address" rows: reserved and offered to a contact by the fresh-address
    // payment pool. On the normal manage screen these live on their own read-only "Chat
    // Privacy" tab instead of the main Addresses list (iOS parity); the Swap address picker
    // variant keeps them inline with their tag. Never offered a Hide action while the offer
    // stands. A reverted offer (revoke, superseding re-offer, or funding) leaves the set and
    // its row moves back to the main list as a normal address again.
    val privacyReservedAddresses by viewModel.privacyReservedAddresses.collectAsState()
    // The Chat Privacy tab exists only while the current account's Chats Payment Privacy
    // toggle is ON: toggle OFF releases every offered reservation (the active set is empty by
    // definition), so the screen shows no tab row at all - just the plain Addresses list.
    val chatsPaymentPrivacyOn by viewModel.chatsPaymentPrivacyEnabled.collectAsState()
    // 0 = Addresses, 1 = Chat Privacy. Tabs exist only on the normal screen, not the picker.
    var selectedManageTab by remember { mutableStateOf(0) }
    val manageTabsVisible = onAddressPicked == null && chatsPaymentPrivacyOn
    val showingChatPrivacyTab = manageTabsVisible && selectedManageTab == 1
    // If the toggle flips OFF while the user is sitting on the Chat Privacy tab, land them on
    // Addresses - the tab they were on no longer exists.
    LaunchedEffect(chatsPaymentPrivacyOn) {
        if (!chatsPaymentPrivacyOn) selectedManageTab = 0
    }
    val chatPrivacyAddresses = remember(addresses, privacyReservedAddresses) {
        addresses.filter { it.address in privacyReservedAddresses }.sortedBy { it.index }
    }
    val visibleAddresses = remember(addresses, domainOwningAddresses, privacyReservedAddresses) {
        val visible = addresses.filterNot { it.hidden || (onAddressPicked == null && it.address in privacyReservedAddresses) }
        val primary = visible.filter { it.isCurrent }
        val rest = visible.filterNot { it.isCurrent }
            .sortedWith(compareByDescending<com.kachat.app.services.WalletService.SpendingAddressEntry> { it.balanceSompi > 0 }.thenByDescending { it.index })
        val active = rest.filter { it.balanceSompi > 0 || it.address in domainOwningAddresses }
        val fresh = rest.filter { it.balanceSompi == 0L && it.address !in domainOwningAddresses }
        primary + active + fresh
    }
    val hiddenAddresses = remember(addresses) { addresses.filter { it.hidden } }

    LaunchedEffect(Unit) {
        viewModel.loadManageAddresses()
    }

    LaunchedEffect(consolidateState.status) {
        when (consolidateState.status) {
            WalletViewModel.ConsolidateStatus.SUCCESS -> {
                val count = consolidateState.sweptCount
                Toast.makeText(
                    context,
                    if (count > 0) "Consolidated $count address${if (count == 1) "" else "es"}" else context.getString(R.string.nothing_to_consolidate),
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.resetConsolidateState()
            }
            WalletViewModel.ConsolidateStatus.FAILED -> {
                Toast.makeText(context, consolidateState.errorMessage ?: context.getString(R.string.consolidation_failed), Toast.LENGTH_SHORT).show()
                viewModel.resetConsolidateState()
            }
            else -> {}
        }
    }

    // Pull-to-refresh owns its spinner end-to-end (same pattern as the Profile and Domains
    // screens): await the refresh, then dismiss. NEVER keyed off manageAddressesLoading — that
    // flag only flips on the empty-list initial load (a warm pull leaves it false the whole
    // time, so a spinner waiting on it spun forever) and is also driven by background reloads,
    // which must not surface or dismiss the pull indicator.
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.loadManageAddressesAndAwait()
            pullRefreshState.endRefresh()
        }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.manage_addresses), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                actions = {
                    // Bulk visibility manager (iOS parity): compact checkmark list of EVERY
                    // address, so dozens can be toggled off the main list in one sitting.
                    if (onAddressPicked == null) {
                        IconButton(onClick = onNavigateToVisibility) {
                            Icon(Icons.Default.Checklist, "Manage address visibility", tint = KaspaTeal)
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            // Hidden while the QR overlay is up — its Dialog window doesn't fully cover the
            // screen, so the FAB would otherwise still show through around the QR card. Also
            // hidden entirely on the Chat Privacy tab: that tab is purely a viewer, so
            // Generate/Discover/Consolidate disappear there (iOS parity).
            if (qrAddress == null && !showingChatPrivacyTab) {
            FloatingActionButton(
                onClick = { showActionsMenu = true },
                containerColor = KaspaTeal,
                contentColor = Color.Black,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .height(56.dp)
                    .onGloballyPositioned { coords -> actionsMenuAnchor = coords.positionInWindow() }
            ) {
                val addressActionsContentDescription = stringResource(R.string.address_actions_2)
                Text(
                    stringResource(R.string.address_actions),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .semantics { contentDescription = addressActionsContentDescription }
                )
            }
            }
            // A regular DropdownMenu anchors to (and can get pushed off to one side of) this
            // now screen-centered FAB rather than the screen itself — see CenteredOptionsMenu's
            // doc comment for why this uses a real Dialog instead. Anchored to the FAB's top edge
            // (no height offset) so the card grows upward and sits just above the button, matching
            // the composer "+" menu's same near-bottom-of-screen anchoring. centerHorizontally
            // overrides the usual left/right-edge hugging (which assumes the anchor sits near a
            // screen edge) since this FAB is itself screen-centered.
            if (showActionsMenu) {
                CenteredOptionsMenu(
                    onDismissRequest = { showActionsMenu = false },
                    anchor = actionsMenuAnchor,
                    centerHorizontally = true
                ) {
                    PopupMenuRow(Icons.Default.AddCircleOutline, stringResource(R.string.generate_new_spending_address)) {
                        showActionsMenu = false
                        viewModel.generateNewSpendingAddress { index ->
                            Toast.makeText(
                                context,
                                if (index != null) "Spending address #$index is ready."
                                else "Could not check addresses. Try again when connected.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(Icons.Default.Search, stringResource(R.string.discover_addresses)) {
                        showActionsMenu = false
                        viewModel.discoverSpendingAddresses { count ->
                            Toast.makeText(
                                context,
                                if (count > 0) "Found $count used address${if (count == 1) "" else "es"}" else "No additional used addresses found",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(Icons.AutoMirrored.Filled.CallMerge, stringResource(R.string.send_all_kaspa_to_primary_spend)) {
                        showActionsMenu = false
                        showConsolidateConfirm = true
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        // Top-of-screen tab switch, same TabRow treatment as the address-details screen's
        // History/UTXOs/KNS Domains tabs. "Addresses" is the normal spending-address list;
        // "Chat Privacy" is the read-only view of addresses actively offered to contacts as
        // Chats Payment Privacy pool reservations. The Swap address picker keeps the plain
        // list, and so does an account whose Chats Payment Privacy toggle is OFF (no tabs
        // at all - see manageTabsVisible).
        if (manageTabsVisible) {
            TabRow(
                selectedTabIndex = selectedManageTab,
                containerColor = LocalAppColors.current.background,
                contentColor = KaspaTeal
            ) {
                Tab(
                    selected = selectedManageTab == 0,
                    onClick = { selectedManageTab = 0 },
                    text = { Text("Addresses") }
                )
                Tab(
                    selected = selectedManageTab == 1,
                    onClick = { selectedManageTab = 1 },
                    text = { Text("Chat Privacy") }
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Same fix as PortfolioScreen: an idle PullToRefreshContainer "hides" by
                // translating one full container-height above its own position via graphicsLayer,
                // and graphicsLayer translation does NOT clip. On screens whose pull Box is the
                // first child under the Scaffold padding, that resting gray circle lands in the
                // top-app-bar band and the opaque app bar draws over it - but here the Chat
                // Privacy TabRow sits above this Box, so without a clip the resting circle drew
                // right over the tab row, parked there with no pull in progress. clipToBounds
                // keeps the indicator inside this Box: invisible at rest, revealed by a real pull.
                .clipToBounds()
                .nestedScroll(pullRefreshState.nestedScrollConnection)
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showingChatPrivacyTab) {
                // Read-only viewer for addresses currently offered to contacts in LIVE Chats
                // Payment Privacy pools. Rows expose only Copy Address and Show QR Code; the
                // offer lifecycle (revoke/supersede/fund) manages these rows, not the user.
                item {
                    Text(
                        "These are fresh addresses offered to your contacts for private payments. Each contact gets their own, so your payment history stays unlinkable. KaChat keeps at least 2 fresh addresses per chat and replaces them as they are used.",
                        fontSize = 13.sp,
                        color = LocalAppColors.current.textSecondary,
                        lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
                if (loading && addresses.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = KaspaTeal)
                        }
                    }
                } else if (chatPrivacyAddresses.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = LocalAppColors.current.textSecondary,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "No Chat Privacy Addresses",
                                color = LocalAppColors.current.textPrimary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "When you chat with someone while Chats Payment Privacy is on, the fresh addresses offered to them appear here.",
                                color = LocalAppColors.current.textSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    items(chatPrivacyAddresses, key = { it.index }) { entry ->
                        ChatPrivacyAddressRow(
                            entry = entry,
                            onCopyClick = {
                                clipboardManager.setText(AnnotatedString(entry.address))
                                showAddressCopiedToast(context, entry.address)
                            },
                            onQrClick = { qrAddress = entry.address }
                        )
                    }
                }
            } else {
            if (onAddressPicked != null) {
                item {
                    Text(
                        stringResource(R.string.tap_an_address_below_to_swap),
                        color = KaspaTeal,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LocalAppColors.current.surface)
                        .clickable { showIdentityWarning = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.chatting_address), color = LocalAppColors.current.textSecondary, fontWeight = FontWeight.Bold)
                        Text(
                            text = identityAddress ?: "Loading...",
                            color = LocalAppColors.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // The Hidden (n) sub-screen survives only for Swap's address picker — the normal
            // screen manages visibility through the checklist button instead (iOS parity).
            if (hiddenAddresses.isNotEmpty() && onAddressPicked != null) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToHidden)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VisibilityOff, null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Hidden (${hiddenAddresses.size})",
                            color = LocalAppColors.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = LocalAppColors.current.textSecondary)
                    }
                }
            }

            if (loading && addresses.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = KaspaTeal)
                    }
                }
            } else {
                items(visibleAddresses, key = { it.index }) { entry ->
                    ManageAddressRow(
                        entry = entry,
                        showsDomainTag = entry.address in domainOwningAddresses,
                        showsPrivacyTag = entry.address in privacyReservedAddresses,
                        onClick = { if (onAddressPicked != null) onAddressPicked(entry) else onNavigateToTxHistory(entry.index) },
                        onCopyClick = {
                            clipboardManager.setText(AnnotatedString(entry.address))
                            showAddressCopiedToast(context, entry.address)
                        },
                        onQrClick = { qrAddress = entry.address },
                        onActivateClick = { if (!entry.isCurrent) activateIndex = entry.index },
                        onRenameClick = { renamingEntry = entry; renameInput = entry.label ?: "" },
                        onHideClick = {
                            // Same guards + copy as the Address Visibility checklist toggle. The
                            // reserved branch is a backstop only - reserved rows don't show the
                            // Hide menu entry at all.
                            if (entry.address in privacyReservedAddresses) {
                                Toast.makeText(context, "This address is offered to a contact for private payments and stays visible.", Toast.LENGTH_SHORT).show()
                            } else if (entry.balanceSompi > 0) {
                                Toast.makeText(context, "Addresses holding a balance stay visible.", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.setManageAddressHidden(entry.index, true) { ok ->
                                    Toast.makeText(
                                        context,
                                        if (ok) "Address hidden. Re-enable it in Address Visibility."
                                        else "This address stays visible. It is the primary address, holds a balance, or its balance could not be confirmed.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                }
            }

            item {
                // Leaves room so the last address row isn't hidden behind the FAB.
                Spacer(Modifier.height(64.dp))
            }
            }
        }

        // Hard guarantee on top of the clipToBounds above: the indicator is only ever composed
        // while a pull is actually in progress or a refresh is running. At rest (offset 0, not
        // refreshing) there is nothing to draw, so no layout quirk can park the gray circle
        // over the content again.
        if (pullRefreshState.verticalOffset > 0f || pullRefreshState.isRefreshing) {
            PullToRefreshContainer(
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        qrAddress?.let { address ->
            QrCodeOverlay(value = address, onDismiss = { qrAddress = null })
        }
        }
        }
    }

    if (showIdentityWarning) {
        AlertDialog(
            onDismissRequest = { showIdentityWarning = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.chatting_address), color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.never_send_kaspa_you_intend_to),
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { showIdentityWarning = false }) {
                    Text(stringResource(R.string.ok), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    activateIndex?.let { index ->
        ActivateAddressDialog(viewModel = viewModel, index = index, onDismiss = { activateIndex = null })
    }

    if (showConsolidateConfirm) {
        val consolidating = consolidateState.status == WalletViewModel.ConsolidateStatus.RUNNING
        AlertDialog(
            onDismissRequest = { if (!consolidating) showConsolidateConfirm = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.send_all_kaspa_to_primary_spend), color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.sends_every_other_spending_address_s),
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !consolidating,
                    onClick = {
                        viewModel.consolidateSpendingAddresses()
                        showConsolidateConfirm = false
                    }
                ) {
                    Text(stringResource(R.string.confirm), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(enabled = !consolidating, onClick = { showConsolidateConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    renamingEntry?.let { entry ->
        RenameAddressDialog(
            index = entry.index,
            nameInput = renameInput,
            onNameChange = { renameInput = it },
            onDismiss = { renamingEntry = null },
            onSave = {
                viewModel.setManageAddressLabel(entry.index, renameInput)
                renamingEntry = null
            }
        )
    }
}

/** Rename dialog shared by [ManageAddressesScreen] and [ManageAddressesHiddenScreen] — an empty/blank name clears back to the default "Address #N". */
@Composable
private fun RenameAddressDialog(
    index: Int,
    nameInput: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalAppColors.current.surface,
        title = { Text(stringResource(R.string.rename_address), color = LocalAppColors.current.textPrimary) },
        text = {
            Column {
                Text("Address #$index", color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.name)) },
                    placeholder = { Text("Address #$index") },
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
            TextButton(onClick = onSave) {
                Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
            }
        }
    )
}

/**
 * Every hidden spending address, reached via the "Hidden (N)" link on [ManageAddressesScreen] —
 * the only place a hidden address can be unhidden again. Shares [viewModel]'s own `manageAddresses`
 * list rather than loading a separate one, so it's always in sync with the main screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageAddressesHiddenScreen(
    viewModel: WalletViewModel,
    onBack: () -> Unit,
    onNavigateToTxHistory: (Int) -> Unit = {},
    onAddressPicked: ((com.kachat.app.services.WalletService.SpendingAddressEntry) -> Unit)? = null
) {
    val addresses by viewModel.manageAddresses.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var activateIndex by remember { mutableStateOf<Int?>(null) }
    var qrAddress by remember { mutableStateOf<String?>(null) }
    var renamingEntry by remember { mutableStateOf<com.kachat.app.services.WalletService.SpendingAddressEntry?>(null) }
    var renameInput by remember { mutableStateOf("") }

    val hiddenAddresses = remember(addresses) {
        addresses.filter { it.hidden }
            .sortedWith(compareByDescending<com.kachat.app.services.WalletService.SpendingAddressEntry> { it.balanceSompi > 0 }.thenByDescending { it.index })
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.hidden_addresses), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        }
    ) { padding ->
        if (hiddenAddresses.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.VisibilityOff, null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.no_hidden_addresses), color = LocalAppColors.current.textSecondary, textAlign = TextAlign.Center)
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (onAddressPicked != null) {
                        item {
                            Text(
                                stringResource(R.string.tap_an_address_below_to_use),
                                color = KaspaTeal,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    items(hiddenAddresses, key = { it.index }) { entry ->
                        ManageAddressRow(
                            entry = entry,
                            onClick = { if (onAddressPicked != null) onAddressPicked(entry) else onNavigateToTxHistory(entry.index) },
                            onCopyClick = {
                                clipboardManager.setText(AnnotatedString(entry.address))
                                showAddressCopiedToast(context, entry.address)
                            },
                            onQrClick = { qrAddress = entry.address },
                            onActivateClick = { if (!entry.isCurrent) activateIndex = entry.index },
                            onRenameClick = { renamingEntry = entry; renameInput = entry.label ?: "" }
                        )
                    }
                }
                qrAddress?.let { address ->
                    QrCodeOverlay(value = address, onDismiss = { qrAddress = null })
                }
            }
        }
    }

    activateIndex?.let { index ->
        ActivateAddressDialog(viewModel = viewModel, index = index, onDismiss = { activateIndex = null })
    }

    renamingEntry?.let { entry ->
        RenameAddressDialog(
            index = entry.index,
            nameInput = renameInput,
            onNameChange = { renameInput = it },
            onDismiss = { renamingEntry = null },
            onSave = {
                viewModel.setManageAddressLabel(entry.index, renameInput)
                renamingEntry = null
            }
        )
    }
}

/**
 * Address Visibility (iOS parity: SpendingAddressVisibilityView) — a compact checkmark list of
 * EVERY spending address, paged 50 at a time, so dozens can be toggled off the main Manage
 * Addresses list in one sitting. The right arrow never runs out: future pages derive addresses
 * beyond the revealed bound on the fly, and toggling one on raises the bound while keeping the
 * intermediate indices hidden. The primary, funded addresses, and addresses offered to a contact
 * for private payments ("Chat privacy address") are locked visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressVisibilityScreen(
    viewModel: WalletViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val addresses by viewModel.manageAddresses.collectAsState()
    // Chat-privacy pool reservations: locked visible (like primary/funded) and tagged.
    val privacyReservedAddresses by viewModel.privacyReservedAddresses.collectAsState()
    var page by remember { mutableStateOf(0) }
    val pageSize = 50
    val byIndex = remember(addresses) { addresses.associateBy { it.index } }
    val listMax = remember(addresses) { addresses.maxOfOrNull { it.index } ?: -1 }
    // Lazily filled Used/Unused results for rows derived beyond the loaded list.
    val usedCache = remember { mutableStateMapOf<Int, Boolean>() }

    // Refresh on entry: rows inherited from a previous screen visit can carry a stale
    // isCurrent/balance (the primary rotates after every send), and the toggle guards below
    // read those flags. The live reload also runs the self-heal purge for any wrongly hidden
    // primary/funded index.
    LaunchedEffect(Unit) { viewModel.loadManageAddresses() }

    val start = page * pageSize
    val end = start + pageSize - 1
    // Active chat-privacy reservations DO get a checklist row: shown checked with an inert
    // checkbox (they cannot be unchecked - tapping explains why) and the "Chat privacy address"
    // tag. They still stay OFF the main Addresses list (the Chat Privacy tab owns them there);
    // this checklist is the complete per-index map, so hiding rows here read as gaps.
    val pageEntries = remember(byIndex, page) {
        (start..end).map { index ->
            byIndex[index] ?: com.kachat.app.services.WalletService.SpendingAddressEntry(
                index = index,
                address = viewModel.spendingAddressAt(index) ?: "",
                balanceSompi = 0L,
                everUsed = false,
                isCurrent = false,
                hidden = true,
                label = null
            )
        }
    }
    val listState = rememberLazyListState()
    // A page flip must land the user at the TOP of the new page, not wherever the previous
    // page left the scroll offset.
    LaunchedEffect(page) { listState.scrollToItem(0) }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Address Visibility", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                actions = {
                    TextButton(onClick = onBack) {
                        Text("Done", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LocalAppColors.current.background)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { if (page > 0) page -= 1 }, enabled = page > 0) {
                    Icon(
                        Icons.Default.ChevronLeft,
                        "Previous page",
                        tint = if (page > 0) KaspaTeal else LocalAppColors.current.textSecondary.copy(alpha = 0.4f)
                    )
                }
                Text(
                    "#$start - #$end",
                    color = LocalAppColors.current.textSecondary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall
                )
                IconButton(onClick = { page += 1 }) {
                    Icon(Icons.Default.ChevronRight, "Next page", tint = KaspaTeal)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(pageEntries, key = { it.index }) { entry ->
                val funded = entry.balanceSompi > 0
                val reserved = entry.address.isNotEmpty() && entry.address in privacyReservedAddresses
                // Reserved rows always render checked: an actively offered chat-privacy address
                // is locked visible whatever a (stale) hidden flag or a mid-load race says.
                val visible = reserved || (entry.index <= listMax && !entry.hidden)
                // Used-state for derived rows the list loader has never seen.
                if (entry.index > listMax && entry.address.isNotEmpty() && entry.index !in usedCache) {
                    LaunchedEffect(entry.index) {
                        usedCache[entry.index] = viewModel.hasSpendingAddressBeenUsed(entry.address)
                    }
                }
                val used = if (entry.index <= listMax) entry.everUsed else usedCache[entry.index]
                // The WHOLE row toggles, not just the checkmark.
                val toggleVisibility: () -> Unit = {
                    when {
                        entry.isCurrent ->
                            Toast.makeText(context, "The primary address is always visible.", Toast.LENGTH_SHORT).show()
                        // Inert checkbox: offered chat-privacy reservations render checked and
                        // cannot be unchecked - tapping only explains the lock.
                        reserved ->
                            Toast.makeText(context, "This address is offered to a contact for private payments and stays visible.", Toast.LENGTH_SHORT).show()
                        funded && visible ->
                            Toast.makeText(context, "Addresses holding a balance stay visible.", Toast.LENGTH_SHORT).show()
                        entry.index > listMax ->
                            viewModel.revealSpendingAddress(entry.index)
                        else -> {
                            val hiding = !entry.hidden
                            viewModel.setManageAddressHidden(entry.index, hiding) { ok ->
                                if (hiding && !ok) {
                                    Toast.makeText(
                                        context,
                                        "This address stays visible. It is the primary address, holds a balance, or its balance could not be confirmed.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(LocalAppColors.current.surface)
                        .clickable(onClick = toggleVisibility)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .alpha(if (visible) 1f else 0.55f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = toggleVisibility,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            if (visible) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            if (visible) "Visible" else "Hidden",
                            tint = if (visible) KaspaTeal else LocalAppColors.current.textSecondary
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "#${entry.index}",
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (entry.isCurrent) {
                                Icon(Icons.Default.Star, "Primary address", tint = KaspaTeal, modifier = Modifier.size(14.dp))
                            }
                            entry.label?.takeIf { it.isNotBlank() }?.let { label ->
                                Text(label, color = LocalAppColors.current.textSecondary, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        Text(
                            if (entry.address.isNotEmpty()) "${entry.address.take(14)}...${entry.address.takeLast(6)}" else "deriving...",
                            color = LocalAppColors.current.textPrimary,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    when {
                        // The tag mirrors the lock order above. A funded reservation leaves the
                        // active set (reserved is false), so it falls through to the balance
                        // display and stays locked through the funded rule instead.
                        reserved -> Text(
                            "Chat privacy address",
                            color = KaspaTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        funded -> Text(
                            "%.4f KAS".format(java.util.Locale.US, entry.balanceSompi / 100_000_000.0),
                            color = KaspaTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        used != null -> Text(
                            if (used) "Used" else "Unused",
                            color = if (used) Color(0xFFF39C12) else Color(0xFF4CD964),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/** "Make Active Address" confirmation — shared by [ManageAddressesScreen] and [ManageAddressesHiddenScreen]. */
@Composable
private fun ActivateAddressDialog(viewModel: WalletViewModel, index: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalAppColors.current.surface,
        title = { Text(stringResource(R.string.make_active_address), color = LocalAppColors.current.textPrimary) },
        text = {
            Text(
                stringResource(R.string.spending_kaspa_on_kachat_will_come),
                color = LocalAppColors.current.textSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.setActiveSpendingAddress(index)
                onDismiss()
            }) {
                Text(stringResource(R.string.confirm), color = KaspaTeal, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
            }
        }
    )
}

/**
 * Full-screen send flow shared by every address this wallet can sign locally with: a specific
 * spending-chain address (pass [spendingIndex]) or the identity/chatting address (leave
 * [spendingIndex] null) — used from [SpendingAddressTxHistoryScreen]'s Send button and both of
 * [ProfileScreen]'s quick-send entry points ("Chatting Address" and "Spending Address"), so all
 * three open the exact same screen instead of each having their own bare-bones popup. Visually
 * mirrors [ColdSendFlow]'s full-screen layout (scrollable form, in-column primary button,
 * segmented [ColdFeeTier] picker, Coin Control row) and iOS's `SpendingAddressWithdrawView`/
 * `WithdrawKaspaView` field-for-field — but signs and broadcasts directly via
 * [WalletViewModel.withdrawFromSpendingAddress]/[WalletViewModel.onSendClicked] (this wallet
 * already holds the private key for either address), never the external-QR-signer round trip
 * [ColdSendFlow] uses for watch-only keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingAddressSendFlow(
    fromAddress: String,
    balanceSompi: Long,
    title: String,
    viewModel: WalletViewModel,
    onDone: () -> Unit,
    // Which spending-chain address index to sign with, or null for the identity address - the
    // only two send paths this wallet can sign locally with (see WalletViewModel.onSendClicked/
    // withdrawFromSpendingAddress). Everything else about this screen (coin control, fee tier,
    // KNS resolution) is identical either way.
    spendingIndex: Int? = null,
    // Pre-fills the recipient with fromAddress itself (a self-send) and auto-fills Max, for the
    // "Compound UTXOs" entry point - merges every UTXO at this address into one. Locks the
    // recipient field instead of just pre-filling it, matching ColdSendFlow's identical
    // isCompoundMode behavior, since editing it away from fromAddress would defeat the point of
    // a compound send.
    isCompoundMode: Boolean = false,
    portfolioViewModel: com.kachat.app.viewmodels.PortfolioViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    val fiatPriceInCurrency by portfolioViewModel.currentPriceUsd.collectAsState()
    val fiatCurrencyCode by portfolioViewModel.currency.collectAsState()
    var recipientInput by remember(fromAddress) { mutableStateOf("") }
    var amountInput by remember(fromAddress) { mutableStateOf("") }
    val fiatAmountState = com.kachat.app.util.rememberKaspaFiatAmountState(resetKey = fromAddress, onKasTextChange = { amountInput = it })
    var showScanner by remember { mutableStateOf(false) }
    var manualUtxos by remember { mutableStateOf<List<UtxoEntry>?>(null) }
    var showCoinControl by remember { mutableStateOf(false) }
    var isEstimatingMax by remember { mutableStateOf(false) }
    var feeTier by remember { mutableStateOf(ColdFeeTier.NORMAL) }
    var customExtraFeeSompi by remember { mutableStateOf<Long?>(null) }
    var showFeeEditor by remember { mutableStateOf(false) }
    var feeEditorInput by remember { mutableStateOf("") }
    var sentTxId by remember { mutableStateOf<String?>(null) }
    val isSending by viewModel.isSending.collectAsState()
    val sendResult by viewModel.sendResult.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Real input count when known (coin control fixes it exactly) instead of always guessing 1 -
    // same reasoning as ColdSendFlow's estimatedMass, just without a live automatic-selection
    // preview (this path doesn't have one; the fee shown here is still exact once coin control
    // is active, and a close estimate otherwise).
    val estimatedMass = remember(manualUtxos) {
        val inputCount = manualUtxos?.size?.takeIf { it > 0 } ?: 1
        KaspaMass.calculateMass(numInputs = inputCount, outputScriptLens = listOf(34, 34), payloadSize = 0)
    }
    val baseFeeRateSompiPerGram = KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM
    val defaultFeeSompi = KaspaMass.calculateFee(estimatedMass, baseFeeRateSompiPerGram)
    val extraFeeSompi = customExtraFeeSompi ?: (defaultFeeSompi * (feeTier.multiplier - 1))
    val effectiveFeeSompi = defaultFeeSompi + extraFeeSompi
    // Sompi-per-gram rate implied by effectiveFeeSompi/estimatedMass - what actually gets passed
    // to the engine, since sendKaspa/withdrawFromSpendingAddress take a rate, not a flat fee.
    val feeRateOverrideSompi = kotlin.math.ceil(effectiveFeeSompi.toDouble() / estimatedMass).toLong()

    var isResolvingKns by remember { mutableStateOf(false) }
    var knsResolvedAddress by remember { mutableStateOf<String?>(null) }
    var knsError by remember { mutableStateOf<String?>(null) }
    // Debounced KNS domain resolution - lets typing "name.kas" here resolve the same way Create
    // Chat's own address field already does. Skipped entirely in compound mode, where the
    // recipient is always the locked self-address, never user-typed.
    LaunchedEffect(recipientInput) {
        knsResolvedAddress = null
        knsError = null
        if (isCompoundMode) {
            isResolvingKns = false
            return@LaunchedEffect
        }
        val trimmed = recipientInput.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("kaspa:", ignoreCase = true) ||
            trimmed.startsWith("kaspatest:", ignoreCase = true) || !KnsService.looksLikeDomain(trimmed)
        ) {
            isResolvingKns = false
            return@LaunchedEffect
        }
        isResolvingKns = true
        kotlinx.coroutines.delay(500)
        val resolved = viewModel.resolveKnsDomain(trimmed)
        isResolvingKns = false
        if (resolved != null) knsResolvedAddress = resolved else knsError = "KNS domain not found"
    }

    LaunchedEffect(Unit) {
        if (isCompoundMode) {
            recipientInput = fromAddress
            isEstimatingMax = true
            try {
                // Consolidation is bounded by Kaspa's per-transaction mass cap, so pin the send to
                // one transaction's worth of UTXOs (largest-first, ≤MAX_INPUTS_PER_TRANSACTION) and
                // estimate Max over exactly those. This is why Max works even when the address has
                // more UTXOs than a single tx can hold - it consolidates one batch; repeat to reduce.
                val chunk = viewModel.maxConsolidatableChunk(fromAddress, feeRateOverrideSompi)
                if (chunk != null) {
                    manualUtxos = chunk.second
                    fiatAmountState.setMaxKas(chunk.first / 100_000_000.0, fiatPriceInCurrency)
                }
            } catch (e: Exception) {
                // Leave the field untouched on failure - same as the Max button itself.
            } finally {
                isEstimatingMax = false
            }
        }
    }

    LaunchedEffect(sendResult) {
        val result = sendResult ?: return@LaunchedEffect
        if (result.isSuccess) {
            sentTxId = result.getOrNull()
        } else {
            Toast.makeText(context, result.exceptionOrNull()?.message ?: context.getString(R.string.withdrawal_failed), Toast.LENGTH_SHORT).show()
        }
        viewModel.clearSendResult()
    }

    BackHandler(enabled = !isSending) { onDone() }

    if (showScanner) {
        BackHandler { showScanner = false }
        QrScannerOverlay(
            onScanned = { scanned -> recipientInput = scanned.trim(); showScanner = false },
            onDismiss = { showScanner = false }
        )
        return
    }

    if (showCoinControl) {
        BackHandler { showCoinControl = false }
        CoinControlScreen(
            fromAddress = fromAddress,
            fetchUtxos = { addr -> viewModel.fetchUtxosForCoinControl(addr) },
            initialSelection = manualUtxos,
            onDone = { selection -> manualUtxos = selection; showCoinControl = false },
            onCancel = { showCoinControl = false }
        )
        return
    }

    val amountSompi = amountInput.toDoubleOrNull()?.let { Math.round(it * 100_000_000.0) }
    val isValidAddress = remember(recipientInput) { KaspaAddress.isValid(recipientInput) }
    val effectiveAddress = knsResolvedAddress ?: recipientInput
    val hasValidRecipient = if (knsResolvedAddress != null) true else (isValidAddress && !isResolvingKns)

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (isCompoundMode) stringResource(R.string.compound_utxos) else title,
                        color = LocalAppColors.current.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (!isSending) onDone() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = if (isSending) Color.Gray else KaspaTeal)
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (sentTxId != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CD964), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.sent), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(20.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(LocalAppColors.current.surface)
                            .padding(16.dp)
                    ) {
                        Text(stringResource(R.string.to), color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                        Text(recipientInput, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(LocalAppColors.current.surface)
                            .clickable { sentTxId?.let { uriHandler.openUri(kaspaExplorer.txUrl(it)) } }
                            .padding(16.dp)
                    ) {
                        Text("Transaction ID · tap to view in ${kaspaExplorer.displayName}", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                        Text(sentTxId ?: "", color = KaspaTeal, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onDone,
                        colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(stringResource(R.string.done), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                return@Column
            }

            Text(
                (if (isCompoundMode) stringResource(R.string.consolidating_this_address) else stringResource(R.string.recipient_address)).uppercase(),
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            if (isCompoundMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CallMerge, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        fromAddress,
                        color = LocalAppColors.current.textPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                OutlinedTextField(
                    value = recipientInput,
                    onValueChange = { recipientInput = it },
                    placeholder = { Text("kaspa:qr... or name.kas") },
                    singleLine = true,
                    enabled = !isSending,
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
                if (recipientInput.isNotEmpty()) {
                    if (isResolvingKns) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = KaspaTeal, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.resolving_domain), color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (knsError != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = Color(0xFFFF3B30), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(knsError ?: "", color = Color(0xFFFF3B30), style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (knsResolvedAddress != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CD964), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Resolved to ${knsResolvedAddress?.takeLast(12)}", color = Color(0xFF4CD964), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isValidAddress) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                null,
                                tint = if (isValidAddress) Color(0xFF4CD964) else Color(0xFFFF3B30),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(if (isValidAddress) R.string.valid_address else R.string.invalid_address_format),
                                color = if (isValidAddress) Color(0xFF4CD964) else Color(0xFFFF3B30),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { clipboardManager.getText()?.text?.let { recipientInput = it.trim() } }, enabled = !isSending) {
                        Icon(Icons.Default.ContentPaste, null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.paste_from_clipboard), color = KaspaTeal, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showScanner = true }, enabled = !isSending) {
                        Icon(Icons.Default.QrCodeScanner, null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.scan_qr_code), color = KaspaTeal, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text(
                stringResource(R.string.amount_kas).uppercase(),
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = fiatAmountState.displayText,
                onValueChange = { fiatAmountState.onDisplayTextChange(it, fiatPriceInCurrency) },
                placeholder = { Text(if (fiatAmountState.isFiatMode) fiatCurrencyCode.uppercase() else stringResource(R.string.amount_kas)) },
                singleLine = true,
                enabled = !isSending,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                ),
                leadingIcon = {
                    IconButton(onClick = { fiatAmountState.toggleMode(fiatPriceInCurrency) }, enabled = !isSending) {
                        if (fiatAmountState.isFiatMode) {
                            Text(
                                com.kachat.app.util.currencySymbolFor(fiatCurrencyCode),
                                color = KaspaTeal,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Icon(
                                painterResource(R.drawable.ic_kaspa_logo),
                                stringResource(R.string.switch_between_kas_and_fiat),
                                tint = Color.Unspecified,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        fiatAmountState.conversionLabelText(fiatPriceInCurrency, fiatCurrencyCode)?.let { label ->
                            Text(
                                label,
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        if (isEstimatingMax) {
                            CircularProgressIndicator(color = KaspaTeal, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(
                                onClick = {
                                    coroutineScope.launch {
                                        isEstimatingMax = true
                                        try {
                                            if (isCompoundMode) {
                                                // One mass-safe transaction's worth (largest-first, ≤cap), pinned as
                                                // the input set - see the compound LaunchedEffect above.
                                                val chunk = viewModel.maxConsolidatableChunk(fromAddress, feeRateOverrideSompi)
                                                if (chunk != null) {
                                                    manualUtxos = chunk.second
                                                    fiatAmountState.setMaxKas(chunk.first / 100_000_000.0, fiatPriceInCurrency)
                                                }
                                            } else {
                                                val maxSompi = viewModel.estimateMaxSendableAmount(fromAddress, feeRateOverrideSompi, manualUtxos)
                                                fiatAmountState.setMaxKas(maxSompi / 100_000_000.0, fiatPriceInCurrency)
                                            }
                                        } catch (e: Exception) {
                                            // Leave the field untouched on failure - same as Cold Storage/iOS.
                                        } finally {
                                            isEstimatingMax = false
                                        }
                                    }
                                },
                                enabled = !isSending
                            ) {
                                Text(stringResource(R.string.max), color = KaspaTeal)
                            }
                        }
                    }
                },
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
            Text(
                "Available: %.8f KAS".format(java.util.Locale.US, balanceSompi / 100_000_000.0),
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !isSending) { showCoinControl = true },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.coin_control), color = LocalAppColors.current.textPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        manualUtxos?.let { "${it.size} ${if (it.size == 1) stringResource(R.string.utxo) else stringResource(R.string.utxos)}" }
                            ?: stringResource(R.string.automatic),
                        color = LocalAppColors.current.textSecondary
                    )
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(18.dp))
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ColdFeeTier.entries.forEachIndexed { index, tier ->
                    SegmentedButton(
                        selected = feeTier == tier,
                        onClick = { feeTier = tier; customExtraFeeSompi = null },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ColdFeeTier.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = LocalAppColors.current.surfaceVariant,
                            activeContentColor = LocalAppColors.current.textPrimary,
                            inactiveContainerColor = LocalAppColors.current.surface,
                            inactiveContentColor = LocalAppColors.current.textSecondary
                        ),
                        enabled = !isSending
                    ) {
                        Text(tier.label, fontSize = 12.sp)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !isSending) {
                    feeEditorInput = "%.8f".format(java.util.Locale.US, effectiveFeeSompi / 100_000_000.0)
                    showFeeEditor = true
                },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.network_fee), color = LocalAppColors.current.textPrimary)
                Text(
                    "%.8f KAS".format(java.util.Locale.US, effectiveFeeSompi / 100_000_000.0),
                    color = KaspaTeal,
                    fontWeight = FontWeight.Bold,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                )
            }
            Text(
                stringResource(R.string.if_the_network_is_busy_a),
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            if (isSending) {
                InscribeProgressRow(stringResource(R.string.sending_2))
            }

            Button(
                onClick = {
                    amountSompi?.let {
                        if (spendingIndex != null) {
                            viewModel.withdrawFromSpendingAddress(spendingIndex, effectiveAddress.trim(), it, feeRateOverrideSompi, manualUtxos)
                        } else {
                            viewModel.onSendClicked(effectiveAddress.trim(), it, feeRateOverrideSompi, manualUtxos)
                        }
                    }
                },
                enabled = !isSending && hasValidRecipient && (amountSompi ?: 0) > 0,
                colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = LocalAppColors.current.surfaceVariant),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(
                    stringResource(R.string.send),
                    color = if (!isSending && hasValidRecipient && (amountSompi ?: 0) > 0) Color.Black else Color.Gray,
                    fontWeight = FontWeight.Bold
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
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
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
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Default: %.8f KAS".format(java.util.Locale.US, defaultFeeSompi / 100_000_000.0),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val kas = feeEditorInput.toDoubleOrNull()
                    customExtraFeeSompi = if (kas != null && kas > 0) {
                        val desiredFeeSompi = Math.round(kas * 100_000_000.0)
                        (desiredFeeSompi - defaultFeeSompi).coerceAtLeast(0L)
                    } else {
                        null
                    }
                    showFeeEditor = false
                }) {
                    Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { customExtraFeeSompi = null; showFeeEditor = false }) {
                        Text(stringResource(R.string.use_default), color = LocalAppColors.current.textSecondary)
                    }
                    TextButton(onClick = { showFeeEditor = false }) {
                        Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                    }
                }
            }
        )
    }
}

/**
 * On-chain transaction history + UTXOs for one spending address, with direct Send/Receive —
 * reached by tapping an address row in [ManageAddressesScreen]/[ManageAddressesHiddenScreen].
 * Visually mirrors [ColdStorageTxHistoryScreen], but Send goes through [SpendingAddressSendFlow]
 * (direct signing with the address's own already-held private key via
 * [WalletViewModel.withdrawFromSpendingAddress]) rather than Cold Storage's external-QR-signer
 * flow, which only makes sense for a watch-only key with no private key on this device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingAddressTxHistoryScreen(
    index: Int,
    onBack: () -> Unit,
    viewModel: WalletViewModel,
    // Created here (not left to SpendingAddressSendFlow's own default) so its live-price fetch -
    // KaspaFiatAmountState.toggleMode silently no-ops until a price has arrived - gets a head
    // start of however long the user spends on this screen before ever tapping Send, instead of
    // starting from zero the instant the send flow itself first composes.
    portfolioViewModel: com.kachat.app.viewmodels.PortfolioViewModel = hiltViewModel()
) {
    val addresses by viewModel.manageAddresses.collectAsState()
    // Looked up from the already-loaded address list (shared with ManageAddressesScreen) rather
    // than threading label/balance through the route - keeps the nav arg down to just the index
    // withdrawFromSpendingAddress actually signs with.
    val entry = remember(addresses, index) { addresses.firstOrNull { it.index == index } }
    val address = entry?.address.orEmpty()

    val txHistory by viewModel.spendingAddressTxHistory.collectAsState()
    val isLoadingTxHistory by viewModel.loadingSpendingAddressTxHistory.collectAsState()
    val utxos by viewModel.spendingAddressUtxos.collectAsState()
    val isLoadingUtxos by viewModel.loadingSpendingAddressUtxos.collectAsState()
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    val biometricSpendingKeyEnabled by viewModel.biometricSpendingKeyEnabled.collectAsState()
    val uriHandler = LocalUriHandler.current
    // The tapped transaction, while its action chooser is up.
    var transactionActionTarget by remember { mutableStateOf<ColdStorageAddressDiscovery.AddressTransaction?>(null) }
    // The transaction being filed into a portfolio, if any - see [AddToPortfolioSheet].
    var portfolioCandidate by remember { mutableStateOf<ColdStorageAddressDiscovery.AddressTransaction?>(null) }
    var addedPortfolioName by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    var selectedTab by remember { mutableStateOf(0) }
    var showQr by remember { mutableStateOf(false) }
    var showWithdraw by remember { mutableStateOf(false) }
    var showCompoundFlow by remember { mutableStateOf(false) }
    var showPrivateKey by remember { mutableStateOf(false) }
    var utxoLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var labelingUtxoKey by remember { mutableStateOf<String?>(null) }
    var labelInput by remember { mutableStateOf("") }
    var sendingDomain by remember { mutableStateOf<com.kachat.app.services.KnsAsset?>(null) }
    val knsDomains by viewModel.addressKnsDomains.collectAsState()
    val isLoadingKnsDomains by viewModel.addressKnsDomainsLoading.collectAsState()

    LaunchedEffect(address) {
        if (address.isNotEmpty()) {
            viewModel.loadSpendingAddressTxHistory(address)
            viewModel.loadSpendingAddressUtxos(address)
            viewModel.loadAddressKnsDomains(address)
            utxoLabels = viewModel.getSpendingUtxoLabels(address)
        }
    }

    // Domain-transfer send flow parameterized to sign/fund from THIS spending address — same
    // full-screen-swap idiom as the Withdraw flow below. Mirrors iOS's
    // KNSDomainSendView(domain:spendingAddressIndex:) from the spending detail's KNS tab.
    sendingDomain?.let { domain ->
        if (entry != null) {
            KnsDomainSendScreen(
                domain = domain,
                viewModel = viewModel,
                fromSpendingAddressIndex = entry.index,
                sourceAddress = entry.address,
                onDone = {
                    sendingDomain = null
                    viewModel.loadAddressKnsDomains(address)
                },
                onBack = { sendingDomain = null }
            )
            return
        }
    }

    // In-place full-screen swap - not a nav route, not an overlay dialog - mirroring
    // ColdStorageTxHistoryScreen's own `if (showSendFlow) { ...; return }` idiom exactly, so Send
    // takes over the whole screen the same way on both the spending and Cold Storage paths.
    if (showWithdraw && entry != null) {
        SpendingAddressSendFlow(
            fromAddress = entry.address,
            balanceSompi = entry.balanceSompi,
            title = "Send from Address #${entry.index}",
            spendingIndex = entry.index,
            viewModel = viewModel,
            portfolioViewModel = portfolioViewModel,
            onDone = {
                showWithdraw = false
                viewModel.loadSpendingAddressTxHistory(address)
                viewModel.loadSpendingAddressUtxos(address)
            }
        )
        return
    }

    if (showCompoundFlow && entry != null) {
        SpendingAddressSendFlow(
            fromAddress = entry.address,
            balanceSompi = entry.balanceSompi,
            title = "Send from Address #${entry.index}",
            spendingIndex = entry.index,
            viewModel = viewModel,
            portfolioViewModel = portfolioViewModel,
            isCompoundMode = true,
            onDone = {
                showCompoundFlow = false
                viewModel.loadSpendingAddressTxHistory(address)
                viewModel.loadSpendingAddressUtxos(address)
            }
        )
        return
    }

    val displayName = entry?.label?.takeIf { it.isNotBlank() } ?: "Address #$index"

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(displayName, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (biometricSpendingKeyEnabled) {
                            context.authenticateWithDeviceCredential(
                                title = "Unlock to View Private Key",
                                onSuccess = { showPrivateKey = true }
                            )
                        } else {
                            showPrivateKey = true
                        }
                    }) {
                        Icon(Icons.Default.IosShare, stringResource(R.string.export), tint = KaspaTeal)
                    }
                    IconButton(onClick = { uriHandler.openUri(kaspaExplorer.addressUrl(address)) }) {
                        Icon(Icons.Default.Public, stringResource(R.string.view_in_explorer), tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { showQr = true },
                    enabled = address.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.QrCode, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.receive), fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { showWithdraw = true },
                    enabled = (entry?.balanceSompi ?: 0L) > 0L,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.send), fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.balance),
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    "%.8f KAS".format(java.util.Locale.US, (entry?.balanceSompi ?: 0L) / 100_000_000.0),
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = LocalAppColors.current.background,
                contentColor = KaspaTeal
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("History") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("${stringResource(R.string.utxos)} (${utxos.size})") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("KNS Domains (${knsDomains.size})") }
                )
            }
            when (selectedTab) {
                0 -> when {
                    isLoadingTxHistory && txHistory.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = KaspaTeal)
                        }
                    }
                    txHistory.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_transactions_yet), color = LocalAppColors.current.textSecondary, textAlign = TextAlign.Center)
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(txHistory, key = { it.txId }) { tx ->
                                SpendingAddressTxHistoryRow(
                                    tx = tx,
                                    // Tapping a transaction asks what to do with it. It used to
                                    // open the explorer outright, which left "Add to Portfolio"
                                    // on a button most people never looked for.
                                    onClick = { transactionActionTarget = tx }
                                )
                            }
                        }
                    }
                }
                2 -> when {
                    isLoadingKnsDomains && knsDomains.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = KaspaTeal)
                        }
                    }
                    knsDomains.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No KNS domains on this address.", color = LocalAppColors.current.textSecondary, textAlign = TextAlign.Center)
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(knsDomains, key = { it.assetId ?: it.asset ?: it.hashCode().toString() }) { domain ->
                                // Tapping a domain card opens the transfer flow signed/funded
                                // from THIS spending address.
                                KnsDomainCard(
                                    domain = domain,
                                    modifier = Modifier.clickable {
                                        viewModel.resetTransferDomainState()
                                        sendingDomain = domain
                                    }
                                )
                            }
                        }
                    }
                }
                else -> when {
                    isLoadingUtxos && utxos.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = KaspaTeal)
                        }
                    }
                    utxos.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_utxos), color = LocalAppColors.current.textSecondary, textAlign = TextAlign.Center)
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (utxos.size > 1) {
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(LocalAppColors.current.surface)
                                            .clickable { showCompoundFlow = true }
                                            .padding(16.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CallMerge, null, tint = KaspaTeal)
                                            Spacer(Modifier.width(12.dp))
                                            Text(
                                                stringResource(R.string.compound_utxos),
                                                color = LocalAppColors.current.textPrimary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            stringResource(R.string.compound_utxos_description),
                                            color = LocalAppColors.current.textSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                            items(utxos, key = { "${it.transactionId}:${it.index}" }) { utxo ->
                                val key = "${utxo.transactionId}:${utxo.index}"
                                SpendingAddressUtxoRow(
                                    utxo = utxo,
                                    label = utxoLabels[key],
                                    onRenameClick = {
                                        labelingUtxoKey = key
                                        labelInput = utxoLabels[key] ?: ""
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showQr && address.isNotEmpty()) {
        QrCodeOverlay(value = address, onDismiss = { showQr = false })
    }

    if (showPrivateKey) {
        SpendingAddressPrivateKeyOverlay(privateKeyHex = viewModel.getSpendingPrivateKeyHex(index), onDismiss = { showPrivateKey = false })
    }

    labelingUtxoKey?.let { key ->
        AlertDialog(
            onDismissRequest = { labelingUtxoKey = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.rename_utxo), color = LocalAppColors.current.textPrimary) },
            text = {
                OutlinedTextField(
                    value = labelInput,
                    onValueChange = { labelInput = it },
                    label = { Text(stringResource(R.string.name)) },
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
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setSpendingUtxoLabel(address, key, labelInput)
                    utxoLabels = viewModel.getSpendingUtxoLabels(address)
                    labelingUtxoKey = null
                }) {
                    Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { labelingUtxoKey = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    transactionActionTarget?.let { tapped ->
        CenteredOptionsMenu(onDismissRequest = { transactionActionTarget = null }, centerHorizontally = true) {
            PopupMenuRow(Icons.Default.OpenInNew, "Open in Explorer") {
                transactionActionTarget = null
                uriHandler.openUri(kaspaExplorer.txUrl(tapped.txId))
            }
            PopupMenuRow(Icons.Default.PieChart, "Add to Portfolio") {
                transactionActionTarget = null
                portfolioCandidate = tapped
            }
        }
    }

    portfolioCandidate?.let { candidate ->
        AddToPortfolioSheet(
            tx = candidate,
            address = address,
            viewModel = portfolioViewModel,
            onDismiss = { portfolioCandidate = null },
            onAdded = { addedPortfolioName = it },
        )
    }
    addedPortfolioName?.let { name ->
        PortfolioAddedSnackbar(name) { addedPortfolioName = null }
    }
}

/**
 * On-chain transaction history + UTXOs for the wallet's identity/chatting address, with direct
 * Send/Receive/Export - reached from the Profile screen's "Chatting Address" section's "Manage
 * Address" row. Field-for-field the same screen as [SpendingAddressTxHistoryScreen] (balance
 * header, Transaction History/UTXOs tabs, Compound UTXOs, Export, Explorer, Receive/Send), just
 * for the single fixed identity address instead of one spending-chain index - reuses the same
 * address-keyed StateFlows/functions on [WalletViewModel] (spendingAddressTxHistory/Utxos,
 * getSpendingUtxoLabels, etc. - all keyed by address string, nothing spending-chain-specific
 * about them) rather than duplicating a second copy of this state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityAddressDetailScreen(onBack: () -> Unit, viewModel: WalletViewModel, portfolioViewModel: com.kachat.app.viewmodels.PortfolioViewModel = hiltViewModel()) {
    val address by viewModel.address.collectAsState()
    val balanceSompi by viewModel.balanceSompi.collectAsState()

    val txHistory by viewModel.spendingAddressTxHistory.collectAsState()
    val isLoadingTxHistory by viewModel.loadingSpendingAddressTxHistory.collectAsState()
    val utxos by viewModel.spendingAddressUtxos.collectAsState()
    val isLoadingUtxos by viewModel.loadingSpendingAddressUtxos.collectAsState()
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    // The identity key is at least as sensitive as the seed phrase itself (it IS the wallet's
    // main spending key) - gated behind the same biometric flag Settings > View Seed Phrase
    // uses, not the lower-stakes biometricSpendingKeyEnabled a derived spending address uses.
    val biometricSeedPhraseEnabled by viewModel.biometricSeedPhraseEnabled.collectAsState()
    val uriHandler = LocalUriHandler.current
    // The tapped transaction, while its action chooser is up.
    var transactionActionTarget by remember { mutableStateOf<ColdStorageAddressDiscovery.AddressTransaction?>(null) }
    // The transaction being filed into a portfolio, if any - see [AddToPortfolioSheet].
    var portfolioCandidate by remember { mutableStateOf<ColdStorageAddressDiscovery.AddressTransaction?>(null) }
    var addedPortfolioName by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    var selectedTab by remember { mutableStateOf(0) }
    var showQr by remember { mutableStateOf(false) }
    var showWithdraw by remember { mutableStateOf(false) }
    var showCompoundFlow by remember { mutableStateOf(false) }
    var showPrivateKey by remember { mutableStateOf(false) }
    var utxoLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var labelingUtxoKey by remember { mutableStateOf<String?>(null) }
    var labelInput by remember { mutableStateOf("") }

    LaunchedEffect(address) {
        val addr = address
        if (!addr.isNullOrEmpty()) {
            viewModel.loadSpendingAddressTxHistory(addr)
            viewModel.loadSpendingAddressUtxos(addr)
            utxoLabels = viewModel.getSpendingUtxoLabels(addr)
        }
    }

    if (showWithdraw && address != null) {
        SpendingAddressSendFlow(
            fromAddress = address!!,
            balanceSompi = balanceSompi,
            title = "Send Kaspa",
            viewModel = viewModel,
            portfolioViewModel = portfolioViewModel,
            onDone = {
                showWithdraw = false
                viewModel.loadSpendingAddressTxHistory(address!!)
                viewModel.loadSpendingAddressUtxos(address!!)
            }
        )
        return
    }

    if (showCompoundFlow && address != null) {
        SpendingAddressSendFlow(
            fromAddress = address!!,
            balanceSompi = balanceSompi,
            title = "Send Kaspa",
            viewModel = viewModel,
            portfolioViewModel = portfolioViewModel,
            isCompoundMode = true,
            onDone = {
                showCompoundFlow = false
                viewModel.loadSpendingAddressTxHistory(address!!)
                viewModel.loadSpendingAddressUtxos(address!!)
            }
        )
        return
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Chatting Address", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (biometricSeedPhraseEnabled) {
                            context.authenticateWithDeviceCredential(
                                title = "Unlock to View Private Key",
                                onSuccess = { showPrivateKey = true }
                            )
                        } else {
                            showPrivateKey = true
                        }
                    }) {
                        Icon(Icons.Default.IosShare, stringResource(R.string.export), tint = KaspaTeal)
                    }
                    IconButton(onClick = { address?.let { uriHandler.openUri(kaspaExplorer.addressUrl(it)) } }) {
                        Icon(Icons.Default.Public, stringResource(R.string.view_in_explorer), tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { showQr = true },
                    enabled = !address.isNullOrEmpty(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.QrCode, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.receive), fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { showWithdraw = true },
                    enabled = balanceSompi > 0L,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.send), fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.balance),
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    "%.8f KAS".format(java.util.Locale.US, balanceSompi / 100_000_000.0),
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = LocalAppColors.current.background,
                contentColor = KaspaTeal
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.transaction_history)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("${stringResource(R.string.utxos)} (${utxos.size})") }
                )
            }
            when (selectedTab) {
                0 -> when {
                    isLoadingTxHistory && txHistory.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = KaspaTeal)
                        }
                    }
                    txHistory.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_transactions_yet), color = LocalAppColors.current.textSecondary, textAlign = TextAlign.Center)
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(txHistory, key = { it.txId }) { tx ->
                                SpendingAddressTxHistoryRow(
                                    tx = tx,
                                    // Tapping a transaction asks what to do with it. It used to
                                    // open the explorer outright, which left "Add to Portfolio"
                                    // on a button most people never looked for.
                                    onClick = { transactionActionTarget = tx }
                                )
                            }
                        }
                    }
                }
                else -> when {
                    isLoadingUtxos && utxos.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = KaspaTeal)
                        }
                    }
                    utxos.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_utxos), color = LocalAppColors.current.textSecondary, textAlign = TextAlign.Center)
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (utxos.size > 1) {
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(LocalAppColors.current.surface)
                                            .clickable { showCompoundFlow = true }
                                            .padding(16.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CallMerge, null, tint = KaspaTeal)
                                            Spacer(Modifier.width(12.dp))
                                            Text(
                                                stringResource(R.string.compound_utxos),
                                                color = LocalAppColors.current.textPrimary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            stringResource(R.string.compound_utxos_description),
                                            color = LocalAppColors.current.textSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                            items(utxos, key = { "${it.transactionId}:${it.index}" }) { utxo ->
                                val key = "${utxo.transactionId}:${utxo.index}"
                                SpendingAddressUtxoRow(
                                    utxo = utxo,
                                    label = utxoLabels[key],
                                    onRenameClick = {
                                        labelingUtxoKey = key
                                        labelInput = utxoLabels[key] ?: ""
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showQr && !address.isNullOrEmpty()) {
        QrCodeOverlay(
            value = address!!,
            onDismiss = { showQr = false },
            message = "Just send 5-10 KAS at a time, that's plenty to cover chat fees for a while (about 500 messages per KAS)",
            borderColor = KaspaTeal,
            borderWidth = 4.dp
        )
    }

    if (showPrivateKey) {
        SpendingAddressPrivateKeyOverlay(privateKeyHex = viewModel.getPrivateKeyHex(), onDismiss = { showPrivateKey = false })
    }

    labelingUtxoKey?.let { key ->
        AlertDialog(
            onDismissRequest = { labelingUtxoKey = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text(stringResource(R.string.rename_utxo), color = LocalAppColors.current.textPrimary) },
            text = {
                OutlinedTextField(
                    value = labelInput,
                    onValueChange = { labelInput = it },
                    label = { Text(stringResource(R.string.name)) },
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
            },
            confirmButton = {
                TextButton(onClick = {
                    val addr = address ?: return@TextButton
                    viewModel.setSpendingUtxoLabel(addr, key, labelInput)
                    utxoLabels = viewModel.getSpendingUtxoLabels(addr)
                    labelingUtxoKey = null
                }) {
                    Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { labelingUtxoKey = null }) {
                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    transactionActionTarget?.let { tapped ->
        CenteredOptionsMenu(onDismissRequest = { transactionActionTarget = null }, centerHorizontally = true) {
            PopupMenuRow(Icons.Default.OpenInNew, "Open in Explorer") {
                transactionActionTarget = null
                uriHandler.openUri(kaspaExplorer.txUrl(tapped.txId))
            }
            PopupMenuRow(Icons.Default.PieChart, "Add to Portfolio") {
                transactionActionTarget = null
                portfolioCandidate = tapped
            }
        }
    }

    portfolioCandidate?.let { candidate ->
        AddToPortfolioSheet(
            tx = candidate,
            address = address.orEmpty(),
            viewModel = portfolioViewModel,
            onDismiss = { portfolioCandidate = null },
            onAdded = { addedPortfolioName = it },
        )
    }
    addedPortfolioName?.let { name ->
        PortfolioAddedSnackbar(name) { addedPortfolioName = null }
    }
}

/**
 * Reveals a single spending address's own derived private key - not the wallet's seed phrase -
 * so a specific address's spending capability can be exported/backed up without exposing the
 * rest of the wallet. Mirrors SeedPhraseScreen's reveal flow at the same sensitivity level
 * (FLAG_SECURE screenshot/recording block, tap-to-reveal with a 7s auto-hide timer) rather than
 * inventing a lighter-weight pattern for equally sensitive key material. The caller already
 * gates presentation behind biometrics, same as View Seed Phrase's own entry point.
 */
@Composable
private fun SpendingAddressPrivateKeyOverlay(privateKeyHex: String, onDismiss: () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(revealed) {
        if (revealed) {
            delay(7000)
            revealed = false
        }
    }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalAppColors.current.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.done), color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF2C1E1E))
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Warning, null, tint = Color(0xFFF39C12), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        stringResource(R.string.security_warning),
                        color = Color(0xFFF39C12),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Anyone with this address's private key can spend its funds. Never share it with anyone.",
                        color = Color(0xFF948B8B),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            if (!revealed) {
                Column(
                    modifier = Modifier
                        .clickable { revealed = true }
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.VisibilityOff,
                        null,
                        tint = LocalAppColors.current.textSecondary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Tap to reveal private key",
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                Text(
                    privateKeyHex,
                    color = LocalAppColors.current.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(LocalAppColors.current.surface)
                        .padding(16.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            if (revealed) {
                TextButton(onClick = {
                    copyPrivateKeyWithAutoWipe(context, privateKeyHex)
                    Toast.makeText(context, "Private key copied. Clipboard clears in 30s.", Toast.LENGTH_SHORT).show()
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContentCopy, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.copy_private_key_hex), color = KaspaTeal)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SpendingAddressTxHistoryRow(tx: ColdStorageAddressDiscovery.AddressTransaction, onClick: () -> Unit) {
    val kas = tx.amountSompi / 100_000_000.0
    val dateStr = tx.blockTimeMillis?.let {
        java.text.SimpleDateFormat("MMM d, yyyy, h:mm a", java.util.Locale.US).format(java.util.Date(it))
    } ?: "Pending"
    val directionColor = if (tx.sent) Color(0xFFFF3B30) else Color(0xFF4CD964)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(directionColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (tx.sent) Icons.AutoMirrored.Filled.Send else Icons.AutoMirrored.Filled.CallReceived,
                null,
                tint = directionColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(if (tx.sent) "Sent" else "Received", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
            Text(dateStr, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text(
                tx.txId,
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${if (tx.sent) "-" else "+"}%.8f KAS".format(java.util.Locale.US, kas),
            color = directionColor,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SpendingAddressUtxoRow(utxo: ColdStorageAddressDiscovery.AddressUtxo, label: String? = null, onRenameClick: () -> Unit = {}) {
    val kas = utxo.amountSompi / 100_000_000.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (!label.isNullOrBlank()) {
                Text(
                    label,
                    color = KaspaTeal,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "%.8f KAS".format(java.util.Locale.US, kas),
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${utxo.transactionId}:${utxo.index}",
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (utxo.isCoinbase) {
            Text(
                stringResource(R.string.coinbase),
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        IconButton(onClick = onRenameClick, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Edit, stringResource(R.string.rename), tint = KaspaTeal, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * One spending address row in [ManageAddressesScreen] — shared by the main and "Hidden" sections,
 * differing only in whether [WalletService.SpendingAddressEntry.hidden] shows a hide or an unhide
 * swipe action. Hiding is a swipe-left action (matching Chats' swipe-to-delete and Cold Storage's
 * address rows) rather than a permanent icon button, since it's reached for less often than the
 * actions in the overflow menu. Unhiding is always available, but an address can't be hidden while
 * it still holds a balance or is the primary ("Pay in Kaspa") spending address — see
 * [WalletViewModel.setManageAddressHidden], which enforces the same rule as a backstop.
 *
 * Everything besides hide/unhide (copy, QR, set primary, rename) lives behind a single overflow
 * button's [CenteredOptionsMenu] rather than a row of icons, so the address itself has room to sit
 * on its own line instead of being squeezed by four icon buttons. Send/receive for a specific
 * address live in [SpendingAddressTxHistoryScreen] instead (reached via `onClick`).
 */
/** "Contains domain" capsule tag shown on spending/cold address rows that own at least one KNS
 *  domain — shared component ported from iOS's ContainsDomainTag. */
@Composable
fun ContainsDomainTag() {
    Text(
        text = "Contains domain",
        color = KaspaTeal,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(KaspaTeal.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun ManageAddressRow(
    entry: com.kachat.app.services.WalletService.SpendingAddressEntry,
    onClick: () -> Unit,
    onCopyClick: () -> Unit,
    onQrClick: () -> Unit,
    onActivateClick: () -> Unit,
    onRenameClick: () -> Unit,
    /** Row-menu "Hide Address" (iOS parity) — same flag the Address Visibility checklist edits.
     *  Null hides the menu entry (e.g. the picker variant of this row). */
    onHideClick: (() -> Unit)? = null,
    /** "Contains domain" tag — this address owns at least one KNS domain (batched lookup). */
    showsDomainTag: Boolean = false,
    /** "Chat privacy address" tag — this address is reserved and currently offered to a contact
     *  for private payments (fresh-address payment pool). Such rows never offer Hide; the
     *  authoritative refusal lives in the pool-store-backed guards below the UI. */
    showsPrivacyTag: Boolean = false
) {
    val kas = entry.balanceSompi / 100_000_000.0
    var showMenu by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }

    // Swipe-to-hide was retired in favor of the bulk Address Visibility checklist (iOS parity).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.label?.takeIf { it.isNotBlank() } ?: "Address #${entry.index}",
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                if (entry.isCurrent) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Star, "Primary address", tint = KaspaTeal, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${entry.address.take(14)}...${entry.address.takeLast(6)}",
                color = LocalAppColors.current.textPrimary,
                fontSize = 14.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "%.8f KAS".format(java.util.Locale.US, kas),
                color = LocalAppColors.current.textPrimary,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Three honest states, never a guess: "Used" is monotonic (persisted used-memory
                // plus live probes, so a confirmed-used address can never flip back), "Unused"
                // only when THIS load's live check actually confirmed it, and a neutral
                // "Unverified" when the probe failed or the row was painted from the snapshot —
                // a failed check must not masquerade as a fresh address.
                val (usedTagText, usedTagColor) = when {
                    entry.everUsed -> "Used" to Color(0xFFF39C12)
                    entry.liveChecked -> "Unused" to Color(0xFF4CD964)
                    else -> "Unverified" to LocalAppColors.current.textSecondary
                }
                Text(
                    text = usedTagText,
                    color = usedTagColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                if (showsPrivacyTag) {
                    // Same plain-badge styling as the Used/Unused/Unverified states above.
                    Text(
                        text = "Chat privacy address",
                        color = KaspaTeal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (showsDomainTag) {
                    ContainsDomainTag()
                }
            }
        }
        IconButton(
            onClick = { showMenu = true },
            modifier = Modifier
                .size(44.dp)
                .onGloballyPositioned { coords ->
                    menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
                }
        ) {
            Icon(Icons.Default.MoreVert, "Address actions", tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(28.dp))
        }
    }

    if (showMenu) {
        CenteredOptionsMenu(onDismissRequest = { showMenu = false }, anchor = menuAnchor) {
            PopupMenuRow(Icons.Default.Edit, stringResource(R.string.rename_address)) {
                showMenu = false
                onRenameClick()
            }
            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
            PopupMenuRow(Icons.Default.ContentCopy, stringResource(R.string.copy_address)) {
                showMenu = false
                onCopyClick()
            }
            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
            PopupMenuRow(Icons.Default.QrCode, stringResource(R.string.show_qr_code)) {
                showMenu = false
                onQrClick()
            }
            if (!entry.isCurrent) {
                HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                PopupMenuRow(Icons.Default.Star, stringResource(R.string.set_as_primary_address)) {
                    showMenu = false
                    onActivateClick()
                }
            }
            // Hide straight from the row (iOS parity) — never offered for the primary address or
            // for a chat-privacy reservation (offered to a contact, locked visible); the funded
            // guard lives in the caller so it can toast the reason.
            if (onHideClick != null && !entry.isCurrent && !showsPrivacyTag) {
                HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                PopupMenuRow(Icons.Default.VisibilityOff, "Hide Address") {
                    showMenu = false
                    onHideClick()
                }
            }
        }
    }
}

/**
 * One read-only row on [ManageAddressesScreen]'s Chat Privacy tab: an address reserved and
 * actively offered to a contact by the fresh-address payment pool (Chats Payment Privacy).
 * Same card styling as [ManageAddressRow], but the overflow menu offers ONLY Copy Address and
 * Show QR Code - no rename, hide, set-primary, or history navigation; the offer lifecycle
 * (revoke/supersede/fund) manages these rows, not the user. Balance shows only once funds
 * arrive, so a funded-but-still-active offer displays what came in.
 */
@Composable
private fun ChatPrivacyAddressRow(
    entry: com.kachat.app.services.WalletService.SpendingAddressEntry,
    onCopyClick: () -> Unit,
    onQrClick: () -> Unit
) {
    val kas = entry.balanceSompi / 100_000_000.0
    var showMenu by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Address #${entry.index}",
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                entry.label?.takeIf { it.isNotBlank() }?.let { label ->
                    Text(
                        text = label,
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${entry.address.take(14)}...${entry.address.takeLast(6)}",
                color = LocalAppColors.current.textPrimary,
                fontSize = 14.sp,
                maxLines = 1
            )
            if (entry.balanceSompi > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "%.8f KAS".format(java.util.Locale.US, kas),
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        IconButton(
            onClick = { showMenu = true },
            modifier = Modifier
                .size(44.dp)
                .onGloballyPositioned { coords ->
                    menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
                }
        ) {
            Icon(Icons.Default.MoreVert, "Address actions", tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(28.dp))
        }
    }

    if (showMenu) {
        CenteredOptionsMenu(onDismissRequest = { showMenu = false }, anchor = menuAnchor) {
            PopupMenuRow(Icons.Default.ContentCopy, stringResource(R.string.copy_address)) {
                showMenu = false
                onCopyClick()
            }
            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
            PopupMenuRow(Icons.Default.QrCode, stringResource(R.string.show_qr_code)) {
                showMenu = false
                onQrClick()
            }
        }
    }
}

/** Default tapback-style set, not a full emoji keyboard - user-customizable via Settings > Chats
 *  > Quick Reactions (see [com.kachat.app.repository.AppSettingsRepository.quickReactionEmojis]),
 *  this is just the fallback [QuickReactionBar] uses until then. */
val QUICK_REACTION_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/**
 * The popup shown when a message bubble is double-tapped: a row of common emoji to react with,
 * plus a reply shortcut in the bottom-right corner - replaces the old behavior where double-tap
 * jumped straight into reply mode, giving an explicit choice between reacting and replying
 * instead. Reuses [CenteredOptionsMenu]'s anchor-positioned card shell with custom content rather
 * than [PopupMenuRow]s.
 */
@Composable
fun QuickReactionBar(
    onDismissRequest: () -> Unit,
    anchor: Offset,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    emojis: List<String> = QUICK_REACTION_EMOJIS
) {
    CenteredOptionsMenu(onDismissRequest = onDismissRequest, anchor = anchor) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                emojis.forEach { emoji ->
                    Text(
                        emoji,
                        fontSize = 26.sp,
                        modifier = Modifier.clickable {
                            onReact(emoji)
                            onDismissRequest()
                        }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(
                    onClick = {
                        onReply()
                        onDismissRequest()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = stringResource(R.string.reply), tint = KaspaTeal)
                }
            }
        }
    }
}

/** Small rounded pill overlapping a bubble's bottom outer corner, showing the distinct emoji reacted with (and a count when more than one person used the same one). */
@Composable
fun ReactionPill(reactions: List<ReactionEntity>, modifier: Modifier = Modifier, myAddress: String? = null) {
    val counts = remember(reactions) { reactions.groupingBy { it.emoji }.eachCount() }
    // Status of the local user's own reaction: "pending" → no icon, "sent" → green checkmark once it
    // goes through, "failed" → red error icon + red outline (the tappable Retry is under the message).
    // Only the local user's reaction ever pends/fails; everyone else's is always "sent".
    val myReaction = remember(reactions, myAddress) { reactions.firstOrNull { it.reactorAddress == myAddress } }
    // The green "sent" checkmark is a recent confirmation, not a permanent badge - drop it once the
    // reaction is older than 10 minutes. Computed each recomposition (not remembered) so it clears
    // on the next refresh past the window. Pending/failed are always shown.
    val myStatus = myReaction?.let { r ->
        when (r.deliveryStatus) {
            "failed" -> "failed"
            "sent" -> if (System.currentTimeMillis() - r.blockTimestamp < 600_000L) "sent" else null
            else -> null
        }
    }
    Surface(
        color = LocalAppColors.current.surfaceVariant,
        shape = RoundedCornerShape(50),
        shadowElevation = 2.dp,
        border = if (myStatus == "failed") BorderStroke(1.dp, Color(0xFFFF3B30).copy(alpha = 0.6f)) else null,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (myStatus) {
                "failed" -> Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = stringResource(R.string.failed_to_send),
                    tint = Color(0xFFFF3B30),
                    modifier = Modifier.size(11.dp)
                )
                "sent" -> Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CD964),
                    modifier = Modifier.size(11.dp)
                )
            }
            counts.forEach { (emoji, count) ->
                Text(emoji, fontSize = 13.sp)
                if (count > 1) {
                    Text(count.toString(), fontSize = 11.sp, color = LocalAppColors.current.textSecondary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * A small options card, positioned via a real [Dialog] rather than an anchored
 * `DropdownMenu`/`Popup` — see [ManageAddressesScreen]'s Address Actions menu for why: a floating
 * anchored Popup can dismiss itself on the very touch that opened it, since it shares the same
 * composition/touch pass as the button that triggered it. A Dialog is backed by its own Android
 * window, added only once that opening gesture has fully finished, so it doesn't race. It's also
 * how [ChatsScreen]/broadcast rooms' message and avatar context menus avoid a second, unrelated
 * bug: Material3's stock `DropdownMenu` clips its content to its own fixed small internal shape
 * token no matter what shape you pass it, which is what made those menus render with visibly
 * square corners.
 *
 * [anchor], if given, is the window-relative pixel position (`LayoutCoordinates.positionInWindow()`)
 * to hug a corner of instead of centering — e.g. just below a tapped avatar or message, or just
 * above the composer's "+" button. Which corner is picked (and therefore which direction the card
 * grows in) flips per axis based on which half of the screen [anchor] falls in, so it never grows
 * off-screen; since the card's own size isn't known until it's laid out, that placement happens in
 * a second pass, via [Modifier.onSizeChanged], once the real size is measured — the on-screen jump
 * from the first frame's guess is a single frame and not noticeable. With no anchor, the card is
 * centered at the bottom of the screen instead, for a FAB-triggered menu like Address Actions
 * where there's no single on-screen element to sit next to.
 *
 * [content] should be one or more [PopupMenuRow]s, optionally separated by [HorizontalDivider]s.
 */
@Composable
fun CenteredOptionsMenu(
    onDismissRequest: () -> Unit,
    anchor: Offset? = null,
    centerHorizontally: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        val density = LocalDensity.current
        val cardWidthDp = 280.dp
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            // A Dialog's window normally takes input focus when it appears, which dismisses the
            // soft keyboard if the composer's TextField had it up — these flags are the standard
            // "popup that doesn't steal focus" combo, so the keyboard (and whatever layout shift
            // it caused, which [anchor] was captured after) stays exactly as it was.
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            // FLAG_NOT_FOCUSABLE (needed above so the keyboard doesn't close) also breaks the
            // Dialog's own built-in dismissOnClickOutside — it stops delivering ACTION_OUTSIDE once
            // the window can't take focus. Every branch below rolls its own tap-anywhere-outside
            // dismiss instead (a full-size scrim behind the card), so every branch's window needs
            // to actually span the full screen for there to be anything for that scrim to cover.
            window.setGravity(Gravity.TOP or Gravity.START)
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        if (anchor != null) {
            val metrics = view.context.resources.displayMetrics
            val marginPx = with(density) { 8.dp.toPx() }
            val horizontalEnd = anchor.x > metrics.widthPixels / 2f
            val verticalBottom = anchor.y > metrics.heightPixels / 2f
            var cardSize by remember { mutableStateOf(IntSize.Zero) }
            val offsetX = if (centerHorizontally) {
                ((metrics.widthPixels - cardSize.width) / 2f).coerceAtLeast(marginPx)
            } else {
                (if (horizontalEnd) anchor.x - cardSize.width else anchor.x).coerceAtLeast(marginPx)
            }
            val offsetY = (if (verticalBottom) anchor.y - cardSize.height - marginPx else anchor.y + marginPx).coerceAtLeast(marginPx)
            // This window now spans the full screen (so the card can be offset to hug whichever
            // corner it needs to), which leaves no genuine "outside the window" area for the
            // Dialog's own dismissOnClickOutside to detect — same underlying reason as the
            // FAB-triggered branch needing none of this. A manual full-size scrim stands in for
            // it instead; each [PopupMenuRow]'s own clickable consumes its tap before it can reach
            // this one, so only taps on the blank area around the card actually dismiss it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onDismissRequest
                    )
            ) {
                Surface(
                    color = LocalAppColors.current.surfaceVariant,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .widthIn(min = 160.dp, max = cardWidthDp)
                        .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                        .onSizeChanged { cardSize = it }
                ) {
                    Column(modifier = Modifier.width(IntrinsicSize.Max), content = content)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onDismissRequest
                    )
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    color = LocalAppColors.current.surfaceVariant,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.widthIn(min = 160.dp, max = cardWidthDp)
                ) {
                    Column(modifier = Modifier.width(IntrinsicSize.Max), content = content)
                }
            }
        }
    }
}

/** One row of a [CenteredOptionsMenu] — icon fixed at a consistent left offset so it lines up the same across every row regardless of label length. */
@Composable
fun PopupMenuRow(
    icon: ImageVector,
    label: String,
    labelColor: Color = LocalAppColors.current.textPrimary,
    iconTint: Color = KaspaTeal,
    onClick: () -> Unit
) {
    PopupMenuRowContent(label, labelColor, onClick) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
    }
}

/** [PopupMenuRow] overload for a custom drawable (e.g. the Kaspa "K" mark) instead of a Material [ImageVector]. */
@Composable
fun PopupMenuRow(
    icon: Painter,
    label: String,
    labelColor: Color = LocalAppColors.current.textPrimary,
    iconTint: Color = KaspaTeal,
    onClick: () -> Unit
) {
    PopupMenuRowContent(label, labelColor, onClick) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PopupMenuRowContent(label: String, labelColor: Color, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Text(label, color = labelColor, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InscribeProgressRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = KaspaTeal, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = LocalAppColors.current.textPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditKnsProfileScreen(
    viewModel: WalletViewModel,
    onBack: () -> Unit,
    onNavigateToDomains: () -> Unit = {},
    onNavigateToSetupGuide: () -> Unit = {}
) {
    val knsProfile by viewModel.knsProfile.collectAsState()
    val activeProfileDomainName by viewModel.activeProfileDomainName.collectAsState()
    val ownedDomainAssets by viewModel.ownedDomainAssets.collectAsState()
    val pendingAvatarUri by viewModel.pendingAvatarUri.collectAsState()
    val pendingBannerUri by viewModel.pendingBannerUri.collectAsState()
    val avatarCleared by viewModel.avatarCleared.collectAsState()
    val bannerCleared by viewModel.bannerCleared.collectAsState()
    val editState by viewModel.editProfileState.collectAsState()
    val showSetupGuides by viewModel.showSetupGuides.collectAsState()

    var bio by remember { mutableStateOf("") }
    var x by remember { mutableStateOf("") }
    var website by remember { mutableStateOf("") }
    var telegram by remember { mutableStateOf("") }
    var discord by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var github by remember { mutableStateOf("") }
    var redirect by remember { mutableStateOf("") }
    var seeded by remember { mutableStateOf(false) }
    // The domain the fields were last read from. A KNS profile belongs to a DOMAIN, so promoting a
    // different one to primary swaps which avatar, banner and details are in effect - and these
    // fields used to seed exactly once, so the screen kept showing the old domain's values until
    // it was closed and reopened. Keyed on the domain rather than on the profile object so an
    // ordinary refresh of the SAME profile never overwrites what the user is part-way through
    // typing.
    var seededDomain by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(knsProfile, activeProfileDomainName) {
        if (!seeded || seededDomain != activeProfileDomainName) {
            bio = knsProfile?.bio ?: ""
            x = knsProfile?.x ?: ""
            website = knsProfile?.website ?: ""
            telegram = knsProfile?.telegram ?: ""
            discord = knsProfile?.discord ?: ""
            email = knsProfile?.contactEmail ?: ""
            github = knsProfile?.github ?: ""
            redirect = knsProfile?.redirectUrl ?: ""
            // Staged image picks belonged to the previous domain's profile.
            if (seeded) viewModel.clearPendingProfileImages()
            seeded = true
            seededDomain = activeProfileDomainName
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.resetEditProfileState() }
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) viewModel.setPendingAvatar(uri) }
    val bannerPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) viewModel.setPendingBanner(uri) }

    var showSaveDialog by remember { mutableStateOf(false) }

    // What "Save" is actually about to submit — each entry becomes its own on-chain commit/reveal
    // transaction, so this doubles as both the confirm dialog's change list and its cost count.
    val pendingChanges = remember(bio, x, website, telegram, discord, email, github, redirect, pendingAvatarUri, pendingBannerUri, avatarCleared, bannerCleared, knsProfile) {
        buildList {
            if (pendingAvatarUri != null) add("Avatar")
            else if (avatarCleared && !knsProfile?.avatarUrl.isNullOrEmpty()) add("Avatar (removed)")
            if (pendingBannerUri != null) add("Banner")
            else if (bannerCleared && !knsProfile?.bannerUrl.isNullOrEmpty()) add("Banner (removed)")
            if (bio.trim() != (knsProfile?.bio ?: "")) add("Bio")
            if (x.trim() != (knsProfile?.x ?: "")) add("X")
            if (website.trim() != (knsProfile?.website ?: "")) add("Website")
            if (telegram.trim() != (knsProfile?.telegram ?: "")) add("Telegram")
            if (discord.trim() != (knsProfile?.discord ?: "")) add("Discord")
            if (email.trim() != (knsProfile?.contactEmail ?: "")) add("Email")
            if (github.trim() != (knsProfile?.github ?: "")) add("GitHub")
            if (redirect.trim() != (knsProfile?.redirectUrl ?: "")) add("Redirect")
        }
    }

    val inFlight = editState.step !in listOf(
        WalletViewModel.EditProfileStep.IDLE,
        WalletViewModel.EditProfileStep.SUCCESS,
        WalletViewModel.EditProfileStep.PARTIAL_FAILURE,
        WalletViewModel.EditProfileStep.FAILED
    )

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.edit_kns_profile), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack, enabled = !inFlight) {
                        Text(stringResource(R.string.cancel), color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    TextButton(
                        onClick = { if (pendingChanges.isNotEmpty()) showSaveDialog = true },
                        enabled = !inFlight && pendingChanges.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.save), color = if (!inFlight && pendingChanges.isNotEmpty()) KaspaTeal else Color.Gray, fontWeight = FontWeight.Bold)
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Re-enters the same guided wizard used to create a profile from scratch - it
            // already knows (via the domain/profile it fetches) to offer skipping domain
            // registration and pre-fill the banner/avatar/detail steps with whatever's already
            // inscribed, so this is a safe re-entry point regardless of how much of a profile
            // already exists. Lives here (rather than next to "KNS Profile" on the Profile tab)
            // since that spot sits directly beside the banner image, which made it unclickable
            // whenever a banner was set.
            if (showSetupGuides) {
                SettingsSection(title = stringResource(R.string.setup_guide)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToSetupGuide() }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.setup_guide),
                            color = LocalAppColors.current.textPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Default.ChevronRight, null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(20.dp))
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.domains)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToDomains() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.domains), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${ownedDomainAssets.size}", color = LocalAppColors.current.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.ChevronRight, null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(20.dp))
                }
            }

            SettingsSection(title = stringResource(R.string.avatar)) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ContactAvatar(
                        imageUrl = if (avatarCleared) null else (pendingAvatarUri?.toString() ?: knsProfile?.avatarUrl),
                        fallbackText = activeProfileDomainName ?: "?",
                        size = 64.dp
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(onClick = { avatarPicker.launch("image/*") }, enabled = !inFlight) {
                            Text(stringResource(R.string.choose_avatar), color = KaspaTeal, fontWeight = FontWeight.Bold)
                        }
                        if (pendingAvatarUri != null || (!avatarCleared && !knsProfile?.avatarUrl.isNullOrEmpty())) {
                            TextButton(
                                onClick = {
                                    if (pendingAvatarUri != null) viewModel.setPendingAvatar(null) else viewModel.clearExistingAvatar()
                                },
                                enabled = !inFlight
                            ) {
                                Text(stringResource(R.string.remove), color = Color(0xFFFF3B30))
                            }
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.banner)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val previewUrl = if (bannerCleared) null else (pendingBannerUri?.toString() ?: knsProfile?.bannerUrl)
                    if (previewUrl != null) {
                        SubcomposeAsyncImage(
                            model = previewUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(LocalAppColors.current.surface)
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(onClick = { bannerPicker.launch("image/*") }, enabled = !inFlight) {
                            Text(stringResource(R.string.choose_banner), color = KaspaTeal, fontWeight = FontWeight.Bold)
                        }
                        if (pendingBannerUri != null || (!bannerCleared && !knsProfile?.bannerUrl.isNullOrEmpty())) {
                            TextButton(
                                onClick = {
                                    if (pendingBannerUri != null) viewModel.setPendingBanner(null) else viewModel.clearExistingBanner()
                                },
                                enabled = !inFlight
                            ) {
                                Text(stringResource(R.string.remove), color = Color(0xFFFF3B30))
                            }
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.profile)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditProfileTextField(stringResource(R.string.bio), bio, { bio = it }, enabled = !inFlight, singleLine = false)
                    EditProfileTextField(stringResource(R.string.x), x, { x = it }, enabled = !inFlight)
                    EditProfileTextField(stringResource(R.string.website), website, { website = it }, enabled = !inFlight)
                    EditProfileTextField(stringResource(R.string.telegram), telegram, { telegram = it }, enabled = !inFlight)
                    EditProfileTextField(stringResource(R.string.discord), discord, { discord = it }, enabled = !inFlight)
                    EditProfileTextField(stringResource(R.string.email), email, { email = it }, enabled = !inFlight)
                    EditProfileTextField(stringResource(R.string.github), github, { github = it }, enabled = !inFlight)
                    EditProfileTextField(stringResource(R.string.redirect), redirect, { redirect = it }, enabled = !inFlight)
                }
            }

            Spacer(Modifier.height(60.dp))
        }
    }

    if (showSaveDialog) {
        val terminal = editState.step in listOf(
            WalletViewModel.EditProfileStep.SUCCESS,
            WalletViewModel.EditProfileStep.PARTIAL_FAILURE,
            WalletViewModel.EditProfileStep.FAILED
        )
        fun closeDialog() {
            showSaveDialog = false
            viewModel.resetEditProfileState()
        }
        AlertDialog(
            onDismissRequest = {
                when (editState.step) {
                    WalletViewModel.EditProfileStep.IDLE -> showSaveDialog = false
                    else -> if (terminal) closeDialog()
                }
            },
            containerColor = LocalAppColors.current.surface,
            title = {
                Text(
                    when (editState.step) {
                        WalletViewModel.EditProfileStep.IDLE -> "Confirm Changes"
                        WalletViewModel.EditProfileStep.SUCCESS -> "Saved"
                        WalletViewModel.EditProfileStep.PARTIAL_FAILURE -> "Some Changes Failed"
                        WalletViewModel.EditProfileStep.FAILED -> "Save Failed"
                        else -> "Saving..."
                    },
                    color = LocalAppColors.current.textPrimary
                )
            },
            text = {
                when (editState.step) {
                    WalletViewModel.EditProfileStep.IDLE -> Column {
                        Text(
                            "${pendingChanges.size} change${if (pendingChanges.size == 1) "" else "s"}. Each is submitted as its own on-chain transaction from your chatting address:",
                            color = LocalAppColors.current.textPrimary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        pendingChanges.forEach { Text("• $it", color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.each_transaction_temporarily_uses_2_kas),
                            color = LocalAppColors.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    WalletViewModel.EditProfileStep.UPLOADING_AVATAR,
                    WalletViewModel.EditProfileStep.UPLOADING_BANNER,
                    WalletViewModel.EditProfileStep.SUBMITTING_FIELD -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(color = KaspaTeal)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            when (editState.step) {
                                WalletViewModel.EditProfileStep.UPLOADING_AVATAR -> "Uploading avatar..."
                                WalletViewModel.EditProfileStep.UPLOADING_BANNER -> "Uploading banner..."
                                else -> "Submitting ${editState.currentFieldLabel}..."
                            },
                            color = LocalAppColors.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    WalletViewModel.EditProfileStep.SUCCESS -> Text(
                        if (editState.fieldResults.isEmpty()) "Nothing to save." else "All changes saved.",
                        color = Color(0xFF4CD964),
                        fontWeight = FontWeight.Bold
                    )
                    WalletViewModel.EditProfileStep.PARTIAL_FAILURE -> Column {
                        Text(stringResource(R.string.some_changes_failed_to_save), color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                        editState.fieldResults.filter { !it.success }.forEach {
                            Text("${it.fieldKey}: ${it.errorMessage ?: "failed"}", color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    WalletViewModel.EditProfileStep.FAILED -> Text(
                        editState.fieldResults.firstOrNull { !it.success }?.errorMessage ?: "Save failed",
                        color = Color(0xFFFF3B30)
                    )
                }
            },
            confirmButton = {
                when (editState.step) {
                    WalletViewModel.EditProfileStep.IDLE -> TextButton(
                        onClick = {
                            viewModel.saveKnsProfile(
                                mapOf(
                                    "bio" to bio,
                                    "x" to x,
                                    "website" to website,
                                    "telegram" to telegram,
                                    "discord" to discord,
                                    "contactEmail" to email,
                                    "github" to github,
                                    "redirectUrl" to redirect
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.confirm), color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                    WalletViewModel.EditProfileStep.SUCCESS,
                    WalletViewModel.EditProfileStep.PARTIAL_FAILURE,
                    WalletViewModel.EditProfileStep.FAILED -> TextButton(onClick = { closeDialog() }) {
                        Text(stringResource(R.string.done), color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                    else -> {}
                }
            },
            dismissButton = {
                if (editState.step == WalletViewModel.EditProfileStep.IDLE) {
                    TextButton(onClick = { showSaveDialog = false }) {
                        Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                    }
                }
            }
        )
    }
}

@Composable
private fun EditProfileTextField(label: String, value: String, onValueChange: (String) -> Unit, enabled: Boolean, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        enabled = enabled,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = LocalAppColors.current.textPrimary,
            unfocusedTextColor = LocalAppColors.current.textPrimary,
            disabledTextColor = Color.Gray,
            focusedBorderColor = KaspaTeal,
            unfocusedBorderColor = LocalAppColors.current.textSecondary,
            focusedLabelColor = KaspaTeal,
            unfocusedLabelColor = LocalAppColors.current.textSecondary
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SeedPhraseScreen(viewModel: WalletViewModel, onBack: () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    val mnemonic = remember { viewModel.getActiveMnemonic() ?: "" }
    val privateKey = remember { viewModel.getPrivateKeyHex() }
    val words = remember { mnemonic.split(" ") }
    val context = LocalContext.current

    // Blocks screenshots and screen recording of the seed phrase / private key for as long as
    // this screen is on-screen (window-level flag, the standard Android mechanism - unlike iOS,
    // which has no API to block a screenshot outright, only to detect active screen recording
    // after the fact via UIScreen.isCaptured). Cleared on leaving so it doesn't leak onto other
    // screens.
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Auto-hides the words again after a short window instead of leaving them on screen
    // indefinitely once revealed — someone glancing at the phone later shouldn't still see them.
    LaunchedEffect(revealed) {
        if (revealed) {
            delay(7000)
            revealed = false
        }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.seed_phrase), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.done), color = KaspaTeal, fontWeight = FontWeight.Bold)
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
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF2C1E1E))
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFF39C12),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = stringResource(R.string.security_warning),
                        color = Color(0xFFF39C12),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.anyone_with_your_seed_phrase_can),
                        color = Color(0xFF948B8B),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (!revealed) {
                Column(
                    modifier = Modifier
                        .clickable { revealed = true }
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = null,
                        tint = LocalAppColors.current.textSecondary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.tap_to_reveal_seed_phrase),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 3
                ) {
                    words.forEachIndexed { index, word ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(LocalAppColors.current.surface)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.width(20.dp)
                            )
                            Text(
                                text = word,
                                color = LocalAppColors.current.textPrimary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (revealed) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Seed-phrase copy is intentionally NOT offered — the recovery phrase must be
                    // transcribed by hand, never placed on the clipboard (other apps and clipboard
                    // history can read it). The private key hex may still be copied, but the
                    // clipboard is auto-wiped 30s later (see copyPrivateKeyWithAutoWipe).
                    TextButton(onClick = {
                        copyPrivateKeyWithAutoWipe(context, privateKey)
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tag, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.copy_private_key_hex), color = KaspaTeal)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    // 4.0 (matches iOS): null renders the settings HUB (one row per category); a key renders
    // just that category's items as its own page.
    sectionKey: String? = null,
    walletViewModel: WalletViewModel = hiltViewModel(),
    connectionViewModel: ConnectionViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val balance by walletViewModel.fullBalance.collectAsState()
    val dotColorHex by connectionViewModel.dotColorHex.collectAsState()
    val darkModeEnabled by walletViewModel.darkModeEnabled.collectAsState()
    val showSetupGuides by walletViewModel.showSetupGuides.collectAsState()
    val currencyCode by walletViewModel.currency.collectAsState()
    val biometricSeedPhraseEnabled by walletViewModel.biometricSeedPhraseEnabled.collectAsState()
    val notificationsEnabled by settingsViewModel.notificationsEnabled.collectAsState()
    val showFeeEstimate by settingsViewModel.showFeeEstimate.collectAsState()
    val chatPhotoQualityPreset by chatViewModel.chatPhotoQualityPreset.collectAsState()
    val kaspaExplorer by chatViewModel.kaspaExplorer.collectAsState()
    val syncSystemContactsEnabled by chatViewModel.syncSystemContactsEnabled.collectAsState()
    val autoCreateSystemContactsEnabled by chatViewModel.autoCreateSystemContactsEnabled.collectAsState()
    val exportChatHistoryState by chatViewModel.exportState.collectAsState()
    val importChatHistoryState by chatViewModel.importState.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val syncContactsPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val autoCreatePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val importChatHistoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) chatViewModel.importChatHistory(uri)
    }

    LaunchedEffect(Unit) {
        walletViewModel.refreshBalance()
    }

    // Danger Zone "Wipe and Re-sync" flow: the chat picker and the blocking progress modal
    // render as full-screen overlays over the whole Scaffold (same in-composition Box pattern
    // as ChatRestoreProgressOverlay on the storage pages), so their visibility lives up here.
    var showResyncChatPicker by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = LocalAppColors.current.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .background(LocalAppColors.current.background)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(R.string.settings),
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                TopStatusBar(
                    balance = balance,
                    onStatusClick = { navController.navigate("connection_status") },
                    dotColorHex = dotColorHex,
                    showAddButton = false
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (sectionKey == null) {
                // Flat hub list (matches iOS): no section titles - just the categories, the
                // seed-phrase action, and Danger Zone in one card.
                Surface(
                    color = LocalAppColors.current.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                    SettingsNavigationItem(stringResource(R.string.customization), Icons.Default.Palette, onClick = { navController.navigate("settings_section/customization") })
                    HorizontalDivider(color = LocalAppColors.current.divider)
                    SettingsNavigationItem(stringResource(R.string.security), Icons.Default.Security, onClick = { navController.navigate("settings_section/security") })
                    HorizontalDivider(color = LocalAppColors.current.divider)
                    SettingsNavigationItem(stringResource(R.string.connection), Icons.Default.Language, onClick = { navController.navigate("settings_section/connection") })
                    HorizontalDivider(color = LocalAppColors.current.divider)
                    // Top-level Notifications hub (4.0, matches iOS): Chats / Wallet / KaPosts
                    // subpages - sits between Connection and Chats like iOS's settings list.
                    SettingsNavigationItem(stringResource(R.string.notifications), Icons.Default.NotificationsNone, onClick = { navController.navigate("settings_section/notifications") })
                    HorizontalDivider(color = LocalAppColors.current.divider)
                    SettingsNavigationItem(stringResource(R.string.chats), Icons.Default.Forum, onClick = { navController.navigate("settings_section/chats") })
                    HorizontalDivider(color = LocalAppColors.current.divider)
                    SettingsNavigationItem(stringResource(R.string.contacts), Icons.Default.People, onClick = { navController.navigate("settings_section/contacts") })
                    HorizontalDivider(color = LocalAppColors.current.divider)
                    SettingsNavigationItem(stringResource(R.string.storage), Icons.Default.Storage, onClick = { navController.navigate("settings_section/storage") })
                    HorizontalDivider(color = LocalAppColors.current.divider)
                    SettingsNavigationItem(stringResource(R.string.chat_history), Icons.Default.History, onClick = { navController.navigate("settings_section/chat_history") })
                    HorizontalDivider(color = LocalAppColors.current.divider)
                    SettingsNavigationItem(stringResource(R.string.diagnostics), Icons.Default.MonitorHeart, onClick = { navController.navigate("settings_section/diagnostics") })
                    HorizontalDivider(color = LocalAppColors.current.divider)
                    SettingsActionItem(stringResource(R.string.view_seed_phrase), Icons.Default.Key, KaspaTeal) {
                        if (biometricSeedPhraseEnabled) {
                            context.authenticateWithDeviceCredential(
                                title = "Unlock to View Seed Phrase",
                                onSuccess = { navController.navigate("seed_phrase") }
                            )
                        } else {
                            navController.navigate("seed_phrase")
                        }
                    }
                    HorizontalDivider(color = LocalAppColors.current.divider)
                    SettingsActionItem(stringResource(R.string.danger_zone), Icons.Default.Warning, Color.Red) {
                        navController.navigate("settings_section/danger_zone")
                    }
                    }
                }
            }

            if (sectionKey == "customization") {
            SettingsSection(title = stringResource(R.string.customization)) {
                SettingsSwitchItem(stringResource(R.string.dark_mode), darkModeEnabled) { enabled ->
                    walletViewModel.setDarkModeEnabled(enabled)
                }
                SettingsDivider()
                SettingsNavigationItem(stringResource(R.string.menu), Icons.Default.Apps, onClick = {
                    navController.navigate("settings_menu")
                })
                SettingsDivider()
                SettingsNavigationItem(stringResource(R.string.language), Icons.Default.Translate, onClick = {
                    navController.navigate("language_settings")
                })
                SettingsDivider()
                SettingsNavigationItem(stringResource(R.string.currency), Icons.Default.AttachMoney, currencyCode.uppercase(), onClick = {
                    navController.navigate("currency_settings")
                })
            }
            }

            if (sectionKey == "security") {
            SettingsSection(title = stringResource(R.string.security)) {
                // Shared with the accounts screen's App Settings (see AppSettingsScreen.kt) -
                // every row is app-wide, so both entry points render the exact same items.
                SecuritySettingsItems(
                    walletViewModel = walletViewModel,
                    onNavigateToChildMode = { navController.navigate("child_mode_settings") }
                )
            }
            }

            if (sectionKey == "connection") {
            SettingsSection(title = stringResource(R.string.connection)) {
                SettingsNavigationItem(stringResource(R.string.connection_settings), Icons.Default.Language, "Mainnet", onClick = {
                    navController.navigate("connection_settings")
                })
                SettingsDivider()
                SettingsNavigationItem(stringResource(R.string.kaspa_explorer), Icons.Default.Explore, kaspaExplorer.displayName, onClick = {
                    navController.navigate("kaspa_explorer_settings")
                })
            }
            }

            if (sectionKey == "notifications") {
            // Notifications hub (matches iOS's NotificationsHubPage): three subpages.
            SettingsSection(title = stringResource(R.string.notifications)) {
                SettingsNavigationItem(stringResource(R.string.chats), Icons.Default.Forum, onClick = {
                    navController.navigate("notification_settings")
                })
                SettingsDivider()
                SettingsNavigationItem("Wallet", Icons.Default.AccountBalanceWallet, onClick = {
                    navController.navigate("wallet_notification_settings")
                })
                SettingsDivider()
                SettingsNavigationItem("KaPosts", Icons.Default.Edit, onClick = {
                    navController.navigate("kaposts_notification_settings")
                })
            }
            }

            if (sectionKey == "chats") {
            SettingsSection(title = stringResource(R.string.chats)) {
                SettingsSwitchItem(stringResource(R.string.show_fee_estimate), showFeeEstimate) { enabled ->
                    settingsViewModel.setShowFeeEstimate(enabled)
                }
                SettingsDivider()
                SettingsNavigationItem(
                    stringResource(R.string.photo_quality),
                    Icons.Default.Photo,
                    chatPhotoQualityPreset.displayName,
                    onClick = { navController.navigate("photo_quality_settings") }
                )
                SettingsDivider()
                val quickReactionEmojis by settingsViewModel.quickReactionEmojis.collectAsState()
                SettingsNavigationItem(
                    "Quick Reactions",
                    Icons.Default.EmojiEmotions,
                    quickReactionEmojis.joinToString(""),
                    onClick = { navController.navigate("quick_reaction_settings") }
                )
            }
            }

            if (sectionKey == "contacts") {
            SettingsSection(title = stringResource(R.string.contacts)) {
                SettingsSwitchItem(stringResource(R.string.sync_system_contacts), syncSystemContactsEnabled) { enabled ->
                    chatViewModel.setSyncSystemContactsEnabled(enabled)
                    if (enabled) syncContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
                SettingsDivider()
                SettingsSwitchItem(stringResource(R.string.autocreate_system_contacts), autoCreateSystemContactsEnabled) { enabled ->
                    chatViewModel.setAutoCreateSystemContactsEnabled(enabled)
                    if (enabled) {
                        autoCreatePermissionLauncher.launch(
                            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
                        )
                    }
                }
                SettingsFooter(stringResource(R.string.uses_your_device_contacts_to_match))
            }
            }

            if (sectionKey == "storage") {
            // Storage hub (matches iOS's Settings > Storage): one category row per backup
            // provider, each pushing its own page (see StorageScreens.kt). Google Drive is
            // Android's counterpart to iOS's iCloud row. On-device usage has no settings of its
            // own, so it stays inline here as a readout rather than a row leading to a page with
            // a single read-only line.
            // On-device settings first (message retention + local usage), matching iOS's Storage
            // screen, before the cloud-provider rows below.
            SettingsSection(title = stringResource(R.string.on_this_device)) {
                val localStorageSizeBytes by chatViewModel.localStorageSizeBytes.collectAsState()
                LaunchedEffect(Unit) { chatViewModel.refreshLocalStorageSize() }
                MessageRetentionSetting(chatViewModel)
                SettingsDivider()
                SettingsInfoItem(
                    label = stringResource(R.string.local_storage_used),
                    value = localStorageSizeBytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
                        ?: "Calculating..."
                )
            }

            SettingsSection(title = stringResource(R.string.cloud_storage)) {
                SettingsNavigationItem(stringResource(R.string.google_drive), Icons.Default.CloudQueue, onClick = {
                    navController.navigate("storage_google_drive")
                })
                SettingsDivider()
                SettingsNavigationItem(stringResource(R.string.nextcloud), Icons.Default.Cloud, onClick = {
                    navController.navigate("storage_nextcloud")
                })
            }
            }

            if (sectionKey == "chat_history") {
            SettingsSection(title = stringResource(R.string.chat_history)) {
                val exportInFlight = exportChatHistoryState.status == ChatViewModel.ChatHistoryOpStatus.IN_PROGRESS
                val importInFlight = importChatHistoryState.status == ChatViewModel.ChatHistoryOpStatus.IN_PROGRESS

                SettingsActionItem(
                    label = if (exportInFlight) "Exporting..." else "Export Chat History",
                    icon = Icons.Default.FileUpload,
                    color = if (exportInFlight) Color.Gray else KaspaTeal
                ) {
                    if (!exportInFlight) {
                        chatViewModel.exportChatHistory { uri ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Export Chat History"))
                        }
                    }
                }
                if (exportChatHistoryState.status == ChatViewModel.ChatHistoryOpStatus.FAILED) {
                    Text(
                        exportChatHistoryState.message ?: "Export failed",
                        color = Color(0xFFFF3B30),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                SettingsDivider()

                SettingsActionItem(
                    label = if (importInFlight) "Importing..." else "Import Chat History",
                    icon = Icons.Default.FileDownload,
                    color = if (importInFlight) Color.Gray else KaspaTeal
                ) {
                    if (!importInFlight) {
                        importChatHistoryLauncher.launch(arrayOf("application/json"))
                    }
                }
                if (importChatHistoryState.status == ChatViewModel.ChatHistoryOpStatus.SUCCESS) {
                    Text(
                        importChatHistoryState.message ?: "Import complete",
                        color = Color(0xFF4CD964),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                if (importChatHistoryState.status == ChatViewModel.ChatHistoryOpStatus.FAILED) {
                    Text(
                        importChatHistoryState.message ?: "Import failed",
                        color = Color(0xFFFF3B30),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                SettingsFooter(stringResource(R.string.exports_a_plaintext_json_file_of))
            }
            }

            if (sectionKey == "diagnostics") {
            SettingsSection(title = stringResource(R.string.diagnostics)) {
                // Shared with the accounts screen's App Settings (see AppSettingsScreen.kt).
                DiagnosticsSettingsItems(chatViewModel = chatViewModel)
            }
            }

            if (sectionKey == "danger_zone") {
            SettingsSection(title = stringResource(R.string.danger_zone)) {
                val googleBackupOpState by chatViewModel.googleBackupOpState.collectAsState()
                val resyncPhase by chatViewModel.restoreCoordinator.phase.collectAsState()
                var showResyncScopeDialog by remember { mutableStateOf(false) }
                var showDriveWipeConfirm by remember { mutableStateOf(false) }
                // The shared Drive op state also carries backup/restore results from the storage
                // pages; only surface it here once a wipe was actually requested from this row.
                var driveWipeRequested by remember { mutableStateOf(false) }

                val resyncInFlight = resyncPhase is com.kachat.app.services.BackupRestoreCoordinator.Phase.Running
                val driveWipeInFlight = googleBackupOpState.status == ChatViewModel.GoogleBackupOpStatus.IN_PROGRESS

                // Runs through the BackupRestoreCoordinator: scope chooser first (All Chats or a
                // multi-select of conversations), then the same un-leavable progress modal as a
                // backup restore. See the overlays after this Scaffold.
                SettingsActionItem(
                    label = "Wipe and Re-sync Incoming Messages",
                    icon = Icons.Default.Cached,
                    color = if (resyncInFlight) Color.Gray else Color.Red
                ) {
                    if (!resyncInFlight) showResyncScopeDialog = true
                }
                SettingsDivider()
                // 4.0 (matches iOS): the old combined "wipe account & messages (& Cloud)"
                // entries are gone. This row touches ONLY the current wallet's Google Drive
                // backup file (ChatViewModel.deleteDriveBackup); the account, local messages,
                // and other wallets' backups stay. Account removal keeps its own flow on the
                // accounts screen (WalletViewModel.deleteWallet).
                SettingsActionItem(
                    label = if (driveWipeInFlight) "Wiping..." else "Wipe Google Drive Backup",
                    icon = Icons.Default.CloudOff,
                    color = if (driveWipeInFlight) Color.Gray else Color.Red
                ) {
                    if (!driveWipeInFlight) showDriveWipeConfirm = true
                }
                if (driveWipeRequested && (googleBackupOpState.status == ChatViewModel.GoogleBackupOpStatus.SUCCESS || googleBackupOpState.status == ChatViewModel.GoogleBackupOpStatus.FAILED)) {
                    SettingsFooter(googleBackupOpState.message ?: "Done")
                }

                if (showResyncScopeDialog) {
                    AlertDialog(
                        onDismissRequest = { showResyncScopeDialog = false },
                        containerColor = LocalAppColors.current.surface,
                        title = { Text("Wipe and Re-sync Incoming Messages", color = LocalAppColors.current.textPrimary) },
                        text = {
                            Text(
                                "This removes incoming messages from this device, then re-syncs them from the blockchain. Your account info and sent messages are preserved. Re-sync every chat, or only chats you select.",
                                color = LocalAppColors.current.textSecondary
                            )
                        },
                        confirmButton = {
                            Column(horizontalAlignment = Alignment.End) {
                                TextButton(onClick = {
                                    showResyncScopeDialog = false
                                    chatViewModel.wipeAndResyncIncomingMessages(null)
                                }) {
                                    Text("All Chats", color = Color.Red, fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = {
                                    showResyncScopeDialog = false
                                    showResyncChatPicker = true
                                }) {
                                    Text("Select Chats", color = KaspaTeal)
                                }
                                TextButton(onClick = { showResyncScopeDialog = false }) {
                                    Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                                }
                            }
                        }
                    )
                }

                if (showDriveWipeConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDriveWipeConfirm = false },
                        containerColor = LocalAppColors.current.surface,
                        title = { Text("Wipe Google Drive Backup?", color = LocalAppColors.current.textPrimary) },
                        text = {
                            Text(
                                "This deletes this wallet's chat history backup file from your Google Drive. Your account and the messages on this device stay as they are. Only the Drive copy is deleted, and other wallets' backups are not affected.",
                                color = LocalAppColors.current.textSecondary
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showDriveWipeConfirm = false
                                driveWipeRequested = true
                                chatViewModel.deleteDriveBackup()
                            }) {
                                Text("Wipe Backup", color = Color.Red, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDriveWipeConfirm = false }) {
                                Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary)
                            }
                        }
                    )
                }
            }
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    // Danger Zone overlays, over the whole settings Scaffold: the scoped-resync chat picker,
    // and the same blocking progress modal the storage pages use for restores (the coordinator
    // owns the job in its own scope; the overlay is un-leavable while it runs).
    if (sectionKey == "danger_zone") {
        if (showResyncChatPicker) {
            ResyncChatPickerOverlay(
                chatViewModel = chatViewModel,
                onStart = { ids ->
                    showResyncChatPicker = false
                    chatViewModel.wipeAndResyncIncomingMessages(ids)
                },
                onDismiss = { showResyncChatPicker = false }
            )
        }
        ChatRestoreProgressOverlay(chatViewModel.restoreCoordinator)
    }
    }
}

/**
 * Full-screen chooser for a chat-scoped "Wipe and Re-sync Incoming Messages" — the same
 * in-composition overlay pattern as [ChatRestoreProgressOverlay], listing the account's 1:1
 * conversations in chat-list row styling (avatar, name, short address) with a checkbox per
 * chat. Groups are not listed: the wipe has only ever covered 1:1 message history.
 */
@Composable
private fun ResyncChatPickerOverlay(
    chatViewModel: ChatViewModel,
    onStart: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val conversations by chatViewModel.conversations.collectAsState()
    val colors = LocalAppColors.current
    val selected = remember { mutableStateListOf<String>() }

    BackHandler { onDismiss() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            // Claims the hit test so the settings page underneath is unreachable while choosing.
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel), tint = colors.textPrimary)
            }
            Text(
                "Select Chats to Re-sync",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }
        Text(
            "Only the chats you select are wiped and re-synced from the blockchain. Every other chat keeps its history untouched.",
            color = colors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(conversations, key = { it.contact.id }) { convo ->
                val isSelected = convo.contact.id in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (isSelected) selected.remove(convo.contact.id) else selected.add(convo.contact.id)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ContactAvatar(
                        imageUrl = convo.contact.knsAvatarUrl,
                        deviceContactPhotoUri = convo.contact.systemContactPhotoUri,
                        backupPhotoBase64 = convo.contact.backupPhotoBase64,
                        fallbackText = convo.contact.alias ?: convo.contact.id.takeLast(8),
                        size = 48.dp
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = convo.contact.alias ?: KaspaAddress.shortDisplay(convo.contact.id),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = KaspaAddress.shortDisplay(convo.contact.id),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { checked ->
                            if (checked) {
                                if (!isSelected) selected.add(convo.contact.id)
                            } else {
                                selected.remove(convo.contact.id)
                            }
                        },
                        colors = CheckboxDefaults.colors(checkedColor = KaspaTeal)
                    )
                }
            }
        }
        Button(
            onClick = { onStart(selected.toList()) },
            enabled = selected.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Red,
                disabledContainerColor = colors.surfaceVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            Text(
                when {
                    selected.isEmpty() -> "Wipe and Re-sync"
                    selected.size == 1 -> "Wipe and Re-sync 1 Chat"
                    else -> "Wipe and Re-sync ${selected.size} Chats"
                },
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SettingsSection(title: String?, headerAction: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = LocalAppColors.current.textSecondary,
                    modifier = Modifier.weight(1f).padding(start = 8.dp, bottom = 8.dp)
                )
                headerAction?.invoke()
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(LocalAppColors.current.surface)
        ) {
            content()
        }
    }
}

/** A circular icon button with its label centered underneath — used in pairs on [ProfileScreen] (Accept Kaspa As Payment / Fund Chatting Address). */
@Composable
private fun ProfileCircleAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(KaspaTeal)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color.Black, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            color = LocalAppColors.current.textPrimary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * One compact address action row on [ProfileScreen] (iOS parity: ContactsView's
 * addressActionRow), one per address role (Chatting Address / Spending Address): the role title
 * with its balance underneath, and three icon-only circle buttons on the right: Copy, Send,
 * Manage. Sits directly on the screen background - no card surface - matching how the big QR
 * circle buttons ([ProfileCircleAction]) sit above it. Replaces the old collapsible address
 * dropdowns that hid the same three actions behind an expand
 * chevron. `address` is nullable because the current spending address can be momentarily
 * unresolvable right after wallet load; in that state the row shows a loading placeholder and
 * the caller's actions guard against the nil address.
 */
@Composable
private fun ProfileAddressActionCard(
    title: String,
    address: String?,
    balanceText: String?,
    onCopy: () -> Unit,
    onSend: () -> Unit,
    onManage: () -> Unit
) {
    // Two buttons instead of three now, so the row centers as one cluster instead of
    // splitting to the screen edges.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(LocalAppColors.current.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)
    ) {
        // The title + balance block IS the copy affordance: tapping it copies the address,
        // replacing the old dedicated Copy button.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Fixed width so Send and Manage land in the same column on both cards
            // regardless of title and balance text width.
            modifier = Modifier
                .width(140.dp)
                .clickable(enabled = address != null) { onCopy() }
        ) {
            Text(
                title,
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1
            )
            if (address == null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "Loading...",
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 12.sp
                )
            } else if (!balanceText.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    balanceText,
                    color = KaspaTeal,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
        }
        ProfileAddressCardAction(Icons.AutoMirrored.Filled.Send, "Send", onSend)
        ProfileAddressCardAction(Icons.Default.Settings, "Manage", onManage)
    }
}

/** One circular icon button inside [ProfileAddressActionCard] — a smaller sibling of [ProfileCircleAction] with the same teal-on-circle look. No text label: the icon carries the meaning, [contentDescription] keeps it accessible. */
@Composable
private fun ProfileAddressCardAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(KaspaTeal.copy(alpha = 0.15f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = KaspaTeal, modifier = Modifier.size(26.dp))
    }
}

@Composable
fun SettingsSwitchItem(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = LocalAppColors.current.textPrimary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = KaspaTeal,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color.Gray
            )
        )
    }
}

@Composable
fun SettingsNavigationItem(label: String, icon: ImageVector?, value: String = "", showIcon: Boolean = true, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showIcon && icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
        }
        Text(text = label, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(text = value, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp))
        }
        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(14.dp))
    }
}

@Composable
fun SettingsActionItem(label: String, icon: ImageVector, color: Color, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = label, color = color, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SettingsInfoItem(label: String, value: String, valueColor: Color = LocalAppColors.current.textSecondary, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodyLarge)
        Text(text = value, color = valueColor, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = LocalAppColors.current.divider, thickness = 0.5.dp)
}

@Composable
fun SettingsFooter(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalAppColors.current.textSecondary,
        modifier = Modifier.padding(16.dp)
    )
}

/**
 * The Security section's rows — every one app-wide (biometric toggles + Child Mode), so the same
 * items render both in the in-account Settings > Security page and in the accounts screen's App
 * Settings sheet (see AppSettingsScreen.kt), one source of truth. Caller supplies the enclosing
 * [SettingsSection] and the navigation to the Child Mode screen (each host has its own NavHost).
 */
@Composable
fun SecuritySettingsItems(
    walletViewModel: WalletViewModel,
    onNavigateToChildMode: () -> Unit
) {
    val biometricSeedPhraseEnabled by walletViewModel.biometricSeedPhraseEnabled.collectAsState()
    val biometricAccountLoginEnabled by walletViewModel.biometricAccountLoginEnabled.collectAsState()
    val biometricSpendingKeyEnabled by walletViewModel.biometricSpendingKeyEnabled.collectAsState()
    SettingsSwitchItem(stringResource(R.string.biometrics_for_seed_phrase), biometricSeedPhraseEnabled) { enabled ->
        walletViewModel.setBiometricSeedPhraseEnabled(enabled)
    }
    SettingsDivider()
    SettingsSwitchItem(stringResource(R.string.biometrics_for_account_login), biometricAccountLoginEnabled) { enabled ->
        walletViewModel.setBiometricAccountLoginEnabled(enabled)
    }
    SettingsDivider()
    SettingsSwitchItem(stringResource(R.string.biometrics_for_address_private_keys), biometricSpendingKeyEnabled) { enabled ->
        walletViewModel.setBiometricSpendingKeyEnabled(enabled)
    }
    SettingsDivider()
    // Child Mode (matches iOS Settings > Security > Child Mode). NEVER biometric-gated - the
    // whole point is that the device owner (the child) can pass fingerprint/face unlock but
    // must not know the parent's password. Fully functional with no account active: the
    // password record and enabled flag are device-global (ChildModeService/DataStore).
    val childModeEnabled by walletViewModel.childModeEnabled.collectAsState()
    SettingsNavigationItem(
        stringResource(R.string.child_mode),
        Icons.Default.FamilyRestroom,
        if (childModeEnabled) stringResource(R.string.on) else stringResource(R.string.off),
        onClick = onNavigateToChildMode
    )
    SettingsDivider()
    // Per-account fresh-address payment pool toggle - moved here from the Chats page
    // (all 3 platforms keep it under Security now).
    val privacySettingsViewModel: SettingsViewModel = hiltViewModel()
    val chatsPaymentPrivacyEnabled by privacySettingsViewModel.chatsPaymentPrivacyEnabled.collectAsState()
    SettingsSwitchItem("Chats Payment Privacy", chatsPaymentPrivacyEnabled) { enabled ->
        privacySettingsViewModel.setChatsPaymentPrivacyEnabled(enabled)
    }
    SettingsFooter("On: you receive payments on fresh private addresses shared with each contact, and payments you send are funded from your private spending addresses. Off: you receive on your public chatting address and send from it. Either way, payments you send arrive on a fresh address whenever the recipient shares one.")
}

/**
 * The Diagnostics section's rows — the export gathers app/device/settings/node-pool info and is
 * account-tolerant (see DiagnosticsExportService's no-active-account fallbacks), so the same
 * items render both in the in-account Settings > Diagnostics page and in the accounts screen's
 * App Settings sheet. Caller supplies the enclosing [SettingsSection].
 */
@Composable
fun DiagnosticsSettingsItems(chatViewModel: ChatViewModel = hiltViewModel()) {
    val diagnosticsExportState by chatViewModel.diagnosticsExportState.collectAsState()
    val context = LocalContext.current
    val diagnosticsExportInFlight = diagnosticsExportState.status == ChatViewModel.ChatHistoryOpStatus.IN_PROGRESS

    SettingsActionItem(
        label = if (diagnosticsExportInFlight) "Exporting..." else "Export Diagnostics Archive",
        icon = Icons.Default.BugReport,
        color = if (diagnosticsExportInFlight) Color.Gray else KaspaTeal
    ) {
        if (!diagnosticsExportInFlight) {
            chatViewModel.exportDiagnostics { uri ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Export Diagnostics Archive"))
            }
        }
    }
    if (diagnosticsExportState.status == ChatViewModel.ChatHistoryOpStatus.FAILED) {
        Text(
            diagnosticsExportState.message ?: "Export failed",
            color = Color(0xFFFF3B30),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
    SettingsFooter(stringResource(R.string.exports_app_device_info_connection_settings))
}

/**
 * Kaspa-logo total-balance row — Android port of iOS's BalanceToolbarLabel
 * (SettingsView.swift): 15dp Kaspa logo + full 8-decimal balance in bold with
 * tabular figures and secondary color, so the header balance reads identically
 * on every main page (Chats/Settings via TopStatusBar; Broadcasts, Cold Storage,
 * Swap, Portfolio and KaPosts via BalanceTopBarLabel below).
 */
@Composable
fun BalanceLabelRow(balance: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            painterResource(R.drawable.ic_kaspa_logo),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = balance,
            color = LocalAppColors.current.textSecondary,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                fontFeatureSettings = "tnum"
            )
        )
    }
}

/**
 * Self-contained variant for screens that don't already collect the wallet balance:
 * observes WalletViewModel.fullBalance and refreshes it once on appearance (matches
 * the iOS label's `.task { refreshBalance() }`).
 */
@Composable
fun BalanceTopBarLabel(
    modifier: Modifier = Modifier,
    walletViewModel: WalletViewModel = hiltViewModel()
) {
    val balance by walletViewModel.fullBalance.collectAsState()
    LaunchedEffect(Unit) { walletViewModel.refreshBalance() }
    BalanceLabelRow(balance, modifier)
}

@Composable
fun TopStatusBar(
    balance: String,
    onStatusClick: () -> Unit,
    onAddClick: () -> Unit = {},
    dotColorHex: Long = 0xFF4CD964,
    // Chats has this moved to a floating action button instead (see ChatsScreen.kt), and
    // Profile/Settings have no "add chat" action of their own - every current caller passes
    // false explicitly, so this default only matters for a future screen that doesn't.
    showAddButton: Boolean = true,
    showEditButton: Boolean = false,
    isEditing: Boolean = false,
    onEditClick: () -> Unit = {},
    selectAllLabel: String? = null,
    onSelectAllClick: () -> Unit = {},
    // Profile-only (matches iOS's gear button in ProfileView's toolbar) — Settings isn't a
    // bottom-tab destination, this is its only entry point.
    showSettingsButton: Boolean = false,
    onSettingsClick: () -> Unit = {}
) {
    val statusColor = Color(dotColorHex)

    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(
            onClick = onStatusClick,
            modifier = Modifier
                .size(40.dp)
                .background(LocalAppColors.current.surface, CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(statusColor, CircleShape)
            )
        }

        BalanceLabelRow(balance)

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isEditing && selectAllLabel != null) {
                TextButton(onClick = onSelectAllClick) {
                    Text(selectAllLabel, color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(4.dp))
            }
            if (showEditButton) {
                // Text toggle instead of a pen icon, matching iOS's "Select"/"Cancel" toolbar
                // button (ChatListView.swift) rather than a Material edit-pencil affordance.
                TextButton(onClick = onEditClick) {
                    Text(
                        text = if (isEditing) "Cancel" else "Select",
                        color = KaspaTeal,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                if (!isEditing) Spacer(Modifier.width(8.dp))
            }
            if (!isEditing) {
                if (showAddButton) {
                    IconButton(
                        onClick = onAddClick,
                        modifier = Modifier
                            .size(40.dp)
                            .background(LocalAppColors.current.surface, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonAddAlt1,
                            contentDescription = stringResource(R.string.add_contact),
                            tint = KaspaTeal,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else if (showSettingsButton) {
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier
                            .size(40.dp)
                            .background(LocalAppColors.current.surface, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
                            tint = KaspaTeal,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else if (!showEditButton) {
                    // Keeps the balance text centered between the two ends, same as when the button is shown.
                    Spacer(modifier = Modifier.size(40.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionStatusScreen(onBack: () -> Unit, viewModel: ConnectionViewModel = hiltViewModel()) {
    val network by viewModel.network.collectAsState()
    val indexerUrl by viewModel.indexerUrl.collectAsState()

    val activeNodes by viewModel.activeNodes.collectAsState()
    val allNodes by viewModel.allNodes.collectAsState()
    // Pool sections only make sense when the POOL is choosing the node. Pinned to a specific
    // node (the shipped default or the user's own), there is no pool at work - discovery is
    // off and the app only ever talks to that one address - so pool status, pool actions, and
    // the node lists would just show a pool of one. Null (not yet loaded) hides them too, so
    // a pinned user never sees them flash on screen entry.
    val nodeSelectionIsAutomatic by viewModel.nodeSelectionIsAutomatic.collectAsState()
    val showPoolSections = nodeSelectionIsAutomatic == true
    // "Other Nodes" means genuinely other than what's already listed above under Active
    // Nodes — allNodes includes every known node (active and not), so without this filter
    // the same active nodes showed up twice, once in each section.
    val otherNodes = remember(allNodes) { allNodes.filterNot { it.status == "Active" } }
    val status by viewModel.status.collectAsState()
    val dotColorHex by viewModel.dotColorHex.collectAsState()
    val lastSyncAt by viewModel.lastSyncAt.collectAsState()
    val nodeConnectionsBlocked by viewModel.nodeConnectionsBlocked.collectAsState()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // status is derived from the exact same latency threshold as dotColorHex, so
    // the text here can never contradict the dot's color: green only says
    // Connected/Healthy, orange only says Degraded, red only says Disconnected/Unhealthy.
    val statusColor = Color(dotColorHex)
    val statusText = when (status) {
        ConnStatus.CONNECTED -> "Connected"
        ConnStatus.DEGRADED -> "Degraded"
        ConnStatus.DISCONNECTED -> "Disconnected"
    }
    val poolHealthText = when (status) {
        ConnStatus.CONNECTED -> "Healthy"
        ConnStatus.DEGRADED -> "Degraded"
        ConnStatus.DISCONNECTED -> "Unhealthy"
    }
    // "Verified" = currently reachable at all (Active or Suspect), a broader real count
    // than "Active" (Active additionally requires being in-sync and not recently failing).
    // Excludes nodes that haven't been probed even once yet (latency == "—") — those also
    // report as "Suspect" (see NodeRegistry.statusOf's lastProbe == null branch), but counting
    // them as "Verified" is misleading: right after a fresh launch/DNS-seed resolution this made
    // the whole pool look "Verified" before a single probe had actually completed.
    val verifiedCount = remember(allNodes) { allNodes.count { (it.status == "Active" || it.status == "Suspect") && it.latency != "—" } }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.connection_status), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.done), color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsSection(title = stringResource(R.string.connection_status)) {
                ConnectionInfoRow(stringResource(R.string.status), statusText, statusColor)
                SettingsDivider()
                ConnectionInfoRow(
                    stringResource(R.string.protocol),
                    if (activeNodes.firstOrNull()?.ip?.let { com.kachat.app.services.grpc.parseNodeAddress(it)?.secure } == true) {
                        "gRPC (secure)"
                    } else {
                        "gRPC (plaintext)"
                    }
                )
                SettingsDivider()
                ConnectionInfoRow(stringResource(R.string.connected_node), activeNodes.firstOrNull()?.ip ?: "None")
                SettingsDivider()
                ConnectionInfoRow(stringResource(R.string.latency), activeNodes.firstOrNull()?.latency ?: "—", statusColor)
                SettingsDivider()
                ConnectionInfoRow(stringResource(R.string.indexer), indexerUrl.substringAfter("://").substringBefore("/"))
                SettingsDivider()
                ConnectionInfoRow(stringResource(R.string.last_sync), lastSyncAt)
                // Honest all-blocked explanation: only shown once the pool has concluded that
                // NO node answers at all on this network (see NodePoolManager's
                // nodeConnectionsBlocked doc) - a plain red "Disconnected" with no reason
                // otherwise reads as an app bug on firewalled/captive networks.
                if (nodeConnectionsBlocked && status == ConnStatus.DISCONNECTED) {
                    SettingsDivider()
                    Text(
                        stringResource(R.string.node_connections_blocked),
                        color = Color(0xFFF39C12),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            if (showPoolSections) {
                SettingsSection(title = stringResource(R.string.pool_status)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        PoolStatItem(stringResource(R.string.active), activeNodes.size.toString(), Color(0xFF4CD964))
                        PoolStatItem(stringResource(R.string.verified), verifiedCount.toString(), Color(0xFF2196F3))
                        PoolStatItem(stringResource(R.string.total), allNodes.size.toString(), Color.Gray)
                    }
                    SettingsDivider()
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.pool_health), color = LocalAppColors.current.textPrimary)
                        Text(poolHealthText, color = statusColor)
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.actions)) {
                // Refresh/Clear operate on pool discovery, which is off while pinned to a
                // specific node - only Reconnect still means something there.
                if (showPoolSections) {
                    SettingsActionItem(stringResource(R.string.refresh_pool), Icons.Default.Refresh, KaspaTeal, onClick = {
                        viewModel.refreshPool()
                        coroutineScope.launch { snackbarHostState.showSnackbar("Refreshing pool…") }
                    })
                    SettingsDivider()
                    SettingsActionItem(stringResource(R.string.clear_connection_pool), Icons.Default.DeleteSweep, Color.Red, onClick = {
                        viewModel.clearPool()
                        coroutineScope.launch { snackbarHostState.showSnackbar("Pool cleared, reconnecting to seed nodes") }
                    })
                    SettingsDivider()
                }
                SettingsActionItem(stringResource(R.string.reconnect), Icons.Default.Replay, KaspaTeal, onClick = {
                    viewModel.reconnect()
                    coroutineScope.launch { snackbarHostState.showSnackbar("Reconnecting…") }
                })
            }

            KaspaNodeQuickAccessSection(viewModel)

            if (showPoolSections) {
                Text(
                    text = "Primary: ${activeNodes.firstOrNull()?.ip ?: "None"}",
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(Color(0xFF4CD964), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(text = stringResource(R.string.active_nodes), style = MaterialTheme.typography.titleMedium, color = LocalAppColors.current.textPrimary)
                    }
                    Text(text = activeNodes.size.toString(), color = LocalAppColors.current.textSecondary)
                }
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LocalAppColors.current.surface)
                ) {
                    activeNodes.forEachIndexed { index, node ->
                        ActiveNodeRow(node)
                        if (index < activeNodes.size - 1) SettingsDivider()
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(Color.Gray, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(text = stringResource(R.string.other_nodes), style = MaterialTheme.typography.titleMedium, color = LocalAppColors.current.textPrimary)
                    }
                    Text(text = otherNodes.size.toString(), color = LocalAppColors.current.textSecondary)
                }
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LocalAppColors.current.surface)
                ) {
                    otherNodes.forEachIndexed { index, node ->
                        AllNodeRow(node)
                        if (index < otherNodes.size - 1) SettingsDivider()
                    }
                }

                Text(
                    text = stringResource(R.string.all_discovered_nodes_sorted_by_state),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalAppColors.current.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }


            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

/** Read-only label/value row for the push-diagnostics section of [NotificationSettingsScreen]. */
@Composable
private fun PushDiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = LocalAppColors.current.textPrimary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        Text(
            text = value,
            color = LocalAppColors.current.textSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()
    val soundEnabled by viewModel.notificationSoundEnabled.collectAsState()
    val vibrationEnabled by viewModel.notificationVibrationEnabled.collectAsState()
    val pushActive by viewModel.pushActive.collectAsState()
    val pushDiag by viewModel.pushDiagnostics.collectAsState()

    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                // "Chats" since 4.0: this page is the Chats subpage of the top-level
                // Notifications hub (Settings > Notifications > Chats), matching iOS.
                title = { Text(stringResource(R.string.chats), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = LocalAppColors.current.textPrimary, modifier = Modifier.size(20.dp))
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            if (!permissionGranted) {
                Surface(
                    color = Color(0xFF2C1C1C),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.notifications_are_off_in_system_settings), color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.kachat_can_t_show_notifications_until),
                            color = LocalAppColors.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                )
                            }
                        }) {
                            Text(stringResource(R.string.open_settings), color = KaspaTeal, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.push_notifications)) {
                SettingsSwitchItem(stringResource(R.string.notifications), notificationsEnabled && permissionGranted) {
                    viewModel.setNotificationsEnabled(it)
                }
                SettingsFooter(
                    if (notificationsEnabled && permissionGranted)
                        "Messages, broadcasts, and KaPosts notifications are delivered by the KaChat push service, including while the app is closed. Group notifications are checked in the background about every 15 minutes."
                    else
                        "Notifications are disabled."
                )
            }

            if (notificationsEnabled && permissionGranted) {
                SettingsSection(title = stringResource(R.string.sound_vibration)) {
                    SettingsSwitchItem(stringResource(R.string.play_sound), soundEnabled) {
                        viewModel.setNotificationSoundEnabled(it)
                    }
                    SettingsDivider()
                    SettingsSwitchItem(stringResource(R.string.vibration), vibrationEnabled) {
                        viewModel.setNotificationVibrationEnabled(it)
                    }
                }
            }

            // Read-only push diagnostics — background delivery for DMs/broadcasts/KaPosts has no
            // polling fallback, so when pushes don't arrive this is the first place to look
            // (paired with `adb logcat -s KaChatPush` for the full story).
            SettingsSection(title = "Push diagnostics") {
                PushDiagnosticRow("Remote push active", if (pushActive) "Yes" else "No")
                SettingsDivider()
                PushDiagnosticRow(
                    "FCM token",
                    when {
                        pushDiag.fcmTokenPresent -> "Present"
                        pushDiag.lastAttemptAtMs == null -> "—"
                        else -> "Missing"
                    }
                )
                SettingsDivider()
                PushDiagnosticRow(
                    "Last registration",
                    pushDiag.lastAttemptAtMs?.let { ts ->
                        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                            .format(java.util.Date(ts))
                        val outcome = if (pushDiag.lastAttemptSucceeded == true) "OK" else "failed"
                        "${pushDiag.lastAction ?: "attempt"} $outcome · $time"
                    } ?: "None yet"
                )
                SettingsFooter(
                    pushDiag.lastError?.let { "Last error: $it" }
                        ?: "Delivery problems? Capture logs with: adb logcat -s KaChatPush"
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

/**
 * Settings > Notifications > Wallet — the Address Activity toggle (default ON). Gates
 * [com.kachat.app.services.AddressActivityNotifier]'s local notifications for external receipts
 * on any spending or cold storage address. Mirrors iOS's WalletNotificationSettingsView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletNotificationSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val addressActivityEnabled by viewModel.addressActivityNotificationsEnabled.collectAsState()
    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Wallet", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = LocalAppColors.current.textPrimary, modifier = Modifier.size(20.dp))
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            SettingsSection(title = null) {
                SettingsSwitchItem("Address Activity", addressActivityEnabled) {
                    viewModel.setAddressActivityNotificationsEnabled(it)
                }
                SettingsFooter("Notify when any of your spending or cold storage addresses receives Kaspa from an external source. Transfers between your own addresses are ignored.")
            }
        }
    }
}

/**
 * Settings > Notifications > KaPosts — five default-ON toggles choosing which KaPosts activity
 * kinds post a notification. Filtering happens at the poll source
 * ([com.kachat.app.services.KaPostsNotificationPoller]) via the K API contentType/voteType
 * mapping (vote+downvote = dislike, vote = like, reply = comment, quote = repost,
 * follow = follow). Mirrors iOS's KaPostsNotificationSettingsView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KaPostsNotificationSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val likes by viewModel.kaPostsNotifyLikes.collectAsState()
    val reposts by viewModel.kaPostsNotifyReposts.collectAsState()
    val follows by viewModel.kaPostsNotifyFollows.collectAsState()
    val dislikes by viewModel.kaPostsNotifyDislikes.collectAsState()
    val comments by viewModel.kaPostsNotifyComments.collectAsState()
    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("KaPosts", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = LocalAppColors.current.textPrimary, modifier = Modifier.size(20.dp))
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            SettingsSection(title = null) {
                SettingsSwitchItem("Likes", likes) { viewModel.setKaPostsNotifyLikes(it) }
                SettingsDivider()
                SettingsSwitchItem("Reposts", reposts) { viewModel.setKaPostsNotifyReposts(it) }
                SettingsDivider()
                SettingsSwitchItem("Follows", follows) { viewModel.setKaPostsNotifyFollows(it) }
                SettingsDivider()
                SettingsSwitchItem("Dislikes", dislikes) { viewModel.setKaPostsNotifyDislikes(it) }
                SettingsDivider()
                SettingsSwitchItem("Comments", comments) { viewModel.setKaPostsNotifyComments(it) }
                SettingsFooter("Choose which KaPosts activity sends a notification. Quotes of your posts count as reposts.")
            }
        }
    }
}

/**
 * Global default photo-compression quality for chat photos — mirrors iOS's
 * `PhotoQualitySettingsSheet`/`ChatPhotoQualitySlider`. Writes take effect immediately (no
 * separate Save step), matching every other row in [SettingsScreen]; only affects photos attached
 * after the change, never a photo already staged in a chat's composer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoQualitySettingsScreen(onBack: () -> Unit, chatViewModel: ChatViewModel = hiltViewModel()) {
    val preset by chatViewModel.chatPhotoQualityPreset.collectAsState()
    val presets = com.kachat.app.models.ChatPhotoQualityPreset.entries
    val sliderPosition = presets.indexOf(preset).coerceAtLeast(0).toFloat()

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.photo_quality), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = LocalAppColors.current.textPrimary, modifier = Modifier.size(20.dp))
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                stringResource(R.string.controls_how_much_photos_are_compressed),
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            SettingsSection(title = stringResource(R.string.chats)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.photo_quality_2), color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodyLarge)
                        Text(preset.summaryText, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = sliderPosition,
                        onValueChange = {
                            chatViewModel.updateChatPhotoQualityPreset(
                                com.kachat.app.models.ChatPhotoQualityPreset.fromSliderValue(it.toInt())
                            )
                        },
                        valueRange = 0f..(presets.size - 1).toFloat(),
                        steps = presets.size - 2,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = KaspaTeal,
                            inactiveTrackColor = Color.Gray
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

/** Settings > Chats > Quick Reactions - lets the user replace any of the 6 emojis shown in the
 *  double-tap [QuickReactionBar]. Each slot is a single-emoji text field, pre-selected on focus
 *  so tapping the system keyboard's emoji key naturally replaces it rather than appending -
 *  matches iOS's `QuickReactionEmojisSettingsView` (a grid picker there, since iOS's system
 *  emoji keyboard isn't as immediately reachable as Android's dedicated emoji key). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickReactionSettingsScreen(onBack: () -> Unit, settingsViewModel: SettingsViewModel = hiltViewModel()) {
    val emojis by settingsViewModel.quickReactionEmojis.collectAsState()
    var editingSlotIndex by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Quick Reactions", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = LocalAppColors.current.textPrimary, modifier = Modifier.size(20.dp))
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                "Tap a slot to replace it with a different emoji.",
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                emojis.forEachIndexed { index, emoji ->
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(LocalAppColors.current.surface)
                            .clickable { editingSlotIndex = index },
                        contentAlignment = Alignment.Center
                    ) {
                        // A plain Text sized to fill the box (rather than an OutlinedTextField,
                        // which was clipping the emoji glyph - its decoration box's own internal
                        // padding/min-line-height left too little room at 26sp) renders the full
                        // glyph cleanly and doubles as this slot's tap target.
                        Text(emoji, fontSize = 28.sp)
                    }
                }
            }

            TextButton(onClick = {
                settingsViewModel.setQuickReactionEmojis(com.kachat.app.repository.AppSettingsRepository.DEFAULT_QUICK_REACTION_EMOJIS)
            }) {
                Text("Reset to Default", color = Color(0xFFFF3B30))
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    val slotIndex = editingSlotIndex
    if (slotIndex != null) {
        EmojiPickerDialog(
            onDismissRequest = { editingSlotIndex = null },
            onSelect = { emoji ->
                settingsViewModel.setQuickReactionEmojis(
                    emojis.toMutableList().also { it[slotIndex] = emoji }
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KaspaExplorerSettingsScreen(onBack: () -> Unit, chatViewModel: ChatViewModel = hiltViewModel()) {
    val kaspaExplorer by chatViewModel.kaspaExplorer.collectAsState()

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.kaspa_explorer), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = LocalAppColors.current.textPrimary, modifier = Modifier.size(20.dp))
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                stringResource(R.string.transaction_links_in_message_menus_and),
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            SettingsSection(title = stringResource(R.string.explorer)) {
                com.kachat.app.models.KaspaExplorer.entries.forEachIndexed { index, explorer ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chatViewModel.updateKaspaExplorer(explorer) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(explorer.displayName, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (kaspaExplorer == explorer) {
                            Icon(Icons.Default.Check, null, tint = KaspaTeal)
                        }
                    }
                    if (index < com.kachat.app.models.KaspaExplorer.entries.lastIndex) {
                        SettingsDivider()
                    }
                }
            }
        }
    }
}

/**
 * A single dropdown for quickly picking which node to connect to: the default node (recommended),
 * automatic discovery, or any address already saved in the IP Address Book. Used by both
 * [ConnectionStatusScreen] (tap the status dot) and [ConnectionSettingsScreen] (above the
 * [AddressBookSection], which is where the address book itself is managed - adding, removing, and
 * labeling entries) - there's no free-text entry anymore, only this dropdown.
 */
@Composable
private fun KaspaNodeQuickAccessSection(viewModel: ConnectionViewModel) {
    val trustedNodeAddress by viewModel.trustedNodeAddress.collectAsState()
    val savedNodeAddresses by viewModel.savedNodeAddresses.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    val normalizedTrusted = trustedNodeAddress.trim()
    val defaultAddress = com.kachat.app.repository.AppSettingsRepository.DEFAULT_TRUSTED_NODE_ADDRESS
    val matchingSaved = savedNodeAddresses.firstOrNull { it.address.trim() == normalizedTrusted }

    val selectedLabel = when {
        normalizedTrusted.isEmpty() -> stringResource(R.string.automatic_scan)
        normalizedTrusted == defaultAddress.trim() -> stringResource(R.string.default_recommended)
        matchingSaved != null -> matchingSaved.label.ifBlank { matchingSaved.address }
        else -> normalizedTrusted
    }

    SettingsSection(title = stringResource(R.string.kaspa_node)) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    selectedLabel,
                    color = LocalAppColors.current.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ArrowDropDown, null, tint = LocalAppColors.current.textSecondary)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.default_recommended)) },
                    onClick = {
                        expanded = false
                        viewModel.setTrustedNodeAddress(defaultAddress)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.automatic_scan)) },
                    onClick = {
                        expanded = false
                        viewModel.setTrustedNodeAddress("")
                    }
                )
                savedNodeAddresses.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(entry.label.ifBlank { entry.address }) },
                        onClick = {
                            expanded = false
                            viewModel.setTrustedNodeAddress(entry.address)
                        }
                    )
                }
            }
        }
        if (normalizedTrusted.isNotEmpty()) {
            SettingsDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.connected_only_to_this_node), color = KaspaTeal, fontSize = 13.sp)
            }
        }
        SettingsFooter(stringResource(R.string.kaspa_node_quick_access_footer))
    }
}

/**
 * "IP Address Book" - the full management UI (add/remove/label saved addresses), used only by
 * [ConnectionSettingsScreen], right below its own [KaspaNodeQuickAccessSection]. The trusted-node
 * override itself is now dropdown-only (default/automatic/a saved address) - no free-text entry -
 * so this section is just the address book, feeding entries into that dropdown.
 */
@Composable
private fun AddressBookSection(viewModel: ConnectionViewModel) {
    val savedNodeAddresses by viewModel.savedNodeAddresses.collectAsState()

    SettingsSection(title = stringResource(R.string.ip_address_book)) {
        var newLabel by remember { mutableStateOf("") }
        var newAddress by remember { mutableStateOf("") }
        var addError by remember { mutableStateOf<String?>(null) }
        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current

        TextField(
            value = newLabel,
            onValueChange = { newLabel = it },
            placeholder = { Text(stringResource(R.string.label_optional), color = Color.DarkGray) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(50.dp).clip(RoundedCornerShape(12.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = LocalAppColors.current.surfaceVariant,
                unfocusedContainerColor = LocalAppColors.current.surfaceVariant,
                focusedTextColor = LocalAppColors.current.textPrimary,
                unfocusedTextColor = LocalAppColors.current.textPrimary,
                cursorColor = KaspaTeal,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = newAddress,
                onValueChange = { newAddress = it; addError = null },
                placeholder = { Text(stringResource(R.string.host_port_or_grpcs_host), color = Color.DarkGray) },
                modifier = Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(12.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = LocalAppColors.current.surfaceVariant,
                    unfocusedContainerColor = LocalAppColors.current.surfaceVariant,
                    focusedTextColor = LocalAppColors.current.textPrimary,
                    unfocusedTextColor = LocalAppColors.current.textPrimary,
                    cursorColor = KaspaTeal,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
            Spacer(Modifier.width(12.dp))
            IconButton(
                onClick = {
                    val trimmed = newAddress.trim()
                    if (trimmed.isEmpty()) return@IconButton
                    if (com.kachat.app.services.grpc.parseNodeAddress(trimmed) == null) {
                        addError = "Enter as host:port or grpcs://host"
                    } else {
                        addError = null
                        viewModel.addSavedNodeAddress(newLabel.trim(), trimmed)
                        newLabel = ""
                        newAddress = ""
                    }
                },
                modifier = Modifier.size(40.dp).background(KaspaTeal, CircleShape)
            ) {
                Icon(Icons.Default.Add, null, tint = Color.Black)
            }
        }
        addError?.let {
            Text(it, color = Color(0xFFFF3B30), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        if (savedNodeAddresses.isEmpty()) {
            Text(
                stringResource(R.string.no_saved_addresses),
                color = LocalAppColors.current.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            savedNodeAddresses.forEachIndexed { index, entry ->
                if (index > 0) SettingsDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboardManager.setText(AnnotatedString(entry.address))
                            showAddressCopiedToast(context, entry.address)
                        }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        if (entry.label.isNotBlank()) {
                            Text(entry.label, color = LocalAppColors.current.textPrimary)
                            Text(
                                entry.address,
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            Text(entry.address, color = LocalAppColors.current.textPrimary, fontFamily = FontFamily.Monospace)
                        }
                    }
                    // Immediate delete (matching this screen's convention: row taps copy with a
                    // toast, no confirm dialogs anywhere), so a toast confirms the removal.
                    // Deleting the entry the Kaspa Node dropdown currently pins does NOT touch
                    // node selection: only the book entry goes, the pinned address stays set
                    // and the dropdown falls back to showing the raw address (see
                    // KaspaNodeQuickAccessSection's selectedLabel).
                    IconButton(onClick = {
                        viewModel.removeSavedNodeAddress(entry.id)
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.address_removed_from_book),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(Icons.Default.Delete, null, tint = LocalAppColors.current.textSecondary)
                    }
                }
            }
        }
        SettingsFooter(stringResource(R.string.save_your_own_node_addresses_here))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(onBack: () -> Unit, viewModel: ConnectionViewModel = hiltViewModel()) {
    val network by viewModel.network.collectAsState()
    val indexerUrl by viewModel.indexerUrl.collectAsState()
    val knsApiUrl by viewModel.knsApiUrl.collectAsState()
    val kaspaRestApiUrl by viewModel.kaspaRestApiUrl.collectAsState()
    val kapostIndexerUrl by viewModel.kapostIndexerUrl.collectAsState()
    val broadcastIndexerUrl by viewModel.broadcastIndexerUrl.collectAsState()
    val pushIndexerUrl by viewModel.pushIndexerUrl.collectAsState()
    val verboseApiLogging by viewModel.verboseApiLogging.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.connection_settings), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = LocalAppColors.current.textPrimary, modifier = Modifier.size(20.dp))
                    }
                },
                actions = {
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.surfaceVariant),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp).padding(end = 8.dp)
                    ) {
                        Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.Bold)
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
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsSection(title = stringResource(R.string.kachat_indexer)) {
                ConnectionUrlField(label = "Indexer URL", value = indexerUrl)
                SettingsFooter(stringResource(R.string.message_indexer_service_for_chat_functionality))
            }


            SettingsSection(title = stringResource(R.string.kapost_indexer)) {
                ConnectionUrlField(label = "KaPost Indexer URL", value = kapostIndexerUrl)
                SettingsFooter(stringResource(R.string.kapost_indexer_footer))
            }

            SettingsSection(title = stringResource(R.string.broadcast_indexer)) {
                ConnectionUrlField(label = "Broadcast Indexer URL", value = broadcastIndexerUrl)
                SettingsFooter(stringResource(R.string.broadcast_indexer_footer))
            }

            SettingsSection(title = "Push Registration") {
                ConnectionUrlField(label = "Push Indexer URL", value = pushIndexerUrl)
                SettingsFooter("Host this device registers with for native push notifications (FCM). Defaults to the KaChat indexer.")
            }

            SettingsSection(title = stringResource(R.string.kaspa_name_service)) {
                ConnectionUrlField(label = "KNS API URL", value = knsApiUrl)
                SettingsFooter(stringResource(R.string.kns_domain_resolution_service))
            }

            SettingsSection(title = stringResource(R.string.kaspa_explorer_api)) {
                ConnectionUrlField(label = "Kaspa REST API URL", value = kaspaRestApiUrl)
                SettingsFooter(stringResource(R.string.rest_api_for_transaction_history_and))
            }

            KaspaNodeQuickAccessSection(viewModel)
            AddressBookSection(viewModel)

            SettingsSection(title = "Diagnostics") {
                SettingsSwitchItem(
                    label = "Verbose API Logging",
                    checked = verboseApiLogging,
                    onCheckedChange = { viewModel.setVerboseApiLogging(it) }
                )
                SettingsFooter("Logs every API request and response to the system log. Failures and slow requests are always logged. Leave off for normal use.")
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

/** Read-only for now — editing these let a mistyped URL crash the whole app (fixed at the NetworkService layer too, but not editable at all is safer). */
@Composable
fun ConnectionUrlField(label: String, value: String) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(label, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            color = LocalAppColors.current.textPrimary,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun ConnectionInfoRow(label: String, value: String, valueColor: Color = LocalAppColors.current.textSecondary) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (label == "Status") {
                Box(modifier = Modifier.size(8.dp).background(valueColor, CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun PoolStatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodySmall)
        }
        Text(value, color = color, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ActiveNodeRow(node: NodeInfo) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // weight(1f) here (not on the status badge) so a long address — real discovered
            // peers can be IPv6 literals like [2601:680:cc80:5630:e1e5:e6fa:c86b:b946]:16111,
            // much longer than the old hardcoded IPv4 seeds — elides instead of squeezing the
            // badge down to near-zero width, which forced its own text to wrap letter by letter.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Shield, null, tint = Color(0xFF2196F3), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(node.ip, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.background(Color(0xFF1E3A1E), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(node.status, color = Color(node.color), fontSize = 10.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Row {
                Text(node.type, color = Color(0xFF2196F3), fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Text(node.latency, color = Color(0xFFF39C12), fontSize = 10.sp)
            }
            Text("DAA: ${node.daaScore}", color = LocalAppColors.current.textSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
fun AllNodeRow(node: NodeInfo) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // weight(1f) here (not on the latency/status side) so a long address — real discovered
        // peers can be IPv6 literals like [2601:680:cc80:5630:e1e5:e6fa:c86b:b946]:16111, much
        // longer than the old hardcoded IPv4 seeds — elides instead of squeezing the status
        // badge down to near-zero width, which forced its own text to wrap letter by letter.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.size(8.dp).background(Color(node.color), CircleShape))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Shield, null, tint = Color(0xFF2196F3), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(node.ip, color = LocalAppColors.current.textPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(node.latency, color = Color(0xFFF39C12), fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.background(Color(0x33FF3B30), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(node.status, color = Color(node.color), fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun rememberQrBitmapPainter(
    content: String,
    size: Int = 512,
    padding: Int = 0
): BitmapPainter {
    val density = LocalDensity.current
    val sizePx = with(density) { size.dp.roundToPx() }
    
    val bitmap = remember(content) {
        if (content.isEmpty()) {
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
        } else {
            val matrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                mapOf(EncodeHintType.MARGIN to padding)
            )
            val w = matrix.width
            val h = matrix.height
            // One setPixels() beats width*height individual bounds-checked setPixel() calls
            // (~262k for a 512px QR) by 1-2 orders of magnitude - this runs on the main thread
            // inside remember{}, and the animated (KSPT) variant re-encodes every 2.5s.
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    pixels[row + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
                }
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.asImageBitmap()
        }
    }
    return BitmapPainter(bitmap)
}

/**
 * Byte-mode QR encoding for raw binary payloads (e.g. a KSPT transaction frame) — ISO-8859-1 maps
 * every byte 1:1 to a char and back, so wrapping [bytes] this way and forcing ZXing's
 * `CHARACTER_SET` hint round-trips the exact bytes through its String-based encoder in byte mode,
 * matching KasSigner's own raw-byte QR encoding (`qrcode::QrCode::new(&[u8])`) on the other end.
 */
@Composable
fun rememberQrBitmapPainter(
    bytes: ByteArray,
    size: Int = 512,
    padding: Int = 0
): BitmapPainter {
    val density = LocalDensity.current
    val sizePx = with(density) { size.dp.roundToPx() }

    val bitmap = remember(bytes) {
        if (bytes.isEmpty()) {
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
        } else {
            val content = String(bytes, Charsets.ISO_8859_1)
            val matrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                mapOf(EncodeHintType.MARGIN to padding, EncodeHintType.CHARACTER_SET to "ISO-8859-1")
            )
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    pixels[row + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
                }
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            bmp.asImageBitmap()
        }
    }
    return BitmapPainter(bitmap)
}

/**
 * Cycles through [frames] at a fixed interval — the display half of KasSigner's animated multi-
 * frame QR protocol (see [com.kachat.app.util.QrFrameChunker]). A single-frame list just renders
 * a static code with no play/pause controls or frame counter.
 */
@Composable
fun AnimatedQrDisplay(frames: List<ByteArray>, modifier: Modifier = Modifier, frameDelayMs: Long = 2500L) {
    var frameIndex by remember(frames) { mutableStateOf(0) }
    // Matches KasSee's own 2.5s auto-advance (kassee/web/js/app.js's displayKsptQr) — a scanning
    // camera needs real time to lock onto and decode each frame; a faster cycle (this used to
    // default to 200ms) skips past frames before the scanner ever catches them.
    var isPlaying by remember(frames) { mutableStateOf(true) }

    LaunchedEffect(frames, isPlaying) {
        if (frames.size <= 1 || !isPlaying) return@LaunchedEffect
        while (true) {
            delay(frameDelayMs)
            frameIndex = (frameIndex + 1) % frames.size
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        val painter = rememberQrBitmapPainter(bytes = frames[frameIndex.coerceIn(frames.indices)])
        Box(
            modifier = Modifier
                .size(280.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .border(2.dp, KaspaTeal, RoundedCornerShape(20.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(painter = painter, contentDescription = stringResource(R.string.qr_code_2), modifier = Modifier.fillMaxSize())
        }
        if (frames.size > 1) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                frames.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            // Fixed, not theme-based — this always sits on a forced-white card
                            // (see ColdSendFlow), so the inactive dot needs to read against white
                            // specifically rather than whatever the app's own surfaceVariant is.
                            .background(if (i == frameIndex) KaspaTeal else Color(0xFFD0D0D5))
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Manual stepping works whether playing or paused — tapping prev/next doesn't
                // require pausing first, matching KasSee's own frame-nav buttons.
                IconButton(onClick = { frameIndex = (frameIndex - 1 + frames.size) % frames.size }) {
                    Icon(Icons.Default.SkipPrevious, "Previous frame", tint = KaspaTeal)
                }
                IconButton(onClick = { isPlaying = !isPlaying }) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        tint = KaspaTeal
                    )
                }
                IconButton(onClick = { frameIndex = (frameIndex + 1) % frames.size }) {
                    Icon(Icons.Default.SkipNext, "Next frame", tint = KaspaTeal)
                }
                Text("Frame ${frameIndex + 1} / ${frames.size}", color = Color(0xFF6B6B70), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Full-bleed QR overlay over the current screen's content area — matches iOS's push-navigated
 *  QR screens (full-screen white, dismissed via a back arrow/system back, never by a stray tap).
 *  Tapping anywhere on the overlay content copies the address — the whole screen is the copy
 *  target, so there's no dedicated copy button, just the quiet hint under the address. [message],
 *  if given, renders as a caption below the code (e.g. funding guidance). */
@Composable
fun QrCodeOverlay(
    value: String,
    onDismiss: () -> Unit,
    message: String? = null,
    borderColor: Color = KaspaTeal,
    borderWidth: Dp = 2.dp
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    BackHandler(onBack = onDismiss)

    // Full-screen white, not the app's own themed background — a bright, high-contrast quiet
    // zone around the code is what actually gets a reliable scan on another device's camera,
    // regardless of whether KaChat itself is in light or dark mode.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            // The entire overlay is the copy target (no ripple — the whole screen flashing on
            // tap would look broken). The back arrow below still consumes its own taps, so
            // dismissal stays on the arrow / system back only.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                clipboardManager.setText(AnnotatedString(value))
                com.kachat.app.util.showAddressCopiedToast(context, value)
            },
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(4.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.Black)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val qrPainter = rememberQrBitmapPainter(value)
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .border(borderWidth, borderColor, RoundedCornerShape(20.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = qrPainter,
                    contentDescription = stringResource(R.string.qr_code),
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = value,
                color = Color.Black,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.tap_anywhere_to_copy),
                color = Color(0xFF9A9AA0),
                style = MaterialTheme.typography.bodySmall
            )
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    color = Color(0xFF6B6B70),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateChatScreen(
    onBack: () -> Unit,
    onChatCreated: (String) -> Unit,
    onGroupCreated: (String) -> Unit = {},
    startInGroupMode: Boolean = false,
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }

    // Group chat mode. The create button is tab-aware (Chats vs Group Chats), so the screen
    // opens directly in the right mode instead of exposing a toggle.
    var isGroupMode by remember { mutableStateOf(startInGroupMode) }
    var groupName by remember { mutableStateOf("") }
    var groupAddressRows by remember { mutableStateOf(listOf(GroupAddressRow())) }
    // New group flow: members are picked from existing contacts (searchable), not typed.
    var selectedMemberAddresses by remember { mutableStateOf(setOf<String>()) }
    var memberSearchText by remember { mutableStateOf("") }
    var scanningGroupRowId by remember { mutableStateOf<String?>(null) }
    var importingGroupRowId by remember { mutableStateOf<String?>(null) }
    val isCreatingGroup by chatViewModel.isCreatingGroup.collectAsState()
    val createGroupError by chatViewModel.createGroupError.collectAsState()
    var importErrorMessage by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val isValidRawAddress = remember(address) { KaspaAddress.isValid(address) }
    val looksLikeKnsDomain = remember(address) { com.kachat.app.services.KnsService.looksLikeDomain(address) }

    val knsResolvedAddress by chatViewModel.knsResolvedAddress.collectAsState()
    val isResolvingKns by chatViewModel.isResolvingKns.collectAsState()
    val knsError by chatViewModel.knsError.collectAsState()
    // Existing contacts (via conversations) shown in the group member picker.
    val conversations by chatViewModel.conversations.collectAsState()

    val context = LocalContext.current
    // Reads the picked contact's data via the /entities sub-path of the URI the system picker
    // itself returns — covered by the temporary read grant that comes with that URI, so no
    // READ_CONTACTS runtime permission is needed (matches ChatInfoScreen's "Link from Contacts"
    // picker, which relies on the same grant for its own, narrower query).
    val importContactMimeTypes = setOf(
        ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
    )
    val pickContactForImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        val targetGroupRowId = importingGroupRowId
        importingGroupRowId = null
        if (uri == null) return@rememberLauncherForActivityResult
        val entityUri = Uri.withAppendedPath(uri, ContactsContract.Contacts.Entity.CONTENT_DIRECTORY)
        var foundAddress: String? = null
        var displayName: String? = null
        context.contentResolver.query(
            entityUri,
            arrayOf(
                ContactsContract.Contacts.Entity.MIMETYPE,
                ContactsContract.Contacts.Entity.DATA1,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            ),
            null, null, null
        )?.use { cursor ->
            val mimeIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.Entity.MIMETYPE)
            val dataIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.Entity.DATA1)
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (cursor.moveToNext()) {
                if (displayName == null) displayName = cursor.getString(nameIdx)
                if (foundAddress != null) continue
                val mime = cursor.getString(mimeIdx) ?: continue
                if (mime !in importContactMimeTypes) continue
                val value = cursor.getString(dataIdx) ?: continue
                foundAddress = com.kachat.app.services.SystemContactsSyncService.extractKaspaAddresses(value).firstOrNull()
            }
        }
        if (targetGroupRowId != null) {
            // Group mode: write into the row that requested the import, not the single-contact
            // address field. No inline error slot for this case besides the row's own KNS/
            // validity status line - a contact with no address just leaves the row untouched.
            if (foundAddress != null) {
                groupAddressRows = groupAddressRows.map {
                    if (it.id == targetGroupRowId) it.copy(text = foundAddress!!, knsError = null) else it
                }
            } else {
                groupAddressRows = groupAddressRows.map {
                    if (it.id == targetGroupRowId) it.copy(knsError = "No Kaspa address found in ${displayName ?: "that contact"}") else it
                }
            }
        } else if (foundAddress != null) {
            address = foundAddress!!
            if (name.isBlank()) name = displayName ?: ""
            importErrorMessage = null
        } else {
            importErrorMessage = "No Kaspa address found in ${displayName ?: "that contact"}"
        }
    }

    LaunchedEffect(address) {
        importErrorMessage = null
        chatViewModel.onCreateChatAddressChanged(address)
    }

    // The address actually used to create the contact — the resolved owner address
    // when the input is a KNS domain, otherwise whatever was typed directly.
    val effectiveAddress = if (looksLikeKnsDomain) knsResolvedAddress else address
    val isValidAddress = if (looksLikeKnsDomain) knsResolvedAddress != null else isValidRawAddress

    if (showScanner) {
        BackHandler { showScanner = false }
        QrScannerOverlay(
            onScanned = { scanned ->
                address = scanned
                showScanner = false
            },
            onDismiss = { showScanner = false }
        )
        return
    }

    scanningGroupRowId?.let { rowId ->
        BackHandler { scanningGroupRowId = null }
        QrScannerOverlay(
            onScanned = { scanned ->
                groupAddressRows = groupAddressRows.map {
                    if (it.id == rowId) it.copy(text = scanned.trim()) else it
                }
                scanningGroupRowId = null
            },
            onDismiss = { scanningGroupRowId = null }
        )
        return
    }

    val canCreateGroup = groupName.trim().isNotEmpty() && selectedMemberAddresses.isNotEmpty()
    var showCreateGroupConfirm by remember { mutableStateOf(false) }
    if (showCreateGroupConfirm) {
        val k = selectedMemberAddresses.size
        val txCount = k + 1
        val fee = chatViewModel.estimateGroupControlTxFeeSompi(1600) * txCount
        AlertDialog(
            onDismissRequest = { showCreateGroupConfirm = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Create group", color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    "Create \"${groupName.trim()}\" and invite $k member${if (k == 1) "" else "s"}?\n\nEstimated network fee ≈ ${com.kachat.app.repository.ChatRepository.formatKas(fee)} KAS across $txCount transaction${if (txCount == 1) "" else "s"}.",
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCreateGroupConfirm = false
                    chatViewModel.createGroupChat(groupName, selectedMemberAddresses.toList()) { groupId -> onGroupCreated(groupId) }
                }) { Text(stringResource(R.string.create), color = KaspaTeal, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGroupConfirm = false }) { Text(stringResource(R.string.cancel), color = LocalAppColors.current.textSecondary) }
            }
        )
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (isGroupMode) "New Group Chat" else "Create chat",
                        color = LocalAppColors.current.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.cancel), color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    if (isGroupMode) {
                        if (isCreatingGroup) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = KaspaTeal, strokeWidth = 2.dp)
                            Spacer(Modifier.width(16.dp))
                        } else {
                            TextButton(
                                onClick = { showCreateGroupConfirm = true },
                                enabled = canCreateGroup
                            ) {
                                Text(stringResource(R.string.create), color = if (canCreateGroup) KaspaTeal else Color.DarkGray, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        TextButton(
                            onClick = {
                                val resolvedAddress = effectiveAddress ?: return@TextButton
                                chatViewModel.addContact(
                                    address = resolvedAddress,
                                    name = name,
                                    knsName = if (looksLikeKnsDomain) com.kachat.app.services.KnsService.normalizeDomain(address) else null
                                )
                                onChatCreated(resolvedAddress)
                            },
                            enabled = isValidAddress
                        ) {
                            Text(stringResource(R.string.add), color = if (isValidAddress) KaspaTeal else Color.DarkGray, fontWeight = FontWeight.Bold)
                        }
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
                .let { if (isGroupMode) it.verticalScroll(rememberScrollState()) else it }
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            if (isGroupMode) {
                GroupChatCreationFields(
                    groupName = groupName,
                    onGroupNameChange = { groupName = it },
                    searchText = memberSearchText,
                    onSearchTextChange = { memberSearchText = it },
                    conversations = conversations,
                    selectedAddresses = selectedMemberAddresses,
                    onToggleMember = { address ->
                        selectedMemberAddresses = if (selectedMemberAddresses.contains(address)) {
                            selectedMemberAddresses - address
                        } else if (selectedMemberAddresses.size < MAX_GROUP_MEMBERS) {
                            selectedMemberAddresses + address
                        } else selectedMemberAddresses
                    },
                    errorMessage = createGroupError
                )

                // Add someone who is not in your contacts, by raw address or KNS domain. Reuses
                // the screen-level `address` state, its KNS resolution, and the scanner/import.
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Add by Address",
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Add anyone by Kaspa address or KNS domain, even if they are not in your contacts.",
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = { Text(stringResource(R.string.kaspa_qr_or_name_kas), color = Color.DarkGray) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = LocalAppColors.current.surface,
                        unfocusedContainerColor = LocalAppColors.current.surface,
                        focusedTextColor = LocalAppColors.current.textPrimary,
                        unfocusedTextColor = LocalAppColors.current.textPrimary,
                        cursorColor = KaspaTeal,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    singleLine = true
                )
                if (looksLikeKnsDomain && isResolvingKns) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = KaspaTeal, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.resolving_domain), color = LocalAppColors.current.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (looksLikeKnsDomain && knsError != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(knsError ?: "", color = Color(0xFFFF3B30), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (isValidAddress) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CD964), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (looksLikeKnsDomain) "Resolved to ${knsResolvedAddress?.takeLast(12)}" else stringResource(R.string.valid_address),
                            color = Color(0xFF4CD964),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    CreateChatActionItem(Icons.Default.PersonAddAlt1, "Import") {
                        pickContactForImportLauncher.launch(null)
                    }
                    CreateChatActionItem(Icons.Default.ContentPaste, "Paste") {
                        clipboardManager.getText()?.text?.let { address = it.trim() }
                    }
                    CreateChatActionItem(Icons.Default.QrCodeScanner, "Scan QR") { showScanner = true }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val resolved = effectiveAddress
                        if (resolved != null && isValidAddress && selectedMemberAddresses.size < MAX_GROUP_MEMBERS) {
                            selectedMemberAddresses = selectedMemberAddresses + resolved
                            address = ""
                        }
                    },
                    enabled = isValidAddress,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black)
                ) {
                    Text("Add to Group", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(32.dp))
                return@Column
            }

            Text(
                text = stringResource(R.string.address),
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
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.kaspa_address_or_kns_domain),
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                TextField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = { Text(stringResource(R.string.kaspa_qr_or_name_kas), color = Color.DarkGray) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = LocalAppColors.current.textPrimary,
                        unfocusedTextColor = LocalAppColors.current.textPrimary,
                        cursorColor = KaspaTeal,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    singleLine = true
                )
                
                if (looksLikeKnsDomain && isResolvingKns) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = KaspaTeal, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.resolving_domain), color = LocalAppColors.current.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (looksLikeKnsDomain && knsError != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(knsError ?: "", color = Color(0xFFFF3B30), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (looksLikeKnsDomain && knsResolvedAddress != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CD964), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Resolved to ${knsResolvedAddress?.takeLast(12)}",
                            color = Color(0xFF4CD964),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (isValidAddress) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CD964),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.valid_address),
                            color = Color(0xFF4CD964),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    CreateChatActionItem(Icons.Default.PersonAddAlt1, "Import") {
                        pickContactForImportLauncher.launch(null)
                    }
                    CreateChatActionItem(Icons.Default.ContentPaste, "Paste") {
                        clipboardManager.getText()?.text?.let { address = it.trim() }
                    }
                    CreateChatActionItem(Icons.Default.QrCodeScanner, "Scan QR") { showScanner = true }
                }

                importErrorMessage?.let { message ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(message, color = Color(0xFFFF3B30), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = stringResource(R.string.enter_a_kaspa_address_kaspa_or),
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.name_optional),
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.contact_name_2), color = Color.DarkGray) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(25.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = LocalAppColors.current.surface,
                    unfocusedContainerColor = LocalAppColors.current.surface,
                    focusedTextColor = LocalAppColors.current.textPrimary,
                    unfocusedTextColor = LocalAppColors.current.textPrimary,
                    cursorColor = KaspaTeal,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true
            )
        }
    }
}

@Composable
fun CreateChatActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = KaspaTeal, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val MAX_GROUP_MEMBERS = 50

/** One row in the group-member address list - supports both a raw Kaspa address and a KNS domain, resolved the same way the single-contact flow's address field does. */
data class GroupAddressRow(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String = "",
    val resolvedAddress: String? = null,
    val isResolvingKns: Boolean = false,
    val knsError: String? = null
) {
    val trimmedText: String get() = text.trim()
    val looksLikeDomain: Boolean get() = com.kachat.app.services.KnsService.looksLikeDomain(trimmedText)

    /** The actual address this row resolves to - resolved KNS owner address, or the raw typed/scanned address. Null while a domain hasn't resolved yet. */
    val effectiveAddress: String? get() = if (looksLikeDomain) resolvedAddress else trimmedText.ifEmpty { null }

    /**
     * Matches the single-contact flow's `isValidAddress` trust model exactly: a resolved KNS
     * domain is trusted outright (the KNS API is the source of truth for it), only a raw typed/
     * scanned/pasted address gets re-validated here. Re-running a resolved domain's address back
     * through `KaspaAddress.isValid` was the bug behind "KNS domains don't work in group mode" -
     * it isn't wrong exactly, but it's a stricter, redundant check the 1:1 flow deliberately
     * skips, and it was silently keeping "Add Address"/"Create" disabled even after a domain
     * resolved successfully.
     */
    val isValid: Boolean get() = if (looksLikeDomain) resolvedAddress != null else KaspaAddress.isValid(trimmedText)
}

@Composable
fun GroupChatCreationFields(
    groupName: String,
    onGroupNameChange: (String) -> Unit,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    conversations: List<Conversation>,
    selectedAddresses: Set<String>,
    onToggleMember: (String) -> Unit,
    errorMessage: String?
) {
    Text(
        text = stringResource(R.string.group_name),
        color = LocalAppColors.current.textPrimary,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(12.dp))
    TextField(
        value = groupName,
        onValueChange = onGroupNameChange,
        placeholder = { Text(stringResource(R.string.group_name_2), color = Color.DarkGray) },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = LocalAppColors.current.surface,
            unfocusedContainerColor = LocalAppColors.current.surface,
            focusedTextColor = LocalAppColors.current.textPrimary,
            unfocusedTextColor = LocalAppColors.current.textPrimary,
            cursorColor = KaspaTeal,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        singleLine = true
    )

    Spacer(modifier = Modifier.height(24.dp))

    // --- Members (N): collapsible list of who has been added so far ---
    var membersExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { membersExpanded = !membersExpanded }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (selectedAddresses.isEmpty()) stringResource(R.string.members) else "${stringResource(R.string.members)} (${selectedAddresses.size})",
            color = LocalAppColors.current.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (membersExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = LocalAppColors.current.textSecondary
        )
    }
    if (membersExpanded) {
        Spacer(modifier = Modifier.height(8.dp))
        if (selectedAddresses.isEmpty()) {
            Text(
                text = "No members added yet. Open Contacts below to add people.",
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            val contactsById = remember(conversations) { conversations.map { it.contact }.associateBy { it.id } }
            selectedAddresses.forEach { address ->
                val contact = contactsById[address]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ContactAvatar(
                        imageUrl = contact?.knsAvatarUrl,
                        deviceContactPhotoUri = contact?.systemContactPhotoUri,
                        backupPhotoBase64 = contact?.backupPhotoBase64,
                        fallbackText = contact?.alias ?: KaspaAddress.shortDisplay(address),
                        size = 40.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = contact?.alias ?: KaspaAddress.shortDisplay(address),
                            color = LocalAppColors.current.textPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = KaspaAddress.shortDisplay(address),
                            color = LocalAppColors.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = { onToggleMember(address) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color(0xFFFF3B30))
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // --- Contacts: collapsible search + list of people you have chatted with ---
    var contactsExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { contactsExpanded = !contactsExpanded }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Contacts",
            color = LocalAppColors.current.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (contactsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = LocalAppColors.current.textSecondary
        )
    }
    if (contactsExpanded) {
        Spacer(modifier = Modifier.height(8.dp))

        // Search box to filter the existing contacts shown below.
        TextField(
            value = searchText,
            onValueChange = onSearchTextChange,
            placeholder = { Text("Search contacts", color = Color.DarkGray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = LocalAppColors.current.textSecondary) },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = LocalAppColors.current.surface,
                unfocusedContainerColor = LocalAppColors.current.surface,
                focusedTextColor = LocalAppColors.current.textPrimary,
                unfocusedTextColor = LocalAppColors.current.textPrimary,
                cursorColor = KaspaTeal,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        val query = searchText.trim().lowercase()
        val contacts = remember(conversations, query) {
            conversations.map { it.contact }
                .distinctBy { it.id }
                .sortedBy { (it.alias ?: it.id).lowercase() }
                .filter { query.isEmpty() || (it.alias ?: "").lowercase().contains(query) || it.id.lowercase().contains(query) }
        }

        if (conversations.isEmpty()) {
            Text(
                text = "You have no contacts yet. Start a 1:1 chat with someone first, then you can add them to a group.",
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        } else if (contacts.isEmpty()) {
            Text(
                text = "No contacts match your search.",
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            contacts.forEach { contact ->
                val selected = selectedAddresses.contains(contact.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleMember(contact.id) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ContactAvatar(
                        imageUrl = contact.knsAvatarUrl,
                        deviceContactPhotoUri = contact.systemContactPhotoUri,
                        backupPhotoBase64 = contact.backupPhotoBase64,
                        fallbackText = contact.alias ?: contact.id.takeLast(8),
                        size = 40.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = contact.alias ?: KaspaAddress.shortDisplay(contact.id),
                            color = LocalAppColors.current.textPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = KaspaAddress.shortDisplay(contact.id),
                            color = LocalAppColors.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
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

    errorMessage?.let { message ->
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, color = Color(0xFFFF3B30), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "Search and tap contacts to add them to the group. You can add up to $MAX_GROUP_MEMBERS.",
        color = LocalAppColors.current.textSecondary,
        style = MaterialTheme.typography.bodySmall
    )

    Spacer(modifier = Modifier.height(32.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInfoScreen(
    contactId: String,
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
    fromBroadcast: Boolean = false,
    onNavigateToPhotoSettings: (String) -> Unit = {},
    onNavigateToNotificationSettings: (String) -> Unit = {},
    onNavigateToDomains: (String) -> Unit = {}
) {
    val conversation = chatViewModel.conversations.collectAsState().value.find { it.contact.id == contactId }
    val messages by chatViewModel.getMessages(contactId).collectAsState(initial = emptyList())
    val myAddress by walletViewModel.address.collectAsState()
    val kaspaExplorer by chatViewModel.kaspaExplorer.collectAsState()
    val uriHandler = LocalUriHandler.current
    // nil hides the "Chess Stats" row entirely - only shown once this contact has actually played
    // at least one chess game (an always-visible "0W - 0L" on every contact who's never played
    // would just be clutter). See ChessGameEngine.record.
    val chessRecord = remember(messages, myAddress) {
        val address = myAddress ?: return@remember null
        val chessSourceMessages = messages.map {
            com.kachat.app.util.ChessGameEngine.SimpleChessSourceMessage(
                id = it.id,
                plaintextBody = it.plaintextBody,
                isOutgoing = it.direction == "sent",
                blockTimestamp = it.blockTimestamp
            )
        }
        val hasChessHistory = chessSourceMessages.any { message ->
            val unwrapped = MessageReply.parseOrNull(message.plaintextBody)?.text ?: message.plaintextBody
            com.kachat.app.util.ChessMessage.parseOrNull(unwrapped) is com.kachat.app.util.ChessEnvelope.Invite
        }
        if (hasChessHistory) com.kachat.app.util.ChessGameEngine.record(chessSourceMessages, address, contactId) else null
    }
    val knsProfile = chatViewModel.knsProfiles.collectAsState().value[contactId]
    val knsFields = knsProfile?.profile
    val ownedDomains = knsProfile?.ownedDomains.orEmpty()
    val hasMoreInfo = knsFields != null && listOf(
        knsFields.bio, knsFields.x, knsFields.website, knsFields.telegram,
        knsFields.discord, knsFields.contactEmail, knsFields.github, knsFields.redirectUrl
    ).any { !it.isNullOrBlank() }
    val systemContactId = conversation?.contact?.systemContactId
    val systemContactName = conversation?.contact?.systemContactName

    var contactName by remember { mutableStateOf("") }

    // Synchronize local state with database when it loads
    LaunchedEffect(conversation?.contact?.alias) {
        contactName = conversation?.contact?.alias ?: ""
    }

    // No custom nickname saved yet — once the contact's primary KNS domain resolves, prefill the
    // field with it (still fully editable) rather than leaving it blank. Guarded on contactName
    // still being blank at the moment this fires so it never clobbers something already typed.
    LaunchedEffect(knsProfile?.selectedDomain) {
        if (conversation?.contact?.alias == null && contactName.isBlank()) {
            knsProfile?.selectedDomain?.let { contactName = it }
        }
    }

    LaunchedEffect(contactId) {
        chatViewModel.refreshKnsProfile(contactId)
    }

    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current

    val context = LocalContext.current
    val pickContactLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // PHOTO_URI rides along on the picker's one-shot URI grant (no READ_CONTACTS needed), so a
        // manual link picks up the device address-book photo the same way the automatic scan does.
        context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_URI
            ),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val lookupKey = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY))
                val displayName = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY))
                val photoUri = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI))
                if (lookupKey != null && displayName != null) {
                    contactName = displayName
                    chatViewModel.linkSystemContact(contactId, lookupKey, displayName, photoUri)
                }
            }
        }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (fromBroadcast) "User Info" else "Chat Info", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.cancel), color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        chatViewModel.updateContactName(contactId, contactName)
                        onBack()
                    }) {
                        Text(stringResource(R.string.save), color = KaspaTeal, fontWeight = FontWeight.Bold)
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
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Contact-name card — only needed as a fallback when there's no KNS profile to show
            // instead; the "KNS Profile" section below takes over this role (avatar + editable
            // name) once a profile exists, rather than showing both.
            if (ownedDomains.isEmpty()) {
                Surface(
                    color = LocalAppColors.current.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ContactAvatar(
                            imageUrl = knsFields?.avatarUrl,
                            deviceContactPhotoUri = conversation?.contact?.systemContactPhotoUri,
                            fallbackText = conversation?.contact?.alias ?: contactId.takeLast(8),
                            size = 60.dp,
                            backgroundColor = LocalAppColors.current.surfaceVariant,
                            fontSize = 20.sp
                        )
                        Spacer(Modifier.width(16.dp))
                        TextField(
                            value = contactName,
                            onValueChange = { contactName = it },
                            placeholder = { Text(stringResource(R.string.contact_name), color = LocalAppColors.current.textSecondary) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = LocalAppColors.current.textPrimary,
                                unfocusedTextColor = LocalAppColors.current.textPrimary,
                                cursorColor = KaspaTeal,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.fillMaxWidth().offset(x = (-16).dp)
                        )
                    }
                }
            }

            if (ownedDomains.isNotEmpty()) {
                SettingsSection(title = stringResource(R.string.kns_profile)) {
                    Column {
                        val bannerUrl = knsFields?.bannerUrl?.takeIf { it.isNotBlank() }
                        if (bannerUrl != null) {
                            SubcomposeAsyncImage(
                                model = bannerUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(90.dp)
                                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                                    .background(LocalAppColors.current.surfaceVariant)
                            )
                        }
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            ContactAvatar(
                                imageUrl = knsFields?.avatarUrl,
                                deviceContactPhotoUri = conversation?.contact?.systemContactPhotoUri,
                                fallbackText = knsProfile?.selectedDomain ?: contactId.takeLast(8),
                                size = 48.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                TextField(
                                    value = contactName,
                                    onValueChange = { contactName = it },
                                    placeholder = { Text(knsProfile?.selectedDomain ?: "Contact Name", color = LocalAppColors.current.textSecondary) },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedTextColor = LocalAppColors.current.textPrimary,
                                        unfocusedTextColor = LocalAppColors.current.textPrimary,
                                        cursorColor = KaspaTeal,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .offset(x = (-16).dp)
                                )
                                val bio = knsFields?.bio?.takeIf { it.isNotBlank() }
                                if (bio != null) {
                                    Text(
                                        text = bio,
                                        color = LocalAppColors.current.textPrimary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.clickable { clipboardManager.setText(AnnotatedString(bio)) }
                                    )
                                } else {
                                    Text(
                                        text = if (hasMoreInfo) "On-chain profile data available." else "No on-chain profile data yet.",
                                        color = LocalAppColors.current.textSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        var moreInfoExpanded by remember(contactId) { mutableStateOf(false) }
                        if (hasMoreInfo) {
                            HorizontalDivider(color = LocalAppColors.current.divider)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { moreInfoExpanded = !moreInfoExpanded }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.more_info), color = KaspaTeal, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Icon(
                                    if (moreInfoExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (moreInfoExpanded) "Collapse" else "Expand",
                                    tint = KaspaTeal
                                )
                            }
                            if (moreInfoExpanded) {
                                HorizontalDivider(color = LocalAppColors.current.divider)
                                Column(modifier = Modifier.padding(16.dp)) {
                                    val socialLinks = listOfNotNull(
                                        knsFields?.x?.takeIf { it.isNotBlank() }?.let { "X" to it },
                                        knsFields?.website?.takeIf { it.isNotBlank() }?.let { "Website" to it },
                                        knsFields?.telegram?.takeIf { it.isNotBlank() }?.let { "Telegram" to it },
                                        knsFields?.discord?.takeIf { it.isNotBlank() }?.let { "Discord" to it },
                                        knsFields?.contactEmail?.takeIf { it.isNotBlank() }?.let { "Email" to it },
                                        knsFields?.github?.takeIf { it.isNotBlank() }?.let { "GitHub" to it },
                                        knsFields?.redirectUrl?.takeIf { it.isNotBlank() }?.let { "Redirect" to it }
                                    )
                                    socialLinks.forEachIndexed { index, (label, value) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    val url = if (value.startsWith("http")) value else "https://$value"
                                                    try {
                                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                                    } catch (e: Exception) { /* no browser available */ }
                                                }
                                                .padding(vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(label, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodyMedium)
                                            Text(value, color = KaspaTeal, style = MaterialTheme.typography.bodyMedium)
                                        }
                                        if (index < socialLinks.lastIndex) {
                                            HorizontalDivider(color = LocalAppColors.current.divider)
                                        }
                                    }
                                }
                            }
                        }

                    }
                }
            }

            // One row into a dedicated screen rather than the full list inline: an address that
            // owns a few dozen domains pushed everything below it - the address card, the media
            // and notification controls - far enough down the screen to be hard to reach. Still
            // reads the already-populated ChatViewModel.knsProfiles cache the LaunchedEffect
            // above fills, so opening the screen costs no fetch.
            SettingsSection(title = stringResource(R.string.kns_domains)) {
                SettingsNavigationItem(
                    stringResource(R.string.kns_domains),
                    Icons.Default.AlternateEmail,
                    when {
                        // Null = the lookup has not returned yet; a finished lookup always stores
                        // a KnsProfileUiState, empty ownedDomains and all.
                        knsProfile == null -> "Loading..."
                        ownedDomains.isEmpty() -> stringResource(R.string.no_domains_yet)
                        else -> ownedDomains.size.toString()
                    },
                    onClick = {
                        // Nothing to open until the lookup has produced something.
                        if (ownedDomains.isNotEmpty()) onNavigateToDomains(contactId)
                    }
                )
            }

            SettingsSection(
                title = stringResource(R.string.address),
                headerAction = {
                    Text(
                        stringResource(R.string.view_in_explorer),
                        color = KaspaTeal,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .padding(end = 8.dp, bottom = 8.dp)
                            .clickable { uriHandler.openUri(kaspaExplorer.addressUrl(contactId)) }
                    )
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboardManager.setText(AnnotatedString(contactId))
                            showAddressCopiedToast(context, contactId)
                        }
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val qrPainter = rememberQrBitmapPainter(contactId)
                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(2.dp, KaspaTeal, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(qrPainter, "QR Code", modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = contactId,
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Pair aliases (ported from iOS ChatInfoView): the deterministic aliases identifying
            // this conversation's messages on-chain. Receiving = the alias on messages this
            // contact sends me (what my sync watches - WalletManager.myDeterministicAlias, my
            // pubkey as HKDF context); Sending = the alias on my messages to them
            // (WalletManager.theirDeterministicAlias, their pubkey as context) - direction
            // semantics verified against ChatRepository.syncContextualMessages/
            // WalletService.sendKasiaMessage usage. Derived only on demand (tap), never at render.
            var revealedReceivingAlias by remember(contactId) { mutableStateOf<String?>(null) }
            var revealedSendingAlias by remember(contactId) { mutableStateOf<String?>(null) }

            @Composable
            fun AliasRow(label: String, revealed: String?, derive: () -> String?, onRevealed: (String?) -> Unit) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (revealed != null) {
                                clipboardManager.setText(AnnotatedString(revealed))
                                Toast.makeText(context, context.getString(R.string.alias_copied), Toast.LENGTH_SHORT).show()
                            } else {
                                val derived = derive()
                                if (derived != null) {
                                    onRevealed(derived)
                                } else {
                                    Toast.makeText(context, context.getString(R.string.alias_unavailable), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, color = LocalAppColors.current.textPrimary)
                    if (revealed != null) {
                        Text(
                            revealed,
                            color = LocalAppColors.current.textSecondary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("••••••••••••", color = LocalAppColors.current.textSecondary)
                            Icon(Icons.Default.Visibility, contentDescription = stringResource(R.string.reveal), tint = KaspaTeal, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Column {
                SettingsSection(title = stringResource(R.string.aliases)) {
                    AliasRow(
                        label = stringResource(R.string.receiving_alias),
                        revealed = revealedReceivingAlias,
                        derive = { chatViewModel.deriveReceivingAlias(contactId) },
                        onRevealed = { revealedReceivingAlias = it }
                    )
                    SettingsDivider()
                    AliasRow(
                        label = stringResource(R.string.sending_alias),
                        revealed = revealedSendingAlias,
                        derive = { chatViewModel.deriveSendingAlias(contactId) },
                        onRevealed = { revealedSendingAlias = it }
                    )
                }
                SettingsFooter(stringResource(R.string.these_identify_this_conversations_messages))
            }

            SettingsSection(title = stringResource(R.string.system_contact)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (systemContactId != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.linked), color = LocalAppColors.current.textPrimary)
                            Text(systemContactName ?: "", color = LocalAppColors.current.textSecondary)
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = LocalAppColors.current.divider)
                        Spacer(Modifier.height(12.dp))
                    } else {
                        Text(stringResource(R.string.not_linked), color = LocalAppColors.current.textSecondary)
                        Spacer(Modifier.height(12.dp))
                    }

                    Row(
                        modifier = Modifier.clickable { pickContactLauncher.launch(null) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PersonAddAlt1, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.link_from_contacts), color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }

                    if (systemContactId != null) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.clickable { chatViewModel.unlinkSystemContact(contactId) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.RemoveCircleOutline, null, tint = Color(0xFFFF3B30), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.unlink), color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (!fromBroadcast) {
                SettingsSection(title = stringResource(R.string.incoming_notifications)) {
                    val notificationOverride = com.kachat.app.models.ContactNotificationMode.fromName(conversation?.contact?.notificationOverride)
                    SettingsNavigationItem(
                        stringResource(R.string.incoming_notifications),
                        Icons.Default.NotificationsNone,
                        notificationOverride?.displayName ?: "Default",
                        onClick = { onNavigateToNotificationSettings(contactId) }
                    )
                }

                SettingsSection(title = stringResource(R.string.photos)) {
                    val photoOverride = com.kachat.app.models.PhotoAutoDisplayMode.fromName(conversation?.contact?.photoAutoDisplayOverride)
                    SettingsNavigationItem(
                        stringResource(R.string.photos),
                        Icons.Default.Photo,
                        photoOverride.displayName,
                        onClick = { onNavigateToPhotoSettings(contactId) }
                    )
                }
            }

            if (!fromBroadcast) {
                SettingsSection(title = stringResource(R.string.info)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val addedDate = remember(conversation) {
                            conversation?.contact?.addedAt?.let {
                                java.text.SimpleDateFormat("MMMM dd, yyyy", java.util.Locale.US).format(java.util.Date(it))
                            } ?: "Unknown"
                        }

                        val lastMessageTime = remember(messages) {
                            messages.firstOrNull()?.blockTimestamp?.let {
                                val diff = System.currentTimeMillis() - it
                                val hours = diff / (1000 * 60 * 60)
                                val minutes = (diff / (1000 * 60)) % 60
                                val days = hours / 24
                                when {
                                    days > 0 -> "$days day${if (days == 1L) "" else "s"} ago"
                                    hours > 0 -> "${hours} hr, ${minutes} min"
                                    else -> "${minutes} min"
                                }
                            } ?: "None"
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.added), color = LocalAppColors.current.textPrimary)
                            Text(addedDate, color = LocalAppColors.current.textSecondary)
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = LocalAppColors.current.divider)
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.last_message), color = LocalAppColors.current.textPrimary)
                            Text(lastMessageTime, color = LocalAppColors.current.textSecondary)
                        }
                        if (chessRecord != null) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = LocalAppColors.current.divider)
                            Spacer(Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.chess_stats), color = LocalAppColors.current.textPrimary)
                                Text("${chessRecord.first}W - ${chessRecord.second}L", color = LocalAppColors.current.textSecondary)
                            }
                        }
                        Spacer(Modifier.height(24.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            InfoStatItem(label = "Sent", value = messages.count { it.direction == "sent" }.toString())
                            InfoStatItem(label = "Received", value = messages.count { it.direction == "received" }.toString())
                            InfoStatItem(label = "Total", value = messages.size.toString())
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

/** Reached from Chat Info's "Photos" row — a selectable list of [PhotoAutoDisplayMode]s for this one contact, matching [KaspaExplorerSettingsScreen]'s picker pattern. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPhotoSettingsScreen(contactId: String, onBack: () -> Unit, chatViewModel: ChatViewModel = hiltViewModel()) {
    val conversation = chatViewModel.conversations.collectAsState().value.find { it.contact.id == contactId }
    val photoOverride = com.kachat.app.models.PhotoAutoDisplayMode.fromName(conversation?.contact?.photoAutoDisplayOverride)
    val automaticResolvesToShow = conversation?.contact?.conversationStatus == "active"

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.photos), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = LocalAppColors.current.textPrimary, modifier = Modifier.size(20.dp))
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                "Automatic currently ${if (automaticResolvesToShow) "shows" else "hides"} photos from this contact. " +
                    "It hides photos from contacts you haven't added or messaged yet, until you tap to reveal them.",
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            SettingsSection(title = stringResource(R.string.photos)) {
                com.kachat.app.models.PhotoAutoDisplayMode.entries.forEachIndexed { index, mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                chatViewModel.updateContactPhotoOverride(
                                    contactId,
                                    if (mode == com.kachat.app.models.PhotoAutoDisplayMode.AUTOMATIC) null else mode
                                )
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(mode.displayName, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (photoOverride == mode) {
                            Icon(Icons.Default.Check, null, tint = KaspaTeal)
                        }
                    }
                    if (index < com.kachat.app.models.PhotoAutoDisplayMode.entries.lastIndex) {
                        SettingsDivider()
                    }
                }
            }
        }
    }
}

/** Reached from Chat Info's "Incoming Notifications" row — a selectable list of [ContactNotificationMode]s for this one contact, matching [ContactPhotoSettingsScreen]'s picker pattern (a null override shows as "Default", the first row, rather than one of the enum's own cases). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactNotificationSettingsScreen(contactId: String, onBack: () -> Unit, chatViewModel: ChatViewModel = hiltViewModel()) {
    val conversation = chatViewModel.conversations.collectAsState().value.find { it.contact.id == contactId }
    val notificationOverride = com.kachat.app.models.ContactNotificationMode.fromName(conversation?.contact?.notificationOverride)

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.incoming_notifications), color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = LocalAppColors.current.textPrimary, modifier = Modifier.size(20.dp))
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                stringResource(R.string.default_follows_settings_notifications_off_disables),
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            SettingsSection(title = stringResource(R.string.incoming_notifications)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { chatViewModel.updateContactNotificationOverride(contactId, null) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.default_option), color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (notificationOverride == null) {
                        Icon(Icons.Default.Check, null, tint = KaspaTeal)
                    }
                }
                com.kachat.app.models.ContactNotificationMode.entries.forEach { mode ->
                    SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chatViewModel.updateContactNotificationOverride(contactId, mode) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(mode.displayName, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (notificationOverride == mode) {
                            Icon(Icons.Default.Check, null, tint = KaspaTeal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InfoStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
    }
}


/**
 * The contact's KNS domains, primary first, on their own screen.
 *
 * Pushed from Chat Info rather than listed inline: an address owning a few dozen domains buried
 * everything below it on that screen. Reads the same [ChatViewModel] profile cache Chat Info
 * already filled, so opening it costs no fetch; it refreshes on entry only to pick up anything
 * that landed since. Mirrors iOS's ContactDomainsView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDomainsScreen(
    contactId: String,
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val knsProfile = chatViewModel.knsProfiles.collectAsState().value[contactId]
    val ownedDomains = knsProfile?.ownedDomains ?: emptyList()

    LaunchedEffect(contactId) { chatViewModel.refreshKnsProfile(contactId) }

    // The contact's OWN primary from the reverse lookup, not selectedDomain, which prefers a
    // domain pinned locally for this chat. Same rule as iOS.
    val primaryDomain = knsProfile?.explicitPrimaryDomain
    // Primary first, the rest alphabetically, so the marked row is always on top whatever order
    // the KNS assets endpoint happened to return.
    val sortedDomains = remember(ownedDomains, primaryDomain) {
        ownedDomains.sortedWith(compareBy({ it != primaryDomain }, { it.lowercase() }))
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.kns_domains),
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBackIos,
                            null,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
        SettingsSection(title = stringResource(R.string.kns_domains)) {
            when {
                knsProfile == null -> Text(
                    "Loading domains...",
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                sortedDomains.isEmpty() -> Text(
                    stringResource(R.string.no_domains_yet),
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                else -> sortedDomains.forEachIndexed { index, domain ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                clipboardManager.setText(AnnotatedString(domain))
                                Toast.makeText(context, "$domain copied", Toast.LENGTH_SHORT).show()
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            domain,
                            color = colors.textPrimary,
                            fontWeight = if (domain == primaryDomain) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (domain == primaryDomain) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.primary),
                                color = KaspaTeal,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(KaspaTeal.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    if (index < sortedDomains.lastIndex) {
                        SettingsDivider()
                    }
                }
            }
        }
        Text(
            // Says which rule produced the badge, so a primary that looks wrong is explainable
            // rather than mysterious.
            if (primaryDomain != null) {
                "Primary is the domain this contact set as their KNS primary name. Tap any domain to copy it."
            } else {
                "This contact hasn't set a KNS primary name. Tap any domain to copy it."
            },
            color = colors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        }
    }
}
