package com.chessai.engine;

/** State needed to undo a move or a null move. */
public final class Undo {
    int capturedPiece;
    int capturedSquare;
    int castling;
    int enPassant;
    int halfmoveClock;
    int fullmoveNumber;
    long hash;
    int side;
    int movedPiece;
    int rookFrom = -1;
    int rookTo = -1;
}
