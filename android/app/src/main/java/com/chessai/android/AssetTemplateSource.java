package com.chessai.android;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.chessai.vision.GrayImage;
import com.chessai.vision.RgbImage;
import com.chessai.vision.TemplateSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Loads the shared piece templates from the APK assets. */
final class AssetTemplateSource implements TemplateSource {
    private final AssetManager assets;

    AssetTemplateSource(AssetManager assets) {
        this.assets = assets;
    }

    @Override
    public GrayImage image(String resourcePath) {
        String path = normalize(resourcePath);
        try (InputStream stream = assets.open(path)) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            if (bitmap == null) {
                return null;
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            bitmap.recycle();
            int[] gray = new int[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                int rgb = pixels[i];
                int r = (rgb >>> 16) & 0xff;
                int g = (rgb >>> 8) & 0xff;
                int b = rgb & 0xff;
                gray[i] = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
            }
            return new GrayImage(width, height, gray);
        } catch (IOException ex) {
            return null;
        }
    }

    @Override
    public RgbImage rgbImage(String resourcePath) {
        String path = normalize(resourcePath);
        try (InputStream stream = assets.open(path)) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            if (bitmap == null) {
                return null;
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            bitmap.recycle();
            return new RgbImage(width, height, pixels);
        } catch (IOException ex) {
            return null;
        }
    }

    @Override
    public String text(String resourcePath) {
        String path = normalize(resourcePath);
        try (InputStream stream = assets.open(path);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException ex) {
            return null;
        }
    }

    private static String normalize(String path) {
        if (path == null) {
            return "";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
