package com.chessai.engine;

import java.util.StringJoiner;

/**
 * A complete, mutable chess position with make/unmake support.
 *
 * <p>Squares are numbered a1=0, b1=1, ... h8=63.  Pieces are encoded with
 * {@link Piece}: white 1..6 and black 9..14.</p>
 */
public final class Position {
    public static final int WHITE = Piece.WHITE;
    public static final int BLACK = Piece.BLACK;

    public static final int CASTLE_WHITE_KING = 1;
    public static final int CASTLE_WHITE_QUEEN = 2;
    public static final int CASTLE_BLACK_KING = 4;
    public static final int CASTLE_BLACK_QUEEN = 8;

    private static final int[] KNIGHT_OFFSETS = {17, 15, 10, 6, -6, -10, -15, -17};
    private static final int[] KING_OFFSETS = {1, -1, 8, -8, 9, 7, -7, -9};
    private static final int[] BISHOP_OFFSETS = {9, 7, -7, -9};
    private static final int[] ROOK_OFFSETS = {1, -1, 8, -8};
    private static final int[] QUEEN_OFFSETS = {1, -1, 8, -8, 9, 7, -7, -9};

    private static final int[] CASTLE_MASK = new int[64];

    static {
        CASTLE_MASK[4] = CASTLE_WHITE_KING | CASTLE_WHITE_QUEEN;
        CASTLE_MASK[60] = CASTLE_BLACK_KING | CASTLE_BLACK_QUEEN;
        CASTLE_MASK[7] = CASTLE_WHITE_KING;
        CASTLE_MASK[0] = CASTLE_WHITE_QUEEN;
        CASTLE_MASK[63] = CASTLE_BLACK_KING;
        CASTLE_MASK[56] = CASTLE_BLACK_QUEEN;
    }

    private final int[] board = new int[64];
    private int side;
    private int castling;
    private int enPassant = -1;
    private int halfmoveClock;
    private int fullmoveNumber = 1;
    private long hash;

    public Position() {
        setStartPosition();
    }

    public Position(Position other) {
        System.arraycopy(other.board, 0, board, 0, 64);
        side = other.side;
        castling = other.castling;
        enPassant = other.enPassant;
        halfmoveClock = other.halfmoveClock;
        fullmoveNumber = other.fullmoveNumber;
        hash = other.hash;
    }

    public static Position startPosition() {
        return new Position();
    }

    public void setStartPosition() {
        clear();
        String[] back = {"rnbqkbnr", "pppppppp", "........", "........",
                "........", "........", "PPPPPPPP", "RNBQKBNR"};
        for (int row = 0; row < 8; row++) {
            int rank = 7 - row;
            for (int file = 0; file < 8; file++) {
                char c = back[row].charAt(file);
                if (c != '.') {
                    board[rank * 8 + file] = Piece.fromChar(c);
                }
            }
        }
        side = WHITE;
        castling = 15;
        enPassant = -1;
        halfmoveClock = 0;
        fullmoveNumber = 1;
        hash = computeHash();
    }

    public void clear() {
        java.util.Arrays.fill(board, Piece.EMPTY);
        side = WHITE;
        castling = 0;
        enPassant = -1;
        halfmoveClock = 0;
        fullmoveNumber = 1;
        hash = 0;
    }

    public int pieceAt(int square) {
        return board[square];
    }

    public void setPiece(int square, int piece) {
        board[square] = piece;
        hash = computeHash();
    }

    public int getSide() {
        return side;
    }

    public void setSide(int side) {
        this.side = side;
        hash = computeHash();
    }

    public int getCastling() {
        return castling;
    }

    public void setCastling(int castling) {
        this.castling = castling & 15;
    }

    public int getEnPassant() {
        return enPassant;
    }

    public int getHalfmoveClock() {
        return halfmoveClock;
    }

    public int getFullmoveNumber() {
        return fullmoveNumber;
    }

    public long getHash() {
        return hash;
    }

    public int[] boardCopy() {
        return board.clone();
    }

    public long computeHash() {
        long h = 0;
        for (int square = 0; square < 64; square++) {
            int piece = board[square];
            if (piece != Piece.EMPTY) {
                h ^= Zobrist.PIECE_SQUARE[piece][square];
            }
        }
        if (side == BLACK) {
            h ^= Zobrist.SIDE_TO_MOVE;
        }
        h ^= Zobrist.CASTLING[castling];
        if (enPassant >= 0) {
            h ^= Zobrist.EN_PASSANT_FILE[enPassant & 7];
        }
        return h;
    }

    public int kingSquare(int color) {
        int king = Piece.make(color, Piece.KING);
        for (int i = 0; i < 64; i++) {
            if (board[i] == king) {
                return i;
            }
        }
        return -1;
    }

    public boolean inCheck(int color) {
        int king = kingSquare(color);
        return king >= 0 && isSquareAttacked(king, color ^ 8);
    }

    public boolean isSquareAttacked(int square, int byColor) {
        int file = square & 7;
        int rank = square >>> 3;

        if (byColor == WHITE) {
            if (file > 0 && square - 9 >= 0 && board[square - 9] == Piece.make(WHITE, Piece.PAWN)) {
                return true;
            }
            if (file < 7 && square - 7 >= 0 && board[square - 7] == Piece.make(WHITE, Piece.PAWN)) {
                return true;
            }
        } else {
            if (file > 0 && square + 7 < 64 && board[square + 7] == Piece.make(BLACK, Piece.PAWN)) {
                return true;
            }
            if (file < 7 && square + 9 < 64 && board[square + 9] == Piece.make(BLACK, Piece.PAWN)) {
                return true;
            }
        }

        for (int offset : KNIGHT_OFFSETS) {
            int target = square + offset;
            if (target >= 0 && target < 64 && Math.abs((target & 7) - file) <= 2
                    && Math.abs((target >>> 3) - rank) <= 2
                    && board[target] == Piece.make(byColor, Piece.KNIGHT)) {
                return true;
            }
        }

        for (int offset : KING_OFFSETS) {
            int target = square + offset;
            if (target >= 0 && target < 64 && Math.abs((target & 7) - file) <= 1
                    && Math.abs((target >>> 3) - rank) <= 1
                    && board[target] == Piece.make(byColor, Piece.KING)) {
                return true;
            }
        }

        for (int offset : BISHOP_OFFSETS) {
            int target = square + offset;
            while (target >= 0 && target < 64 && Math.abs((target & 7) - file) <= 1
                    && Math.abs((target >>> 3) - rank) <= 1) {
                int piece = board[target];
                if (piece != Piece.EMPTY) {
                    if (Piece.color(piece) == byColor
                            && (Piece.type(piece) == Piece.BISHOP || Piece.type(piece) == Piece.QUEEN)) {
                        return true;
                    }
                    break;
                }
                file = target & 7;
                rank = target >>> 3;
                target += offset;
            }
            file = square & 7;
            rank = square >>> 3;
        }

        for (int offset : ROOK_OFFSETS) {
            int target = square + offset;
            while (target >= 0 && target < 64) {
                int targetFile = target & 7;
                int targetRank = target >>> 3;
                if (Math.abs(targetFile - file) > 1 || Math.abs(targetRank - rank) > 1) {
                    break;
                }
                int piece = board[target];
                if (piece != Piece.EMPTY) {
                    if (Piece.color(piece) == byColor
                            && (Piece.type(piece) == Piece.ROOK || Piece.type(piece) == Piece.QUEEN)) {
                        return true;
                    }
                    break;
                }
                file = targetFile;
                rank = targetRank;
                target += offset;
            }
            file = square & 7;
            rank = square >>> 3;
        }
        return false;
    }

    public void generateMoves(MoveList moves) {
        moves.clear();
        int us = side;
        int them = us ^ 8;
        for (int from = 0; from < 64; from++) {
            int piece = board[from];
            if (piece == Piece.EMPTY || Piece.color(piece) != us) {
                continue;
            }
            int type = Piece.type(piece);
            switch (type) {
                case Piece.PAWN -> generatePawnMoves(from, moves, us, them);
                case Piece.KNIGHT -> generateJumpMoves(from, moves, us, KNIGHT_OFFSETS);
                case Piece.BISHOP -> generateSlidingMoves(from, moves, us, BISHOP_OFFSETS);
                case Piece.ROOK -> generateSlidingMoves(from, moves, us, ROOK_OFFSETS);
                case Piece.QUEEN -> generateSlidingMoves(from, moves, us, QUEEN_OFFSETS);
                case Piece.KING -> {
                    generateJumpMoves(from, moves, us, KING_OFFSETS);
                    generateCastles(moves, us);
                }
                default -> {
                }
            }
        }
    }

    private void generatePawnMoves(int from, MoveList moves, int us, int them) {
        int direction = us == WHITE ? 8 : -8;
        int startRank = us == WHITE ? 1 : 6;
        int promotionRank = us == WHITE ? 7 : 0;
        int file = from & 7;
        int rank = from >>> 3;

        int one = from + direction;
        if (one >= 0 && one < 64 && board[one] == Piece.EMPTY) {
            if ((one >>> 3) == promotionRank) {
                addPromotions(moves, from, one, 0);
            } else {
                moves.add(Move.of(from, one));
                if (rank == startRank) {
                    int two = from + 2 * direction;
                    if (two >= 0 && two < 64 && board[two] == Piece.EMPTY) {
                        moves.add(Move.of(from, two, 0, Move.FLAG_DOUBLE_PAWN));
                    }
                }
            }
        }

        for (int df = -1; df <= 1; df += 2) {
            int targetFile = file + df;
            if (targetFile < 0 || targetFile > 7) {
                continue;
            }
            int target = from + direction + df;
            if (target < 0 || target >= 64) {
                continue;
            }
            int targetPiece = board[target];
            if (targetPiece != Piece.EMPTY && Piece.color(targetPiece) == them) {
                if ((target >>> 3) == promotionRank) {
                    addPromotions(moves, from, target, Move.FLAG_CAPTURE);
                } else {
                    moves.add(Move.of(from, target, 0, Move.FLAG_CAPTURE));
                }
            } else if (target == enPassant && targetPiece == Piece.EMPTY) {
                moves.add(Move.of(from, target, 0, Move.FLAG_CAPTURE | Move.FLAG_EN_PASSANT));
            }
        }
    }

    private void addPromotions(MoveList moves, int from, int to, int flags) {
        for (int type : new int[]{Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT}) {
            moves.add(Move.of(from, to, type, flags));
        }
    }

    private void generateJumpMoves(int from, MoveList moves, int us, int[] offsets) {
        int file = from & 7;
        int rank = from >>> 3;
        for (int offset : offsets) {
            int to = from + offset;
            if (to < 0 || to >= 64) {
                continue;
            }
            int toFile = to & 7;
            int toRank = to >>> 3;
            if (Math.abs(toFile - file) > 2 || Math.abs(toRank - rank) > 2) {
                continue;
            }
            int target = board[to];
            if (target == Piece.EMPTY) {
                moves.add(Move.of(from, to));
            } else if (Piece.color(target) != us) {
                moves.add(Move.of(from, to, 0, Move.FLAG_CAPTURE));
            }
        }
    }

    private void generateSlidingMoves(int from, MoveList moves, int us, int[] directions) {
        int file = from & 7;
        int rank = from >>> 3;
        for (int direction : directions) {
            int to = from + direction;
            int currentFile = file;
            int currentRank = rank;
            while (to >= 0 && to < 64) {
                int toFile = to & 7;
                int toRank = to >>> 3;
                if (Math.abs(toFile - currentFile) > 1 || Math.abs(toRank - currentRank) > 1) {
                    break;
                }
                int target = board[to];
                if (target == Piece.EMPTY) {
                    moves.add(Move.of(from, to));
                } else {
                    if (Piece.color(target) != us) {
                        moves.add(Move.of(from, to, 0, Move.FLAG_CAPTURE));
                    }
                    break;
                }
                currentFile = toFile;
                currentRank = toRank;
                to += direction;
            }
        }
    }

    private void generateCastles(MoveList moves, int us) {
        if (us == WHITE) {
            if ((castling & CASTLE_WHITE_KING) != 0
                    && board[5] == Piece.EMPTY && board[6] == Piece.EMPTY
                    && board[7] == Piece.make(WHITE, Piece.ROOK)
                    && !isSquareAttacked(4, BLACK)
                    && !isSquareAttacked(5, BLACK)
                    && !isSquareAttacked(6, BLACK)) {
                moves.add(Move.of(4, 6, 0, Move.FLAG_CASTLE));
            }
            if ((castling & CASTLE_WHITE_QUEEN) != 0
                    && board[1] == Piece.EMPTY && board[2] == Piece.EMPTY && board[3] == Piece.EMPTY
                    && board[0] == Piece.make(WHITE, Piece.ROOK)
                    && !isSquareAttacked(4, BLACK)
                    && !isSquareAttacked(3, BLACK)
                    && !isSquareAttacked(2, BLACK)) {
                moves.add(Move.of(4, 2, 0, Move.FLAG_CASTLE));
            }
        } else {
            if ((castling & CASTLE_BLACK_KING) != 0
                    && board[61] == Piece.EMPTY && board[62] == Piece.EMPTY
                    && board[63] == Piece.make(BLACK, Piece.ROOK)
                    && !isSquareAttacked(60, WHITE)
                    && !isSquareAttacked(61, WHITE)
                    && !isSquareAttacked(62, WHITE)) {
                moves.add(Move.of(60, 62, 0, Move.FLAG_CASTLE));
            }
            if ((castling & CASTLE_BLACK_QUEEN) != 0
                    && board[57] == Piece.EMPTY && board[58] == Piece.EMPTY && board[59] == Piece.EMPTY
                    && board[56] == Piece.make(BLACK, Piece.ROOK)
                    && !isSquareAttacked(60, WHITE)
                    && !isSquareAttacked(59, WHITE)
                    && !isSquareAttacked(58, WHITE)) {
                moves.add(Move.of(60, 58, 0, Move.FLAG_CASTLE));
            }
        }
    }

    public void generateLegalMoves(MoveList moves) {
        MoveList pseudo = new MoveList(256);
        generateMoves(pseudo);
        moves.clear();
        int us = side;
        for (int i = 0; i < pseudo.size(); i++) {
            int move = pseudo.get(i);
            Undo undo = makeMove(move);
            boolean legal = !inCheck(us);
            unmakeMove(move, undo);
            if (legal) {
                moves.add(move);
            }
        }
    }

    public boolean isLegal(int move) {
        int us = side;
        Undo undo = makeMove(move);
        boolean legal = !inCheck(us);
        unmakeMove(move, undo);
        return legal;
    }

    public int findLegalMove(int from, int to, int promotionType) {
        MoveList moves = new MoveList(256);
        generateLegalMoves(moves);
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.get(i);
            if (Move.from(move) == from && Move.to(move) == to
                    && (promotionType == 0 || Move.promotion(move) == promotionType)) {
                return move;
            }
        }
        return Move.NONE;
    }

    public Undo makeMove(int move) {
        Undo undo = new Undo();
        undo.castling = castling;
        undo.enPassant = enPassant;
        undo.halfmoveClock = halfmoveClock;
        undo.fullmoveNumber = fullmoveNumber;
        undo.hash = hash;
        undo.side = side;

        int from = Move.from(move);
        int to = Move.to(move);
        int promotion = Move.promotion(move);
        int us = side;
        int them = us ^ 8;
        int moving = board[from];
        undo.movedPiece = moving;

        int capturedSquare = to;
        int captured = board[to];
        if (Move.isEnPassant(move)) {
            capturedSquare = to + (us == WHITE ? -8 : 8);
            captured = board[capturedSquare];
            board[capturedSquare] = Piece.EMPTY;
        }
        undo.capturedPiece = captured;
        undo.capturedSquare = capturedSquare;

        if (captured != Piece.EMPTY) {
            hash ^= Zobrist.PIECE_SQUARE[captured][capturedSquare];
        }
        hash ^= Zobrist.PIECE_SQUARE[moving][from];
        board[from] = Piece.EMPTY;

        int placed = promotion == 0 ? moving : Piece.make(us, promotion);
        board[to] = placed;
        hash ^= Zobrist.PIECE_SQUARE[placed][to];

        if (Move.isCastle(move)) {
            boolean kingSide = to > from;
            int rookFrom = from + (kingSide ? 3 : -4);
            int rookTo = from + (kingSide ? 1 : -1);
            undo.rookFrom = rookFrom;
            undo.rookTo = rookTo;
            int rook = board[rookFrom];
            board[rookFrom] = Piece.EMPTY;
            board[rookTo] = rook;
            hash ^= Zobrist.PIECE_SQUARE[rook][rookFrom];
            hash ^= Zobrist.PIECE_SQUARE[rook][rookTo];
        }

        int newCastling = castling & ~(CASTLE_MASK[from] | CASTLE_MASK[to]);
        if (newCastling != castling) {
            hash ^= Zobrist.CASTLING[castling];
            hash ^= Zobrist.CASTLING[newCastling];
            castling = newCastling;
        }

        if (enPassant >= 0) {
            hash ^= Zobrist.EN_PASSANT_FILE[enPassant & 7];
        }
        enPassant = -1;
        if ((move & Move.FLAG_DOUBLE_PAWN) != 0) {
            enPassant = from + (us == WHITE ? 8 : -8);
        }
        if (enPassant >= 0) {
            hash ^= Zobrist.EN_PASSANT_FILE[enPassant & 7];
        }

        if (Piece.type(moving) == Piece.PAWN || captured != Piece.EMPTY) {
            halfmoveClock = 0;
        } else {
            halfmoveClock++;
        }
        if (us == BLACK) {
            fullmoveNumber++;
        }

        side = them;
        hash ^= Zobrist.SIDE_TO_MOVE;
        return undo;
    }

    public void unmakeMove(int move, Undo undo) {
        side = undo.side;
        int us = side;
        int from = Move.from(move);
        int to = Move.to(move);

        board[from] = undo.movedPiece;
        if (Move.isEnPassant(move)) {
            board[to] = Piece.EMPTY;
            board[undo.capturedSquare] = undo.capturedPiece;
        } else {
            board[to] = undo.capturedPiece;
        }

        if (Move.isCastle(move)) {
            board[undo.rookTo] = Piece.EMPTY;
            board[undo.rookFrom] = Piece.make(us, Piece.ROOK);
        }

        castling = undo.castling;
        enPassant = undo.enPassant;
        halfmoveClock = undo.halfmoveClock;
        fullmoveNumber = undo.fullmoveNumber;
        hash = undo.hash;
    }

    public Undo makeNullMove() {
        Undo undo = new Undo();
        undo.castling = castling;
        undo.enPassant = enPassant;
        undo.halfmoveClock = halfmoveClock;
        undo.fullmoveNumber = fullmoveNumber;
        undo.hash = hash;
        undo.side = side;

        if (enPassant >= 0) {
            hash ^= Zobrist.EN_PASSANT_FILE[enPassant & 7];
        }
        enPassant = -1;
        side ^= 8;
        hash ^= Zobrist.SIDE_TO_MOVE;
        return undo;
    }

    public void unmakeNullMove(Undo undo) {
        castling = undo.castling;
        enPassant = undo.enPassant;
        halfmoveClock = undo.halfmoveClock;
        fullmoveNumber = undo.fullmoveNumber;
        hash = undo.hash;
        side = undo.side;
    }

    public boolean hasNonPawnMaterial(int color) {
        for (int piece : board) {
            if (piece != Piece.EMPTY && Piece.color(piece) == color) {
                int type = Piece.type(piece);
                if (type == Piece.KNIGHT || type == Piece.BISHOP
                        || type == Piece.ROOK || type == Piece.QUEEN) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isFiftyMoveDraw() {
        return halfmoveClock >= 100;
    }

    public boolean isInsufficientMaterial() {
        int knights = 0;
        int bishops = 0;
        int bishopSquareParity = -1;
        for (int square = 0; square < 64; square++) {
            int piece = board[square];
            if (piece == Piece.EMPTY || Piece.type(piece) == Piece.KING) {
                continue;
            }
            int type = Piece.type(piece);
            if (type == Piece.PAWN || type == Piece.ROOK || type == Piece.QUEEN) {
                return false;
            }
            if (type == Piece.KNIGHT) {
                knights++;
            } else if (type == Piece.BISHOP) {
                bishops++;
                int parity = ((square & 7) + (square >>> 3)) & 1;
                if (bishopSquareParity == -1) {
                    bishopSquareParity = parity;
                } else if (bishopSquareParity != parity) {
                    return false;
                }
            }
        }
        return knights + bishops <= 1 || (knights == 0 && bishops == 1);
    }

    public boolean isGameOver() {
        MoveList legal = new MoveList(256);
        generateLegalMoves(legal);
        return legal.size() == 0 || isFiftyMoveDraw() || isInsufficientMaterial();
    }

    public String toAscii() {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            sb.append(rank + 1).append("  ");
            for (int file = 0; file < 8; file++) {
                sb.append(Piece.toChar(board[rank * 8 + file])).append(' ');
            }
            sb.append('\n');
        }
        sb.append("\n   a b c d e f g h\n");
        sb.append("side: ").append(side == WHITE ? "white" : "black").append('\n');
        return sb.toString();
    }

    public String toFen() {
        StringJoiner rows = new StringJoiner("/");
        for (int rank = 7; rank >= 0; rank--) {
            StringBuilder row = new StringBuilder();
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int piece = board[rank * 8 + file];
                if (piece == Piece.EMPTY) {
                    empty++;
                } else {
                    if (empty > 0) {
                        row.append(empty);
                        empty = 0;
                    }
                    row.append(Piece.toChar(piece));
                }
            }
            if (empty > 0) {
                row.append(empty);
            }
            rows.add(row);
        }
        StringBuilder castle = new StringBuilder();
        if ((castling & CASTLE_WHITE_KING) != 0) castle.append('K');
        if ((castling & CASTLE_WHITE_QUEEN) != 0) castle.append('Q');
        if ((castling & CASTLE_BLACK_KING) != 0) castle.append('k');
        if ((castling & CASTLE_BLACK_QUEEN) != 0) castle.append('q');
        if (castle.length() == 0) {
            castle.append('-');
        }
        String ep = enPassant < 0 ? "-" : Move.squareName(enPassant);
        return rows + " " + (side == WHITE ? "w" : "b") + " " + castle + " " + ep
                + " " + halfmoveClock + " " + fullmoveNumber;
    }

    public static Position fromFen(String fen) {
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 4) {
            throw new IllegalArgumentException("Invalid FEN: " + fen);
        }
        Position position = new Position();
        position.clear();
        String[] ranks = parts[0].split("/");
        if (ranks.length != 8) {
            throw new IllegalArgumentException("Invalid FEN board: " + parts[0]);
        }
        for (int row = 0; row < 8; row++) {
            int rank = 7 - row;
            int file = 0;
            for (char c : ranks[row].toCharArray()) {
                if (Character.isDigit(c)) {
                    file += c - '0';
                } else {
                    if (file > 7) {
                        throw new IllegalArgumentException("Invalid FEN rank: " + ranks[row]);
                    }
                    position.board[rank * 8 + file] = Piece.fromChar(c);
                    file++;
                }
            }
            if (file != 8) {
                throw new IllegalArgumentException("Invalid FEN rank length: " + ranks[row]);
            }
        }
        position.side = parts[1].equalsIgnoreCase("w") ? WHITE : BLACK;
        int castle = 0;
        if (!parts[2].equals("-")) {
            for (char c : parts[2].toCharArray()) {
                castle |= switch (c) {
                    case 'K' -> CASTLE_WHITE_KING;
                    case 'Q' -> CASTLE_WHITE_QUEEN;
                    case 'k' -> CASTLE_BLACK_KING;
                    case 'q' -> CASTLE_BLACK_QUEEN;
                    default -> 0;
                };
            }
        }
        position.castling = castle;
        position.enPassant = parts[3].equals("-") ? -1
                : ((parts[3].charAt(0) - 'a') + 8 * (parts[3].charAt(1) - '1'));
        position.halfmoveClock = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
        position.fullmoveNumber = parts.length > 5 ? Integer.parseInt(parts[5]) : 1;
        position.hash = position.computeHash();
        return position;
    }
}
