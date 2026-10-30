package com.chessai.vision;

/** Small platform-neutral grayscale image used while loading piece templates. */
public final class GrayImage {
    public final int width;
    public final int height;
    private final int[] values;

    public GrayImage(int width, int height, int[] values) {
        if (width <= 0 || height <= 0 || values.length != width * height) {
            throw new IllegalArgumentException("Invalid grayscale image");
        }
        this.width = width;
        this.height = height;
        this.values = values;
    }

    public int luminance(int x, int y) {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x >= width) x = width - 1;
        if (y >= height) y = height - 1;
        return values[y * width + x];
    }
}
