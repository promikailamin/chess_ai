package com.chessai.vision;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Desktop template source backed by the JVM classpath and ImageIO. */
public final class AwtTemplateSource implements TemplateSource {
    @Override
    public GrayImage image(String resourcePath) {
        try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return null;
            }
            BufferedImage image = ImageIO.read(stream);
            if (image == null) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] values = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int rgb = image.getRGB(x, y);
                    int r = (rgb >>> 16) & 0xff;
                    int g = (rgb >>> 8) & 0xff;
                    int b = rgb & 0xff;
                    values[y * width + x] = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
                }
            }
            return new GrayImage(width, height, values);
        } catch (IOException ignored) {
            return null;
        }
    }

    @Override
    public String text(String resourcePath) {
        try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (IOException ignored) {
            return null;
        }
    }
}
