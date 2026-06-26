package com.example.lidlrefill;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import java.util.Random;

public class OverlayService extends AccessibilityService {

    // ==========================================
    // OVERLAY-FELDER
    // ==========================================

    private WindowManager windowManager;
    private FrameLayout circleOverlay;  // 🔵 Runder Button (Refill)
    private FrameLayout rectOverlay;    // 🟦 Rechteck (Volumen)
    private TextView tvCircleStatus;
    private TextView tvRectStatus;

    // Positionen
    private float circleX = 100;
    private float circleY = 500;
    private float rectX = 100;
    private float rectY = 300;

    // Status
    private boolean isRunning = false;
    private int refillCount = 0;
    private float lastVolume = -1;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Random random = new Random();
    private SharedPreferences prefs;

    // Ziel-Volumen
    private static final float TARGET_VOLUME = 0.15f;

    // ==========================================
    // OVERLAY ERSTELLEN
    // ==========================================

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("refill_status", MODE_PRIVATE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createOverlay();
    }

    private void createOverlay() {
        int layoutFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;

        // ==========================================
        // 1. RUNDER KREIS (FINGER für Refill-Button)
        // ==========================================
        circleOverlay = new FrameLayout(this);
        circleOverlay.setBackgroundColor(Color.parseColor("#4FC3F7"));
        circleOverlay.setAlpha(0.7f);
        circleOverlay.setElevation(100);

        // Status-Text auf dem Kreis
        tvCircleStatus = new TextView(this);
        tvCircleStatus.setText("🔵 Refill");
        tvCircleStatus.setTextColor(Color.WHITE);
        tvCircleStatus.setTextSize(12);
        tvCircleStatus.setPadding(8, 8, 8, 8);
        circleOverlay.addView(tvCircleStatus);

        // Kreis-Größe: 80dp
        int size = (int) (80 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams circleParams = new FrameLayout.LayoutParams(size, size);
        circleParams.gravity = Gravity.TOP | Gravity.START;
        circleParams.leftMargin = (int) circleX;
        circleParams.topMargin = (int) circleY;
        circleOverlay.setLayoutParams(circleParams);

        // Touch-Listener für Verschieben
        setupTouchListener(circleOverlay, true);

        // ==========================================
        // 2. RECHTECK (AUGE für Volumen)
        // ==========================================
        rectOverlay = new FrameLayout(this);
        rectOverlay.setBackgroundColor(Color.parseColor("#FF6F00"));
        rectOverlay.setAlpha(0.5f);
        rectOverlay.setElevation(100);

        // Status-Text auf dem Rechteck
        tvRectStatus = new TextView(this);
        tvRectStatus.setText("🟦 Volumen");
        tvRectStatus.setTextColor(Color.WHITE);
        tvRectStatus.setTextSize(12);
        tvRectStatus.setPadding(8, 8, 8, 8);
        rectOverlay.addView(tvRectStatus);

        // Rechteck-Größe: 120x60dp
        int rectWidth = (int) (120 * getResources().getDisplayMetrics().density);
        int rectHeight = (int) (60 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams rectParams = new FrameLayout.LayoutParams(rectWidth, rectHeight);
        rectParams.gravity = Gravity.TOP | Gravity.START;
        rectParams.leftMargin = (int) rectX;
        rectParams.topMargin = (int) rectY;
        rectOverlay.setLayoutParams(rectParams);

        // Touch-Listener für Verschieben
        setupTouchListener(rectOverlay, false);

        // ==========================================
        // 3. OVERLAY ZUM FENSTER HINZUFÜGEN
        // ==========================================
        WindowManager.LayoutParams overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                layoutFlags,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.START;

        // Container für beide Overlays
        FrameLayout container = new FrameLayout(this);
        container.addView(circleOverlay);
        container.addView(rectOverlay);

        // Touch-Events an die Overlays weiterleiten
        container.setOnTouchListener((v, event) -> {
            // Nur damit der Container Touch-Events empfängt
            return false;
        });

        windowManager.addView(container, overlayParams);
    }

    private void setupTouchListener(View view, boolean isCircle) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // Position aktualisieren
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) v.getLayoutParams();
                    params.leftMargin = (int) (event.getRawX() - v.getWidth() / 2);
                    params.topMargin = (int) (event.getRawY() - v.getHeight() / 2);
                    v.setLayoutParams(params);

                    if (isCircle) {
                        circleX = params.leftMargin;
                        circleY = params.topMargin;
                    } else {
                        rectX = params.leftMargin;
                        rectY = params.topMargin;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    // Position speichern
                    savePositions();
                    return true;
            }
            return false;
        });
    }

    private void savePositions() {
        prefs.edit()
                .putFloat("circle_x", circleX)
                .putFloat("circle_y", circleY)
                .putFloat("rect_x", rectX)
                .putFloat("rect_y", rectY)
                .apply();
    }

    private void loadPositions() {
        circleX = prefs.getFloat("circle_x", 100);
        circleY = prefs.getFloat("circle_y", 500);
        rectX = prefs.getFloat("rect_x", 100);
        rectY = prefs.getFloat("rect_y", 300);
    }

    // ==========================================
    // ACCESSIBILITY SERVICE LOGIK
    // ==========================================

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isRunning) return;

        // Prüfe ob Lidl App im Vordergrund ist
        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!packageName.contains("lidlconnect") && !packageName.contains("lidl")) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // ==========================================
        // VOLUMEN AUS RECT-BEREICH LESEN
        // ==========================================
        float currentVolume = extractVolumeFromRect(root);
        if (currentVolume > 0 && currentVolume != lastVolume) {
            lastVolume = currentVolume;
            updateVolumeStatus(currentVolume);
        }

        // ==========================================
        // REFILL BUTTON IM CIRCLE-BEREICH KLICKEN
        // ==========================================
        if (currentVolume <= TARGET_VOLUME && currentVolume > 0) {
            clickRefillButtonInCircle(root);
        }

        root.recycle();
    }

    private float extractVolumeFromRect(AccessibilityNodeInfo root) {
        // Prüfe, ob der rechteckige Bereich ein "GB" enthält
        Rect rect = new Rect(
                (int) rectX,
                (int) rectY,
                (int) (rectX + 120 * getResources().getDisplayMetrics().density),
                (int) (rectY + 60 * getResources().getDisplayMetrics().density)
        );

        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("GB");
            for (AccessibilityNodeInfo node : nodes) {
                Rect nodeRect = new Rect();
                node.getBoundsInScreen(nodeRect);
                // Prüfe ob der Node im Rechteck liegt
                if (Rect.intersects(rect, nodeRect)) {
                    String text = node.getText() != null ? node.getText().toString() : "";
                    if (text.matches(".*\\d+[,\\.]\\d+\\s*GB.*")) {
                        String number = text.replace(",", ".").replace("GB", "").trim();
                        try {
                            float val = Float.parseFloat(number);
                            if (val > 0 && val < 2.0) {
                                return val;
                            }
                        } catch (NumberFormatException e) {
                            // Ignorieren
                        }
                    }
                }
                node.recycle();
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Fehler beim Volumen auslesen: " + e.getMessage());
        }

        return -1;
    }

    private void clickRefillButtonInCircle(AccessibilityNodeInfo root) {
        // Prüfe, ob der kreisförmige Bereich einen "Refill"-Button enthält
        Rect circleRect = new Rect(
                (int) circleX,
                (int) circleY,
                (int) (circleX + 80 * getResources().getDisplayMetrics().density),
                (int) (circleY + 80 * getResources().getDisplayMetrics().density)
        );

        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Refill");
            if (nodes.isEmpty()) {
                nodes = root.findAccessibilityNodeInfosByText("Refill aktivieren");
            }

            for (AccessibilityNodeInfo node : nodes) {
                Rect nodeRect = new Rect();
                node.getBoundsInScreen(nodeRect);
                if (Rect.intersects(circleRect, nodeRect)) {
                    if (node.isClickable()) {
                        // Menschliche Verzögerung (2-5 Sekunden)
                        int delay = random.nextInt(3000) + 2000;
                        updateStatus("🎯 Refill-Button gefunden! Klicke in " + (delay/1000) + "s...");

                        handler.postDelayed(() -> {
                            performHumanClick(node);
                            refillCount++;
                            updateRefillCount(refillCount);
                            updateStatus("✅ Refill #" + refillCount + " aktiviert!");
                            lastVolume = -1;
                        }, delay);
                        return;
                    }
                }
                node.recycle();
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Fehler beim Refill-Klick: " + e.getMessage());
        }
    }

    private void performHumanClick(AccessibilityNodeInfo node) {
        if (node == null) return;

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);

        float centerX = rect.centerX() + random.nextInt(20) - 10;
        float centerY = rect.centerY() + random.nextInt(20) - 10;

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        Path path = new Path();

        // Menschliche Bewegung
        if (random.nextBoolean()) {
            float startX = centerX + random.nextInt(80) - 40;
            float startY = centerY + random.nextInt(80) - 40;
            path.moveTo(startX, startY);
            path.lineTo(centerX, centerY);
        } else {
            path.moveTo(centerX, centerY);
        }

        long duration = random.nextInt(100) + 50;
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));

        dispatchGesture(gestureBuilder.build(), null, null);
        Log.d("OverlayService", "Klick auf Button an Position: " + centerX + ", " + centerY);
    }

    // ==========================================
    // STATUS UPDATES
    // ==========================================

    private void updateStatus(String status) {
        prefs.edit().putString("status", status).apply();
        Log.d("OverlayService", status);
    }

    private void updateVolumeStatus(float volume) {
        prefs.edit().putFloat("volume", volume).apply();
        Log.d("OverlayService", "Volumen: " + volume + " GB");
    }

    private void updateRefillCount(int count) {
        prefs.edit().putInt("refill_count", count).apply();
    }

    // ==========================================
    // SERVICE STEUERUNG
    // ==========================================

    public void startService() {
        isRunning = true;
        loadPositions();
        updateStatus("✅ Overlay aktiv! Ziehe die Felder auf die Lidl App.");
    }

    public void stopService() {
        isRunning = false;
        updateStatus("⏹️ Service gestoppt");
        if (windowManager != null && circleOverlay != null) {
            try {
                windowManager.removeView(circleOverlay);
                windowManager.removeView(rectOverlay);
            } catch (Exception e) {
                // Ignorieren
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.d("OverlayService", "Service unterbrochen");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopService();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }
}
