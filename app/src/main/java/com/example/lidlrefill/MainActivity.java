package com.example.lidlrefill;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.media.projection.MediaProjectionManager;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Button btnStartRecording, btnRefresh, btnToggleService;
    private TextView tvServiceStatus, tvStatus;

    private SharedPreferences prefs;
    private static final String PREFS_NAME = "RefillRecorderPrefs";
    private static final String KEY_BUTTON_X = "button_x";
    private static final String KEY_BUTTON_Y = "button_y";
    private static final String KEY_VOLUME_X = "volume_x";
    private static final String KEY_VOLUME_Y = "volume_y";
    private static final String KEY_VOLUME_WIDTH = "volume_width";
    private static final String KEY_VOLUME_HEIGHT = "volume_height";
    private static final String KEY_SWIPE_START_X = "swipe_start_x";
    private static final String KEY_SWIPE_START_Y = "swipe_start_y";
    private static final String KEY_SWIPE_END_X = "swipe_end_x";
    private static final String KEY_SWIPE_END_Y = "swipe_end_y";
    private static final String KEY_IS_RECORDED = "is_recorded";
    private static final String KEY_SERVICE_RUNNING = "service_running";

    // Gespeicherte Positionen
    private float buttonX = 0, buttonY = 0;
    private float volumeX = 0, volumeY = 0;
    private float volumeWidth = 0.25f, volumeHeight = 0.10f;
    private float swipeStartX = 0.5f, swipeStartY = 0.15f;
    private float swipeEndX = 0.5f, swipeEndY = 0.50f;
    private boolean isRecorded = false;
    private boolean isServiceRunning = false;

    private WindowManager windowManager;
    private View markerView;
    private int recordingType = 0; // 1=Refill, 2=Volumen, 3=Swipe
    private boolean isMarkerVisible = false;

    private static final int REQUEST_MEDIA_PROJECTION = 1001;

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
        loadServiceState();
    }

    private void initViews() {
        btnStartRecording = findViewById(R.id.btn_start_recording);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnToggleService = findViewById(R.id.btn_toggle_service);
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
                    showFloatingMarker();
                    Toast.makeText(this, "📍 Ziehe Marker auf den Refill-Button", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("🔵 Volumen", (d, w) -> {
                    recordingType = 2;
                    showFloatingMarker();
                    Toast.makeText(this, "📍 Ziehe Marker auf die Volumen-Anzeige", Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("🔄 Swipe-Geste", (d, w) -> {
                    recordingType = 3;
                    showFloatingMarker();
                    Toast.makeText(this, "📍 Ziehe Marker für Swipe-Geste (Startpunkt)", Toast.LENGTH_LONG).show();
                })
                .show();
        });

        btnRefresh.setOnClickListener(v -> {
            if (isServiceRunning) {
                Toast.makeText(this, "🔄 Aktualisiere...", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(this, RefillAccessibilityService.class);
                intent.putExtra("action", "refresh");
                startService(intent);
            } else {
                Toast.makeText(this, "⚠️ Service läuft nicht!", Toast.LENGTH_SHORT).show();
            }
        });

        btnToggleService.setOnClickListener(v -> {
            if (isServiceRunning) {
                stopService();
            } else {
                startService();
            }
        });
    }

    private void startService() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "⚠️ Bitte Accessibility Service aktivieren!", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        if (!isRecorded) {
            Toast.makeText(this, "⚠️ Bitte zuerst Positionen aufnehmen!", Toast.LENGTH_LONG).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaProjectionManager projectionManager = 
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (projectionManager != null) {
                startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
                return;
            }
        }

        startMonitoringService();
    }

    private void startMonitoringService() {
        isServiceRunning = true;
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, true).apply();

        Intent intent = new Intent(this, RefillAccessibilityService.class);
        intent.putExtra("action", "start_monitoring");
        intent.putExtra("button_x", buttonX);
        intent.putExtra("button_y", buttonY);
        intent.putExtra("volume_x", volumeX);
        intent.putExtra("volume_y", volumeY);
        intent.putExtra("volume_width", volumeWidth);
        intent.putExtra("volume_height", volumeHeight);
        intent.putExtra("swipe_start_x", swipeStartX);
        intent.putExtra("swipe_start_y", swipeStartY);
        intent.putExtra("swipe_end_x", swipeEndX);
        intent.putExtra("swipe_end_y", swipeEndY);
        startService(intent);

        btnToggleService.setText("⏹️ Service stoppen");
        btnToggleService.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_red_dark)));
        tvStatus.setText("🟢 Läuft");
        tvStatus.setTextColor(Color.parseColor("#4CAF50"));
        Toast.makeText(this, "▶️ Service gestartet!", Toast.LENGTH_SHORT).show();
    }

    private void stopService() {
        isServiceRunning = false;
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply();

        Intent intent = new Intent(this, RefillAccessibilityService.class);
        intent.putExtra("action", "stop_monitoring");
        startService(intent);

        btnToggleService.setText("▶️ Service starten");
        btnToggleService.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_blue_light)));
        tvStatus.setText("🔴 Gestoppt");
        tvStatus.setTextColor(Color.parseColor("#B0BEC5"));
        Toast.makeText(this, "⏹️ Service gestoppt!", Toast.LENGTH_SHORT).show();
    }

    private void loadServiceState() {
        isServiceRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false);
        if (isServiceRunning) {
            btnToggleService.setText("⏹️ Service stoppen");
            btnToggleService.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_red_dark)));
            tvStatus.setText("🟢 Läuft");
            tvStatus.setTextColor(Color.parseColor("#4CAF50"));
        } else {
            btnToggleService.setText("▶️ Service starten");
            btnToggleService.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_blue_light)));
            tvStatus.setText("🔴 Gestoppt");
            tvStatus.setTextColor(Color.parseColor("#B0BEC5"));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "✅ Screenshot-Berechtigung erteilt!", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(this, RefillAccessibilityService.class);
                intent.putExtra("action", "media_projection");
                intent.putExtra("resultCode", resultCode);
                intent.putExtra("data", data);
                startService(intent);
                startMonitoringService();
            } else {
                Toast.makeText(this, "⚠️ Screenshot-Berechtigung verweigert!", Toast.LENGTH_LONG).show();
                startMonitoringService();
            }
        }
    }

    // ✅ FLOATING MARKER - GANZ VORNE (TYPE_APPLICATION_OVERLAY)
    private void showFloatingMarker() {
        if (isMarkerVisible) {
            removeMarker();
            return;
        }

        markerView = new View(this) {
            private Paint paint = new Paint();
            private Paint borderPaint = new Paint();
            private RectF rect = new RectF();

            {
                paint.setStyle(Paint.Style.FILL);
                borderPaint.setStyle(Paint.Style.STROKE);
                borderPaint.setStrokeWidth(6);
                setWillNotDraw(false);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth();
                int h = getHeight();
                int padding = 8;

                if (recordingType == 1) {
                    // 🟢 Kreis für Refill-Button
                    paint.setColor(Color.argb(200, 255, 87, 34));
                    borderPaint.setColor(Color.parseColor("#4CAF50"));
                    float cx = w / 2f;
                    float cy = h / 2f;
                    float radius = Math.min(w, h) / 2f - padding;
                    canvas.drawCircle(cx, cy, radius, paint);
                    canvas.drawCircle(cx, cy, radius, borderPaint);
                    Paint textPaint = new Paint();
                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(22);
                    textPaint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText("REFILL", cx, cy + 8, textPaint);
                } else if (recordingType == 2) {
                    // 🔵 Rechteck für Volumen
                    paint.setColor(Color.argb(200, 33, 150, 243));
                    borderPaint.setColor(Color.parseColor("#2196F3"));
                    rect.set(padding, padding, w - padding, h - padding);
                    canvas.drawRoundRect(rect, 12, 12, paint);
                    canvas.drawRoundRect(rect, 12, 12, borderPaint);
                    Paint textPaint = new Paint();
                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(20);
                    textPaint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText("VOLUMEN", w / 2f, h / 2f + 7, textPaint);
                } else {
                    // 🟠 Pfeil für Swipe-Geste
                    paint.setColor(Color.argb(200, 255, 152, 0));
                    borderPaint.setColor(Color.parseColor("#FF9800"));
                    rect.set(padding, padding, w - padding, h - padding);
                    canvas.drawRoundRect(rect, 12, 12, paint);
                    canvas.drawRoundRect(rect, 12, 12, borderPaint);
                    Paint textPaint = new Paint();
                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(24);
                    textPaint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText("⬇️ SWIPE", w / 2f, h / 2f + 8, textPaint);
                }
            }
        };

        // Größen für Marker
        int size, height;
        if (recordingType == 1) {
            size = 120;
            height = 120;
        } else if (recordingType == 2) {
            size = 340;
            height = 120;
        } else {
            size = 180;
            height = 180;
        }

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
                        float centerX = v.getX() + v.getWidth() / 2f;
                        float centerY = v.getY() + v.getHeight() / 2f;
                        float xPercent = centerX / windowManager.getDefaultDisplay().getWidth();
                        float yPercent = centerY / windowManager.getDefaultDisplay().getHeight();
                        savePosition(xPercent, yPercent);
                        removeMarker();
                        return true;
                }
                return false;
            }
        });

        // ✅ WICHTIG: TYPE_APPLICATION_OVERLAY für GANZ VORNE!
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                size,
                height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 200;
        params.y = 300;

        windowManager.addView(markerView, params);
        isMarkerVisible = true;
        Toast.makeText(this, "📌 Marker erscheint über ALLEN Apps!", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "✅ Refill-Button gespeichert! (" + Math.round(x * 100) + "%, " + Math.round(y * 100) + "%)", Toast.LENGTH_LONG).show();
        } else if (recordingType == 2) {
            volumeX = x;
            volumeY = y;
            volumeWidth = 0.25f;
            volumeHeight = 0.10f;
            Toast.makeText(this, "✅ Volumen gespeichert! (" + Math.round(x * 100) + "%, " + Math.round(y * 100) + "%)", Toast.LENGTH_LONG).show();
        } else {
            swipeStartX = x;
            swipeStartY = y;
            swipeEndX = x;
            swipeEndY = Math.min(y + 0.35f, 0.90f);
            Toast.makeText(this, "✅ Swipe-Geste gespeichert! (" + Math.round(x * 100) + "%, " + Math.round(y * 100) + "%)", Toast.LENGTH_LONG).show();
        }
        savePositions();
    }

    private void savePositions() {
        prefs.edit()
            .putFloat(KEY_BUTTON_X, buttonX)
            .putFloat(KEY_BUTTON_Y, buttonY)
            .putFloat(KEY_VOLUME_X, volumeX)
            .putFloat(KEY_VOLUME_Y, volumeY)
            .putFloat(KEY_VOLUME_WIDTH, volumeWidth)
            .putFloat(KEY_VOLUME_HEIGHT, volumeHeight)
            .putFloat(KEY_SWIPE_START_X, swipeStartX)
            .putFloat(KEY_SWIPE_START_Y, swipeStartY)
            .putFloat(KEY_SWIPE_END_X, swipeEndX)
            .putFloat(KEY_SWIPE_END_Y, swipeEndY)
            .putBoolean(KEY_IS_RECORDED, true)
            .apply();
        isRecorded = true;
        tvServiceStatus.setText("✅ Alle Positionen gespeichert!");
        tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"));
    }

    private void loadSavedPositions() {
        isRecorded = prefs.getBoolean(KEY_IS_RECORDED, false);
        if (isRecorded) {
            buttonX = prefs.getFloat(KEY_BUTTON_X, 0);
            buttonY = prefs.getFloat(KEY_BUTTON_Y, 0);
            volumeX = prefs.getFloat(KEY_VOLUME_X, 0);
            volumeY = prefs.getFloat(KEY_VOLUME_Y, 0);
            volumeWidth = prefs.getFloat(KEY_VOLUME_WIDTH, 0.25f);
            volumeHeight = prefs.getFloat(KEY_VOLUME_HEIGHT, 0.10f);
            swipeStartX = prefs.getFloat(KEY_SWIPE_START_X, 0.5f);
            swipeStartY = prefs.getFloat(KEY_SWIPE_START_Y, 0.15f);
            swipeEndX = prefs.getFloat(KEY_SWIPE_END_X, 0.5f);
            swipeEndY = prefs.getFloat(KEY_SWIPE_END_Y, 0.50f);
            tvServiceStatus.setText("✅ Alle Positionen gespeichert!");
            tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"));
        }
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            new AlertDialog.Builder(this)
                .setTitle("⚠️ Overlay-Berechtigung benötigt")
                .setMessage("Um den Marker über der Lidl App anzeigen zu können, benötigt die App die Berechtigung 'Über anderen Apps anzeigen'.")
                .setPositiveButton("⚙️ Einstellungen öffnen", (d, w) -> {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                })
                .setNegativeButton("Abbrechen", null)
                .show();
        }
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
        loadServiceState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        removeMarker();
    }
}
