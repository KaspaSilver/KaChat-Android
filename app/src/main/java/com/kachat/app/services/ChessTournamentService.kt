package com.kachat.app.services

import android.util.Log
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.util.ChessEngine
import com.kachat.app.util.ChessArenaEvent
import com.kachat.app.util.ChessLeaderboardRow
import com.kachat.app.util.ChessMove
import com.kachat.app.util.ChessTournament
import com.kachat.app.util.ChessTournamentCodec
import com.kachat.app.util.ChessTournamentEngine
import com.kachat.app.util.ChessTournamentGame
import com.kachat.app.util.ChessTournamentMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chess tournaments (5.1): the `#chess-arena` room, reduced by [ChessTournamentEngine] into the
 * bracket every phone agrees on, and the actions a player can take - each one a broadcast
 * transaction. Mirrors iOS's ChessTournamentService.
 *
 * The arena is machinery, not a chat: it is scanned only while a chess screen holds [acquire],
 * never notifies, and never appears in Public Chats (see [SERVICE_CHANNELS]).
 */
@Singleton
class ChessTournamentService @Inject constructor(
    private val database: KaChatDatabase,
    private val broadcastRepository: BroadcastRepository,
    private val scanningService: BroadcastScanningService,
    private val walletManager: WalletManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _tournaments = MutableStateFlow<Map<String, ChessTournament>>(emptyMap())
    val tournaments: StateFlow<Map<String, ChessTournament>> = _tournaments.asStateFlow()

    private val _leaderboard = MutableStateFlow<List<ChessLeaderboardRow>>(emptyList())
    val leaderboard: StateFlow<List<ChessLeaderboardRow>> = _leaderboard.asStateFlow()

    /** Chain time as this phone estimates it: wall clock, for running clocks between blocks. */
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Moves sent and not yet seen back from the chain ("<tournament>|<game>"), so the player
     *  cannot double-send. */
    private val _pendingMoveGames = MutableStateFlow<Set<String>>(emptySet())
    val pendingMoveGames: StateFlow<Set<String>> = _pendingMoveGames.asStateFlow()

    private val lock = Any()
    private var refCount = 0
    private var liveViewing: AutoCloseable? = null
    private var clockJob: Job? = null
    /** Claims already posted for a game - one is enough; the chain confirms it. */
    private val claimedGames = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        scope.launch {
            database.broadcastDao().getMessagesForChannel(ChessTournamentCodec.ARENA_CHANNEL)
                .distinctUntilChanged()
                .collect { rows -> reduce(rows) }
        }
    }

    val myAddress: String? get() = runCatching { walletManager.getAddress() }.getOrNull()

    // MARK: - Watching the arena

    /** A chess screen came on: keep the arena scanned while any is up. */
    fun acquire() = synchronized(lock) {
        refCount += 1
        if (refCount != 1) return@synchronized
        scope.launch {
            runCatching { broadcastRepository.joinChannel(ChessTournamentCodec.ARENA_CHANNEL) }
                .onFailure { Log.w(TAG, "Could not join the arena", it) }
        }
        liveViewing = scanningService.startLiveViewing(ChessTournamentCodec.ARENA_CHANNEL)
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive) {
                delay(200)
                _now.value = System.currentTimeMillis()
                claimTimeoutsIfDue()
            }
        }
    }

    fun release() = synchronized(lock) {
        refCount = maxOf(0, refCount - 1)
        if (refCount != 0) return@synchronized
        liveViewing?.close()
        liveViewing = null
        clockJob?.cancel()
        clockJob = null
    }

    private fun reduce(rows: List<com.kachat.app.models.BroadcastMessageEntity>) {
        val events = rows.mapNotNull { row ->
            if (row.deliveryStatus != "sent" || row.id.startsWith("pending_")) return@mapNotNull null
            val message = ChessTournamentCodec.decode(row.content) ?: return@mapNotNull null
            ChessArenaEvent(row.id, row.senderAddress, row.blockTimestamp, message)
        }
        val reduced = ChessTournamentEngine.reduce(events)
        _tournaments.value = reduced
        _leaderboard.value = ChessTournamentEngine.leaderboard(reduced.values)
        // Asked to join a public room that filled first: queue into the next one, once.
        val queued = queuedPublicRoomId
        val me = myAddress
        if (queued != null && me != null) {
            val room = reduced[queued]
            if (room != null && room.isFull && me !in room.players) {
                queuedPublicRoomId = null
                val wasDuel = ChessTournamentCodec.duelNumber(queued) != null
                scope.launch { if (wasDuel) joinPublicDuelQueue() else joinPublicQueue() }
            } else if (room != null && me in room.players) {
                queuedPublicRoomId = null
            }
        }
        // A move of ours that the chain now shows is no longer pending.
        val mine = myAddress
        _pendingMoveGames.value = _pendingMoveGames.value.filter { key ->
            val (tid, gid) = key.split('|', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return@filter true
            val game = reduced[tid]?.games?.get(gid) ?: return@filter true
            game.playerToMove == mine && !game.isOver
        }.toSet()
    }

    // MARK: - Lists

    /** The public room taking players right now: the first numbered room that is not full. Its
     *  id exists before anyone has joined it (the first join creates it), so the lobby can
     *  always show "Public tournament #N" with its seats. */
    fun currentPublicRoomId(all: Map<String, ChessTournament>): String {
        var number = 1
        while (all[ChessTournamentCodec.publicId(number)]?.isFull == true) number += 1
        return ChessTournamentCodec.publicId(number)
    }

    /** The public 1v1 room taking players right now. */
    fun currentDuelRoomId(all: Map<String, ChessTournament>): String {
        var number = 1
        while (all[ChessTournamentCodec.duelId(number)]?.isFull == true) number += 1
        return ChessTournamentCodec.duelId(number)
    }

    /** Private 1v1s this player is in, still open or in play. */
    fun myPrivateDuels(all: Map<String, ChessTournament>) = myPrivate(all, duel = true)

    /** Private tournaments this player is in, still open or in play. */
    fun myPrivateTournaments(all: Map<String, ChessTournament>) = myPrivate(all, duel = false)

    private fun myPrivate(all: Map<String, ChessTournament>, duel: Boolean): List<ChessTournament> {
        val me = myAddress ?: return emptyList()
        return all.values
            .filter {
                !it.isPublic && it.isDuel == duel && me in it.players &&
                    (it.status == ChessTournament.Status.OPEN || it.status == ChessTournament.Status.LIVE)
            }
            .sortedByDescending { it.createdAt }
    }

    fun openTournaments(all: Map<String, ChessTournament>) =
        all.values.filter { it.status == ChessTournament.Status.OPEN }.sortedByDescending { it.createdAt }

    fun liveTournaments(all: Map<String, ChessTournament>) =
        all.values.filter { it.status == ChessTournament.Status.LIVE }.sortedByDescending { it.startedAt ?: 0 }

    fun finishedTournaments(all: Map<String, ChessTournament>) =
        all.values.filter { it.status == ChessTournament.Status.FINISHED }.sortedByDescending { it.startedAt ?: 0 }

    /** The tournament this player is in that is not over, if any. A waiting seat that has
     *  expired does not count: the player is free to join elsewhere. */
    fun myActiveTournament(all: Map<String, ChessTournament>): ChessTournament? {
        val me = myAddress ?: return null
        val now = _now.value
        return all.values
            .filter {
                (it.status == ChessTournament.Status.LIVE && me in it.players) ||
                    (it.status == ChessTournament.Status.OPEN && it.isSeated(me, now))
            }
            .maxByOrNull { it.createdAt }
    }

    // MARK: - Actions (each one a broadcast transaction)

    /** The room this player asked to join and is waiting to appear in. */
    @Volatile
    private var queuedPublicRoomId: String? = null

    /** Joins the public room taking players now. If that room fills before this join lands
     *  (someone else got the last seat), [reduce] notices and joins the next room. */
    suspend fun joinPublicQueue() = joinQueue(currentPublicRoomId(_tournaments.value))

    /** Joins the public 1v1 room taking players now; same race handling as the tournaments. */
    suspend fun joinPublicDuelQueue() = joinQueue(currentDuelRoomId(_tournaments.value))

    private suspend fun joinQueue(id: String) {
        val me = myAddress ?: return
        if (myActiveTournament(_tournaments.value) != null) return
        if (_tournaments.value[id]?.players?.contains(me) == true) return
        queuedPublicRoomId = id
        send(ChessTournamentCodec.join(id))
    }

    /** A private 1v1 for a friend: no creator code, an eight-character code to share. */
    suspend fun createPrivateDuel(name: String): String? {
        val id = ChessTournamentCodec.newPrivateId()
        val clean = name.trim()
        if (!send(ChessTournamentCodec.createDuel(id, clean.ifEmpty { "1v1" }))) return null
        return id
    }

    /** A private tournament for friends. Needs the creator code; returns null (with a message)
     *  when it is wrong, without sending anything. */
    suspend fun createPrivateTournament(name: String, code: String): String? {
        if (!ChessTournamentCodec.isValidCreateKey(ChessTournamentCodec.createKey(code, "check"), "check")) {
            _lastError.value = "That creator code is not right."
            return null
        }
        val id = ChessTournamentCodec.newPrivateId()
        val clean = name.trim()
        if (!send(ChessTournamentCodec.create(id, clean.ifEmpty { "Private tournament" }, code))) return null
        return id
    }

    /** Joins a friend's private tournament or 1v1 by its code (the id). */
    suspend fun joinPrivate(rawCode: String): Boolean {
        val id = rawCode.trim().lowercase()
        val tournament = _tournaments.value[id]
        if (tournament == null || tournament.isPublic) {
            _lastError.value = "No open tournament with that code. Codes are eight characters; the tournament must exist and still have seats."
            return false
        }
        if (tournament.status != ChessTournament.Status.OPEN) {
            _lastError.value = "That tournament has already started."
            return false
        }
        join(tournament)
        return true
    }

    suspend fun join(tournament: ChessTournament) {
        val me = myAddress ?: return
        if (me in tournament.players || tournament.status != ChessTournament.Status.OPEN) return
        send(ChessTournamentCodec.join(tournament.id))
    }

    /** Gives the seat back while the room is still waiting (one transaction). */
    suspend fun leave(tournament: ChessTournament) {
        val me = myAddress ?: return
        if (tournament.status != ChessTournament.Status.OPEN || me !in tournament.players) return
        queuedPublicRoomId = null
        send(ChessTournamentCodec.leave(tournament.id))
    }

    suspend fun cancel(tournament: ChessTournament) {
        if (tournament.creator != myAddress || tournament.status != ChessTournament.Status.OPEN) return
        send(ChessTournamentCodec.cancel(tournament.id))
    }

    /** Plays a move if it is ours to make and legal; the board shows it as pending until the
     *  chain returns it. */
    suspend fun play(move: ChessMove, tournament: ChessTournament, game: ChessTournamentGame) {
        val me = myAddress ?: return
        val key = "${tournament.id}|${game.id}"
        if (game.playerToMove != me || game.isOver || key in _pendingMoveGames.value ||
            !ChessEngine.isLegal(move, game.board)) return
        val normalized = ChessEngine.normalizingPromotion(move, game.board)
        _pendingMoveGames.value = _pendingMoveGames.value + key
        val sent = send(
            ChessTournamentCodec.move(
                id = tournament.id, game = game.id, ply = game.moves.size + 1,
                from = normalized.from.algebraic, to = normalized.to.algebraic,
                promotion = normalized.promotion?.promotionLetter,
            )
        )
        if (!sent) _pendingMoveGames.value = _pendingMoveGames.value - key
    }

    suspend fun resign(tournament: ChessTournament, game: ChessTournamentGame) {
        val me = myAddress ?: return
        if (game.color(me) == null || game.isOver) return
        send(ChessTournamentCodec.resign(tournament.id, game.id))
    }

    suspend fun sendChat(text: String, tournament: ChessTournament, game: ChessTournamentGame?) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        send(ChessTournamentCodec.chat(tournament.id, game?.id, clean))
    }

    fun clearError() {
        _lastError.value = null
    }

    /** The opponent flagged in one of our games: post the claim (once). Runs on every tick. */
    private fun claimTimeoutsIfDue() {
        val me = myAddress ?: return
        val now = _now.value
        for (tournament in _tournaments.value.values) {
            if (tournament.status != ChessTournament.Status.LIVE) continue
            for (game in tournament.games.values) {
                if (game.isOver) continue
                val mine = game.color(me) ?: continue
                if (game.sideToMove == mine) continue
                val key = "${tournament.id}|${game.id}"
                if (key in claimedGames) continue
                // A second's margin past zero, so the claim's block time is safely after.
                if (game.remainingMs(game.sideToMove, now - 1_500) != 0L) continue
                claimedGames += key
                scope.launch { send(ChessTournamentCodec.claim(tournament.id, game.id)) }
            }
        }
    }

    /**
     * One broadcast transaction, with the retries a fast sequence of sends needs: the change of
     * the previous transaction is not spendable until it is mined, so a move sent within a
     * second of the last one can hit "no spendable UTXO" - the same retry iOS uses.
     */
    private suspend fun send(message: ChessTournamentMessage): Boolean {
        val content = ChessTournamentCodec.encode(message)
        var attempt = 0
        while (true) {
            try {
                broadcastRepository.sendBroadcast(ChessTournamentCodec.ARENA_CHANNEL, content)
                _lastError.value = null
                return true
            } catch (e: Exception) {
                attempt += 1
                if (attempt > 5) {
                    _lastError.value = e.message ?: "Could not send"
                    Log.w(TAG, "Send failed after retries", e)
                    return false
                }
                delay(1_200)
            }
        }
    }

    companion object {
        private const val TAG = "ChessTournament"

        /** Rooms the app uses as machinery, never shown as chats: the chess arena. Hidden from
         *  Public Chats, no unread, no banners (iOS BroadcastService.serviceChannels). */
        val SERVICE_CHANNELS: Set<String> = setOf(ChessTournamentCodec.ARENA_CHANNEL)
    }
}
