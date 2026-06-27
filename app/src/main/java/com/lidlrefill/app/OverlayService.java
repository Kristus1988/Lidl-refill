package com.lidlrefill.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;

import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.common.InputImage;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.Display;
import android.view.WindowManager;
import android.media.ImageReader;
import android.media.Image;
import java.nio.ByteBuffer;

public class OverlayService extends AccessibilityService {
    private WindowManager windowManager;
    private FrameLayout floatingView;
    private Button swipeButton;
    private Button ocrButton;
    private Button configButton;
    private Button startButton;
    
    private Rect ocrRect = new Rect(100, 100, 300, 300);
    private boolean isDragging = false;
    private float lastX, lastY;
    private boolean isOcrMode = false;
    private boolean isSwipeMode = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private TextRecognizer textRecognizer;
    
    private int currentStep = 0;
    private String lastOcrText = "";
    private long lastOcrTime = 0;
    private double consumptionRate = 0;
    private boolean isRunning = false;
    
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
        
        // Accessibility Service konfigurieren
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        setServiceInfo(info);
        
        textRecognizer = TextRecognition.getClient(
                new DevanagariTextRecognizerOptions.Builder().build()
        );
        
        createOverlay();
    }
    
    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // Haupt-Layout
        floatingView = new FrameLayout(this);
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.overlay_layout, null);
        
        swipeButton = view.findViewById(R.id.btnSwipe);
        ocrButton = view.findViewById(R.id.btnOcr);
        configButton = view.findViewById(R.id.btnConfig);
        startButton = view.findViewById(R.id.btnStart);
        
        // Swipe-Button
        swipeButton.setOnClickListener(v -> {
            if (!isRunning) {
                performSwipeGesture();
            }
        });
        
        // OCR-Button (Rechteck platzieren)
        ocrButton.setOnClickListener(v -> {
            isOcrMode = !isOcrMode;
            if (isOcrMode) {
                Toast.makeText(this, "OCR-Modus aktiv - Tippen Sie auf den Bildschirm, um Rechteck zu platzieren", Toast.LENGTH_LONG).show();
            }
        });
        
        // Config-Button für Positionierung
        configButton.setOnClickListener(v -> {
            isSwipeMode = !isSwipeMode;
            if (isSwipeMode) {
                Toast.makeText(this, "Swipe-Button verschiebbar", Toast.LENGTH_LONG).show();
            }
        });
        
        // Start-Button für Automatisierung
        startButton.setOnClickListener(v -> {
            if (!isRunning) {
                startAutomation();
            } else {
                stopAutomation();
            }
        });
        
        // Overlay-Parameter
        int layoutFlag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN;
        }
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                layoutFlag,
                PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 100;
        
        windowManager.addView(floatingView, params);
        
        // Touch-Listener für Verschiebung
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = event.getRawX();
                        lastY = event.getRawY();
                        isDragging = true;
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        if (isDragging && (isSwipeMode || isOcrMode)) {
                            float deltaX = event.getRawX() - lastX;
                            float deltaY = event.getRawY() - lastY;
                            
                            params.x += deltaX;
                            params.y += deltaY;
                            windowManager.updateViewLayout(floatingView, params);
                            
                            lastX = event.getRawX();
                            lastY = event.getRawY();
                        }
                        return true;
                        
                    case MotionEvent.ACTION_UP:
                        isDragging = false;
                        if (isOcrMode) {
                            // OCR-Rechteck basierend auf Position setzen
                            int rectSize = 200;
                            ocrRect = new Rect(
                                    params.x,
                                    params.y,
                                    params.x + rectSize,
                                    params.y + rectSize
                            );
                            Toast.makeText(OverlayService.this, 
                                    "OCR-Rechteck bei (" + params.x + ", " + params.y + ")", 
                                    Toast.LENGTH_SHORT).show();
                            isOcrMode = false;
                        }
                        return true;
                }
                return false;
            }
        });
    }
    
    private void performSwipeGesture() {
        Path path = new Path();
        float startX = 500;
        float startY = 800;
        float endX = 500;
        float endY = 200;
        
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 500));
        GestureDescription gesture = gestureBuilder.build();
        
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                Log.d("OverlayService", "Swipe-Geste ausgeführt");
                if (isRunning) {
                    performOcrAndCalculate();
                }
            }
        }, null);
    }
    
    private void performOcrAndCalculate() {
        // Screenshot machen für OCR
        takeScreenshot();
    }
    
    private void takeScreenshot() {
        // MediaProjection für Screenshot verwenden
        // Vereinfachte Version: Simulierte OCR
        handler.postDelayed(() -> {
            if (isRunning) {
                // OCR-Ergebnis simulieren (in echter App: echten OCR verwenden)
                String currentText = "0.85 GB";
                long currentTime = System.currentTimeMillis();
                
                if (lastOcrTime > 0) {
                    double timeDiff = (currentTime - lastOcrTime) / 60000.0; // Minuten
                    double dataDiff = parseData(currentText) - parseData(lastOcrText);
                    consumptionRate = dataDiff / timeDiff;
                    
                    Log.d("OverlayService", "Verbrauchsrate: " + consumptionRate + " GB/min");
                    
                    // Berechnen, wann 0.70 GB erreicht sind
                    double targetData = 0.70;
                    double remainingData = targetData - parseData(currentText);
                    double waitTime = (remainingData / consumptionRate) * 60000; // ms
                    
                    if (waitTime > 0 && waitTime < 300000) { // Max 5 Minuten
                        handler.postDelayed(() -> {
                            performSwipeGesture();
                            handler.postDelayed(() -> {
                                clickButton();
                            }, 1000);
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
            Log.e("OverlayService", "Fehler beim Parsen: " + e.getMessage());
        }
        return 0;
    }
    
    private void clickButton() {
        // Button-Klick simulieren
        // In echter App: Position des Buttons aus OCR erkennen und klicken
        Log.d("OverlayService", "Button geklickt");
    }
    
    private void startAutomation() {
        isRunning = true;
        startButton.setText("Stop");
        Toast.makeText(this, "Automatisierung gestartet", Toast.LENGTH_LONG).show();
        
        // Warte 2 Minuten, dann OCR ausführen
        handler.postDelayed(() -> {
            if (isRunning) {
                performSwipeGesture();
            }
        }, 120000); // 2 Minuten
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
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
        if (textRecognizer != null) {
            textRecognizer.close();
        }
    }
}
