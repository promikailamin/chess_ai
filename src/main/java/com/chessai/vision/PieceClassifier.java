package com.chessai.vision;

import com.chessai.engine.Piece;
import com.chessai.engine.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the 64 squares of a detected board and turns them into a position.
 *
 * <p>Each cell uses its own background estimate, so pieces on a crowded board
 * do not contaminate one another.  Recognition compares both the color and the
 * alpha shape of the bundled theme sprites, and rejects selection brackets and
 * other overlays that do not line up with a piece silhouette.</p>
 */
public final class PieceClassifier {
    private static final int PATCH = 96;
    private static final int MATCH = 48;
    private static final double ALPHA_CUTOFF = 0.25;

    private static final String[] TYPE_NAMES = {
            null, "pawn", "knight", "bishop", "rook", "queen", "king"
    };

    private static final class ColorTemplate {
        final int type;
        final int color;
        final String theme;
        final float[] rgb;
        final float[] alpha;
        final int bboxCenterX;
        final int bboxCenterY;

        ColorTemplate(int type, int color, String theme, float[] rgb, float[] alpha,
                      int bboxCenterX, int bboxCenterY) {
            this.type = type;
            this.color = color;
            this.theme = theme;
            this.rgb = rgb;
            this.alpha = alpha;
            this.bboxCenterX = bboxCenterX;
            this.bboxCenterY = bboxCenterY;
        }
    }

    private static final class ColorTheme {
        final String name;
        final List<ColorTemplate> templates = new ArrayList<>();
        final List<double[]> backgrounds = new ArrayList<>();
        final List<double[]> foregrounds = new ArrayList<>();

        ColorTheme(String name) {
            this.name = name;
        }
    }

    private static final class ColorMatch {
        ColorTemplate template;
        double score;
        double coverage;
        double iou;
    }

    private static final class Cell {
        final int[] rgb = new int[PATCH * PATCH];
        int backgroundR;
        int backgroundG;
        int backgroundB;
        boolean[] mask;
        double area;
        double core;
        double largestRatio;
        boolean safe;
        double[] downRgb;
        boolean[] downMask;
        int downMaskCount;
        int minX;
        int minY;
        int maxX;
        int maxY;
        int bboxCenterX;
        int bboxCenterY;

        boolean hasShape() {
            return maxX >= minX && maxY >= minY;
        }
    }

    private final Map<String, ColorTheme> themes = new LinkedHashMap<>();
    private final TemplateSource source;
    private String chosenTheme = "color";

    public PieceClassifier(TemplateSource source) {
        if (source == null) {
            throw new IllegalArgumentException("Template source is required");
        }
        this.source = source;
        loadTemplates();
    }

    private void loadTemplates() {
        String manifest = source.text("/templates/color/manifest.csv");
        if (manifest == null) {
            return;
        }
        for (String line : manifest.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("theme,")) {
                continue;
            }
            String[] parts = line.split(",", -1);
            if (parts.length < 10) {
                continue;
            }
            try {
                String themeName = parts[0].trim();
                int type = Integer.parseInt(parts[1].trim());
                int color = Integer.parseInt(parts[2].trim());
                String resource = "/templates/color/" + parts[3].trim();
                double[] background = {
                        Double.parseDouble(parts[4].trim()),
                        Double.parseDouble(parts[5].trim()),
                        Double.parseDouble(parts[6].trim())
                };
                double[] foreground = {
                        Double.parseDouble(parts[7].trim()),
                        Double.parseDouble(parts[8].trim()),
                        Double.parseDouble(parts[9].trim())
                };
                RgbImage image = source.rgbImage(resource);
                if (image == null) {
                    continue;
                }
                ColorTemplate template = makeTemplate(type, color, themeName, image);
                if (template == null) {
                    continue;
                }
                ColorTheme theme = themes.computeIfAbsent(themeName, ColorTheme::new);
                theme.templates.add(template);
                theme.backgrounds.add(background);
                theme.foregrounds.add(foreground);
            } catch (NumberFormatException ignored) {
                // Skip malformed manifest rows rather than disabling recognition.
            }
        }
    }

    private ColorTemplate makeTemplate(int type, int color, String theme, RgbImage image) {
        float[] rgb = new float[MATCH * MATCH * 3];
        float[] alpha = new float[MATCH * MATCH];
        int minX = MATCH;
        int minY = MATCH;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < MATCH; y++) {
            for (int x = 0; x < MATCH; x++) {
                int argb = sampleArgb(image, x, y);
                int index = y * MATCH + x;
                rgb[index * 3] = (argb >>> 16) & 0xff;
                rgb[index * 3 + 1] = (argb >>> 8) & 0xff;
                rgb[index * 3 + 2] = argb & 0xff;
                float a = ((argb >>> 24) & 0xff) / 255.0f;
                alpha[index] = a;
                if (a > ALPHA_CUTOFF) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return null;
        }
        return new ColorTemplate(type, color, theme, rgb, alpha, minX + maxX, minY + maxY);
    }

    private int sampleArgb(RgbImage image, int x, int y) {
        if (image.width == MATCH && image.height == MATCH) {
            return image.argbAt(x, y);
        }
        double sourceX = (x + 0.5) * image.width / MATCH - 0.5;
        double sourceY = (y + 0.5) * image.height / MATCH - 0.5;
        int x0 = (int) Math.floor(sourceX);
        int y0 = (int) Math.floor(sourceY);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        double fx = sourceX - x0;
        double fy = sourceY - y0;
        int p00 = image.argbAt(x0, y0);
        int p10 = image.argbAt(x1, y0);
        int p01 = image.argbAt(x0, y1);
        int p11 = image.argbAt(x1, y1);
        int out = 0;
        for (int shift = 24; shift >= 0; shift -= 8) {
            double c00 = (p00 >>> shift) & 0xff;
            double c10 = (p10 >>> shift) & 0xff;
            double c01 = (p01 >>> shift) & 0xff;
            double c11 = (p11 >>> shift) & 0xff;
            double top = c00 * (1 - fx) + c10 * fx;
            double bottom = c01 * (1 - fx) + c11 * fx;
            int value = (int) Math.round(top * (1 - fy) + bottom * fy);
            out |= (value & 0xff) << shift;
        }
        return out;
    }

    public Recognition recognize(RasterImage image, BoardDetector.Detection detection) {
        Cell[] cells = new Cell[64];
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                cells[row * 8 + col] = extractCell(image, detection.unitToImage, row, col);
            }
        }

        ColorTheme theme = chooseTheme(cells);
        int[] board = new int[64];
        char[][] chars = new char[8][8];
        double confidenceSum = 0;
        int occupied = 0;

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int index = row * 8 + col;
                int rank = 7 - row;
                int square = rank * 8 + col;
                Cell cell = cells[index];
                ColorMatch match = null;
                boolean occupiedCell = false;
                if (theme != null && cell.area >= 0.015) {
                    match = match(cell, theme);
                    if (match != null) {
                        boolean safeShape = cell.core >= 0.02 && cell.largestRatio >= 0.3;
                        boolean alignedShape = match.iou >= 0.08 && match.score >= 0.86;
                        occupiedCell = cell.area >= 0.02 && (safeShape || alignedShape);
                    }
                }
                if (Boolean.getBoolean("chessai.debug")) {
                    System.out.printf("cell r%d c%d area=%.3f core=%.3f lr=%.3f theme=%s "
                                    + "match=%s score=%.3f iou=%.3f occ=%s%n",
                            row, col, cell.area, cell.core, cell.largestRatio,
                            theme == null ? "-" : theme.name,
                            match == null ? "-" : TYPE_NAMES[match.template.type],
                            match == null ? 0 : match.score,
                            match == null ? 0 : match.iou,
                            occupiedCell);
                }
                if (!occupiedCell || match == null || match.score < 0.33) {
                    board[square] = Piece.EMPTY;
                    chars[row][col] = '.';
                    continue;
                }
                boolean white = match.template.color == 0;
                int piece = Piece.make(white ? Piece.WHITE : Piece.BLACK, match.template.type);
                board[square] = piece;
                chars[row][col] = Piece.toChar(piece);
                confidenceSum += match.score;
                occupied++;
            }
        }

        if (Boolean.getBoolean("chessai.debug")) {
            System.out.println("theme=" + chosenTheme);
        }

        Position position = Position.fromFen("8/8/8/8/8/8/8/8 w - - 0 1");
        for (int square = 0; square < 64; square++) {
            if (board[square] != Piece.EMPTY) {
                position.setPiece(square, board[square]);
            }
        }
        position.setSide(Piece.WHITE);
        position.setCastling(inferCastling(board));
        int whiteKings = countKing(board, Piece.WHITE);
        int blackKings = countKing(board, Piece.BLACK);
        if (whiteKings == 0 || blackKings == 0) {
            if (whiteKings == 0) {
                placeMissingKing(position, Piece.WHITE);
            }
            if (blackKings == 0) {
                placeMissingKing(position, Piece.BLACK);
            }
        }
        position = Position.fromFen(position.toFen());
        double confidence = occupied == 0 ? 0 : confidenceSum / occupied;
        return new Recognition(position, confidence, detection.score, detection.method,
                detection.quad, chars);
    }

    private ColorTheme chooseTheme(Cell[] cells) {
        if (themes.isEmpty()) {
            chosenTheme = "none";
            return null;
        }
        List<ColorTheme> candidates = rankThemes(cells);
        ColorTheme best = null;
        double bestScore = -1;
        for (ColorTheme theme : candidates) {
            double total = 0;
            int count = 0;
            for (Cell cell : cells) {
                if (!cell.safe) {
                    continue;
                }
                ColorMatch match = match(cell, theme);
                if (match != null) {
                    total += match.score;
                    count++;
                }
            }
            double score = count == 0 ? 0 : total / count;
            if (Boolean.getBoolean("chessai.debug")) {
                System.out.printf("theme-score %s=%.3f (%d cells)%n", theme.name, score, count);
            }
            if (score > bestScore) {
                bestScore = score;
                best = theme;
            }
        }
        if (best == null) {
            best = candidates.get(0);
        }
        chosenTheme = best.name;
        return best;
    }

    private List<ColorTheme> rankThemes(Cell[] cells) {
        double[][] backgrounds = new double[64][3];
        List<double[]> foregroundList = new ArrayList<>();
        for (int i = 0; i < cells.length; i++) {
            Cell cell = cells[i];
            backgrounds[i][0] = cell.backgroundR;
            backgrounds[i][1] = cell.backgroundG;
            backgrounds[i][2] = cell.backgroundB;
            if (cell.area >= 0.02 && cell.core >= 0.02) {
                foregroundList.add(meanMaskedColor(cell));
            }
        }
        java.util.Arrays.sort(backgrounds, Comparator.comparingDouble(PieceClassifier::luminance));
        double[] darkBackground = mean(backgrounds, 0, 32);
        double[] lightBackground = mean(backgrounds, 32, 64);

        double[] darkForeground = {128, 128, 128};
        double[] lightForeground = {128, 128, 128};
        if (!foregroundList.isEmpty()) {
            foregroundList.sort(Comparator.comparingDouble(PieceClassifier::luminance));
            int quarter = Math.max(1, foregroundList.size() / 4);
            darkForeground = mean(foregroundList, 0, quarter);
            lightForeground = mean(foregroundList,
                    foregroundList.size() - quarter, foregroundList.size());
        }

        List<ThemeScore> scores = new ArrayList<>();
        for (ColorTheme theme : themes.values()) {
            double backgroundScore = (nearest(darkBackground, theme.backgrounds)
                    + nearest(lightBackground, theme.backgrounds)) / 2.0;
            double foregroundScore = (nearest(lightForeground, theme.foregrounds)
                    + nearest(darkForeground, theme.foregrounds)) / 2.0;
            scores.add(new ThemeScore(theme, backgroundScore * 0.45 + foregroundScore * 0.55));
        }
        scores.sort(Comparator.comparingDouble(score -> score.score));
        List<ColorTheme> result = new ArrayList<>();
        int limit = Math.min(5, scores.size());
        for (int i = 0; i < limit; i++) {
            result.add(scores.get(i).theme);
        }
        return result;
    }

    private static final class ThemeScore {
        final ColorTheme theme;
        final double score;

        ThemeScore(ColorTheme theme, double score) {
            this.theme = theme;
            this.score = score;
        }
    }

    private double[] meanMaskedColor(Cell cell) {
        double red = 0;
        double green = 0;
        double blue = 0;
        int count = 0;
        for (int i = 0; i < cell.mask.length; i++) {
            if (!cell.mask[i]) {
                continue;
            }
            int rgb = cell.rgb[i];
            red += (rgb >>> 16) & 0xff;
            green += (rgb >>> 8) & 0xff;
            blue += rgb & 0xff;
            count++;
        }
        if (count == 0) {
            return new double[]{cell.backgroundR, cell.backgroundG, cell.backgroundB};
        }
        return new double[]{red / count, green / count, blue / count};
    }

    private static double nearest(double[] color, List<double[]> palette) {
        double best = Double.MAX_VALUE;
        for (double[] candidate : palette) {
            double dr = color[0] - candidate[0];
            double dg = color[1] - candidate[1];
            double db = color[2] - candidate[2];
            best = Math.min(best, Math.sqrt(dr * dr + dg * dg + db * db));
        }
        return best == Double.MAX_VALUE ? 255 : best;
    }

    private static double luminance(double[] color) {
        return 0.299 * color[0] + 0.587 * color[1] + 0.114 * color[2];
    }

    private static double[] mean(double[][] colors, int from, int to) {
        double[] out = new double[3];
        if (to <= from) {
            return out;
        }
        for (int i = from; i < to; i++) {
            out[0] += colors[i][0];
            out[1] += colors[i][1];
            out[2] += colors[i][2];
        }
        out[0] /= to - from;
        out[1] /= to - from;
        out[2] /= to - from;
        return out;
    }

    private static double[] mean(List<double[]> colors, int from, int to) {
        double[] out = new double[3];
        if (to <= from) {
            return out;
        }
        for (int i = from; i < to; i++) {
            double[] color = colors.get(i);
            out[0] += color[0];
            out[1] += color[1];
            out[2] += color[2];
        }
        out[0] /= to - from;
        out[1] /= to - from;
        out[2] /= to - from;
        return out;
    }

    private Cell extractCell(RasterImage image, Homography homography, int row, int col) {
        Cell cell = new Cell();
        double[] luminance = new double[PATCH * PATCH];
        for (int y = 0; y < PATCH; y++) {
            for (int x = 0; x < PATCH; x++) {
                double u = (col + (x + 0.5) / PATCH) / 8.0;
                double v = (row + (y + 0.5) / PATCH) / 8.0;
                double[] point = homography.map(u, v);
                int[] rgb = image.rgbAt(point[0], point[1]);
                int index = y * PATCH + x;
                cell.rgb[index] = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
                luminance[index] = 0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2];
            }
        }
        estimateBackground(cell);
        boolean[] mask = buildMask(cell, luminance);
        cell.mask = mask;
        int area = 0;
        int core = 0;
        for (int y = 0; y < PATCH; y++) {
            for (int x = 0; x < PATCH; x++) {
                if (mask[y * PATCH + x]) {
                    area++;
                    if (x >= 34 && x < 62 && y >= 34 && y < 62) {
                        core++;
                    }
                }
            }
        }
        cell.area = area / (double) (PATCH * PATCH);
        cell.core = core / (28.0 * 28.0);
        int largest = largestComponentArea(mask);
        cell.largestRatio = area == 0 ? 0 : largest / (double) area;
        cell.safe = cell.area >= 0.02 && cell.core >= 0.02 && cell.largestRatio >= 0.3;
        prepareMatchImage(cell);
        return cell;
    }

    private void estimateBackground(Cell cell) {
        int[] red = new int[256];
        int[] green = new int[256];
        int[] blue = new int[256];
        int count = 0;
        for (int y = 0; y < PATCH; y++) {
            for (int x = 0; x < PATCH; x++) {
                if (x < 3 || y < 3 || x >= PATCH - 3 || y >= PATCH - 3) {
                    continue;
                }
                if (x >= 8 && x < PATCH - 8 && y >= 8 && y < PATCH - 8) {
                    continue;
                }
                int rgb = cell.rgb[y * PATCH + x];
                red[(rgb >>> 16) & 0xff]++;
                green[(rgb >>> 8) & 0xff]++;
                blue[rgb & 0xff]++;
                count++;
            }
        }
        cell.backgroundR = histogramMedian(red, count);
        cell.backgroundG = histogramMedian(green, count);
        cell.backgroundB = histogramMedian(blue, count);
    }

    private int histogramMedian(int[] histogram, int count) {
        if (count == 0) {
            return 128;
        }
        int left = (count - 1) / 2;
        int right = count / 2;
        int seen = 0;
        int leftValue = 0;
        int rightValue = 0;
        for (int i = 0; i < histogram.length; i++) {
            seen += histogram[i];
            if (seen > left && leftValue == 0) {
                leftValue = i;
            }
            if (seen > right) {
                rightValue = i;
                break;
            }
        }
        return (leftValue + rightValue) / 2;
    }

    private boolean[] buildMask(Cell cell, double[] luminance) {
        boolean[] raw = new boolean[PATCH * PATCH];
        double backgroundLuminance = 0.299 * cell.backgroundR
                + 0.587 * cell.backgroundG + 0.114 * cell.backgroundB;
        for (int y = 4; y < PATCH - 4; y++) {
            for (int x = 4; x < PATCH - 4; x++) {
                int index = y * PATCH + x;
                int rgb = cell.rgb[index];
                int r = (rgb >>> 16) & 0xff;
                int g = (rgb >>> 8) & 0xff;
                int b = rgb & 0xff;
                int distance = Math.max(Math.abs(r - cell.backgroundR),
                        Math.max(Math.abs(g - cell.backgroundG),
                                Math.abs(b - cell.backgroundB)));
                double lumShift = Math.abs(luminance[index] - backgroundLuminance);
                raw[index] = distance > 30 || lumShift > 34;
            }
        }
        boolean[] opened = dilate(erode(raw, 1), 1);
        return erode(dilate(opened, 2), 2);
    }

    private static boolean[] erode(boolean[] input, int radius) {
        boolean[] out = new boolean[input.length];
        for (int y = 0; y < PATCH; y++) {
            for (int x = 0; x < PATCH; x++) {
                boolean keep = true;
                for (int dy = -radius; dy <= radius && keep; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= PATCH || ny >= PATCH
                                || !input[ny * PATCH + nx]) {
                            keep = false;
                            break;
                        }
                    }
                }
                out[y * PATCH + x] = keep;
            }
        }
        return out;
    }

    private static boolean[] dilate(boolean[] input, int radius) {
        boolean[] out = new boolean[input.length];
        for (int y = 0; y < PATCH; y++) {
            for (int x = 0; x < PATCH; x++) {
                boolean set = false;
                for (int dy = -radius; dy <= radius && !set; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx >= 0 && ny >= 0 && nx < PATCH && ny < PATCH
                                && input[ny * PATCH + nx]) {
                            set = true;
                            break;
                        }
                    }
                }
                out[y * PATCH + x] = set;
            }
        }
        return out;
    }

    private int largestComponentArea(boolean[] mask) {
        int[] labels = new int[mask.length];
        java.util.Arrays.fill(labels, -1);
        int[] queue = new int[mask.length];
        int best = 0;
        int label = 0;
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || labels[start] >= 0) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            labels[start] = label;
            int area = 0;
            while (head < tail) {
                int current = queue[head++];
                int x = current % PATCH;
                int y = current / PATCH;
                area++;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= PATCH || ny >= PATCH) {
                            continue;
                        }
                        int next = ny * PATCH + nx;
                        if (mask[next] && labels[next] < 0) {
                            labels[next] = label;
                            queue[tail++] = next;
                        }
                    }
                }
            }
            best = Math.max(best, area);
            label++;
        }
        return best;
    }

    private void prepareMatchImage(Cell cell) {
        cell.downRgb = new double[MATCH * MATCH * 3];
        for (int y = 0; y < MATCH; y++) {
            double sourceY = (y + 0.5) * PATCH / MATCH - 0.5;
            int y0 = (int) Math.floor(sourceY);
            int y1 = y0 + 1;
            double fy = sourceY - y0;
            for (int x = 0; x < MATCH; x++) {
                double sourceX = (x + 0.5) * PATCH / MATCH - 0.5;
                int x0 = (int) Math.floor(sourceX);
                int x1 = x0 + 1;
                double fx = sourceX - x0;
                int index = y * MATCH + x;
                for (int channel = 0; channel < 3; channel++) {
                    int shift = 16 - channel * 8;
                    double c00 = (cell.rgb[clampedIndex(x0, y0)] >>> shift) & 0xff;
                    double c10 = (cell.rgb[clampedIndex(x1, y0)] >>> shift) & 0xff;
                    double c01 = (cell.rgb[clampedIndex(x0, y1)] >>> shift) & 0xff;
                    double c11 = (cell.rgb[clampedIndex(x1, y1)] >>> shift) & 0xff;
                    double top = c00 * (1 - fx) + c10 * fx;
                    double bottom = c01 * (1 - fx) + c11 * fx;
                    cell.downRgb[index * 3 + channel] = top * (1 - fy) + bottom * fy;
                }
            }
        }

        cell.downMask = new boolean[MATCH * MATCH];
        cell.minX = MATCH;
        cell.minY = MATCH;
        cell.maxX = -1;
        cell.maxY = -1;
        cell.downMaskCount = 0;
        for (int y = 0; y < MATCH; y++) {
            int sourceY = (int) Math.floor((y + 0.5) * PATCH / MATCH);
            for (int x = 0; x < MATCH; x++) {
                int sourceX = (int) Math.floor((x + 0.5) * PATCH / MATCH);
                if (cell.mask[clampedIndex(sourceX, sourceY)]) {
                    cell.downMask[y * MATCH + x] = true;
                    cell.downMaskCount++;
                    if (x < cell.minX) cell.minX = x;
                    if (x > cell.maxX) cell.maxX = x;
                    if (y < cell.minY) cell.minY = y;
                    if (y > cell.maxY) cell.maxY = y;
                }
            }
        }
        cell.bboxCenterX = cell.minX + cell.maxX;
        cell.bboxCenterY = cell.minY + cell.maxY;
    }

    private static int clampedIndex(int x, int y) {
        int cx = Math.max(0, Math.min(PATCH - 1, x));
        int cy = Math.max(0, Math.min(PATCH - 1, y));
        return cy * PATCH + cx;
    }

    private ColorMatch match(Cell cell, ColorTheme theme) {
        if (!cell.hasShape() || cell.downMaskCount == 0) {
            return null;
        }
        ColorMatch best = null;
        for (ColorTemplate template : theme.templates) {
            int dx = (int) Math.round((cell.bboxCenterX - template.bboxCenterX) / 2.0);
            int dy = (int) Math.round((cell.bboxCenterY - template.bboxCenterY) / 2.0);
            dx = Math.max(-4, Math.min(4, dx));
            dy = Math.max(-4, Math.min(4, dy));
            for (int pass = 0; pass < 2; pass++) {
                int shiftX = pass == 0 ? 0 : dx;
                int shiftY = pass == 0 ? 0 : dy;
                if (pass == 1 && shiftX == 0 && shiftY == 0) {
                    continue;
                }
                ColorMatch current = score(cell, template, shiftX, shiftY);
                if (current != null && (best == null || current.score > best.score)) {
                    best = current;
                }
            }
        }
        return best;
    }

    private ColorMatch score(Cell cell, ColorTemplate template, int shiftX, int shiftY) {
        double difference = 0;
        int count = 0;
        int intersection = 0;
        int templateArea = 0;
        for (int y = 0; y < MATCH; y++) {
            int sy = Math.floorMod(y - shiftY, MATCH);
            for (int x = 0; x < MATCH; x++) {
                int sx = Math.floorMod(x - shiftX, MATCH);
                int templateIndex = sy * MATCH + sx;
                float alpha = template.alpha[templateIndex];
                if (alpha <= ALPHA_CUTOFF) {
                    continue;
                }
                templateArea++;
                int index = y * MATCH + x;
                if (cell.downMask[index]) {
                    intersection++;
                }
                double a = alpha;
                double red = template.rgb[templateIndex * 3] * a
                        + cell.backgroundR * (1 - a);
                double green = template.rgb[templateIndex * 3 + 1] * a
                        + cell.backgroundG * (1 - a);
                double blue = template.rgb[templateIndex * 3 + 2] * a
                        + cell.backgroundB * (1 - a);
                difference += Math.abs(cell.downRgb[index * 3] - red);
                difference += Math.abs(cell.downRgb[index * 3 + 1] - green);
                difference += Math.abs(cell.downRgb[index * 3 + 2] - blue);
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        ColorMatch match = new ColorMatch();
        match.template = template;
        match.score = 1 - difference / (count * 3.0 * 255.0);
        match.coverage = intersection / (double) cell.downMaskCount;
        int union = cell.downMaskCount + templateArea - intersection;
        match.iou = union == 0 ? 0 : intersection / (double) union;
        return match;
    }

    private int inferCastling(int[] board) {
        int rights = 0;
        if (board[4] == Piece.make(Piece.WHITE, Piece.KING)) {
            if (board[7] == Piece.make(Piece.WHITE, Piece.ROOK)) {
                rights |= Position.CASTLE_WHITE_KING;
            }
            if (board[0] == Piece.make(Piece.WHITE, Piece.ROOK)) {
                rights |= Position.CASTLE_WHITE_QUEEN;
            }
        }
        if (board[60] == Piece.make(Piece.BLACK, Piece.KING)) {
            if (board[63] == Piece.make(Piece.BLACK, Piece.ROOK)) {
                rights |= Position.CASTLE_BLACK_KING;
            }
            if (board[56] == Piece.make(Piece.BLACK, Piece.ROOK)) {
                rights |= Position.CASTLE_BLACK_QUEEN;
            }
        }
        return rights;
    }

    private int countKing(int[] board, int color) {
        int king = Piece.make(color, Piece.KING);
        int count = 0;
        for (int piece : board) {
            if (piece == king) {
                count++;
            }
        }
        return count;
    }

    private void placeMissingKing(Position position, int color) {
        int preferred = color == Piece.WHITE ? 4 : 60;
        if (position.pieceAt(preferred) == Piece.EMPTY) {
            position.setPiece(preferred, Piece.make(color, Piece.KING));
            return;
        }
        for (int square = 0; square < 64; square++) {
            if (position.pieceAt(square) == Piece.EMPTY) {
                position.setPiece(square, Piece.make(color, Piece.KING));
                return;
            }
        }
    }
}
