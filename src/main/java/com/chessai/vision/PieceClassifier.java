package com.chessai.vision;

import com.chessai.engine.Piece;
import com.chessai.engine.Position;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the 64 squares of a detected board and turns them into a position.
 *
 * <p>Flat themes are matched with silhouette templates.  Rendered 3-D themes
 * are matched with gradient templates.  Both template families are shipped as
 * resources and can be extended by running {@code tools/generate_templates.py}.</p>
 */
public final class PieceClassifier {
    private static final int PATCH = 96;
    private static final int NORMALIZED = 48;

    private static final String[] TYPE_NAMES = {
            null, "pawn", "knight", "bishop", "rook", "queen", "king"
    };

    private static final class Template {
        final int type;
        final int color;
        final boolean[] shape;
        final boolean learned;
        final String theme;
        final double crop;

        Template(int type, boolean[] shape) {
            this(type, -1, shape, false, null, 1.0);
        }

        Template(int type, int color, boolean[] shape, boolean learned,
                 String theme, double crop) {
            this.type = type;
            this.color = color;
            this.shape = shape;
            this.learned = learned;
            this.theme = theme;
            this.crop = crop;
        }
    }

    private static final class Theme {
        final String name;
        final double crop;
        final List<Template> templates = new ArrayList<>();

        Theme(String name, double crop) {
            this.name = name;
            this.crop = crop;
        }
    }

    private static final class Match {
        int type;
        int color = -1;
        double score;
        Template template;
        boolean flat;
    }

    private static final class Cell {
        int[] rgb = new int[PATCH * PATCH];
        double[] lum = new double[PATCH * PATCH];
        double backgroundLum;
        final Map<Double, boolean[]> masks = new HashMap<>();
        final Map<Double, boolean[]> shapes = new HashMap<>();
        final Map<Double, Double> occupancies = new HashMap<>();

        boolean[] mask(double crop) {
            return masks.computeIfAbsent(crop, key -> new boolean[PATCH * PATCH]);
        }

        boolean[] shape(double crop) {
            return shapes.get(crop);
        }

        double occupancy(double crop) {
            Double value = occupancies.get(crop);
            return value == null ? 0 : value;
        }
    }

    private final List<Template> templates = new ArrayList<>();
    private final Map<String, Theme> themes = new LinkedHashMap<>();

    public PieceClassifier() {
        loadTemplates();
    }

    private void loadTemplates() {
        boolean flatOnly = Boolean.getBoolean("chessai.classifier.flatOnly");
        for (int type = Piece.PAWN; type <= Piece.KING; type++) {
            loadTemplate(type, true, "/templates/2d/" + TYPE_NAMES[type] + ".png");
            if (!flatOnly) {
                loadTemplate(type, false, "/templates/3d/" + TYPE_NAMES[type] + ".png");
            }
        }
        if (!Boolean.getBoolean("chessai.classifier.noLearned")) {
            loadLearnedTemplates();
        }
    }

    private void loadLearnedTemplates() {
        String manifest = "/templates/learned/manifest.csv";
        try (InputStream stream = getClass().getResourceAsStream(manifest)) {
            if (stream == null) {
                return;
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line = reader.readLine();
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (parts.length < 9) {
                    continue;
                }
                try {
                    String themeName = parts[0].trim();
                    int type = Integer.parseInt(parts[1].trim());
                    int color = Integer.parseInt(parts[2].trim());
                    double crop = Double.parseDouble(parts[4].trim());
                    String resource = "/templates/learned/" + parts[8].trim();
                    boolean[] shape = loadShape(resource);
                    if (shape == null) {
                        continue;
                    }
                    Theme theme = themes.computeIfAbsent(themeName,
                            key -> new Theme(key, crop));
                    theme.templates.add(new Template(type, color, shape, true,
                            themeName, crop));
                } catch (NumberFormatException ignored) {
                    // Skip malformed manifest rows rather than disabling recognition.
                }
            }
        } catch (IOException ignored) {
            // Learned templates are optional; the built-in templates still work.
        }
    }

    private boolean[] loadShape(String resource) {
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            BufferedImage image = ImageIO.read(stream);
            if (image == null) {
                return null;
            }
            boolean[] raw = new boolean[NORMALIZED * NORMALIZED];
            for (int y = 0; y < NORMALIZED; y++) {
                for (int x = 0; x < NORMALIZED; x++) {
                    int sx = x * image.getWidth() / NORMALIZED;
                    int sy = y * image.getHeight() / NORMALIZED;
                    int rgb = image.getRGB(sx, sy);
                    int r = (rgb >>> 16) & 0xff;
                    int g = (rgb >>> 8) & 0xff;
                    int b = rgb & 0xff;
                    raw[y * NORMALIZED + x] = (0.299 * r + 0.587 * g + 0.114 * b) > 127;
                }
            }
            return normalizeShape(raw, NORMALIZED);
        } catch (IOException ignored) {
            return null;
        }
    }

    private void loadTemplate(int type, boolean flat, String resource) {
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) {
                return;
            }
            BufferedImage image = ImageIO.read(stream);
            if (image == null) {
                return;
            }
            double[] values = new double[NORMALIZED * NORMALIZED];
            for (int y = 0; y < NORMALIZED; y++) {
                for (int x = 0; x < NORMALIZED; x++) {
                    int sx = x * image.getWidth() / NORMALIZED;
                    int sy = y * image.getHeight() / NORMALIZED;
                    int rgb = image.getRGB(sx, sy);
                    int r = (rgb >>> 16) & 0xff;
                    int g = (rgb >>> 8) & 0xff;
                    int b = rgb & 0xff;
                    values[y * NORMALIZED + x] = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
                }
            }
            boolean[] raw = new boolean[values.length];
            for (int i = 0; i < values.length; i++) {
                raw[i] = values[i] > 0.45;
            }
            templates.add(new Template(type, normalizeShape(raw, NORMALIZED)));
        } catch (IOException ignored) {
            // A missing optional template should not stop recognition.
        }
    }

    public Recognition recognize(RasterImage image, BoardDetector.Detection detection) {
        Cell[] cells = new Cell[64];
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                cells[row * 8 + col] = extractRaw(image, detection.unitToImage, row, col);
            }
        }
        int[][] backgrounds = estimateBackgrounds(cells);
        List<Double> crops = new ArrayList<>();
        for (Theme theme : themes.values()) {
            if (!crops.contains(theme.crop)) {
                crops.add(theme.crop);
            }
        }
        if (crops.isEmpty()) {
            crops.add(1.0);
        }
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Cell cell = cells[row * 8 + col];
                int parity = (row + col) & 1;
                for (double crop : crops) {
                    prepareCell(cell, backgrounds, parity, crop);
                }
            }
        }

        int[] board = new int[64];
        char[][] chars = new char[8][8];
        double confidenceSum = 0;
        int occupied = 0;
        String chosenTheme = "built-in";

        if (!themes.isEmpty()) {
            Theme bestTheme = null;
            double bestScore = -1;
            Match[] bestMatches = null;
            for (Theme theme : themes.values()) {
                Match[] matches = new Match[64];
                double total = 0;
                int count = 0;
                for (int index = 0; index < 64; index++) {
                    Cell cell = cells[index];
                    if (cell.occupancy(theme.crop) < 0.014) {
                        continue;
                    }
                    Match match = classifyType(cell, theme.crop, theme.templates);
                    if (match == null) {
                        continue;
                    }
                    matches[index] = match;
                    total += match.score;
                    count++;
                }
                double score = count == 0 ? 0 : total / count;
                if (Boolean.getBoolean("chessai.debug")) {
                    System.out.printf("theme-score %s=%.3f (%d cells)%n", theme.name, score, count);
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestTheme = theme;
                    bestMatches = matches;
                }
            }
            if (bestTheme != null && bestMatches != null) {
                chosenTheme = bestTheme.name;
                for (int index = 0; index < 64; index++) {
                    int row = index / 8;
                    int col = index % 8;
                    int rank = 7 - row;
                    int square = rank * 8 + col;
                    Cell cell = cells[index];
                    Match match = bestMatches[index];
                    if (Boolean.getBoolean("chessai.debug")) {
                        System.out.printf("cell r%d c%d occ=%.3f match=%s score=%.3f%n",
                                row, col, cell.occupancy(bestTheme.crop),
                                match == null ? "-" : TYPE_NAMES[match.type],
                                match == null ? 0 : match.score);
                    }
                    if (cell.occupancy(bestTheme.crop) < 0.014
                            || match == null || match.score < 0.33) {
                        board[square] = Piece.EMPTY;
                        chars[row][col] = '.';
                        continue;
                    }
                    boolean white = match.color >= 0
                            ? match.color == 0
                            : isWhite(cell, bestTheme.crop);
                    int piece = Piece.make(white ? Piece.WHITE : Piece.BLACK, match.type);
                    board[square] = piece;
                    chars[row][col] = Piece.toChar(piece);
                    confidenceSum += match.score;
                    occupied++;
                }
            }
        } else {
            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col++) {
                    int index = row * 8 + col;
                    Cell cell = cells[index];
                    int rank = 7 - row;
                    int square = rank * 8 + col;
                    if (cell.occupancy(1.0) < 0.014) {
                        board[square] = Piece.EMPTY;
                        chars[row][col] = '.';
                        continue;
                    }
                    Match match = classifyType(cell, 1.0, templates);
                    if (match == null || match.score < 0.33) {
                        board[square] = Piece.EMPTY;
                        chars[row][col] = '.';
                        continue;
                    }
                    boolean white = match.color >= 0
                            ? match.color == 0
                            : isWhite(cell, 1.0);
                    int piece = Piece.make(white ? Piece.WHITE : Piece.BLACK, match.type);
                    board[square] = piece;
                    chars[row][col] = Piece.toChar(piece);
                    confidenceSum += match.score;
                    occupied++;
                }
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
            // Keep the position playable in the GUI; the confidence tells the
            // caller that recognition was incomplete.
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

    private Cell extractRaw(RasterImage image, Homography homography, int row, int col) {
        Cell cell = new Cell();
        for (int y = 0; y < PATCH; y++) {
            for (int x = 0; x < PATCH; x++) {
                double u = (col + (x + 0.5) / PATCH) / 8.0;
                double v = (row + (y + 0.5) / PATCH) / 8.0;
                double[] point = homography.map(u, v);
                int[] rgb = image.rgbAt(point[0], point[1]);
                int index = y * PATCH + x;
                cell.rgb[index] = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
                cell.lum[index] = 0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2];
            }
        }
        return cell;
    }

    private int[][] estimateBackgrounds(Cell[] cells) {
        int[][][] r = new int[2][PATCH * PATCH][32];
        int[][][] g = new int[2][PATCH * PATCH][32];
        int[][][] b = new int[2][PATCH * PATCH][32];
        int[] counts = new int[2];
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int parity = (row + col) & 1;
                int slot = counts[parity]++;
                Cell cell = cells[row * 8 + col];
                for (int i = 0; i < PATCH * PATCH; i++) {
                    int rgb = cell.rgb[i];
                    r[parity][i][slot] = (rgb >>> 16) & 0xff;
                    g[parity][i][slot] = (rgb >>> 8) & 0xff;
                    b[parity][i][slot] = rgb & 0xff;
                }
            }
        }
        int[][] backgrounds = new int[2][PATCH * PATCH];
        for (int parity = 0; parity < 2; parity++) {
            int count = Math.max(1, counts[parity]);
            for (int i = 0; i < PATCH * PATCH; i++) {
                int rr = median(r[parity][i], count);
                int gg = median(g[parity][i], count);
                int bb = median(b[parity][i], count);
                backgrounds[parity][i] = (rr << 16) | (gg << 8) | bb;
            }
        }
        return backgrounds;
    }

    private int median(int[] values, int count) {
        int[] copy = java.util.Arrays.copyOf(values, count);
        java.util.Arrays.sort(copy);
        return copy[count / 2];
    }

    private void prepareCell(Cell cell, int[][] backgrounds, int parity, double crop) {
        int cutoff = Math.max(1, Math.min(PATCH, (int) Math.round(crop * PATCH)));
        boolean[] mask = new boolean[PATCH * PATCH];
        for (int y = 4; y < cutoff - 4; y++) {
            for (int x = 4; x < PATCH - 4; x++) {
                int index = y * PATCH + x;
                int rgb = cell.rgb[index];
                int r = (rgb >>> 16) & 0xff;
                int g = (rgb >>> 8) & 0xff;
                int b = rgb & 0xff;
                int bg = backgrounds[parity][index];
                int bgR = (bg >>> 16) & 0xff;
                int bgG = (bg >>> 8) & 0xff;
                int bgB = bg & 0xff;
                int distance = Math.max(Math.abs(r - bgR),
                        Math.max(Math.abs(g - bgG), Math.abs(b - bgB)));
                double bgLum = 0.299 * bgR + 0.587 * bgG + 0.114 * bgB;
                int lumShift = (int) Math.round(Math.abs(cell.lum[index] - bgLum));
                mask[index] = distance > 26 || lumShift > 30;
            }
        }
        mask = openClose(mask);
        mask = largestComponent(mask, cutoff);
        cell.masks.put(crop, mask);
        int area = 0;
        for (boolean value : mask) {
            if (value) {
                area++;
            }
        }
        cell.occupancies.put(crop, area / (double) (PATCH * PATCH));

        boolean[] flatMask = new boolean[NORMALIZED * NORMALIZED];
        for (int y = 0; y < NORMALIZED; y++) {
            for (int x = 0; x < NORMALIZED; x++) {
                int count = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        if (mask[(y * 2 + dy) * PATCH + (x * 2 + dx)]) {
                            count++;
                        }
                    }
                }
                flatMask[y * NORMALIZED + x] = count >= 2;
            }
        }
        cell.shapes.put(crop, normalizeShape(flatMask, NORMALIZED));
    }

    private Match classifyType(Cell cell, double crop, List<Template> candidates) {
        Match best = null;
        boolean[] shape = cell.shape(crop);
        if (shape == null) {
            return null;
        }
        for (Template template : candidates) {
            double score = dice(shape, template.shape);
            if (template.type == Piece.KNIGHT) {
                score = Math.max(score, dice(mirror(shape), template.shape));
            }
            if (best == null || score > best.score) {
                best = new Match();
                best.type = template.type;
                best.score = score;
                best.template = template;
                best.flat = template.learned;
                best.color = template.color;
            }
        }
        return best;
    }

    private boolean isWhite(Cell cell, double crop) {
        boolean[] mask = cell.mask(crop);
        double bright = 0;
        double dark = 0;
        double sum = 0;
        int count = 0;
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i]) {
                continue;
            }
            double value = cell.lum[i];
            sum += value;
            count++;
            if (value > 170) {
                bright++;
            } else if (value < 90) {
                dark++;
            }
        }
        if (count == 0) {
            return true;
        }
        double mean = sum / count;
        if (bright > dark * 1.1) {
            return true;
        }
        if (dark > bright * 1.1) {
            return false;
        }
        return mean > 125;
    }

    private static double dice(boolean[] mask, boolean[] template) {
        int intersection = 0;
        int maskArea = 0;
        int templateArea = 0;
        for (int i = 0; i < mask.length; i++) {
            if (mask[i]) {
                maskArea++;
                if (template[i]) {
                    intersection++;
                }
            }
            if (template[i]) {
                templateArea++;
            }
        }
        if (maskArea == 0 || templateArea == 0) {
            return 0;
        }
        return 2.0 * intersection / (maskArea + templateArea);
    }

    private static boolean[] mirror(boolean[] mask) {
        int size = (int) Math.round(Math.sqrt(mask.length));
        boolean[] out = new boolean[mask.length];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                out[y * size + x] = mask[y * size + (size - 1 - x)];
            }
        }
        return out;
    }

    private static boolean[] normalizeShape(boolean[] mask, int size) {
        int minX = size;
        int minY = size;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (mask[y * size + x]) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        boolean[] out = new boolean[size * size];
        if (maxX < minX || maxY < minY) {
            return out;
        }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        double scale = (size - 1.0) / Math.max(width, height);
        double offsetX = (size - width * scale) / 2.0;
        double offsetY = (size - height * scale) / 2.0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int sx = minX + (int) Math.round((x - offsetX) / scale);
                int sy = minY + (int) Math.round((y - offsetY) / scale);
                if (sx >= minX && sx <= maxX && sy >= minY && sy <= maxY) {
                    out[y * size + x] = mask[sy * size + sx];
                }
            }
        }
        return out;
    }

    private double correlation(double[] a, double[] b) {
        double meanA = 0;
        double meanB = 0;
        for (int i = 0; i < a.length; i++) {
            meanA += a[i];
            meanB += b[i];
        }
        meanA /= a.length;
        meanB /= b.length;
        double numerator = 0;
        double varianceA = 0;
        double varianceB = 0;
        for (int i = 0; i < a.length; i++) {
            double da = a[i] - meanA;
            double db = b[i] - meanB;
            numerator += da * db;
            varianceA += da * da;
            varianceB += db * db;
        }
        if (varianceA < 1e-9 || varianceB < 1e-9) {
            return 0;
        }
        return Math.max(0, numerator / Math.sqrt(varianceA * varianceB));
    }

    private boolean[] openClose(boolean[] input) {
        boolean[] eroded = erode(input);
        boolean[] opened = dilate(eroded);
        boolean[] dilated = dilate(opened);
        return erode(dilated);
    }

    private boolean[] erode(boolean[] input) {
        boolean[] out = new boolean[input.length];
        for (int y = 1; y < PATCH - 1; y++) {
            for (int x = 1; x < PATCH - 1; x++) {
                boolean keep = true;
                for (int dy = -1; dy <= 1 && keep; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (!input[(y + dy) * PATCH + (x + dx)]) {
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

    private boolean[] dilate(boolean[] input) {
        boolean[] out = new boolean[input.length];
        for (int y = 1; y < PATCH - 1; y++) {
            for (int x = 1; x < PATCH - 1; x++) {
                boolean set = false;
                for (int dy = -1; dy <= 1 && !set; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (input[(y + dy) * PATCH + (x + dx)]) {
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

    private boolean[] largestComponent(boolean[] mask, int cutoff) {
        int[] labels = new int[mask.length];
        java.util.Arrays.fill(labels, -1);
        int[] queue = new int[mask.length];
        int bestLabel = -1;
        double bestScore = -1;
        int edgeLabel = -1;
        double edgeScore = -1;
        int label = 0;
        double center = PATCH / 2.0;
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || labels[start] >= 0) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            labels[start] = label;
            int area = 0;
            double sumX = 0;
            double sumY = 0;
            int minX = PATCH;
            int maxX = -1;
            int minY = PATCH;
            int maxY = -1;
            while (head < tail) {
                int current = queue[head++];
                int x = current % PATCH;
                int y = current / PATCH;
                area++;
                sumX += x;
                sumY += y;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
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
            boolean touchesEdge = minX <= 1 || minY <= 1 || maxX >= PATCH - 2;
            if (area >= 60) {
                double cx = sumX / area;
                double cy = sumY / area;
                double score = area / (1.0 + ((cx - center) * (cx - center)
                        + (cy - center) * (cy - center)) / 250.0);
                if (!touchesEdge) {
                    if (score > bestScore) {
                        bestScore = score;
                        bestLabel = label;
                    }
                } else if (score > edgeScore) {
                    edgeScore = score;
                    edgeLabel = label;
                }
            }
            label++;
        }
        if (bestLabel < 0) {
            bestLabel = edgeLabel;
        }
        if (bestLabel < 0) {
            return mask.clone();
        }
        boolean[] out = new boolean[mask.length];
        for (int i = 0; i < mask.length; i++) {
            out[i] = labels[i] == bestLabel;
        }
        return out;
    }

    private double[] edgeMap(double[] luminance) {
        int n = (int) Math.sqrt(luminance.length);
        double[] equalized = equalizeHistogram(luminance);
        double[] gx = new double[luminance.length];
        double[] gy = new double[luminance.length];
        for (int y = 1; y < n - 1; y++) {
            for (int x = 1; x < n - 1; x++) {
                int i = y * n + x;
                gx[i] = equalized[i + 1] - equalized[i - 1];
                gy[i] = equalized[i + n] - equalized[i - n];
            }
        }
        double[] edge = new double[luminance.length];
        double[] kernel = {1, 4, 6, 4, 1};
        for (int y = 2; y < n - 2; y++) {
            for (int x = 2; x < n - 2; x++) {
                double sum = 0;
                double weight = 0;
                for (int ky = -2; ky <= 2; ky++) {
                    for (int kx = -2; kx <= 2; kx++) {
                        double w = kernel[ky + 2] * kernel[kx + 2];
                        int i = (y + ky) * n + (x + kx);
                        sum += Math.hypot(gx[i], gy[i]) * w;
                        weight += w;
                    }
                }
                edge[y * n + x] = sum / weight;
            }
        }
        double max = 0;
        for (int y = 10; y < n - 10; y++) {
            for (int x = 10; x < n - 10; x++) {
                max = Math.max(max, edge[y * n + x]);
            }
        }
        if (max < 1e-9) {
            return new double[NORMALIZED * NORMALIZED];
        }
        double[] out = new double[NORMALIZED * NORMALIZED];
        int span = n - 20;
        for (int y = 0; y < NORMALIZED; y++) {
            int sy = 10 + (int) Math.round(y * (span - 1.0) / (NORMALIZED - 1.0));
            for (int x = 0; x < NORMALIZED; x++) {
                int sx = 10 + (int) Math.round(x * (span - 1.0) / (NORMALIZED - 1.0));
                out[y * NORMALIZED + x] = edge[sy * n + sx] / max;
            }
        }
        return out;
    }

    private double[] equalizeHistogram(double[] values) {
        int[] histogram = new int[256];
        for (double value : values) {
            int bin = (int) Math.max(0, Math.min(255, Math.round(value)));
            histogram[bin]++;
        }
        int[] cdf = new int[256];
        int cumulative = 0;
        for (int i = 0; i < 256; i++) {
            cumulative += histogram[i];
            cdf[i] = cumulative;
        }
        int total = values.length;
        int cdfMin = 0;
        for (int value : cdf) {
            if (value > 0) {
                cdfMin = value;
                break;
            }
        }
        double[] mapping = new double[256];
        for (int i = 0; i < 256; i++) {
            mapping[i] = total == cdfMin ? i : 255.0 * (cdf[i] - cdfMin) / (total - cdfMin);
        }
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            int bin = (int) Math.max(0, Math.min(255, Math.round(values[i])));
            out[i] = mapping[bin];
        }
        return out;
    }

    private double median(double[] values) {
        double[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0 ? (copy[middle - 1] + copy[middle]) / 2.0 : copy[middle];
    }
}
