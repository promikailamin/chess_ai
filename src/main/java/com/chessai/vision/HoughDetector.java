package com.chessai.vision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A compact Hough transform for near-vertical and near-horizontal lines. */
public final class HoughDetector {
    public static final class Result {
        public final List<HoughLine> vertical;
        public final List<HoughLine> horizontal;

        Result(List<HoughLine> vertical, List<HoughLine> horizontal) {
            this.vertical = vertical;
            this.horizontal = horizontal;
        }
    }

    private HoughDetector() {
    }

    public static Result detect(RasterImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        double[] gray = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                gray[y * w + x] = image.luminance(x, y);
            }
        }
        double[] magnitude = new double[w * h];
        List<Integer> sampled = new ArrayList<>(Math.min(60000, w * h / 4));
        int sampleStep = Math.max(1, (w * h) / 50000);
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                double gx = gray[y * w + x + 1] - gray[y * w + x - 1];
                double gy = gray[(y + 1) * w + x] - gray[(y - 1) * w + x];
                double mag = Math.hypot(gx, gy);
                magnitude[y * w + x] = mag;
                if ((y * w + x) % sampleStep == 0) {
                    sampled.add((int) mag);
                }
            }
        }
        sampled.sort(Integer::compareTo);
        if (sampled.isEmpty()) {
            return new Result(new ArrayList<>(), new ArrayList<>());
        }
        double threshold = Math.max(35.0, sampled.get((int) (sampled.size() * 0.90)));

        int diagonal = (int) Math.ceil(Math.hypot(w, h));
        int rhoBins = diagonal * 2 + 1;
        int offset = diagonal;
        int[][] accumulator = new int[180][rhoBins];
        double[] cosTable = new double[180];
        double[] sinTable = new double[180];
        for (int t = 0; t < 180; t++) {
            double theta = Math.toRadians(t);
            cosTable[t] = Math.cos(theta);
            sinTable[t] = Math.sin(theta);
        }

        int edgePoints = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                double mag = magnitude[y * w + x];
                if (mag < threshold) {
                    continue;
                }
                if (mag < magnitude[y * w + x - 1] || mag < magnitude[y * w + x + 1]
                        || mag < magnitude[(y - 1) * w + x] || mag < magnitude[(y + 1) * w + x]) {
                    continue;
                }
                edgePoints++;
                for (int t = 0; t < 180; t++) {
                    int rho = (int) Math.round(x * cosTable[t] + y * sinTable[t]) + offset;
                    if (rho >= 0 && rho < rhoBins) {
                        accumulator[t][rho]++;
                    }
                }
            }
        }
        if (edgePoints == 0) {
            return new Result(new ArrayList<>(), new ArrayList<>());
        }

        int maxVotes = 0;
        for (int t = 0; t < 180; t++) {
            for (int r = 0; r < rhoBins; r++) {
                maxVotes = Math.max(maxVotes, accumulator[t][r]);
            }
        }
        int voteThreshold = Math.max(14, (int) (maxVotes * 0.16));

        List<Candidate> candidates = new ArrayList<>();
        for (int t = 0; t < 180; t++) {
            boolean vertical = t <= 20 || t >= 160;
            boolean horizontal = t >= 70 && t <= 110;
            if (!vertical && !horizontal) {
                continue;
            }
            for (int r = 1; r < rhoBins - 1; r++) {
                int votes = accumulator[t][r];
                if (votes < voteThreshold) {
                    continue;
                }
                if (votes < accumulator[t][r - 1] || votes < accumulator[t][r + 1]) {
                    continue;
                }
                if (t > 0 && votes < accumulator[t - 1][r]) {
                    continue;
                }
                if (t < 179 && votes < accumulator[t + 1][r]) {
                    continue;
                }
                double theta = Math.toRadians(t);
                double rho = r - offset;
                HoughLine line = new HoughLine(theta, rho, votes, vertical);
                candidates.add(new Candidate(line, vertical));
            }
        }

        candidates.sort(Comparator.comparingDouble((Candidate c) -> c.line.weight).reversed());
        List<HoughLine> verticalLines = cluster(candidates, true, w, h);
        List<HoughLine> horizontalLines = cluster(candidates, false, w, h);
        return new Result(verticalLines, horizontalLines);
    }

    private static final class Candidate {
        final HoughLine line;
        final boolean vertical;

        Candidate(HoughLine line, boolean vertical) {
            this.line = line;
            this.vertical = vertical;
        }
    }

    private static List<HoughLine> cluster(List<Candidate> candidates, boolean vertical, int w, int h) {
        double centerX = w / 2.0;
        double centerY = h / 2.0;
        double positionTolerance = Math.max(4.0, w * 0.008);
        List<HoughLine> result = new ArrayList<>();
        List<Double> positions = new ArrayList<>();
        List<Double> angles = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        List<Double> rhos = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.vertical != vertical) {
                continue;
            }
            HoughLine line = candidate.line;
            double position = line.positionAtCenter(centerX, centerY);
            if (Double.isNaN(position)) {
                continue;
            }
            double angle = line.angleDegrees();
            int match = -1;
            for (int i = 0; i < result.size(); i++) {
                if (Math.abs(positions.get(i) - position) <= positionTolerance
                        && angleDifference(angles.get(i), angle) <= 2.5) {
                    match = i;
                    break;
                }
            }
            if (match < 0) {
                positions.add(position);
                angles.add(angle);
                weights.add(line.weight);
                rhos.add(line.rho);
                result.add(line);
            } else if (line.weight > weights.get(match)) {
                positions.set(match, position);
                angles.set(match, angle);
                weights.set(match, line.weight);
                rhos.set(match, line.rho);
                result.set(match, line);
            }
        }
        if (result.size() > 48) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < result.size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.comparingDouble((Integer i) -> -weights.get(i)));
            List<HoughLine> limited = new ArrayList<>();
            for (int i = 0; i < 48; i++) {
                limited.add(result.get(order.get(i)));
            }
            result = limited;
        }
        result.sort(Comparator.comparingDouble(line -> line.positionAtCenter(centerX, centerY)));
        return result;
    }

    private static double angleDifference(double a, double b) {
        double diff = Math.abs(a - b) % 180.0;
        return Math.min(diff, 180.0 - diff);
    }
}
