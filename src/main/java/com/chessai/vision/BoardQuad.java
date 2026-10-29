package com.chessai.vision;

/** Four board corners, clockwise from the top-left (a8) corner. */
public final class BoardQuad {
    private final double[] xs = new double[4];
    private final double[] ys = new double[4];

    public BoardQuad(double tlX, double tlY, double trX, double trY,
                     double brX, double brY, double blX, double blY) {
        xs[0] = tlX;
        ys[0] = tlY;
        xs[1] = trX;
        ys[1] = trY;
        xs[2] = brX;
        ys[2] = brY;
        xs[3] = blX;
        ys[3] = blY;
    }

    public static BoardQuad rectangle(double left, double top, double right, double bottom) {
        return new BoardQuad(left, top, right, top, right, bottom, left, bottom);
    }

    public double x(int index) {
        return xs[index & 3];
    }

    public double y(int index) {
        return ys[index & 3];
    }

    public double topLeftX() {
        return xs[0];
    }

    public double topLeftY() {
        return ys[0];
    }

    public double topRightX() {
        return xs[1];
    }

    public double topRightY() {
        return ys[1];
    }

    public double bottomRightX() {
        return xs[2];
    }

    public double bottomRightY() {
        return ys[2];
    }

    public double bottomLeftX() {
        return xs[3];
    }

    public double bottomLeftY() {
        return ys[3];
    }

    public double area() {
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            sum += xs[i] * ys[j] - xs[j] * ys[i];
        }
        return Math.abs(sum) * 0.5;
    }

    public BoardQuad scaled(double factor) {
        return new BoardQuad(xs[0] * factor, ys[0] * factor, xs[1] * factor, ys[1] * factor,
                xs[2] * factor, ys[2] * factor, xs[3] * factor, ys[3] * factor);
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                sb.append(" -> ");
            }
            sb.append('(').append(Math.round(xs[i])).append(',').append(Math.round(ys[i])).append(')');
        }
        return sb.toString();
    }
}
