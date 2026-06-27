package com.example.lidlrefill;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Random;

public class RefillAccessibilityService extends AccessibilityService {

    private static final String TAG = "RefillService";
    private static final String PREFS_NAME = "RefillRecorderPrefs";
    private static final String KEY_REFILL_COUNT = "refill_count";

    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isMonitoring = false;
    private int refillCount = 0;

    private float buttonX = 0, buttonY = 0;
    private float volumeX = 0, volumeY = 0;
    private float swipeStartX = 0, swipeStartY = 0;
    private float swipeEndX = 0, swipeEndY = 0;
    private boolean isRecorded = false;

    private float currentVolume = 0.0f;
    private float lastVolume = 0.0f;
    private long lastCheckTime = 0;
    private float consumptionRate = 0.0f;
    private boolean isLearningPhase = true;
    private boolean isWaitingForRefill = false;
    private static final float TARGET_VOLUME = 0.35f;
    private Random random = new Random();

    private WindowManager windowManager;
    private View overlayView;
    private TextView tvStatus, tvVolume, tvRefills, tvNext;
    private Button btnPause, btnStop, btnRefresh;
    private boolean isOverlayVisible = false;
    private boolean isPaused = false;
    private boolean isScrollingToPosition = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isMonitoring || isPaused || !isRecorded) return;

        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!pkg.toLowerCase().contains("lidl") && !pkg.toLowerCase().contains("connect")) {
            return;
        }

        Log.d(TAG, "📱 Lidl App erkannt");
        performActions();
    }

    private void performActions() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.d(TAG, "⏳ Kein Root, warte...");
            return;
        }

        float volume = readVolumeAtPosition(root);
        if (volume > 0) {
            currentVolume = volume;
            updateOverlay();
            handleVolumeUpdate();
            Log.d(TAG, "📊 Volumen: " + volume + " GB");
        }

        if (shouldClick()) {
            clickRefillAtPosition(root);
        }
    }

    // ✅ SWIPE-GESTE
    private void performSwipeRefresh() {
        Log.d(TAG, "🔄 Führe Swipe-Geste aus");

        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;

        int startX = (int) (swipeStartX * width);
        int startY = (int) (swipeStartY * height);
        int endX = (int) (swipeEndX * width);
        int endY = (int) (swipeEndY * height);

        Log.d(TAG, "📌 Swipe von (" + startX + "," + startY + ") nach (" + endX + "," + endY + ")");

        long baseTime = System.currentTimeMillis();

        // ACTION_DOWN
        MotionEvent downEvent = MotionEvent.obtain(
                baseTime,
                baseTime + 10,
                MotionEvent.ACTION_DOWN,
                startX, startY, 0
        );
        dispatchGestureEvent(downEvent);
        downEvent.recycle();

        // ACTION_MOVE (in Schritten)
        int steps = 20;
        for (int i = 0; i <= steps; i++) {
            float progress = (float) i / steps;
            int currentX = startX + (int) ((endX - startX) * progress);
            int currentY = startY + (int) ((endY - startY) * progress);

            long eventTime = baseTime + (long) (300 * progress);
            MotionEvent moveEvent = MotionEvent.obtain(
                    baseTime,
                    eventTime,
                    MotionEvent.ACTION_MOVE,
                    currentX, currentY, 0
            );
            dispatchGestureEvent(moveEvent);
            moveEvent.recycle();

            try {
                Thread.sleep(300 / steps);
            } catch (InterruptedException ignored) {}
        }

        // ACTION_UP
        MotionEvent upEvent = MotionEvent.obtain(
                baseTime,
                baseTime + 310,
                MotionEvent.ACTION_UP,
                endX, endY, 0
        );
        dispatchGestureEvent(upEvent);
        upEvent.recycle();

        Log.d(TAG, "✅ Swipe-Geste ausgeführt");
        showToast("🔄 Aktualisiert!");
    }

    private void dispatchGestureEvent(MotionEvent event) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        root.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private float readVolumeAtPosition(AccessibilityNodeInfo root) {
        try {
            int x = (int) (volumeX * getResources().getDisplayMetrics().widthPixels);
            int y = (int) (volumeY * getResources().getDisplayMetrics().heightPixels);

            scrollToPosition(root, x, y);

            AccessibilityNodeInfo node = findNodeAt(root, x, y);
            if (node != null && node.getText() != null) {
                String text = node.getText().toString();
                float value = extractVolume(text);
                if (value > 0) {
                    Log.d(TAG, "✅ Volumen: " + value + " GB");
                    return value;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Fehler beim Volumen-Auslesen: " + e.getMessage());
        }
        return 0;
    }

    private void clickRefillAtPosition(AccessibilityNodeInfo root) {
        try {
            int x = (int) (buttonX * getResources().getDisplayMetrics().widthPixels);
            int y = (int) (buttonY * getResources().getDisplayMetrics().heightPixels);

            scrollToPosition(root, x, y);

            AccessibilityNodeInfo node = findNodeAt(root, x, y);
            if (node != null && node.isClickable()) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                executeRefill();
                Log.d(TAG, "✅ Refill-Button geklickt");
                return;
            }

            java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Refill aktivieren");
            for (AccessibilityNodeInfo n : nodes) {
                if (n.isClickable()) {
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    executeRefill();
                    Log.d(TAG, "✅ Refill-Button via Text gefunden");
                    return;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Fehler beim Refill-Klick: " + e.getMessage());
        }
    }

    private void scrollToPosition(AccessibilityNodeInfo root, int targetX, int targetY) {
        if (isScrollingToPosition) return;
        isScrollingToPosition = true;

        try {
            AccessibilityNodeInfo node = findNodeAt(root, targetX, targetY);
            if (node != null) {
                Rect rect = new Rect();
                node.getBoundsInScreen(rect);
                if (rect.contains(targetX, targetY)) {
                    isScrollingToPosition = false;
                    return;
                }
            }

            for (int i = 0; i < 15; i++) {
                root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}

                AccessibilityNodeInfo newRoot = getRootInActiveWindow();
                if (newRoot != null) {
                    node = findNodeAt(newRoot, targetX, targetY);
                    if (node != null) {
                        Rect rect = new Rect();
                        node.getBoundsInScreen(rect);
                        if (rect.contains(targetX, targetY)) {
                            isScrollingToPosition = false;
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Fehler beim Scrollen: " + e.getMessage());
        }
        isScrollingToPosition = false;
    }

    private AccessibilityNodeInfo findNodeAt(AccessibilityNodeInfo root, int x, int y) {
        return findNodeRecursive(root, x, y);
    }

    private AccessibilityNodeInfo findNodeRecursive(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return null;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.contains(x, y) && node.getText() != null && node.getText().length() > 0) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findNodeRecursive(child, x, y);
            if (result != null) return result;
        }
        return null;
    }

    private float extractVolume(String text) {
        try {
            String[] parts = text.split(" ");
            for (String p : parts) {
                p = p.replace(",", ".").trim();
                if (p.matches("\\d+(\\.\\d+)?")) {
                    float v = Float.parseFloat(p);
                    if (v > 0 && v < 100) return v;
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private void handleVolumeUpdate() {
        if (isWaitingForRefill) {
            handler.postDelayed(() -> {
                isWaitingForRefill = false;
                Log.d(TAG, "⏳ 2 Min Wartezeit vorbei");
            }, 120000);
            return;
        }

        long now = System.currentTimeMillis();

        if (isLearningPhase) {
            if (lastCheckTime > 0) {
                float diff = lastVolume - currentVolume;
                long minutes = (now - lastCheckTime) / 1000 / 60;
                if (minutes > 0 && diff > 0) {
                    consumptionRate = diff / minutes;
                    if (consumptionRate > 0.001) {
                        isLearningPhase = false;
                        showToast("✅ Verbrauch: " + String.format("%.3f", consumptionRate) + " GB/min");
                    }
                }
            }
            lastVolume = currentVolume;
            lastCheckTime = now;
            updateOverlay();
            return;
        }

        if (consumptionRate > 0.001) {
            float remaining = currentVolume - TARGET_VOLUME;
            if (remaining <= 0) return;
            float minutes = remaining / consumptionRate;
            int delay = (int) (minutes * 60 * (0.8 + random.nextDouble() * 0.4));
            delay = Math.max(30, Math.min(delay, 1800));
            updateOverlay();
        }

        lastVolume = currentVolume;
        lastCheckTime = now;
        updateOverlay();
    }

    private boolean shouldClick() {
        return currentVolume <= TARGET_VOLUME && !isWaitingForRefill;
    }

    private void executeRefill() {
        refillCount++;
        isWaitingForRefill = true;
        isLearningPhase = true;

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_REFILL_COUNT, refillCount).apply();

        Log.d(TAG, "✅ Refill #" + refillCount + " ausgeführt!");
        showToast("✅ Refill #" + refillCount + " ausgeführt!");
        updateOverlay();

        handler.postDelayed(() -> {
            isWaitingForRefill = false;
            showToast("⏳ Prüfe neues Volumen...");
            updateOverlay();
        }, 120000);
    }

    private void showOverlay() {
        if (isOverlayVisible) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        overlayView = inflater.inflate(R.layout.watermark_overlay, null);

        tvStatus = overlayView.findViewById(R.id.tv_overlay_status);
        tvVolume = overlayView.findViewById(R.id.tv_overlay_volume);
        tvRefills = overlayView.findViewById(R.id.tv_overlay_refills);
        tvNext = overlayView.findViewById(R.id.tv_overlay_next_check);
        btnPause = overlayView.findViewById(R.id.btn_overlay_pause);
        btnStop = overlayView.findViewById(R.id.btn_overlay_stop);
        btnRefresh = overlayView.findViewById(R.id.btn_overlay_refresh);

        btnPause.setOnClickListener(v -> {
            isPaused = !isPaused;
            btnPause.setText(isPaused ? "▶️" : "⏸️");
            tvStatus.setText(isPaused ? "⏸️ PAUSIERT" : "🔄 AKTIV");
            tvStatus.setTextColor(isPaused ? Color.parseColor("#FF9800") : Color.parseColor("#4CAF50"));
        });

        btnStop.setOnClickListener(v -> {
            isMonitoring = false;
            removeOverlay();
            showToast("⏹️ Gestoppt");
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("service_running", false).apply();
        });

        btnRefresh.setOnClickListener(v -> {
            showToast("🔄 Aktualisiere...");
            performSwipeRefresh();
            handler.postDelayed(this::performActions, 1500);
        });

        overlayView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    return true;
                case MotionEvent.ACTION_MOVE:
                    v.setX(v.getX() + event.getRawX() - event.getX());
                    v.setY(v.getY() + event.getRawY() - event.getY());
                    return true;
            }
            return false;
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20;
        params.y = 20;

        windowManager.addView(overlayView, params);
        isOverlayVisible = true;
        updateOverlay();
    }

    private void removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
                overlayView = null;
                isOverlayVisible = false;
            } catch (Exception ignored) {}
        }
    }

    private void updateOverlay() {
        if (!isOverlayVisible) return;
        if (tvStatus != null) {
            tvStatus.setText(isPaused ? "⏸️ PAUSIERT" : "🔄 AKTIV");
            tvStatus.setTextColor(isPaused ? Color.parseColor("#FF9800") : Color.parseColor("#4CAF50"));
        }
        if (tvVolume != null) tvVolume.setText("📊 " + String.format("%.2f", currentVolume) + " GB");
        if (tvRefills != null) tvRefills.setText("🔄 " + refillCount);
        if (tvNext != null) {
            if (isLearningPhase) {
                tvNext.setText("📖 Lernphase");
            } else if (consumptionRate > 0.001) {
                float remaining = currentVolume - TARGET_VOLUME;
                int seconds = (int) ((remaining / consumptionRate) * 60);
                seconds = Math.max(30, Math.min(seconds, 1800));
                tvNext.setText("⏱️ " + seconds + "s");
            } else {
                tvNext.setText("⏱️ --");
            }
        }
    }

    private void showToast(String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "⏹️ Unterbrochen");
        removeOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("action")) {
            String action = intent.getStringExtra("action");
            switch (action) {
                case "start_monitoring":
                    isMonitoring = true;
                    isPaused = false;
                    buttonX = intent.getFloatExtra("button_x", 0);
                    buttonY = intent.getFloatExtra("button_y", 0);
                    volumeX = intent.getFloatExtra("volume_x", 0);
                    volumeY = intent.getFloatExtra("volume_y", 0);
                    swipeStartX = intent.getFloatExtra("swipe_start_x", 0.5f);
                    swipeStartY = intent.getFloatExtra("swipe_start_y", 0.15f);
                    swipeEndX = intent.getFloatExtra("swipe_end_x", 0.5f);
                    swipeEndY = intent.getFloatExtra("swipe_end_y", 0.50f);
                    isRecorded = buttonX > 0 && volumeX > 0;
                    if (isRecorded) {
                        showOverlay();
                        handler.postDelayed(this::performActions, 1500);
                    }
                    Log.d(TAG, "▶️ Monitoring gestartet");
                    break;
                case "stop_monitoring":
                    isMonitoring = false;
                    handler.removeCallbacksAndMessages(null);
                    removeOverlay();
                    Log.d(TAG, "⏹️ Gestoppt");
                    break;
                case "refresh":
                    showToast("🔄 Aktualisiere...");
                    performSwipeRefresh();
                    handler.postDelayed(this::performActions, 1500);
                    break;
            }
        }
        return START_STICKY;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);
        Log.d(TAG, "🔌 Service verbunden");
    }
}
