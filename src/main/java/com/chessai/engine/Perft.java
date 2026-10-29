package com.chessai.engine;

/** Move-generator correctness counter. */
public final class Perft {
    private Perft() {
    }

    public static long count(Position position, int depth) {
        if (depth == 0) {
            return 1;
        }
        MoveList moves = new MoveList(256);
        position.generateLegalMoves(moves);
        if (depth == 1) {
            return moves.size();
        }
        long nodes = 0;
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.get(i);
            Undo undo = position.makeMove(move);
            nodes += count(position, depth - 1);
            position.unmakeMove(move, undo);
        }
        return nodes;
    }

    public static void divide(Position position, int depth) {
        MoveList moves = new MoveList(256);
        position.generateLegalMoves(moves);
        long total = 0;
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.get(i);
            Undo undo = position.makeMove(move);
            long nodes = count(position, depth - 1);
            position.unmakeMove(move, undo);
            total += nodes;
            System.out.printf("%s %d%n", Move.toUci(move), nodes);
        }
        System.out.println("total " + total);
    }
}
