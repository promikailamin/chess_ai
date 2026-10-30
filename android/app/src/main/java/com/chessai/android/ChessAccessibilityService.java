package com.chessai.android;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import com.chessai.autoplay.AutoPlaySession;
import com.chessai.autoplay.BoardTransforms;
import com.chessai.autoplay.OrientationDetector;
import com.chessai.autoplay.TurnText;
import com.chessai.engine.Move;
import com.chessai.engine.Piece;
import com.chessai.vision.BoardQuad;
import com.chessai.vision.Homography;
import com.chessai.vision.Recognition;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Accessibility service that owns the floating control panel, reads the chess
 * app's move indicator, recognises screenshots and dispatches the bot's taps.
 */
public final class ChessAccessibilityService extends AccessibilityService {
    public static final String PREFS = "chess_ai";
    public static final String KEY_TARGET = "target_package";
    public static final String DEFAULT_TARGET_PACKAGE = "com.llx.chess";

    private static final long POLL_INTERVAL_MS = 950;
    private static final long MOVE_SETTLE_MS = 1400;
    private static final int SEARCH_TIME_MS = 1500;
    private static final double MIN_CONFIDENCE = 0.42;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private ExecutorService worker;
    private FloatingChessOverlay overlay;
    private AndroidVision vision;
    private ScreenTextReader textReader;
    private volatile AutoPlaySession session;
    private volatile boolean running;
    private volatile int botColor = Piece.BLACK;
    private long lastEventSchedule;
    private volatile long movePendingUntil;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            requestProcess();
            mainHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private final FloatingChessOverlay.Listener overlayListener =
            new FloatingChessOverlay.Listener() {
                @Override
                public void onStart(int color) {
                    startBot(color);
                }

                @Override
                public void onStop() {
                    stopBot("Stopped");
                }
            };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        worker = Executors.newSingleThreadExecutor();
        overlay = new FloatingChessOverlay(this, overlayListener);
        overlay.showBall();
        overlay.setStatus("Ready - tap the pawn");
        mainHandler.post(pollRunnable);
        worker.execute(() -> {
            try {
                vision = new AndroidVision(getAssets());
                textReader = new ScreenTextReader();
            } catch (RuntimeException ex) {
                overlay.setStatus("Initialization error: " + ex.getMessage());
            }
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!running) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        String target = currentTargetPackage();
        if (packageName != null && target.equals(packageName.toString())) {
            long now = SystemClock.uptimeMillis();
            if (now - lastEventSchedule > 550) {
                lastEventSchedule = now;
                mainHandler.postDelayed(this::requestProcess, 280);
            }
        }
    }

    @Override
    public void onInterrupt() {
        stopBot("Service interrupted");
    }

    @Override
    public void onDestroy() {
        stopBot("Service stopped");
        if (overlay != null) {
            overlay.remove();
            overlay = null;
        }
        if (textReader != null) {
            textReader.close();
            textReader = null;
        }
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        super.onDestroy();
    }

    private void startBot(int color) {
        botColor = color;
        session = new AutoPlaySession(color, 16, 64, SEARCH_TIME_MS, 2, 4500);
        movePendingUntil = 0;
        running = true;
        if (overlay != null) {
            overlay.setRunning(true);
            overlay.setStatus("Bot plays " + (color == Piece.WHITE ? "white" : "black"));
        }
        mainHandler.removeCallbacks(pollRunnable);
        mainHandler.post(pollRunnable);
    }

    private void stopBot(String reason) {
        running = false;
        session = null;
        movePendingUntil = 0;
        mainHandler.removeCallbacks(pollRunnable);
        if (overlay != null) {
            overlay.setRunning(false);
            overlay.setStatus(reason);
        }
    }

    private void finishGame(String reason) {
        running = false;
        session = null;
        movePendingUntil = 0;
        mainHandler.removeCallbacks(pollRunnable);
        if (overlay != null) {
            overlay.setRunning(false);
            overlay.setStatus(reason);
        }
        mainHandler.post(() -> Toast.makeText(this, reason, Toast.LENGTH_LONG).show());
    }

    private void requestProcess() {
        if (!running || session == null || worker == null) {
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            String status;
            try {
                status = processOnWorker();
            } catch (Throwable ex) {
                status = "Error: " + ex.getClass().getSimpleName()
                        + (ex.getMessage() == null ? "" : " - " + ex.getMessage());
            } finally {
                busy.set(false);
            }
            if (status != null && overlay != null) {
                overlay.setStatus(status);
            }
        });
    }

    private String processOnWorker() {
        AutoPlaySession activeSession = session;
        if (!running || activeSession == null) {
            return "Stopped";
        }
        if (vision == null) {
            vision = new AndroidVision(getAssets());
        }
        if (textReader == null) {
            textReader = new ScreenTextReader();
        }

        String target = currentTargetPackage();
        AccessibilityNodeInfo root = findTargetRoot(target);
        if (root == null) {
            return "Waiting for " + target;
        }
        String packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (!target.equals(packageName)) {
            return "Open " + target;
        }
        if (SystemClock.uptimeMillis() < movePendingUntil) {
            return "Waiting for move to finish";
        }

        String accessibleText = collectText(root);
        TurnText.TurnInfo accessibleTurn = TurnText.parse(accessibleText);
        Bitmap screenshot = null;
        try {
            screenshot = ScreenCapture.capture(this, 3500);
            if (screenshot == null) {
                return "Screenshot unavailable";
            }
            String ocrText = textReader.read(screenshot);
            TurnText.TurnInfo screenTurn = TurnText.parse(ocrText);
            TurnText.TurnInfo turn = TurnText.combine(accessibleTurn, screenTurn);

            if (!running || session != activeSession) {
                return "Stopped";
            }
            if (turn.isGameOver()) {
                finishGame("Game over: " + turn.result);
                return "Game over";
            }
            if (turn.side == TurnText.NO_SIDE) {
                return "Waiting for move text";
            }
            if (turn.side != botColor) {
                return "Opponent's turn";
            }

            Recognition recognition;
            try {
                recognition = vision.recognize(screenshot);
            } catch (RuntimeException ex) {
                return "Recognition error: " + ex.getMessage();
            }
            if (recognition.confidence < MIN_CONFIDENCE) {
                return String.format(Locale.US, "Low confidence %.2f - checking again",
                        recognition.confidence);
            }

            boolean flipped = OrientationDetector.isFlipped(recognition.position);
            AutoPlaySession.Decision decision = activeSession.decide(turn,
                    recognition.position, flipped);
            switch (decision.kind) {
                case PLAY -> {
                    if (!running || session != activeSession) {
                        return "Stopped";
                    }
                    movePendingUntil = SystemClock.uptimeMillis() + MOVE_SETTLE_MS;
                    performMove(recognition, decision.move, flipped);
                    return "Playing " + Move.toUci(decision.move)
                            + (flipped ? " (flipped board)" : "");
                }
                case GAME_OVER -> {
                    finishGame(decision.message);
                    return decision.message;
                }
                case INVALID -> {
                    return decision.message;
                }
                default -> {
                    return decision.message;
                }
            }
        } finally {
            if (screenshot != null) {
                screenshot.recycle();
            }
        }
    }

    private void performMove(Recognition recognition, int move, boolean flipped) {
        Homography homography = Homography.fromQuad(recognition.quad);
        int from = Move.from(move);
        int to = Move.to(move);
        mainHandler.post(() -> {
            if (!running || session == null) {
                return;
            }
            tapSquare(homography, from, flipped, () -> {
                mainHandler.postDelayed(() -> {
                    if (!running || session == null) {
                        return;
                    }
                    tapSquare(homography, to, flipped, () -> {
                        if (Move.isPromotion(move)) {
                            mainHandler.postDelayed(this::choosePromotion, 650);
                        }
                    });
                }, 320);
            });
        });
    }

    private void tapSquare(Homography homography, int square, boolean flipped,
                           Runnable after) {
        double u = (BoardTransforms.visualFile(square, flipped) + 0.5) / 8.0;
        double v = (BoardTransforms.visualRow(square, flipped) + 0.5) / 8.0;
        double[] point = homography.map(u, v);
        GestureTapper.tap(this, (float) point[0], (float) point[1], after);
    }

    private void choosePromotion() {
        AccessibilityNodeInfo root = findTargetRoot(currentTargetPackage());
        if (root == null) {
            return;
        }
        String[] labels = {"Queen", "QUEEN", "Q", "\u2655", "\u265B"};
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) {
                continue;
            }
            for (AccessibilityNodeInfo node : nodes) {
                AccessibilityNodeInfo clickable = node;
                while (clickable != null) {
                    if (clickable.isClickable()) {
                        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return;
                    }
                    clickable = clickable.getParent();
                }
            }
        }
    }

    private AccessibilityNodeInfo findTargetRoot(String target) {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (isTarget(active, target)) {
            return active;
        }
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo candidate = window.getRoot();
                    if (isTarget(candidate, target)) {
                        return candidate;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return active;
    }

    private boolean isTarget(AccessibilityNodeInfo node, String target) {
        return node != null && node.getPackageName() != null
                && target.equals(node.getPackageName().toString());
    }

    private String collectText(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 3500) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            CharSequence text = node.getText();
            if (text != null && text.length() > 0) {
                sb.append(text).append(' ');
            }
            CharSequence description = node.getContentDescription();
            if (description != null && description.length() > 0) {
                sb.append(description).append(' ');
            }
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.addLast(child);
                }
            }
        }
        return sb.toString();
    }

    private String currentTargetPackage() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String value = prefs.getString(KEY_TARGET, DEFAULT_TARGET_PACKAGE);
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_TARGET_PACKAGE;
        }
        return value.trim();
    }
}
