package com.chessai.android;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

import com.chessai.vision.BoardDetector;
import com.chessai.vision.PieceClassifier;
import com.chessai.vision.RasterImage;
import com.chessai.vision.Recognition;

/** Android adapter around the shared screenshot recognition pipeline. */
final class AndroidVision {
    private final PieceClassifier classifier;

    AndroidVision(AssetManager assets) {
        classifier = new PieceClassifier(new AssetTemplateSource(assets), false);
    }

    synchronized Recognition recognize(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        RasterImage image = new RasterImage(width, height, pixels);
        BoardDetector.Detection detection = BoardDetector.detect(image);
        return classifier.recognize(image, detection);
    }
}
