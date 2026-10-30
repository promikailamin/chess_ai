package com.chessai.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.chessai.engine.Piece;

/** Draggable floating pawn with the start-game control panel. */
final class FloatingChessOverlay {
    interface Listener {
        void onStart(int botColor);

        void onStop();
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private TextView ball;
    private LinearLayout panel;
    private TextView statusView;
    private RadioButton whiteButton;
    private RadioButton blackButton;
    private Button startButton;
    private Button stopButton;
    private boolean showingPanel;
    private boolean running;
    private int botColor = Piece.BLACK;

    private WindowManager.LayoutParams ballParams;
    private float touchStartX;
    private float touchStartY;
    private int startParamX;
    private int startParamY;
    private boolean dragging;

    FloatingChessOverlay(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    void showBall() {
        if (ball != null) {
            return;
        }
        ball = new TextView(context);
        ball.setText("\u265F");
        ball.setTextColor(Color.WHITE);
        ball.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        ball.setGravity(Gravity.CENTER);
        ball.setTypeface(Typeface.DEFAULT_BOLD);
        ball.setContentDescription("Chess AI bot");
        ball.setBackgroundResource(R.drawable.ball_background);
        ball.setElevation(dp(8));

        ballParams = baseParams();
        ballParams.width = dp(56);
        ballParams.height = dp(56);
        ballParams.x = dp(18);
        ballParams.y = dp(190);

        ball.setOnTouchListener((view, event) -> handleBallTouch(event));
        try {
            windowManager.addView(ball, ballParams);
        } catch (RuntimeException ex) {
            ball = null;
        }
    }

    void setStatus(String message) {
        handler.post(() -> {
            if (statusView != null) {
                statusView.setText(message == null || message.isEmpty() ? "Ready" : message);
            }
        });
    }

    void setRunning(boolean value) {
        handler.post(() -> {
            running = value;
            if (startButton != null) {
                startButton.setEnabled(!value);
                startButton.setAlpha(value ? 0.45f : 1f);
            }
            if (stopButton != null) {
                stopButton.setEnabled(value);
                stopButton.setAlpha(value ? 1f : 0.45f);
            }
            if (whiteButton != null) {
                whiteButton.setEnabled(!value);
                blackButton.setEnabled(!value);
            }
            if (ball != null) {
                ball.setAlpha(value ? 0.78f : 1f);
            }
        });
    }

    void remove() {
        handler.post(() -> {
            if (panel != null) {
                try {
                    windowManager.removeView(panel);
                } catch (RuntimeException ignored) {
                }
                panel = null;
                showingPanel = false;
            }
            if (ball != null) {
                try {
                    windowManager.removeView(ball);
                } catch (RuntimeException ignored) {
                }
                ball = null;
            }
        });
    }

    private boolean handleBallTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                touchStartX = event.getRawX();
                touchStartY = event.getRawY();
                startParamX = ballParams.x;
                startParamY = ballParams.y;
                dragging = false;
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                float dx = event.getRawX() - touchStartX;
                float dy = event.getRawY() - touchStartY;
                int slop = ViewConfiguration.get(context).getScaledTouchSlop();
                if (!dragging && Math.hypot(dx, dy) > slop) {
                    dragging = true;
                }
                if (dragging) {
                    ballParams.x = Math.max(0, startParamX + Math.round(dx));
                    ballParams.y = Math.max(0, startParamY + Math.round(dy));
                    try {
                        windowManager.updateViewLayout(ball, ballParams);
                    } catch (RuntimeException ignored) {
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    togglePanel();
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void togglePanel() {
        if (showingPanel) {
            hidePanel();
        } else {
            showPanel();
        }
    }

    private void hidePanel() {
        showingPanel = false;
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
    }

    private void showPanel() {
        if (panel == null) {
            buildPanel();
        }
        if (panel == null) {
            return;
        }
        showingPanel = true;
        panel.setVisibility(View.VISIBLE);
        if (panel.getParent() == null) {
            try {
                windowManager.addView(panel, panelParams());
            } catch (RuntimeException ex) {
                showingPanel = false;
            }
        }
    }

    private void buildPanel() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.panel_background);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.setElevation(dp(10));

        TextView title = new TextView(context);
        title.setText("Chess AI Bot");
        title.setTextColor(Color.parseColor("#E8B44A"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = new TextView(context);
        statusView.setText("Ready");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusView.setPadding(0, dp(4), 0, dp(10));
        root.addView(statusView);

        TextView colorLabel = new TextView(context);
        colorLabel.setText(R.string.bot_color);
        colorLabel.setTextColor(Color.LTGRAY);
        colorLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(colorLabel);

        RadioGroup colors = new RadioGroup(context);
        colors.setOrientation(RadioGroup.HORIZONTAL);
        whiteButton = radio(R.string.bot_white);
        blackButton = radio(R.string.bot_black);
        colors.addView(whiteButton);
        colors.addView(blackButton);
        colors.check(botColor == Piece.WHITE ? whiteButton.getId() : blackButton.getId());
        colors.setOnCheckedChangeListener((group, checkedId) -> {
            botColor = checkedId == whiteButton.getId() ? Piece.WHITE : Piece.BLACK;
        });
        root.addView(colors);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, 0);

        startButton = actionButton(R.string.start);
        startButton.setOnClickListener(view -> {
            listener.onStart(botColor);
            setStatus("Starting...");
            hidePanel();
        });
        stopButton = actionButton(R.string.stop);
        stopButton.setEnabled(running);
        stopButton.setAlpha(running ? 1f : 0.45f);
        stopButton.setOnClickListener(view -> {
            listener.onStop();
            setStatus("Stopped");
        });
        Button hide = actionButton(R.string.hide);
        hide.setOnClickListener(view -> hidePanel());

        actions.addView(startButton, weightParams());
        actions.addView(stopButton, weightParams());
        actions.addView(hide, weightParams());
        root.addView(actions);

        panel = root;
    }

    private RadioButton radio(int text) {
        RadioButton button = new RadioButton(context);
        button.setId(View.generateViewId());
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        return button;
    }

    private Button actionButton(int text) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setBackgroundResource(R.drawable.button_background);
        return button;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private WindowManager.LayoutParams baseParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        params.format = PixelFormat.TRANSLUCENT;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        params.gravity = Gravity.TOP | Gravity.START;
        return params;
    }

    private WindowManager.LayoutParams panelParams() {
        WindowManager.LayoutParams params = baseParams();
        params.width = dp(310);
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        return params;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}
