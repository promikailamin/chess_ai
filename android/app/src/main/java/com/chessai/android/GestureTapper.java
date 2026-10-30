package com.chessai.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;

/** Sends a short tap gesture and runs a callback after it is dispatched. */
final class GestureTapper {
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private GestureTapper() {
    }

    static void tap(AccessibilityService service, float x, float y, Runnable after) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 45);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                if (after != null) {
                    HANDLER.postDelayed(after, 120);
                }
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                if (after != null) {
                    HANDLER.postDelayed(after, 120);
                }
            }
        }, HANDLER);
    }
}
