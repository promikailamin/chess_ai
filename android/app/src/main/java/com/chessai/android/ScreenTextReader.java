package com.chessai.android;

import android.graphics.Bitmap;

import com.chessai.autoplay.TurnText;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.TimeUnit;

/** OCR fallback for apps that render the move indicator instead of exposing text. */
final class ScreenTextReader {
    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    String read(Bitmap screenshot) {
        String corner = readRegion(screenshot, 0.55, 0.82, 1.0, 1.0);
        if (TurnText.looksLikeTurnText(corner)) {
            return corner;
        }
        String bottomStrip = readRegion(screenshot, 0.0, 0.78, 1.0, 1.0);
        if (TurnText.looksLikeTurnText(bottomStrip)) {
            return bottomStrip;
        }
        if (corner == null || corner.trim().isEmpty()) {
            return bottomStrip == null ? "" : bottomStrip;
        }
        if (bottomStrip == null || bottomStrip.trim().isEmpty()) {
            return corner;
        }
        return corner + " " + bottomStrip;
    }

    private String readRegion(Bitmap source, double left, double top,
                              double right, double bottom) {
        int x = clamp((int) Math.round(source.getWidth() * left), 0, source.getWidth() - 1);
        int y = clamp((int) Math.round(source.getHeight() * top), 0, source.getHeight() - 1);
        int w = clamp((int) Math.round(source.getWidth() * (right - left)), 1,
                source.getWidth() - x);
        int h = clamp((int) Math.round(source.getHeight() * (bottom - top)), 1,
                source.getHeight() - y);
        Bitmap crop = Bitmap.createBitmap(source, x, y, w, h);
        try {
            InputImage image = InputImage.fromBitmap(crop, 0);
            Text text = Tasks.await(recognizer.process(image), 3, TimeUnit.SECONDS);
            return text.getText();
        } catch (Exception ex) {
            return "";
        } finally {
            crop.recycle();
        }
    }

    void close() {
        recognizer.close();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
