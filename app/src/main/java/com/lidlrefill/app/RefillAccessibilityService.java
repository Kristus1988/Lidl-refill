package com.lidlrefill.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

public class RefillAccessibilityService extends AccessibilityService {

    private static RefillAccessibilityService instance;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Service ist bereit
    }

    @Override
    public void onInterrupt() {
        // Service wurde unterbrochen
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    public static RefillAccessibilityService getInstance() {
        return instance;
    }

    // ========== MENSCHLICHER TAP ==========
    public void performHumanTap(int x, int y) {
        if (instance == null) return;

        int offsetX = (int) (Math.random() * 10 - 5);
        int offsetY = (int) (Math.random() * 10 - 5);
        int finalX = x + offsetX;
        int finalY = y + offsetY;

        handler.post(() -> {
            Path path = new Path();
            path.moveTo(finalX, finalY);

            GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
            gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 80));

            dispatchGesture(gestureBuilder.build(), null, null);
        });
    }

    // ========== MENSCHLICHER SWIPE ==========
    public void performHumanSwipe(int startX, int startY, int endX, int endY, long duration) {
        if (instance == null) return;

        int offsetX1 = (int) (Math.random() * 20 - 10);
        int offsetY1 = (int) (Math.random() * 20 - 10);
        int offsetX2 = (int) (Math.random() * 20 - 10);
        int offsetY2 = (int) (Math.random() * 20 - 10);

        int finalStartX = startX + offsetX1;
        int finalStartY = startY + offsetY1;
        int finalEndX = endX + offsetX2;
        int finalEndY = endY + offsetY2;

        long finalDuration = duration + (long) (Math.random() * 200 - 100);

        handler.post(() -> {
            Path path = new Path();
            path.moveTo(finalStartX, finalStartY);

            int midX = (finalStartX + finalEndX) / 2 + (int) (Math.random() * 30 - 15);
            int midY = (finalStartY + finalEndY) / 2 + (int) (Math.random() * 30 - 15);
            path.quadTo(midX, midY, finalEndX, finalEndY);

            GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
            gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, finalDuration));

            dispatchGesture(gestureBuilder.build(), null, null);
        });
    }
}
