package com.chessai.vision;

import com.chessai.engine.Piece;
import com.chessai.engine.Position;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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
        final boolean flat;
        final double[] values;
        final boolean[] shape;
        final int area;

        Template(int type, boolean flat, double[] values) {
            this.type = type;
            this.flat = flat;
            this.values = values;
            boolean[] raw = new boolean[values.length];
            int count = 0;
            for (int i = 0; i < values.length; i++) {
                raw[i] = values[i] > 0.45;
                if (raw[i]) {
                    count++;
                }
            }
            this.area = count;
            this.shape = normalizeShape(raw, NORMALIZED);
        }
    }

    private static final class Match {
        int type;
        double score;
        Template template;
        boolean flat;
    }

    private static final class Cell {
        int[] rgb = new int[PATCH * PATCH];
        double[] lum = new double[PATCH * PATCH];
        boolean[] mask = new boolean[PATCH * PATCH];
        boolean[] flatMask = new boolean[NORMALIZED * NORMALIZED];
        boolean[] normalizedMask = new boolean[NORMALIZED * NORMALIZED];
        double[] edge = new double[NORMALIZED * NORMALIZED];
        double occupancy;
        double backgroundLum;
    }

    private final List<Template> templates = new ArrayList<>();

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
            templates.add(new Template(type, flat, values));
        } catch (IOException ignored) {
            // A missing optional template should not stop recognition.
        }
    }

    public Recognition recognize(RasterImage image, BoardDetector.Detection detection) {
        int[] board = new int[64];
        char[][] chars = new char[8][8];
        double confidenceSum = 0;
        int occupied = 0;

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Cell cell = extract(image, detection.unitToImage, row, col);
                if (Boolean.getBoolean("chessai.debug")) {
                    Match debugMatch = classifyType(cell);
                    System.out.printf("cell r%d c%d occ=%.3f bg=%.1f match=%s score=%.3f%n",
                            row, col, cell.occupancy, cell.backgroundLum,
                            debugMatch == null ? "-" : TYPE_NAMES[debugMatch.type],
                            debugMatch == null ? 0 : debugMatch.score);
                }
                int rank = 7 - row;
                int square = rank * 8 + col;
                if (cell.occupancy < 0.014) {
                    board[square] = Piece.EMPTY;
                    chars[row][col] = '.';
                    continue;
                }
                Match match = classifyType(cell);
                if (match == null || match.score < 0.33) {
                    board[square] = Piece.EMPTY;
                    chars[row][col] = '.';
                    continue;
                }
                boolean white = isWhite(cell, match);
                int piece = Piece.make(white ? Piece.WHITE : Piece.BLACK, match.type);
                board[square] = piece;
                chars[row][col] = Piece.toChar(piece);
                confidenceSum += match.score;
                occupied++;
            }
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

    private Cell extract(RasterImage image, Homography homography, int row, int col) {
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

        int margin = 6;
        int span = PATCH - margin * 2;
        double[] borderR = new double[span * span];
        double[] borderG = new double[span * span];
        double[] borderB = new double[span * span];
        int borderIndex = 0;
        for (int y = margin; y < PATCH - margin; y++) {
            for (int x = margin; x < PATCH - margin; x++) {
                int rgb = cell.rgb[y * PATCH + x];
                borderR[borderIndex] = (rgb >>> 16) & 0xff;
                borderG[borderIndex] = (rgb >>> 8) & 0xff;
                borderB[borderIndex] = rgb & 0xff;
                borderIndex++;
            }
        }
        double bgR = median(borderR);
        double bgG = median(borderG);
        double bgB = median(borderB);
        cell.backgroundLum = 0.299 * bgR + 0.587 * bgG + 0.114 * bgB;

        boolean[] mask = new boolean[PATCH * PATCH];
        for (int y = 4; y < PATCH - 4; y++) {
            for (int x = 4; x < PATCH - 4; x++) {
                int index = y * PATCH + x;
                int rgb = cell.rgb[index];
                int r = (rgb >>> 16) & 0xff;
                int g = (rgb >>> 8) & 0xff;
                int b = rgb & 0xff;
                double distance = Math.max(Math.abs(r - bgR),
                        Math.max(Math.abs(g - bgG), Math.abs(b - bgB)));
                double lumShift = Math.abs(cell.lum[index] - cell.backgroundLum);
                mask[index] = distance > 30 || lumShift > 34;
            }
        }
        mask = openClose(mask);
        mask = largestComponent(mask);
        cell.mask = mask;
        int area = 0;
        for (boolean value : mask) {
            if (value) {
                area++;
            }
        }
        cell.occupancy = area / (double) (PATCH * PATCH);

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
                cell.flatMask[y * NORMALIZED + x] = count >= 2;
            }
        }
        cell.normalizedMask = normalizeShape(cell.flatMask, NORMALIZED);
        cell.edge = edgeMap(cell.lum);
        return cell;
    }

    private Match classifyType(Cell cell) {
        Match best = null;
        if (Boolean.getBoolean("chessai.debug.classify")) {
            StringBuilder sb = new StringBuilder("scores:");
            for (Template template : templates) {
                if (template.flat) {
                    sb.append(' ').append(TYPE_NAMES[template.type]).append('=')
                            .append(String.format("%.3f", dice(cell.normalizedMask, template.shape)));
                }
            }
            System.out.println(sb);
        }
        for (Template template : templates) {
            double score;
            if (template.flat) {
                score = dice(cell.normalizedMask, template.shape);
                if (template.type == Piece.KNIGHT) {
                    score = Math.max(score, dice(mirror(cell.normalizedMask), template.shape));
                }
            } else {
                score = correlation(cell.edge, template.values) * 0.92;
            }
            if (best == null || score > best.score) {
                best = new Match();
                best.type = template.type;
                best.score = score;
                best.template = template;
                best.flat = template.flat;
            }
        }
        return best;
    }

    private boolean isWhite(Cell cell, Match match) {
        double bright = 0;
        double dark = 0;
        double sum = 0;
        int count = 0;
        for (int i = 0; i < cell.mask.length; i++) {
            if (!cell.mask[i]) {
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

    private boolean[] largestComponent(boolean[] mask) {
        int[] labels = new int[mask.length];
        java.util.Arrays.fill(labels, -1);
        int[] queue = new int[mask.length];
        int bestLabel = -1;
        double bestScore = -1;
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
            boolean touchesEdge = minX <= 1 || minY <= 1 || maxX >= PATCH - 2 || maxY >= PATCH - 2;
            if (area >= 60 && !touchesEdge) {
                double cx = sumX / area;
                double cy = sumY / area;
                double score = area / (1.0 + ((cx - center) * (cx - center)
                        + (cy - center) * (cy - center)) / 250.0);
                if (score > bestScore) {
                    bestScore = score;
                    bestLabel = label;
                }
            }
            label++;
        }
        boolean[] out = new boolean[mask.length];
        if (bestLabel >= 0) {
            for (int i = 0; i < mask.length; i++) {
                out[i] = labels[i] == bestLabel;
            }
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
