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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
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

/** "You", the contact's name, or the short address - iOS's ContactsManager.displayName. */
private fun chessName(address: String, me: String?, contacts: Map<String, ContactEntity>): String = when {
    address == me -> "You"
    contacts[address] != null -> contacts[address]!!.displayName
    else -> KaspaAddress.shortDisplay(address)
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

/**
 * The lobby: your tournament first (if you are in one), then open rooms waiting for players,
 * tournaments in play (anyone can watch), recent results, and the leaderboard.
 */
@Composable
fun ChessTournamentsScreen(navController: NavController, onBack: (() -> Unit)? = null) {
    val vm: ChessTournamentViewModel = hiltViewModel()
    val service = vm.service
    HoldArena(service)
    ChessErrorToast(service)
    val colors = LocalAppColors.current
    val all by service.tournaments.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val me = service.myAddress
    val mine = service.myActiveTournament(all)
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            MainPageHeader(title = "Chess", onBack = onBack) {
                IconButton(onClick = { navController.navigate("chess_leaderboard") }) {
                    Icon(Icons.Default.EmojiEvents, contentDescription = "Leaderboard", tint = KaspaTeal)
                }
            }
        },
        floatingActionButton = {
            if (mine == null) {
                FloatingActionButton(
                    onClick = { newName = ""; showCreate = true },
                    containerColor = KaspaTeal,
                    contentColor = Color.Black,
                    shape = CircleShape,
                ) { Icon(Icons.Default.Add, contentDescription = "Start a tournament") }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 96.dp),
        ) {
            fun open(t: ChessTournament) = navController.navigate("chess_tournament/${t.id}")
            if (mine != null) {
                ChessSectionHeader("Your tournament")
                ChessCard {
                    TournamentRow(mine, if (mine.status == ChessTournament.Status.OPEN) "Waiting for players" else "In play", me, contacts) { open(mine) }
                }
            }
            val openList = service.openTournaments(all).filter { it.id != mine?.id }
            ChessSectionHeader("Open")
            ChessCard {
                if (openList.isEmpty()) {
                    Text(
                        "No one is waiting for players right now. Start a tournament and seven others can join.",
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                openList.forEach { t ->
                    TournamentRow(t, "${t.seatsLeft} seat${if (t.seatsLeft == 1) "" else "s"} left", me, contacts) { open(t) }
                }
            }
            Text(
                "Eight players, single elimination, five minutes a side. Every move is a Kaspa transaction (about 0.0017 KAS each).",
                color = colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            val live = service.liveTournaments(all).filter { it.id != mine?.id }
            if (live.isNotEmpty()) {
                ChessSectionHeader("In play")
                ChessCard { live.forEach { t -> TournamentRow(t, "Watch", me, contacts) { open(t) } } }
            }
            val done = service.finishedTournaments(all).take(20)
            if (done.isNotEmpty()) {
                ChessSectionHeader("Finished")
                ChessCard {
                    done.forEach { t ->
                        TournamentRow(t, t.champion?.let { "Won by ${chessName(it, me, contacts)}" } ?: "Finished", me, contacts) { open(t) }
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { if (!isCreating) showCreate = false },
            title = { Text("Start a tournament") },
            text = {
                Column {
                    Text("You take the first seat. The tournament starts the moment eight players have joined. Creating it is one transaction.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, placeholder = { Text("Name") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isCreating) return@TextButton
                    isCreating = true
                    vm.launch {
                        val id = service.createTournament(newName)
                        isCreating = false
                        showCreate = false
                        if (id != null) navController.navigate("chess_tournament/$id")
                    }
                }) { Text(if (isCreating) "Starting…" else "Start", color = KaspaTeal) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }, enabled = !isCreating) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TournamentRow(
    tournament: ChessTournament,
    action: String,
    me: String?,
    contacts: Map<String, ContactEntity>,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (tournament.status == ChessTournament.Status.FINISHED) Icons.Default.EmojiEvents else Icons.Default.GridOn,
            contentDescription = null,
            tint = KaspaTeal,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tournament.name, color = colors.textPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${tournament.players.size} of ${ChessTournamentCodec.PLAYER_COUNT} players · by ${chessName(tournament.creator, me, contacts)}",
                color = colors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(action, color = KaspaTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

// MARK: - Leaderboard

/** Wins, losses and titles for every address seen in the arena - what the phone has read. */
@Composable
fun ChessLeaderboardScreen(navController: NavController) {
    val vm: ChessTournamentViewModel = hiltViewModel()
    val service = vm.service
    HoldArena(service)
    val colors = LocalAppColors.current
    val rows by service.leaderboard.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val me = service.myAddress
    Scaffold(
        containerColor = colors.background,
        topBar = { MainPageHeader(title = "Leaderboard", onBack = { navController.popBackStack() }) },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (rows.isEmpty()) {
                item {
                    Text("No finished games yet.", color = colors.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
                }
            }
            itemsIndexed(rows, key = { _, row -> row.address }) { index, row ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", color = colors.textSecondary, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(28.dp))
                    ChessAvatar(row.address, contacts)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(chessName(row.address, me, contacts), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${row.wins}W · ${row.losses}L · ${row.tournamentsPlayed} played", color = colors.textSecondary, fontSize = 12.sp)
                    }
                    if (row.tournamentsWon > 0) {
                        Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Color(0xFFFFCC00), modifier = Modifier.size(18.dp))
                        Text("${row.tournamentsWon}", color = Color(0xFFFFCC00), fontWeight = FontWeight.SemiBold)
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
    val tournament = all[tournamentId]
    val me = service.myAddress
    // Saveable: backing out of the game recomposes this screen, and a plain remember would
    // forget the game was opened and push it straight back.
    var autoOpenedGameId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var chatText by remember { mutableStateOf("") }
    var isJoining by remember { mutableStateOf(false) }
    var showCancelConfirm by remember { mutableStateOf(false) }

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
                                "Waiting for ${tournament.seatsLeft} more player${if (tournament.seatsLeft == 1) "" else "s"}. It starts by itself when the eighth joins.",
                                color = colors.textPrimary, fontSize = 14.sp,
                            )
                            if (me != null && me !in tournament.players) {
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
                                ) { Text(if (isJoining) "Joining…" else "Join (one transaction)", fontWeight = FontWeight.SemiBold) }
                            } else if (tournament.creator == me) {
                                TextButton(onClick = { showCancelConfirm = true }) { Text("Cancel tournament", color = colors.danger) }
                            }
                        }
                        ChessTournament.Status.LIVE -> {
                            val game = me?.let { tournament.currentGame(it) }
                            when {
                                game == null -> Text("In play. Tap any game to watch it live.", color = colors.textPrimary, fontSize = 14.sp)
                                game.isOver && game.winner == me -> Text(
                                    "You won ${roundName(game.round)}. Waiting for your next opponent - watch the other game meanwhile.",
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
                                Text("${chessName(champion, me, contacts)} won the tournament", color = Color(0xFFFFCC00), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        ChessTournament.Status.CANCELLED -> Text("Cancelled by the creator.", color = colors.textSecondary, fontSize = 14.sp)
                    }
                }
            }

            if (tournament.status == ChessTournament.Status.OPEN) {
                ChessSectionHeader("Players (${tournament.players.size} of ${ChessTournamentCodec.PLAYER_COUNT})")
                ChessCard {
                    tournament.players.forEachIndexed { index, address ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChessAvatar(address, contacts)
                            Spacer(Modifier.width(12.dp))
                            Text(chessName(address, me, contacts), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("Seed ${index + 1}", color = colors.textSecondary, fontSize = 12.sp)
                        }
                    }
                    repeat(tournament.seatsLeft) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).border(1.dp, colors.textSecondary.copy(alpha = 0.4f), CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Text("Open seat", color = colors.textSecondary)
                        }
                    }
                }
            } else {
                for (round in listOf(1, 2, 3)) {
                    val games = tournament.gamesInRound(round)
                    if (games.isEmpty()) continue
                    ChessSectionHeader(when (round) { 3 -> "Final"; 2 -> "Semifinals"; else -> "Round 1" })
                    ChessCard {
                        games.forEach { game ->
                            Row(
                                Modifier.fillMaxWidth().clickable { openGame(game.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row {
                                        Text(chessName(game.white, me, contacts), color = colors.textPrimary, fontSize = 14.sp,
                                            fontWeight = if (game.winner == game.white) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                                        Text("  vs  ", color = colors.textSecondary, fontSize = 14.sp)
                                        Text(chessName(game.black, me, contacts), color = colors.textPrimary, fontSize = 14.sp,
                                            fontWeight = if (game.winner == game.black) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                                    }
                                    Text(gameStatus(game, me, contacts), color = colors.textSecondary, fontSize = 12.sp)
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

            ChessSectionHeader("Lobby chat")
            ChessCard {
                val lines = tournament.chat.filter { it.game.isEmpty() }.takeLast(50)
                if (lines.isEmpty()) Text("Say hello.", color = colors.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                lines.forEach { line ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(chessName(line.sender, me, contacts), color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(line.text, color = colors.textPrimary, fontSize = 14.sp)
                    }
                }
                ChessComposer(chatText, onChange = { chatText = it }) {
                    val text = chatText
                    chatText = ""
                    vm.launch { service.sendChat(text, tournament, null) }
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

private fun gameStatus(game: ChessTournamentGame, me: String?, contacts: Map<String, ContactEntity>): String {
    val winner = game.winner
    val outcome = game.outcome
    if (winner == null || outcome == null) {
        return "Move ${game.moves.size / 2 + 1} · ${if (game.sideToMove == ChessColor.WHITE) "white" else "black"} to move"
    }
    val who = chessName(winner, me, contacts)
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

    val title = game?.let { when (it.round) { 3 -> "Final"; 2 -> "Semifinal"; else -> "Round 1" } } ?: "Game"
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

        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            ClockRow(game, if (flipped) ChessColor.WHITE else ChessColor.BLACK, now, me, contacts)
            Spacer(Modifier.height(12.dp))
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
            }
            Spacer(Modifier.height(12.dp))
            ClockRow(game, if (flipped) ChessColor.BLACK else ChessColor.WHITE, now, me, contacts)
            Spacer(Modifier.height(10.dp))
            val statusText = run {
                val winner = game.winner
                val outcome = game.outcome
                if (winner != null && outcome != null) {
                    val who = chessName(winner, me, contacts)
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
            Text(
                statusText,
                color = if (game.isOver) colors.textSecondary else colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            if (myColor != null && !game.isOver) {
                TextButton(onClick = { showResignConfirm = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.Default.Flag, contentDescription = null, tint = colors.danger, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Resign", color = colors.danger, fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))
            val lines = tournament.chat.filter { it.game == game.id }.takeLast(80)
            if (lines.isEmpty()) {
                Text("No messages yet.", color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
            lines.forEach { line ->
                val mine = line.sender == me
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Column(
                        Modifier
                            .widthIn(max = 280.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (mine) KaspaTeal else colors.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        if (!mine) Text(chessName(line.sender, me, contacts), color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(line.text, color = if (mine) Color.Black else colors.textPrimary, fontSize = 14.sp)
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
        AlertDialog(
            onDismissRequest = { showResignConfirm = false },
            title = { Text("Resign this game?") },
            confirmButton = {
                TextButton(onClick = { showResignConfirm = false; vm.launch { service.resign(tournament, game) } }) {
                    Text("Resign", color = colors.danger)
                }
            },
            dismissButton = { TextButton(onClick = { showResignConfirm = false }) { Text("Keep playing") } },
        )
    }
}

@Composable
private fun ClockRow(game: ChessTournamentGame, color: ChessColor, now: Long, me: String?, contacts: Map<String, ContactEntity>) {
    val colors = LocalAppColors.current
    val address = game.address(color)
    val remaining = game.remainingMs(color, now)
    val running = !game.isOver && game.sideToMove == color
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        ChessAvatar(address, contacts)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(chessName(address, me, contacts), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (color == ChessColor.WHITE) "White" else "Black", color = colors.textSecondary, fontSize = 12.sp)
        }
        val tenths = (remaining / 100).toInt()
        val seconds = tenths / 10
        val text = if (seconds < 10) "0:%02d.%d".format(seconds, tenths % 10) else "%d:%02d".format(seconds / 60, seconds % 60)
        Text(
            text,
            color = if (remaining < 20_000 && running) colors.danger else colors.textPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (running) KaspaTeal.copy(alpha = 0.2f) else colors.textSecondary.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
