package com.chessai.engine;

import java.util.Random;

/** Deterministic Zobrist keys. */
public final class Zobrist {
    public static final long[][] PIECE_SQUARE = new long[16][64];
    public static final long[] CASTLING = new long[16];
    public static final long[] EN_PASSANT_FILE = new long[8];
    public static final long SIDE_TO_MOVE;

    static {
        Random random = new Random(0x5EEDC0DEL);
        for (int piece = 0; piece < 16; piece++) {
            for (int square = 0; square < 64; square++) {
                PIECE_SQUARE[piece][square] = random.nextLong();
            }
        }
        for (int i = 0; i < CASTLING.length; i++) {
            CASTLING[i] = random.nextLong();
        }
        for (int i = 0; i < EN_PASSANT_FILE.length; i++) {
            EN_PASSANT_FILE[i] = random.nextLong();
        }
        SIDE_TO_MOVE = random.nextLong();
    }

    private Zobrist() {
    }
}
