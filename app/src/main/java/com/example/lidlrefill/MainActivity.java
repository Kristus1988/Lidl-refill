package com.example.lidlrefill;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private Button btnStart, btnStop, btnOpenApp, btnOpenSettings, btnStartRecording;
    private TextView tvStatus, tvVolume, tvRefill, tvRefillCount, tvNextCheck, tvServiceStatus;
    private ProgressBar progressStatus;

    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private int refillCount = 0;
    private boolean isServiceEnabled = false;
    private Random random = new Random();

    private static final String PREFS_NAME = "RefillRecorderPrefs";
    private static final String KEY_REFILL_COUNT = "refill_count";
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
    private int recordingType = 0; // 1 = Button, 2 = Volume
    private boolean isMarkerVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        initViews();
        setupButtons();
        checkAccessibilityService();
        loadSavedPositions();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        refillCount = prefs.getInt(KEY_REFILL_COUNT, 0);
        tvRefillCount.setText("🔄 Refills: " + refillCount);
    }

    private void initViews() {
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnOpenApp = findViewById(R.id.btn_open_app);
        btnOpenSettings = findViewById(R.id.btn_open_settings);
        btnStartRecording = findViewById(R.id.btn_start_recording);
        tvStatus = findViewById(R.id.tv_status);
        tvVolume = findViewById(R.id.tv_volume);
        tvRefill = findViewById(R.id.tv_refill);
        tvRefillCount = findViewById(R.id.tv_refill_count);
        tvNextCheck = findViewById(R.id.tv_next_check);
        tvServiceStatus = findViewById(R.id.tv_service_status);
        progressStatus = findViewById(R.id.progress_status);
    }

    private void setupButtons() {
        btnOpenSettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        btnStartRecording.setOnClickListener(v -> {
            if (!isServiceEnabled) {
                Toast.makeText(this, "⚠️ Bitte zuerst Accessibility Service aktivieren!", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }

            if (!hasOverlayPermission()) {
                requestOverlayPermission();
                return;
            }

            new AlertDialog.Builder(this)
                .setTitle("📌 Position aufnehmen")
                .setMessage("Wähle, was du aufnehmen möchtest:\n\n" +
                           "1. Lidl App öffnen\n" +
                           "2. Marker erscheint\n" +
                           "3. Marker auf Button/Anzeige ziehen\n" +
                           "4. Loslassen = speichern")
                .setPositiveButton("🟢 Refill-Button", (d, w) -> {
                    recordingType = 1;
                    showMiniMarker();
                    Toast.makeText(this, "📍 Ziehe den Marker auf den 'Refill aktivieren' Button", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("🔵 Volumen anzeige", (d, w) -> {
                    recordingType = 2;
                    showMiniMarker();
                    Toast.makeText(this, "📍 Ziehe den Marker auf die Volumen-Anzeige", Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("Abbrechen", null)
                .show();
        });

        btnOpenApp.setOnClickListener(v -> {
            openLidlApp();
        });

        btnStart.setOnClickListener(v -> {
            if (!isServiceEnabled) {
                Toast.makeText(this, "⚠️ Bitte zuerst Accessibility Service aktivieren!", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }

            if (!isRecorded) {
                Toast.makeText(this, "⚠️ Bitte zuerst Positionen aufnehmen!", Toast.LENGTH_LONG).show();
                return;
            }

            isRunning = true;
            tvStatus.setText("🔄 Überwache Lidl App...");
            tvStatus.setTextColor(Color.parseColor("#4FC3F7"));
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);

            Intent serviceIntent = new Intent(this, RefillAccessibilityService.class);
            serviceIntent.putExtra("action", "start_monitoring");
            serviceIntent.putExtra("button_x", buttonX);
            serviceIntent.putExtra("button_y", buttonY);
            serviceIntent.putExtra("volume_x", volumeX);
            serviceIntent.putExtra("volume_y", volumeY);
            startService(serviceIntent);

            updateNextCheckTime();
        });

        btnStop.setOnClickListener(v -> {
            isRunning = false;
            Intent serviceIntent = new Intent(this, RefillAccessibilityService.class);
            serviceIntent.putExtra("action", "stop_monitoring");
            startService(serviceIntent);

            tvStatus.setText("⏹️ Gestoppt");
            tvStatus.setTextColor(Color.parseColor("#B0BEC5"));
            tvNextCheck.setText("⏱️ --");
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
        });

        btnStop.setEnabled(false);
    }

    // ✅ MINI-MARKER (NUR EIN KLEINER PUNKT)
    private void showMiniMarker() {
        if (isMarkerVisible) {
            removeMarker();
            return;
        }

        // Marker erstellen
        markerView = new ImageView(this);
        
        // Je nach Typ unterschiedliche Farbe
        if (recordingType == 1) {
            markerView.setImageResource(R.drawable.circle_marker);
        } else {
            markerView.setImageResource(R.drawable.rect_marker);
        }
        markerView.setScaleType(ImageView.ScaleType.CENTER_CROP);

        // Marker-Größe
        int size = (recordingType == 1) ? 80 : 140;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, 
                (recordingType == 1) ? size : 50);

        // Touch-Listener für Ziehen
        markerView.setOnTouchListener(new View.OnTouchListener() {
            private float initialX, initialY;
            private float touchX, touchY;

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
                        float deltaX = event.getRawX() - touchX;
                        float deltaY = event.getRawY() - touchY;
                        v.setX(initialX + deltaX);
                        v.setY(initialY + deltaY);
                        return true;
                    case MotionEvent.ACTION_UP:
                        // Position speichern
                        float centerX = v.getX() + v.getWidth() / 2;
                        float centerY = v.getY() + v.getHeight() / 2;
                        
                        float xPercent = centerX / getWindowManager().getDefaultDisplay().getWidth();
                        float yPercent = centerY / getWindowManager().getDefaultDisplay().getHeight();
                        
                        savePosition(xPercent, yPercent);
                        removeMarker();
                        return true;
                }
                return false;
            }
        });

        // Overlay-Parameter
        WindowManager.LayoutParams wmParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
        );
        wmParams.gravity = Gravity.TOP | Gravity.START;
        wmParams.x = 200;
        wmParams.y = 300;

        windowManager.addView(markerView, wmParams);
        isMarkerVisible = true;
    }

    private void removeMarker() {
        if (markerView != null && windowManager != null) {
            try {
                windowManager.removeView(markerView);
                markerView = null;
                isMarkerVisible = false;
            } catch (Exception e) {
                // Ignorieren
            }
        }
    }

    private void savePosition(float xPercent, float yPercent) {
        if (recordingType == 1) {
            buttonX = xPercent;
            buttonY = yPercent;
            Toast.makeText(this, "✅ Refill-Button Position gespeichert! (" + 
                    Math.round(xPercent * 100) + "%, " + Math.round(yPercent * 100) + "%)", Toast.LENGTH_LONG).show();
        } else {
            volumeX = xPercent;
            volumeY = yPercent;
            Toast.makeText(this, "✅ Volumen-Position gespeichert! (" + 
                    Math.round(xPercent * 100) + "%, " + Math.round(yPercent * 100) + "%)", Toast.LENGTH_LONG).show();
        }
        savePositions();
    }

    private void savePositions() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putFloat(KEY_BUTTON_X, buttonX);
        editor.putFloat(KEY_BUTTON_Y, buttonY);
        editor.putFloat(KEY_VOLUME_X, volumeX);
        editor.putFloat(KEY_VOLUME_Y, volumeY);
        editor.putBoolean(KEY_IS_RECORDED, true);
        editor.apply();
        
        isRecorded = true;
        tvServiceStatus.setText("✅ Positionen gespeichert!");
        tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"));
        tvServiceStatus.setVisibility(View.VISIBLE);
    }

    private void loadSavedPositions() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        isRecorded = prefs.getBoolean(KEY_IS_RECORDED, false);
        if (isRecorded) {
            buttonX = prefs.getFloat(KEY_BUTTON_X, 0);
            buttonY = prefs.getFloat(KEY_BUTTON_Y, 0);
            volumeX = prefs.getFloat(KEY_VOLUME_X, 0);
            volumeY = prefs.getFloat(KEY_VOLUME_Y, 0);
            tvServiceStatus.setText("✅ Positionen gespeichert!");
            tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"));
            tvServiceStatus.setVisibility(View.VISIBLE);
        }
    }

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            new AlertDialog.Builder(this)
                .setTitle("⚠️ Overlay-Berechtigung benötigt")
                .setMessage("Um die Position-Marker über der Lidl App anzeigen zu können, benötigt die App die Berechtigung 'Über anderen Apps anzeigen'.\n\n" +
                           "Bitte aktiviere sie in den Einstellungen.")
                .setPositiveButton("⚙️ Einstellungen öffnen", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton("Abbrechen", null)
                .show();
        }
    }

    private void openLidlApp() {
        String[] possiblePackages = {
            "de.lidlconnect.android",
            "de.lidl.connect",
            "com.lidlconnect.app",
            "com.lidlconnect.android"
        };
        
        for (String pkg : possiblePackages) {
            try {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launchIntent != null) {
                    startActivity(launchIntent);
                    Toast.makeText(this, "📱 Lidl Connect geöffnet!", Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (Exception e) {
                // Ignorieren
            }
        }
        
        Toast.makeText(this, "⚠️ Lidl Connect nicht gefunden! Bitte installieren.", Toast.LENGTH_LONG).show();
        try {
            Intent playStoreIntent = new Intent(Intent.ACTION_VIEW);
            playStoreIntent.setData(Uri.parse("market://details?id=de.lidlconnect.android"));
            startActivity(playStoreIntent);
        } catch (Exception e) {
            // Ignorieren
        }
    }

    private void checkAccessibilityService() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

        for (AccessibilityServiceInfo info : enabledServices) {
            if (info.getResolveInfo().serviceInfo.packageName.equals(getPackageName())) {
                isServiceEnabled = true;
                tvServiceStatus.setText("✅ Service AKTIV");
                tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"));
                tvServiceStatus.setVisibility(View.VISIBLE);
                return;
            }
        }

        isServiceEnabled = false;
        tvServiceStatus.setText("❌ Service INAKTIV – Bitte aktivieren!");
        tvServiceStatus.setTextColor(Color.parseColor("#F44336"));
        tvServiceStatus.setVisibility(View.VISIBLE);
    }

    private void updateNextCheckTime() {
        int delay = random.nextInt(120) + 60;
        tvNextCheck.setText("⏱️ Nächste Prüfung in " + delay + "s");

        mainHandler.postDelayed(() -> {
            if (isRunning && isServiceEnabled) {
                Intent serviceIntent = new Intent(this, RefillAccessibilityService.class);
                serviceIntent.putExtra("action", "check_refill");
                serviceIntent.putExtra("button_x", buttonX);
                serviceIntent.putExtra("button_y", buttonY);
                serviceIntent.putExtra("volume_x", volumeX);
                serviceIntent.putExtra("volume_y", volumeY);
                startService(serviceIntent);
                updateNextCheckTime();
            }
        }, delay * 1000L);
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
