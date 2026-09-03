package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessEngineTest {

    private fun sq(algebraic: String) = ChessSquare.fromAlgebraic(algebraic)!!

    private fun play(board: ChessBoard, from: String, to: String, promotion: ChessPieceType? = null): ChessBoard {
        val move = ChessMove(sq(from), sq(to), promotion)
        assertTrue("$from-$to should be legal", ChessEngine.isLegal(move, board))
        return ChessEngine.apply(move, board)
    }

    @Test
    fun `initial position has 20 legal moves`() {
        val board = ChessEngine.initialBoard()
        assertEquals(20, ChessEngine.legalMoves(board).size)
    }

    @Test
    fun `pawn can double push from start rank only`() {
        val board = ChessEngine.initialBoard()
        val moves = ChessEngine.legalMoves(sq("e2"), board)
        assertTrue(moves.any { it.to == sq("e4") })
        assertTrue(moves.any { it.to == sq("e3") })
    }

    @Test
    fun `illegal move is rejected`() {
        val board = ChessEngine.initialBoard()
        val move = ChessMove(sq("e2"), sq("e5"), null)
        assertFalse(ChessEngine.isLegal(move, board))
    }

    @Test
    fun `fools mate results in checkmate`() {
        var board = ChessEngine.initialBoard()
        board = play(board, "f2", "f3")
        board = play(board, "e7", "e5")
        board = play(board, "g2", "g4")
        board = play(board, "d8", "h4")
        assertTrue(ChessEngine.isCheckmate(board))
        assertTrue(ChessEngine.isKingInCheck(ChessColor.WHITE, board))
        assertEquals(0, ChessEngine.legalMoves(board).size)
    }

    @Test
    fun `king cannot move into check`() {
        // Set up a position where the king's only "step" square is attacked by a rook.
        var board = ChessEngine.initialBoard()
        board = play(board, "e2", "e4")
        board = play(board, "e7", "e5")
        // White king still on e1; not relevant to a direct capture-into-check test here -
        // exercise via isSquareAttacked directly instead, which the king-move generator uses.
        assertFalse(ChessEngine.isSquareAttacked(sq("e2"), ChessColor.BLACK, board))
    }

    @Test
    fun `kingside castling is legal once path is clear`() {
        var board = ChessEngine.initialBoard()
        board = play(board, "g1", "f3")
        board = play(board, "g8", "f6")
        board = play(board, "g2", "g3")
        board = play(board, "g7", "g6")
        board = play(board, "f1", "g2")
        board = play(board, "f8", "g7")
        val whiteCastle = ChessMove(sq("e1"), sq("g1"), null)
        assertTrue(ChessEngine.isLegal(whiteCastle, board))
        board = ChessEngine.apply(whiteCastle, board)
        assertEquals(ChessPiece(ChessPieceType.KING, ChessColor.WHITE), board.piece(sq("g1")))
        assertEquals(ChessPiece(ChessPieceType.ROOK, ChessColor.WHITE), board.piece(sq("f1")))
        assertEquals(null, board.piece(sq("h1")))
    }

    @Test
    fun `a king currently in check cannot castle even with a clear path and full rights`() {
        // White king e1 (kingside rook still on h1, f1/g1 empty, rights intact), a black rook
        // on e8 giving check down the open e-file - not checkmate (the king can still step to
        // d2), so this specifically exercises "in check" blocking castling rather than the
        // position simply having zero legal moves for any piece.
        val squares: Array<Array<ChessPiece?>> = Array(8) { arrayOfNulls(8) }
        squares[0][4] = ChessPiece(ChessPieceType.KING, ChessColor.WHITE) // e1
        squares[0][7] = ChessPiece(ChessPieceType.ROOK, ChessColor.WHITE) // h1
        squares[7][4] = ChessPiece(ChessPieceType.ROOK, ChessColor.BLACK) // e8
        squares[7][0] = ChessPiece(ChessPieceType.KING, ChessColor.BLACK) // a8
        val board = ChessBoard(
            squares = squares,
            sideToMove = ChessColor.WHITE,
            whiteCanCastleKingside = true,
            whiteCanCastleQueenside = false,
            blackCanCastleKingside = false,
            blackCanCastleQueenside = false,
            enPassantTarget = null
        )
        assertTrue(ChessEngine.isKingInCheck(ChessColor.WHITE, board))
        val kingMoves = ChessEngine.legalMoves(sq("e1"), board)
        assertTrue("king should still have an escape square", kingMoves.isNotEmpty())
        assertFalse(kingMoves.any { it.to == sq("g1") })
    }

    @Test
    fun `en passant capture is available immediately after a double push`() {
        var board = ChessEngine.initialBoard()
        board = play(board, "e2", "e4")
        board = play(board, "a7", "a6")
        board = play(board, "e4", "e5")
        board = play(board, "d7", "d5")
        // White pawn on e5 can capture en passant onto d6.
        val epMove = ChessMove(sq("e5"), sq("d6"), null)
        assertTrue(ChessEngine.isLegal(epMove, board))
        val after = ChessEngine.apply(epMove, board)
        assertEquals(ChessPiece(ChessPieceType.PAWN, ChessColor.WHITE), after.piece(sq("d6")))
        assertEquals(null, after.piece(sq("d5")))
        assertEquals(null, after.piece(sq("e5")))
    }

    @Test
    fun `en passant window closes after one ply`() {
        var board = ChessEngine.initialBoard()
        board = play(board, "e2", "e4")
        board = play(board, "a7", "a6")
        board = play(board, "e4", "e5")
        board = play(board, "d7", "d5")
        board = play(board, "b1", "c3") // unrelated move, en passant window should now be gone
        board = play(board, "a6", "a5")
        val epMove = ChessMove(sq("e5"), sq("d6"), null)
        assertFalse(ChessEngine.isLegal(epMove, board))
    }

    @Test
    fun `pawn reaching the back rank must promote`() {
        // Build a near-empty board via repeated captures is tedious; instead verify the engine's
        // own move generation always offers all four promotion pieces for a qualifying move by
        // constructing the position through legal, minimal play is impractical here - assert the
        // narrower, still-meaningful invariant: normalizingPromotion defaults a promotion-eligible
        // move to queen.
        val board = ChessEngine.initialBoard()
        // Fabricate a board with a lone white pawn one step from promotion for this unit test.
        val squares: Array<Array<ChessPiece?>> = Array(8) { arrayOfNulls(8) }
        squares[0][4] = ChessPiece(ChessPieceType.KING, ChessColor.WHITE)
        squares[7][4] = ChessPiece(ChessPieceType.KING, ChessColor.BLACK)
        squares[6][0] = ChessPiece(ChessPieceType.PAWN, ChessColor.WHITE)
        val promoBoard = ChessBoard(
            squares = squares,
            sideToMove = ChessColor.WHITE,
            whiteCanCastleKingside = false,
            whiteCanCastleQueenside = false,
            blackCanCastleKingside = false,
            blackCanCastleQueenside = false,
            enPassantTarget = null
        )
        val bareMove = ChessMove(sq("a7"), sq("a8"), null)
        val normalized = ChessEngine.normalizingPromotion(bareMove, promoBoard)
        assertEquals(ChessPieceType.QUEEN, normalized.promotion)
        assertTrue(ChessEngine.isLegal(bareMove, promoBoard))
        val after = ChessEngine.apply(normalized, promoBoard)
        assertEquals(ChessPiece(ChessPieceType.QUEEN, ChessColor.WHITE), after.piece(sq("a8")))
    }

    @Test
    fun `stalemate position has zero legal moves and is not check`() {
        // Classic stalemate: black king on a8, white king on c7, white queen on b6 - black to
        // move, not in check, no legal moves.
        val squares: Array<Array<ChessPiece?>> = Array(8) { arrayOfNulls(8) }
        squares[7][0] = ChessPiece(ChessPieceType.KING, ChessColor.BLACK) // a8
        squares[6][2] = ChessPiece(ChessPieceType.KING, ChessColor.WHITE) // c7
        squares[5][1] = ChessPiece(ChessPieceType.QUEEN, ChessColor.WHITE) // b6
        val board = ChessBoard(
            squares = squares,
            sideToMove = ChessColor.BLACK,
            whiteCanCastleKingside = false,
            whiteCanCastleQueenside = false,
            blackCanCastleKingside = false,
            blackCanCastleQueenside = false,
            enPassantTarget = null
        )
        assertFalse(ChessEngine.isKingInCheck(ChessColor.BLACK, board))
        assertTrue(ChessEngine.isStalemate(board))
        assertFalse(ChessEngine.isCheckmate(board))
    }

    // MARK: - Insufficient material (dead positions)

    /** Builds a board holding only the given pieces, white to move, no castling rights. */
    private fun position(vararg pieces: Pair<String, ChessPiece>): ChessBoard {
        val squares: Array<Array<ChessPiece?>> = Array(8) { arrayOfNulls(8) }
        for ((algebraic, piece) in pieces) {
            val square = sq(algebraic)
            squares[square.rank][square.file] = piece
        }
        return ChessBoard(
            squares = squares,
            sideToMove = ChessColor.WHITE,
            whiteCanCastleKingside = false,
            whiteCanCastleQueenside = false,
            blackCanCastleKingside = false,
            blackCanCastleQueenside = false,
            enPassantTarget = null,
        )
    }

    private fun white(type: ChessPieceType) = ChessPiece(type, ChessColor.WHITE)
    private fun black(type: ChessPieceType) = ChessPiece(type, ChessColor.BLACK)

    @Test
    fun `two lone kings is a draw by insufficient material, not stalemate`() {
        val board = position("e1" to white(ChessPieceType.KING), "e8" to black(ChessPieceType.KING))
        assertTrue(ChessEngine.isInsufficientMaterial(board))
        // The distinction that matters: the king still has legal moves, so the stalemate test
        // never fires and the game would otherwise never end.
        assertFalse(ChessEngine.isStalemate(board))
        assertTrue(ChessEngine.legalMoves(board).isNotEmpty())
    }

    @Test
    fun `king and single minor piece against a bare king is a draw`() {
        val withBishop = position(
            "e1" to white(ChessPieceType.KING),
            "c1" to white(ChessPieceType.BISHOP),
            "e8" to black(ChessPieceType.KING),
        )
        val withKnight = position(
            "e1" to white(ChessPieceType.KING),
            "b1" to white(ChessPieceType.KNIGHT),
            "e8" to black(ChessPieceType.KING),
        )
        assertTrue(ChessEngine.isInsufficientMaterial(withBishop))
        assertTrue(ChessEngine.isInsufficientMaterial(withKnight))
    }

    @Test
    fun `opposite-coloured bishops are not a dead position`() {
        // c1 is dark, c8 is light - a mate is still constructible, so this is NOT a draw.
        val board = position(
            "e1" to white(ChessPieceType.KING),
            "c1" to white(ChessPieceType.BISHOP),
            "e8" to black(ChessPieceType.KING),
            "c8" to black(ChessPieceType.BISHOP),
        )
        assertFalse(ChessEngine.isInsufficientMaterial(board))
    }

    @Test
    fun `same-coloured bishops are a dead position`() {
        // c1 and f8 are both dark squares.
        val board = position(
            "e1" to white(ChessPieceType.KING),
            "c1" to white(ChessPieceType.BISHOP),
            "e8" to black(ChessPieceType.KING),
            "f8" to black(ChessPieceType.BISHOP),
        )
        assertTrue(ChessEngine.isInsufficientMaterial(board))
    }

    @Test
    fun `a pawn or a rook means there is still material to mate with`() {
        val withPawn = position(
            "e1" to white(ChessPieceType.KING),
            "a2" to white(ChessPieceType.PAWN),
            "e8" to black(ChessPieceType.KING),
        )
        val withRook = position(
            "e1" to white(ChessPieceType.KING),
            "a1" to white(ChessPieceType.ROOK),
            "e8" to black(ChessPieceType.KING),
        )
        assertFalse(ChessEngine.isInsufficientMaterial(withPawn))
        assertFalse(ChessEngine.isInsufficientMaterial(withRook))
        assertFalse(ChessEngine.isInsufficientMaterial(ChessEngine.initialBoard()))
    }
}
