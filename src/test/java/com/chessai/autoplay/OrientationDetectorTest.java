package com.chessai.autoplay;

import com.chessai.engine.Piece;
import com.chessai.engine.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrientationDetectorTest {
    @Test
    void recognisesWhiteAtBottom() {
        assertFalse(OrientationDetector.isFlipped(Position.startPosition()));
    }

    @Test
    void recognisesBlackAtBottom() {
        Position start = Position.startPosition();
        Position rotated = new Position();
        rotated.clear();
        for (int square = 0; square < 64; square++) {
            rotated.setPiece(63 - square, start.pieceAt(square));
        }
        rotated.setCastling(BoardTransforms.swapCastling(start.getCastling()));
        assertTrue(OrientationDetector.isFlipped(rotated));
    }

    @Test
    void transformsFlippedBoardBackToRealPosition() {
        Position start = Position.startPosition();
        Position rotated = new Position();
        rotated.clear();
        for (int square = 0; square < 64; square++) {
            rotated.setPiece(63 - square, start.pieceAt(square));
        }
        rotated.setCastling(BoardTransforms.swapCastling(start.getCastling()));
        Position actual = BoardTransforms.actualPosition(rotated, true, Piece.WHITE, 1);
        assertTrue(OrientationDetector.isFlipped(rotated));
        for (int square = 0; square < 64; square++) {
            if (start.pieceAt(square) != actual.pieceAt(square)) {
                throw new AssertionError("mismatch on " + com.chessai.engine.Move.squareName(square));
            }
        }
    }
}
