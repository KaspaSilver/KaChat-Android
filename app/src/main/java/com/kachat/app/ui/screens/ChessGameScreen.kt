package com.kachat.app.ui.screens

import com.kachat.app.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.models.MessageEntity
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ChessColor
import com.kachat.app.util.ChessEngine
import com.kachat.app.util.ChessEnvelope
import com.kachat.app.util.ChessGameEngine
import com.kachat.app.util.ChessGameStatusKind
import com.kachat.app.util.ChessMessage
import com.kachat.app.util.ChessMove
import com.kachat.app.util.ChessPiece
import com.kachat.app.util.ChessPieceType
import com.kachat.app.util.ChessSquare
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.MessageReply
import com.kachat.app.util.VoiceMessage
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.delay

/**
 * Full-screen interactive chess board, opened by tapping a chess card in a 1:1 chat
 * ([ChessBubble] in Screens.kt). Board state is entirely derived from the conversation's
 * messages ([ChessGameEngine.summarize]) - re-derived fresh from `chatViewModel.getMessages`
 * (Room-`Flow`-backed) on every recomposition, so a new move arriving while this screen is open
 * updates it automatically, the same way any other message-driven screen in this app stays live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChessGameScreen(
    navController: NavController,
    contactId: String,
    gameId: String,
    chatViewModel: ChatViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel()
) {
    val conversations by chatViewModel.conversations.collectAsState()
    val conversation = conversations.find { it.contact.id == contactId }
    val messages by chatViewModel.getMessages(contactId).collectAsState(initial = emptyList())
    val myAddress by walletViewModel.address.collectAsState()
    val focusManager = LocalFocusManager.current

    // ChatThreadScreen marks itself "active" (suppressing notifications for this contact while
    // its own composable is on screen) via the identical DisposableEffect pattern, but navigating
    // here to the chess board is a separate destination in the NavHost - Compose Navigation
    // actually disposes ChatThreadScreen while this screen is shown, which cleared that flag and
    // let move notifications through for a game the user was already watching live. Re-set it
    // here so it stays active for the whole time this screen (not just the chat thread) is open.
    DisposableEffect(contactId) {
        chatViewModel.setActiveContact(contactId)
        onDispose { chatViewModel.setActiveContact(null) }
    }

    // Every non-chess message in the conversation - chess move/invite/response/resign envelopes
    // are deliberately excluded since the live board above already shows that state; repeating
    // it here as text would just be clutter.
    val chatMessages = remember(messages) {
        messages.filter { message ->
            // Cross-device "sent via another device" fill-ins must never render here - Android
            // doesn't create them, but rows restored from an iOS archive by builds predating the
            // import-time skip can still be in the DB (see MessageEntity.isSentPlaceholder).
            if (message.isSentPlaceholder) return@filter false
            val unwrapped = MessageReply.parseOrNull(message.plaintextBody)?.text ?: message.plaintextBody
            ChessMessage.parseOrNull(unwrapped) == null
        }.sortedBy { it.blockTimestamp }
    }
    var chatDraft by remember { mutableStateOf("") }

    val chessSourceMessages = remember(messages) {
        messages.map {
            ChessGameEngine.SimpleChessSourceMessage(
                id = it.id,
                plaintextBody = it.plaintextBody,
                isOutgoing = it.direction == "sent",
                blockTimestamp = it.blockTimestamp
            )
        }
    }
    val summary = remember(chessSourceMessages, myAddress) {
        val address = myAddress
        if (address != null) ChessGameEngine.summarize(gameId, chessSourceMessages, address, contactId) else null
    }
    val myColor = remember(summary, myAddress) {
        val address = myAddress
        if (address != null) summary?.colorFor(address) else null
    }
    val isMyTurn = summary != null && myColor != null && summary.status.kind == ChessGameStatusKind.IN_PROGRESS && summary.board.sideToMove == myColor

    // ------------------------------------------------------------------
    // Chess clocks (timed games only). Casual-async semantics: my clock runs only while THIS
    // screen is open (resumed) AND it's my turn AND the game is in progress - closing the board
    // pauses it, because the opponent may be offline for hours and the clock measures thinking
    // time at the board, not wall time. Each side's authoritative remaining time is the clockMs
    // carried on their own most recent move (increment already added by the sender); my displayed
    // time additionally subtracts thinking time accumulated locally this turn, which is persisted
    // to SharedPreferences (key "chess_clock_<gameId>", value "moveCount|elapsedMs") so process
    // death mid-think doesn't refund the time already spent. The opponent's clock is never ticked
    // speculatively while they think - it just shows their last reported value.
    // ------------------------------------------------------------------
    val isTimed = summary?.isTimed == true
    val incrementMs = (summary?.tcIncSeconds ?: 0) * 1000L
    val moveCount = summary?.moveHistory?.size ?: 0
    val context = LocalContext.current
    val clockPrefs = remember(context) {
        context.getSharedPreferences("chess_clocks", android.content.Context.MODE_PRIVATE)
    }
    val clockPrefsKey = remember(gameId) { "chess_clock_$gameId" }

    // Thinking time (ms) spent at the board this turn. Seeded from the persisted record when it
    // matches the current move count; reset to zero whenever the move count advances (my own
    // optimistic send included) or the stored record belongs to an older turn.
    var thinkingElapsedMs by remember(gameId) { mutableStateOf(0L) }
    LaunchedEffect(gameId, moveCount, isTimed, summary != null) {
        if (!isTimed || summary == null) return@LaunchedEffect
        val stored = clockPrefs.getString(clockPrefsKey, null)?.split("|")
        thinkingElapsedMs = if (stored?.size == 2 && stored[0].toIntOrNull() == moveCount) {
            stored[1].toLongOrNull() ?: 0L
        } else {
            0L
        }
    }

    // Screen resumed-ness gates the tick: a backgrounded app (or this destination left) must not
    // burn clock. No lifecycle-runtime-compose dependency in this project, so observe manually.
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val myLastReportedClockMs = remember(summary, myColor) {
        val color = myColor
        if (color != null) summary?.lastReportedClockMs(color) else null
    }
    val opponentRemainingMs = remember(summary, myColor) {
        val color = myColor
        if (color != null) summary?.lastReportedClockMs(color.opposite) else null
    }
    val myRemainingMs = if (isTimed) {
        ((myLastReportedClockMs ?: 0L) - thinkingElapsedMs).coerceAtLeast(0L)
    } else {
        null
    }

    val clockRunning = isTimed && isMyTurn && isResumed && (myRemainingMs ?: 0L) > 0L
    LaunchedEffect(clockRunning, moveCount, gameId) {
        if (!clockRunning) return@LaunchedEffect
        var lastTick = android.os.SystemClock.elapsedRealtime()
        var lastPersist = lastTick
        try {
            while (true) {
                delay(100)
                val now = android.os.SystemClock.elapsedRealtime()
                thinkingElapsedMs += now - lastTick
                lastTick = now
                if (now - lastPersist >= 1000) {
                    clockPrefs.edit().putString(clockPrefsKey, "$moveCount|$thinkingElapsedMs").apply()
                    lastPersist = now
                }
            }
        } finally {
            // Screen closed / turn ended / app backgrounded: persist the final tally so reopening
            // resumes from here rather than refunding up to a second of think time.
            clockPrefs.edit().putString(clockPrefsKey, "$moveCount|$thinkingElapsedMs").apply()
        }
    }

    // Flagging: my clock hit zero on my turn -> auto-resign with reason "timeout", exactly once
    // per screen session (the resign lands optimistically, flipping the game to RESIGNED, so this
    // can't re-fire after that either). Input below is also gated on not-flagged.
    val hasFlagged = isTimed && isMyTurn && myRemainingMs != null && myRemainingMs <= 0L
    var timeoutSent by remember(gameId) { mutableStateOf(false) }
    LaunchedEffect(hasFlagged) {
        if (hasFlagged && !timeoutSent) {
            timeoutSent = true
            clockPrefs.edit().remove(clockPrefsKey).apply()
            chatViewModel.resignChessGame(contactId, gameId, reason = "timeout")
        }
    }

    // Cumulative wins/losses against this contact, across every chess game ever played with them
    // (not just the current one) - see ChessGameEngine.record.
    val chessRecord = remember(chessSourceMessages, myAddress) {
        val address = myAddress
        if (address != null) ChessGameEngine.record(chessSourceMessages, address, contactId) else 0 to 0
    }

    // The MessageEntity behind the most recent action in this game, whichever it was (invite/
    // move/response/resign) - ChessGameSummary.lastMessageId already identifies it.
    val lastActionMessage = remember(summary, messages) {
        summary?.let { s -> messages.firstOrNull { it.id == s.lastMessageId } }
    }
    // Drives the "Sent"/"Retry" indicator under the turn status - only shown right after *I*
    // made the most recent move (not after an invite/response/resign, and not when the most
    // recent action was the opponent's move, which has no local delivery status to report).
    val lastMoveSendStatus = remember(lastActionMessage) {
        val message = lastActionMessage
        if (message != null && message.direction == "sent") {
            val unwrapped = MessageReply.parseOrNull(message.plaintextBody)?.text ?: message.plaintextBody
            if (ChessMessage.parseOrNull(unwrapped) is ChessEnvelope.Move) message.deliveryStatus else null
        } else {
            null
        }
    }

    var selectedSquare by remember { mutableStateOf<ChessSquare?>(null) }
    var pendingPromotionMove by remember { mutableStateOf<ChessMove?>(null) }
    var showResignConfirm by remember { mutableStateOf(false) }

    val legalDestinations = remember(selectedSquare, summary) {
        val square = selectedSquare
        val board = summary?.board
        if (square != null && board != null) ChessEngine.legalMoves(square, board).map { it.to } else emptyList()
    }

    fun send(move: ChessMove) {
        if (isTimed) {
            // Stamp the move with my remaining clock AFTER the move, increment already added -
            // that value becomes my authoritative clock until my next move. The local thinking
            // accumulator resets (the persisted record is keyed to a move count that just
            // advanced, so it's stale either way; clear it eagerly).
            val newClockMs = (myRemainingMs ?: 0L) + incrementMs
            clockPrefs.edit().remove(clockPrefsKey).apply()
            thinkingElapsedMs = 0L
            chatViewModel.sendChessMove(contactId, gameId, move, clockMs = newClockMs)
        } else {
            chatViewModel.sendChessMove(contactId, gameId, move)
        }
    }

    fun handleTap(square: ChessSquare) {
        val board = summary?.board ?: return
        if (!isMyTurn) return
        // Flagged: the timeout resign is on its way - no further moves.
        if (hasFlagged) return
        val currentSelection = selectedSquare
        if (currentSelection != null) {
            if (legalDestinations.contains(square)) {
                val movingPiece = board.piece(currentSelection)
                val backRank = if (movingPiece?.color == ChessColor.WHITE) 7 else 0
                selectedSquare = null
                if (movingPiece?.type == ChessPieceType.PAWN && square.rank == backRank) {
                    pendingPromotionMove = ChessMove(currentSelection, square, null)
                } else {
                    send(ChessMove(currentSelection, square, null))
                }
                return
            }
            val piece = board.piece(square)
            selectedSquare = if (piece != null && piece.color == myColor) square else null
        } else {
            val piece = board.piece(square)
            if (piece != null && piece.color == myColor) selectedSquare = square
        }
    }

    fun sendChatMessage() {
        val text = chatDraft.trim()
        if (text.isEmpty()) return
        chatDraft = ""
        chatViewModel.sendMessage(contactId, text)
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            conversation?.contact?.alias?.takeIf { it.isNotBlank() } ?: contactId.takeLast(8),
                            color = LocalAppColors.current.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        if (summary != null) {
                            Text(
                                summary.statusText,
                                color = if (summary.status.isGameOver) LocalAppColors.current.textSecondary else KaspaTeal,
                                fontSize = 12.sp
                            )
                        }
                        if (lastMoveSendStatus != null) {
                            MoveSendStatusRow(
                                status = lastMoveSendStatus,
                                onRetry = { lastActionMessage?.let { chatViewModel.retrySendMessage(it) } }
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(
                            R.string.back), tint = LocalAppColors.current.textPrimary)
                    }
                },
                actions = {
                    Column(horizontalAlignment = Alignment.End) {
                        // Available whenever the game isn't over - including a still-pending
                        // invite, which the sender must be able to close (a resign on a pending
                        // game is already how starting a new game retires the previous one).
                        if (summary != null && !summary.status.isGameOver) {
                            val resignLabel = if (summary.status.kind == ChessGameStatusKind.PENDING_RESPONSE)
                                stringResource(R.string.chess_cancel_game) else stringResource(R.string.resign)
                            TextButton(onClick = { showResignConfirm = true }) {
                                Text(resignLabel, color = Color(0xFFFF3B30))
                            }
                        }
                        WinLossCounter(wins = chessRecord.first, losses = chessRecord.second)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        bottomBar = {
            if (summary != null) {
                // navigationBarsPadding() keeps the send row clear of the system nav bar when the
                // keyboard is closed; imePadding() on the Scaffold above handles the keyboard-open
                // case - matches the 1:1 chat composer's identical pattern in Screens.kt.
                // Text-only, deliberately - no mic, no "+" menu, no photos/payments/another chess
                // invite. This is a quick-chat surface for while a game's in progress, not a full
                // composer.
                Column(modifier = Modifier.background(LocalAppColors.current.background).navigationBarsPadding().padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextField(
                            value = chatDraft,
                            onValueChange = { chatDraft = it },
                            placeholder = { Text(stringResource(R.string.message), color = LocalAppColors.current.textSecondary) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = LocalAppColors.current.surface,
                                unfocusedContainerColor = LocalAppColors.current.surface,
                                focusedTextColor = LocalAppColors.current.textPrimary,
                                unfocusedTextColor = LocalAppColors.current.textPrimary,
                                cursorColor = KaspaTeal,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(20.dp),
                            maxLines = 4
                        )
                        IconButton(onClick = { sendChatMessage() }, enabled = chatDraft.isNotBlank()) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.send),
                                tint = if (chatDraft.isNotBlank()) KaspaTeal else LocalAppColors.current.textSecondary
                            )
                        }
                    }
                }
            }
        },
        containerColor = LocalAppColors.current.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Taps that land on an interactive child (a board square, a button) are already
                // consumed by that child's own `clickable`, so this only fires for taps on empty
                // space (header padding, captured-pieces bar background, the divider) - "tap
                // outside to dismiss the keyboard", without interfering with square selection.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (summary != null) {
                Spacer(Modifier.height(8.dp))
                if (isTimed && opponentRemainingMs != null) {
                    // Opponent's chip above the board (their side), mine below - the standard
                    // over-the-board clock arrangement. Theirs shows their last *reported* time
                    // (never ticked speculatively while they think off-device).
                    ChessClockRow(
                        remainingMs = opponentRemainingMs,
                        isSideToMove = summary.status.kind == ChessGameStatusKind.IN_PROGRESS &&
                            myColor != null && summary.board.sideToMove == myColor.opposite
                    )
                    Spacer(Modifier.height(6.dp))
                }
                CapturedPiecesBar(summary, myColor ?: ChessColor.WHITE)
                Spacer(Modifier.height(8.dp))
                Box(contentAlignment = Alignment.Center) {
                    InteractiveChessBoard(
                        board = summary.board,
                        orientation = myColor ?: ChessColor.WHITE,
                        selectedSquare = selectedSquare,
                        legalDestinations = legalDestinations,
                        lastMove = summary.moveHistory.lastOrNull(),
                        onSquareTap = ::handleTap
                    )
                    if (!isMyTurn && summary.status.kind == ChessGameStatusKind.IN_PROGRESS) {
                        WaitingOnOpponentOverlay()
                    }
                }
                if (isTimed && myRemainingMs != null) {
                    Spacer(Modifier.height(6.dp))
                    ChessClockRow(remainingMs = myRemainingMs, isSideToMove = isMyTurn)
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                ChessChatHistory(
                    messages = chatMessages,
                    onRetry = { chatViewModel.retrySendMessage(it) },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = KaspaTeal)
                }
            }
        }
    }

    if (showResignConfirm) {
        AlertDialog(
            onDismissRequest = { showResignConfirm = false },
            title = { Text(stringResource(R.string.resign_this_game)) },
            confirmButton = {
                TextButton(onClick = {
                    showResignConfirm = false
                    chatViewModel.resignChessGame(contactId, gameId)
                    navController.popBackStack()
                }) {
                    Text(stringResource(R.string.resign), color = Color(0xFFFF3B30))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResignConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    val pendingMove = pendingPromotionMove
    if (pendingMove != null) {
        AlertDialog(
            onDismissRequest = { pendingPromotionMove = null },
            title = { Text(stringResource(R.string.promote_pawn_to)) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    for (type in listOf(ChessPieceType.QUEEN, ChessPieceType.ROOK, ChessPieceType.BISHOP, ChessPieceType.KNIGHT)) {
                        ChessPieceGlyph(
                            piece = ChessPiece(type, myColor ?: ChessColor.WHITE),
                            fontSize = 36.sp,
                            modifier = Modifier.clickable {
                                pendingPromotionMove = null
                                send(pendingMove.copy(promotion = type))
                            }
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }
}

/** "Sent"/"Retry" indicator directly under the turn status, so the player has confirmation their
 *  move actually went through while in full-screen game mode (they can't see the normal chat
 *  transcript's own delivery-status ticks from here). Mirrors the app's existing sent/pending/
 *  failed icon language (see [ChessChatRow]'s status icon) rather than inventing new iconography. */
@Composable
private fun MoveSendStatusRow(status: String, onRetry: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = if (status == "failed") Modifier.clickable { onRetry() } else Modifier
    ) {
        when (status) {
            "sent" -> {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CD964), modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.sent), color = LocalAppColors.current.textSecondary, fontSize = 11.sp)
            }
            "failed" -> {
                Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.retry), color = Color(0xFFFF3B30), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            else -> {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.sending), color = LocalAppColors.current.textSecondary, fontSize = 11.sp)
            }
        }
    }
}

/** "W" / "L" small labels over a "0 - 0"-style tally - top bar, under the Resign button, always
 *  visible (not just mid-game) so the running record against this contact stays in view. */
@Composable
private fun WinLossCounter(wins: Int, losses: Int) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("W", fontSize = 9.sp, color = LocalAppColors.current.textSecondary)
            Text("$wins", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LocalAppColors.current.textPrimary)
        }
        Text("-", fontSize = 12.sp, color = LocalAppColors.current.textSecondary)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("L", fontSize = 9.sp, color = LocalAppColors.current.textSecondary)
            Text("$losses", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LocalAppColors.current.textPrimary)
        }
    }
}

/** One clock chip row (timed games only) - opponent's above the board, the local player's below,
 *  both hugging the trailing edge like a physical clock beside the board. The side to move is
 *  emphasized (teal fill, bold, brighter text); under 20 seconds the chip tints red. Monospace
 *  digits so the display doesn't jitter as it ticks. */
@Composable
private fun ChessClockRow(remainingMs: Long, isSideToMove: Boolean) {
    val lowTime = remainingMs < 20_000L
    val containerColor = when {
        lowTime -> Color(0xFFFF3B30).copy(alpha = if (isSideToMove) 0.30f else 0.16f)
        isSideToMove -> KaspaTeal.copy(alpha = 0.28f)
        else -> LocalAppColors.current.surface
    }
    val textColor = when {
        lowTime -> Color(0xFFFF3B30)
        isSideToMove -> LocalAppColors.current.textPrimary
        else -> LocalAppColors.current.textSecondary
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(color = containerColor, shape = RoundedCornerShape(10.dp)) {
            Text(
                formatChessClock(remainingMs),
                color = textColor,
                fontSize = 16.sp,
                fontWeight = if (isSideToMove) FontWeight.Bold else FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
            )
        }
    }
}

/** m:ss normally; under ten seconds switch to tenths (e.g. "0:07.3") so the final countdown
 *  visibly moves between whole seconds. */
private fun formatChessClock(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    return if (clamped < 10_000L) {
        val tenths = clamped / 100
        "0:0${tenths / 10}.${tenths % 10}"
    } else {
        val totalSeconds = clamped / 1000
        "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
    }
}

/** Overlay shown on the board while waiting for the opponent's move - the "..." cycles 1/2/3 dots
 *  like a typing indicator rather than sitting static, so it reads as "still waiting" rather than
 *  looking frozen/stuck. */
@Composable
private fun WaitingOnOpponentOverlay() {
    var dotCount by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            dotCount = (dotCount % 3) + 1
        }
    }
    Box(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            stringResource(R.string.waiting_on_opponent) + ".".repeat(dotCount),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun InteractiveChessBoard(
    board: com.kachat.app.util.ChessBoard,
    orientation: ChessColor,
    selectedSquare: ChessSquare?,
    legalDestinations: List<ChessSquare>,
    lastMove: com.kachat.app.util.ChessMoveRecord?,
    onSquareTap: (ChessSquare) -> Unit
) {
    val squareSizeDp = 44.dp
    val labelSizeDp = 16.dp
    val ranks = if (orientation == ChessColor.WHITE) (7 downTo 0) else (0..7)
    val files = if (orientation == ChessColor.WHITE) (0..7) else (7 downTo 0)

    Column {
        FileLabelsRow(files, squareSizeDp, labelSizeDp)
        for (rank in ranks) {
            Row {
                RankLabel(rank, squareSizeDp, labelSizeDp)
                for (file in files) {
                    val square = ChessSquare(file, rank)
                    val isLight = (file + rank) % 2 != 0
                    val isSelected = selectedSquare == square
                    val isDestination = legalDestinations.contains(square)
                    val isLastMoveSquare = lastMove != null && (lastMove.from == square || lastMove.to == square)
                    Box(
                        modifier = Modifier
                            .size(squareSizeDp)
                            .background(if (isLight) ChessLightSquareColor else ChessDarkSquareColor)
                            .then(
                                if (isLastMoveSquare) Modifier.background(Color(0xFFFFEB3B).copy(alpha = 0.35f)) else Modifier
                            )
                            .then(
                                if (isSelected) Modifier.background(KaspaTeal.copy(alpha = 0.45f)) else Modifier
                            )
                            .clickable { onSquareTap(square) },
                        contentAlignment = Alignment.Center
                    ) {
                        val piece = board.piece(square)
                        if (piece != null) {
                            ChessPieceGlyph(piece, fontSize = 28.sp)
                        }
                        if (isDestination) {
                            Box(
                                modifier = Modifier
                                    .size(squareSizeDp / 3)
                                    .background(KaspaTeal.copy(alpha = 0.6f), CircleShape)
                            )
                        }
                    }
                }
                RankLabel(rank, squareSizeDp, labelSizeDp)
            }
        }
        FileLabelsRow(files, squareSizeDp, labelSizeDp)
    }
}

/** File letters (a-h) shown above and below the board, in the current orientation's order. */
@Composable
private fun FileLabelsRow(files: IntProgression, squareSizeDp: androidx.compose.ui.unit.Dp, labelSizeDp: androidx.compose.ui.unit.Dp) {
    Row {
        Box(Modifier.size(labelSizeDp))
        for (file in files) {
            Box(modifier = Modifier.width(squareSizeDp).height(labelSizeDp), contentAlignment = Alignment.Center) {
                Text(('a' + file).toString(), fontSize = 10.sp, color = LocalAppColors.current.textSecondary)
            }
        }
        Box(Modifier.size(labelSizeDp))
    }
}

/** Rank number (1-8) shown to the left and right of a board row. */
@Composable
private fun RankLabel(rank: Int, squareSizeDp: androidx.compose.ui.unit.Dp, labelSizeDp: androidx.compose.ui.unit.Dp) {
    Box(modifier = Modifier.width(labelSizeDp).height(squareSizeDp), contentAlignment = Alignment.Center) {
        Text("${rank + 1}", fontSize = 10.sp, color = LocalAppColors.current.textSecondary)
    }
}

/** Captured-pieces tray: pieces the opponent has taken from me on the leading edge, pieces I've
 *  taken from them on the trailing edge - mirrors how online chess UIs show each side's haul next
 *  to their own info. */
@Composable
private fun CapturedPiecesBar(summary: com.kachat.app.util.ChessGameSummary, myColor: ChessColor) {
    val takenFromMe = if (myColor == ChessColor.WHITE) summary.capturedByBlack else summary.capturedByWhite
    val takenByMe = if (myColor == ChessColor.WHITE) summary.capturedByWhite else summary.capturedByBlack
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        CapturedGroup(pieces = takenFromMe, pieceColor = myColor, label = "They captured")
        CapturedGroup(pieces = takenByMe, pieceColor = myColor.opposite, label = "You captured")
    }
}

@Composable
private fun CapturedGroup(pieces: List<ChessPieceType>, pieceColor: ChessColor, label: String) {
    Column {
        Text(label, fontSize = 10.sp, color = LocalAppColors.current.textSecondary)
        Row {
            if (pieces.isEmpty()) {
                Text(stringResource(R.string.str), fontSize = 10.sp, color = LocalAppColors.current.textSecondary)
            } else {
                for (type in pieces) {
                    ChessPieceGlyph(ChessPiece(type, pieceColor), fontSize = 14.sp)
                }
            }
        }
    }
}

/** Scrollable, auto-scroll-to-latest chat history under the board - lets you keep chatting
 *  without leaving the full-screen game. */
@Composable
private fun ChessChatHistory(messages: List<MessageEntity>, onRetry: (MessageEntity) -> Unit, modifier: Modifier = Modifier) {
    val listState: LazyListState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            ChessChatRow(message, onRetry = { onRetry(message) })
        }
    }
}

/** Same green-check/pending/red-error delivery status as the main chat's [MessageBubble], plus
 *  the same long-press "Retry Send" for a failed message - this mini history is otherwise a much
 *  lighter rendering, but a failed send shouldn't be any less recoverable here than in the full
 *  chat it mirrors. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChessChatRow(message: MessageEntity, onRetry: () -> Unit) {
    val isSent = message.direction == "sent"
    var showMenu by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat()) },
        horizontalAlignment = if (isSent) Alignment.End else Alignment.Start
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start) {
            Surface(
                color = if (isSent) KaspaTeal else LocalAppColors.current.surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { if (ChatViewModel.shouldShowRetryOption(message)) showMenu = true }
                    )
            ) {
                Text(
                    chessChatPreviewText(message),
                    color = if (isSent) Color.Black else LocalAppColors.current.textPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        if (isSent) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (message.deliveryStatus) {
                    "failed" -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = stringResource(R.string.failed_to_send),
                            tint = Color(0xFFFF3B30),
                            modifier = Modifier.size(11.dp)
                        )
                        // Tappable "Retry" next to the red error icon, matching the full-screen
                        // move-status row's Retry affordance.
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
                        modifier = Modifier.size(11.dp)
                    )
                    else -> Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CD964),
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
    if (showMenu) {
        CenteredOptionsMenu(onDismissRequest = { showMenu = false }, anchor = menuAnchor) {
            PopupMenuRow(Icons.Default.Refresh, stringResource(R.string.retry_send)) {
                onRetry()
                showMenu = false
            }
        }
    }
}

/** Condensed, text-only rendering for the mini history - unlike the normal message bubbles,
 *  non-text content (photos/voice/payments) collapses to a short label rather than fully
 *  rendering, to keep this secondary surface lightweight. */
private fun chessChatPreviewText(message: MessageEntity): String {
    if (message.type == MessageProtocol.TYPE_PAY) return "💰 Payment"
    if (message.type == MessageProtocol.TYPE_HANDSHAKE) return "👋 Handshake"
    val unwrapped = MessageReply.parseOrNull(message.plaintextBody)?.text ?: message.plaintextBody
    if (VoiceMessage.parseOrNull(unwrapped) != null) return "🎤 Voice message"
    if (ImageMessage.parseOrNull(unwrapped) != null) return "📷 Photo"
    return unwrapped ?: ""
}
