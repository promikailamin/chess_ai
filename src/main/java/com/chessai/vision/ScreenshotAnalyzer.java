package com.chessai.vision;

import com.chessai.engine.Move;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/** End-to-end screenshot analysis: board detection, piece recognition, overlay. */
public final class ScreenshotAnalyzer {
    private ScreenshotAnalyzer() {
    }

    public static Recognition analyze(File file) throws IOException {
        RasterImage image = ImageIoRaster.load(file);
        BoardDetector.Detection detection = BoardDetector.detect(image);
        PieceClassifier classifier = new PieceClassifier(TemplateSources.create());
        return classifier.recognize(image, detection);
    }

    public static Recognition analyze(File file, BoardQuad quad) throws IOException {
        RasterImage image = ImageIoRaster.load(file);
        Homography homography = Homography.fromQuad(quad);
        BoardDetector.Detection detection =
                new BoardDetector.Detection(quad, homography, 1.0, "manual");
        PieceClassifier classifier = new PieceClassifier(TemplateSources.create());
        return classifier.recognize(image, detection);
    }

    public static void saveOverlay(File source, File target, Recognition recognition, int bestMove)
            throws IOException {
        BufferedImage image = ImageIO.read(source);
        if (image == null) {
            throw new IOException("Unsupported image: " + source);
        }
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(255, 40, 40, 220));
        graphics.setStroke(new BasicStroke(3f));
        BoardQuad quad = recognition.quad;
        int[] xs = new int[4];
        int[] ys = new int[4];
        for (int i = 0; i < 4; i++) {
            xs[i] = (int) Math.round(quad.x(i));
            ys[i] = (int) Math.round(quad.y(i));
        }
        graphics.drawPolygon(xs, ys, 4);

        Homography homography = Homography.fromQuad(quad);
        graphics.setColor(new Color(30, 220, 120, 190));
        graphics.setStroke(new BasicStroke(2f));
        for (int i = 0; i <= 8; i++) {
            double[] a = homography.map(i / 8.0, 0);
            double[] b = homography.map(i / 8.0, 1);
            graphics.drawLine((int) a[0], (int) a[1], (int) b[0], (int) b[1]);
            double[] c = homography.map(0, i / 8.0);
            double[] d = homography.map(1, i / 8.0);
            graphics.drawLine((int) c[0], (int) c[1], (int) d[0], (int) d[1]);
        }

        if (bestMove != Move.NONE) {
            int from = Move.from(bestMove);
            int to = Move.to(bestMove);
            int fromRow = 7 - (from >>> 3);
            int fromCol = from & 7;
            int toRow = 7 - (to >>> 3);
            int toCol = to & 7;
            double[] a = homography.map((fromCol + 0.5) / 8.0, (fromRow + 0.5) / 8.0);
            double[] b = homography.map((toCol + 0.5) / 8.0, (toRow + 0.5) / 8.0);
            graphics.setColor(new Color(30, 120, 255, 235));
            graphics.setStroke(new BasicStroke(Math.max(6f, image.getWidth() / 180f)));
            graphics.drawLine((int) a[0], (int) a[1], (int) b[0], (int) b[1]);
            int radius = (int) Math.max(9, image.getWidth() / 110f);
            graphics.fillOval((int) b[0] - radius, (int) b[1] - radius, radius * 2, radius * 2);
        }
        graphics.dispose();
        ImageIO.write(image, "png", target);
    }
}
