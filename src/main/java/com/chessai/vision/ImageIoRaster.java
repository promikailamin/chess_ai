package com.chessai.vision;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/** Desktop helpers for converting between files/buffered images and {@link RasterImage}. */
public final class ImageIoRaster {
    private ImageIoRaster() {
    }

    public static RasterImage load(File file) throws IOException {
        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IOException("Unsupported image: " + file);
        }
        return fromBufferedImage(image);
    }

    public static RasterImage fromBufferedImage(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        return new RasterImage(w, h, pixels);
    }

    public static BufferedImage toBufferedImage(RasterImage image) {
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, image.getWidth(), image.getHeight(), image.pixels(), 0, image.getWidth());
        return result;
    }
}
