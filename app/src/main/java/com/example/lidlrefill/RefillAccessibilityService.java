package com.example.lidlrefill;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isMonitoring || !isRecorded) return;

        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        
        if (!packageName.toLowerCase().contains("lidl") && 
            !packageName.toLowerCase().contains("connect")) {
            return;
        }

        Log.d(TAG, "📱 Lidl App erkannt: " + packageName);
        handleLidlApp();
    }

    private void handleLidlApp() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        float volume = readVolumeAtPosition(root);
        if (volume > 0) {
            currentVolume = volume;
            updateVolumeDisplay(volume);
            handleVolumeUpdate();
        }

        if (shouldClickRefill()) {
            clickRefillButton(root);
        }
    }

    private float readVolumeAtPosition(AccessibilityNodeInfo root) {
        try {
            int x = (int) (volumeX * getResources().getDisplayMetrics().widthPixels);
            int y = (int) (volumeY * getResources().getDisplayMetrics().heightPixels);

            AccessibilityNodeInfo node = findNodeAtPosition(root, x, y);
            if (node != null && node.getText() != null) {
                String text = node.getText().toString();
                float value = extractVolumeFromText(text);
                if (value > 0) {
                    Log.d(TAG, "📊 Volumen gelesen: " + value + " GB");
                    return value;
                }
            }
            
            float fallbackValue = findVolumeInText(root);
            if (fallbackValue > 0) {
                Log.d(TAG, "📊 Volumen (Fallback): " + fallbackValue + " GB");
                return fallbackValue;
            }
        } catch (Exception e) {
            Log.e(TAG, "Fehler beim Volumen-Auslesen: " + e.getMessage());
        }
        return 0;
    }

    private AccessibilityNodeInfo findNodeAtPosition(AccessibilityNodeInfo root, int x, int y) {
        return findNodeAtPositionRecursive(root, x, y);
    }

    private AccessibilityNodeInfo findNodeAtPositionRecursive(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return null;

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        
        if (rect.contains(x, y) && node.getText() != null && node.getText().length() > 0) {
            return node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findNodeAtPositionRecursive(child, x, y);
            if (result != null) return result;
        }
        return null;
    }

    private float extractVolumeFromText(String text) {
        if (text == null || text.isEmpty()) return 0;
        
        try {
            String[] parts = text.split(" ");
            for (String part : parts) {
                part = part.replace(",", ".").trim();
                if (part.matches("\\d+(\\.\\d+)?")) {
                    float value = Float.parseFloat(part);
                    if (value > 0 && value < 100) {
                        return value;
                    }
                }
            }
        } catch (Exception e) {
            // Ignorieren
        }
        return 0;
    }

    private float findVolumeInText(AccessibilityNodeInfo root) {
        return findVolumeInTextRecursive(root);
    }

    private float findVolumeInTextRecursive(AccessibilityNodeInfo node) {
        if (node == null) return 0;

        if (node.getText() != null) {
            String text = node.getText().toString();
            if (text.contains("GB") || text.contains("Gb")) {
                float value = extractVolumeFromText(text);
                if (value > 0) return value;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            float result = findVolumeInTextRecursive(child);
            if (result > 0) return result;
        }
        return 0;
    }

    private void updateVolumeDisplay(float volume) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putFloat("current_volume", volume).apply();
    }

    private void handleVolumeUpdate() {
        if (isWaitingForRefill) {
            handler.postDelayed(() -> {
                isWaitingForRefill = false;
                Log.d(TAG, "⏳ 2 Minuten Wartezeit vorbei, prüfe erneut");
            }, 120000);
            return;
        }

        long now = System.currentTimeMillis();

        if (isLearningPhase) {
            if (lastCheckTime > 0) {
                float diff = lastVolume - currentVolume;
                long timeDiff = (now - lastCheckTime) / 1000 / 60;
                if (timeDiff > 0 && diff > 0) {
                    consumptionRate = diff / timeDiff;
                    Log.d(TAG, "📊 Verbrauch: " + String.format("%.3f", consumptionRate) + " GB/min");
                    
                    if (consumptionRate > 0.001) {
                        isLearningPhase = false;
                        Log.d(TAG, "✅ Lernphase beendet! Verbrauch: " + String.format("%.3f", consumptionRate) + " GB/min");
                        showToast("✅ Verbrauch gelernt: " + String.format("%.3f", consumptionRate) + " GB/min");
                    }
                }
            }
            lastVolume = currentVolume;
            lastCheckTime = now;
            return;
        }

        if (consumptionRate > 0.001) {
            float gbRemaining = currentVolume - TARGET_VOLUME;
            if (gbRemaining <= 0) {
                Log.d(TAG, "⚡ Volumen unter " + TARGET_VOLUME + " GB! Refill wird ausgeführt.");
                return;
            }

            float estimatedMinutes = gbRemaining / consumptionRate;
            double variation = 0.8 + (random.nextDouble() * 0.4);
            int delaySeconds = (int) (estimatedMinutes * 60 * variation);
            delaySeconds = Math.max(30, Math.min(delaySeconds, 1800));
            
            Log.d(TAG, "⏱️ Warte " + delaySeconds + "s bis nächste Prüfung");
            showToast("⏱️ Nächste Prüfung in " + delaySeconds + "s");
            
            handler.postDelayed(() -> {
                Log.d(TAG, "🔍 Prüfe Volumen...");
            }, delaySeconds * 1000L);
        }

        lastVolume = currentVolume;
        lastCheckTime = now;
    }

    private boolean shouldClickRefill() {
        if (currentVolume <= TARGET_VOLUME && !isWaitingForRefill) {
            Log.d(TAG, "⚡ Volumen bei " + currentVolume + " GB -> Refill nötig!");
            return true;
        }
        return false;
    }

    private void clickRefillButton(AccessibilityNodeInfo root) {
        int x = (int) (buttonX * getResources().getDisplayMetrics().widthPixels);
        int y = (int) (buttonY * getResources().getDisplayMetrics().heightPixels);

        AccessibilityNodeInfo node = findNodeAtPosition(root, x, y);
        if (node != null && node.isClickable()) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            executeRefill();
            return;
        }

        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Refill aktivieren");
        for (AccessibilityNodeInfo n : nodes) {
            if (n.isClickable()) {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                executeRefill();
                return;
            }
        }

        List<AccessibilityNodeInfo> unlimitedNodes = root.findAccessibilityNodeInfosByText("Unlimited Refill");
        for (AccessibilityNodeInfo n : unlimitedNodes) {
            n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            handler.postDelayed(() -> {
                AccessibilityNodeInfo newRoot = getRootInActiveWindow();
                if (newRoot != null) {
                    List<AccessibilityNodeInfo> newNodes = newRoot.findAccessibilityNodeInfosByText("Refill aktivieren");
                    for (AccessibilityNodeInfo nn : newNodes) {
                        if (nn.isClickable()) {
                            nn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            executeRefill();
                            return;
                        }
                    }
                }
            }, 500);
            return;
        }
    }

    private void executeRefill() {
        refillCount++;
        isWaitingForRefill = true;
        isLearningPhase = true;
        
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_REFILL_COUNT, refillCount).apply();
        
        Log.d(TAG, "✅ Refill #" + refillCount + " ausgeführt!");
        showToast("✅ Refill #" + refillCount + " ausgeführt! Warte 2 Minuten...");
        
        handler.postDelayed(() -> {
            isWaitingForRefill = false;
            Log.d(TAG, "⏳ 2 Minuten Wartezeit vorbei, prüfe Volumen");
            showToast("⏳ Prüfe neues Volumen...");
        }, 120000);
    }

    private void showToast(String message) {
        handler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "⏹️ Service unterbrochen");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("action")) {
            String action = intent.getStringExtra("action");
            
            switch (action) {
                case "start_monitoring":
                    isMonitoring = true;
                    buttonX = intent.getFloatExtra("button_x", 0);
                    buttonY = intent.getFloatExtra("button_y", 0);
                    volumeX = intent.getFloatExtra("volume_x", 0);
                    volumeY = intent.getFloatExtra("volume_y", 0);
                    isRecorded = (buttonX > 0 && volumeX > 0);
                    Log.d(TAG, "▶️ Monitoring gestartet");
                    break;
                case "stop_monitoring":
                    isMonitoring = false;
                    handler.removeCallbacksAndMessages(null);
                    Log.d(TAG, "⏹️ Monitoring gestoppt");
                    break;
                case "check_refill":
                    Log.d(TAG, "🔍 Prüfe auf Refill...");
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        handleLidlApp();
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
                          AccessibilityEvent.TYPE_VIEW_CLICKED |
                          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);

        Log.d(TAG, "🔌 Accessibility Service verbunden!");
    }
}
