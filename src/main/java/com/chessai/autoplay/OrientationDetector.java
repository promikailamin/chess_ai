package com.chessai.autoplay;

import com.chessai.engine.Piece;
import com.chessai.engine.Position;

/**
 * Estimates whether the chess app displays the board from black's side.
 *
 * <p>The screenshot recogniser always describes the grid with the top row as
 * rank 8.  When the app flips the board, that grid is a 180-degree rotated
 * version of the real position.  Pawn direction is the strongest signal, with
 * piece placement near the home ranks as a fallback.</p>
 */
public final class OrientationDetector {
    private OrientationDetector() {
    }

    public static boolean isFlipped(Position visual) {
        int whiteBottom = 0;
        int blackBottom = 0;
        int whiteTop = 0;
        int blackTop = 0;
        double whitePawnRow = 0;
        double blackPawnRow = 0;
        int whitePawns = 0;
        int blackPawns = 0;

        for (int square = 0; square < 64; square++) {
            int piece = visual.pieceAt(square);
            if (piece == Piece.EMPTY) {
                continue;
            }
            int row = 7 - (square >>> 3);
            boolean white = Piece.isWhite(piece);
            if (row >= 6) {
                if (white) whiteBottom++; else blackBottom++;
            } else if (row <= 1) {
                if (white) whiteTop++; else blackTop++;
            }
            if (Piece.type(piece) == Piece.PAWN) {
                if (white) {
                    whitePawnRow += row;
                    whitePawns++;
                } else {
                    blackPawnRow += row;
                    blackPawns++;
                }
            }
        }

        if (whitePawns >= 2 && blackPawns >= 2) {
            double whiteAverage = whitePawnRow / whitePawns;
            double blackAverage = blackPawnRow / blackPawns;
            if (Math.abs(whiteAverage - blackAverage) >= 1.0) {
                return whiteAverage < blackAverage;
            }
        }

        int normalScore = whiteBottom + blackTop;
        int flippedScore = blackBottom + whiteTop;
        return flippedScore > normalScore;
    }
}
