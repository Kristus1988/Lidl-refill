package com.lidlrefill.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityServiceInfo;
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
    private Button btnSwipePlace, btnOcrPlace, btnRefillPlace;
    private Button btnSwipeNow, btnRefillNow, btnOcrNow, btnStopAuto, btnStartAuto;
    private Button btnClose;
    
    // Positionen
    private Point swipeStart = new Point(0, 0);
    private Point swipeEnd = new Point(0, 0);
    private boolean swipePlaced = false;
    private Rect ocrRect = new Rect(100, 100, 300, 300);
    private boolean ocrPlaced = false;
    private Point refillButton = new Point(500, 500);
    private boolean refillPlaced = false;
    
    // Modi
    private enum Mode { NONE, SWIPE_PLACE, OCR_PLACE, REFILL_PLACE }
    private Mode currentMode = Mode.NONE;
    
    // Drag & Drop
    private boolean isDragging = false;
    private float lastX, lastY;
    private float dragOffsetX, dragOffsetY;
    private View activeVisual = null;
    private View swipeVisual, ocrVisual, refillVisual;
    private Handler handler = new Handler(Looper.getMainLooper());
    private int screenWidth, screenHeight;
    private boolean isRunning = false;
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override
    public void onInterrupt() {}
    
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        
        Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        screenWidth = size.x;
        screenHeight = size.y;
        
        // Standard-Swipe von oben nach unten
        swipeStart.set(screenWidth / 2, 100);
        swipeEnd.set(screenWidth / 2, screenHeight - 100);
        swipePlaced = true;
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.packageNames = null;
        setServiceInfo(info);
        
        createOverlay();
        createVisualHelpers();
        updateStatus("✅ Bereit - Elemente per Drag & Drop platzieren");
    }
    
    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // Hauptcontainer
        FrameLayout mainContainer = new FrameLayout(this);
        
        // Control Panel
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        View controlView = inflater.inflate(R.layout.overlay_layout, null);
        
        tvStatus = controlView.findViewById(R.id.tvStatus);
        tvCoordinates = controlView.findViewById(R.id.tvCoordinates);
        btnSwipePlace = controlView.findViewById(R.id.btnSwipePlace);
        btnOcrPlace = controlView.findViewById(R.id.btnOcrPlace);
        btnRefillPlace = controlView.findViewById(R.id.btnRefillPlace);
        btnSwipeNow = controlView.findViewById(R.id.btnSwipeNow);
        btnRefillNow = controlView.findViewById(R.id.btnRefillNow);
        btnOcrNow = controlView.findViewById(R.id.btnOcrNow);
        btnStopAuto = controlView.findViewById(R.id.btnStopAuto);
        btnStartAuto = controlView.findViewById(R.id.btnStartAuto);
        
        // Schließen-Button (roter Kreis mit X)
        btnClose = new Button(this);
        btnClose.setText("✕");
        btnClose.setTextColor(Color.WHITE);
        btnClose.setTextSize(20);
        btnClose.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.RED));
        btnClose.setPadding(0, 0, 0, 0);
        btnClose.setAllCaps(false);
        
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(80, 80);
        closeParams.gravity = Gravity.TOP | Gravity.END;
        closeParams.setMargins(0, 0, 10, 0);
        btnClose.setLayoutParams(closeParams);
        
        btnClose.setOnClickListener(v -> {
            stopAutomation();
            hideVisuals();
            if (floatingView != null && windowManager != null) {
                windowManager.removeView(floatingView);
            }
            Toast.makeText(this, "Overlay geschlossen", Toast.LENGTH_SHORT).show();
        });
        
        // Buttons einrichten
        setupButtons();
        
        // Control Panel zum Container hinzufügen
        controlPanel = new FrameLayout(this);
        controlPanel.addView(controlView);
        mainContainer.addView(controlPanel);
        mainContainer.addView(btnClose);
        
        // Overlay-Parameter
        int layoutFlag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
        
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
        params.x = 20;
        params.y = 100;
        params.alpha = 0.95f;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        
        floatingView = mainContainer;
        floatingView.setElevation(999);
        
        windowManager.addView(floatingView, params);
        setupTouchListener(params);
    }
    
    private void setupButtons() {
        // ============ DRAG & DROP PLATZIERUNG ============
        
        btnSwipePlace.setOnClickListener(v -> {
            currentMode = Mode.SWIPE_PLACE;
            swipeTapCount = 0;
            updateStatus("🟡 Swipe: Ziehe den gelben Pfeil an die richtige Position");
            Toast.makeText(this, "Ziehe den gelben Pfeil an die gewünschte Position", Toast.LENGTH_LONG).show();
            showSwipeVisual();
            activeVisual = swipeVisual;
        });
        
        btnOcrPlace.setOnClickListener(v -> {
            currentMode = Mode.OCR_PLACE;
            updateStatus("🟡 OCR: Ziehe das cyan Rechteck über den Datenverbrauch");
            Toast.makeText(this, "Ziehe das Rechteck über den Datenverbrauch", Toast.LENGTH_LONG).show();
            showOcrVisual();
            activeVisual = ocrVisual;
        });
        
        btnRefillPlace.setOnClickListener(v -> {
            currentMode = Mode.REFILL_PLACE;
            updateStatus("🟡 Refill: Ziehe den roten Kreis auf den Button");
            Toast.makeText(this, "Ziehe den roten Kreis auf den '1GB laden'-Button", Toast.LENGTH_LONG).show();
            showRefillVisual();
            activeVisual = refillVisual;
        });
        
        // ============ SOFORT-AKTIONEN ============
        
        btnSwipeNow.setOnClickListener(v -> {
            if (swipePlaced) {
                updateStatus("🔄 Swipe wird ausgeführt...");
                performSwipeGesture();
            } else {
                Toast.makeText(this, "❌ Swipe nicht platziert!", Toast.LENGTH_SHORT).show();
            }
        });
        
        btnRefillNow.setOnClickListener(v -> {
            if (refillPlaced) {
                updateStatus("🔄 Refill wird ausgeführt...");
                clickRefillButton();
            } else {
                Toast.makeText(this, "❌ Refill nicht platziert!", Toast.LENGTH_SHORT).show();
            }
        });
        
        btnOcrNow.setOnClickListener(v -> {
            if (ocrPlaced) {
                updateStatus("📷 OCR wird ausgeführt...");
                performOcrNow();
            } else {
                Toast.makeText(this, "❌ OCR nicht platziert!", Toast.LENGTH_SHORT).show();
            }
        });
        
        btnStopAuto.setOnClickListener(v -> {
            if (isRunning) {
                stopAutomation();
                Toast.makeText(this, "⏹ Automation gestoppt!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "⚠️ Automation läuft nicht", Toast.LENGTH_SHORT).show();
            }
        });
        
        btnStartAuto.setOnClickListener(v -> {
            if (isRunning) {
                Toast.makeText(this, "⚠️ Automation läuft bereits", Toast.LENGTH_SHORT).show();
            } else if (checkAllPlaced()) {
                startAutomation();
            } else {
                Toast.makeText(this, "⚠️ Bitte alle Elemente platzieren!", Toast.LENGTH_LONG).show();
            }
        });
    }
    
    private boolean checkAllPlaced() {
        if (!swipePlaced) {
            Toast.makeText(this, "❌ Swipe nicht platziert", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!ocrPlaced) {
            Toast.makeText(this, "❌ OCR nicht platziert", Toast.LENGTH_SHOW).show();
            return false;
        }
        if (!refillPlaced) {
            Toast.makeText(this, "❌ Refill nicht platziert", Toast.LENGTH_SHOW).show();
            return false;
        }
        return true;
    }
    
    private void setupTouchListener(WindowManager.LayoutParams params) {
        floatingView.setOnTouchListener((v, event) -> {
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
                    
                    if (isDragging && currentMode != Mode.NONE && activeVisual != null) {
                        // VISUAL ZIEHEN
                        int newX = (int)(event.getRawX() - dragOffsetX);
                        int newY = (int)(event.getRawY() - dragOffsetY);
                        
                        // Aktualisiere Visual-Position
                        WindowManager.LayoutParams visualParams = 
                            (WindowManager.LayoutParams) activeVisual.getLayoutParams();
                        visualParams.x = newX;
                        visualParams.y = newY;
                        windowManager.updateViewLayout(activeVisual, visualParams);
                        
                        // Aktualisiere Koordinaten
                        updateCoordinates(newX, newY);
                        
                        // Live-Position anzeigen
                        updateStatus("📌 Position: (" + newX + ", " + newY + ")");
                    }
                    return true;
                    
                case MotionEvent.ACTION_UP:
                    if (isDragging && currentMode != Mode.NONE && activeVisual != null) {
                        // Position speichern
                        WindowManager.LayoutParams visualParams = 
                            (WindowManager.LayoutParams) activeVisual.getLayoutParams();
                        int finalX = visualParams.x;
                        int finalY = visualParams.y;
                        
                        // Position je nach Modus speichern
                        savePosition(finalX, finalY);
                        
                        // Visual ausblenden und Modus beenden
                        hideVisuals();
                        currentMode = Mode.NONE;
                        activeVisual = null;
                        updateStatus("✅ Element platziert bei (" + finalX + ", " + finalY + ")");
                        Toast.makeText(this, "✅ Platziert bei (" + finalX + ", " + finalY + ")", Toast.LENGTH_SHORT).show();
                    }
                    isDragging = false;
                    return true;
            }
            return false;
        });
    }
    
    private void savePosition(int x, int y) {
        switch (currentMode) {
            case SWIPE_PLACE:
                if (swipeTapCount == 0) {
                    swipeStart.set(x + 60, y + 10);
                    swipeTapCount = 1;
                    updateStatus("🟡 Swipe Start bei (" + swipeStart.x + ", " + swipeStart.y + ") - Ziehe für Endpunkt");
                    Toast.makeText(this, "Startpunkt gesetzt! Ziehe nun für Endpunkt", Toast.LENGTH_SHORT).show();
                    // Zeige wieder das Visual für den Endpunkt
                    showSwipeVisual();
                    activeVisual = swipeVisual;
                    // Setze Offset für Endpunkt
                    dragOffsetX = 60;
                    dragOffsetY = 10;
                } else {
                    swipeEnd.set(x + 60, y + 10);
                    swipePlaced = true;
                    swipeTapCount = 0;
                    // Sicherstellen: Start oben, Ende unten
                    if (swipeStart.y > swipeEnd.y) {
                        int tempY = swipeStart.y;
                        swipeStart.y = swipeEnd.y;
                        swipeEnd.y = tempY;
                    }
                    updateStatus("✅ Swipe: (" + swipeStart.x + "," + swipeStart.y + ") → (" + swipeEnd.x + "," + swipeEnd.y + ")");
                    Toast.makeText(this, "✅ Swipe platziert!\nOben (" + swipeStart.x + "," + swipeStart.y + ")\nUnten (" + swipeEnd.x + "," + swipeEnd.y + ")", Toast.LENGTH_LONG).show();
                    hideVisuals();
                    activeVisual = null;
                    currentMode = Mode.NONE;
                }
                break;
                
            case OCR_PLACE:
                int rectSize = 200;
                ocrRect.set(x, y, x + rectSize, y + rectSize);
                ocrPlaced = true;
                updateStatus("✅ OCR-Rechteck bei (" + x + ", " + y + ")");
                Toast.makeText(this, "✅ OCR-Rechteck platziert!\n(" + x + ", " + y + ")", Toast.LENGTH_LONG).show();
                break;
                
            case REFILL_PLACE:
                refillButton.set(x + 40, y + 40);
                refillPlaced = true;
                updateStatus("✅ Refill-Button bei (" + refillButton.x + ", " + refillButton.y + ")");
                Toast.makeText(this, "✅ Refill-Button platziert!\n(" + refillButton.x + ", " + refillButton.y + ")", Toast.LENGTH_LONG).show();
                break;
        }
    }
    
    // ============ VISUELLE HILFEN ============
    
    private int swipeTapCount = 0;
    private FrameLayout controlPanel;
    
    private void createVisualHelpers() {
        // Swipe-Visual (gelber Pfeil) - DRAG & DROP FÄHIG
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
                canvas.drawLine(w/2, 0, w/2, h - 20, paint);
                
                // Pfeilspitze nach unten
                Paint arrowPaint = new Paint();
                arrowPaint.setColor(Color.YELLOW);
                arrowPaint.setStyle(Paint.Style.FILL);
                
                Path arrowPath = new Path();
                arrowPath.moveTo(w/2, h);
                arrowPath.lineTo(w/2 - 30, h - 30);
                arrowPath.lineTo(w/2 + 30, h - 30);
                arrowPath.close();
                canvas.drawPath(arrowPath, arrowPaint);
                
                // ⬇ Text
                Paint textPaint = new Paint();
                textPaint.setColor(Color.YELLOW);
                textPaint.setTextSize(50);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("⬇", w/2, h/3, textPaint);
                
                // "Ziehen" Hinweis
                Paint hintPaint = new Paint();
                hintPaint.setColor(Color.WHITE);
                hintPaint.setTextSize(18);
                hintPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("⇱ Ziehen", w/2, h - 50, hintPaint);
            }
        };
        
        // OCR-Visual (cyan Rechteck) - DRAG & DROP FÄHIG
        ocrVisual = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Paint paint = new Paint();
                paint.setColor(Color.CYAN);
                paint.setStrokeWidth(6);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRect(10, 10, getWidth()-10, getHeight()-10, paint);
                
                Paint fillPaint = new Paint();
                fillPaint.setColor(Color.argb(80, 0, 255, 255));
                fillPaint.setStyle(Paint.Style.FILL);
                canvas.drawRect(10, 10, getWidth()-10, getHeight()-10, fillPaint);
                
                // "OCR" Text
                Paint textPaint = new Paint();
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(30);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("OCR", getWidth()/2, getHeight()/2 + 10, textPaint);
                
                // "Ziehen" Hinweis
                Paint hintPaint = new Paint();
                hintPaint.setColor(Color.WHITE);
                hintPaint.setTextSize(16);
                hintPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("⇱ Ziehen", getWidth()/2, getHeight() - 20, hintPaint);
            }
        };
        
        // Refill-Visual (roter Kreis mit R) - DRAG & DROP FÄHIG
        refillVisual = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int size = Math.min(getWidth(), getHeight());
                Paint paint = new Paint();
                paint.setColor(Color.RED);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(size/2, size/2, size/2 - 10, paint);
                
                Paint borderPaint = new Paint();
                borderPaint.setColor(Color.WHITE);
                borderPaint.setStrokeWidth(4);
                borderPaint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(size/2, size/2, size/2 - 10, borderPaint);
                
                Paint textPaint = new Paint();
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(size * 0.6f);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("R", size/2, size/2 + size * 0.2f, textPaint);
                
                // "Ziehen" Hinweis
                Paint hintPaint = new Paint();
                hintPaint.setColor(Color.WHITE);
                hintPaint.setTextSize(14);
                hintPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("⇱ Ziehen", size/2, size - 5, hintPaint);
            }
        };
        
        hideVisuals();
    }
    
    private void showSwipeVisual() {
        addVisual(swipeVisual, 120, 300);
        dragOffsetX = 60;
        dragOffsetY = 10;
    }
    
    private void showOcrVisual() {
        addVisual(ocrVisual, 220, 220);
        dragOffsetX = 10;
        dragOffsetY = 10;
    }
    
    private void showRefillVisual() {
        addVisual(refillVisual, 100, 100);
        dragOffsetX = 50;
        dragOffsetY = 50;
    }
    
    private void addVisual(View visual, int width, int height) {
        hideVisuals();
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = screenWidth / 2 - width / 2;
        params.y = screenHeight / 2 - height / 2;
        params.elevation = 1000;
        
        // Größerer Touch-Bereich für einfaches Ziehen
        visual.setPadding(20, 20, 20, 20);
        visual.setClickable(true);
        
        visual.setLayoutParams(params);
        windowManager.addView(visual, params);
        
        // Touch-Listener für das Visual
        visual.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    isDragging = true;
                    // Offset speichern für präzises Ziehen
                    dragOffsetX = event.getRawX() - params.x;
                    dragOffsetY = event.getRawY() - params.y;
                    return true;
                    
                case MotionEvent.ACTION_MOVE:
                    if (isDragging) {
                        params.x = (int)(event.getRawX() - dragOffsetX);
                        params.y = (int)(event.getRawY() - dragOffsetY);
                        windowManager.updateViewLayout(visual, params);
                        updateStatus("📌 Position: (" + params.x + ", " + params.y + ")");
                        updateCoordinates(params.x, params.y);
                    }
                    return true;
                    
                case MotionEvent.ACTION_UP:
                    if (isDragging) {
                        isDragging = false;
                        // Position speichern
                        if (currentMode != Mode.NONE) {
                            savePosition(params.x, params.y);
                        }
                    }
                    return true;
            }
            return false;
        });
    }
    
    private void hideVisuals() {
        removeVisual(swipeVisual);
        removeVisual(ocrVisual);
        removeVisual(refillVisual);
        activeVisual = null;
    }
    
    private void removeVisual(View visual) {
        if (visual != null && visual.getParent() != null) {
            try {
                windowManager.removeView(visual);
            } catch (Exception e) {
                Log.e("OverlayService", "Fehler: " + e.getMessage());
            }
        }
    }
    
    private void updateStatus(String text) {
        tvStatus.setText(text);
    }
    
    private void updateCoordinates(int x, int y) {
        tvCoordinates.setText("Pos: (" + x + ", " + y + ")");
    }
    
    // ============ AKTIONEN ============
    
    private void performSwipeGesture() {
        Path path = new Path();
        path.moveTo(swipeStart.x, swipeStart.y);
        path.lineTo(swipeEnd.x, swipeEnd.y);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 600));
        
        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                updateStatus("✅ Swipe ausgeführt!");
                handler.postDelayed(() -> {
                    if (!isRunning) updateStatus("✅ Bereit");
                }, 2000);
            }
        }, null);
    }
    
    private void clickRefillButton() {
        Path clickPath = new Path();
        clickPath.moveTo(refillButton.x, refillButton.y);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 1));
        
        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                updateStatus("✅ Refill geklickt!");
                Toast.makeText(OverlayService.this, "✅ Refill-Button wurde geklickt!", Toast.LENGTH_SHORT).show();
                handler.postDelayed(() -> {
                    if (!isRunning) updateStatus("✅ Bereit");
                }, 2000);
            }
        }, null);
    }
    
    private void performOcrNow() {
        handler.postDelayed(() -> {
            double randomData = 0.3 + Math.random() * 0.7;
            String result = String.format("%.2f GB", randomData);
            updateStatus("📊 OCR: " + result);
            Toast.makeText(this, "📊 OCR-Ergebnis: " + result, Toast.LENGTH_LONG).show();
            handler.postDelayed(() -> {
                if (!isRunning) updateStatus("✅ Bereit");
            }, 3000);
        }, 1500);
    }
    
    private void startAutomation() {
        isRunning = true;
        btnStartAuto.setText("▶ Läuft...");
        updateStatus("🟢 Automatik läuft...");
        Toast.makeText(this, "🚀 Automatisierung gestartet!", Toast.LENGTH_LONG).show();
        
        handler.postDelayed(() -> {
            if (isRunning) {
                performSwipeGesture();
                handler.postDelayed(() -> {
                    if (isRunning) {
                        performOcrNow();
                        handler.postDelayed(() -> {
                            if (isRunning) {
                                clickRefillButton();
                            }
                        }, 3000);
                    }
                }, 2000);
            }
        }, 2000);
    }
    
    private void stopAutomation() {
        isRunning = false;
        btnStartAuto.setText("▶ Start");
        updateStatus("🔴 Gestoppt");
        handler.removeCallbacksAndMessages(null);
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
