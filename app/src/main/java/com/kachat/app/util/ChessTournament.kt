package com.kachat.app.util

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.text.BreakIterator

// Chess tournaments (5.1) - eight-player knockout in the public #chess-arena room, every move a
// Kaspa transaction, no referee. A byte-for-byte port of iOS's ChessTournamentModels.swift and
// ChessTournamentEngine.swift (CHESS_TOURNAMENTS.md in the iOS repo is the protocol). Every phone
// runs the same rules over the same rows and lands on the same bracket, boards, clocks and
// results, so nothing here may be "improved" on its own: a rule that differs by one line from
// iOS is a tournament the two platforms disagree about.

// MARK: - Wire protocol (CHESS_TOURNAMENTS.md §2)

/** One tournament message as it travels in the arena. `decode` returns null for anything that
 *  is not a well-formed tournament message, so the arena can carry other content harmlessly. */
data class ChessTournamentMessage(
    val type: String = "chess_t",
    val v: Int = 1,
    /** Tournament id: a lowercase UUID chosen by the creator. */
    val t: String,
    /** Action: create, join, cancel, move, resign, claim, chat. */
    val a: String,
    val name: String? = null,
    /** Game id "<round>-<index>", e.g. "1-0"; "" (or absent) for tournament-level chat. */
    val g: String? = null,
    /** Ply number of a move (1 = white's first move). */
    val n: Long? = null,
    val from: String? = null,
    val to: String? = null,
    /** Promotion piece letter: q r b n. */
    val promo: String? = null,
    val text: String? = null,
    /** Private tournaments only: proof the creator holds the creator code (§2.1). */
    val k: String? = null,
    /** `create` only: how many players - 2 (a 1v1) or 8 (a tournament). Absent = 8. */
    val p: Long? = null,
)

object ChessTournamentCodec {
    const val ARENA_CHANNEL = "chess-arena"
    const val PLAYER_COUNT = 8
    const val CLOCK_MS = 5L * 60 * 1000
    /** A seat in a waiting room lasts this long: if the room has not filled by then, the seat
     *  expires and the player is out of the queue - with the app closed, on a walk, whatever. */
    const val SEAT_TTL_MS = 5L * 60 * 1000

    /** Chain time is charged only past an allowance per move, so the seconds a move spends
     *  reaching the other phone (a block, the indexer's poll) are nobody's thinking time. */
    const val MOVE_DELAY_MS = 10L * 1000

    /** A side's FIRST move gets 25 seconds instead: the game starts at the second join's block
     *  time, and a player must not lose clock before their phone has even shown the board. This
     *  is the gate on a simultaneous join - the clock does not run for either side until they
     *  have shown up with a move (or the 25 seconds are up). */
    const val FIRST_MOVE_GRACE_MS = 25L * 1000

    /**
     * The allowances apply to games that STARTED at or after this block time (2026-09-24 00:00
     * UTC). A rule change must never reach back: the games before it were decided under the
     * rules of their day, and re-judging them re-opened games that had ended (a claim valid at
     * 5:00 became "early" under the minute's grace) and let a player resign a finished game for
     * a second loss. Every platform ships the same instant (iOS 2641f2e).
     */
    const val ALLOWANCE_FROM_MS = 1_790_208_000_000L

    /** The allowance for the move at [ply] (1 = white's first, 2 = black's first) in a game
     *  started at [startedAt]; zero for games from before [ALLOWANCE_FROM_MS]. */
    fun allowanceMs(ply: Int, startedAt: Long): Long {
        if (startedAt < ALLOWANCE_FROM_MS) return 0
        return if (ply <= 2) FIRST_MOVE_GRACE_MS else MOVE_DELAY_MS
    }
    const val NAME_MAX_LENGTH = 40
    const val CHAT_MAX_LENGTH = 280

    // MARK: Public rooms and private tournaments (CHESS_TOURNAMENTS.md §2.1)

    /** Public rooms are numbered: `public-1`, `public-2`, ... One is open at a time; a join to
     *  room N is accepted only when room N-1 is full, so the queue never forks. Nobody creates
     *  them - the first join is the creation. */
    const val PUBLIC_ID_PREFIX = "public-"
    /** Public 1v1 rooms: the same queue, two seats: `duel-1`, `duel-2`, ... */
    const val DUEL_ID_PREFIX = "duel-"
    fun publicId(number: Int) = "$PUBLIC_ID_PREFIX$number"
    fun duelId(number: Int) = "$DUEL_ID_PREFIX$number"
    fun publicNumber(id: String): Int? = number(id, PUBLIC_ID_PREFIX)
    fun duelNumber(id: String): Int? = number(id, DUEL_ID_PREFIX)
    private fun number(id: String, prefix: String): Int? {
        if (!id.startsWith(prefix)) return null
        val n = swiftInt(id.substring(prefix.length)) ?: return null
        return n.takeIf { it >= 1 }
    }

    /**
     * Swift's `Int(String)`: an optional sign and ASCII digits only, nil on overflow. Kotlin's
     * toIntOrNull also takes Arabic-Indic and other Unicode digits, which would make a room id
     * like "public-١" room 1 on Android and nothing on an iPhone.
     */
    private fun swiftInt(text: String): Int? {
        if (text.isEmpty()) return null
        var index = 0
        var negative = false
        if (text[0] == '+' || text[0] == '-') {
            negative = text[0] == '-'
            index = 1
            if (text.length == 1) return null
        }
        var value = 0L
        while (index < text.length) {
            val c = text[index]
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
            if (value > Int.MAX_VALUE.toLong() + 1) return null
            index++
        }
        val signed = if (negative) -value else value
        return if (signed in Int.MIN_VALUE..Int.MAX_VALUE) signed.toInt() else null
    }

    /** Public = a numbered room of either kind. */
    fun isPublic(id: String): Boolean = publicNumber(id) != null || duelNumber(id) != null

    /** The creator code for private tournaments - the same on every platform. The chain
     *  carries only `k` = SHA-256(code:id), so the code never appears on chain and a key from one
     *  tournament is no use for another. Change it here and in the other apps to rotate it. */
    const val PRIVATE_CREATE_CODE = "KACHAT-CHESS"

    fun createKey(code: String, id: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((code.trim().uppercase() + ":" + id).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    fun isValidCreateKey(key: String?, id: String): Boolean =
        key != null && key == createKey(PRIVATE_CREATE_CODE, id)

    /** Private ids are short and shareable: eight lowercase letters and digits, no confusables. */
    fun newPrivateId(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = java.security.SecureRandom()
        return (0 until 8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    /** Keys sorted, slashes unescaped, absent fields left out - what iOS's JSONEncoder writes. */
    fun encode(message: ChessTournamentMessage): String {
        val fields = sortedMapOf<String, Any>()
        fields["type"] = message.type
        fields["v"] = message.v
        fields["t"] = message.t
        fields["a"] = message.a
        message.name?.let { fields["name"] = it }
        message.g?.let { fields["g"] = it }
        message.n?.let { fields["n"] = it }
        message.from?.let { fields["from"] = it }
        message.to?.let { fields["to"] = it }
        message.promo?.let { fields["promo"] = it }
        message.text?.let { fields["text"] = it }
        message.k?.let { fields["k"] = it }
        message.p?.let { fields["p"] = it }
        return fields.entries.joinToString(",", "{", "}") { (key, value) ->
            "${quote(key)}:${if (value is String) quote(value) else value.toString()}"
        }
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /**
     * Cheap gate first (this runs over every arena row), then a STRICT decode, as iOS's
     * JSONDecoder does: malformed JSON, a field of the wrong type (a ply sent as "1"), or a
     * missing type/v/t/a rejects the whole message. A lenient parser here would accept what
     * iPhones ignore, and the two would reach different boards.
     */
    fun decode(content: String): ChessTournamentMessage? {
        if (graphemeCount(content) > 2_048 || !content.startsWith("{") || !content.contains("\"chess_t\"")) return null
        val fields = parseFlatObject(content) ?: return null
        fun string(key: String, required: Boolean = false): Result<String?> {
            if (!fields.containsKey(key)) return if (required) Result.failure(Exception()) else Result.success(null)
            return when (val value = fields[key]) {
                null -> if (required) Result.failure(Exception()) else Result.success(null)
                is String -> Result.success(value)
                else -> Result.failure(Exception())
            }
        }
        fun int(key: String, required: Boolean = false): Result<Long?> {
            if (!fields.containsKey(key)) return if (required) Result.failure(Exception()) else Result.success(null)
            return when (val value = fields[key]) {
                null -> if (required) Result.failure(Exception()) else Result.success(null)
                is JsonNumber -> value.asWholeLong()?.let { Result.success(it) } ?: Result.failure(Exception())
                else -> Result.failure(Exception())
            }
        }
        return try {
            val message = ChessTournamentMessage(
                type = string("type", required = true).getOrThrow()!!,
                v = int("v", required = true).getOrThrow()!!.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else throw Exception() },
                t = string("t", required = true).getOrThrow()!!,
                a = string("a", required = true).getOrThrow()!!,
                name = string("name").getOrThrow(),
                g = string("g").getOrThrow(),
                n = int("n").getOrThrow(),
                from = string("from").getOrThrow(),
                to = string("to").getOrThrow(),
                promo = string("promo").getOrThrow(),
                text = string("text").getOrThrow(),
                k = string("k").getOrThrow(),
                p = int("p").getOrThrow(),
            )
            if (message.type != "chess_t" || message.v != 1 || message.t.isEmpty() || graphemeCount(message.t) > 64) null else message
        } catch (e: Exception) {
            null
        }
    }

    /** A JSON number kept as its text, so an integer field can be checked exactly. */
    private class JsonNumber(val text: String) {
        fun asWholeLong(): Long? {
            text.toLongOrNull()?.let { return it }
            val decimal = text.toBigDecimalOrNull() ?: return null
            return try { decimal.stripTrailingZeros().longValueExact() } catch (e: ArithmeticException) { null }
        }
    }

    /**
     * The top-level object's fields: strings, numbers (as [JsonNumber]), null, or a marker for
     * any other value (object, array, boolean) - which no field of ours may hold. Null when the
     * text is not exactly one strict JSON object. A repeated key keeps its last value.
     */
    private fun parseFlatObject(content: String): Map<String, Any?>? = try {
        val reader = JsonReader(StringReader(content))
        reader.isLenient = false
        val fields = HashMap<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            fields[key] = when (reader.peek()) {
                JsonToken.STRING -> reader.nextString()
                JsonToken.NUMBER -> JsonNumber(reader.nextString())
                JsonToken.NULL -> { reader.nextNull(); null }
                else -> { reader.skipValue(); OtherValue }
            }
        }
        reader.endObject()
        if (reader.peek() != JsonToken.END_DOCUMENT) null else fields
    } catch (e: Exception) {
        null
    }

    private object OtherValue

    fun create(id: String, name: String, code: String) = ChessTournamentMessage(
        t = id, a = "create", name = graphemePrefix(name, NAME_MAX_LENGTH), k = createKey(code, id), p = PLAYER_COUNT.toLong(),
    )
    /** A private 1v1 needs no creator code: anyone can open one for a friend. */
    fun createDuel(id: String, name: String) =
        ChessTournamentMessage(t = id, a = "create", name = graphemePrefix(name, NAME_MAX_LENGTH), p = 2)
    fun join(id: String) = ChessTournamentMessage(t = id, a = "join")
    fun leave(id: String) = ChessTournamentMessage(t = id, a = "leave")
    fun cancel(id: String) = ChessTournamentMessage(t = id, a = "cancel")
    fun move(id: String, game: String, ply: Int, from: String, to: String, promotion: String?) =
        ChessTournamentMessage(t = id, a = "move", g = game, n = ply.toLong(), from = from, to = to, promo = promotion)
    fun resign(id: String, game: String) = ChessTournamentMessage(t = id, a = "resign", g = game)
    fun claim(id: String, game: String) = ChessTournamentMessage(t = id, a = "claim", g = game)
    fun chat(id: String, game: String?, text: String) =
        ChessTournamentMessage(t = id, a = "chat", g = game ?: "", text = graphemePrefix(text, CHAT_MAX_LENGTH))

    /**
     * iOS's `ChessSquare(algebraic:)`, exactly: the text lowercased, then two user-perceived
     * characters - a file a-h and a rank whose Unicode whole-number value is 1-8. Swift reads a
     * rank like "²" or "Ⅷ" by its numeric value, so this does too; the app's own parser accepts
     * only ASCII digits, and the difference would be a move one platform plays and the other
     * ignores.
     */
    fun square(algebraic: String): ChessSquare? {
        val lowered = algebraic.lowercase()
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(lowered)
        val clusters = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            clusters += lowered.substring(start, end)
            if (clusters.size > 2) return null
            start = end
            end = iterator.next()
        }
        if (clusters.size != 2) return null
        val fileText = clusters[0]
        val rankText = clusters[1]
        if (fileText.length != 1 || fileText[0] !in 'a'..'h') return null
        // A whole-number value belongs to a single scalar.
        if (rankText.codePointCount(0, rankText.length) != 1) return null
        val rank = Character.getNumericValue(rankText.codePointAt(0))
        if (rank !in 1..8) return null
        return ChessSquare(fileText[0] - 'a', rank - 1)
    }

    /** Swift's `String.count`: user-perceived characters, not UTF-16 units. */
    fun graphemeCount(text: String): Int {
        if (text.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    /** Swift's `String.prefix(n)`: the first [max] user-perceived characters. */
    fun graphemePrefix(text: String, max: Int): String {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var end = 0
        repeat(max) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return text
            end = next
        }
        return text.substring(0, end)
    }
}

// MARK: - Derived state (CHESS_TOURNAMENTS.md §3-4)

/** The rules' input: one arena row, already known to be a tournament message. */
data class ChessArenaEvent(val txId: String, val sender: String, val blockTime: Long, val message: ChessTournamentMessage)

data class ChessTournamentChatLine(
    val id: String,
    val sender: String,
    val text: String,
    val blockTime: Long,
    /** "" for the tournament lobby. */
    val game: String,
)

/**
 * A chat line of this phone's that is still on its way (or failed): shown under the board with a
 * clock (or a red mark) until the chain returns it, when it becomes a [ChessTournamentChatLine]
 * with the green check - the same three states a 1:1 chat bubble has (iOS 759a2d3).
 */
data class ChessPendingChatLine(
    val id: String,
    val tournament: String,
    val line: ChessTournamentChatLine,
    val failed: Boolean,
)

data class ChessTournamentMove(
    val txId: String,
    val ply: Int,
    val color: ChessColor,
    val from: ChessSquare,
    val to: ChessSquare,
    val promotion: ChessPieceType?,
    val pieceType: ChessPieceType,
    val captured: ChessPieceType?,
    val blockTime: Long,
    /** The mover's remaining clock after this move. */
    val clockAfterMs: Long,
)

sealed class ChessTournamentOutcome {
    object Checkmate : ChessTournamentOutcome()
    object Resignation : ChessTournamentOutcome()
    object Timeout : ChessTournamentOutcome()
    /** A draw on the board (stalemate, material, fifty moves, repetition), broken by clock. */
    data class DrawTiebreak(val reason: String) : ChessTournamentOutcome()
}

data class ChessTournamentGame(
    val id: String,
    val round: Int,
    val index: Int,
    val white: String,
    val black: String,
    /** Block time the game started (the message that decided both players). */
    val startedAt: Long,
    val board: ChessBoard,
    val moves: List<ChessTournamentMove>,
    /** Clock consumed so far by each side, in chain time. */
    val whiteUsedMs: Long = 0,
    val blackUsedMs: Long = 0,
    /** Block time of the last event (start or last move) - the side to move's clock runs from here. */
    val lastEventAt: Long,
    val winner: String? = null,
    val outcome: ChessTournamentOutcome? = null,
    /** Block time the game ended. */
    val endedAt: Long? = null,
    /** Positions seen, for threefold repetition (a compact board key -> count). */
    val positionCounts: Map<String, Int> = emptyMap(),
    val halfmoveClock: Int = 0,
) {
    val isOver: Boolean get() = winner != null
    val sideToMove: ChessColor get() = board.sideToMove
    val playerToMove: String get() = if (sideToMove == ChessColor.WHITE) white else black

    fun address(color: ChessColor): String = if (color == ChessColor.WHITE) white else black
    fun color(address: String): ChessColor? = when (address) {
        white -> ChessColor.WHITE
        black -> ChessColor.BLACK
        else -> null
    }

    fun usedMs(color: ChessColor): Long = if (color == ChessColor.WHITE) whiteUsedMs else blackUsedMs

    /** Remaining clock for [color] at chain time [now] (or wall time, for display). */
    fun remainingMs(color: ChessColor, now: Long): Long {
        var used = usedMs(color)
        if (!isOver && color == sideToMove) used += chargedMs(now - lastEventAt)
        return maxOf(0L, ChessTournamentCodec.CLOCK_MS - used)
    }

    /** What the side to move is charged for [elapsed] ms of chain time since the last event:
     *  the time past this ply's allowance (see [ChessTournamentCodec.allowanceMs]). */
    fun chargedMs(elapsed: Long): Long =
        maxOf(0L, elapsed - ChessTournamentCodec.allowanceMs(moves.size + 1, startedAt))

    /** The allowance still unspent on the current move, for the phone to show ("clock starts in
     *  0:42"); zero once the clock is running. */
    fun allowanceLeftMs(now: Long): Long {
        if (isOver) return 0
        return maxOf(0L, ChessTournamentCodec.allowanceMs(moves.size + 1, startedAt) - maxOf(0L, now - lastEventAt))
    }
}

data class ChessTournament(
    val id: String,
    val name: String,
    val creator: String,
    val createdAt: Long,
    val createTxId: String,
    /** 2 for a 1v1, 8 for a tournament. */
    val capacity: Int,
    /** Seat order: index 0 is seed 1 (the creator). */
    val players: List<String> = emptyList(),
    /** Block time each seated player took their seat (for seat expiry while waiting). */
    val joinedAt: Map<String, Long> = emptyMap(),
    val startedAt: Long? = null,
    val cancelled: Boolean = false,
    val games: Map<String, ChessTournamentGame> = emptyMap(),
    val chat: List<ChessTournamentChatLine> = emptyList(),
    /** White games per player so far, for colour assignment after round 1. */
    val whiteCount: Map<String, Int> = emptyMap(),
) {
    enum class Status { OPEN, LIVE, FINISHED, CANCELLED }

    val isDuel: Boolean get() = capacity == 2
    val rounds: Int get() = if (isDuel) 1 else 3
    val finalGameId: String get() = "$rounds-0"
    val status: Status
        get() = when {
            cancelled -> Status.CANCELLED
            startedAt == null -> Status.OPEN
            games[finalGameId]?.isOver == true -> Status.FINISHED
            else -> Status.LIVE
        }
    val champion: String? get() = games[finalGameId]?.winner
    val seatsLeft: Int get() = maxOf(0, capacity - players.size)
    /** The players whose seats are still good at [now] (chain or wall time): while a room waits,
     *  a seat older than [ChessTournamentCodec.SEAT_TTL_MS] has expired. Once the room has
     *  started every player stays. */
    fun seatedPlayers(now: Long): List<String> {
        if (status != Status.OPEN) return players
        return players.filter { (joinedAt[it] ?: createdAt) + ChessTournamentCodec.SEAT_TTL_MS > now }
    }

    fun isSeated(address: String, now: Long): Boolean = address in seatedPlayers(now)

    /** When [address]'s seat runs out, while waiting. */
    fun seatExpiry(address: String): Long? {
        if (status != Status.OPEN || address !in players) return null
        return (joinedAt[address] ?: createdAt) + ChessTournamentCodec.SEAT_TTL_MS
    }

    val isPublic: Boolean get() = ChessTournamentCodec.isPublic(id)
    val isFull: Boolean get() = players.size >= capacity

    fun seed(address: String): Int? = players.indexOf(address).takeIf { it >= 0 }?.plus(1)

    fun game(round: Int, index: Int): ChessTournamentGame? = games["$round-$index"]

    /** The games of a round, in bracket order. */
    fun gamesInRound(round: Int): List<ChessTournamentGame> {
        val count = if (isDuel) 1 else when (round) { 1 -> 4; 2 -> 2; else -> 1 }
        return (0 until count).mapNotNull { games["$round-$it"] }
    }

    /** The game [address] is playing (or waiting to play) right now, if any. */
    fun currentGame(address: String): ChessTournamentGame? {
        for (round in listOf(3, 2, 1)) {
            gamesInRound(round).firstOrNull { it.white == address || it.black == address }?.let { return it }
        }
        return null
    }
}

/**
 * One player's record, computed from what the phone has read. Two boards read it: 1v1 (duel
 * games, public and private) and Tournaments (tournaments won, then the games inside them).
 * [wins]/[losses] are the totals over both, the figures the indexer's `/chess/leaderboard`
 * serves.
 */
data class ChessLeaderboardRow(
    val address: String,
    val wins: Int = 0,
    val losses: Int = 0,
    val tournamentsPlayed: Int = 0,
    val tournamentsWon: Int = 0,
    val lastPlayedAt: Long = 0,
    /** 1v1 games (`duel-N` rooms and private 1v1s). */
    val duelWins: Int = 0,
    val duelLosses: Int = 0,
    /** Games inside eight-player tournaments. */
    val tournamentGameWins: Int = 0,
    val tournamentGameLosses: Int = 0,
)

// MARK: - The rules

/**
 * CHESS_TOURNAMENTS.md §3-4 as one pure function: the arena's tournament messages, in chain
 * order, in; every tournament's bracket, boards, clocks and results out. Line for line
 * iOS's ChessTournamentEngine - keep it self-contained, and change it only together with iOS.
 */
object ChessTournamentEngine {
    /** Chain order: block time, then txid - the same on every device. */
    fun ordered(events: List<ChessArenaEvent>): List<ChessArenaEvent> =
        events.sortedWith(compareBy<ChessArenaEvent> { it.blockTime }.thenBy { it.txId })

    fun reduce(events: List<ChessArenaEvent>): Map<String, ChessTournament> {
        val tournaments = HashMap<String, ChessTournament>()
        for (event in ordered(events)) apply(event, tournaments)
        return tournaments
    }

    fun apply(event: ChessArenaEvent, tournaments: MutableMap<String, ChessTournament>) {
        val message = event.message
        when (message.a) {
            "create" -> {
                // Public rooms are never created by message. A private tournament (8) needs the
                // creator key; a private 1v1 (2) is open to anyone.
                val capacity = if (message.p == 2L) 2 else ChessTournamentCodec.PLAYER_COUNT
                if (tournaments[message.t] != null || ChessTournamentCodec.isPublic(message.t)) return
                if (capacity != 2 && !ChessTournamentCodec.isValidCreateKey(message.k, message.t)) return
                val cleanName = message.name.orEmpty().trim()
                tournaments[message.t] = ChessTournament(
                    id = message.t,
                    name = if (cleanName.isEmpty()) (if (capacity == 2) "1v1" else "Tournament")
                    else ChessTournamentCodec.graphemePrefix(cleanName, ChessTournamentCodec.NAME_MAX_LENGTH),
                    creator = event.sender,
                    createdAt = event.blockTime,
                    createTxId = event.txId,
                    capacity = capacity,
                    players = listOf(event.sender),
                    joinedAt = mapOf(event.sender to event.blockTime),
                )
            }
            "join" -> {
                if (tournaments[message.t] == null) {
                    // The first join opens a public room - whichever number it names. There used
                    // to be a rule that room N counts only once room N-1 is full; it made every
                    // phone's view depend on holding the complete history back to room 1, and a
                    // phone missing the early rooms (indexer window, retention, a late backfill)
                    // then rejected every later room outright and queued into a room the others
                    // had long finished. Which room is "current" is a client choice now
                    // (ChessTournamentService.currentPublicRoomId: the lowest open room).
                    val publicNumber = ChessTournamentCodec.publicNumber(message.t)
                    val duelNumber = ChessTournamentCodec.duelNumber(message.t)
                    if (publicNumber != null) {
                        tournaments[message.t] = ChessTournament(
                            id = message.t, name = "Public tournament #$publicNumber", creator = event.sender,
                            createdAt = event.blockTime, createTxId = event.txId, capacity = ChessTournamentCodec.PLAYER_COUNT,
                        )
                    } else if (duelNumber != null) {
                        tournaments[message.t] = ChessTournament(
                            id = message.t, name = "Public 1v1 #$duelNumber", creator = event.sender,
                            createdAt = event.blockTime, createTxId = event.txId, capacity = 2,
                        )
                    }
                }
                var tournament = tournaments[message.t] ?: return
                if (tournament.status != ChessTournament.Status.OPEN) return
                // Seats that ran out while the room waited are given back first - so a room can
                // never fill with players who left long ago, and a returning player takes a
                // fresh seat. Deterministic: judged at this join's block time, the same on
                // every phone.
                tournament = expireSeats(tournament, event.blockTime)
                if (event.sender in tournament.players) return
                tournament = tournament.copy(
                    players = tournament.players + event.sender,
                    joinedAt = tournament.joinedAt + (event.sender to event.blockTime),
                )
                if (tournament.players.size == tournament.capacity) {
                    tournament = start(tournament, event.blockTime)
                }
                tournaments[message.t] = tournament
            }
            "leave" -> {
                // A seat given back while the room is still waiting. Once it has started there
                // is no leaving - only resigning the game.
                val tournament = tournaments[message.t] ?: return
                if (tournament.status != ChessTournament.Status.OPEN || event.sender !in tournament.players) return
                tournaments[message.t] = tournament.copy(
                    players = tournament.players - event.sender,
                    joinedAt = tournament.joinedAt - event.sender,
                )
            }
            "cancel" -> {
                val tournament = tournaments[message.t] ?: return
                if (tournament.status != ChessTournament.Status.OPEN || tournament.isPublic || tournament.creator != event.sender) return
                tournaments[message.t] = tournament.copy(cancelled = true)
            }
            "move" -> {
                val tournament = tournaments[message.t] ?: return
                if (tournament.status != ChessTournament.Status.LIVE) return
                val gameId = message.g ?: return
                var game = tournament.games[gameId] ?: return
                if (game.isOver || game.playerToMove != event.sender) return
                val ply = message.n ?: return
                if (ply != (game.moves.size + 1).toLong()) return
                val from = message.from?.let { ChessTournamentCodec.square(it) } ?: return
                val to = message.to?.let { ChessTournamentCodec.square(it) } ?: return
                // A move after the mover's clock ran out is void: the opponent's claim decides.
                // Charged past the move's allowance (a minute for a side's first move, ten
                // seconds after) - see ChessTournamentCodec.allowanceMs.
                val elapsed = game.chargedMs(event.blockTime - game.lastEventAt)
                val remaining = ChessTournamentCodec.CLOCK_MS - game.usedMs(game.sideToMove)
                if (elapsed >= remaining) return
                var move = ChessMove(from, to, ChessPieceType.fromPromotionLetter(message.promo))
                move = ChessEngine.normalizingPromotion(move, game.board)
                if (!ChessEngine.isLegal(move, game.board)) return
                val piece = game.board.piece(from) ?: return
                val isEnPassant = piece.type == ChessPieceType.PAWN && to == game.board.enPassantTarget && game.board.piece(to) == null
                val captured = game.board.piece(to)?.type ?: if (isEnPassant) ChessPieceType.PAWN else null
                val mover = game.sideToMove
                val board = ChessEngine.apply(move, game.board)
                val whiteUsed = if (mover == ChessColor.WHITE) game.whiteUsedMs + elapsed else game.whiteUsedMs
                val blackUsed = if (mover == ChessColor.BLACK) game.blackUsedMs + elapsed else game.blackUsedMs
                val moverUsed = if (mover == ChessColor.WHITE) whiteUsed else blackUsed
                val key = positionKey(board)
                val counts = game.positionCounts + (key to (game.positionCounts[key] ?: 0) + 1)
                game = game.copy(
                    board = board,
                    whiteUsedMs = whiteUsed,
                    blackUsedMs = blackUsed,
                    lastEventAt = event.blockTime,
                    moves = game.moves + ChessTournamentMove(
                        txId = event.txId, ply = ply.toInt(), color = mover, from = from, to = to, promotion = move.promotion,
                        pieceType = piece.type, captured = captured, blockTime = event.blockTime,
                        clockAfterMs = ChessTournamentCodec.CLOCK_MS - moverUsed,
                    ),
                    halfmoveClock = if (piece.type == ChessPieceType.PAWN || captured != null) 0 else game.halfmoveClock + 1,
                    positionCounts = counts,
                )
                game = when {
                    ChessEngine.isCheckmate(game.board) -> finish(game, game.address(mover), ChessTournamentOutcome.Checkmate, event.blockTime)
                    ChessEngine.isStalemate(game.board) -> finishDraw(game, "stalemate", event.blockTime)
                    ChessEngine.isInsufficientMaterial(game.board) -> finishDraw(game, "insufficient material", event.blockTime)
                    game.halfmoveClock >= 100 -> finishDraw(game, "fifty-move rule", event.blockTime)
                    (counts[key] ?: 0) >= 3 -> finishDraw(game, "threefold repetition", event.blockTime)
                    else -> game
                }
                var updated = tournament.copy(games = tournament.games + (gameId to game))
                if (game.isOver) updated = advance(updated, game)
                tournaments[message.t] = updated
            }
            "resign" -> {
                val tournament = tournaments[message.t] ?: return
                if (tournament.status != ChessTournament.Status.LIVE) return
                val gameId = message.g ?: return
                var game = tournament.games[gameId] ?: return
                if (game.isOver) return
                val color = game.color(event.sender) ?: return
                game = finish(game, game.address(color.opposite), ChessTournamentOutcome.Resignation, event.blockTime)
                tournaments[message.t] = advance(tournament.copy(games = tournament.games + (gameId to game)), game)
            }
            "claim" -> {
                val tournament = tournaments[message.t] ?: return
                if (tournament.status != ChessTournament.Status.LIVE) return
                val gameId = message.g ?: return
                var game = tournament.games[gameId] ?: return
                if (game.isOver) return
                val claimant = game.color(event.sender) ?: return
                if (claimant == game.sideToMove) return
                // Valid only if, by chain time, the side to move had indeed run out - past the
                // same allowance a move gets.
                val elapsed = game.chargedMs(event.blockTime - game.lastEventAt)
                val remaining = ChessTournamentCodec.CLOCK_MS - game.usedMs(game.sideToMove)
                if (elapsed < remaining) return
                game = if (game.sideToMove == ChessColor.WHITE) {
                    game.copy(whiteUsedMs = ChessTournamentCodec.CLOCK_MS)
                } else {
                    game.copy(blackUsedMs = ChessTournamentCodec.CLOCK_MS)
                }
                game = finish(game, event.sender, ChessTournamentOutcome.Timeout, event.blockTime)
                tournaments[message.t] = advance(tournament.copy(games = tournament.games + (gameId to game)), game)
            }
            "chat" -> {
                val tournament = tournaments[message.t] ?: return
                val text = message.text?.trim()?.takeIf { it.isNotEmpty() } ?: return
                tournaments[message.t] = tournament.copy(
                    chat = tournament.chat + ChessTournamentChatLine(
                        id = event.txId, sender = event.sender,
                        text = ChessTournamentCodec.graphemePrefix(text, ChessTournamentCodec.CHAT_MAX_LENGTH),
                        blockTime = event.blockTime, game = message.g ?: "",
                    )
                )
            }
            else -> return
        }
    }

    private fun expireSeats(tournament: ChessTournament, time: Long): ChessTournament {
        val kept = tournament.players.filter {
            (tournament.joinedAt[it] ?: tournament.createdAt) + ChessTournamentCodec.SEAT_TTL_MS > time
        }
        if (kept.size == tournament.players.size) return tournament
        return tournament.copy(players = kept, joinedAt = tournament.joinedAt.filterKeys { it in kept })
    }

    // MARK: - Bracket

    private fun start(tournament: ChessTournament, time: Long): ChessTournament {
        val seeds = tournament.players
        val pairs = if (tournament.isDuel) listOf(0 to 1) else listOf(0 to 7, 1 to 6, 2 to 5, 3 to 4)
        val games = tournament.games.toMutableMap()
        val whiteCount = tournament.whiteCount.toMutableMap()
        pairs.forEachIndexed { index, (w, b) ->
            val white = seeds[w]
            games["1-$index"] = makeGame(1, index, white, seeds[b], time)
            whiteCount[white] = (whiteCount[white] ?: 0) + 1
        }
        return tournament.copy(startedAt = time, games = games, whiteCount = whiteCount)
    }

    private fun advance(tournament: ChessTournament, game: ChessTournamentGame): ChessTournament {
        if (game.round >= tournament.rounds) return tournament
        val time = game.endedAt ?: return tournament
        val nextRound = game.round + 1
        val nextIndex = game.index / 2
        val a = tournament.game(game.round, nextIndex * 2)?.winner ?: return tournament
        val b = tournament.game(game.round, nextIndex * 2 + 1)?.winner ?: return tournament
        if (tournament.game(nextRound, nextIndex) != null) return tournament
        // Colours: fewer whites so far gets white; tie -> lower seed.
        val whitesA = tournament.whiteCount[a] ?: 0
        val whitesB = tournament.whiteCount[b] ?: 0
        val aIsWhite = if (whitesA != whitesB) whitesA < whitesB else (tournament.seed(a) ?: 99) < (tournament.seed(b) ?: 99)
        val white = if (aIsWhite) a else b
        val black = if (aIsWhite) b else a
        return tournament.copy(
            games = tournament.games + ("$nextRound-$nextIndex" to makeGame(nextRound, nextIndex, white, black, time)),
            whiteCount = tournament.whiteCount + (white to (tournament.whiteCount[white] ?: 0) + 1),
        )
    }

    private fun makeGame(round: Int, index: Int, white: String, black: String, time: Long): ChessTournamentGame {
        val board = ChessEngine.initialBoard()
        return ChessTournamentGame(
            id = "$round-$index", round = round, index = index, white = white, black = black,
            startedAt = time, board = board, moves = emptyList(), lastEventAt = time,
            positionCounts = mapOf(positionKey(board) to 1),
        )
    }

    private fun finish(game: ChessTournamentGame, winner: String, outcome: ChessTournamentOutcome, time: Long) =
        game.copy(winner = winner, outcome = outcome, endedAt = time)

    /** A draw on the board: the player with more clock left advances; equal -> black. */
    private fun finishDraw(game: ChessTournamentGame, reason: String, time: Long): ChessTournamentGame {
        val whiteLeft = ChessTournamentCodec.CLOCK_MS - game.whiteUsedMs
        val blackLeft = ChessTournamentCodec.CLOCK_MS - game.blackUsedMs
        val winner = if (whiteLeft > blackLeft) game.white else game.black
        return finish(game, winner, ChessTournamentOutcome.DrawTiebreak(reason), time)
    }

    /** Board, side to move, castling rights and en-passant square - what repetition compares. */
    fun positionKey(board: ChessBoard): String = buildString {
        for (rank in 0 until 8) {
            for (file in 0 until 8) {
                val piece = board.squares[rank][file]
                if (piece == null) {
                    append('.')
                } else {
                    val letter = when (piece.type) {
                        ChessPieceType.PAWN -> 'p'
                        ChessPieceType.KNIGHT -> 'n'
                        ChessPieceType.BISHOP -> 'b'
                        ChessPieceType.ROOK -> 'r'
                        ChessPieceType.QUEEN -> 'q'
                        ChessPieceType.KING -> 'k'
                    }
                    append(if (piece.color == ChessColor.WHITE) letter.uppercaseChar() else letter)
                }
            }
        }
        append(if (board.sideToMove == ChessColor.WHITE) "w" else "b")
        append(if (board.whiteCanCastleKingside) "K" else "-")
        append(if (board.whiteCanCastleQueenside) "Q" else "-")
        append(if (board.blackCanCastleKingside) "k" else "-")
        append(if (board.blackCanCastleQueenside) "q" else "-")
        append(board.enPassantTarget?.algebraic ?: "-")
    }

    // MARK: - Leaderboard

    fun leaderboard(tournaments: Collection<ChessTournament>): List<ChessLeaderboardRow> {
        val rows = HashMap<String, ChessLeaderboardRow>()
        fun row(address: String) = rows[address] ?: ChessLeaderboardRow(address)
        for (tournament in tournaments) {
            val started = tournament.startedAt ?: continue
            // A 1v1 is not a tournament: it counts on the 1v1 board only.
            if (!tournament.isDuel) {
                for (player in tournament.players) {
                    val r = row(player)
                    rows[player] = r.copy(tournamentsPlayed = r.tournamentsPlayed + 1, lastPlayedAt = maxOf(r.lastPlayedAt, started))
                }
            }
            for (game in tournament.games.values) {
                if (!game.isOver) continue
                val winner = game.winner ?: continue
                val loser = if (winner == game.white) game.black else game.white
                val ended = game.endedAt ?: 0L
                val w = row(winner)
                rows[winner] = w.copy(
                    wins = w.wins + 1,
                    duelWins = if (tournament.isDuel) w.duelWins + 1 else w.duelWins,
                    tournamentGameWins = if (tournament.isDuel) w.tournamentGameWins else w.tournamentGameWins + 1,
                    lastPlayedAt = maxOf(w.lastPlayedAt, ended),
                )
                val l = row(loser)
                rows[loser] = l.copy(
                    losses = l.losses + 1,
                    duelLosses = if (tournament.isDuel) l.duelLosses + 1 else l.duelLosses,
                    tournamentGameLosses = if (tournament.isDuel) l.tournamentGameLosses else l.tournamentGameLosses + 1,
                    lastPlayedAt = maxOf(l.lastPlayedAt, ended),
                )
            }
            if (!tournament.isDuel) tournament.champion?.let { champion ->
                val c = row(champion)
                rows[champion] = c.copy(tournamentsWon = c.tournamentsWon + 1)
            }
        }
        // Wins and losses are the leaderboard: most wins first, fewest losses breaking ties.
        return rows.values.sortedWith(
            compareByDescending<ChessLeaderboardRow> { it.wins }
                .thenBy { it.losses }
                .thenByDescending { it.lastPlayedAt }
        )
    }

    /** The 1v1 board: players with a 1v1 game behind them, most wins first, fewest losses
     *  breaking ties (iOS 784208f). */
    fun duelLeaderboard(rows: List<ChessLeaderboardRow>): List<ChessLeaderboardRow> =
        rows.filter { it.duelWins + it.duelLosses > 0 }.sortedWith(
            compareByDescending<ChessLeaderboardRow> { it.duelWins }
                .thenBy { it.duelLosses }
                .thenByDescending { it.lastPlayedAt }
        )

    /** The tournament board: tournaments won first, then the record inside them. */
    fun tournamentLeaderboard(rows: List<ChessLeaderboardRow>): List<ChessLeaderboardRow> =
        rows.filter { it.tournamentsPlayed > 0 }.sortedWith(
            compareByDescending<ChessLeaderboardRow> { it.tournamentsWon }
                .thenByDescending { it.tournamentGameWins }
                .thenBy { it.tournamentGameLosses }
                .thenByDescending { it.lastPlayedAt }
        )
}
