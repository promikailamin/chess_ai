package com.chessai.vision;

/** Small platform-neutral ARGB image used for color piece templates. */
public final class RgbImage {
    public final int width;
    public final int height;
    private final int[] argb;

    public RgbImage(int width, int height, int[] argb) {
        if (width <= 0 || height <= 0 || argb.length != width * height) {
            throw new IllegalArgumentException("Invalid RGB image");
        }
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    public int argbAt(int x, int y) {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x >= width) x = width - 1;
        if (y >= height) y = height - 1;
        return argb[y * width + x];
    }
}
