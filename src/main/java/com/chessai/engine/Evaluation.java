package com.chessai.engine;

/** Material, piece-square tables and a handful of positional terms. */
public final class Evaluation {
    private static final int[] MATERIAL = {0, 100, 320, 330, 500, 900, 0};

    private static final int[] PAWN_MID = {
            0, 0, 0, 0, 0, 0, 0, 0,
            5, 10, 10, -20, -20, 10, 10, 5,
            5, -5, -10, 0, 0, -10, -5, 5,
            0, 0, 0, 20, 20, 0, 0, 0,
            5, 5, 10, 25, 25, 10, 5, 5,
            10, 10, 20, 30, 30, 20, 10, 10,
            50, 50, 50, 50, 50, 50, 50, 50,
            0, 0, 0, 0, 0, 0, 0, 0
    };

    private static final int[] PAWN_END = {
            0, 0, 0, 0, 0, 0, 0, 0,
            10, 10, 10, 10, 10, 10, 10, 10,
            10, 10, 10, 10, 10, 10, 10, 10,
            20, 20, 20, 20, 20, 20, 20, 20,
            30, 30, 30, 30, 30, 30, 30, 30,
            50, 50, 50, 50, 50, 50, 50, 50,
            80, 80, 80, 80, 80, 80, 80, 80,
            0, 0, 0, 0, 0, 0, 0, 0
    };

    private static final int[] KNIGHT = {
            -50, -40, -30, -30, -30, -30, -40, -50,
            -40, -20, 0, 5, 5, 0, -20, -40,
            -30, 5, 10, 15, 15, 10, 5, -30,
            -30, 0, 15, 20, 20, 15, 0, -30,
            -30, 5, 15, 20, 20, 15, 5, -30,
            -30, 0, 10, 15, 15, 10, 0, -30,
            -40, -20, 0, 0, 0, 0, -20, -40,
            -50, -40, -30, -30, -30, -30, -40, -50
    };

    private static final int[] BISHOP = {
            -20, -10, -10, -10, -10, -10, -10, -20,
            -10, 5, 0, 0, 0, 0, 5, -10,
            -10, 10, 10, 10, 10, 10, 10, -10,
            -10, 0, 10, 10, 10, 10, 0, -10,
            -10, 5, 5, 10, 10, 5, 5, -10,
            -10, 0, 5, 10, 10, 5, 0, -10,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -20, -10, -10, -10, -10, -10, -10, -20
    };

    private static final int[] ROOK = {
            0, 0, 0, 5, 5, 0, 0, 0,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            5, 10, 10, 10, 10, 10, 10, 5,
            0, 0, 0, 0, 0, 0, 0, 0
    };

    private static final int[] QUEEN = {
            -20, -10, -10, -5, -5, -10, -10, -20,
            -10, 0, 5, 0, 0, 0, 0, -10,
            -10, 5, 5, 5, 5, 5, 0, -10,
            0, 0, 5, 5, 5, 5, 0, -5,
            -5, 0, 5, 5, 5, 5, 0, -5,
            -10, 0, 5, 5, 5, 5, 0, -10,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -20, -10, -10, -5, -5, -10, -10, -20
    };

    private static final int[] KING_MID = {
            20, 30, 10, 0, 0, 10, 30, 20,
            20, 20, 0, 0, 0, 0, 20, 20,
            -10, -20, -20, -20, -20, -20, -20, -10,
            -20, -30, -30, -40, -40, -30, -30, -20,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30
    };

    private static final int[] KING_END = {
            -50, -30, -30, -30, -30, -30, -30, -50,
            -30, -30, 0, 0, 0, 0, -30, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -20, -10, 0, 0, -10, -20, -30,
            -50, -40, -30, -20, -20, -30, -40, -50
    };

    private Evaluation() {
    }

    /** Score from White's point of view, in centipawns. */
    public static int evaluate(Position position) {
        int score = 0;
        int nonPawnMaterial = 0;
        int[] pawns = new int[2];
        int[] bishops = new int[2];
        int[] pawnFiles = new int[2 * 8];

        for (int square = 0; square < 64; square++) {
            int piece = position.pieceAt(square);
            if (piece == Piece.EMPTY) {
                continue;
            }
            int color = Piece.color(piece);
            int type = Piece.type(piece);
            int sign = color == Piece.WHITE ? 1 : -1;
            int index = color == Piece.WHITE ? square : square ^ 56;
            score += sign * MATERIAL[type];
            if (type != Piece.PAWN && type != Piece.KING) {
                nonPawnMaterial += MATERIAL[type];
            }
            if (type == Piece.PAWN) {
                pawns[color == Piece.WHITE ? 0 : 1]++;
                pawnFiles[(color == Piece.WHITE ? 0 : 8) + (square & 7)]++;
            } else if (type == Piece.BISHOP) {
                bishops[color == Piece.WHITE ? 0 : 1]++;
            }
        }

        double phase = Math.min(1.0, nonPawnMaterial / 6400.0);

        for (int square = 0; square < 64; square++) {
            int piece = position.pieceAt(square);
            if (piece == Piece.EMPTY) {
                continue;
            }
            int color = Piece.color(piece);
            int type = Piece.type(piece);
            int sign = color == Piece.WHITE ? 1 : -1;
            int index = color == Piece.WHITE ? square : square ^ 56;
            int value = switch (type) {
                case Piece.PAWN -> (int) (PAWN_MID[index] * phase + PAWN_END[index] * (1 - phase));
                case Piece.KNIGHT -> KNIGHT[index];
                case Piece.BISHOP -> BISHOP[index];
                case Piece.ROOK -> ROOK[index];
                case Piece.QUEEN -> QUEEN[index];
                case Piece.KING -> (int) (KING_MID[index] * phase + KING_END[index] * (1 - phase));
                default -> 0;
            };
            score += sign * value;
        }

        for (int color = 0; color < 2; color++) {
            int white = color == 0 ? 1 : -1;
            int base = color * 8;
            if (bishops[color] >= 2) {
                score += white * 30;
            }
            for (int file = 0; file < 8; file++) {
                int count = pawnFiles[base + file];
                if (count > 1) {
                    score -= white * 12 * (count - 1);
                }
                if (count == 0) {
                    continue;
                }
                boolean isolated = (file == 0 || pawnFiles[base + file - 1] == 0)
                        && (file == 7 || pawnFiles[base + file + 1] == 0);
                if (isolated) {
                    score -= white * 15 * count;
                }
            }
        }

        score += passedPawns(position);
        score += rookFiles(position);
        score += kingShield(position);
        score += position.getSide() == Piece.WHITE ? 10 : -10;
        return score;
    }

    private static int passedPawns(Position position) {
        int score = 0;
        for (int square = 0; square < 64; square++) {
            int piece = position.pieceAt(square);
            if (Piece.type(piece) != Piece.PAWN) {
                continue;
            }
            int color = Piece.color(piece);
            int file = square & 7;
            int rank = square >>> 3;
            boolean passed = true;
            for (int df = -1; df <= 1 && passed; df++) {
                int f = file + df;
                if (f < 0 || f > 7) {
                    continue;
                }
                for (int r = rank + (color == Piece.WHITE ? 1 : -1);
                     r >= 0 && r < 8; r += color == Piece.WHITE ? 1 : -1) {
                    int other = position.pieceAt(r * 8 + f);
                    if (other != Piece.EMPTY && Piece.type(other) == Piece.PAWN
                            && Piece.color(other) != color) {
                        passed = false;
                        break;
                    }
                }
            }
            if (passed) {
                int advance = color == Piece.WHITE ? rank : 7 - rank;
                score += (color == Piece.WHITE ? 1 : -1) * (10 + advance * advance * 5);
            }
        }
        return score;
    }

    private static int rookFiles(Position position) {
        int score = 0;
        for (int square = 0; square < 64; square++) {
            int piece = position.pieceAt(square);
            if (Piece.type(piece) != Piece.ROOK) {
                continue;
            }
            int color = Piece.color(piece);
            int file = square & 7;
            boolean friendlyPawn = false;
            boolean enemyPawn = false;
            for (int rank = 0; rank < 8; rank++) {
                int other = position.pieceAt(rank * 8 + file);
                if (Piece.type(other) == Piece.PAWN) {
                    if (Piece.color(other) == color) {
                        friendlyPawn = true;
                    } else {
                        enemyPawn = true;
                    }
                }
            }
            int bonus = friendlyPawn ? 0 : (enemyPawn ? 8 : 15);
            score += (color == Piece.WHITE ? 1 : -1) * bonus;
        }
        return score;
    }

    private static int kingShield(Position position) {
        int score = 0;
        for (int color = 0; color < 2; color++) {
            int kingSquare = position.kingSquare(color);
            if (kingSquare < 0) {
                continue;
            }
            int file = kingSquare & 7;
            int rank = kingSquare >>> 3;
            int direction = color == 0 ? 1 : -1;
            int shield = 0;
            for (int df = -1; df <= 1; df++) {
                int f = file + df;
                if (f < 0 || f > 7) {
                    continue;
                }
                int r = rank + direction;
                if (r >= 0 && r < 8) {
                    int piece = position.pieceAt(r * 8 + f);
                    if (Piece.type(piece) == Piece.PAWN && Piece.color(piece) == color) {
                        shield++;
                    }
                }
            }
            score += (color == 0 ? 1 : -1) * shield * 8;
        }
        return score;
    }
}
