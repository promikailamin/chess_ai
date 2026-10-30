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
        Detection squareBoard = detectSquareBoard(work, scale, hough);
        if (squareBoard != null) {
            return squareBoard;
        }
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
        // Square board-only images (for example Lichess analysis screenshots)
        // often fill almost the entire frame, unlike phone screenshots where
        // the board is surrounded by UI chrome.
        guesses.add(BoardQuad.rectangle(0.020 * w, 0.020 * h, 0.980 * w, 0.980 * h));
        guesses.add(BoardQuad.rectangle(0.035 * w, 0.035 * h, 0.965 * w, 0.965 * h));
        // Lichess board screenshots have a 19px coordinate gutter on a 512px
        // canvas, i.e. about 3.7% per side.
        guesses.add(BoardQuad.rectangle(19.0 / 512.0 * w, 19.0 / 512.0 * h,
                (1.0 - 19.0 / 512.0) * w, (1.0 - 19.0 / 512.0) * h));
        guesses.add(BoardQuad.rectangle(0.050 * w, 0.050 * h, 0.950 * w, 0.950 * h));
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

    /**
     * Board-only square images (Lichess/analysis exports and the downloaded
     * Hugging Face screenshots) have a grid that fills almost the whole frame.
     * The regular heuristic deliberately ignores grids wider than ~68% of the
     * image because phone screenshots always contain UI chrome, so handle this
     * layout explicitly instead of falling back to a label-inclusive guess.
     */
    private static Detection detectSquareBoard(RasterImage image, double scale,
                                               HoughDetector.Result hough) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (Math.abs(w - h) > 0.08 * Math.max(w, h)) {
            return null;
        }
        Detection flat = detectFlatSquareBoard(image, scale);
        if (flat != null) {
            return flat;
        }
        LineGrid lineGrid = findLineGrid(hough, image, true);
        if (lineGrid != null) {
            Homography fitted = fitFromLines(lineGrid.vertical, lineGrid.horizontal, image);
            if (fitted != null) {
                BoardQuad quad = quadFromHomography(fitted, image);
                double score = checkerboardScore(image, fitted);
                if (score > 0.15) {
                    if (scale < 0.999) {
                        BoardQuad full = quad.scaled(1.0 / scale);
                        return new Detection(full, Homography.fromQuad(full), score,
                                "square-grid");
                    }
                    return new Detection(quad, fitted, score, "square-grid");
                }
            }
        }
        List<Sequence> vertical = findSequences(image, true, true);
        List<Sequence> horizontal = findSequences(image, false, true);
        Detection best = null;
        for (Sequence v : vertical.subList(0, Math.min(6, vertical.size()))) {
            double spanX = v.positions[8] - v.positions[0];
            if (spanX < 0.80 * w) {
                continue;
            }
            for (Sequence hs : horizontal.subList(0, Math.min(6, horizontal.size()))) {
                double spanY = hs.positions[8] - hs.positions[0];
                if (spanY < 0.80 * h) {
                    continue;
                }
                if (Math.abs(spanX - spanY) > 0.08 * Math.max(spanX, spanY)) {
                    continue;
                }
                BoardQuad quad = BoardQuad.rectangle(v.positions[0], hs.positions[0],
                        v.positions[8], hs.positions[8]);
                double score = checkerboardScore(image, Homography.fromQuad(quad));
                if (score < 0.15) {
                    continue;
                }
                if (best == null || score > best.score) {
                    if (scale < 0.999) {
                        BoardQuad full = quad.scaled(1.0 / scale);
                        best = new Detection(full, Homography.fromQuad(full), score,
                                "square-grid");
                    } else {
                        best = new Detection(quad, Homography.fromQuad(quad), score,
                                "square-grid");
                    }
                }
            }
        }
        return best;
    }

    /**
     * Flat board-only images use two dominant colours for the 64 squares.  The
     * bounding box of those colours is a much more reliable board outline than
     * the coordinate labels, which otherwise look like extra grid lines.
     */
    private static Detection detectFlatSquareBoard(RasterImage image, double scale) {
        int w = image.getWidth();
        int h = image.getHeight();
        int bins = 16;
        int[] histogram = new int[bins * bins * bins];
        int step = Math.max(1, Math.max(w, h) / 512);
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int[] rgb = image.rgbAt(x, y);
                int index = ((rgb[0] >> 4) << 8) | ((rgb[1] >> 4) << 4) | (rgb[2] >> 4);
                histogram[index]++;
            }
        }
        int first = -1;
        int second = -1;
        for (int index = 0; index < histogram.length; index++) {
            if (first < 0 || histogram[index] > histogram[first]) {
                second = first;
                first = index;
            } else if (second < 0 || histogram[index] > histogram[second]) {
                second = index;
            }
        }
        if (first < 0 || second < 0) {
            return null;
        }
        double[] firstColour = binColour(first);
        double[] secondColour = binColour(second);
        int rows = (h + step - 1) / step;
        int columns = (w + step - 1) / step;
        int[] rowHits = new int[rows];
        int[] columnHits = new int[columns];
        int count = 0;
        double threshold = 44;
        for (int y = 0, rowIndex = 0; y < h; y += step, rowIndex++) {
            for (int x = 0, columnIndex = 0; x < w; x += step, columnIndex++) {
                int[] rgb = image.rgbAt(x, y);
                if (colourDistance(rgb, firstColour) <= threshold
                        || colourDistance(rgb, secondColour) <= threshold) {
                    count++;
                    rowHits[rowIndex]++;
                    columnHits[columnIndex]++;
                }
            }
        }
        double sampled = (double) rows * columns;
        if (count / sampled < 0.35) {
            return null;
        }
        int minX = -1;
        int maxX = -1;
        int minY = -1;
        int maxY = -1;
        int rowThreshold = Math.max(1, (int) (0.30 * columns));
        int columnThreshold = Math.max(1, (int) (0.30 * rows));
        for (int i = 0; i < rows; i++) {
            if (rowHits[i] >= rowThreshold) {
                if (minY < 0) minY = i * step;
                maxY = Math.min(h - 1, i * step + step - 1);
            }
        }
        for (int i = 0; i < columns; i++) {
            if (columnHits[i] >= columnThreshold) {
                if (minX < 0) minX = i * step;
                maxX = Math.min(w - 1, i * step + step - 1);
            }
        }
        if (maxX <= minX || maxY <= minY) {
            return null;
        }
        double boxWidth = maxX - minX;
        double boxHeight = maxY - minY;
        if (boxWidth < 0.60 * w || boxHeight < 0.60 * h) {
            return null;
        }
        if (Math.abs(boxWidth - boxHeight) > 0.06 * Math.max(boxWidth, boxHeight)) {
            return null;
        }
        double pad = Math.max(1.0, 0.002 * Math.min(w, h));
        BoardQuad quad = BoardQuad.rectangle(
                Math.max(0, minX - pad), Math.max(0, minY - pad),
                Math.min(w - 1, maxX + pad + 1), Math.min(h - 1, maxY + pad + 1));
        double score = checkerboardScore(image, Homography.fromQuad(quad));
        if (scale < 0.999) {
            BoardQuad full = quad.scaled(1.0 / scale);
            return new Detection(full, Homography.fromQuad(full), score, "square-colour");
        }
        return new Detection(quad, Homography.fromQuad(quad), score, "square-colour");
    }

    private static double[] binColour(int index) {
        return new double[]{
                ((index >> 8) & 0xf) * 16 + 8,
                ((index >> 4) & 0xf) * 16 + 8,
                (index & 0xf) * 16 + 8
        };
    }

    private static double colourDistance(int[] rgb, double[] colour) {
        double red = rgb[0] - colour[0];
        double green = rgb[1] - colour[1];
        double blue = rgb[2] - colour[2];
        return Math.sqrt(red * red + green * green + blue * blue);
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
            // A low-confidence guess is often a full-frame square that still
            // contains the coordinate labels.  In that case let the grid lines
            // replace it even when the raw checkerboard score drops, because
            // the fitted grid is much more trustworthy than the initial guess.
            boolean lowConfidenceGuess = score < 0.45;
            if (fittedScore >= score - 0.02
                    || (lowConfidenceGuess && fittedScore >= 0.30)) {
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
        return findLineGrid(hough, image, false);
    }

    private static LineGrid findLineGrid(HoughDetector.Result hough, RasterImage image,
                                         boolean allowLargeGrid) {
        HoughLine[] vertical = bestSequence(hough.vertical, true, image, allowLargeGrid);
        HoughLine[] horizontal = bestSequence(hough.horizontal, false, image, allowLargeGrid);
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
        return bestSequence(candidates, vertical, image, false);
    }

    private static HoughLine[] bestSequence(List<HoughLine> candidates, boolean vertical,
                                            RasterImage image, boolean allowLargeGrid) {
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
        double maxSpan = vertical
                ? (allowLargeGrid ? 0.97 * dim : 0.82 * dim)
                : 0.995 * dim;

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
        return findSequences(image, vertical, false);
    }

    private static List<Sequence> findSequences(RasterImage image, boolean vertical,
                                                boolean allowLargeGrid) {
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
        double minSpan;
        double maxSpan;
        double minStep;
        double maxStep;
        if (allowLargeGrid) {
            minSpan = vertical ? 0.30 * w : 0.35 * h;
            maxSpan = vertical ? 0.96 * w : 0.995 * h;
            minStep = (vertical ? 0.030 * w : 0.035 * h);
            maxStep = (vertical ? 0.125 * w : 0.130 * h);
        } else {
            minSpan = vertical ? 0.30 * w : 0.50 * h;
            maxSpan = vertical ? 0.68 * w : 0.995 * h;
            minStep = (vertical ? 0.030 * w : 0.050 * h);
            maxStep = (vertical ? 0.090 * w : 0.130 * h);
        }
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
