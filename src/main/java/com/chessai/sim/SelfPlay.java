package com.chessai.sim;

import com.chessai.engine.Move;
import com.chessai.engine.MoveList;
import com.chessai.engine.Piece;
import com.chessai.engine.Position;
import com.chessai.engine.Search;
import com.chessai.engine.SearchResult;

import java.util.HashMap;
import java.util.Map;

/** Headless engine-vs-engine simulation used for regression and tuning. */
public final class SelfPlay {
    public enum Outcome {
        WHITE_WIN, BLACK_WIN, DRAW
    }

    public static final class Stats {
        public int games;
        public int whiteWins;
        public int blackWins;
        public int draws;
        public long totalPlies;
        public long totalNodes;
        public long elapsedMillis;

        public void print() {
            System.out.println();
            System.out.println("========== self-play summary ==========");
            System.out.printf("games        : %d%n", games);
            System.out.printf("white wins   : %d (%.1f%%)%n", whiteWins,
                    games == 0 ? 0 : 100.0 * whiteWins / games);
            System.out.printf("black wins   : %d (%.1f%%)%n", blackWins,
                    games == 0 ? 0 : 100.0 * blackWins / games);
            System.out.printf("draws        : %d (%.1f%%)%n", draws,
                    games == 0 ? 0 : 100.0 * draws / games);
            System.out.printf("avg plies    : %.1f%n", games == 0 ? 0 : (double) totalPlies / games);
            System.out.printf("total nodes  : %d%n", totalNodes);
            System.out.printf("nodes/second : %d%n",
                    elapsedMillis == 0 ? totalNodes : totalNodes * 1000 / elapsedMillis);
            System.out.printf("elapsed      : %.2f s%n", elapsedMillis / 1000.0);
        }
    }

    private SelfPlay() {
    }

    public static Stats run(int games, int depth, long timeMillis, int maxPlies,
                            boolean randomOpenings, boolean verbose) {
        Stats stats = new Stats();
        long start = System.currentTimeMillis();
        Search engine = new Search(32);
        for (int game = 0; game < games; game++) {
            Position position = Position.startPosition();
            Map<Long, Integer> repetitions = new HashMap<>();
            repetitions.put(position.getHash(), 1);
            Outcome outcome = null;
            int ply = 0;
            for (; ply < maxPlies; ply++) {
                if (position.isFiftyMoveDraw() || position.isInsufficientMaterial()) {
                    outcome = Outcome.DRAW;
                    break;
                }
                MoveList legal = new MoveList(256);
                position.generateLegalMoves(legal);
                if (legal.size() == 0) {
                    if (position.inCheck(position.getSide())) {
                        outcome = position.getSide() == Piece.WHITE ? Outcome.BLACK_WIN : Outcome.WHITE_WIN;
                    } else {
                        outcome = Outcome.DRAW;
                    }
                    break;
                }
                SearchResult result = engine.search(position, depth, timeMillis,
                        randomOpenings && ply < 10);
                stats.totalNodes += result.nodes;
                if (result.move == Move.NONE) {
                    outcome = Outcome.DRAW;
                    break;
                }
                position.makeMove(result.move);
                int count = repetitions.merge(position.getHash(), 1, Integer::sum);
                if (count >= 3) {
                    outcome = Outcome.DRAW;
                    break;
                }
            }
            if (outcome == null) {
                outcome = Outcome.DRAW;
            }
            stats.games++;
            stats.totalPlies += ply;
            switch (outcome) {
                case WHITE_WIN -> stats.whiteWins++;
                case BLACK_WIN -> stats.blackWins++;
                case DRAW -> stats.draws++;
            }
            if (verbose && (game + 1) % 25 == 0) {
                System.out.printf("game %d/%d  W %d  B %d  D %d  avg %.1f plies  %.1f s%n",
                        game + 1, games, stats.whiteWins, stats.blackWins, stats.draws,
                        (double) stats.totalPlies / stats.games,
                        (System.currentTimeMillis() - start) / 1000.0);
            }
        }
        stats.elapsedMillis = System.currentTimeMillis() - start;
        return stats;
    }
}
