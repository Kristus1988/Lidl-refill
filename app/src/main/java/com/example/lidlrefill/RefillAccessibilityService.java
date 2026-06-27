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

    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isMonitoring = false;
    private int refillCount = 0;
    private String targetPackage = null;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isMonitoring) return;

        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        
        // Prüfen ob die Ziel-App im Vordergrund ist
        if (targetPackage != null && !packageName.equals(targetPackage)) {
            return;
        }

        Log.d(TAG, "📱 Lidl App erkannt: " + packageName);

        // Suche nach dem Refill-Button
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // 1. Suche nach "Refill aktivieren" Button
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Refill aktivieren");
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isClickable()) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                refillCount++;
                saveRefillCount();
                showToast("✅ Refill #" + refillCount + " ausgeführt!");
                Log.d(TAG, "✅ Refill #" + refillCount + " ausgeführt!");
                return;
            }
        }

        // 2. Suche nach "Unlimited Refill" und scrolle dorthin
        List<AccessibilityNodeInfo> unlimitedNodes = root.findAccessibilityNodeInfosByText("Unlimited Refill");
        for (AccessibilityNodeInfo node : unlimitedNodes) {
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            
            handler.postDelayed(() -> {
                AccessibilityNodeInfo newRoot = getRootInActiveWindow();
                if (newRoot != null) {
                    List<AccessibilityNodeInfo> newNodes = newRoot.findAccessibilityNodeInfosByText("Refill aktivieren");
                    for (AccessibilityNodeInfo n : newNodes) {
                        if (n.isClickable()) {
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

        // 3. Suche nach "Refill" (falls anders geschrieben)
        List<AccessibilityNodeInfo> refillNodes = root.findAccessibilityNodeInfosByText("Refill");
        for (AccessibilityNodeInfo node : refillNodes) {
            if (node.isClickable()) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                refillCount++;
                saveRefillCount();
                showToast("✅ Refill #" + refillCount + " ausgeführt!");
                Log.d(TAG, "✅ Refill #" + refillCount + " ausgeführt!");
                return;
            }
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
                    targetPackage = intent.getStringExtra("target_package");
                    Log.d(TAG, "▶️ Monitoring gestartet für: " + targetPackage);
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
