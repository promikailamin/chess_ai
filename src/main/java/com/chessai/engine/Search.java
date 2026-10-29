package com.chessai.engine;

import java.util.Random;

/**
 * Iterative-deepening alpha-beta search with quiescence, a transposition table,
 * null-move pruning, late-move reductions, killer moves and history ordering.
 */
public final class Search {
    public static final int INF = 1_000_000;
    public static final int MATE = 100_000;
    public static final int MATE_THRESHOLD = MATE - 1000;

    private static final int MAX_PLY = 96;

    private final TranspositionTable table;
    private final int[][] killers = new int[MAX_PLY][2];
    private final int[][] history = new int[16][64];
    private final Random random = new Random(0xC0FFEEL);

    private Position position;
    private long nodes;
    private long deadline;
    private boolean stopped;
    private int rootBestMove;
    private int rootBestScore;

    public Search(int tableMegabytes) {
        this.table = new TranspositionTable(tableMegabytes);
    }

    public void clear() {
        table.clear();
        for (int[] killer : killers) {
            killer[0] = 0;
            killer[1] = 0;
        }
        for (int[] row : history) {
            java.util.Arrays.fill(row, 0);
        }
    }

    public SearchResult search(Position position, int maxDepth, long timeMillis) {
        return search(position, maxDepth, timeMillis, false);
    }

    public SearchResult search(Position position, int maxDepth, long timeMillis, boolean randomOpening) {
        this.position = position;
        this.nodes = 0;
        this.stopped = false;
        long start = System.currentTimeMillis();
        this.deadline = start + Math.max(1, timeMillis);

        MoveList legal = new MoveList(256);
        position.generateLegalMoves(legal);
        if (legal.size() == 0) {
            return new SearchResult(Move.NONE, position.inCheck(position.getSide()) ? -MATE : 0,
                    0, 0, System.currentTimeMillis() - start);
        }

        int bestMove = legal.get(0);
        int bestScore = 0;
        int completedDepth = 0;
        int previousBest = bestMove;

        for (int depth = 1; depth <= Math.max(1, maxDepth); depth++) {
            rootBestMove = previousBest;
            rootBestScore = -INF;
            int alpha = -INF;
            int beta = INF;
            MoveList ordered = new MoveList(256);
            for (int i = 0; i < legal.size(); i++) {
                ordered.add(legal.get(i));
            }
            orderMoves(ordered, previousBest, 0, position);

            int localBest = 0;
            int localScore = -INF;
            int legalCount = 0;
            for (int i = 0; i < ordered.size(); i++) {
                int move = ordered.get(i);
                int us = position.getSide();
                Undo undo = position.makeMove(move);
                if (position.inCheck(us)) {
                    position.unmakeMove(move, undo);
                    continue;
                }
                legalCount++;
                int score;
                if (legalCount == 1) {
                    score = -negamax(depth - 1, -beta, -alpha, 1);
                } else {
                    score = -negamax(depth - 1, -alpha - 1, -alpha, 1);
                    if (!stopped && score > alpha) {
                        score = -negamax(depth - 1, -beta, -alpha, 1);
                    }
                }
                position.unmakeMove(move, undo);
                if (stopped) {
                    break;
                }
                if (score > localScore) {
                    localScore = score;
                    localBest = move;
                }
                if (score > alpha) {
                    alpha = score;
                }
                if (System.currentTimeMillis() >= deadline) {
                    stopped = true;
                    break;
                }
            }

            if (stopped) {
                break;
            }
            if (localBest != 0) {
                bestMove = localBest;
                bestScore = localScore;
                previousBest = localBest;
                completedDepth = depth;
                rootBestMove = localBest;
                rootBestScore = localScore;
            }
            if (Math.abs(bestScore) > MATE_THRESHOLD) {
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            if (randomOpening && position.getFullmoveNumber() <= 6 && legal.size() > 1) {
                bestMove = pickOpeningMove(legal, bestMove);
            }
        }

        return new SearchResult(bestMove, bestScore, completedDepth, nodes,
                System.currentTimeMillis() - start);
    }

    private int pickOpeningMove(MoveList legal, int bestMove) {
        if (random.nextInt(100) < 65) {
            return bestMove;
        }
        return legal.get(random.nextInt(legal.size()));
    }

    private int negamax(int depth, int alpha, int beta, int ply) {
        nodes++;
        if ((nodes & 2047) == 0 && System.currentTimeMillis() >= deadline) {
            stopped = true;
            return 0;
        }
        if (stopped) {
            return 0;
        }
        if (position.isFiftyMoveDraw() || position.isInsufficientMaterial()) {
            return 0;
        }
        if (ply >= MAX_PLY - 2) {
            return evaluateForSide();
        }

        int ttMove = 0;
        TranspositionTable.Entry entry = table.probe(position.getHash());
        if (entry != null) {
            ttMove = entry.move;
            if (entry.depth >= depth && ply > 0) {
                if (entry.flag == TranspositionTable.EXACT) {
                    return entry.score;
                }
                if (entry.flag == TranspositionTable.LOWER && entry.score >= beta) {
                    return entry.score;
                }
                if (entry.flag == TranspositionTable.UPPER && entry.score <= alpha) {
                    return entry.score;
                }
            }
        }

        boolean inCheck = position.inCheck(position.getSide());
        if (depth <= 0) {
            return quiescence(alpha, beta, ply);
        }

        if (!inCheck && depth >= 3 && position.hasNonPawnMaterial(position.getSide())) {
            Undo undo = position.makeNullMove();
            int score = -negamax(depth - 1 - 2, -beta, -beta + 1, ply + 1);
            position.unmakeNullMove(undo);
            if (stopped) {
                return 0;
            }
            if (score >= beta) {
                return beta;
            }
        }

        MoveList moves = new MoveList(256);
        position.generateMoves(moves);
        orderMoves(moves, ttMove, ply, position);

        int originalAlpha = alpha;
        int bestScore = -MATE + ply;
        int bestMove = 0;
        int legalMoves = 0;

        for (int i = 0; i < moves.size(); i++) {
            int move = moves.get(i);
            int us = position.getSide();
            boolean capture = Move.isCapture(move);
            Undo undo = position.makeMove(move);
            if (position.inCheck(us)) {
                position.unmakeMove(move, undo);
                continue;
            }
            legalMoves++;
            int score;
            if (legalMoves == 1) {
                score = -negamax(depth - 1, -beta, -alpha, ply + 1);
            } else {
                int reduction = 0;
                if (depth >= 3 && legalMoves > 3 && !capture && !inCheck
                        && !Move.isPromotion(move)) {
                    reduction = 1;
                }
                score = -negamax(depth - 1 - reduction, -alpha - 1, -alpha, ply + 1);
                if (!stopped && score > alpha && reduction > 0) {
                    score = -negamax(depth - 1, -alpha - 1, -alpha, ply + 1);
                }
                if (!stopped && score > alpha && score < beta) {
                    score = -negamax(depth - 1, -beta, -alpha, ply + 1);
                }
            }
            position.unmakeMove(move, undo);
            if (stopped) {
                return 0;
            }

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
            if (score > alpha) {
                alpha = score;
            }
            if (alpha >= beta) {
                if (!capture) {
                    killers[ply][1] = killers[ply][0];
                    killers[ply][0] = move;
                    history[pieceAtMoveFrom(move)][Move.to(move)] += depth * depth;
                }
                break;
            }
        }

        if (legalMoves == 0) {
            return inCheck ? -MATE + ply : 0;
        }

        int flag = bestScore <= originalAlpha ? TranspositionTable.UPPER
                : bestScore >= beta ? TranspositionTable.LOWER
                : TranspositionTable.EXACT;
        table.store(position.getHash(), depth, bestScore, flag, bestMove);
        return bestScore;
    }

    private int quiescence(int alpha, int beta, int ply) {
        nodes++;
        if ((nodes & 2047) == 0 && System.currentTimeMillis() >= deadline) {
            stopped = true;
            return 0;
        }
        if (stopped) {
            return 0;
        }
        if (ply >= MAX_PLY - 2) {
            return evaluateForSide();
        }
        int standPat = evaluateForSide();
        if (standPat >= beta) {
            return beta;
        }
        if (standPat > alpha) {
            alpha = standPat;
        }

        MoveList moves = new MoveList(128);
        position.generateMoves(moves);
        int[] scores = new int[moves.size()];
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.get(i);
            scores[i] = Move.isCapture(move) || Move.isPromotion(move)
                    ? 100000 + captureScore(move, position)
                    : -1;
        }
        for (int i = 0; i < moves.size(); i++) {
            int best = i;
            for (int j = i + 1; j < moves.size(); j++) {
                if (scores[j] > scores[best]) {
                    best = j;
                }
            }
            if (best != i) {
                moves.swap(i, best);
                int tmp = scores[i];
                scores[i] = scores[best];
                scores[best] = tmp;
            }
            int move = moves.get(i);
            if (scores[i] < 0) {
                break;
            }
            int us = position.getSide();
            Undo undo = position.makeMove(move);
            if (position.inCheck(us)) {
                position.unmakeMove(move, undo);
                continue;
            }
            int score = -quiescence(-beta, -alpha, ply + 1);
            position.unmakeMove(move, undo);
            if (stopped) {
                return 0;
            }
            if (score >= beta) {
                return beta;
            }
            if (score > alpha) {
                alpha = score;
            }
        }
        return alpha;
    }

    private int evaluateForSide() {
        int whiteScore = Evaluation.evaluate(position);
        return position.getSide() == Piece.WHITE ? whiteScore : -whiteScore;
    }

    private void orderMoves(MoveList moves, int ttMove, int ply, Position position) {
        int[] scores = new int[moves.size()];
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.get(i);
            int score;
            if (move == ttMove) {
                score = 2_000_000;
            } else if (Move.isPromotion(move)) {
                score = 1_500_000 + Move.promotion(move) * 100;
            } else if (Move.isCapture(move)) {
                score = 1_000_000 + captureScore(move, position);
            } else if (move == killers[ply][0]) {
                score = 900_000;
            } else if (move == killers[ply][1]) {
                score = 800_000;
            } else {
                score = history[pieceAtMoveFrom(move)][Move.to(move)];
            }
            scores[i] = score;
        }
        for (int i = 0; i < moves.size(); i++) {
            int best = i;
            for (int j = i + 1; j < moves.size(); j++) {
                if (scores[j] > scores[best]) {
                    best = j;
                }
            }
            if (best != i) {
                moves.swap(i, best);
                int tmp = scores[i];
                scores[i] = scores[best];
                scores[best] = tmp;
            }
        }
    }

    private int pieceAtMoveFrom(int move) {
        int piece = position.pieceAt(Move.from(move));
        return piece == Piece.EMPTY ? 0 : piece;
    }

    private int captureScore(int move, Position position) {
        int attacker = position.pieceAt(Move.from(move));
        int victim;
        if (Move.isEnPassant(move)) {
            victim = Piece.make(position.getSide() ^ 8, Piece.PAWN);
        } else {
            victim = position.pieceAt(Move.to(move));
        }
        int attackerType = attacker == Piece.EMPTY ? Piece.PAWN : Piece.type(attacker);
        int victimType = victim == Piece.EMPTY ? Piece.PAWN : Piece.type(victim);
        return victimType * 100 - attackerType;
    }
}
