package com.chessai.autoplay;

import com.chessai.engine.Move;
import com.chessai.engine.Piece;
import com.chessai.engine.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoPlaySessionTest {
    private static TurnText.TurnInfo turn(int side, int fullmove) {
        return new TurnText.TurnInfo(side, fullmove, null,
                side == Piece.WHITE ? "White's Move" : "Black's Move");
    }

    @Test
    void playsOnlyOnBotsTurnAndDoesNotRepeat() {
        AutoPlaySession session = new AutoPlaySession(Piece.BLACK, 4, 2, 150);
        Position start = Position.startPosition();

        assertEquals(AutoPlaySession.Kind.WAIT,
                session.decide(turn(Piece.WHITE, 1), start, false).kind);

        AutoPlaySession.Decision first = session.decide(turn(Piece.BLACK, 1), start, false);
        assertEquals(AutoPlaySession.Kind.WAIT, first.kind, "board must be seen twice");
        AutoPlaySession.Decision second = session.decide(turn(Piece.BLACK, 1), start, false);
        assertEquals(AutoPlaySession.Kind.PLAY, second.kind);
        assertTrue(start.isLegal(second.move));

        AutoPlaySession.Decision duplicate = session.decide(turn(Piece.BLACK, 1), start, false);
        assertEquals(AutoPlaySession.Kind.WAIT, duplicate.kind);
    }

    @Test
    void stopsOnCheckmate() {
        AutoPlaySession session = new AutoPlaySession(Piece.BLACK, 4, 2, 150);
        Position mate = Position.fromFen("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1");
        session.decide(turn(Piece.BLACK, 1), mate, false);
        AutoPlaySession.Decision decision = session.decide(turn(Piece.BLACK, 1), mate, false);
        assertEquals(AutoPlaySession.Kind.GAME_OVER, decision.kind);
        assertTrue(decision.message.toLowerCase().contains("checkmate"));
    }

    @Test
    void stopsWhenScreenSaysGameOver() {
        AutoPlaySession session = new AutoPlaySession(Piece.WHITE, 4, 2, 50);
        TurnText.TurnInfo result = TurnText.parse("White wins");
        AutoPlaySession.Decision decision = session.decide(result, Position.startPosition(), false);
        assertEquals(AutoPlaySession.Kind.GAME_OVER, decision.kind);
    }

    @Test
    void worksWithFlippedBoard() {
        AutoPlaySession session = new AutoPlaySession(Piece.WHITE, 4, 2, 150);
        Position start = Position.startPosition();
        Position rotated = new Position();
        rotated.clear();
        for (int square = 0; square < 64; square++) {
            rotated.setPiece(63 - square, start.pieceAt(square));
        }
        rotated.setCastling(BoardTransforms.swapCastling(start.getCastling()));

        session.decide(turn(Piece.WHITE, 1), rotated, true);
        AutoPlaySession.Decision decision = session.decide(turn(Piece.WHITE, 1), rotated, true);
        assertEquals(AutoPlaySession.Kind.PLAY, decision.kind);
        assertTrue(Move.from(decision.move) != Move.to(decision.move));
    }
}
