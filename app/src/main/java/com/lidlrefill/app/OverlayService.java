package com.lidlrefill.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.graphics.Rect;
import android.util.Log;

public class OverlayService extends AccessibilityService {
    private WindowManager windowManager;
    private FrameLayout floatingView;
    private Button swipeButton, ocrButton, configButton, startButton;
    private Rect ocrRect = new Rect(100, 100, 300, 300);
    private boolean isDragging = false, isOcrMode = false, isSwipeMode = false;
    private float lastX, lastY;
    private Handler handler = new Handler(Looper.getMainLooper());
    
    private boolean isRunning = false;
    private String lastOcrText = "";
    private long lastOcrTime = 0;
    private double consumptionRate = 0;
    private int currentStep = 0;
    
    // Für bessere Kompatibilität mit System-Apps
    private static final int[] GESTURE_COORDINATES = {
        300, 800,  // Start X,Y
        300, 200   // End X,Y
    };
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Wird für Accessibility-Events benötigt
    }
    
    @Override
    public void onInterrupt() {
        // Wird bei Unterbrechung aufgerufen
    }
    
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        
        // **WICHTIG: Accessibility Service für ALLE Apps konfigurieren**
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE |
                    AccessibilityServiceInfo.FLAG_ENABLE_ACCESSIBILITY_VOLUME |
                    AccessibilityServiceInfo.DEFAULT;
        
        // **ALLES abdecken - auch System-Apps**
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC |
                           AccessibilityServiceInfo.FEEDBACK_HAPTIC |
                           AccessibilityServiceInfo.FEEDBACK_AUDIBLE |
                           AccessibilityServiceInfo.FEEDBACK_VISUAL |
                           AccessibilityServiceInfo.FEEDBACK_SPOKEN;
        
        // **WICHTIG: Für System-Apps**
        info.notificationTimeout = 50;
        info.packageNames = null; // Alle Apps, auch System-Apps
        
        setServiceInfo(info);
        createOverlay();
    }
    
    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // **ÜBER ALLEN Apps - auch System-Apps**
        int layoutFlag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD; // Über Sperrbildschirm!
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN |
                         WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED; // Sperrbildschirm
        }
        
        // **Höchste Priorität für Overlay**
        int layerType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE;
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layerType,
                layoutFlag,
                PixelFormat.TRANSLUCENT
        );
        
        // **Positionierbar über ALLEN Apps**
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 50;
        params.y = 50;
        params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        
        // **Für System-Apps wichtig**
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        
        // Layout inflaten
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.overlay_layout, null);
        floatingView = new FrameLayout(this);
        floatingView.addView(view);
        
        // Buttons initialisieren
        swipeButton = view.findViewById(R.id.btnSwipe);
        ocrButton = view.findViewById(R.id.btnOcr);
        configButton = view.findViewById(R.id.btnConfig);
        startButton = view.findViewById(R.id.btnStart);
        
        setupButtons();
        setupTouchListener(params);
        
        windowManager.addView(floatingView, params);
        
        // **Overlay immer im Vordergrund halten**
        floatingView.bringToFront();
        Toast.makeText(this, "Overlay aktiv - funktioniert über ALLEN Apps!", Toast.LENGTH_LONG).show();
    }
    
    private void setupButtons() {
        swipeButton.setOnClickListener(v -> performSwipeGesture());
        
        ocrButton.setOnClickListener(v -> {
            isOcrMode = !isOcrMode;
            if (isOcrMode) {
                Toast.makeText(this, "OCR-Modus: Tippen zum Platzieren des Rechtecks", Toast.LENGTH_LONG).show();
            }
        });
        
        configButton.setOnClickListener(v -> {
            isSwipeMode = !isSwipeMode;
            Toast.makeText(this, isSwipeMode ? "Buttons verschiebbar" : "Buttons fixiert", Toast.LENGTH_SHORT).show();
        });
        
        startButton.setOnClickListener(v -> {
            if (!isRunning) {
                startAutomation();
            } else {
                stopAutomation();
            }
        });
    }
    
    private void setupTouchListener(WindowManager.LayoutParams params) {
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private float initialX, initialY;
            private boolean isDragging = false;
            
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = event.getRawX();
                        initialY = event.getRawY();
                        isDragging = false;
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        float deltaX = event.getRawX() - initialX;
                        float deltaY = event.getRawY() - initialY;
                        
                        if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                            isDragging = true;
                        }
                        
                        if (isDragging && (isSwipeMode || isOcrMode)) {
                            params.x += deltaX;
                            params.y += deltaY;
                            windowManager.updateViewLayout(floatingView, params);
                            initialX = event.getRawX();
                            initialY = event.getRawY();
                            
                            if (isOcrMode) {
                                // OCR-Rechteck aktualisieren
                                ocrRect = new Rect(params.x, params.y, 
                                                  params.x + 200, params.y + 200);
                                Toast.makeText(OverlayService.this, 
                                    "OCR-Rechteck: (" + params.x + ", " + params.y + ")", 
                                    Toast.LENGTH_SHORT).show();
                            }
                        }
                        return true;
                        
                    case MotionEvent.ACTION_UP:
                        if (!isDragging && !isSwipeMode && !isOcrMode) {
                            // Normaler Klick auf den Hintergrund
                            floatingView.bringToFront();
                        }
                        return true;
                }
                return false;
            }
        });
    }
    
    private void performSwipeGesture() {
        // **Swipe-Geste für ALLE Apps**
        Path path = new Path();
        
        // Dynamische Positionen basierend auf Bildschirmgröße
        int[] screenSize = getScreenSize();
        int startX = screenSize[0] / 2;
        int startY = screenSize[1] - 100;
        int endX = screenSize[0] / 2;
        int endY = 100;
        
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        
        // **Längere Geste für bessere Erkennung**
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 800));
        GestureDescription gesture = gestureBuilder.build();
        
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                Log.d("OverlayService", "Swipe-Geste ausgeführt über ALLEN Apps!");
                if (isRunning) {
                    handler.postDelayed(() -> performOcr(), 1000);
                }
            }
            
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                Log.d("OverlayService", "Swipe-Geste abgebrochen");
            }
        }, null);
    }
    
    private int[] getScreenSize() {
        // Bildschirmgröße ermitteln
        android.view.Display display = windowManager.getDefaultDisplay();
        android.graphics.Point size = new android.graphics.Point();
        display.getSize(size);
        return new int[]{size.x, size.y};
    }
    
    private void performOcr() {
        // OCR-Funktion (vereinfacht)
        handler.postDelayed(() -> {
            if (isRunning) {
                // Simuliert OCR-Ergebnis
                String currentText = String.format("%.2f GB", 0.5 + Math.random() * 0.5);
                long currentTime = System.currentTimeMillis();
                
                if (lastOcrTime > 0) {
                    double timeDiff = (currentTime - lastOcrTime) / 60000.0;
                    double dataDiff = parseData(currentText) - parseData(lastOcrText);
                    consumptionRate = dataDiff / timeDiff;
                    
                    Log.d("OverlayService", "Verbrauch: " + consumptionRate + " GB/min");
                    
                    // Zielberechnung
                    double targetData = 0.70;
                    double remainingData = targetData - parseData(currentText);
                    double waitTime = (remainingData / consumptionRate) * 60000;
                    
                    if (waitTime > 0 && waitTime < 300000) {
                        handler.postDelayed(() -> {
                            performSwipeGesture();
                            handler.postDelayed(() -> clickButton(), 1500);
                        }, (long) waitTime);
                    }
                }
                
                lastOcrText = currentText;
                lastOcrTime = currentTime;
            }
        }, 2000);
    }
    
    private double parseData(String text) {
        try {
            String[] parts = text.split(" ");
            if (parts.length > 0) {
                return Double.parseDouble(parts[0].replace(",", "."));
            }
        } catch (Exception e) {
            Log.e("OverlayService", "Parse-Fehler: " + e.getMessage());
        }
        return 0;
    }
    
    private void clickButton() {
        // **Button-Klick simulieren**
        // Hier müsste die genaue Position des Buttons aus OCR ermittelt werden
        Log.d("OverlayService", "Button-Klick ausgeführt!");
        
        // Beispiel: Klick in der Mitte des Bildschirms
        int[] screenSize = getScreenSize();
        int x = screenSize[0] / 2;
        int y = screenSize[1] / 2;
        
        Path clickPath = new Path();
        clickPath.moveTo(x, y);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 1));
        
        dispatchGesture(gestureBuilder.build(), null, null);
    }
    
    private void startAutomation() {
        isRunning = true;
        startButton.setText("Stop");
        Toast.makeText(this, "Automatisierung gestartet - läuft über ALLEN Apps!", Toast.LENGTH_LONG).show();
        
        handler.postDelayed(() -> {
            if (isRunning) {
                performSwipeGesture();
            }
        }, 2000);
    }
    
    private void stopAutomation() {
        isRunning = false;
        startButton.setText("Start");
        handler.removeCallbacksAndMessages(null);
        Toast.makeText(this, "Automatisierung gestoppt", Toast.LENGTH_LONG).show();
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null && windowManager != null) {
            windowManager.removeView(floatingView);
        }
        handler.removeCallbacksAndMessages(null);
    }
}
