package com.lidlrefill.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Rect;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.util.Log;

public class OverlayService extends AccessibilityService {
    private WindowManager windowManager;
    private FrameLayout floatingView;
    private TextView tvStatus, tvCoordinates;
    private Button btnSwipe, btnOcr, btnRefill, btnStart;
    
    // **Feste Swipe-Position: Immer von oben nach unten**
    private Point swipeStart = new Point(0, 0);
    private Point swipeEnd = new Point(0, 0);
    private boolean swipePlaced = false;
    
    // OCR-Rechteck
    private Rect ocrRect = new Rect(100, 100, 300, 300);
    private boolean ocrPlaced = false;
    
    // Refill-Button
    private Point refillButton = new Point(500, 500);
    private boolean refillPlaced = false;
    
    // Modi
    private enum Mode { NONE, SWIPE_PLACE, OCR_PLACE, REFILL_PLACE }
    private Mode currentMode = Mode.NONE;
    
    // Drag & Drop
    private boolean isDragging = false;
    private float lastX, lastY;
    
    // Visuelle Hilfen
    private View swipeVisual, ocrVisual, refillVisual;
    private Handler handler = new Handler(Looper.getMainLooper());
    private int screenWidth, screenHeight;
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}
    
    @Override
    public void onInterrupt() {}
    
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        
        // **Bildschirmgröße ermitteln**
        Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        screenWidth = size.x;
        screenHeight = size.y;
        
        // **Swipe-Standard von oben nach unten**
        swipeStart.set(screenWidth / 2, 100);
        swipeEnd.set(screenWidth / 2, screenHeight - 100);
        swipePlaced = true;
        
        // **Accessibility für ALLE Apps**
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE |
                    AccessibilityServiceInfo.FLAG_ENABLE_ACCESSIBILITY_VOLUME;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC |
                           AccessibilityServiceInfo.FEEDBACK_HAPTIC;
        info.packageNames = null; // **ALLE Apps - auch System**
        info.notificationTimeout = 50;
        setServiceInfo(info);
        
        createOverlay();
        createVisualHelpers();
        updateSwipeVisual();
    }
    
    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // **Layout für Overlay**
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.overlay_layout, null);
        
        tvStatus = view.findViewById(R.id.tvStatus);
        tvCoordinates = view.findViewById(R.id.tvCoordinates);
        btnSwipe = view.findViewById(R.id.btnSwipe);
        btnOcr = view.findViewById(R.id.btnOcr);
        btnRefill = view.findViewById(R.id.btnRefill);
        btnStart = view.findViewById(R.id.btnStart);
        
        setupButtons();
        updateStatus("✅ Bereit - Swipe von oben nach unten");
        
        // **Overlay-Parameter für ALLE Apps (auch System)**
        int layoutFlag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
        
        // **Für Android 10+ zusätzliche Flags**
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        
        // **Höchste Priorität**
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20;
        params.y = 100;
        params.alpha = 0.95f;
        
        // **Für Notch/Display Cutout**
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        
        floatingView = new FrameLayout(this);
        floatingView.addView(view);
        floatingView.setElevation(999);
        floatingView.bringToFront();
        
        windowManager.addView(floatingView, params);
        
        // Touch-Listener
        setupTouchListener(params);
        
        Toast.makeText(this, "✅ Overlay über ALLEN Apps aktiv!", Toast.LENGTH_LONG).show();
    }
    
    private void setupButtons() {
        // **Swipe platzieren - aber immer von oben nach unten**
        btnSwipe.setOnClickListener(v -> {
            currentMode = Mode.SWIPE_PLACE;
            updateStatus("🟡 Swipe: Oben antippen für Start, unten für Ende");
            showSwipeVisual();
            Toast.makeText(this, 
                "Tippen Sie oben für Startpunkt, unten für Endpunkt\n(Swipe geht immer von oben nach unten)", 
                Toast.LENGTH_LONG).show();
        });
        
        btnOcr.setOnClickListener(v -> {
            currentMode = Mode.OCR_PLACE;
            updateStatus("🟡 OCR: Tippen für obere linke Ecke");
            showOcrVisual();
            Toast.makeText(this, "Tippen Sie auf die obere linke Ecke des Datenverbrauchs", Toast.LENGTH_LONG).show();
        });
        
        btnRefill.setOnClickListener(v -> {
            currentMode = Mode.REFILL_PLACE;
            updateStatus("🟡 Refill: Tippen auf den Button in der App");
            showRefillVisual();
            Toast.makeText(this, "Tippen Sie auf den '1GB laden'-Button", Toast.LENGTH_LONG).show();
        });
        
        btnStart.setOnClickListener(v -> {
            if (btnStart.getText().equals("▶ Start")) {
                if (checkAllPlaced()) {
                    startAutomation();
                } else {
                    Toast.makeText(this, "⚠️ Bitte alle Elemente platzieren!", Toast.LENGTH_LONG).show();
                }
            } else {
                stopAutomation();
            }
        });
    }
    
    private boolean checkAllPlaced() {
        if (!swipePlaced) {
            Toast.makeText(this, "❌ Swipe-Geste nicht platziert", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!ocrPlaced) {
            Toast.makeText(this, "❌ OCR-Rechteck nicht platziert", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!refillPlaced) {
            Toast.makeText(this, "❌ Refill-Button nicht platziert", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }
    
    private void setupTouchListener(WindowManager.LayoutParams params) {
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = event.getRawX();
                        lastY = event.getRawY();
                        isDragging = false;
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        float deltaX = event.getRawX() - lastX;
                        float deltaY = event.getRawY() - lastY;
                        
                        if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                            isDragging = true;
                        }
                        
                        if (isDragging) {
                            params.x += deltaX;
                            params.y += deltaY;
                            windowManager.updateViewLayout(floatingView, params);
                            lastX = event.getRawX();
                            lastY = event.getRawY();
                            updateCoordinates(params.x, params.y);
                        }
                        return true;
                        
                    case MotionEvent.ACTION_UP:
                        if (!isDragging && currentMode != Mode.NONE) {
                            // **Klick auf Bildschirm für Platzierung**
                            int x = (int)event.getRawX();
                            int y = (int)event.getRawY();
                            handleScreenTap(x, y);
                        }
                        return true;
                }
                return false;
            }
        });
    }
    
    private void handleScreenTap(int x, int y) {
        switch (currentMode) {
            case SWIPE_PLACE:
                handleSwipePlacement(x, y);
                break;
            case OCR_PLACE:
                handleOcrPlacement(x, y);
                break;
            case REFILL_PLACE:
                handleRefillPlacement(x, y);
                break;
        }
        updateCoordinates(x, y);
    }
    
    // ==================== SWIPE PLATZIERUNG (von oben nach unten) ====================
    private int swipeTapCount = 0;
    
    private void handleSwipePlacement(int x, int y) {
        if (swipeTapCount == 0) {
            // **Startpunkt (oben)**
            swipeStart.set(x, y);
            swipeTapCount = 1;
            updateStatus("🟡 Swipe: Start oben bei (" + x + ", " + y + ") - Tippen für Endpunkt unten");
            Toast.makeText(this, "✅ Startpunkt oben: (" + x + ", " + y + ")\nTippen Sie für den Endpunkt unten", Toast.LENGTH_SHORT).show();
        } else {
            // **Endpunkt (unten)**
            swipeEnd.set(x, y);
            swipeTapCount = 0;
            currentMode = Mode.NONE;
            swipePlaced = true;
            
            // **Sicherstellen, dass Start oben und Ende unten ist**
            if (swipeStart.y > swipeEnd.y) {
                // Tauschen, wenn Start unter Ende liegt
                int tempY = swipeStart.y;
                swipeStart.y = swipeEnd.y;
                swipeEnd.y = tempY;
            }
            
            updateStatus("✅ Swipe von oben nach unten: (" + swipeStart.x + "," + swipeStart.y + ") → (" + swipeEnd.x + "," + swipeEnd.y + ")");
            Toast.makeText(this, "✅ Swipe: Oben (" + swipeStart.x + "," + swipeStart.y + ") → Unten (" + swipeEnd.x + "," + swipeEnd.y + ")", Toast.LENGTH_LONG).show();
            updateSwipeVisual();
            hideVisuals();
        }
    }
    
    // ==================== OCR PLATZIERUNG ====================
    private void handleOcrPlacement(int x, int y) {
        int rectSize = 200;
        ocrRect.set(x, y, x + rectSize, y + rectSize);
        currentMode = Mode.NONE;
        ocrPlaced = true;
        updateStatus("✅ OCR-Rechteck: (" + ocrRect.left + "," + ocrRect.top + ") Größe: " + rectSize + "x" + rectSize);
        Toast.makeText(this, "✅ OCR-Rechteck bei (" + x + ", " + y + ")", Toast.LENGTH_LONG).show();
        updateOcrVisual();
        hideVisuals();
    }
    
    // ==================== REFILL PLATZIERUNG ====================
    private void handleRefillPlacement(int x, int y) {
        refillButton.set(x, y);
        currentMode = Mode.NONE;
        refillPlaced = true;
        updateStatus("✅ Refill-Button bei (" + x + ", " + y + ")");
        Toast.makeText(this, "✅ Refill-Button bei (" + x + ", " + y + ")", Toast.LENGTH_LONG).show();
        updateRefillVisual();
        hideVisuals();
    }
    
    // ==================== VISUELLE HILFEN ====================
    private void createVisualHelpers() {
        // **Swipe-Visual (Pfeil von oben nach unten)**
        swipeVisual = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Paint paint = new Paint();
                paint.setColor(Color.YELLOW);
                paint.setStrokeWidth(8);
                paint.setStyle(Paint.Style.STROKE);
                
                int w = getWidth();
                int h = getHeight();
                
                // Linie von oben nach unten
                canvas.drawLine(w/2, 0, w/2, h, paint);
                
                // Pfeilspitze nach unten
                Paint arrowPaint = new Paint();
                arrowPaint.setColor(Color.YELLOW);
                arrowPaint.setStyle(Paint.Style.FILL);
                
                float[] points = {
                    w/2, h - 10,
                    w/2 - 20, h - 30,
                    w/2 + 20, h - 30
                };
                
                Path arrowPath = new Path();
                arrowPath.moveTo(points[0], points[1]);
                arrowPath.lineTo(points[2], points[3]);
                arrowPath.lineTo(points[4], points[5]);
                arrowPath.close();
                canvas.drawPath(arrowPath, arrowPaint);
                
                // "⬇" Text
                Paint textPaint = new Paint();
                textPaint.setColor(Color.YELLOW);
                textPaint.setTextSize(40);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("⬇", w/2, h/2 + 15, textPaint);
            }
        };
        
        // **OCR-Visual (Rechteck)**
        ocrVisual = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Paint paint = new Paint();
                paint.setColor(Color.CYAN);
                paint.setStrokeWidth(4);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
                
                Paint fillPaint = new Paint();
                fillPaint.setColor(Color.argb(60, 0, 255, 255));
                fillPaint.setStyle(Paint.Style.FILL);
                canvas.drawRect(0, 0, getWidth(), getHeight(), fillPaint);
            }
        };
        
        // **Refill-Visual (Kreis mit "R")**
        refillVisual = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int size = Math.min(getWidth(), getHeight());
                Paint paint = new Paint();
                paint.setColor(Color.RED);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(size/2, size/2, size/2, paint);
                
                Paint borderPaint = new Paint();
                borderPaint.setColor(Color.WHITE);
                borderPaint.setStrokeWidth(3);
                borderPaint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(size/2, size/2, size/2 - 2, borderPaint);
                
                Paint textPaint = new Paint();
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(size * 0.5f);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("R", size/2, size/2 + size * 0.17f, textPaint);
            }
        };
        
        hideVisuals();
    }
    
    private void showSwipeVisual() {
        addVisual(swipeVisual, 80, Math.abs(swipeEnd.y - swipeStart.y) + 40);
        updateSwipeVisual();
    }
    
    private void showOcrVisual() {
        addVisual(ocrVisual, 200, 200);
        updateOcrVisual();
    }
    
    private void showRefillVisual() {
        addVisual(refillVisual, 80, 80);
        updateRefillVisual();
    }
    
    private void addVisual(View visual, int width, int height) {
        hideVisuals();
        
        // **Visual auch über ALLEN Apps**
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 100;
        params.elevation = 1000;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        
        visual.setLayoutParams(params);
        windowManager.addView(visual, params);
    }
    
    private void updateSwipeVisual() {
        if (swipeVisual.getParent() != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) swipeVisual.getLayoutParams();
            int startY = Math.min(swipeStart.y, swipeEnd.y);
            int endY = Math.max(swipeStart.y, swipeEnd.y);
            
            params.width = 80;
            params.height = endY - startY + 20;
            params.x = Math.min(swipeStart.x, swipeEnd.x) - 40;
            params.y = startY - 10;
            windowManager.updateViewLayout(swipeVisual, params);
            swipeVisual.invalidate();
        }
    }
    
    private void updateOcrVisual() {
        if (ocrVisual.getParent() != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) ocrVisual.getLayoutParams();
            params.width = ocrRect.width();
            params.height = ocrRect.height();
            params.x = ocrRect.left;
            params.y = ocrRect.top;
            windowManager.updateViewLayout(ocrVisual, params);
            ocrVisual.invalidate();
        }
    }
    
    private void updateRefillVisual() {
        if (refillVisual.getParent() != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) refillVisual.getLayoutParams();
            params.x = refillButton.x - 40;
            params.y = refillButton.y - 40;
            windowManager.updateViewLayout(refillVisual, params);
            refillVisual.invalidate();
        }
    }
    
    private void hideVisuals() {
        removeVisual(swipeVisual);
        removeVisual(ocrVisual);
        removeVisual(refillVisual);
    }
    
    private void removeVisual(View visual) {
        if (visual != null && visual.getParent() != null) {
            try {
                windowManager.removeView(visual);
            } catch (Exception e) {
                Log.e("OverlayService", "Fehler beim Entfernen: " + e.getMessage());
            }
        }
    }
    
    private void updateStatus(String text) {
        tvStatus.setText(text);
    }
    
    private void updateCoordinates(int x, int y) {
        tvCoordinates.setText("Pos: (" + x + ", " + y + ")");
    }
    
    // ==================== AUTOMATISIERUNG ====================
    private boolean isRunning = false;
    private String lastOcrText = "";
    private long lastOcrTime = 0;
    private double consumptionRate = 0;
    
    private void startAutomation() {
        isRunning = true;
        btnStart.setText("⏹ Stop");
        updateStatus("🟢 Automatik läuft... Swipe von oben nach unten");
        Toast.makeText(this, "🚀 Automatisierung gestartet!", Toast.LENGTH_LONG).show();
        
        // **Erste Aktion nach 2 Minuten**
        handler.postDelayed(() -> {
            if (isRunning) {
                performSwipeGesture();
            }
        }, 2000);
    }
    
    private void stopAutomation() {
        isRunning = false;
        btnStart.setText("▶ Start");
        updateStatus("🔴 Gestoppt");
        handler.removeCallbacksAndMessages(null);
        Toast.makeText(this, "⏹ Automatisierung gestoppt", Toast.LENGTH_LONG).show();
    }
    
    private void performSwipeGesture() {
        // **Swipe von oben nach unten**
        Path path = new Path();
        path.moveTo(swipeStart.x, swipeStart.y);
        path.lineTo(swipeEnd.x, swipeEnd.y);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 600));
        
        updateStatus("🔄 Swipe ausgeführt: Oben → Unten");
        
        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                Log.d("OverlayService", "✅ Swipe von oben nach unten ausgeführt");
                if (isRunning) {
                    handler.postDelayed(() -> performOcr(), 1500);
                }
            }
            
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                Log.d("OverlayService", "❌ Swipe abgebrochen");
            }
        }, null);
    }
    
    private void performOcr() {
        updateStatus("📷 OCR wird ausgeführt...");
        
        // **OCR-Funktion**
        handler.postDelayed(() -> {
            if (isRunning) {
                // Hier echten OCR-Code einfügen
                double currentData = 0.5 + Math.random() * 0.5;
                String currentText = String.format("%.2f GB", currentData);
                long currentTime = System.currentTimeMillis();
                
                updateStatus("📊 Daten: " + currentText);
                
                if (lastOcrTime > 0 && !lastOcrText.isEmpty()) {
                    double timeDiff = (currentTime - lastOcrTime) / 60000.0;
                    double dataDiff = currentData - parseData(lastOcrText);
                    
                    if (timeDiff > 0 && dataDiff > 0) {
                        consumptionRate = dataDiff / timeDiff;
                        updateStatus("📈 Verbrauch: " + String.format("%.3f", consumptionRate) + " GB/min");
                        
                        double targetData = 0.70;
                        double remainingData = targetData - currentData;
                        double waitTimeMinutes = remainingData / consumptionRate;
                        long waitTimeMs = (long)(waitTimeMinutes * 60000);
                        
                        if (waitTimeMs > 5000 && waitTimeMs < 300000) {
                            updateStatus("⏱ Warte " + (waitTimeMs/1000) + "s bis 0.70 GB");
                            
                            handler.postDelayed(() -> {
                                if (isRunning) {
                                    performSwipeGesture();
                                    handler.postDelayed(() -> clickRefillButton(), 2000);
                                }
                            }, waitTimeMs);
                        } else {
                            // Zu kurz oder zu lang - neuen Versuch starten
                            handler.postDelayed(() -> {
                                if (isRunning) performOcr();
                            }, 30000);
                        }
                    }
                }
                
                lastOcrText = currentText;
                lastOcrTime = currentTime;
            }
        }, 2000);
    }
    
    private void clickRefillButton() {
        updateStatus("🔄 Refill-Button wird geklickt...");
        
        Path clickPath = new Path();
        clickPath.moveTo(refillButton.x, refillButton.y);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 1));
        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                updateStatus("✅ Refill geklickt! Warte auf nächsten Zyklus...");
                Toast.makeText(OverlayService.this, "✅ 1GB wurde aufgeladen!", Toast.LENGTH_SHORT).show();
                
                // Nächsten Zyklus starten
                if (isRunning) {
                    handler.postDelayed(() -> {
                        if (isRunning) {
                            lastOcrText = "";
                            lastOcrTime = 0;
                            performOcr();
                        }
                    }, 120000); // 2 Minuten warten
                }
            }
        }, null);
    }
    
    private double parseData(String text) {
        try {
            String cleaned = text.replace(",", ".").replaceAll("[^0-9.]", "");
            return Double.parseDouble(cleaned);
        } catch (Exception e) {
            return 0;
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        hideVisuals();
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {}
        }
        handler.removeCallbacksAndMessages(null);
    }
}
