package com.chessai.vision;

/**
 * Supplies the bundled piece-template images and manifest to the classifier.
 *
 * <p>Desktop builds use {@link AwtTemplateSource}; the Android app supplies an
 * asset-based implementation so the same recognition code runs on both.</p>
 */
public interface TemplateSource {
    /** Returns the image at {@code resourcePath}, or {@code null} if unavailable. */
    GrayImage image(String resourcePath);

    /** Returns the UTF-8 text at {@code resourcePath}, or {@code null} if unavailable. */
    String text(String resourcePath);
}
