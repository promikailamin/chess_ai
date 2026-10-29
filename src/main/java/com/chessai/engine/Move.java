package com.chessai.engine;

/** Compact chess move encoding. */
public final class Move {
    public static final int NONE = 0;

    public static final int FLAG_CAPTURE = 1 << 15;
    public static final int FLAG_EN_PASSANT = 1 << 16;
    public static final int FLAG_CASTLE = 1 << 17;
    public static final int FLAG_DOUBLE_PAWN = 1 << 18;

    private Move() {
    }

    public static int of(int from, int to) {
        return of(from, to, 0, 0);
    }

    public static int of(int from, int to, int promotionType, int flags) {
        return (from & 63) | ((to & 63) << 6) | ((promotionType & 7) << 12) | flags;
    }

    public static int from(int move) {
        return move & 63;
    }

    public static int to(int move) {
        return (move >>> 6) & 63;
    }

    public static int promotion(int move) {
        return (move >>> 12) & 7;
    }

    public static int flags(int move) {
        return move & (FLAG_CAPTURE | FLAG_EN_PASSANT | FLAG_CASTLE | FLAG_DOUBLE_PAWN);
    }

    public static boolean isCapture(int move) {
        return (move & FLAG_CAPTURE) != 0;
    }

    public static boolean isEnPassant(int move) {
        return (move & FLAG_EN_PASSANT) != 0;
    }

    public static boolean isCastle(int move) {
        return (move & FLAG_CASTLE) != 0;
    }

    public static boolean isPromotion(int move) {
        return promotion(move) != 0;
    }

    public static String squareName(int square) {
        int file = square & 7;
        int rank = square >>> 3;
        return String.valueOf((char) ('a' + file)) + (rank + 1);
    }

    public static String toUci(int move) {
        if (move == NONE) {
            return "0000";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(squareName(from(move))).append(squareName(to(move)));
        if (isPromotion(move)) {
            sb.append(Character.toLowerCase(Piece.toChar(promotion(move))));
        }
        return sb.toString();
    }

    public static int parseUci(String uci) {
        if (uci == null || uci.length() < 4) {
            return NONE;
        }
        int from = (uci.charAt(0) - 'a') + 8 * (uci.charAt(1) - '1');
        int to = (uci.charAt(2) - 'a') + 8 * (uci.charAt(3) - '1');
        int promo = 0;
        if (uci.length() >= 5) {
            promo = switch (Character.toLowerCase(uci.charAt(4))) {
                case 'n' -> Piece.KNIGHT;
                case 'b' -> Piece.BISHOP;
                case 'r' -> Piece.ROOK;
                case 'q' -> Piece.QUEEN;
                default -> 0;
            };
        }
        return of(from, to, promo, 0);
    }
}
