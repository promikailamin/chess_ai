package com.chessai.vision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Locates the playing area of a chess board.  It first looks for the
 * characteristic evenly-spaced grid lines, then refines the result with a
 * Hough transform.  This handles both flat 2D themes and the rendered
 * perspective boards used by the screenshots.
 */
public final class BoardDetector {
    private static final boolean DEBUG = Boolean.getBoolean("chessai.debug");
    public static final class Detection {
        public final BoardQuad quad;
        public final Homography unitToImage;
        public final double score;
        public final String method;

        Detection(BoardQuad quad, Homography unitToImage, double score, String method) {
            this.quad = quad;
            this.unitToImage = unitToImage;
            this.score = score;
            this.method = method;
        }
    }

    private static final class Sequence {
        final double[] positions = new double[9];
        final double score;

        Sequence(double[] positions, double score) {
            System.arraycopy(positions, 0, this.positions, 0, 9);
            this.score = score;
        }
    }

    private BoardDetector() {
    }

    public static Detection detect(RasterImage image) {
        double scale = Math.min(1.0, 900.0 / Math.max(image.getWidth(), image.getHeight()));
        RasterImage work = scale < 0.999 ? image.scaled(scale) : image;
        int w = work.getWidth();
        int h = work.getHeight();

        HoughDetector.Result hough = HoughDetector.detect(work);
        List<BoardQuad> guesses = new ArrayList<>();

        List<Sequence> verticalSequences = findSequences(work, true);
        List<Sequence> horizontalSequences = findSequences(work, false);
        int verticalLimit = Math.min(6, verticalSequences.size());
        int horizontalLimit = Math.min(6, horizontalSequences.size());
        for (int i = 0; i < verticalLimit; i++) {
            for (int j = 0; j < horizontalLimit; j++) {
                Sequence v = verticalSequences.get(i);
                Sequence hs = horizontalSequences.get(j);
                guesses.add(BoardQuad.rectangle(v.positions[0], hs.positions[0],
                        v.positions[8], hs.positions[8]));
            }
        }

        guesses.add(BoardQuad.rectangle(0.313 * w, 0.030 * h, 0.733 * w, 0.967 * h));
        guesses.add(BoardQuad.rectangle(0.280 * w, 0.020 * h, 0.770 * w, 0.980 * h));
        guesses.add(new BoardQuad(0.300 * w, 0.050 * h, 0.745 * w, 0.050 * h,
                0.800 * w, 0.960 * h, 0.240 * w, 0.960 * h));
        guesses.add(new BoardQuad(0.290 * w, 0.040 * h, 0.755 * w, 0.040 * h,
                0.795 * w, 0.970 * h, 0.245 * w, 0.970 * h));
        guesses.add(new BoardQuad(0.320 * w, 0.040 * h, 0.735 * w, 0.040 * h,
                0.780 * w, 0.965 * h, 0.260 * w, 0.965 * h));

        LineGrid lineGrid = findLineGrid(hough, work);
        if (lineGrid != null) {
            Homography fitted = fitFromLines(lineGrid.vertical, lineGrid.horizontal, work);
            if (fitted != null) {
                double score = checkerboardScore(work, fitted);
                if (DEBUG) {
                    System.out.printf("grid-fit=%s score=%.3f%n",
                            quadFromHomography(fitted, work).describe(), score);
                }
                if (score > 0.30) {
                    BoardQuad gridQuad = quadFromHomography(fitted, work);
                    if (scale < 0.999) {
                        BoardQuad fullQuad = gridQuad.scaled(1.0 / scale);
                        return new Detection(fullQuad, Homography.fromQuad(fullQuad), score, "hough-grid");
                    }
                    return new Detection(gridQuad, fitted, score, "hough-grid");
                }
            }
        }

        Detection best = null;
        Detection bestHough = null;
        for (BoardQuad guess : guesses) {
            Detection refined = refine(work, hough, guess);
            if (best == null || refined.score > best.score) {
                best = refined;
            }
            if (refined.method.equals("hough")
                    && (bestHough == null || refined.score > bestHough.score)) {
                bestHough = refined;
            }
        }
        if (bestHough != null && bestHough.score > 0.30) {
            best = bestHough;
        }
        if (best == null) {
            BoardQuad fallback = BoardQuad.rectangle(0.313 * w, 0.030 * h, 0.733 * w, 0.967 * h);
            best = new Detection(fallback, Homography.fromQuad(fallback), 0, "fallback");
        }
        if (scale < 0.999) {
            BoardQuad fullQuad = best.quad.scaled(1.0 / scale);
            return new Detection(fullQuad, Homography.fromQuad(fullQuad), best.score, best.method);
        }
        return best;
    }

    private static Detection refine(RasterImage image, HoughDetector.Result hough, BoardQuad guess) {
        BoardQuad current = guess;
        Homography homography = Homography.fromQuad(current);
        double score = checkerboardScore(image, homography);
        String method = "guess";

        for (int pass = 0; pass < 2; pass++) {
            HoughLine[] vertical = selectLines(homography, hough.vertical, true, image);
            HoughLine[] horizontal = selectLines(homography, hough.horizontal, false, image);
            if (vertical == null || horizontal == null) {
                break;
            }
            Homography fitted = fitFromLines(vertical, horizontal, image);
            if (fitted == null) {
                break;
            }
            double fittedScore = checkerboardScore(image, fitted);
            if (DEBUG) {
                System.out.printf("refine guess=%s pass=%d fitted=%s score=%.3f%n",
                        guess.describe(), pass, quadFromHomography(fitted, image).describe(), fittedScore);
            }
            if (fittedScore >= score - 0.02) {
                homography = fitted;
                score = fittedScore;
                current = quadFromHomography(fitted, image);
                method = "hough";
            } else {
                break;
            }
        }
        return new Detection(current, homography, score, method);
    }


    private static final class LineGrid {
        final HoughLine[] vertical;
        final HoughLine[] horizontal;
        final double score;

        LineGrid(HoughLine[] vertical, HoughLine[] horizontal, double score) {
            this.vertical = vertical;
            this.horizontal = horizontal;
            this.score = score;
        }
    }

    private static LineGrid findLineGrid(HoughDetector.Result hough, RasterImage image) {
        HoughLine[] vertical = bestSequence(hough.vertical, true, image);
        HoughLine[] horizontal = bestSequence(hough.horizontal, false, image);
        if (vertical == null || horizontal == null) {
            return null;
        }
        if (DEBUG) {
            System.out.print("grid V:");
            for (HoughLine line : vertical) {
                System.out.printf(" %.1f/%.0f/%.0f", line.positionAtCenter(image.getWidth() / 2.0,
                        image.getHeight() / 2.0), line.angleDegrees(), line.weight);
            }
            System.out.print("\ngrid H:");
            for (HoughLine line : horizontal) {
                System.out.printf(" %.1f/%.0f/%.0f", line.positionAtCenter(image.getWidth() / 2.0,
                        image.getHeight() / 2.0), line.angleDegrees(), line.weight);
            }
            System.out.println();
        }
        double score = 0;
        for (HoughLine line : vertical) {
            score += line.weight;
        }
        for (HoughLine line : horizontal) {
            score += line.weight;
        }
        return new LineGrid(vertical, horizontal, score);
    }

    private static HoughLine[] bestSequence(List<HoughLine> candidates, boolean vertical,
                                            RasterImage image) {
        List<HoughLine> lines = new ArrayList<>();
        for (HoughLine line : candidates) {
            if (line.vertical == vertical) {
                lines.add(line);
            }
        }
        if (lines.size() < 9) {
            return null;
        }
        double centerX = image.getWidth() / 2.0;
        double centerY = image.getHeight() / 2.0;
        lines.sort(Comparator.comparingDouble(line -> line.positionAtCenter(centerX, centerY)));
        double dim = vertical ? image.getWidth() : image.getHeight();
        double minSpan = vertical ? 0.24 * dim : 0.28 * dim;
        double maxSpan = vertical ? 0.82 * dim : 0.995 * dim;

        HoughLine[] best = null;
        double bestScore = -1;
        int n = lines.size();
        double[] pos = new double[n];
        for (int i = 0; i < n; i++) {
            pos[i] = lines.get(i).positionAtCenter(centerX, centerY);
        }
        for (int start = 0; start < n - 8; start++) {
            for (int end = start + 8; end < n; end++) {
                double span = pos[end] - pos[start];
                if (span < minSpan || span > maxSpan) {
                    continue;
                }
                double step = span / 8.0;
                double tolerance = Math.max(3.0, step * 0.12);
                HoughLine[] chosen = new HoughLine[9];
                boolean[] used = new boolean[n];
                double score = 0;
                boolean valid = true;
                for (int k = 0; k < 9; k++) {
                    double target = pos[start] + k * step;
                    int pick = -1;
                    double pickScore = -1;
                    for (int c = 0; c < n; c++) {
                        if (used[c]) {
                            continue;
                        }
                        double distance = Math.abs(pos[c] - target);
                        if (distance > tolerance) {
                            continue;
                        }
                        double distanceScale = 2.5;
                        double candidateScore = lines.get(c).weight
                                / (1.0 + (distance / distanceScale) * (distance / distanceScale));
                        if (candidateScore > pickScore) {
                            pickScore = candidateScore;
                            pick = c;
                        }
                    }
                    if (pick < 0) {
                        valid = false;
                        break;
                    }
                    used[pick] = true;
                    chosen[k] = lines.get(pick);
                    score += chosen[k].weight;
                }
                if (!valid) {
                    continue;
                }
                for (int k = 1; k < 9; k++) {
                    double actualStep = pos[findIndex(lines, chosen[k], centerX, centerY)]
                            - pos[findIndex(lines, chosen[k - 1], centerX, centerY)];
                    if (actualStep < step * 0.55) {
                        valid = false;
                        break;
                    }
                }
                if (valid && score > bestScore) {
                    bestScore = score;
                    best = chosen;
                }
            }
        }
        return best;
    }

    private static int findIndex(List<HoughLine> lines, HoughLine target,
                                 double centerX, double centerY) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i) == target) {
                return i;
            }
        }
        return 0;
    }

    private static HoughLine[] selectLines(Homography homography, List<HoughLine> candidates,
                                           boolean vertical, RasterImage image) {
        if (candidates.size() < 4) {
            return null;
        }
        double tolerance = Math.max(8.0, 0.055 * Math.min(image.getWidth(), image.getHeight()));
        HoughLine[] selected = new HoughLine[9];
        boolean[] used = new boolean[candidates.size()];
        double[] samples = {0.05, 0.2, 0.4, 0.6, 0.8, 0.95};
        for (int index = 0; index < 9; index++) {
            double u = index / 8.0;
            double[] xs = new double[samples.length];
            double[] ys = new double[samples.length];
            for (int k = 0; k < samples.length; k++) {
                double[] point = vertical
                        ? homography.map(u, samples[k])
                        : homography.map(samples[k], u);
                xs[k] = point[0];
                ys[k] = point[1];
            }
            double bestScore = -1;
            int bestIndex = -1;
            for (int c = 0; c < candidates.size(); c++) {
                if (used[c]) {
                    continue;
                }
                HoughLine line = candidates.get(c);
                if (line.vertical != vertical) {
                    continue;
                }
                double distance = 0;
                for (int k = 0; k < samples.length; k++) {
                    distance += line.distance(xs[k], ys[k]);
                }
                distance /= samples.length;
                if (distance > tolerance) {
                    continue;
                }
                double scale = 6.0;
                double candidateScore = line.weight / (1.0 + (distance / scale) * (distance / scale));
                if (candidateScore > bestScore) {
                    bestScore = candidateScore;
                    bestIndex = c;
                }
            }
            if (bestIndex < 0) {
                return null;
            }
            selected[index] = candidates.get(bestIndex);
            used[bestIndex] = true;
            if (DEBUG) {
                HoughLine chosen = candidates.get(bestIndex);
                System.out.printf("  %s[%d] -> pos %.1f weight %.0f%n", vertical ? "V" : "H",
                        index, chosen.positionAtCenter(image.getWidth() / 2.0, image.getHeight() / 2.0), chosen.weight);
            }
        }
        return selected;
    }

    private static Homography fitFromLines(HoughLine[] vertical, HoughLine[] horizontal,
                                           RasterImage image) {
        List<Double> sourceU = new ArrayList<>();
        List<Double> sourceV = new ArrayList<>();
        List<Double> destX = new ArrayList<>();
        List<Double> destY = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            for (int j = 0; j < 9; j++) {
                double[] point = intersect(vertical[i], horizontal[j]);
                if (point == null) {
                    continue;
                }
                if (point[0] < -0.1 * image.getWidth() || point[0] > 1.1 * image.getWidth()
                        || point[1] < -0.1 * image.getHeight() || point[1] > 1.1 * image.getHeight()) {
                    continue;
                }
                sourceU.add(i / 8.0);
                sourceV.add(j / 8.0);
                destX.add(point[0]);
                destY.add(point[1]);
            }
        }
        if (sourceU.size() < 12) {
            return null;
        }
        double[] su = new double[sourceU.size()];
        double[] sv = new double[sourceV.size()];
        double[] dx = new double[destX.size()];
        double[] dy = new double[destY.size()];
        for (int i = 0; i < su.length; i++) {
            su[i] = sourceU.get(i);
            sv[i] = sourceV.get(i);
            dx[i] = destX.get(i);
            dy[i] = destY.get(i);
        }
        try {
            return Homography.fromCorrespondences(su, sv, dx, dy);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static double[] intersect(HoughLine a, HoughLine b) {
        double cosA = Math.cos(a.theta);
        double sinA = Math.sin(a.theta);
        double cosB = Math.cos(b.theta);
        double sinB = Math.sin(b.theta);
        double det = cosA * sinB - sinA * cosB;
        if (Math.abs(det) < 1e-9) {
            return null;
        }
        double x = (a.rho * sinB - sinA * b.rho) / det;
        double y = (cosA * b.rho - a.rho * cosB) / det;
        return new double[]{x, y};
    }

    private static BoardQuad quadFromHomography(Homography homography, RasterImage image) {
        double[] tl = homography.map(0, 0);
        double[] tr = homography.map(1, 0);
        double[] br = homography.map(1, 1);
        double[] bl = homography.map(0, 1);
        return new BoardQuad(tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1]);
    }

    public static double checkerboardScore(RasterImage image, Homography homography) {
        double[] values = new double[64];
        double sum = 0;
        int index = 0;
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                double uCenter = (col + 0.5) / 8.0;
                double vCenter = (row + 0.5) / 8.0;
                double value = 0;
                int samples = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        double u = uCenter + dx * 0.12 / 8.0;
                        double v = vCenter + dy * 0.12 / 8.0;
                        double[] point = homography.map(u, v);
                        if (point[0] < 0 || point[1] < 0
                                || point[0] >= image.getWidth() || point[1] >= image.getHeight()) {
                            return -1;
                        }
                        value += image.luminanceAt(point[0], point[1]);
                        samples++;
                    }
                }
                value /= samples;
                values[index++] = value;
                sum += value;
            }
        }
        double mean = sum / 64.0;
        double variance = 0;
        double correlation = 0;
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                double centered = values[row * 8 + col] - mean;
                variance += centered * centered;
                double sign = ((row + col) & 1) == 0 ? 1 : -1;
                correlation += centered * sign;
            }
        }
        double std = Math.sqrt(variance / 64.0);
        if (std < 1e-6) {
            return -1;
        }
        return Math.abs(correlation / (64.0 * std));
    }

    private static List<Sequence> findSequences(RasterImage image, boolean vertical) {
        int w = image.getWidth();
        int h = image.getHeight();
        int dim = vertical ? w : h;
        double[] scores = new double[dim];
        double[] gray = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                gray[y * w + x] = image.luminance(x, y);
            }
        }
        if (vertical) {
            for (int x = 1; x < w - 1; x++) {
                double sum = 0;
                for (int y = (int) (h * 0.05); y < (int) (h * 0.95); y++) {
                    sum += Math.abs(gray[y * w + x + 1] - gray[y * w + x - 1]);
                }
                scores[x] = sum;
            }
        } else {
            for (int y = 1; y < h - 1; y++) {
                double sum = 0;
                for (int x = (int) (w * 0.12); x < (int) (w * 0.88); x++) {
                    sum += Math.abs(gray[(y + 1) * w + x] - gray[(y - 1) * w + x]);
                }
                scores[y] = sum;
            }
        }
        double[] smoothed = new double[dim];
        for (int i = 0; i < dim; i++) {
            double sum = 0;
            int count = 0;
            for (int d = -2; d <= 2; d++) {
                int j = i + d;
                if (j >= 0 && j < dim) {
                    sum += scores[j];
                    count++;
                }
            }
            smoothed[i] = sum / count;
        }
        double max = 0;
        double total = 0;
        for (double value : smoothed) {
            max = Math.max(max, value);
            total += value;
        }
        if (max < 1e-6) {
            return List.of();
        }
        double mean = total / dim;
        double peakThreshold = Math.max(mean * 1.15, max * 0.08);
        List<Integer> peaks = new ArrayList<>();
        int minDistance = Math.max(3, dim / 160);
        for (int i = 2; i < dim - 2; i++) {
            double value = smoothed[i];
            if (value < peakThreshold) {
                continue;
            }
            if (value < smoothed[i - 1] || value < smoothed[i + 1]) {
                continue;
            }
            if (!peaks.isEmpty() && i - peaks.get(peaks.size() - 1) < minDistance) {
                if (value > smoothed[peaks.get(peaks.size() - 1)]) {
                    peaks.set(peaks.size() - 1, i);
                }
                continue;
            }
            peaks.add(i);
        }
        if (peaks.size() < 4) {
            return List.of();
        }
        double minSpan = vertical ? 0.30 * w : 0.50 * h;
        double maxSpan = vertical ? 0.68 * w : 0.995 * h;
        double minStep = (vertical ? 0.030 * w : 0.050 * h);
        double maxStep = (vertical ? 0.090 * w : 0.130 * h);
        List<Sequence> candidates = new ArrayList<>();
        double[] position = new double[9];
        for (int startPeak : peaks) {
            for (double step = minStep; step <= maxStep; step += 0.5) {
                double span = step * 8;
                if (span < minSpan || span > maxSpan) {
                    continue;
                }
                double score = 0;
                boolean valid = true;
                for (int k = 0; k < 9; k++) {
                    double target = startPeak + k * step;
                    int center = (int) Math.round(target);
                    int tolerance = Math.max(2, (int) Math.round(step * 0.05));
                    int best = -1;
                    double bestValue = -1;
                    for (int d = -tolerance; d <= tolerance; d++) {
                        int p = center + d;
                        if (p >= 0 && p < dim && smoothed[p] > bestValue) {
                            bestValue = smoothed[p];
                            best = p;
                        }
                    }
                    if (best < 0 || bestValue < peakThreshold * 0.35) {
                        valid = false;
                        break;
                    }
                    position[k] = best;
                    score += Math.min(3.0, bestValue / peakThreshold);
                }
                if (!valid) {
                    continue;
                }
                for (int k = 1; k < 9; k++) {
                    if (position[k] - position[k - 1] < step * 0.55) {
                        valid = false;
                        break;
                    }
                }
                if (valid) {
                    candidates.add(new Sequence(position.clone(), score));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((Sequence s) -> s.score).reversed());
        List<Sequence> unique = new ArrayList<>();
        for (Sequence candidate : candidates) {
            boolean duplicate = false;
            for (Sequence existing : unique) {
                if (Math.abs(existing.positions[0] - candidate.positions[0]) < 3
                        && Math.abs((existing.positions[8] - existing.positions[0])
                        - (candidate.positions[8] - candidate.positions[0])) < 4) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                unique.add(candidate);
            }
            if (unique.size() >= 16) {
                break;
            }
        }
        return unique;
    }
}
