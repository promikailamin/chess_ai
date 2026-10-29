package com.chessai.vision;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/** Simple ARGB raster used by the image-processing pipeline. */
public final class RasterImage {
    private final int width;
    private final int height;
    private final int[] pixels;

    public RasterImage(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    public static RasterImage load(File file) throws IOException {
        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IOException("Unsupported image: " + file);
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        return new RasterImage(w, h, pixels);
    }

    public static RasterImage fromBufferedImage(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        return new RasterImage(w, h, image.getRGB(0, 0, w, h, null, 0, w));
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int[] pixels() {
        return pixels;
    }

    public int getRgb(int x, int y) {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x >= width) x = width - 1;
        if (y >= height) y = height - 1;
        return pixels[y * width + x];
    }

    public int getRgbClamped(int x, int y) {
        return getRgb(x, y);
    }

    public double luminance(int x, int y) {
        int rgb = getRgb(x, y);
        int r = (rgb >>> 16) & 0xff;
        int g = (rgb >>> 8) & 0xff;
        int b = rgb & 0xff;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }

    public int[] rgbAt(double x, double y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        double fx = x - x0;
        double fy = y - y0;
        int p00 = getRgb(x0, y0);
        int p10 = getRgb(x1, y0);
        int p01 = getRgb(x0, y1);
        int p11 = getRgb(x1, y1);
        int[] out = new int[3];
        for (int channel = 0; channel < 3; channel++) {
            int shift = 16 - channel * 8;
            double c00 = (p00 >>> shift) & 0xff;
            double c10 = (p10 >>> shift) & 0xff;
            double c01 = (p01 >>> shift) & 0xff;
            double c11 = (p11 >>> shift) & 0xff;
            double top = c00 * (1 - fx) + c10 * fx;
            double bottom = c01 * (1 - fx) + c11 * fx;
            out[channel] = (int) Math.round(top * (1 - fy) + bottom * fy);
        }
        return out;
    }

    public double luminanceAt(double x, double y) {
        int[] rgb = rgbAt(x, y);
        return 0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2];
    }

    public RasterImage scaled(double factor) {
        if (Math.abs(factor - 1.0) < 1e-9) {
            return this;
        }
        int nw = Math.max(1, (int) Math.round(width * factor));
        int nh = Math.max(1, (int) Math.round(height * factor));
        int[] out = new int[nw * nh];
        double sx = (double) width / nw;
        double sy = (double) height / nh;
        for (int y = 0; y < nh; y++) {
            double sourceY = (y + 0.5) * sy - 0.5;
            for (int x = 0; x < nw; x++) {
                double sourceX = (x + 0.5) * sx - 0.5;
                int x0 = (int) Math.floor(sourceX);
                int y0 = (int) Math.floor(sourceY);
                int x1 = x0 + 1;
                int y1 = y0 + 1;
                double fx = sourceX - x0;
                double fy = sourceY - y0;
                int p00 = getRgb(x0, y0);
                int p10 = getRgb(x1, y0);
                int p01 = getRgb(x0, y1);
                int p11 = getRgb(x1, y1);
                int a = 0xff000000;
                for (int shift = 16; shift >= 0; shift -= 8) {
                    double c00 = (p00 >>> shift) & 0xff;
                    double c10 = (p10 >>> shift) & 0xff;
                    double c01 = (p01 >>> shift) & 0xff;
                    double c11 = (p11 >>> shift) & 0xff;
                    double top = c00 * (1 - fx) + c10 * fx;
                    double bottom = c01 * (1 - fx) + c11 * fx;
                    int value = (int) Math.round(top * (1 - fy) + bottom * fy);
                    a |= (value & 0xff) << shift;
                }
                out[y * nw + x] = a;
            }
        }
        return new RasterImage(nw, nh, out);
    }

    public BufferedImage toBufferedImage() {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }
}
