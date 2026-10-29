package com.chessai.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerftTest {
    @Test
    void startPositionCounts() {
        assertEquals(20, Perft.count(Position.startPosition(), 1));
        assertEquals(400, Perft.count(Position.startPosition(), 2));
        assertEquals(8902, Perft.count(Position.startPosition(), 3));
        assertEquals(197281, Perft.count(Position.startPosition(), 4));
    }

    @Test
    void kiwipeteCounts() {
        Position position = Position.fromFen(
                "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        assertEquals(48, Perft.count(position, 1));
        assertEquals(2039, Perft.count(position, 2));
        assertEquals(97862, Perft.count(position, 3));
    }

    @Test
    void enPassantAndPromotionPosition() {
        Position position = Position.fromFen("8/P1k5/8/8/8/8/5K2/8 w - - 0 1");
        assertEquals(12, Perft.count(position, 1));
    }
}
