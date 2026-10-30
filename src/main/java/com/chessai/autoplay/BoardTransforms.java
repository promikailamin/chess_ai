package com.chessai.autoplay;

import com.chessai.engine.Piece;
import com.chessai.engine.Position;

/** Converts between the visual screenshot grid and the real board orientation. */
public final class BoardTransforms {
    private BoardTransforms() {
    }

    /**
     * Applies an optional 180-degree board flip and stamps the side/fullmove
     * parsed from the on-screen move indicator.
     */
    public static Position actualPosition(Position visual, boolean flipped,
                                          int side, int fullmove) {
        int[] board = new int[64];
        for (int square = 0; square < 64; square++) {
            int target = flipped ? 63 - square : square;
            board[target] = visual.pieceAt(square);
        }
        int castling = flipped ? swapCastling(visual.getCastling()) : visual.getCastling();
        int actualSide = side == TurnText.NO_SIDE ? visual.getSide() : side;
        return fromBoard(board, actualSide, castling, Math.max(1, fullmove));
    }

    /** Square index as it appears in the recognised, white-at-bottom grid. */
    public static int visualSquare(int actualSquare, boolean flipped) {
        return flipped ? 63 - actualSquare : actualSquare;
    }

    public static int visualRow(int actualSquare, boolean flipped) {
        int visual = visualSquare(actualSquare, flipped);
        return 7 - (visual >>> 3);
    }

    public static int visualFile(int actualSquare, boolean flipped) {
        int visual = visualSquare(actualSquare, flipped);
        return visual & 7;
    }

    public static int swapCastling(int castling) {
        int result = 0;
        if ((castling & Position.CASTLE_WHITE_KING) != 0) {
            result |= Position.CASTLE_BLACK_KING;
        }
        if ((castling & Position.CASTLE_WHITE_QUEEN) != 0) {
            result |= Position.CASTLE_BLACK_QUEEN;
        }
        if ((castling & Position.CASTLE_BLACK_KING) != 0) {
            result |= Position.CASTLE_WHITE_KING;
        }
        if ((castling & Position.CASTLE_BLACK_QUEEN) != 0) {
            result |= Position.CASTLE_WHITE_QUEEN;
        }
        return result;
    }

    private static Position fromBoard(int[] board, int side, int castling, int fullmove) {
        StringBuilder fen = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int piece = board[rank * 8 + file];
                if (piece == Piece.EMPTY) {
                    empty++;
                } else {
                    if (empty > 0) {
                        fen.append(empty);
                        empty = 0;
                    }
                    fen.append(Piece.toChar(piece));
                }
            }
            if (empty > 0) {
                fen.append(empty);
            }
            if (rank > 0) {
                fen.append('/');
            }
        }
        fen.append(' ').append(side == Piece.WHITE ? 'w' : 'b').append(' ');
        if ((castling & Position.CASTLE_WHITE_KING) != 0) fen.append('K');
        if ((castling & Position.CASTLE_WHITE_QUEEN) != 0) fen.append('Q');
        if ((castling & Position.CASTLE_BLACK_KING) != 0) fen.append('k');
        if ((castling & Position.CASTLE_BLACK_QUEEN) != 0) fen.append('q');
        if (fen.charAt(fen.length() - 1) == ' ') {
            fen.append('-');
        }
        fen.append(" - 0 ").append(fullmove);
        return Position.fromFen(fen.toString());
    }
}
