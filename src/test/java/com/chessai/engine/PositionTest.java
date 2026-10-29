package com.chessai.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionTest {
    @Test
    void fenRoundTrip() {
        String fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 3 17";
        Position position = Position.fromFen(fen);
        assertEquals(fen, position.toFen());
        assertEquals(Piece.WHITE, position.getSide());
        assertEquals(17, position.getFullmoveNumber());
    }

    @Test
    void checkmateAndStalemate() {
        Position mate = Position.fromFen("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1");
        assertTrue(mate.inCheck(Piece.BLACK));
        assertTrue(mate.isGameOver());

        Position stalemate = Position.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
        assertFalse(stalemate.inCheck(Piece.BLACK));
        assertTrue(stalemate.isGameOver());
    }

    @Test
    void legalMovesRespectPins() {
        Position position = Position.fromFen("4k3/8/8/8/8/8/4R3/4K3 b - - 0 1");
        MoveList moves = new MoveList(32);
        position.generateLegalMoves(moves);
        for (int i = 0; i < moves.size(); i++) {
            int to = Move.to(moves.get(i));
            assertFalse(to == 60 || to == 52 || to == 44,
                    "King must not move onto the rook's file");
        }
    }

    @Test
    void castlingIsGenerated() {
        Position position = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        MoveList moves = new MoveList(64);
        position.generateLegalMoves(moves);
        int e1 = 4;
        boolean kingSide = false;
        boolean queenSide = false;
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.get(i);
            if (Move.from(move) == e1 && Move.to(move) == 6) {
                kingSide = true;
            }
            if (Move.from(move) == e1 && Move.to(move) == 2) {
                queenSide = true;
            }
        }
        assertTrue(kingSide);
        assertTrue(queenSide);
    }
}
