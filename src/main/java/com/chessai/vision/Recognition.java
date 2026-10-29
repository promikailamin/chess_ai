package com.chessai.vision;

import com.chessai.engine.Position;

/** Result of turning a screenshot into a chess position. */
public final class Recognition {
    public final Position position;
    public final double confidence;
    public final double boardScore;
    public final String method;
    public final BoardQuad quad;
    public final char[][] squares;

    public Recognition(Position position, double confidence, double boardScore,
                       String method, BoardQuad quad, char[][] squares) {
        this.position = position;
        this.confidence = confidence;
        this.boardScore = boardScore;
        this.method = method;
        this.quad = quad;
        this.squares = squares;
    }
}
