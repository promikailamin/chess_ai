package com.chessai.vision;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Template source backed by a directory on disk, used for trained template sets. */
public final class FileTemplateSource implements TemplateSource {
    private final Path root;

    public FileTemplateSource(Path root) {
        this.root = root;
    }

    private Path resolve(String resourcePath) {
        String path = resourcePath;
        int marker = path.indexOf("/templates/");
        if (marker >= 0) {
            path = path.substring(marker + "/templates/".length());
            int slash = path.indexOf('/');
            if (slash >= 0) {
                path = path.substring(slash + 1);
            }
        }
        return root.resolve(path);
    }

    @Override
    public GrayImage image(String resourcePath) {
        BufferedImage image = read(resourcePath);
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
    }

    @Override
    public RgbImage rgbImage(String resourcePath) {
        BufferedImage image = read(resourcePath);
        if (image == null) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                argb[y * width + x] = image.getRGB(x, y);
            }
        }
        return new RgbImage(width, height, argb);
    }

    @Override
    public String text(String resourcePath) {
        try {
            Path file = resolve(resourcePath);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }

    private BufferedImage read(String resourcePath) {
        try {
            Path file = resolve(resourcePath);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return ImageIO.read(file.toFile());
        } catch (IOException ignored) {
            return null;
        }
    }
}
