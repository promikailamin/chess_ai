package com.chessai.engine;

/** Result of an engine search. */
public final class SearchResult {
    public final int move;
    public final int score;
    public final int depth;
    public final long nodes;
    public final long timeMillis;

    public SearchResult(int move, int score, int depth, long nodes, long timeMillis) {
        this.move = move;
        this.score = score;
        this.depth = depth;
        this.nodes = nodes;
        this.timeMillis = timeMillis;
    }

    public String moveUci() {
        return Move.toUci(move);
    }
}
