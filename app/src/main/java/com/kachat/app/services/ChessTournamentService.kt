package com.kachat.app.services

import android.util.Log
import com.kachat.app.repository.BroadcastRepository
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.util.ChessEngine
import com.kachat.app.util.ChessArenaEvent
import com.kachat.app.util.ChessLeaderboardRow
import com.kachat.app.util.ChessMove
import com.kachat.app.util.ChessPendingChatLine
import com.kachat.app.util.ChessTournamentChatLine
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
import kotlinx.coroutines.flow.first
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
    private val knsService: KnsService,
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

    /** Chat of ours not yet returned by the chain, oldest first - shown under the board with a
     *  clock (or a red mark when it failed), the same three states a 1:1 chat bubble has. */
    private val _pendingChat = MutableStateFlow<List<ChessPendingChatLine>>(emptyList())
    val pendingChat: StateFlow<List<ChessPendingChatLine>> = _pendingChat.asStateFlow()

    /** KNS domains for arena players, so a name can fall back to one - see [resolveNames]. */
    private val _knsNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val knsNames: StateFlow<Map<String, String>> = _knsNames.asStateFlow()

    /** Moves sent and not yet seen back from the chain ("<tournament>|<game>"), so the player
     *  cannot double-send. */
    private val _pendingMoveGames = MutableStateFlow<Set<String>>(emptySet())
    val pendingMoveGames: StateFlow<Set<String>> = _pendingMoveGames.asStateFlow()

    private val lock = Any()
    private var refCount = 0
    private var liveViewing: AutoCloseable? = null
    private var clockJob: Job? = null
    private var backfillJob: Job? = null
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
        // The live block scan only ever sees what is mined while it runs, so on its own a phone
        // opening Chess cannot see a seat taken a minute earlier - the two would never find each
        // other in the same room. The broadcast indexer serves the arena's history like a
        // curated room's, so it is read on open and kept fresh while a chess screen is up.
        backfillJob?.cancel()
        _historyReady.value = false
        backfillJob = scope.launch {
            val deadline = System.currentTimeMillis() + HISTORY_WAIT_MS
            while (isActive) {
                val fetched = runCatching { broadcastRepository.backfillFromIndexer(ChessTournamentCodec.ARENA_CHANNEL) }
                    .onFailure { Log.w(TAG, "Arena backfill failed", it) }
                    .getOrDefault(-1)
                // Answered (even with nothing), or the wait ran out: rooms can be picked.
                if (fetched >= 0 || System.currentTimeMillis() > deadline) _historyReady.value = true
                delay(ARENA_BACKFILL_INTERVAL_MS)
            }
        }
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
        backfillJob?.cancel()
        backfillJob = null
        clockJob?.cancel()
        clockJob = null
    }

    private fun reduce(rows: List<com.kachat.app.models.BroadcastMessageEntity>) {
        val events = rows.mapNotNull { row ->
            if (row.deliveryStatus != "sent" || row.id.startsWith("pending_")) return@mapNotNull null
            val message = ChessTournamentCodec.decode(row.content) ?: return@mapNotNull null
            ChessArenaEvent(row.id, row.senderAddress, row.blockTimestamp, message)
        }
        // Ours still on its way (or failed), which the chain has not returned yet.
        val mineAddress = myAddress
        _pendingChat.value = rows.mapNotNull { row ->
            if (row.deliveryStatus == "sent" || row.senderAddress != mineAddress) return@mapNotNull null
            val message = ChessTournamentCodec.decode(row.content) ?: return@mapNotNull null
            if (message.a != "chat") return@mapNotNull null
            val text = message.text?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ChessPendingChatLine(
                id = row.id,
                tournament = message.t,
                line = ChessTournamentChatLine(row.id, row.senderAddress, text, row.blockTimestamp, message.g ?: ""),
                failed = row.deliveryStatus == "failed",
            )
        }
        val reduced = ChessTournamentEngine.reduce(events)
        _tournaments.value = reduced
        resolveNames(reduced.values.flatMap { it.players }.toSet() + listOfNotNull(mineAddress))
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

    /** The public tournament room taking players right now - see [currentRoomNumber]. Its id
     *  exists before anyone has joined it (the first join creates it), so the lobby can always
     *  show "Public tournament #N" with its seats. */
    fun currentPublicRoomId(all: Map<String, ChessTournament>): String =
        ChessTournamentCodec.publicId(currentRoomNumber(all) { ChessTournamentCodec.publicNumber(it) })

    /** The public 1v1 room taking players right now. */
    fun currentDuelRoomId(all: Map<String, ChessTournament>): String =
        ChessTournamentCodec.duelId(currentRoomNumber(all) { ChessTournamentCodec.duelNumber(it) })

    /**
     * The room to queue into: the lowest-numbered public room of that kind still waiting for
     * players; when none is, one past the highest room this phone knows. Every phone with the
     * same recent history lands on the same number - and it does not need the history back to
     * room 1, which is why the engine no longer requires the previous room to be full
     * (iOS d2ab780).
     */
    private fun currentRoomNumber(all: Map<String, ChessTournament>, numberOf: (String) -> Int?): Int {
        var open: Int? = null
        var highest = 0
        for (room in all.values) {
            val number = numberOf(room.id) ?: continue
            highest = maxOf(highest, number)
            if (room.status == ChessTournament.Status.OPEN && !room.isFull) {
                open = minOf(open ?: number, number)
            }
        }
        return open ?: (highest + 1)
    }

    /**
     * True once the arena's history has come back from the indexer (or a few seconds have
     * passed). A join before that would pick a room from an empty view - room 1 - which
     * everyone else finished long ago.
     */
    private val _historyReady = MutableStateFlow(false)
    val historyReady: StateFlow<Boolean> = _historyReady.asStateFlow()

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
    suspend fun joinPublicQueue(): String? = joinPublicRoom(duel = false)

    /** Joins the public 1v1 room taking players now; same race handling as the tournaments. */
    suspend fun joinPublicDuelQueue(): String? = joinPublicRoom(duel = true)

    /**
     * The seat that ran out is still in `players` until the next join drops it (the engine judges
     * that at the join's block time) - so "already in" means seated NOW, never the stale list, or
     * a returning player's tap would do nothing at all. Every refusal says why (iOS b552d7d).
     *
     * Returns the room joined (or the one the player is already in), so the screen can open its
     * waiting room - and null when nothing was sent.
     */
    private suspend fun joinPublicRoom(duel: Boolean): String? {
        val me = myAddress ?: return null
        if (!_historyReady.value) {
            _lastError.value = "Still loading the rooms - try again in a moment."
            return null
        }
        // The freshest shared view first: whatever the indexer holds this second is what every
        // other phone is choosing from, and a phone choosing off its own stale view offers a room
        // the others have moved past (iOS 29bf054). The DAO flow delivers the merge a beat later,
        // so the rows are reduced here and now rather than read off the old state.
        runCatching { broadcastRepository.fetchNewestFromIndexer(ChessTournamentCodec.ARENA_CHANNEL) }
            .onFailure { Log.w(TAG, "Arena refresh before joining failed", it) }
        runCatching {
            reduce(database.broadcastDao().getMessagesForChannel(ChessTournamentCodec.ARENA_CHANNEL).first())
        }.onFailure { Log.w(TAG, "Arena reduce before joining failed", it) }
        val id = if (duel) currentDuelRoomId(_tournaments.value) else currentPublicRoomId(_tournaments.value)
        val busy = myActiveTournament(_tournaments.value)
        if (busy != null) {
            _lastError.value = if (busy.status == ChessTournament.Status.OPEN) {
                "You're already waiting in ${busy.name}."
            } else {
                "You're still playing in ${busy.name}."
            }
            return null
        }
        // Already seated here: no transaction, but the room is still where this player belongs.
        if (_tournaments.value[id]?.isSeated(me, _now.value) == true) return id
        queuedPublicRoomId = id
        return if (send(ChessTournamentCodec.join(id))) id else null
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
        if (tournament.status != ChessTournament.Status.OPEN) return
        if (tournament.isSeated(me, _now.value)) {
            _lastError.value = "You're already in this room."
            return
        }
        val busy = myActiveTournament(_tournaments.value)
        if (busy != null && busy.id != tournament.id) {
            _lastError.value = if (busy.status == ChessTournament.Status.OPEN) {
                "You're already waiting in ${busy.name}."
            } else {
                "You're still playing in ${busy.name}."
            }
            return
        }
        send(ChessTournamentCodec.join(tournament.id))
    }

    /** Gives the seat back while the room is still waiting (one transaction). */
    suspend fun leave(tournament: ChessTournament) {
        val me = myAddress ?: return
        if (tournament.status != ChessTournament.Status.OPEN || !tournament.isSeated(me, _now.value)) return
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

    // MARK: - Names

    /** Addresses whose KNS domain was asked for this session. */
    private val resolvedNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Names in the arena follow the app's rule - the contact's name, then their KNS domain, then
     * the shortened address - and the domain part needs a lookup. Asked once per address per
     * session; [knsNames] then answers, and the screens re-render when it lands (iOS 30d0cca).
     */
    private fun resolveNames(addresses: Set<String>) {
        val fresh = addresses.filter { it.isNotEmpty() && resolvedNames.add(it) }
        if (fresh.isEmpty()) return
        scope.launch {
            for (address in fresh) {
                val domain = runCatching { knsService.reverseResolve(address) }.getOrNull()
                if (!domain.isNullOrBlank()) _knsNames.value = _knsNames.value + (address to domain)
            }
        }
    }

    // MARK: - Fees

    /**
     * What sending [message] costs right now, as "0.0017 KAS", for a button label. An arena
     * message is a fixed-size payload with one input, like every arena send, so this is the same
     * local calculation the composers use - no network round trip (iOS 3076f66).
     */
    fun feeText(message: ChessTournamentMessage): String? = runCatching {
        val payload = com.kachat.app.util.MessageProtocol.buildBcastPayload(
            ChessTournamentCodec.ARENA_CHANNEL, ChessTournamentCodec.encode(message),
        )
        val mass = com.kachat.app.util.KaspaMass.calculateMass(
            numInputs = 1, outputScriptLens = listOf(34), payloadSize = payload.size,
        )
        val sompi = com.kachat.app.util.KaspaMass.calculateFee(
            mass, com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toLong(),
        )
        // Four decimals: "0.0017 KAS" reads at a glance; the exact sompi is in the transaction.
        "%.4f KAS".format(sompi / 100_000_000.0)
    }.getOrNull()

    /** The join button's label: "Join (Fee: 0.0017 KAS)". */
    fun joinLabel(roomId: String): String {
        val fee = feeText(ChessTournamentCodec.join(roomId)) ?: return "Join"
        return "Join (Fee: $fee)"
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

        /** How often the arena's history is re-read while a chess screen is up - the same
         *  cadence a public room's own indexer poll uses. */
        private const val ARENA_BACKFILL_INTERVAL_MS = 8_000L

        /** How long a join waits for that history before going ahead anyway. */
        private const val HISTORY_WAIT_MS = 8_000L

        /** Rooms the app uses as machinery, never shown as chats: the chess arena. Hidden from
         *  Public Chats, no unread, no banners (iOS BroadcastService.serviceChannels). */
        val SERVICE_CHANNELS: Set<String> = setOf(ChessTournamentCodec.ARENA_CHANNEL)
    }
}
