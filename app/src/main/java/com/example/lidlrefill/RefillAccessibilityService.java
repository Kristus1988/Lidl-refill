package com.example.lidlrefill;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

public class RefillAccessibilityService extends AccessibilityService {

    private static final String TAG = "RefillService";
    private static final String PREFS_NAME = "RefillRecorderPrefs";
    private static final String KEY_REFILL_COUNT = "refill_count";
    private static final String KEY_BUTTON_X = "button_x";
    private static final String KEY_BUTTON_Y = "button_y";
    private static final String KEY_BUTTON_WIDTH = "button_width";
    private static final String KEY_BUTTON_HEIGHT = "button_height";
    private static final String KEY_IS_RECORDED = "is_recorded";

    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isMonitoring = false;
    private boolean isRecordingMode = false;
    private int refillCount = 0;

    // Gespeicherte Button-Position
    private float savedX = 0;
    private float savedY = 0;
    private float savedWidth = 0;
    private float savedHeight = 0;
    private boolean isButtonRecorded = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isMonitoring) return;

        // Lade gespeicherte Position
        loadSavedPosition();

        // Nur reagieren, wenn die Lidl App im Vordergrund ist
        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!packageName.contains("lidl") && !packageName.contains("Lidl")) {
            return;
        }

        Log.d(TAG, "📱 Lidl App erkannt: " + packageName);

        // Prüfe, ob der Unlimited Refill Bereich sichtbar ist
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // 1. Versuche mit gespeicherter Position zu klicken
        if (isButtonRecorded && savedX > 0 && savedY > 0) {
            if (clickAtSavedPosition(root)) {
                Log.d(TAG, "✅ Refill an gespeicherter Position geklickt!");
                return;
            }
        }

        // 2. Suche nach "Refill aktivieren" Button
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Refill aktivieren");
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isClickable()) {
                // Speichern, wenn Recorder aktiv
                if (isRecordingMode) {
                    saveButtonPosition(node);
                    isRecordingMode = false;
                    showToast("✅ Refill-Button Position gespeichert!");
                    return;
                }

                // Klicken
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                refillCount++;
                saveRefillCount();
                showToast("✅ Refill #" + refillCount + " ausgeführt!");
                Log.d(TAG, "✅ Refill #" + refillCount + " ausgeführt!");
                return;
            }
        }

        // 3. Suche nach "Unlimited Refill" und scrolle dorthin
        List<AccessibilityNodeInfo> unlimitedNodes = root.findAccessibilityNodeInfosByText("Unlimited Refill");
        for (AccessibilityNodeInfo node : unlimitedNodes) {
            // Scrolle zum Bereich
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            
            // Warte kurz und suche dann erneut
            handler.postDelayed(() -> {
                AccessibilityNodeInfo newRoot = getRootInActiveWindow();
                if (newRoot != null) {
                    List<AccessibilityNodeInfo> newNodes = newRoot.findAccessibilityNodeInfosByText("Refill aktivieren");
                    for (AccessibilityNodeInfo n : newNodes) {
                        if (n.isClickable()) {
                            if (isRecordingMode) {
                                saveButtonPosition(n);
                                isRecordingMode = false;
                                showToast("✅ Refill-Button Position gespeichert!");
                                return;
                            }
                            n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            refillCount++;
                            saveRefillCount();
                            showToast("✅ Refill #" + refillCount + " ausgeführt!");
                            Log.d(TAG, "✅ Refill #" + refillCount + " ausgeführt!");
                        }
                    }
                }
            }, 500);
            return;
        }
    }

    private boolean clickAtSavedPosition(AccessibilityNodeInfo root) {
        // Suche nach einem Element an der gespeicherten Position
        // AccessibilityService kann nicht direkt an Koordinaten klicken,
        // aber wir können den Button über den Text finden
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Refill aktivieren");
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isClickable()) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                refillCount++;
                saveRefillCount();
                showToast("✅ Refill #" + refillCount + " ausgeführt!");
                return true;
            }
        }
        return false;
    }

    private void saveButtonPosition(AccessibilityNodeInfo node) {
        android.graphics.Rect rect = new android.graphics.Rect();
        node.getBoundsInScreen(rect);
        
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putFloat(KEY_BUTTON_X, rect.left);
        editor.putFloat(KEY_BUTTON_Y, rect.top);
        editor.putFloat(KEY_BUTTON_WIDTH, rect.width());
        editor.putFloat(KEY_BUTTON_HEIGHT, rect.height());
        editor.putBoolean(KEY_IS_RECORDED, true);
        editor.apply();

        isButtonRecorded = true;
        savedX = rect.left;
        savedY = rect.top;
        savedWidth = rect.width();
        savedHeight = rect.height();

        Log.d(TAG, "💾 Button gespeichert bei (" + rect.left + ", " + rect.top + ")");
    }

    private void loadSavedPosition() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        isButtonRecorded = prefs.getBoolean(KEY_IS_RECORDED, false);
        if (isButtonRecorded) {
            savedX = prefs.getFloat(KEY_BUTTON_X, 0);
            savedY = prefs.getFloat(KEY_BUTTON_Y, 0);
            savedWidth = prefs.getFloat(KEY_BUTTON_WIDTH, 0);
            savedHeight = prefs.getFloat(KEY_BUTTON_HEIGHT, 0);
        }
    }

    private void saveRefillCount() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_REFILL_COUNT, refillCount).apply();
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
                    Log.d(TAG, "▶️ Monitoring gestartet");
                    break;
                case "stop_monitoring":
                    isMonitoring = false;
                    Log.d(TAG, "⏹️ Monitoring gestoppt");
                    break;
                case "check_refill":
                    Log.d(TAG, "🔍 Prüfe auf Refill...");
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        onAccessibilityEvent(new AccessibilityEvent());
                    }
                    break;
                case "start_recording":
                    isRecordingMode = true;
                    showToast("📝 Aufnahme-Modus aktiv! Klicke auf Refill-Button.");
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
