package com.chessai.autoplay;

import com.chessai.engine.Move;
import com.chessai.engine.Piece;
import com.chessai.engine.Position;
import com.chessai.engine.Search;
import com.chessai.engine.SearchResult;

/**
 * Decides when the bot should move.  The session deliberately acts only when
 * the on-screen move indicator says it is the bot's turn, requires the same
 * board to be seen twice, and never repeats a move for an unchanged board.
 */
public final class AutoPlaySession {
    public enum Kind {
        WAIT,
        PLAY,
        GAME_OVER,
        INVALID
    }

    public static final class Decision {
        public final Kind kind;
        public final int move;
        public final Position position;
        public final String message;

        private Decision(Kind kind, int move, Position position, String message) {
            this.kind = kind;
            this.move = move;
            this.position = position;
            this.message = message;
        }

        public static Decision wait(String message) {
            return new Decision(Kind.WAIT, Move.NONE, null, message);
        }

        public static Decision play(int move, Position position) {
            return new Decision(Kind.PLAY, move, position, "Play " + Move.toUci(move));
        }

        public static Decision gameOver(String message) {
            return new Decision(Kind.GAME_OVER, Move.NONE, null, message);
        }

        public static Decision invalid(String message) {
            return new Decision(Kind.INVALID, Move.NONE, null, message);
        }
    }

    private static final int RECENT_LIMIT = 8;

    private final int botColor;
    private final Search search;
    private final int maxDepth;
    private final long timeMillis;
    private final int minStableObservations;
    private final long retryCooldownMillis;

    private final int[] recentHashes = new int[RECENT_LIMIT];
    private int recentCount;
    private int turnObservations;
    private int candidateHash;
    private int stableCount;
    private int lastActedHash;
    private long lastActedAt;
    private int noMoveHash;
    private int noMoveCount;
    private int lastMove;
    private boolean finished;
    private String finishReason;

    public AutoPlaySession(int botColor, int tableMegabytes, int maxDepth, long timeMillis) {
        this(botColor, tableMegabytes, maxDepth, timeMillis, 2, 4500);
    }

    public AutoPlaySession(int botColor, int tableMegabytes, int maxDepth, long timeMillis,
                           int minStableObservations, long retryCooldownMillis) {
        this.botColor = botColor;
        this.search = new Search(tableMegabytes);
        this.maxDepth = Math.max(1, maxDepth);
        this.timeMillis = Math.max(1, timeMillis);
        this.minStableObservations = Math.max(1, minStableObservations);
        this.retryCooldownMillis = Math.max(0, retryCooldownMillis);
    }

    public int getBotColor() {
        return botColor;
    }

    public int getLastMove() {
        return lastMove;
    }

    public boolean isFinished() {
        return finished;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public Decision decide(TurnText.TurnInfo turn, Position visualPosition, boolean flipped) {
        if (finished) {
            return Decision.gameOver(finishReason);
        }
        if (turn == null) {
            return Decision.wait("Waiting for move text");
        }
        if (turn.isGameOver()) {
            return finish("Game over: " + turn.result);
        }

        Position actual;
        try {
            actual = BoardTransforms.actualPosition(visualPosition, flipped,
                    turn.side, turn.fullmove);
        } catch (RuntimeException ex) {
            resetObservations();
            return Decision.invalid("Could not rebuild board: " + ex.getMessage());
        }

        String invalid = validate(actual);
        if (invalid != null) {
            resetObservations();
            return Decision.invalid(invalid);
        }
        if (turn.side == TurnText.NO_SIDE) {
            return Decision.wait("Waiting for move text");
        }

        if (turn.side != botColor) {
            resetObservations();
            return Decision.wait("Opponent's turn");
        }

        int hash = (int) actual.getHash();
        turnObservations++;
        remember(hash);
        if (hash == candidateHash) {
            stableCount++;
        } else {
            candidateHash = hash;
            stableCount = 1;
        }

        boolean stable = stableCount >= minStableObservations;
        if (!stable
                && turnObservations >= Math.max(3, minStableObservations + 1)
                && occurrences(hash) >= minStableObservations) {
            stable = true;
        }
        if (!stable) {
            return Decision.wait("Checking board");
        }

        long now = System.currentTimeMillis();
        if (hash == lastActedHash && now - lastActedAt < retryCooldownMillis) {
            return Decision.wait("Waiting for the app to accept "
                    + Move.toUci(lastMove));
        }

        SearchResult result;
        try {
            result = search.search(actual, maxDepth, timeMillis);
        } catch (RuntimeException ex) {
            return Decision.invalid("Engine error: " + ex.getMessage());
        }

        if (result.move == Move.NONE) {
            if (hash == noMoveHash) {
                noMoveCount++;
            } else {
                noMoveHash = hash;
                noMoveCount = 1;
            }
            if (noMoveCount < 2) {
                return Decision.wait("No legal move - checking again");
            }
            String reason = actual.inCheck(actual.getSide())
                    ? (actual.getSide() == botColor ? "Checkmate - bot lost" : "Checkmate - bot won")
                    : "Stalemate or draw";
            return finish(reason);
        }
        noMoveHash = 0;
        noMoveCount = 0;
        if (!actual.isLegal(result.move)) {
            return Decision.invalid("Engine returned an illegal move");
        }

        lastActedHash = hash;
        lastActedAt = now;
        lastMove = result.move;
        resetObservations();
        return Decision.play(result.move, actual);
    }

    private void remember(int hash) {
        if (recentCount < RECENT_LIMIT) {
            recentHashes[recentCount++] = hash;
            return;
        }
        System.arraycopy(recentHashes, 1, recentHashes, 0, RECENT_LIMIT - 1);
        recentHashes[RECENT_LIMIT - 1] = hash;
    }

    private int occurrences(int hash) {
        int count = 0;
        for (int i = 0; i < recentCount; i++) {
            if (recentHashes[i] == hash) {
                count++;
            }
        }
        return count;
    }

    private void resetObservations() {
        candidateHash = 0;
        stableCount = 0;
        recentCount = 0;
        turnObservations = 0;
        noMoveHash = 0;
        noMoveCount = 0;
    }

    private Decision finish(String reason) {
        finished = true;
        finishReason = reason;
        return Decision.gameOver(reason);
    }

    private String validate(Position position) {
        int whiteKings = 0;
        int blackKings = 0;
        for (int square = 0; square < 64; square++) {
            int piece = position.pieceAt(square);
            if (piece == Piece.EMPTY) {
                continue;
            }
            if (Piece.type(piece) == Piece.KING) {
                if (Piece.isWhite(piece)) whiteKings++; else blackKings++;
            }
            if (Piece.type(piece) == Piece.PAWN && (square < 8 || square >= 56)) {
                return "Invalid pawn placement - recognition needs another try";
            }
        }
        if (whiteKings != 1 || blackKings != 1) {
            return "Board recognition did not find both kings";
        }
        return null;
    }
}
