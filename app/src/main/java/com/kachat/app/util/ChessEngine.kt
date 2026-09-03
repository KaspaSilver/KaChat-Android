package com.kachat.app.util

/**
 * Pure, UI-free chess rules engine backing the "Play Chess" 1:1 chat feature. No external
 * dependency (unlike a Gradle dependency, this project has no safe way to review/vet a chess
 * library choice mid-session, and the iOS side can't add an SPM package here at all - see
 * ChessGameService's doc comment) - hand-written and ported 1:1 from iOS's ChessEngine.swift.
 * Board state is never persisted directly; it's always re-derived by replaying a game's move
 * messages through this engine (see ChessGameService.summarize).
 */
enum class ChessColor {
    WHITE, BLACK;

    val opposite: ChessColor get() = if (this == WHITE) BLACK else WHITE
}

enum class ChessPieceType {
    PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING;

    /** Single-letter wire notation for promotion ("q"/"r"/"b"/"n"). */
    val promotionLetter: String?
        get() = when (this) {
            QUEEN -> "q"
            ROOK -> "r"
            BISHOP -> "b"
            KNIGHT -> "n"
            else -> null
        }

    companion object {
        fun fromPromotionLetter(letter: String?): ChessPieceType? = when (letter?.lowercase()) {
            "q" -> QUEEN
            "r" -> ROOK
            "b" -> BISHOP
            "n" -> KNIGHT
            else -> null
        }
    }
}

data class ChessPiece(val type: ChessPieceType, val color: ChessColor) {
    /** Unicode chess glyph - used by both the in-chat board thumbnail and the full-screen board,
     *  so neither needs bundled piece image assets. Deliberately always uses the solid/filled
     *  "black chess piece" code points (U+265A-265F) regardless of `color` - the "white chess
     *  piece" code points (U+2654-2659) are outline-only glyphs with almost no solid area, so
     *  filling them white (see ChessPieceGlyph's fillColor in Screens.kt) against a light board
     *  square looked nearly invisible. Color is conveyed entirely by that fill/outline trick, not
     *  by which glyph is used.
     *
     *  The trailing U+FE0E (text presentation selector) on the pawn matters on iOS, where Apple's
     *  system font has a special colored glyph for the bare pawn character that ignores the
     *  applied text color entirely (unlike the other five chess symbols) - ported here too for
     *  parity in case Android's emoji font ever does the same for this code point; it's a no-op
     *  otherwise. */
    val glyph: String
        get() = when (type) {
            ChessPieceType.KING -> "♚"
            ChessPieceType.QUEEN -> "♛"
            ChessPieceType.ROOK -> "♜"
            ChessPieceType.BISHOP -> "♝"
            ChessPieceType.KNIGHT -> "♞"
            ChessPieceType.PAWN -> "♟︎"
        }
}

/** 0-indexed file (a=0...h=7) and rank (1=0...8=7). */
data class ChessSquare(val file: Int, val rank: Int) {
    val isValid: Boolean get() = file in 0..7 && rank in 0..7

    /** Algebraic notation, e.g. "e4". */
    val algebraic: String get() = "${('a' + file)}${rank + 1}"

    companion object {
        fun fromAlgebraic(algebraic: String): ChessSquare? {
            if (algebraic.length != 2) return null
            val fileChar = algebraic[0].lowercaseChar()
            val rankChar = algebraic[1]
            if (fileChar < 'a' || fileChar > 'h') return null
            val rankDigit = rankChar.digitToIntOrNull() ?: return null
            if (rankDigit !in 1..8) return null
            return ChessSquare(fileChar - 'a', rankDigit - 1)
        }
    }
}

data class ChessMove(val from: ChessSquare, val to: ChessSquare, val promotion: ChessPieceType?) {
    /** Lets a pending promotion choice key a Compose `key()`/dialog state directly. */
    val id: String get() = "${from.algebraic}-${to.algebraic}-${promotion?.promotionLetter ?: ""}"
}

data class ChessBoard(
    /** squares[rank][file], rank 0 = rank "1". */
    val squares: Array<Array<ChessPiece?>>,
    val sideToMove: ChessColor,
    val whiteCanCastleKingside: Boolean,
    val whiteCanCastleQueenside: Boolean,
    val blackCanCastleKingside: Boolean,
    val blackCanCastleQueenside: Boolean,
    /** The square a pawn skipped over on its most recent double-push, capturable en passant this
     *  ply only - cleared on every move that isn't itself a qualifying double push. */
    val enPassantTarget: ChessSquare?
) {
    fun piece(square: ChessSquare): ChessPiece? {
        if (!square.isValid) return null
        return squares[square.rank][square.file]
    }

    fun canCastleKingside(color: ChessColor): Boolean = if (color == ChessColor.WHITE) whiteCanCastleKingside else blackCanCastleKingside
    fun canCastleQueenside(color: ChessColor): Boolean = if (color == ChessColor.WHITE) whiteCanCastleQueenside else blackCanCastleQueenside

    fun copyBoard(): Array<Array<ChessPiece?>> = Array(8) { r -> Array(8) { f -> squares[r][f] } }

    // equals/hashCode by structure (array content), not reference - data class default only
    // compares Array *references*, which would break equality-based state comparisons.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChessBoard) return false
        return squares.contentDeepEquals(other.squares) &&
            sideToMove == other.sideToMove &&
            whiteCanCastleKingside == other.whiteCanCastleKingside &&
            whiteCanCastleQueenside == other.whiteCanCastleQueenside &&
            blackCanCastleKingside == other.blackCanCastleKingside &&
            blackCanCastleQueenside == other.blackCanCastleQueenside &&
            enPassantTarget == other.enPassantTarget
    }

    override fun hashCode(): Int {
        var result = squares.contentDeepHashCode()
        result = 31 * result + sideToMove.hashCode()
        result = 31 * result + whiteCanCastleKingside.hashCode()
        result = 31 * result + whiteCanCastleQueenside.hashCode()
        result = 31 * result + blackCanCastleKingside.hashCode()
        result = 31 * result + blackCanCastleQueenside.hashCode()
        result = 31 * result + (enPassantTarget?.hashCode() ?: 0)
        return result
    }
}

object ChessEngine {
    private val knightOffsets = listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
    private val kingOffsets = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
    private val diagonalDirections = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
    private val straightDirections = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    fun initialBoard(): ChessBoard {
        val squares: Array<Array<ChessPiece?>> = Array(8) { arrayOfNulls(8) }
        val backRank = listOf(
            ChessPieceType.ROOK, ChessPieceType.KNIGHT, ChessPieceType.BISHOP, ChessPieceType.QUEEN,
            ChessPieceType.KING, ChessPieceType.BISHOP, ChessPieceType.KNIGHT, ChessPieceType.ROOK
        )
        for (file in 0..7) {
            squares[0][file] = ChessPiece(backRank[file], ChessColor.WHITE)
            squares[1][file] = ChessPiece(ChessPieceType.PAWN, ChessColor.WHITE)
            squares[6][file] = ChessPiece(ChessPieceType.PAWN, ChessColor.BLACK)
            squares[7][file] = ChessPiece(backRank[file], ChessColor.BLACK)
        }
        return ChessBoard(
            squares = squares,
            sideToMove = ChessColor.WHITE,
            whiteCanCastleKingside = true,
            whiteCanCastleQueenside = true,
            blackCanCastleKingside = true,
            blackCanCastleQueenside = true,
            enPassantTarget = null
        )
    }

    // ---- Move generation --------------------------------------------------------------------

    /** Every legal move for the side to move - pseudo-legal moves filtered to exclude any that
     *  leave the mover's own king in check. */
    fun legalMoves(board: ChessBoard): List<ChessMove> {
        val moves = mutableListOf<ChessMove>()
        for (rank in 0..7) {
            for (file in 0..7) {
                val square = ChessSquare(file, rank)
                val piece = board.piece(square) ?: continue
                if (piece.color != board.sideToMove) continue
                moves.addAll(pseudoLegalMoves(piece, square, board))
            }
        }
        return moves.filter { move ->
            val resulting = apply(move, board)
            !isKingInCheck(board.sideToMove, resulting)
        }
    }

    fun legalMoves(square: ChessSquare, board: ChessBoard): List<ChessMove> =
        legalMoves(board).filter { it.from == square }

    fun isLegal(move: ChessMove, board: ChessBoard): Boolean =
        legalMoves(board).contains(normalizingPromotion(move, board))

    /** A wire move with `promotion == null` reaching the back rank defaults to queen - matches
     *  the engine's own generated move list, which always includes an explicit queen-promotion
     *  variant for such a move. */
    fun normalizingPromotion(move: ChessMove, board: ChessBoard): ChessMove {
        if (move.promotion != null) return move
        val piece = board.piece(move.from) ?: return move
        if (piece.type != ChessPieceType.PAWN) return move
        val backRank = if (piece.color == ChessColor.WHITE) 7 else 0
        if (move.to.rank != backRank) return move
        return move.copy(promotion = ChessPieceType.QUEEN)
    }

    fun isKingInCheck(color: ChessColor, board: ChessBoard): Boolean {
        val kingSquare = findKing(color, board) ?: return false
        return isSquareAttacked(kingSquare, color.opposite, board)
    }

    fun isCheckmate(board: ChessBoard): Boolean =
        isKingInCheck(board.sideToMove, board) && legalMoves(board).isEmpty()

    fun isStalemate(board: ChessBoard): Boolean =
        !isKingInCheck(board.sideToMove, board) && legalMoves(board).isEmpty()

    /**
     * A dead position: no sequence of legal moves by either side can produce checkmate, so the
     * game is drawn immediately (FIDE 5.2.2). The four material combinations that qualify:
     * K vs K, K+B vs K, K+N vs K, and K+B vs K+B with both bishops on same-coloured squares.
     *
     * This is NOT stalemate - a lone king almost always HAS legal moves, so the stalemate test
     * never fires and, without this, two bare kings could be shuffled forever with no way to end
     * the game short of resigning or the clock running out.
     */
    fun isInsufficientMaterial(board: ChessBoard): Boolean {
        val whiteMinors = mutableListOf<ChessSquare>()
        val blackMinors = mutableListOf<ChessSquare>()

        for (rank in 0..7) {
            for (file in 0..7) {
                val square = ChessSquare(file, rank)
                val piece = board.piece(square) ?: continue
                when (piece.type) {
                    ChessPieceType.KING -> continue
                    ChessPieceType.BISHOP, ChessPieceType.KNIGHT ->
                        if (piece.color == ChessColor.WHITE) whiteMinors.add(square) else blackMinors.add(square)
                    // Any of these can force (or promote into) a mate.
                    else -> return false
                }
            }
        }

        return when {
            whiteMinors.isEmpty() && blackMinors.isEmpty() -> true          // K vs K
            whiteMinors.size + blackMinors.size == 1 -> true                 // K+B vs K, K+N vs K
            whiteMinors.size == 1 && blackMinors.size == 1 -> {
                // K+B vs K+B is dead only when both bishops are on the same colour squares - two
                // knights, or bishops on opposite colours, can still mate with help.
                val white = whiteMinors.first()
                val black = blackMinors.first()
                board.piece(white)?.type == ChessPieceType.BISHOP &&
                    board.piece(black)?.type == ChessPieceType.BISHOP &&
                    isLightSquare(white) == isLightSquare(black)
            }
            else -> false
        }
    }

    private fun isLightSquare(square: ChessSquare): Boolean = (square.file + square.rank) % 2 == 1

    private fun findKing(color: ChessColor, board: ChessBoard): ChessSquare? {
        for (rank in 0..7) {
            for (file in 0..7) {
                val square = ChessSquare(file, rank)
                val piece = board.piece(square)
                if (piece != null && piece.type == ChessPieceType.KING && piece.color == color) return square
            }
        }
        return null
    }

    /** True if any `color` piece currently attacks `square` - used for check detection and for
     *  castling's "king may not pass through or land on an attacked square" rule. Deliberately
     *  separate from move generation for pawns (a pawn attacks diagonally regardless of whether
     *  that square is occupied, but only ever *moves* straight into an empty square). */
    fun isSquareAttacked(square: ChessSquare, color: ChessColor, board: ChessBoard): Boolean {
        val pawnRankOffset = if (color == ChessColor.WHITE) -1 else 1
        for (fileOffset in listOf(-1, 1)) {
            val from = ChessSquare(square.file + fileOffset, square.rank + pawnRankOffset)
            val piece = board.piece(from)
            if (piece != null && piece.type == ChessPieceType.PAWN && piece.color == color) return true
        }
        for ((df, dr) in knightOffsets) {
            val from = ChessSquare(square.file + df, square.rank + dr)
            val piece = board.piece(from)
            if (piece != null && piece.type == ChessPieceType.KNIGHT && piece.color == color) return true
        }
        for ((df, dr) in kingOffsets) {
            val from = ChessSquare(square.file + df, square.rank + dr)
            val piece = board.piece(from)
            if (piece != null && piece.type == ChessPieceType.KING && piece.color == color) return true
        }
        for (direction in diagonalDirections) {
            if (slidingAttacker(square, direction, board, color, setOf(ChessPieceType.BISHOP, ChessPieceType.QUEEN))) return true
        }
        for (direction in straightDirections) {
            if (slidingAttacker(square, direction, board, color, setOf(ChessPieceType.ROOK, ChessPieceType.QUEEN))) return true
        }
        return false
    }

    private fun slidingAttacker(
        square: ChessSquare,
        direction: Pair<Int, Int>,
        board: ChessBoard,
        color: ChessColor,
        types: Set<ChessPieceType>
    ): Boolean {
        var current = ChessSquare(square.file + direction.first, square.rank + direction.second)
        while (current.isValid) {
            val piece = board.piece(current)
            if (piece != null) {
                return piece.color == color && types.contains(piece.type)
            }
            current = ChessSquare(current.file + direction.first, current.rank + direction.second)
        }
        return false
    }

    // ---- Pseudo-legal move generation (per piece, ignoring own-king-safety) ------------------

    private fun pseudoLegalMoves(piece: ChessPiece, square: ChessSquare, board: ChessBoard): List<ChessMove> =
        when (piece.type) {
            ChessPieceType.PAWN -> pawnMoves(piece.color, square, board)
            ChessPieceType.KNIGHT -> steppingMoves(knightOffsets, piece.color, square, board)
            ChessPieceType.BISHOP -> slidingMoves(diagonalDirections, piece.color, square, board)
            ChessPieceType.ROOK -> slidingMoves(straightDirections, piece.color, square, board)
            ChessPieceType.QUEEN -> slidingMoves(diagonalDirections + straightDirections, piece.color, square, board)
            ChessPieceType.KING -> kingMoves(piece.color, square, board)
        }

    private fun pawnMoves(color: ChessColor, square: ChessSquare, board: ChessBoard): List<ChessMove> {
        val moves = mutableListOf<ChessMove>()
        val direction = if (color == ChessColor.WHITE) 1 else -1
        val startRank = if (color == ChessColor.WHITE) 1 else 6
        val backRank = if (color == ChessColor.WHITE) 7 else 0

        fun addMove(to: ChessSquare) {
            if (!to.isValid) return
            if (to.rank == backRank) {
                for (promo in listOf(ChessPieceType.QUEEN, ChessPieceType.ROOK, ChessPieceType.BISHOP, ChessPieceType.KNIGHT)) {
                    moves.add(ChessMove(square, to, promo))
                }
            } else {
                moves.add(ChessMove(square, to, null))
            }
        }

        val singlePush = ChessSquare(square.file, square.rank + direction)
        if (singlePush.isValid && board.piece(singlePush) == null) {
            addMove(singlePush)
            val doublePush = ChessSquare(square.file, square.rank + direction * 2)
            if (square.rank == startRank && board.piece(doublePush) == null) {
                moves.add(ChessMove(square, doublePush, null))
            }
        }

        for (fileOffset in listOf(-1, 1)) {
            val target = ChessSquare(square.file + fileOffset, square.rank + direction)
            if (!target.isValid) continue
            val occupant = board.piece(target)
            if (occupant != null && occupant.color != color) {
                addMove(target)
            } else if (target == board.enPassantTarget) {
                moves.add(ChessMove(square, target, null))
            }
        }
        return moves
    }

    private fun steppingMoves(offsets: List<Pair<Int, Int>>, color: ChessColor, square: ChessSquare, board: ChessBoard): List<ChessMove> {
        val moves = mutableListOf<ChessMove>()
        for ((df, dr) in offsets) {
            val target = ChessSquare(square.file + df, square.rank + dr)
            if (!target.isValid) continue
            val occupant = board.piece(target)
            if (occupant != null && occupant.color == color) continue
            moves.add(ChessMove(square, target, null))
        }
        return moves
    }

    private fun slidingMoves(directions: List<Pair<Int, Int>>, color: ChessColor, square: ChessSquare, board: ChessBoard): List<ChessMove> {
        val moves = mutableListOf<ChessMove>()
        for ((df, dr) in directions) {
            var target = ChessSquare(square.file + df, square.rank + dr)
            while (target.isValid) {
                val occupant = board.piece(target)
                if (occupant != null) {
                    if (occupant.color != color) moves.add(ChessMove(square, target, null))
                    break
                }
                moves.add(ChessMove(square, target, null))
                target = ChessSquare(target.file + df, target.rank + dr)
            }
        }
        return moves
    }

    private fun kingMoves(color: ChessColor, square: ChessSquare, board: ChessBoard): List<ChessMove> {
        val moves = steppingMoves(kingOffsets, color, square, board).toMutableList()

        // Castling: king/rook still have rights, in-between squares empty, king not currently in
        // check and doesn't pass through or land on an attacked square.
        if (isSquareAttacked(square, color.opposite, board)) return moves
        val rank = if (color == ChessColor.WHITE) 0 else 7

        if (board.canCastleKingside(color) &&
            board.piece(ChessSquare(5, rank)) == null &&
            board.piece(ChessSquare(6, rank)) == null &&
            !isSquareAttacked(ChessSquare(5, rank), color.opposite, board) &&
            !isSquareAttacked(ChessSquare(6, rank), color.opposite, board)
        ) {
            moves.add(ChessMove(square, ChessSquare(6, rank), null))
        }
        if (board.canCastleQueenside(color) &&
            board.piece(ChessSquare(3, rank)) == null &&
            board.piece(ChessSquare(2, rank)) == null &&
            board.piece(ChessSquare(1, rank)) == null &&
            !isSquareAttacked(ChessSquare(3, rank), color.opposite, board) &&
            !isSquareAttacked(ChessSquare(2, rank), color.opposite, board)
        ) {
            moves.add(ChessMove(square, ChessSquare(2, rank), null))
        }
        return moves
    }

    // ---- Applying a move ----------------------------------------------------------------------

    /** Mechanically performs `move` (assumed to be at least pseudo-legal - `legalMoves` is the
     *  gate that should be checked before calling this for a real game action). Handles capture,
     *  en passant capture, castling (moves the rook too), promotion, and updates castling
     *  rights/en passant target/side to move. */
    fun apply(move: ChessMove, board: ChessBoard): ChessBoard {
        val squares = board.copyBoard()
        val piece = board.piece(move.from) ?: return board.copy(squares = squares)

        val isEnPassantCapture = piece.type == ChessPieceType.PAWN && move.to == board.enPassantTarget && board.piece(move.to) == null
        val isCastle = piece.type == ChessPieceType.KING && kotlin.math.abs(move.to.file - move.from.file) == 2

        squares[move.from.rank][move.from.file] = null
        val movedPiece = ChessPiece(move.promotion ?: piece.type, piece.color)
        squares[move.to.rank][move.to.file] = movedPiece

        if (isEnPassantCapture) {
            squares[move.from.rank][move.to.file] = null
        }

        if (isCastle) {
            val rank = move.from.rank
            if (move.to.file == 6) {
                squares[rank][7] = null
                squares[rank][5] = ChessPiece(ChessPieceType.ROOK, piece.color)
            } else {
                squares[rank][0] = null
                squares[rank][3] = ChessPiece(ChessPieceType.ROOK, piece.color)
            }
        }

        var whiteKingside = board.whiteCanCastleKingside
        var whiteQueenside = board.whiteCanCastleQueenside
        var blackKingside = board.blackCanCastleKingside
        var blackQueenside = board.blackCanCastleQueenside

        if (piece.type == ChessPieceType.KING) {
            if (piece.color == ChessColor.WHITE) {
                whiteKingside = false; whiteQueenside = false
            } else {
                blackKingside = false; blackQueenside = false
            }
        }
        fun revoke(square: ChessSquare) {
            when (square.file to square.rank) {
                0 to 0 -> whiteQueenside = false
                7 to 0 -> whiteKingside = false
                0 to 7 -> blackQueenside = false
                7 to 7 -> blackKingside = false
            }
        }
        revoke(move.from)
        revoke(move.to)

        val enPassantTarget = if (piece.type == ChessPieceType.PAWN && kotlin.math.abs(move.to.rank - move.from.rank) == 2) {
            ChessSquare(move.from.file, (move.from.rank + move.to.rank) / 2)
        } else {
            null
        }

        return ChessBoard(
            squares = squares,
            sideToMove = board.sideToMove.opposite,
            whiteCanCastleKingside = whiteKingside,
            whiteCanCastleQueenside = whiteQueenside,
            blackCanCastleKingside = blackKingside,
            blackCanCastleQueenside = blackQueenside,
            enPassantTarget = enPassantTarget
        )
    }
}
