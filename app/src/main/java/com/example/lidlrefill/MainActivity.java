package com.example.lidlrefill;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Button btnStartRecording, btnOpenApp;
    private TextView tvServiceStatus, tvStatus;

    private SharedPreferences prefs;
    private static final String PREFS_NAME = "RefillRecorderPrefs";
    private static final String KEY_BUTTON_X = "button_x";
    private static final String KEY_BUTTON_Y = "button_y";
    private static final String KEY_VOLUME_X = "volume_x";
    private static final String KEY_VOLUME_Y = "volume_y";
    private static final String KEY_IS_RECORDED = "is_recorded";

    private float buttonX = 0, buttonY = 0;
    private float volumeX = 0, volumeY = 0;
    private boolean isRecorded = false;

    private WindowManager windowManager;
    private ImageView markerView;
    private int recordingType = 0;
    private boolean isMarkerVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        initViews();
        setupButtons();
        checkAccessibilityService();
        loadSavedPositions();
    }

    private void initViews() {
        btnStartRecording = findViewById(R.id.btn_start_recording);
        btnOpenApp = findViewById(R.id.btn_open_app);
        tvServiceStatus = findViewById(R.id.tv_service_status);
        tvStatus = findViewById(R.id.tv_status);
    }

    private void setupButtons() {
        btnStartRecording.setOnClickListener(v -> {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "⚠️ Bitte Accessibility Service aktivieren!", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }

            if (!hasOverlayPermission()) {
                requestOverlayPermission();
                return;
            }

            new AlertDialog.Builder(this)
                .setTitle("📌 Position aufnehmen")
                .setMessage("Wähle aus:")
                .setPositiveButton("🟢 Refill-Button", (d, w) -> {
                    recordingType = 1;
                    showMiniMarker();
                    Toast.makeText(this, "📍 Auf Refill-Button ziehen", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("🔵 Volumen", (d, w) -> {
                    recordingType = 2;
                    showMiniMarker();
                    Toast.makeText(this, "📍 Auf Volumen-Anzeige ziehen", Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("Abbrechen", null)
                .show();
        });

        btnOpenApp.setOnClickListener(v -> openLidlApp());
    }

    private void showMiniMarker() {
        if (isMarkerVisible) {
            removeMarker();
            return;
        }

        markerView = new ImageView(this);
        markerView.setImageResource(recordingType == 1 ? R.drawable.circle_marker : R.drawable.rect_marker);
        markerView.setScaleType(ImageView.ScaleType.CENTER_CROP);

        int size = (recordingType == 1) ? 80 : 140;
        int height = (recordingType == 1) ? 80 : 50;

        markerView.setOnTouchListener(new View.OnTouchListener() {
            private float initialX, initialY, touchX, touchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = v.getX();
                        initialY = v.getY();
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        v.setX(initialX + event.getRawX() - touchX);
                        v.setY(initialY + event.getRawY() - touchY);
                        return true;
                    case MotionEvent.ACTION_UP:
                        float cx = v.getX() + v.getWidth() / 2;
                        float cy = v.getY() + v.getHeight() / 2;
                        float xP = cx / windowManager.getDefaultDisplay().getWidth();
                        float yP = cy / windowManager.getDefaultDisplay().getHeight();
                        savePosition(xP, yP);
                        removeMarker();
                        return true;
                }
                return false;
            }
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                size, height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 200;
        params.y = 300;

        windowManager.addView(markerView, params);
        isMarkerVisible = true;
    }

    private void removeMarker() {
        if (markerView != null && windowManager != null) {
            try {
                windowManager.removeView(markerView);
                markerView = null;
                isMarkerVisible = false;
            } catch (Exception ignored) {}
        }
    }

    private void savePosition(float x, float y) {
        if (recordingType == 1) {
            buttonX = x;
            buttonY = y;
            Toast.makeText(this, "✅ Refill-Button gespeichert!", Toast.LENGTH_SHORT).show();
        } else {
            volumeX = x;
            volumeY = y;
            Toast.makeText(this, "✅ Volumen gespeichert!", Toast.LENGTH_SHORT).show();
        }
        savePositions();
    }

    private void savePositions() {
        prefs.edit()
            .putFloat(KEY_BUTTON_X, buttonX)
            .putFloat(KEY_BUTTON_Y, buttonY)
            .putFloat(KEY_VOLUME_X, volumeX)
            .putFloat(KEY_VOLUME_Y, volumeY)
            .putBoolean(KEY_IS_RECORDED, true)
            .apply();
        isRecorded = true;
        tvServiceStatus.setText("✅ Positionen gespeichert!");
        tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"));
    }

    private void loadSavedPositions() {
        isRecorded = prefs.getBoolean(KEY_IS_RECORDED, false);
        if (isRecorded) {
            buttonX = prefs.getFloat(KEY_BUTTON_X, 0);
            buttonY = prefs.getFloat(KEY_BUTTON_Y, 0);
            volumeX = prefs.getFloat(KEY_VOLUME_X, 0);
            volumeY = prefs.getFloat(KEY_VOLUME_Y, 0);
            tvServiceStatus.setText("✅ Positionen gespeichert!");
            tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"));
        }
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            new AlertDialog.Builder(this)
                .setTitle("⚠️ Overlay-Berechtigung")
                .setMessage("Bitte 'Über anderen Apps anzeigen' aktivieren.")
                .setPositiveButton("Einstellungen", (d, w) -> {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                })
                .setNegativeButton("Abbrechen", null)
                .show();
        }
    }

    private void openLidlApp() {
        String[] packages = {"de.lidlconnect.android", "de.lidl.connect", "com.lidlconnect.app"};
        for (String pkg : packages) {
            try {
                Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent != null) {
                    startActivity(intent);
                    return;
                }
            } catch (Exception ignored) {}
        }
        Toast.makeText(this, "⚠️ Lidl Connect nicht gefunden!", Toast.LENGTH_SHORT).show();
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : list) {
            if (info.getResolveInfo().serviceInfo.packageName.equals(getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private void checkAccessibilityService() {
        if (isAccessibilityServiceEnabled()) {
            tvServiceStatus.setText("✅ Service aktiv");
            tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"));
        } else {
            tvServiceStatus.setText("❌ Service inaktiv");
            tvServiceStatus.setTextColor(Color.parseColor("#F44336"));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAccessibilityService();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        removeMarker();
    }
}
