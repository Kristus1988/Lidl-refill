package com.example.lidlrefill;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.Rect;
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
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Random;

public class OverlayService extends AccessibilityService {

    private WindowManager windowManager;
    private FrameLayout circleOverlay;
    private FrameLayout rectOverlay;
    private TextView tvCircleStatus;
    private TextView tvRectStatus;

    private float circleX = 100;
    private float circleY = 500;
    private float rectX = 100;
    private float rectY = 300;

    private boolean isRunning = false;
    private int refillCount = 0;
    private float lastVolume = -1;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Random random = new Random();
    private SharedPreferences prefs;

    private static final float TARGET_VOLUME = 0.15f;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("refill_status", MODE_PRIVATE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        loadPositions();
        createOverlay();
    }

    private void createOverlay() {
        int layoutFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;

        // RUNDER KREIS
        circleOverlay = new FrameLayout(this);
        circleOverlay.setBackgroundColor(Color.parseColor("#4FC3F7"));
        circleOverlay.setAlpha(0.7f);
        circleOverlay.setElevation(100);

        tvCircleStatus = new TextView(this);
        tvCircleStatus.setText("🔵 Refill");
        tvCircleStatus.setTextColor(Color.WHITE);
        tvCircleStatus.setTextSize(12);
        tvCircleStatus.setPadding(8, 8, 8, 8);
        circleOverlay.addView(tvCircleStatus);

        int size = (int) (80 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams circleParams = new FrameLayout.LayoutParams(size, size);
        circleParams.gravity = Gravity.TOP | Gravity.START;
        circleParams.leftMargin = (int) circleX;
        circleParams.topMargin = (int) circleY;
        circleOverlay.setLayoutParams(circleParams);
        setupTouchListener(circleOverlay, true);

        // RECHTECK
        rectOverlay = new FrameLayout(this);
        rectOverlay.setBackgroundColor(Color.parseColor("#FF6F00"));
        rectOverlay.setAlpha(0.5f);
        rectOverlay.setElevation(100);

        tvRectStatus = new TextView(this);
        tvRectStatus.setText("🟦 Volumen");
        tvRectStatus.setTextColor(Color.WHITE);
        tvRectStatus.setTextSize(12);
        tvRectStatus.setPadding(8, 8, 8, 8);
        rectOverlay.addView(tvRectStatus);

        int rectWidth = (int) (120 * getResources().getDisplayMetrics().density);
        int rectHeight = (int) (60 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams rectParams = new FrameLayout.LayoutParams(rectWidth, rectHeight);
        rectParams.gravity = Gravity.TOP | Gravity.START;
        rectParams.leftMargin = (int) rectX;
        rectParams.topMargin = (int) rectY;
        rectOverlay.setLayoutParams(rectParams);
        setupTouchListener(rectOverlay, false);

        // CONTAINER
        FrameLayout container = new FrameLayout(this);
        container.addView(circleOverlay);
        container.addView(rectOverlay);

        WindowManager.LayoutParams overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                layoutFlags,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.START;

        windowManager.addView(container, overlayParams);
        isRunning = true;
        updateStatus("✅ Overlay aktiv! Ziehe die Felder auf die Lidl App.");
    }

    private void setupTouchListener(View view, boolean isCircle) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    return true;
                case MotionEvent.ACTION_MOVE:
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isRunning) return;

        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!packageName.contains("lidlconnect") && !packageName.contains("lidl")) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        float currentVolume = extractVolumeFromRect(root);
        if (currentVolume > 0 && currentVolume != lastVolume) {
            lastVolume = currentVolume;
            updateVolumeStatus(currentVolume);
        }

        if (currentVolume <= TARGET_VOLUME && currentVolume > 0) {
            clickRefillButtonInCircle(root);
        }

        root.recycle();
    }

    private float extractVolumeFromRect(AccessibilityNodeInfo root) {
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

    private void updateStatus(String status) {
        prefs.edit().putString("status", status).apply();
        Log.d("OverlayService", status);
    }

    private void updateVolumeStatus(float volume) {
        prefs.edit().putFloat("volume", volume).apply();
    }

    private void updateRefillCount(int count) {
        prefs.edit().putInt("refill_count", count).apply();
    }

    @Override
    public void onInterrupt() {
        Log.d("OverlayService", "Service unterbrochen");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (windowManager != null) {
            try {
                windowManager.removeView(circleOverlay);
                windowManager.removeView(rectOverlay);
            } catch (Exception e) {
                // Ignorieren
            }
        }
        updateStatus("⏹️ Service gestoppt");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }
}
