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

import java.util.List;
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

    // ✅ WIE AUTOKLICKER: SCROLLEN UND WARTEN
    private boolean isScrolling = false;
    private int scrollAttempts = 0;
    private static final int MAX_SCROLL_ATTEMPTS = 5;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isMonitoring || isPaused || !isRecorded) return;

        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!pkg.toLowerCase().contains("lidl") && !pkg.toLowerCase().contains("connect")) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // ✅ 1. ZUERST: Zum Unlimited Refill Bereich scrollen (WIE AUTOKLICKER!)
        if (!isScrolling) {
            scrollToUnlimitedRefill(root);
            return;
        }

        // ✅ 2. VOLUMEN AUSLESEN (wenn nicht am scrollen)
        float volume = readVolume(root);
        if (volume > 0) {
            currentVolume = volume;
            updateOverlay();
            handleVolumeUpdate();
        }

        // ✅ 3. REFILL PRÜFEN (mit Scroll-Fallback)
        if (shouldClick()) {
            clickRefillWithScroll(root);
        }
    }

    // ✅ SCROLLEN ZUM UNLIMITED REFILL BEREICH (WIE AUTOKLICKER!)
    private void scrollToUnlimitedRefill(AccessibilityNodeInfo root) {
        if (isScrolling) return;
        isScrolling = true;
        scrollAttempts = 0;

        Log.d(TAG, "📜 Scrolle zum Unlimited Refill Bereich...");

        // Suche nach "Unlimited Refill" Text
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Unlimited Refill");
        if (!nodes.isEmpty()) {
            // Scrolle zu diesem Element
            AccessibilityNodeInfo target = nodes.get(0);
            target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            Log.d(TAG, "📜 Zu 'Unlimited Refill' gescrollt");
            
            // Warten, bis der Inhalt geladen ist
            handler.postDelayed(() -> {
                isScrolling = false;
                Log.d(TAG, "✅ Scrollen abgeschlossen, prüfe Inhalt");
                // Erneut prüfen
                AccessibilityNodeInfo newRoot = getRootInActiveWindow();
                if (newRoot != null) {
                    float volume = readVolume(newRoot);
                    if (volume > 0) {
                        currentVolume = volume;
                        updateOverlay();
                        handleVolumeUpdate();
                    }
                    if (shouldClick()) {
                        clickRefillWithScroll(newRoot);
                    }
                }
            }, 800);
            return;
        }

        // Fallback: Suche nach "Unlimited"
        List<AccessibilityNodeInfo> unlimitedNodes = root.findAccessibilityNodeInfosByText("Unlimited");
        if (!unlimitedNodes.isEmpty()) {
            unlimitedNodes.get(0).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            Log.d(TAG, "📜 Zu 'Unlimited' gescrollt");
            handler.postDelayed(() -> {
                isScrolling = false;
            }, 800);
            return;
        }

        // Fallback: Suche nach "Refill"
        List<AccessibilityNodeInfo> refillNodes = root.findAccessibilityNodeInfosByText("Refill");
        if (!refillNodes.isEmpty()) {
            refillNodes.get(0).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            Log.d(TAG, "📜 Zu 'Refill' gescrollt");
            handler.postDelayed(() -> {
                isScrolling = false;
            }, 800);
            return;
        }

        // Fallback: Generisches Scrollen nach unten
        root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        Log.d(TAG, "📜 Generisches Scrollen nach unten");
        handler.postDelayed(() -> {
            isScrolling = false;
        }, 800);
    }

    // ✅ KLICKEN MIT SCROLL-FALLBACK (WIE AUTOKLICKER!)
    private void clickRefillWithScroll(AccessibilityNodeInfo root) {
        // Zuerst versuchen, den Button an der gespeicherten Position zu finden
        int x = (int) (buttonX * getResources().getDisplayMetrics().widthPixels);
        int y = (int) (buttonY * getResources().getDisplayMetrics().heightPixels);
        
        AccessibilityNodeInfo node = findNodeAt(root, x, y);
        if (node != null && node.isClickable()) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            executeRefill();
            return;
        }

        // Suche nach "Refill aktivieren" Button
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Refill aktivieren");
        for (AccessibilityNodeInfo n : nodes) {
            if (n.isClickable()) {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                executeRefill();
                return;
            }
        }

        // Suche nach "GUTHABEN AUFLADEN" Button
        List<AccessibilityNodeInfo> chargeNodes = root.findAccessibilityNodeInfosByText("GUTHABEN AUFLADEN");
        for (AccessibilityNodeInfo n : chargeNodes) {
            if (n.isClickable()) {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                executeRefill();
                return;
            }
        }

        // Wenn nichts gefunden: Scrolle und versuche es erneut
        if (scrollAttempts < MAX_SCROLL_ATTEMPTS) {
            scrollAttempts++;
            Log.d(TAG, "🔄 Scroll-Versuch " + scrollAttempts + "/" + MAX_SCROLL_ATTEMPTS);
            root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            handler.postDelayed(() -> {
                AccessibilityNodeInfo newRoot = getRootInActiveWindow();
                if (newRoot != null) {
                    clickRefillWithScroll(newRoot);
                }
            }, 500);
        } else {
            Log.d(TAG, "⚠️ Max Scroll-Versuche erreicht, Button nicht gefunden");
            showToast("⚠️ Refill-Button nicht gefunden!");
            scrollAttempts = 0;
        }
    }

    private float readVolume(AccessibilityNodeInfo root) {
        try {
            int x = (int) (volumeX * getResources().getDisplayMetrics().widthPixels);
            int y = (int) (volumeY * getResources().getDisplayMetrics().heightPixels);
            AccessibilityNodeInfo node = findNodeAt(root, x, y);
            if (node != null && node.getText() != null) {
                String text = node.getText().toString();
                float value = extractVolume(text);
                if (value > 0) {
                    Log.d(TAG, "📊 Volumen gelesen: " + value + " GB");
                    return value;
                }
            }
            
            // Fallback: Suche im gesamten Text
            float fallback = findVolumeInText(root);
            if (fallback > 0) {
                Log.d(TAG, "📊 Volumen (Fallback): " + fallback + " GB");
                return fallback;
            }
        } catch (Exception e) {
            Log.e(TAG, "Fehler beim Volumen-Auslesen: " + e.getMessage());
        }
        return 0;
    }

    private float findVolumeInText(AccessibilityNodeInfo root) {
        return findVolumeRecursive(root);
    }

    private float findVolumeRecursive(AccessibilityNodeInfo node) {
        if (node == null) return 0;
        if (node.getText() != null) {
            String text = node.getText().toString();
            if (text.contains("GB") || text.contains("Gb")) {
                float value = extractVolume(text);
                if (value > 0) return value;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            float result = findVolumeRecursive(child);
            if (result > 0) return result;
        }
        return 0;
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
                        showToast("✅ Verbrauch gelernt: " + String.format("%.3f", consumptionRate) + " GB/min");
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
            if (remaining <= 0) {
                // Volumen unter Ziel, Refill wird beim nächsten Event ausgeführt
                return;
            }
            float minutes = remaining / consumptionRate;
            int delay = (int) (minutes * 60 * (0.8 + random.nextDouble() * 0.4));
            delay = Math.max(30, Math.min(delay, 1800));
            updateOverlay();
            handler.postDelayed(() -> {
                // Beim nächsten Event wird geprüft
            }, delay * 1000L);
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
        isScrolling = false;
        scrollAttempts = 0;
        
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_REFILL_COUNT, refillCount).apply();
        
        Log.d(TAG, "✅ Refill #" + refillCount + " ausgeführt!");
        showToast("✅ Refill #" + refillCount + " ausgeführt!");
        updateOverlay();
        
        handler.postDelayed(() -> {
            isWaitingForRefill = false;
            showToast("⏳ Prüfe neues Volumen...");
            updateOverlay();
            // Nach Refill: neu scrollen
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                scrollToUnlimitedRefill(root);
            }
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
            isScrolling = false;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                scrollToUnlimitedRefill(root);
                handler.postDelayed(() -> {
                    float volume = readVolume(root);
                    if (volume > 0) {
                        currentVolume = volume;
                        updateOverlay();
                        showToast("📊 " + String.format("%.2f", volume) + " GB");
                    } else {
                        showToast("⚠️ Kein Volumen gefunden");
                    }
                }, 1000);
            }
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
                    isScrolling = false;
                    scrollAttempts = 0;
                    buttonX = intent.getFloatExtra("button_x", 0);
                    buttonY = intent.getFloatExtra("button_y", 0);
                    volumeX = intent.getFloatExtra("volume_x", 0);
                    volumeY = intent.getFloatExtra("volume_y", 0);
                    isRecorded = buttonX > 0 && volumeX > 0;
                    if (isRecorded) {
                        showOverlay();
                        // Sofort nach dem Start scrollen
                        handler.postDelayed(() -> {
                            AccessibilityNodeInfo root = getRootInActiveWindow();
                            if (root != null) {
                                scrollToUnlimitedRefill(root);
                            }
                        }, 1000);
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
                    isScrolling = false;
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        scrollToUnlimitedRefill(root);
                        handler.postDelayed(() -> {
                            float volume = readVolume(root);
                            if (volume > 0) {
                                currentVolume = volume;
                                updateOverlay();
                                showToast("📊 " + String.format("%.2f", volume) + " GB");
                            }
                        }, 1000);
                    }
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
