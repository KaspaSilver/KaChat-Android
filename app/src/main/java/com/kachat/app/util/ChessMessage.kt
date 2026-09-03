package com.kachat.app.util

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * "Play Chess" 1:1 chat feature - four JSON envelopes embedded directly in the plaintext message
 * body, same convention as [MessageReply]/[VoiceMessage]/[ImageMessage] (no wire-protocol
 * change). All four share one `gameId` (a UUID minted by the inviter) - [ChessGameSummary]
 * derives the current board by scanning a conversation's messages for that `gameId` and replaying
 * them through [ChessEngine], the same way [MessageReply.replyToId] is resolved client-side
 * against the in-memory message list rather than a database relationship.
 */
/** `@SerializedName` pins the wire value to lowercase "white"/"black" - Gson's default enum
 *  serialization uses the constant name verbatim ("WHITE"/"BLACK"), but iOS's Swift `Codable`
 *  enum encodes its raw String value, which defaults to the lowercase case name. Without this,
 *  every chess_invite sent from one platform silently failed to parse on the other (caught by
 *  parseOrNull's broad catch, falling through to plain-text rendering) - since the invite is the
 *  mandatory first message of every game, that broke the whole game, not just the color choice. */
enum class ChessInviteColor {
    @SerializedName("white") WHITE,
    @SerializedName("black") BLACK
}

data class ChessInviteContent(
    val type: String = "chess_invite",
    val gameId: String,
    val inviterColor: ChessInviteColor,
    /** Optional time control (e.g. 3/2, 2/1, 1/1) - both null means an untimed casual game,
     *  which is also what every pre-timer client sends and how they read these two absent keys
     *  (Gson ignores unknown fields on parse and omits null fields on encode, so the wire stays
     *  byte-compatible with legacy invites). Shared wire spec with iOS - field names must match. */
    val tcMinutes: Int? = null,
    val tcIncSeconds: Int? = null
)

data class ChessResponseContent(
    val type: String = "chess_response",
    val gameId: String,
    val accepted: Boolean
)

data class ChessMoveContent(
    val type: String = "chess_move",
    val gameId: String,
    val from: String,
    val to: String,
    val promotion: String?,
    /** Timed games only: the mover's remaining clock in ms AFTER this move, increment already
     *  added. Each side's authoritative remaining time is the clockMs of their own most recent
     *  move. Null/absent on untimed games and on moves from pre-timer clients. */
    val clockMs: Long? = null
)

data class ChessResignContent(
    val type: String = "chess_resign",
    val gameId: String,
    /** "timeout" when the player flagged (clock hit zero) - new clients render "lost on time",
     *  old clients ignore the field and show a plain resignation. Null for a manual resign. */
    val reason: String? = null
)

/** Any one of the four chess envelope shapes, parsed generically. */
sealed class ChessEnvelope {
    abstract val gameId: String

    data class Invite(val content: ChessInviteContent) : ChessEnvelope() {
        override val gameId: String get() = content.gameId
    }
    data class Response(val content: ChessResponseContent) : ChessEnvelope() {
        override val gameId: String get() = content.gameId
    }
    data class Move(val content: ChessMoveContent) : ChessEnvelope() {
        override val gameId: String get() = content.gameId
    }
    data class Resign(val content: ChessResignContent) : ChessEnvelope() {
        override val gameId: String get() = content.gameId
    }
}

/** Same conventions as [MessageReply]: a plain JSON envelope embedded directly as message
 *  content, with a `{`-prefix + size guard before attempting a full parse, since this runs on
 *  every visible message row alongside reply/image/voice parsing. */
object ChessMessage {
    private val gson = Gson()

    private data class TypeOnly(val type: String)

    fun encode(content: ChessInviteContent): String = gson.toJson(content)
    fun encode(content: ChessResponseContent): String = gson.toJson(content)
    fun encode(content: ChessMoveContent): String = gson.toJson(content)
    fun encode(content: ChessResignContent): String = gson.toJson(content)

    fun parseOrNull(text: String?): ChessEnvelope? {
        if (text.isNullOrBlank() || text.length > 100_000 || text.trimStart().firstOrNull() != '{') return null
        return try {
            val typeOnly = gson.fromJson(text, TypeOnly::class.java) ?: return null
            when (typeOnly.type) {
                "chess_invite" -> gson.fromJson(text, ChessInviteContent::class.java)?.let { ChessEnvelope.Invite(it) }
                "chess_response" -> gson.fromJson(text, ChessResponseContent::class.java)?.let { ChessEnvelope.Response(it) }
                "chess_move" -> gson.fromJson(text, ChessMoveContent::class.java)?.let { ChessEnvelope.Move(it) }
                "chess_resign" -> gson.fromJson(text, ChessResignContent::class.java)?.let { ChessEnvelope.Resign(it) }
                else -> null
            }
        } catch (e: Exception) {
            // Same broad catch as MessageReply.parseOrNull - Gson's reflection deserialization
            // doesn't honor Kotlin non-null defaults for absent JSON keys.
            null
        }
    }
}

/** Game-over-ness and a human-readable status line for a derived board state. */
enum class ChessGameStatusKind {
    PENDING_RESPONSE, DECLINED, IN_PROGRESS, CHECKMATE, STALEMATE,
    /** A dead position - K vs K and friends. A draw, but not a stalemate: see
     *  [com.kachat.app.util.ChessEngine.isInsufficientMaterial]. */
    INSUFFICIENT_MATERIAL,
    RESIGNED,
}

data class ChessGameStatus(
    val kind: ChessGameStatusKind,
    /** Only meaningful for CHECKMATE (winner) / RESIGNED (loser). */
    val color: ChessColor? = null,
    /** RESIGNED only: true when the resign carried reason "timeout" (the loser flagged), so
     *  status rendering says "lost on time" instead of "resigned". */
    val timedOut: Boolean = false
) {
    val isGameOver: Boolean get() = kind != ChessGameStatusKind.PENDING_RESPONSE && kind != ChessGameStatusKind.IN_PROGRESS
}

/** One applied move, recorded during [ChessGameEngine.summarize]'s replay - lets callers show the
 *  actual piece that moved/was captured (e.g. the in-thread move log, captured-pieces tray)
 *  without re-replaying the game themselves. */
data class ChessMoveRecord(
    val from: ChessSquare,
    val to: ChessSquare,
    val pieceType: ChessPieceType,
    val color: ChessColor,
    val capturedType: ChessPieceType?,
    val capturedColor: ChessColor?,
    val promotion: ChessPieceType?,
    /** id of the chat message this move came from - lets a specific log entry look up its own
     *  record via `moveHistory.firstOrNull { it.messageId == message.id }`. */
    val messageId: String,
    /** The mover's remaining clock in ms after this move (increment included), straight from the
     *  move envelope - null on untimed games and on moves sent by pre-timer clients. */
    val clockMs: Long? = null
)

data class ChessGameSummary(
    val gameId: String,
    val status: ChessGameStatus,
    val board: ChessBoard,
    val whiteAddress: String,
    val blackAddress: String,
    /** id of the most recent chess-related message for this game - used to key Compose
     *  recomposition (`key(...)`) without needing full board equality checks in call sites. */
    val lastMessageId: String,
    /** Which color the local device is playing, if it's a participant - lets `statusText` say
     *  "Your turn"/"Their turn" instead of absolute White/Black, which a casual player has to
     *  stop and translate back to "wait, am I white or black in this one?" every time. */
    val viewerColor: ChessColor? = null,
    /** Every move actually applied during replay, in play order. */
    val moveHistory: List<ChessMoveRecord> = emptyList(),
    /** Time control from the invite - both null for an untimed casual game. */
    val tcMinutes: Int? = null,
    val tcIncSeconds: Int? = null
) {
    val isTimed: Boolean get() = tcMinutes != null

    /** "3 | 2"-style label for a timed game, null for casual - shown on the invite bubble, live
     *  card, and anywhere else the game is previewed. */
    val timeControlLabel: String?
        get() = tcMinutes?.let { minutes -> "$minutes | ${tcIncSeconds ?: 0}" }

    /** Authoritative remaining clock for `color` as of their own most recent move that carried a
     *  clock, else the initial allotment; null on untimed games. The viewer's *displayed* own
     *  clock additionally subtracts locally-accumulated thinking time (see ChessGameScreen). */
    fun lastReportedClockMs(color: ChessColor): Long? {
        val minutes = tcMinutes ?: return null
        return moveHistory.lastOrNull { it.color == color && it.clockMs != null }?.clockMs
            ?: (minutes * 60_000L)
    }

    /** Pieces captured so far, grouped by the color that captured them (i.e. `capturedByWhite`
     *  are black pieces White has taken) - drives a captured-pieces tray. */
    val capturedByWhite: List<ChessPieceType>
        get() = moveHistory.filter { it.color == ChessColor.WHITE }.mapNotNull { it.capturedType }
    val capturedByBlack: List<ChessPieceType>
        get() = moveHistory.filter { it.color == ChessColor.BLACK }.mapNotNull { it.capturedType }

    fun colorFor(address: String): ChessColor? = when (address) {
        whiteAddress -> ChessColor.WHITE
        blackAddress -> ChessColor.BLACK
        else -> null
    }

    val statusText: String
        get() = when (status.kind) {
            ChessGameStatusKind.PENDING_RESPONSE -> "Waiting for response"
            ChessGameStatusKind.DECLINED -> "Game declined"
            ChessGameStatusKind.IN_PROGRESS -> {
                if (viewerColor == null) {
                    if (board.sideToMove == ChessColor.WHITE) "White to move" else "Black to move"
                } else if (board.sideToMove == viewerColor) "Your turn" else "Their turn"
            }
            ChessGameStatusKind.CHECKMATE -> {
                if (viewerColor == null) {
                    "Checkmate - ${if (status.color == ChessColor.WHITE) "White" else "Black"} wins"
                } else if (status.color == viewerColor) "Checkmate - You win!" else "Checkmate - You lost"
            }
            ChessGameStatusKind.STALEMATE -> "Stalemate - draw"
            ChessGameStatusKind.INSUFFICIENT_MATERIAL -> "Draw - not enough pieces to checkmate"
            ChessGameStatusKind.RESIGNED -> {
                if (status.timedOut) {
                    if (viewerColor == null) {
                        "${if (status.color == ChessColor.WHITE) "White" else "Black"} lost on time"
                    } else if (status.color == viewerColor) "You lost on time" else "They lost on time"
                } else if (viewerColor == null) {
                    "${if (status.color == ChessColor.WHITE) "White" else "Black"} resigned"
                } else if (status.color == viewerColor) "You resigned" else "They resigned"
            }
        }
}

/**
 * Derives a game's current state from a conversation's already-loaded messages - never persisted
 * on its own. Ported 1:1 from iOS's `ChessGameService.summarize`.
 */
object ChessGameEngine {
    /** A minimal view over whichever message type calls this (1:1 `MessageEntity`) - avoids this
     *  pure logic depending on Room/DB types directly. */
    interface ChessSourceMessage {
        val id: String
        val plaintextBody: String?
        val isOutgoing: Boolean
        val blockTimestamp: Long
    }

    /** Plain adapter for wrapping a `MessageEntity` (`direction == "sent"` -> `isOutgoing`) or any
     *  other message shape without needing this file to depend on Room/DB types directly. */
    data class SimpleChessSourceMessage(
        override val id: String,
        override val plaintextBody: String?,
        override val isOutgoing: Boolean,
        override val blockTimestamp: Long
    ) : ChessSourceMessage

    fun summarize(gameId: String, messages: List<ChessSourceMessage>, myAddress: String, contactAddress: String): ChessGameSummary? {
        var invite: ChessInviteContent? = null
        var inviterAddress: String? = null
        var response: ChessResponseContent? = null
        var board = ChessEngine.initialBoard()
        var resignerAddress: String? = null
        var resignReason: String? = null
        var lastMessageId: String? = null
        val moveHistory = mutableListOf<ChessMoveRecord>()

        for (message in messages.sortedBy { it.blockTimestamp }) {
            val replyUnwrapped = MessageReply.parseOrNull(message.plaintextBody)?.text ?: message.plaintextBody
            val envelope = ChessMessage.parseOrNull(replyUnwrapped) ?: continue
            if (envelope.gameId != gameId) continue
            lastMessageId = message.id
            val senderAddress = if (message.isOutgoing) myAddress else contactAddress

            when (envelope) {
                is ChessEnvelope.Invite -> {
                    invite = envelope.content
                    inviterAddress = senderAddress
                }
                is ChessEnvelope.Response -> {
                    response = envelope.content
                }
                is ChessEnvelope.Move -> {
                    val from = ChessSquare.fromAlgebraic(envelope.content.from) ?: continue
                    val to = ChessSquare.fromAlgebraic(envelope.content.to) ?: continue
                    val promotion = ChessPieceType.fromPromotionLetter(envelope.content.promotion)
                    val move = ChessEngine.normalizingPromotion(ChessMove(from, to, promotion), board)
                    if (!ChessEngine.isLegal(move, board)) continue
                    val movingPiece = board.piece(from) ?: continue
                    val isEnPassantCapture = movingPiece.type == ChessPieceType.PAWN &&
                        to == board.enPassantTarget && board.piece(to) == null
                    val capturedPiece = if (isEnPassantCapture) {
                        board.piece(ChessSquare(to.file, from.rank))
                    } else {
                        board.piece(to)
                    }
                    board = ChessEngine.apply(move, board)
                    moveHistory.add(
                        ChessMoveRecord(
                            from = from,
                            to = to,
                            pieceType = movingPiece.type,
                            color = movingPiece.color,
                            capturedType = capturedPiece?.type,
                            capturedColor = capturedPiece?.color,
                            promotion = promotion,
                            messageId = message.id,
                            clockMs = envelope.content.clockMs
                        )
                    )
                }
                is ChessEnvelope.Resign -> {
                    resignerAddress = senderAddress
                    resignReason = envelope.content.reason
                }
            }
        }

        val nonNullInvite = invite ?: return null
        val nonNullInviterAddress = inviterAddress ?: return null
        val otherAddress = if (nonNullInviterAddress == myAddress) contactAddress else myAddress
        val whiteAddress = if (nonNullInvite.inviterColor == ChessInviteColor.WHITE) nonNullInviterAddress else otherAddress
        val blackAddress = if (nonNullInvite.inviterColor == ChessInviteColor.WHITE) otherAddress else nonNullInviterAddress

        val status = when {
            resignerAddress != null -> {
                val loser = if (resignerAddress == whiteAddress) ChessColor.WHITE else ChessColor.BLACK
                ChessGameStatus(ChessGameStatusKind.RESIGNED, loser, timedOut = resignReason == "timeout")
            }
            response != null && !response.accepted -> ChessGameStatus(ChessGameStatusKind.DECLINED)
            response == null -> ChessGameStatus(ChessGameStatusKind.PENDING_RESPONSE)
            ChessEngine.isCheckmate(board) -> ChessGameStatus(ChessGameStatusKind.CHECKMATE, board.sideToMove.opposite)
            ChessEngine.isStalemate(board) -> ChessGameStatus(ChessGameStatusKind.STALEMATE)
            // After checkmate/stalemate: mate already ended the game, and mate wins over a draw.
            ChessEngine.isInsufficientMaterial(board) -> ChessGameStatus(ChessGameStatusKind.INSUFFICIENT_MATERIAL)
            else -> ChessGameStatus(ChessGameStatusKind.IN_PROGRESS)
        }

        val viewerColor = when (myAddress) {
            whiteAddress -> ChessColor.WHITE
            blackAddress -> ChessColor.BLACK
            else -> null
        }

        return ChessGameSummary(
            gameId = gameId,
            status = status,
            board = board,
            whiteAddress = whiteAddress,
            blackAddress = blackAddress,
            lastMessageId = lastMessageId ?: "",
            viewerColor = viewerColor,
            moveHistory = moveHistory,
            tcMinutes = nonNullInvite.tcMinutes,
            tcIncSeconds = nonNullInvite.tcIncSeconds
        )
    }

    /** Cumulative decisive-outcome tally across every distinct chess game ever invited with this
     *  contact (not just the current one) - checkmate/resignation count as a win or loss for the
     *  local player; stalemate/declined/pending/in-progress games don't count either way. Used by
     *  [ChessGameScreen]'s W/L counter and the chat info screen's "Chess Stats" row. */
    fun record(messages: List<ChessSourceMessage>, myAddress: String, contactAddress: String): Pair<Int, Int> {
        val inviteMessages = messages.filter { message ->
            val unwrapped = MessageReply.parseOrNull(message.plaintextBody)?.text ?: message.plaintextBody
            ChessMessage.parseOrNull(unwrapped) is ChessEnvelope.Invite
        }

        val seenGameIds = mutableSetOf<String>()
        var wins = 0
        var losses = 0
        for (message in inviteMessages) {
            val unwrapped = MessageReply.parseOrNull(message.plaintextBody)?.text ?: message.plaintextBody
            val envelope = ChessMessage.parseOrNull(unwrapped) ?: continue
            if (!seenGameIds.add(envelope.gameId)) continue
            val summary = summarize(envelope.gameId, messages, myAddress, contactAddress) ?: continue
            val myColor = summary.colorFor(myAddress) ?: continue
            when (summary.status.kind) {
                ChessGameStatusKind.CHECKMATE -> {
                    if (summary.status.color == myColor) wins++ else losses++
                }
                ChessGameStatusKind.RESIGNED -> {
                    if (summary.status.color == myColor) losses++ else wins++
                }
                else -> {}
            }
        }
        return wins to losses
    }

    /** The contact's current active (not yet game-over) chess game, if any - scans for every
     *  distinct `gameId` invited in `messages` and returns the summary for whichever is still
     *  active. Enforcement (see `ChatViewModel.startChessGame`) keeps at most one active game per
     *  contact, so this should never find more than one candidate in practice; if older history
     *  somehow left more than one, the most recently invited game wins. */
    fun activeGame(messages: List<ChessSourceMessage>, myAddress: String, contactAddress: String): ChessGameSummary? {
        val inviteMessages = messages
            .filter { message ->
                val unwrapped = MessageReply.parseOrNull(message.plaintextBody)?.text ?: message.plaintextBody
                ChessMessage.parseOrNull(unwrapped) is ChessEnvelope.Invite
            }
            .sortedByDescending { it.blockTimestamp }

        val seenGameIds = mutableSetOf<String>()
        for (message in inviteMessages) {
            val unwrapped = MessageReply.parseOrNull(message.plaintextBody)?.text ?: message.plaintextBody
            val envelope = ChessMessage.parseOrNull(unwrapped) ?: continue
            if (!seenGameIds.add(envelope.gameId)) continue
            val summary = summarize(envelope.gameId, messages, myAddress, contactAddress)
            if (summary != null && !summary.status.isGameOver) return summary
        }
        return null
    }

    /** True if `message` is any chess envelope and no *later* message in `messages` shares its
     *  `gameId` - i.e. this is the current/latest state for that game, which is the only one that
     *  should render as the live status card (earlier moves render as a compact log line
     *  instead, so a long game doesn't repeat a full board on every message). */
    fun isLatestChessMessage(message: ChessSourceMessage, messages: List<ChessSourceMessage>): Boolean {
        val envelope = ChessMessage.parseOrNull(
            MessageReply.parseOrNull(message.plaintextBody)?.text ?: message.plaintextBody
        ) ?: return false
        return messages.none { other ->
            other.blockTimestamp > message.blockTimestamp &&
                ChessMessage.parseOrNull(MessageReply.parseOrNull(other.plaintextBody)?.text ?: other.plaintextBody)?.gameId == envelope.gameId
        }
    }
}
