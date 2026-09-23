package com.kachat.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.displayName
import com.kachat.app.services.ChessTournamentService
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ChessColor
import com.kachat.app.util.ChessEngine
import com.kachat.app.util.ChessMove
import com.kachat.app.util.ChessPiece
import com.kachat.app.util.ChessPieceType
import com.kachat.app.util.ChessSquare
import com.kachat.app.util.ChessTournament
import com.kachat.app.util.ChessTournamentCodec
import com.kachat.app.util.ChessTournamentGame
import com.kachat.app.util.ChessTournamentOutcome
import com.kachat.app.util.KaspaAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// Kaspa Hub > Chess (5.1): the lobby, one tournament, one game, and the leaderboard. Mirrors
// iOS's Views/Chess. Every screen holds ChessTournamentService.acquire() while it is up, so the
// arena is scanned exactly as long as someone is looking at chess.

@HiltViewModel
class ChessTournamentViewModel @Inject constructor(
    val service: ChessTournamentService,
    chatRepository: com.kachat.app.repository.ChatRepository,
) : ViewModel() {
    val contacts: StateFlow<Map<String, ContactEntity>> = chatRepository.getContacts()
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

/**
 * The app's naming rule, in the arena too: the contact's name, then their KNS domain, then the
 * shortened address - and the same for the player themselves, never "You" (iOS 30d0cca).
 */
private fun chessName(
    address: String,
    contacts: Map<String, ContactEntity>,
    knsNames: Map<String, String> = emptyMap(),
): String {
    contacts[address]?.let { return it.displayName }
    knsNames[address]?.takeIf { it.isNotBlank() }?.let { return it }
    return KaspaAddress.shortDisplay(address)
}

@Composable
private fun ChessAvatar(address: String, contacts: Map<String, ContactEntity>, size: Int = 36) {
    val contact = contacts[address]
    ContactAvatar(
        imageUrl = contact?.knsAvatarUrl,
        fallbackText = contact?.displayName ?: address.removePrefix("kaspa:").take(2),
        size = size.dp,
    )
}

/** Holds the arena scan for as long as the calling screen is composed. */
@Composable
private fun HoldArena(service: ChessTournamentService) {
    DisposableEffect(Unit) {
        service.acquire()
        onDispose { service.release() }
    }
}

@Composable
private fun ChessErrorToast(service: ChessTournamentService) {
    val error by service.lastError.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(error) {
        error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            service.clearError()
        }
    }
}

@Composable
private fun ChessSectionHeader(text: String) {
    Text(
        text.uppercase(),
        color = LocalAppColors.current.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun ChessCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LocalAppColors.current.surface),
    ) { content() }
}

private fun clockText(ms: Long): String {
    val seconds = (ms / 1000).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

// MARK: - Lobby

/** Which game: also the name of the play tab. */
enum class ChessLobbyMode(val label: String) { DUEL("1v1"), TOURNAMENT("Tournaments") }

/**
 * Chess Online > 1v1 or Tournaments: one kind of game per screen, chosen on [ChessHomeScreen].
 * Two tabs: the play tab (a public room that pairs the next joiners or fills to eight, plus
 * private games by code) and that kind's leaderboard. Mirrors iOS's ChessTournamentsView.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChessTournamentsScreen(mode: ChessLobbyMode, navController: NavController, onBack: (() -> Unit)? = null) {
    val vm: ChessTournamentViewModel = hiltViewModel()
    val service = vm.service
    HoldArena(service)
    ChessErrorToast(service)
    val colors = LocalAppColors.current
    val all by service.tournaments.collectAsState()
    val now by service.now.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val knsNames by service.knsNames.collectAsState()
    val me = service.myAddress
    val mine = service.myActiveTournament(all)
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var creatorCode by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var showJoinPrivate by remember { mutableStateOf(false) }
    var privateCode by remember { mutableStateOf("") }
    var isJoining by remember { mutableStateOf(false) }
    /** The waiting room on screen (full-screen, nothing else reachable). */
    var waitingRoomId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val lobbyContext = LocalContext.current

    fun open(id: String) = navController.navigate("chess_tournament/$id")

    // Seated in a room that is still filling - a fresh join, a relaunch, or coming back here:
    // the waiting room is the only place to be.
    LaunchedEffect(mine?.id, mine?.status) {
        val room = mine ?: return@LaunchedEffect
        val address = me ?: return@LaunchedEffect
        if (room.status == ChessTournament.Status.OPEN && room.isSeated(address, service.now.value)) {
            waitingRoomId = room.id
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            MainPageHeader(title = mode.label, onBack = onBack)
        },
    ) { padding ->
        // The same tab bar and swipe the Chats screen uses, so a tab is a tab wherever it
        // appears in the app (iOS 353f040).
        val tabs = listOf(mode.label, "Leaderboard")
        val pagerState = androidx.compose.foundation.pager.rememberPagerState { tabs.size }
        val tabScope = rememberCoroutineScope()
        Column(Modifier.fillMaxSize().padding(padding)) {
            androidx.compose.material3.TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = colors.background,
                contentColor = KaspaTeal,
            ) {
                tabs.forEachIndexed { index, label ->
                    androidx.compose.material3.Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { tabScope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(label, fontWeight = FontWeight.Bold) },
                    )
                }
            }
            androidx.compose.foundation.pager.HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            if (page == 1) {
                ChessLeaderboardRows(mode)
                return@HorizontalPager
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                val duel = mode == ChessLobbyMode.DUEL
                val publicId = if (duel) service.currentDuelRoomId(all) else service.currentPublicRoomId(all)
                val number = (if (duel) ChessTournamentCodec.duelNumber(publicId) else ChessTournamentCodec.publicNumber(publicId)) ?: 1
                ChessSectionHeader("Public")
                ChessCard {
                    PublicRoomCard(
                        room = all[publicId],
                        now = now,
                        title = if (duel) "Public 1v1 #$number" else "Public tournament #$number",
                        capacity = if (duel) 2 else ChessTournamentCodec.PLAYER_COUNT,
                        me = me,
                        myActive = mine,
                        isJoining = isJoining,
                        joinLabel = service.joinLabel(publicId),
                        onOpen = ::open,
                        onJoin = {
                            if (!isJoining) {
                                isJoining = true
                                // Straight into the waiting room: the seat only exists once the
                                // transaction lands, and sitting on the lobby until then looks
                                // like the button did nothing. Closed again if nothing was sent.
                                waitingRoomId = publicId
                                vm.launch {
                                    val sent = if (duel) service.joinPublicDuelQueue() else service.joinPublicQueue()
                                    isJoining = false
                                    if (!sent && waitingRoomId == publicId) waitingRoomId = null
                                }
                            }
                        },
                    )
                }
                Text(
                    if (duel) "Join and you are paired with the next person who joins. When a room fills, the game starts and the next room opens. Five minutes a side; every move is a Kaspa transaction (about 0.0017 KAS each). Games here count on the leaderboard."
                    else "There is always a public room waiting for players. When it fills, it starts and the next one opens. Eight players, single elimination, five minutes a side. Every move is a Kaspa transaction (about 0.0017 KAS each).",
                    color = colors.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )

                ChessSectionHeader("Private")
                ChessCard {
                    val privates = if (duel) service.myPrivateDuels(all) else service.myPrivateTournaments(all)
                    privates.forEach { t ->
                        val action = when {
                            t.status != ChessTournament.Status.OPEN -> "In play"
                            duel -> "Waiting"
                            else -> "${t.seatsLeft} seat${if (t.seatsLeft == 1) "" else "s"} left"
                        }
                        TournamentRow(t, action) { open(t.id) }
                    }
                    ChessActionRow(Icons.Default.Key, "Join with a code") { privateCode = ""; showJoinPrivate = true }
                    ChessActionRow(Icons.Default.AddCircleOutline, if (duel) "Create a private 1v1" else "Create a private tournament") {
                        newName = ""; creatorCode = ""; showCreate = true
                    }
                }
                Text(
                    if (duel) "Play a friend: create a 1v1, share its code. Private 1v1s count on the leaderboard too."
                    else "A private tournament is for friends: the creator shares its eight-character code. Creating one needs the creator code.",
                    color = colors.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )

                val live = service.liveTournaments(all).filter { it.isDuel == duel && it.isPublic && it.id != mine?.id }
                if (live.isNotEmpty()) {
                    ChessSectionHeader("In play")
                    ChessCard { live.forEach { t -> TournamentRow(t, "Watch") { open(t.id) } } }
                }
                val done = service.finishedTournaments(all).filter { it.isDuel == duel && it.isPublic }.take(20)
                if (done.isNotEmpty()) {
                    ChessSectionHeader("Finished")
                    ChessCard {
                        done.forEach { t ->
                            TournamentRow(t, t.champion?.let { "Won by ${chessName(it, contacts, knsNames)}" } ?: "Finished") { open(t.id) }
                        }
                    }
                }
            }
            }
        }
    }

    waitingRoomId?.let { id ->
        ChessWaitingRoom(
            tournamentId = id,
            onStarted = { started ->
                waitingRoomId = null
                open(started)
            },
            onFinished = { seatExpired ->
                waitingRoomId = null
                if (seatExpired) {
                    Toast.makeText(
                        lobbyContext,
                        "No one joined in time. You're out of the queue - join again whenever you like.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
    }

    if (showCreate) {
        val duel = mode == ChessLobbyMode.DUEL
        AlertDialog(
            onDismissRequest = { if (!isCreating) showCreate = false },
            title = { Text(if (duel) "Create a private 1v1" else "Create a private tournament") },
            text = {
                Column {
                    Text(
                        if (duel) "You get a code to share with the person you want to play. The game starts when they join. Creating it is one transaction."
                        else "You take the first seat and get a code to share. It starts when eight players have joined. Creating it is one transaction."
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, placeholder = { Text("Name") })
                    if (!duel) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = creatorCode, onValueChange = { creatorCode = it }, singleLine = true, placeholder = { Text("Creator code") })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isCreating) return@TextButton
                    isCreating = true
                    vm.launch {
                        val id = if (duel) service.createPrivateDuel(newName) else service.createPrivateTournament(newName, creatorCode)
                        isCreating = false
                        showCreate = false
                        // The creator holds the first seat: straight into the waiting room.
                        if (id != null) waitingRoomId = id
                    }
                }) { Text(if (isCreating) "Creating…" else "Create", color = KaspaTeal) }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }, enabled = !isCreating) { Text("Cancel") } },
        )
    }

    if (showJoinPrivate) {
        AlertDialog(
            onDismissRequest = { if (!isJoining) showJoinPrivate = false },
            title = { Text("Join with a code") },
            text = {
                Column {
                    Text(
                        "The eight-character code the creator shared. Joining is one transaction (fee: " +
                            "${service.feeText(ChessTournamentCodec.join("abcdefgh")) ?: "--"})."
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = privateCode, onValueChange = { privateCode = it }, singleLine = true, placeholder = { Text("Code") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None, autoCorrect = false,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isJoining) return@TextButton
                    isJoining = true
                    vm.launch {
                        val code = privateCode
                        val joined = service.joinPrivate(code)
                        isJoining = false
                        showJoinPrivate = false
                        if (joined) waitingRoomId = code.trim().lowercase()
                    }
                }) { Text(if (isJoining) "Joining…" else "Join", color = KaspaTeal) }
            },
            dismissButton = { TextButton(onClick = { showJoinPrivate = false }, enabled = !isJoining) { Text("Cancel") } },
        )
    }
}

/** The one public room taking players: its seats, and Join - or where you already are. */
@Composable
private fun PublicRoomCard(
    room: ChessTournament?,
    now: Long,
    title: String,
    capacity: Int,
    me: String?,
    myActive: ChessTournament?,
    isJoining: Boolean,
    /** "Join (Fee: 0.00170000 KAS)" - what this join costs (iOS 3076f66). */
    joinLabel: String,
    onOpen: (String) -> Unit,
    onJoin: () -> Unit,
) {
    val colors = LocalAppColors.current
    // Only live seats count: one older than five minutes has expired (iOS 7206e25).
    val seated = room?.seatedPlayers(now).orEmpty()
    val count = seated.size
    val inThisRoom = me != null && me in seated
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (capacity == 2) Icons.Default.People else Icons.Default.Groups, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold)
                Text("$count of $capacity player${if (capacity == 1) "" else "s"} waiting", color = colors.textSecondary, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(capacity) { seat ->
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (seat < count) KaspaTeal else colors.textSecondary.copy(alpha = 0.25f)))
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            inThisRoom && room != null -> {
                ChessPill("You're in. Waiting for ${maxOf(0, capacity - count)} more…", filled = false) { onOpen(room.id) }
                if (me != null) {
                    room.seatExpiry(me)?.let { expiry ->
                        val left = maxOf(0L, (expiry - now) / 1000)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Your seat is held for ${left / 60}:%02d".format(left % 60) +
                                ". If the room hasn't filled by then, you're out of the queue.",
                            color = colors.textSecondary, fontSize = 12.sp,
                        )
                    }
                }
            }
            myActive != null ->
                ChessPill(
                    if (myActive.status == ChessTournament.Status.OPEN) "You're waiting in ${myActive.name}" else "You're playing in ${myActive.name}",
                    filled = false,
                ) { onOpen(myActive.id) }
            else -> ChessPill(if (isJoining) "Joining…" else joinLabel, filled = true, onClick = onJoin)
        }
    }
}

@Composable
private fun ChessPill(text: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (filled) KaspaTeal else KaspaTeal.copy(alpha = 0.15f))
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Color.Black else KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun ChessActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, color = KaspaTeal, fontSize = 15.sp)
    }
}

@Composable
private fun TournamentRow(tournament: ChessTournament, action: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                tournament.status == ChessTournament.Status.FINISHED -> Icons.Default.EmojiEvents
                !tournament.isPublic -> Icons.Default.Lock
                tournament.isDuel -> Icons.Default.People
                else -> Icons.Default.Groups
            },
            contentDescription = null,
            tint = KaspaTeal,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tournament.name, color = colors.textPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${tournament.players.size} of ${tournament.capacity} players" + if (tournament.isPublic) "" else " · code ${tournament.id}",
                color = colors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(action, color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

// MARK: - Leaderboard

/**
 * One kind's leaderboard, as the second tab of its screen: 1v1 is wins and losses in 1v1 games;
 * Tournaments is tournaments won, then the wins and losses inside them (iOS e86868e).
 */
@Composable
private fun ChessLeaderboardRows(mode: ChessLobbyMode) {
    val vm: ChessTournamentViewModel = hiltViewModel()
    val service = vm.service
    val colors = LocalAppColors.current
    val board by service.leaderboard.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val knsNames by service.knsNames.collectAsState()
    val me = service.myAddress
    val duel = mode == ChessLobbyMode.DUEL
    val rows = if (duel) {
        com.kachat.app.util.ChessTournamentEngine.duelLeaderboard(board)
    } else {
        com.kachat.app.util.ChessTournamentEngine.tournamentLeaderboard(board)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (rows.isEmpty()) {
            item {
                Text(
                    if (duel) "No finished 1v1 games yet." else "No finished tournaments yet.",
                    color = colors.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(20.dp),
                )
            }
        }
        itemsIndexed(rows, key = { _, row -> row.address }) { index, row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${index + 1}", color = colors.textSecondary, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(28.dp))
                ChessAvatar(row.address, contacts)
                Spacer(Modifier.width(12.dp))
                Text(
                    chessName(row.address, contacts, knsNames), color = colors.textPrimary, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (duel) {
                    Text("${row.duelWins} W", color = colors.success, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(10.dp))
                    Text("${row.duelLosses} L", color = colors.danger, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                } else {
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Color(0xFFFFCC00), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${row.tournamentsWon}", color = Color(0xFFFFCC00), fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                        }
                        Row {
                            Text("${row.tournamentGameWins} W", color = colors.success, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.width(8.dp))
                            Text("${row.tournamentGameLosses} L", color = colors.danger, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Kaspa Hub > Chess Online: choose your game. 1v1 or a tournament, each its own screen with that
 * kind's play tab and leaderboard. A room the player is already waiting or playing in is offered
 * first. Mirrors iOS's ChessHomeView.
 */
@Composable
fun ChessHomeScreen(navController: NavController, onBack: (() -> Unit)? = null) {
    val vm: ChessTournamentViewModel = hiltViewModel()
    val service = vm.service
    HoldArena(service)
    ChessErrorToast(service)
    val colors = LocalAppColors.current
    val all by service.tournaments.collectAsState()
    val mine = service.myActiveTournament(all)

    fun open(mode: ChessLobbyMode) = navController.navigate("chess_mode/${mode.name}")

    Scaffold(
        containerColor = colors.background,
        topBar = { MainPageHeader(title = "Chess Online", onBack = onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(
                androidx.compose.ui.res.painterResource(com.kachat.app.R.drawable.ic_kachat_logo),
                contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text("Choose your game", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Five minutes a side. Every move is a Kaspa transaction, so every game is on chain for good.",
                color = colors.textSecondary, fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(20.dp))
            if (mine != null) {
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(KaspaTeal.copy(alpha = 0.12f))
                        .clickable { open(if (mine.isDuel) ChessLobbyMode.DUEL else ChessLobbyMode.TOURNAMENT) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (mine.status == ChessTournament.Status.OPEN) Icons.Default.HourglassEmpty else Icons.Default.PlayArrow,
                        contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (mine.status == ChessTournament.Status.OPEN) "You're waiting in ${mine.name}" else "You're playing in ${mine.name}",
                            color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        )
                        Text("Tap to go back to it", color = colors.textSecondary, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
            ChessGameCard(
                icon = Icons.Default.People,
                title = "1v1",
                detail = "Play the next person who joins, or a friend by code. One game, winner takes the leaderboard point.",
            ) { open(ChessLobbyMode.DUEL) }
            Spacer(Modifier.height(16.dp))
            ChessGameCard(
                icon = Icons.Default.EmojiEvents,
                title = "Tournament",
                detail = "Eight players, single elimination: quarterfinals, semifinals, final. Public rooms fill as players arrive; private ones by code.",
            ) { open(ChessLobbyMode.TOURNAMENT) }
        }
    }
}

@Composable
private fun ChessGameCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(colors.surface).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)).background(KaspaTeal),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(detail, color = colors.textSecondary, fontSize = 14.sp)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

/**
 * The waiting room: covers the app from the moment a player joins a public 1v1 or tournament
 * room - or creates or joins a private one - until it fills, their seat runs out, or they
 * leave. Nothing else is reachable meanwhile: searching for players is the one thing happening.
 * Their avatar, a question mark for every empty seat, the time the seat is held for, the
 * private code to share, and Leave behind a warning. Mirrors iOS's ChessWaitingRoomView.
 */
@Composable
private fun ChessWaitingRoom(
    tournamentId: String,
    /** The room filled and the game exists: the caller opens it and closes this. */
    onStarted: (String) -> Unit,
    /** Left, or the seat ran out: the caller closes this (with a note when it ran out). */
    onFinished: (seatExpired: Boolean) -> Unit,
) {
    val vm: ChessTournamentViewModel = hiltViewModel()
    val service = vm.service
    HoldArena(service)
    ChessErrorToast(service)
    val colors = LocalAppColors.current
    val all by service.tournaments.collectAsState()
    val now by service.now.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val knsNames by service.knsNames.collectAsState()
    val tournament = all[tournamentId]
    val me = service.myAddress
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showLeaveWarning by remember { mutableStateOf(false) }
    var isLeaving by remember { mutableStateOf(false) }
    var handedOff by remember { mutableStateOf(false) }

    // Filled, or the seat ran out: hand the screen over. Runs on every tick.
    LaunchedEffect(tournament?.status, now, handedOff) {
        if (handedOff || tournament == null || me == null) return@LaunchedEffect
        when {
            tournament.status == ChessTournament.Status.LIVE || tournament.status == ChessTournament.Status.FINISHED -> {
                handedOff = true
                onStarted(tournament.id)
            }
            tournament.status == ChessTournament.Status.CANCELLED || !tournament.isSeated(me, now) -> {
                handedOff = true
                onFinished(tournament.status != ChessTournament.Status.CANCELLED)
            }
        }
    }

    Dialog(
        onDismissRequest = { },
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false,
        ),
    ) {
        // One Box so the leave sheet below draws OVER the room rather than under it.
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().background(colors.background).padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            val duel = tournament?.isDuel == true
            Text(
                if (duel) "Looking for an opponent" else "Waiting for players",
                color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp,
            )
            // You first, then whoever else is here, then a question mark for each empty seat.
            val capacity = tournament?.capacity ?: 2
            val seatedNow = tournament?.seatedPlayers(now).orEmpty()
            val shown = (listOfNotNull(me).filter { it in seatedNow } + seatedNow.filter { it != me })
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(if (capacity == 2) 2 else 4),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.padding(horizontal = 32.dp).heightIn(max = 260.dp),
            ) {
                items(shown.size) { index ->
                    val address = shown[index]
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ChessAvatar(address, contacts, size = 64)
                        Spacer(Modifier.height(8.dp))
                        Text(chessName(address, contacts, knsNames), color = colors.textPrimary, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                items(maxOf(0, capacity - shown.size)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(64.dp).clip(CircleShape).background(colors.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) { Text("?", color = colors.textSecondary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold) }
                        Spacer(Modifier.height(8.dp))
                        Text("Waiting", color = colors.textSecondary, fontSize = 12.sp)
                    }
                }
            }
            // The seat exists only once the join lands, which is a few seconds - until then the
            // clock has nothing to count, and a hard 0:00 would read as "already out".
            val expiry = me?.let { tournament?.seatExpiry(it) }
            val left = expiry?.let { maxOf(0L, (it - now) / 1000) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    left?.let { "%d:%02d".format(it / 60, it % 60) } ?: "--:--",
                    color = if (left != null && left < 30) colors.danger else colors.textPrimary,
                    fontSize = 44.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                )
                Text(
                    if (left == null) "Taking your seat - this is one transaction, so it takes a few seconds."
                    else "Your seat is held this long. If no one joins in time, you leave the queue.",
                    color = colors.textSecondary, fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            if (tournament != null && !tournament.isPublic) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Share this code", color = colors.textSecondary, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tournament.id, color = colors.textPrimary, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        IconButton(onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(tournament.id))
                            Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = KaspaTeal, modifier = Modifier.size(18.dp)) }
                        IconButton(onClick = {
                            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    "Play me at chess in KaChat: open Kaspa Hub > Chess > ${if (tournament.isDuel) "1v1" else "Tournaments"} > Join with a code, and enter ${tournament.id}",
                                )
                            }
                            context.startActivity(android.content.Intent.createChooser(share, null))
                        }) { Icon(Icons.Default.Share, contentDescription = "Share", tint = KaspaTeal, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
            Text(
                if (duel) "You're paired with the next person who joins. The game starts by itself."
                else "The tournament starts by itself when all eight seats are taken.",
                color = colors.textSecondary, fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Box(
                Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(colors.danger.copy(alpha = 0.12f))
                    .clickable { showLeaveWarning = true }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (isLeaving) "Leaving…" else "Leave", color = colors.danger, fontWeight = FontWeight.SemiBold)
            }
        }

        // Drawn in the dialog's OWN window, deliberately: a ModalBottomSheet (ActionSheetContainer)
        // opened from inside a Dialog goes to a window of its own behind this one and is never
        // seen - so Leave looked like it did nothing at all. Same shape as the Resign sheet.
        if (showLeaveWarning) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    ) { showLeaveWarning = false },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .background(colors.background)
                        .clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        ) {}
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = colors.danger, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Leave the queue?", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Leaving means you will no longer be searching for another player. Leaving is one transaction; you can join again any time.",
                        color = colors.textSecondary, fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.danger)
                            .clickable {
                                val room = tournament
                                showLeaveWarning = false
                                if (room != null && !isLeaving) {
                                    isLeaving = true
                                    vm.launch {
                                        service.leave(room)
                                        isLeaving = false
                                        handedOff = true
                                        onFinished(false)
                                    }
                                }
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (isLeaving) "Leaving…" else "Leave", color = Color.White, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceVariant)
                            .clickable { showLeaveWarning = false }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Keep waiting", color = colors.textPrimary, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        }
    }
}

// MARK: - One tournament

/**
 * The bracket, the players, and the lobby chat. A player is taken straight to their game the
 * moment it exists (round 1 at the eighth join; later rounds when the pair's other game ends),
 * and can tap any game to watch it meanwhile.
 */
@Composable
fun ChessTournamentScreen(tournamentId: String, navController: NavController) {
    val vm: ChessTournamentViewModel = hiltViewModel()
    val service = vm.service
    HoldArena(service)
    ChessErrorToast(service)
    val colors = LocalAppColors.current
    val all by service.tournaments.collectAsState()
    val now by service.now.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val knsNames by service.knsNames.collectAsState()
    val tournament = all[tournamentId]
    val me = service.myAddress
    // Saveable: backing out of the game recomposes this screen, and a plain remember would
    // forget the game was opened and push it straight back.
    var autoOpenedGameId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var chatText by remember { mutableStateOf("") }
    var isJoining by remember { mutableStateOf(false) }
    var showCancelConfirm by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current

    fun openGame(gameId: String) = navController.navigate("chess_tournament_game/$tournamentId/$gameId")

    // The player's game came into being: open it (once per game).
    LaunchedEffect(tournament?.games?.size) {
        val t = tournament ?: return@LaunchedEffect
        val mine = me ?: return@LaunchedEffect
        val game = t.currentGame(mine) ?: return@LaunchedEffect
        if (game.isOver || autoOpenedGameId == game.id) return@LaunchedEffect
        autoOpenedGameId = game.id
        openGame(game.id)
    }

    Scaffold(
        containerColor = colors.background,
        topBar = { MainPageHeader(title = tournament?.name ?: "Tournament", onBack = { navController.popBackStack() }) },
    ) { padding ->
        if (tournament == null) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = KaspaTeal)
                Spacer(Modifier.height(12.dp))
                Text("Loading the tournament from the arena…", color = colors.textSecondary, fontSize = 14.sp)
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            ChessCard {
                Column(Modifier.padding(16.dp)) {
                    when (tournament.status) {
                        ChessTournament.Status.OPEN -> {
                            Text(
                                if (tournament.isDuel) "Waiting for your opponent. The game starts by itself when they join."
                                else "Waiting for ${tournament.seatsLeft} more player${if (tournament.seatsLeft == 1) "" else "s"}. It starts by itself when the eighth joins.",
                                color = colors.textPrimary, fontSize = 14.sp,
                            )
                            if (me != null && !tournament.isSeated(me, now)) {
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        if (isJoining) return@Button
                                        isJoining = true
                                        vm.launch { service.join(tournament); isJoining = false }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(if (isJoining) "Joining…" else service.joinLabel(tournament.id), fontWeight = FontWeight.SemiBold) }
                            }
                            if (tournament.creator == me && !tournament.isPublic) {
                                TextButton(onClick = { showCancelConfirm = true }) { Text("Cancel tournament", color = colors.danger) }
                            }
                            if (!tournament.isPublic) {
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Code: ${tournament.id}", color = colors.textPrimary, fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(tournament.id))
                                        Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Copy", color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    IconButton(onClick = {
                                        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(
                                                android.content.Intent.EXTRA_TEXT,
                                                "Join my KaChat chess tournament: open Kaspa Hub > Chess > Join with a code, and enter ${tournament.id}",
                                            )
                                        }
                                        context.startActivity(android.content.Intent.createChooser(share, null))
                                    }) { Icon(Icons.Default.Share, contentDescription = "Share", tint = KaspaTeal) }
                                }
                            }
                        }
                        ChessTournament.Status.LIVE -> {
                            val game = me?.let { tournament.currentGame(it) }
                            when {
                                game == null -> Text("In play. Tap any game to watch it live.", color = colors.textPrimary, fontSize = 14.sp)
                                game.isOver && game.winner == me -> Text(
                                    "You won ${if (tournament.isDuel) "the game" else roundName(game.round)}. Waiting for your next opponent - watch the other game meanwhile.",
                                    color = colors.textPrimary, fontSize = 14.sp,
                                )
                                game.isOver -> Text("You are out of this tournament. Watch the rest of the bracket.", color = colors.textPrimary, fontSize = 14.sp)
                                else -> Row(Modifier.clickable { openGame(game.id) }, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = KaspaTeal)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Go to your game", color = KaspaTeal, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        ChessTournament.Status.FINISHED -> tournament.champion?.let { champion ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Color(0xFFFFCC00))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (tournament.isDuel) "${chessName(champion, contacts, knsNames)} won" else "${chessName(champion, contacts, knsNames)} won the tournament",
                                    color = Color(0xFFFFCC00), fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        ChessTournament.Status.CANCELLED -> Text("Cancelled by the creator.", color = colors.textSecondary, fontSize = 14.sp)
                    }
                }
            }

            if (tournament.status == ChessTournament.Status.OPEN) {
                val seated = tournament.seatedPlayers(now)
                ChessSectionHeader("Players (${seated.size} of ${tournament.capacity})")
                ChessCard {
                    seated.forEachIndexed { index, address ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChessAvatar(address, contacts)
                            Spacer(Modifier.width(12.dp))
                            Text(chessName(address, contacts, knsNames), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("Seed ${index + 1}", color = colors.textSecondary, fontSize = 12.sp)
                        }
                    }
                    repeat(maxOf(0, tournament.capacity - seated.size)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).border(1.dp, colors.textSecondary.copy(alpha = 0.4f), CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Text("Open seat", color = colors.textSecondary)
                        }
                    }
                }
            } else {
                for (round in 1..tournament.rounds) {
                    val games = tournament.gamesInRound(round)
                    if (games.isEmpty()) continue
                    ChessSectionHeader(if (tournament.isDuel) "Game" else when (round) { 3 -> "Final"; 2 -> "Semifinals"; else -> "Round 1" })
                    ChessCard {
                        games.forEach { game ->
                            Row(
                                Modifier.fillMaxWidth().clickable { openGame(game.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row {
                                        Text(chessName(game.white, contacts, knsNames), color = colors.textPrimary, fontSize = 14.sp,
                                            fontWeight = if (game.winner == game.white) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                                        Text("  vs  ", color = colors.textSecondary, fontSize = 14.sp)
                                        Text(chessName(game.black, contacts, knsNames), color = colors.textPrimary, fontSize = 14.sp,
                                            fontWeight = if (game.winner == game.black) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                                    }
                                    Text(gameStatus(game, contacts, knsNames), color = colors.textSecondary, fontSize = 12.sp)
                                }
                                if (!game.isOver) {
                                    Text(clockText(game.remainingMs(game.sideToMove, now)), color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

        }
    }

    if (showCancelConfirm && tournament != null) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Cancel this tournament?") },
            text = { Text("Everyone who joined is released. This is one transaction.") },
            confirmButton = {
                TextButton(onClick = { showCancelConfirm = false; vm.launch { service.cancel(tournament) } }) {
                    Text("Cancel tournament", color = colors.danger)
                }
            },
            dismissButton = { TextButton(onClick = { showCancelConfirm = false }) { Text("Keep it") } },
        )
    }
}

private fun roundName(round: Int) = when (round) { 3 -> "the final"; 2 -> "the semifinal"; else -> "round 1" }

private fun gameStatus(game: ChessTournamentGame, contacts: Map<String, ContactEntity>, knsNames: Map<String, String>): String {
    val winner = game.winner
    val outcome = game.outcome
    if (winner == null || outcome == null) {
        return "Move ${game.moves.size / 2 + 1} · ${if (game.sideToMove == ChessColor.WHITE) "white" else "black"} to move"
    }
    val who = chessName(winner, contacts, knsNames)
    return when (outcome) {
        ChessTournamentOutcome.Checkmate -> "$who won by checkmate"
        ChessTournamentOutcome.Resignation -> "$who won by resignation"
        ChessTournamentOutcome.Timeout -> "$who won on time"
        is ChessTournamentOutcome.DrawTiebreak -> "$who won on clock after a draw (${outcome.reason})"
    }
}

@Composable
private fun ChessComposer(text: String, onChange: (String) -> Unit, onSend: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            placeholder = { Text("Message (one transaction)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSend, enabled = text.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send",
                tint = if (text.isNotBlank()) KaspaTeal else LocalAppColors.current.textSecondary)
        }
    }
}


/** One bubble's delivery state - the three a 1:1 chat bubble has. */
private enum class ChessLineStatus { SENT, PENDING, FAILED }

/**
 * The moment a game ends, over the board: a burst for the winner, a quiet card for the loser, a
 * plain one for anyone watching. It stays about two seconds and the result screen follows.
 * Mirrors iOS's ChessGameEndOverlay.
 */
@Composable
private fun ChessGameEndOverlay(winnerName: String, outcome: ChessTournamentOutcome, iWon: Boolean?) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (appeared) 1f else 0.4f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f),
        label = "endScale",
    )
    // Sparks flying out from the middle - a real burst, not a static badge.
    val burst by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 1_100, delayMillis = 150),
        label = "endBurst",
    )
    val headline = when (iWon) {
        true -> "You won!"
        false -> "You lost"
        null -> "$winnerName won"
    }
    val detail = when (outcome) {
        ChessTournamentOutcome.Checkmate -> "Checkmate"
        ChessTournamentOutcome.Resignation -> "By resignation"
        ChessTournamentOutcome.Timeout -> "On time"
        is ChessTournamentOutcome.DrawTiebreak -> "Draw by ${outcome.reason} - won on clock"
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        if (iWon == true) {
            val sparks = listOf("✨", "🎉", "⭐", "🎊", "💫")
            repeat(18) { i ->
                val angle = i / 18.0 * 2 * Math.PI
                Text(
                    sparks[i % sparks.size],
                    fontSize = if (i % 3 == 0) 26.sp else 18.sp,
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = (kotlin.math.cos(angle) * 150f * burst).toFloat()
                            translationY = (kotlin.math.sin(angle) * 150f * burst).toFloat()
                            alpha = 1f - burst
                        },
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (appeared) 1f else 0f },
        ) {
            Icon(
                when (iWon) {
                    true -> Icons.Default.EmojiEvents
                    false -> Icons.Default.Flag
                    null -> Icons.Default.CheckCircle
                },
                contentDescription = null,
                tint = if (iWon == true) Color(0xFFFFCC00) else Color.White,
                modifier = Modifier.size(54.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(headline, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text(detail, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * After a game: the player's record on the board this game counts on (1v1 or Tournaments), with
 * the change this game made counted up from [before], and the top of that board around them.
 * Mirrors iOS's ChessGameResultView.
 */
@Composable
private fun ChessGameResultScreen(
    tournamentId: String,
    gameId: String,
    /** The player's record before this game landed - what the screen counts up from. */
    before: com.kachat.app.util.ChessLeaderboardRow?,
    onDone: () -> Unit,
) {
    val vm: ChessTournamentViewModel = hiltViewModel()
    val service = vm.service
    HoldArena(service)
    val colors = LocalAppColors.current
    val all by service.tournaments.collectAsState()
    val leaderboard by service.leaderboard.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val knsNames by service.knsNames.collectAsState()
    val me = service.myAddress
    val tournament = all[tournamentId]
    val game = tournament?.games?.get(gameId)
    val isDuel = tournament?.isDuel ?: true
    val board = if (isDuel) {
        com.kachat.app.util.ChessTournamentEngine.duelLeaderboard(leaderboard)
    } else {
        com.kachat.app.util.ChessTournamentEngine.tournamentLeaderboard(leaderboard)
    }
    val mine = board.firstOrNull { it.address == me }
    val rank = board.indexOfFirst { it.address == me }.takeIf { it >= 0 }?.plus(1)
    val iWon = game?.winner != null && game.winner == me
    fun wins(row: com.kachat.app.util.ChessLeaderboardRow?) = if (isDuel) row?.duelWins ?: 0 else row?.tournamentGameWins ?: 0
    fun losses(row: com.kachat.app.util.ChessLeaderboardRow?) = if (isDuel) row?.duelLosses ?: 0 else row?.tournamentGameLosses ?: 0
    // The figures start where they stood and settle on the new ones a beat later.
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(500); revealed = true }

    Dialog(
        onDismissRequest = onDone,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            containerColor = colors.background,
            topBar = {
                MainPageHeader(title = if (isDuel) "1v1" else "Tournament") {
                    TextButton(onClick = onDone) { Text("Done", color = KaspaTeal, fontWeight = FontWeight.SemiBold) }
                }
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))
                Icon(
                    if (iWon) Icons.Default.EmojiEvents else Icons.Default.Flag,
                    contentDescription = null,
                    tint = if (iWon) Color(0xFFFFCC00) else colors.textSecondary,
                    modifier = Modifier.size(44.dp),
                )
                Text(if (iWon) "Victory" else "Defeat", color = colors.textPrimary, fontSize = 32.sp, fontWeight = FontWeight.Black)
                if (game != null) {
                    Text(gameStatus(game, contacts, knsNames), color = colors.textSecondary, fontSize = 14.sp)
                }
                Spacer(Modifier.height(20.dp))
                // The record: before -> after, on the board this game counts on.
                Column(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(colors.surface).padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (isDuel) "YOUR 1V1 RECORD" else "YOUR TOURNAMENT RECORD",
                        color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        val shownWins = if (revealed) wins(mine) else wins(before)
                        val shownLosses = if (revealed) losses(mine) else losses(before)
                        ChessResultStat("Wins", shownWins, if (iWon) 1 else 0, colors.success, revealed)
                        val total = shownWins + shownLosses
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (total == 0) "-" else "%.0f%%".format(shownWins * 100.0 / total),
                                color = colors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text("Win rate", color = colors.textSecondary, fontSize = 12.sp)
                        }
                        ChessResultStat("Losses", shownLosses, if (iWon) 0 else 1, colors.danger, revealed)
                    }
                    if (rank != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "#$rank on the ${if (isDuel) "1v1" else "tournament"} board",
                            color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        )
                    }
                }
                if (tournament != null && !tournament.isDuel) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        when {
                            iWon && tournament.status == ChessTournament.Status.FINISHED -> "You won the tournament."
                            iWon -> "You go through to the next round. Your next game opens by itself when your opponent is decided."
                            else -> "You are out of this tournament. You can watch the rest of the bracket."
                        },
                        color = colors.textSecondary, fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    if (isDuel) "1V1 LEADERBOARD" else "TOURNAMENT LEADERBOARD",
                    color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Start).padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                board.take(5).forEachIndexed { index, row ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (row.address == me) KaspaTeal.copy(alpha = 0.12f) else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${index + 1}", color = colors.textSecondary, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(24.dp))
                        ChessAvatar(row.address, contacts, size = 32)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            chessName(row.address, contacts, knsNames), color = colors.textPrimary,
                            fontWeight = if (row.address == me) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (isDuel) {
                            Text("${row.duelWins} W  ${row.duelLosses} L", color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        } else {
                            Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Color(0xFFFFCC00), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${row.tournamentsWon}", color = Color(0xFFFFCC00), fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.width(8.dp))
                            Text("${row.tournamentGameWins} W  ${row.tournamentGameLosses} L", color = colors.textPrimary,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChessResultStat(label: String, value: Int, delta: Int, color: Color, revealed: Boolean) {
    val colors = LocalAppColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", color = color, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = colors.textSecondary, fontSize = 12.sp)
        Text(
            if (delta > 0) "+$delta" else " ",
            color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            modifier = Modifier.graphicsLayer { alpha = if (revealed && delta > 0) 1f else 0f },
        )
    }
}

// MARK: - One game

/**
 * The board with both clocks, the controls, and the game's chat beneath it - laid out like a
 * 1:1 chat with the board where the messages would be. Players tap to move; everyone else
 * watches the same board live.
 */
@Composable
fun ChessTournamentGameScreen(tournamentId: String, gameId: String, navController: NavController) {
    val vm: ChessTournamentViewModel = hiltViewModel()
    val service = vm.service
    HoldArena(service)
    ChessErrorToast(service)
    val colors = LocalAppColors.current
    val all by service.tournaments.collectAsState()
    val now by service.now.collectAsState()
    val pending by service.pendingMoveGames.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val knsNames by service.knsNames.collectAsState()
    val tournament = all[tournamentId]
    val game = tournament?.games?.get(gameId)
    val me = service.myAddress
    val myColor = me?.let { game?.color(it) }
    val isPending = "$tournamentId|$gameId" in pending
    val isMyTurn = game != null && myColor != null && !game.isOver && game.sideToMove == myColor && !isPending
    // The board is drawn from the viewer's side: black players see black at the bottom.
    val flipped = myColor == ChessColor.BLACK
    var selectedSquare by remember { mutableStateOf<ChessSquare?>(null) }
    var pendingPromotion by remember { mutableStateOf<ChessMove?>(null) }
    var showResignConfirm by remember { mutableStateOf(false) }
    var chatText by remember { mutableStateOf("") }
    val pendingChat by service.pendingChat.collectAsState()
    // End-of-game flow: the burst over the board, then the result screen (players only).
    var showEndOverlay by remember { mutableStateOf(false) }
    var showResult by remember { mutableStateOf(false) }
    var endHandledForGame by remember { mutableStateOf<String?>(null) }
    // The player's record as it stood while the game was on - the result screen counts up from it.
    var recordBeforeEnd by remember { mutableStateOf<com.kachat.app.util.ChessLeaderboardRow?>(null) }
    val leaderboard by service.leaderboard.collectAsState()

    // Kept fresh while the game is on (the arena may still be loading when the screen opens);
    // once the game is over it is left alone.
    LaunchedEffect(leaderboard, game?.isOver) {
        val address = me ?: return@LaunchedEffect
        if (game?.isOver != false) return@LaunchedEffect
        recordBeforeEnd = leaderboard.firstOrNull { it.address == address }
            ?: com.kachat.app.util.ChessLeaderboardRow(address)
    }

    // The game just ended: the burst over the board for a couple of seconds, then - for the two
    // players, not for someone watching - the result screen (iOS 759a2d3).
    LaunchedEffect(game?.isOver, game?.id) {
        val finished = game ?: return@LaunchedEffect
        if (!finished.isOver || finished.winner == null || endHandledForGame == finished.id) return@LaunchedEffect
        endHandledForGame = finished.id
        // Opened on a game that was already over (watching a finished bracket): nothing to show.
        if ((finished.endedAt ?: 0L) <= service.now.value - 60_000) return@LaunchedEffect
        showEndOverlay = true
        kotlinx.coroutines.delay(2_400)
        showEndOverlay = false
        if (myColor != null) showResult = true
    }

    val title = game?.let { if (tournament?.isDuel == true) "1v1" else when (it.round) { 3 -> "Final"; 2 -> "Semifinal"; else -> "Round 1" } } ?: "Game"
    Scaffold(
        containerColor = colors.background,
        topBar = { MainPageHeader(title = title, onBack = { navController.popBackStack() }) },
        bottomBar = {
            if (tournament != null && game != null) {
                Box(Modifier.background(colors.surface).navigationBarsPadding().imePadding()) {
                    ChessComposer(chatText, onChange = { chatText = it }) {
                        val text = chatText
                        chatText = ""
                        vm.launch { service.sendChat(text, tournament, game) }
                    }
                }
            }
        },
    ) { padding ->
        if (tournament == null || game == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = KaspaTeal) }
            return@Scaffold
        }
        val legalDestinations: List<ChessSquare> = selectedSquare?.takeIf { isMyTurn }?.let { from ->
            ChessEngine.legalMoves(from, game.board).map { it.to }
        } ?: emptyList()

        fun tap(sq: ChessSquare) {
            if (!isMyTurn || myColor == null) return
            val from = selectedSquare
            if (from != null) {
                if (sq in legalDestinations) {
                    val move = ChessMove(from, sq, null)
                    val piece = game.board.piece(from)
                    selectedSquare = null
                    if (piece?.type == ChessPieceType.PAWN && sq.rank == (if (myColor == ChessColor.WHITE) 7 else 0)) {
                        pendingPromotion = move
                    } else {
                        vm.launch { service.play(move, tournament, game) }
                    }
                    return
                }
                selectedSquare = if (game.board.piece(sq)?.color == myColor) sq else null
                return
            }
            if (game.board.piece(sq)?.color == myColor) selectedSquare = sq
        }

        // The same arrangement as the 1:1 chat's board: a header with the opponent and the
        // status, the other side's clock chip, the board as large as the width allows, our clock
        // chip, then the chat taking what is left with the composer pinned under it (iOS b27b4c8).
        Column(Modifier.fillMaxSize().padding(padding).padding(top = 8.dp)) {
            val statusText = run {
                val winner = game.winner
                val outcome = game.outcome
                if (winner != null && outcome != null) {
                    val who = chessName(winner, contacts, knsNames)
                    when (outcome) {
                        ChessTournamentOutcome.Checkmate -> "Checkmate. $who won."
                        ChessTournamentOutcome.Resignation -> "$who won by resignation."
                        ChessTournamentOutcome.Timeout -> "$who won on time."
                        is ChessTournamentOutcome.DrawTiebreak -> "Draw by ${outcome.reason}. $who won on clock."
                    }
                } else if (isPending) {
                    "Sending your move…"
                } else if (myColor != null) {
                    if (game.sideToMove == myColor && ChessEngine.isKingInCheck(game.sideToMove, game.board)) "Check. Your move."
                    else if (game.sideToMove == myColor) "Your move" else "Their move"
                } else {
                    if (game.sideToMove == ChessColor.WHITE) "White to move" else "Black to move"
                }
            }
            // Header: a player sees the opponent (their own name is on their clock chip); a
            // spectator sees both.
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (myColor != null) chessName(game.address(myColor.opposite), contacts, knsNames)
                    else "${chessName(game.white, contacts, knsNames)} vs ${chessName(game.black, contacts, knsNames)}",
                    color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        statusText,
                        color = if (game.isOver) colors.textSecondary else colors.textPrimary,
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    )
                    if (myColor != null && !game.isOver) {
                        TextButton(onClick = { showResignConfirm = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Icon(Icons.Default.Flag, contentDescription = null, tint = colors.danger, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Resign", color = colors.danger, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ChessClockChip(game, if (flipped) ChessColor.WHITE else ChessColor.BLACK, now, myColor, contacts, knsNames)
            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))) {
                val size = maxWidth / 8
                val ranks = if (flipped) (0..7).toList() else (7 downTo 0).toList()
                val files = if (flipped) (7 downTo 0).toList() else (0..7).toList()
                val last = game.moves.lastOrNull()
                Column {
                    for (rank in ranks) {
                        Row {
                            for (file in files) {
                                val sq = ChessSquare(file, rank)
                                val isLight = (file + rank) % 2 != 0
                                Box(
                                    modifier = Modifier
                                        .size(size)
                                        .background(if (isLight) ChessLightSquareColor else ChessDarkSquareColor)
                                        .clickable { tap(sq) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (last != null && (last.from == sq || last.to == sq)) {
                                        Box(Modifier.fillMaxSize().background(Color.Yellow.copy(alpha = 0.35f)))
                                    }
                                    if (selectedSquare == sq) Box(Modifier.fillMaxSize().background(KaspaTeal.copy(alpha = 0.35f)))
                                    game.board.piece(sq)?.let { ChessPieceGlyph(it, fontSize = (size.value * 0.7f).sp) }
                                    if (sq in legalDestinations) {
                                        Box(Modifier.size(size * 0.3f).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.55f)))
                                    }
                                }
                            }
                        }
                    }
                }
                // The same "Waiting on opponent..." as the 1:1 board, while it is their move.
                if (myColor != null && !game.isOver && game.sideToMove != myColor) {
                    WaitingOnOpponentOverlay()
                }
                if (showEndOverlay) {
                    val winner = game.winner
                    val outcome = game.outcome
                    if (winner != null && outcome != null) {
                        ChessGameEndOverlay(
                            winnerName = chessName(winner, contacts, knsNames),
                            outcome = outcome,
                            iWon = if (myColor == null) null else winner == me,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ChessClockChip(game, if (flipped) ChessColor.BLACK else ChessColor.WHITE, now, myColor, contacts, knsNames)
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))
            // Lines the chain returned (green check on ours), then ours still on the way (clock)
            // or failed (red) - the three states a 1:1 chat bubble has. Its own scrolling area,
            // so the board and clocks stay put.
            val lines = tournament.chat.filter { it.game == game.id }.takeLast(120).map { it to ChessLineStatus.SENT } +
                pendingChat.filter { it.tournament == tournament.id && it.line.game == game.id }
                    .map { it.line to if (it.failed) ChessLineStatus.FAILED else ChessLineStatus.PENDING }
            val chatListState = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(lines.size) { if (lines.isNotEmpty()) chatListState.animateScrollToItem(lines.size - 1) }
            LazyColumn(
                state = chatListState,
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 96.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                if (lines.isEmpty()) {
                    item {
                        Text(
                            "No messages yet. Each message is one transaction.",
                            color = colors.textSecondary, fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                items(lines.size, key = { lines[it].first.id }) { index ->
                    val (line, status) = lines[index]
                val mine = line.sender == me
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        Column(
                            Modifier
                                .widthIn(max = 280.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (mine) KaspaTeal else colors.surfaceVariant)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            if (!mine) Text(chessName(line.sender, contacts, knsNames), color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(line.text, color = if (mine) Color.Black else colors.textPrimary, fontSize = 14.sp)
                        }
                        if (mine) {
                            // Under the bubble, as in a 1:1 chat: green check once the chain has it.
                            Icon(
                                when (status) {
                                    ChessLineStatus.SENT -> Icons.Default.CheckCircle
                                    ChessLineStatus.PENDING -> Icons.Default.Schedule
                                    ChessLineStatus.FAILED -> Icons.Default.Error
                                },
                                contentDescription = null,
                                tint = when (status) {
                                    ChessLineStatus.SENT -> colors.success
                                    ChessLineStatus.PENDING -> colors.textSecondary
                                    ChessLineStatus.FAILED -> colors.danger
                                },
                                modifier = Modifier.size(12.dp).padding(top = 2.dp),
                            )
                        }
                    }
                }
                }
            }
        }

        pendingPromotion?.let { move ->
            Dialog(onDismissRequest = { pendingPromotion = null }) {
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Promote to", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        listOf(ChessPieceType.QUEEN, ChessPieceType.ROOK, ChessPieceType.BISHOP, ChessPieceType.KNIGHT).forEach { type ->
                            Box(
                                Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant).clickable {
                                    pendingPromotion = null
                                    vm.launch { service.play(ChessMove(move.from, move.to, type), tournament, game) }
                                },
                                contentAlignment = Alignment.Center,
                            ) { ChessPieceGlyph(ChessPiece(type, myColor ?: ChessColor.WHITE), fontSize = 40.sp) }
                        }
                    }
                }
            }
        }
    }

    if (showResignConfirm && tournament != null && game != null) {
        val opponent = chessName(game.address(if (myColor == ChessColor.WHITE) ChessColor.BLACK else ChessColor.WHITE), contacts, knsNames)
        ActionSheetContainer(title = "Resign this game?", subtitle = null, onDismiss = { showResignConfirm = false }) {
            Icon(Icons.Default.Flag, contentDescription = null, tint = colors.danger,
                modifier = Modifier.size(34.dp).align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(10.dp))
            Text(
                if (tournament.isDuel) "$opponent wins, and it counts as a loss on the leaderboard. Resigning is one transaction."
                else "$opponent goes through and you are out of the tournament. It counts as a loss on the leaderboard. Resigning is one transaction.",
                color = colors.textSecondary, fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.danger)
                    .clickable { showResignConfirm = false; vm.launch { service.resign(tournament, game) } }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Resign", color = Color.White, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceVariant)
                    .clickable { showResignConfirm = false }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Keep playing", color = colors.textPrimary, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showResult && tournament != null && game != null) {
        ChessGameResultScreen(
            tournamentId = tournamentId,
            gameId = gameId,
            before = recordBeforeEnd,
            onDone = { showResult = false },
        )
    }
}

@Composable
private fun ChessClockChip(
    game: ChessTournamentGame,
    color: ChessColor,
    now: Long,
    myColor: ChessColor?,
    contacts: Map<String, ContactEntity>,
    knsNames: Map<String, String>,
) {
    // The 1:1 board's clock chip: label, timer, time - lit while that side is to move, red under
    // twenty seconds; the side's avatar and name sit at the leading edge (iOS b27b4c8).
    val colors = LocalAppColors.current
    val address = game.address(color)
    val remaining = game.remainingMs(color, now)
    val isActive = !game.isOver && game.sideToMove == color
    val isLow = remaining < 20_000
    val label = when {
        myColor == null -> if (color == ChessColor.WHITE) "White" else "Black"
        color == myColor -> "You"
        else -> "Them"
    }
    val tint = when {
        isLow -> colors.danger.copy(alpha = 0.14f)
        isActive -> KaspaTeal.copy(alpha = 0.12f)
        else -> colors.surface
    }
    val stroke = when {
        isLow -> colors.danger.copy(alpha = 0.55f)
        isActive -> KaspaTeal.copy(alpha = 0.6f)
        else -> colors.divider
    }
    val content = when {
        isLow -> colors.danger
        isActive -> colors.textPrimary
        else -> colors.textSecondary
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChessAvatar(address, contacts, size = 26)
        Spacer(Modifier.width(8.dp))
        Text(
            chessName(address, contacts, knsNames), color = colors.textSecondary, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(tint)
                .border(if (isActive) 1.2.dp else 0.8.dp, stroke, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Default.Timer, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            val tenths = (remaining / 100).toInt()
            val seconds = tenths / 10
            Text(
                if (seconds < 10) "0:%02d.%d".format(seconds, tenths % 10) else "%d:%02d".format(seconds / 60, seconds % 60),
                color = content, fontFamily = FontFamily.Monospace, fontSize = 15.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
            )
        }
    }
}
