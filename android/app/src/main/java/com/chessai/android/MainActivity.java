package com.chessai.android;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Setup screen: accessibility service, overlay permission and target package. */
public final class MainActivity extends Activity {
    private TextView accessibilityStatus;
    private TextView saveStatus;
    private EditText targetPackage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        accessibilityStatus = findViewById(R.id.accessibility_status);
        saveStatus = findViewById(R.id.save_status);
        targetPackage = findViewById(R.id.edit_target);

        String saved = getSharedPreferences(ChessAccessibilityService.PREFS, MODE_PRIVATE)
                .getString(ChessAccessibilityService.KEY_TARGET,
                        ChessAccessibilityService.DEFAULT_TARGET_PACKAGE);
        targetPackage.setText(saved);

        Button accessibility = findViewById(R.id.button_accessibility);
        accessibility.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (RuntimeException ex) {
                Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        Button overlay = findViewById(R.id.button_overlay);
        overlay.setOnClickListener(view -> {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (RuntimeException ex) {
                Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        Button save = findViewById(R.id.button_save_target);
        save.setOnClickListener(view -> saveTarget());

        Button openGame = findViewById(R.id.button_open_game);
        openGame.setOnClickListener(view -> openTargetApp());
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = isAccessibilityEnabled();
        accessibilityStatus.setText(enabled
                ? R.string.status_enabled
                : R.string.status_disabled);
        accessibilityStatus.setTextColor(enabled ? 0xFF1B5E20 : 0xFFB00020);
    }

    private void saveTarget() {
        String value = targetPackage.getText().toString().trim();
        if (value.isEmpty()) {
            value = ChessAccessibilityService.DEFAULT_TARGET_PACKAGE;
        }
        getSharedPreferences(ChessAccessibilityService.PREFS, MODE_PRIVATE)
                .edit()
                .putString(ChessAccessibilityService.KEY_TARGET, value)
                .apply();
        targetPackage.setText(value);
        saveStatus.setText(R.string.saved);
    }

    private void openTargetApp() {
        String value = targetPackage.getText().toString().trim();
        if (value.isEmpty()) {
            value = ChessAccessibilityService.DEFAULT_TARGET_PACKAGE;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(value);
        if (launch == null) {
            Toast.makeText(this, "App not installed: " + value, Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(launch);
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager manager =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }
        List<AccessibilityServiceInfo> services =
                manager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : services) {
            if (info.getResolveInfo() != null
                    && info.getResolveInfo().serviceInfo != null
                    && getPackageName().equals(
                    info.getResolveInfo().serviceInfo.packageName)) {
                return true;
            }
        }
        return false;
    }
}
