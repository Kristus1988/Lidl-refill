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
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.widget.ImageView;
import android.util.Log;

public class OverlayService extends AccessibilityService {
    private WindowManager windowManager;
    private FrameLayout floatingView;
    private TextView tvStatus, tvCoordinates;
    private Button btnSwipe, btnOcr, btnRefill, btnStart;
    
    // Positionen der Elemente
    private Point swipeStart = new Point(300, 800);
    private Point swipeEnd = new Point(300, 200);
    private Rect ocrRect = new Rect(100, 100, 300, 300);
    private Point refillButton = new Point(500, 500);
    
    // Modi
    private enum Mode { NONE, SWIPE_PLACE, OCR_PLACE, REFILL_PLACE }
    private Mode currentMode = Mode.NONE;
    
    // Drag & Drop
    private boolean isDragging = false;
    private float lastX, lastY;
    private View dragTarget = null;
    
    // Visuelle Hilfen
    private View swipeVisual, ocrVisual, refillVisual;
    private Handler handler = new Handler(Looper.getMainLooper());
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}
    
    @Override
    public void onInterrupt() {}
    
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        
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
    }
    
    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.overlay_layout, null);
        
        tvStatus = view.findViewById(R.id.tvStatus);
        tvCoordinates = view.findViewById(R.id.tvCoordinates);
        btnSwipe = view.findViewById(R.id.btnSwipe);
        btnOcr = view.findViewById(R.id.btnOcr);
        btnRefill = view.findViewById(R.id.btnRefill);
        btnStart = view.findViewById(R.id.btnStart);
        
        setupButtons();
        
        int layoutFlag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
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
        params.x = 20;
        params.y = 100;
        
        floatingView = new FrameLayout(this);
        floatingView.addView(view);
        windowManager.addView(floatingView, params);
        
        // Touch-Listener für das Overlay
        setupTouchListener(params);
    }
    
    private void setupButtons() {
        btnSwipe.setOnClickListener(v -> {
            currentMode = Mode.SWIPE_PLACE;
            tvStatus.setText("🟡 Swipe platzieren: Tippen für Start, nochmal für Ende");
            tvStatus.setTextColor(Color.YELLOW);
            showSwipeVisual();
            Toast.makeText(this, "Tippen Sie für Startpunkt, dann für Endpunkt", Toast.LENGTH_LONG).show();
        });
        
        btnOcr.setOnClickListener(v -> {
            currentMode = Mode.OCR_PLACE;
            tvStatus.setText("🟡 OCR-Rechteck: Tippen für Ecke, ziehen zum Vergrößern");
            tvStatus.setTextColor(Color.YELLOW);
            showOcrVisual();
            Toast.makeText(this, "Tippen Sie für die obere linke Ecke", Toast.LENGTH_LONG).show();
        });
        
        btnRefill.setOnClickListener(v -> {
            currentMode = Mode.REFILL_PLACE;
            tvStatus.setText("🟡 Refill-Button: Tippen zum Platzieren");
            tvStatus.setTextColor(Color.YELLOW);
            showRefillVisual();
            Toast.makeText(this, "Tippen Sie auf den Refill-Button in der App", Toast.LENGTH_LONG).show();
        });
        
        btnStart.setOnClickListener(v -> {
            if (btnStart.getText().equals("▶ Start")) {
                startAutomation();
            } else {
                stopAutomation();
            }
        });
    }
    
    private void setupTouchListener(WindowManager.LayoutParams params) {
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // Nur für Overlay-Verschiebung
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
                            // Klick auf Bildschirm für Platzierung
                            handleScreenTap((int)event.getRawX(), (int)event.getRawY());
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
    
    // ==================== SWIPE PLATZIERUNG ====================
    private int swipeTapCount = 0;
    
    private void handleSwipePlacement(int x, int y) {
        if (swipeTapCount == 0) {
            swipeStart.set(x, y);
            swipeTapCount = 1;
            tvStatus.setText("🟡 Startpunkt gesetzt! Tippen Sie für Endpunkt");
            Toast.makeText(this, "Startpunkt: (" + x + ", " + y + ")", Toast.LENGTH_SHORT).show();
            updateSwipeVisual();
        } else {
            swipeEnd.set(x, y);
            swipeTapCount = 0;
            currentMode = Mode.NONE;
            tvStatus.setText("✅ Swipe-Geste platziert: (" + swipeStart.x + "," + swipeStart.y + ") → (" + swipeEnd.x + "," + swipeEnd.y + ")");
            tvStatus.setTextColor(Color.GREEN);
            Toast.makeText(this, "Swipe-Geste von (" + swipeStart.x + "," + swipeStart.y + ") nach (" + swipeEnd.x + "," + swipeEnd.y + ")", Toast.LENGTH_LONG).show();
            hideVisuals();
        }
    }
    
    // ==================== OCR PLATZIERUNG ====================
    private void handleOcrPlacement(int x, int y) {
        ocrRect.set(x, y, x + 200, y + 200);
        currentMode = Mode.NONE;
        tvStatus.setText("✅ OCR-Rechteck platziert: (" + ocrRect.left + "," + ocrRect.top + ")");
        tvStatus.setTextColor(Color.GREEN);
        Toast.makeText(this, "OCR-Rechteck bei (" + x + ", " + y + ")", Toast.LENGTH_LONG).show();
        updateOcrVisual();
    }
    
    // ==================== REFILL PLATZIERUNG ====================
    private void handleRefillPlacement(int x, int y) {
        refillButton.set(x, y);
        currentMode = Mode.NONE;
        tvStatus.setText("✅ Refill-Button platziert: (" + x + ", " + y + ")");
        tvStatus.setTextColor(Color.GREEN);
        Toast.makeText(this, "Refill-Button bei (" + x + ", " + y + ")", Toast.LENGTH_LONG).show();
        updateRefillVisual();
    }
    
    // ==================== VISUELLE HILFEN ====================
    private void createVisualHelpers() {
        // Swipe-Visual (Pfeil-Linie)
        swipeVisual = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Paint paint = new Paint();
                paint.setColor(Color.YELLOW);
                paint.setStrokeWidth(6);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(0, 0, getWidth(), getHeight(), paint);
                
                // Pfeilspitze
                Paint arrowPaint = new Paint();
                arrowPaint.setColor(Color.YELLOW);
                arrowPaint.setStyle(Paint.Style.FILL);
                float[] points = {getWidth(), getHeight(), getWidth()-20, getHeight()-10, getWidth()-10, getHeight()-20};
                // Pfeil zeichnen
            }
        };
        
        // OCR-Visual (Rechteck)
        ocrVisual = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Paint paint = new Paint();
                paint.setColor(Color.CYAN);
                paint.setStrokeWidth(3);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
                
                // Halbtransparente Füllung
                Paint fillPaint = new Paint();
                fillPaint.setColor(Color.argb(50, 0, 255, 255));
                fillPaint.setStyle(Paint.Style.FILL);
                canvas.drawRect(0, 0, getWidth(), getHeight(), fillPaint);
            }
        };
        
        // Refill-Visual (Kreis mit "R")
        refillVisual = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Paint paint = new Paint();
                paint.setColor(Color.RED);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(getWidth()/2, getHeight()/2, getWidth()/2, paint);
                
                Paint textPaint = new Paint();
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(40);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("R", getWidth()/2, getHeight()/2 + 15, textPaint);
            }
        };
        
        // Unsichtbar machen
        hideVisuals();
    }
    
    private void showSwipeVisual() {
        addVisual(swipeVisual, 200, 200);
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
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 100;
        
        visual.setLayoutParams(params);
        windowManager.addView(visual, params);
    }
    
    private void updateSwipeVisual() {
        if (swipeVisual.getParent() != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) swipeVisual.getLayoutParams();
            int width = Math.abs(swipeEnd.x - swipeStart.x) + 20;
            int height = Math.abs(swipeEnd.y - swipeStart.y) + 20;
            params.width = width;
            params.height = height;
            params.x = Math.min(swipeStart.x, swipeEnd.x) - 10;
            params.y = Math.min(swipeStart.y, swipeEnd.y) - 10;
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
        tvStatus.setText("🟢 Automatik läuft...");
        tvStatus.setTextColor(Color.GREEN);
        Toast.makeText(this, "Automatisierung gestartet!", Toast.LENGTH_LONG).show();
        
        // 2 Minuten warten, dann starten
        handler.postDelayed(() -> {
            if (isRunning) {
                performSwipeGesture();
            }
        }, 120000);
    }
    
    private void stopAutomation() {
        isRunning = false;
        btnStart.setText("▶ Start");
        tvStatus.setText("🔴 Gestoppt");
        tvStatus.setTextColor(Color.RED);
        handler.removeCallbacksAndMessages(null);
        Toast.makeText(this, "Automatisierung gestoppt", Toast.LENGTH_LONG).show();
    }
    
    private void performSwipeGesture() {
        Path path = new Path();
        path.moveTo(swipeStart.x, swipeStart.y);
        path.lineTo(swipeEnd.x, swipeEnd.y);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 800));
        
        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                if (isRunning) {
                    handler.postDelayed(() -> performOcr(), 1000);
                }
            }
        }, null);
    }
    
    private void performOcr() {
        // Hier OCR-Logik einfügen
        handler.postDelayed(() -> {
            if (isRunning) {
                // OCR-Ergebnis simulieren
                String currentText = String.format("%.2f GB", 0.5 + Math.random() * 0.5);
                long currentTime = System.currentTimeMillis();
                
                if (lastOcrTime > 0) {
                    double timeDiff = (currentTime - lastOcrTime) / 60000.0;
                    double dataDiff = parseData(currentText) - parseData(lastOcrText);
                    consumptionRate = dataDiff / timeDiff;
                    
                    double targetData = 0.70;
                    double remainingData = targetData - parseData(currentText);
                    double waitTime = (remainingData / consumptionRate) * 60000;
                    
                    if (waitTime > 0 && waitTime < 300000) {
                        handler.postDelayed(() -> {
                            performSwipeGesture();
                            handler.postDelayed(() -> clickRefillButton(), 1500);
                        }, (long) waitTime);
                    }
                }
                
                lastOcrText = currentText;
                lastOcrTime = currentTime;
            }
        }, 2000);
    }
    
    private void clickRefillButton() {
        Path clickPath = new Path();
        clickPath.moveTo(refillButton.x, refillButton.y);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 1));
        dispatchGesture(gestureBuilder.build(), null, null);
        
        tvStatus.setText("✅ Refill-Button geklickt!");
        handler.postDelayed(() -> {
            if (isRunning) {
                tvStatus.setText("🟢 Automatik läuft...");
            }
        }, 2000);
    }
    
    private double parseData(String text) {
        try {
            String[] parts = text.split(" ");
            if (parts.length > 0) {
                return Double.parseDouble(parts[0].replace(",", "."));
            }
        } catch (Exception e) {}
        return 0;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        hideVisuals();
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
        handler.removeCallbacksAndMessages(null);
    }
}
