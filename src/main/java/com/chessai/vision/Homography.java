package com.chessai.vision;

/** A projective 3x3 homography mapping (u,v) to (x,y). */
public final class Homography {
    private final double h00, h01, h02;
    private final double h10, h11, h12;
    private final double h20, h21, h22;

    public Homography(double[] h) {
        this(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], h[8]);
    }

    public Homography(double h00, double h01, double h02,
                      double h10, double h11, double h12,
                      double h20, double h21, double h22) {
        this.h00 = h00;
        this.h01 = h01;
        this.h02 = h02;
        this.h10 = h10;
        this.h11 = h11;
        this.h12 = h12;
        this.h20 = h20;
        this.h21 = h21;
        this.h22 = h22;
    }

    public double[] map(double u, double v) {
        double denominator = h20 * u + h21 * v + h22;
        if (Math.abs(denominator) < 1e-12) {
            denominator = Math.signum(denominator) * 1e-12;
        }
        return new double[]{
                (h00 * u + h01 * v + h02) / denominator,
                (h10 * u + h11 * v + h12) / denominator
        };
    }

    public Homography inverse() {
        double a = h00, b = h01, c = h02;
        double d = h10, e = h11, f = h12;
        double g = h20, h = h21, i = h22;
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-15) {
            throw new IllegalStateException("Singular homography");
        }
        double invDet = 1.0 / det;
        return new Homography(
                (e * i - f * h) * invDet,
                (c * h - b * i) * invDet,
                (b * f - c * e) * invDet,
                (f * g - d * i) * invDet,
                (a * i - c * g) * invDet,
                (c * d - a * f) * invDet,
                (d * h - e * g) * invDet,
                (b * g - a * h) * invDet,
                (a * e - b * d) * invDet
        );
    }

    /** Least-squares homography from source points to destination points. */
    public static Homography fromCorrespondences(double[] sourceX, double[] sourceY,
                                                 double[] destX, double[] destY) {
        if (sourceX.length < 4 || sourceX.length != sourceY.length
                || sourceX.length != destX.length || sourceX.length != destY.length) {
            throw new IllegalArgumentException("Need at least four correspondences");
        }
        double[][] normal = new double[8][8];
        double[] rhs = new double[8];
        for (int i = 0; i < sourceX.length; i++) {
            double u = sourceX[i];
            double v = sourceY[i];
            double x = destX[i];
            double y = destY[i];
            double[] row1 = {u, v, 1, 0, 0, 0, -x * u, -x * v};
            double[] row2 = {0, 0, 0, u, v, 1, -y * u, -y * v};
            accumulate(normal, rhs, row1, x);
            accumulate(normal, rhs, row2, y);
        }
        double[] h = solve(normal, rhs);
        return new Homography(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0);
    }

    private static void accumulate(double[][] normal, double[] rhs, double[] row, double value) {
        for (int i = 0; i < 8; i++) {
            rhs[i] += row[i] * value;
            for (int j = 0; j < 8; j++) {
                normal[i][j] += row[i] * row[j];
            }
        }
    }

    private static double[] solve(double[][] matrix, double[] rhs) {
        int n = rhs.length;
        double[][] a = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(matrix[i], 0, a[i], 0, n);
            a[i][n] = rhs[i];
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) {
                    pivot = row;
                }
            }
            if (Math.abs(a[pivot][col]) < 1e-12) {
                throw new IllegalStateException("Singular system while fitting homography");
            }
            double[] tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            double divisor = a[col][col];
            for (int j = col; j <= n; j++) {
                a[col][j] /= divisor;
            }
            for (int row = 0; row < n; row++) {
                if (row == col) {
                    continue;
                }
                double factor = a[row][col];
                if (Math.abs(factor) < 1e-15) {
                    continue;
                }
                for (int j = col; j <= n; j++) {
                    a[row][j] -= factor * a[col][j];
                }
            }
        }
        double[] solution = new double[n];
        for (int i = 0; i < n; i++) {
            solution[i] = a[i][n];
        }
        return solution;
    }

    public static Homography fromQuad(BoardQuad quad) {
        double[] sx = {0, 1, 1, 0};
        double[] sy = {0, 0, 1, 1};
        double[] dx = {quad.topLeftX(), quad.topRightX(), quad.bottomRightX(), quad.bottomLeftX()};
        double[] dy = {quad.topLeftY(), quad.topRightY(), quad.bottomRightY(), quad.bottomLeftY()};
        return fromCorrespondences(sx, sy, dx, dy);
    }
}
