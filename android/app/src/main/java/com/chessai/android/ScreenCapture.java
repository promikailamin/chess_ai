package com.chessai.android;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.view.Display;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Captures the current display through the accessibility screenshot API. */
final class ScreenCapture {
    private ScreenCapture() {
    }

    static Bitmap capture(AccessibilityService service, long timeoutMillis) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> result = new AtomicReference<>();
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                            HardwareBuffer buffer = screenshot.getHardwareBuffer();
                            try {
                                ColorSpace colorSpace = screenshot.getColorSpace();
                                if (colorSpace == null) {
                                    colorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
                                }
                                Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                                if (hardware != null) {
                                    result.set(hardware.copy(Bitmap.Config.ARGB_8888, false));
                                }
                            } finally {
                                buffer.close();
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            latch.countDown();
                        }
                    });
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            return null;
        }
        return result.get();
    }
}
