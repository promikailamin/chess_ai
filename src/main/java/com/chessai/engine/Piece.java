package com.chessai.engine;

/** Piece encoding used across the engine. */
public final class Piece {
    public static final int WHITE = 0;
    public static final int BLACK = 8;

    public static final int EMPTY = 0;
    public static final int PAWN = 1;
    public static final int KNIGHT = 2;
    public static final int BISHOP = 3;
    public static final int ROOK = 4;
    public static final int QUEEN = 5;
    public static final int KING = 6;

    private Piece() {
    }

    public static int make(int color, int type) {
        return color | type;
    }

    public static int type(int piece) {
        return piece & 7;
    }

    public static int color(int piece) {
        return piece & 8;
    }

    public static boolean isEmpty(int piece) {
        return piece == EMPTY;
    }

    public static boolean isWhite(int piece) {
        return piece != EMPTY && (piece & 8) == WHITE;
    }

    public static boolean isBlack(int piece) {
        return piece != EMPTY && (piece & 8) == BLACK;
    }

    public static char toChar(int piece) {
        if (piece == EMPTY) {
            return '.';
        }
        char c;
        switch (type(piece)) {
            case PAWN -> c = 'p';
            case KNIGHT -> c = 'n';
            case BISHOP -> c = 'b';
            case ROOK -> c = 'r';
            case QUEEN -> c = 'q';
            case KING -> c = 'k';
            default -> c = '?';
        }
        return isWhite(piece) ? Character.toUpperCase(c) : c;
    }

    public static int fromChar(char c) {
        int color = Character.isUpperCase(c) ? WHITE : BLACK;
        return switch (Character.toLowerCase(c)) {
            case 'p' -> make(color, PAWN);
            case 'n' -> make(color, KNIGHT);
            case 'b' -> make(color, BISHOP);
            case 'r' -> make(color, ROOK);
            case 'q' -> make(color, QUEEN);
            case 'k' -> make(color, KING);
            default -> EMPTY;
        };
    }

    public static String name(int type) {
        return switch (type) {
            case PAWN -> "pawn";
            case KNIGHT -> "knight";
            case BISHOP -> "bishop";
            case ROOK -> "rook";
            case QUEEN -> "queen";
            case KING -> "king";
            default -> "empty";
        };
    }
}
