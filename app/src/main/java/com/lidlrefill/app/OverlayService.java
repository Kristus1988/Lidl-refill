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

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class OverlayService extends AccessibilityService {
    private WindowManager windowManager;
    private FrameLayout floatingView;
    private TextView tvStatus, tvCoordinates, tvLearning;
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
    
    private enum Mode { NONE, SWIPE_PLACE, OCR_PLACE, REFILL_PLACE }
    private Mode currentMode = Mode.NONE;
    
    private boolean isDragging = false;
    private float lastX, lastY;
    private float dragOffsetX, dragOffsetY;
    private View activeVisual = null;
    private View swipeVisual, ocrVisual, refillVisual;
    
    private Handler handler = new Handler(Looper.getMainLooper());
    private int screenWidth, screenHeight;
    private boolean isRunning = false;
    private boolean isOverlayDragging = false;
    private float overlayDragX, overlayDragY;
    
    // ============ ULTIMATIVE EFFIZIENZ VARIABLEN ============
    private List<Double> consumptionHistory = new ArrayList<>();
    private List<Long> actualWaitTimes = new ArrayList<>();
    private double averageConsumptionRate = 0;
    private double lastDataValue = 0;
    private long lastDataTime = 0;
    private int cycleCount = 0;
    
    // ============ ULTIMATIVE PARAMETER ============
    private static final double REFILL_THRESHOLD = 0.30;
    private static final double BUFFER_SAFETY = 0.10;
    private static final long MIN_WAIT_TIME = 120000;
    private static final long MAX_WAIT_TIME = 1800000;
    private static final long INITIAL_WAIT_TIME = 600000;
    private static final long SWIPE_DURATION = 3000;
    private static final long OCR_DURATION = 1500;
    
    // ============ AKTUELLE WARTEZEIT ============
    private long currentWaitTime = INITIAL_WAIT_TIME;
    
    // Prognose
    private boolean isFirstMeasurement = true;
    private boolean isSecondMeasurement = true;
    private double predictedRefillTime = 0;
    private double confidenceLevel = 0;
    private boolean usePrediction = false;
    
    // Statistik
    private int totalSwipes = 0;
    private long totalWaitTime = 0;
    
    private DecimalFormat df = new DecimalFormat("0.000");
    
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
        
        swipeStart.set(screenWidth / 2, 100);
        swipeEnd.set(screenWidth / 2, screenHeight - 100);
        swipePlaced = true;
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.packageNames = null;
        setServiceInfo(info);
        
        createOverlay();
        createVisualHelpers();
        updateStatus("✅ ULTIMATIV: Refill bei <= " + REFILL_THRESHOLD + " GB");
        updateLearningStatus();
    }
    
    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // Hauptcontainer
        FrameLayout mainContainer = new FrameLayout(this);
        mainContainer.setClickable(true);
        mainContainer.setFocusable(true);
        
        // Control Panel
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        View controlView = inflater.inflate(R.layout.overlay_layout, null);
        
        tvStatus = controlView.findViewById(R.id.tvStatus);
        tvCoordinates = controlView.findViewById(R.id.tvCoordinates);
        tvLearning = controlView.findViewById(R.id.tvLearning);
        
        btnSwipePlace = controlView.findViewById(R.id.btnSwipePlace);
        btnOcrPlace = controlView.findViewById(R.id.btnOcrPlace);
        btnRefillPlace = controlView.findViewById(R.id.btnRefillPlace);
        btnSwipeNow = controlView.findViewById(R.id.btnSwipeNow);
        btnRefillNow = controlView.findViewById(R.id.btnRefillNow);
        btnOcrNow = controlView.findViewById(R.id.btnOcrNow);
        btnStopAuto = controlView.findViewById(R.id.btnStopAuto);
        btnStartAuto = controlView.findViewById(R.id.btnStartAuto);
        
        // Schließen-Button (klein)
        btnClose = new Button(this);
        btnClose.setText("✕");
        btnClose.setTextColor(Color.WHITE);
        btnClose.setTextSize(12);
        btnClose.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.RED));
        btnClose.setPadding(0, 0, 0, 0);
        btnClose.setAllCaps(false);
        btnClose.setClickable(true);
        
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(40, 40);
        closeParams.gravity = Gravity.TOP | Gravity.END;
        closeParams.setMargins(0, 0, 2, 0);
        btnClose.setLayoutParams(closeParams);
        
        btnClose.setOnClickListener(v -> {
            stopAutomation();
            hideVisuals();
            if (floatingView != null && windowManager != null) {
                try {
                    windowManager.removeView(floatingView);
                } catch (Exception e) {}
            }
            stopSelf();
            Toast.makeText(this, "Overlay geschlossen", Toast.LENGTH_SHORT).show();
        });
        
        setupButtons();
        
        // ============ WICHTIG: Touch-Listener für Overlay-Verschiebung ============
        // Das ganze Overlay kann verschoben werden
        mainContainer.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    overlayDragX = event.getRawX();
                    overlayDragY = event.getRawY();
                    isOverlayDragging = true;
                    return true;
                    
                case MotionEvent.ACTION_MOVE:
                    if (isOverlayDragging && floatingView != null) {
                        WindowManager.LayoutParams params = 
                            (WindowManager.LayoutParams) floatingView.getLayoutParams();
                        float deltaX = event.getRawX() - overlayDragX;
                        float deltaY = event.getRawY() - overlayDragY;
                        params.x += deltaX;
                        params.y += deltaY;
                        windowManager.updateViewLayout(floatingView, params);
                        overlayDragX = event.getRawX();
                        overlayDragY = event.getRawY();
                        updateCoordinates(params.x, params.y);
                    }
                    return true;
                    
                case MotionEvent.ACTION_UP:
                    isOverlayDragging = false;
                    return true;
            }
            return false;
        });
        
        FrameLayout controlPanel = new FrameLayout(this);
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
        params.alpha = 0.92f;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        
        floatingView = mainContainer;
        floatingView.setElevation(999);
        
        windowManager.addView(floatingView, params);
    }
    
    private void setupButtons() {
        btnSwipePlace.setOnClickListener(v -> {
            if (currentMode == Mode.SWIPE_PLACE) {
                currentMode = Mode.NONE;
                hideVisuals();
                updateStatus("✅ Swipe-Modus beendet");
                return;
            }
            currentMode = Mode.SWIPE_PLACE;
            updateStatus("🟡 Swipe: Pfeil ziehen");
            Toast.makeText(this, "Ziehe den Pfeil an die gewünschte Stelle", Toast.LENGTH_SHORT).show();
            showSwipeVisual();
            activeVisual = swipeVisual;
        });
        
        btnOcrPlace.setOnClickListener(v -> {
            if (currentMode == Mode.OCR_PLACE) {
                currentMode = Mode.NONE;
                hideVisuals();
                updateStatus("✅ OCR-Modus beendet");
                return;
            }
            currentMode = Mode.OCR_PLACE;
            updateStatus("🟡 OCR: Rechteck ziehen");
            Toast.makeText(this, "Ziehe das Rechteck über den Datenverbrauch", Toast.LENGTH_SHORT).show();
            showOcrVisual();
            activeVisual = ocrVisual;
        });
        
        btnRefillPlace.setOnClickListener(v -> {
            if (currentMode == Mode.REFILL_PLACE) {
                currentMode = Mode.NONE;
                hideVisuals();
                updateStatus("✅ Refill-Modus beendet");
                return;
            }
            currentMode = Mode.REFILL_PLACE;
            updateStatus("🟡 Refill: Kreis ziehen");
            Toast.makeText(this, "Ziehe den Kreis auf den '1GB laden'-Button", Toast.LENGTH_SHORT).show();
            showRefillVisual();
            activeVisual = refillVisual;
        });
        
        btnSwipeNow.setOnClickListener(v -> {
            if (swipePlaced) {
                updateStatus("🔄 Swipe...");
                performSwipeGesture();
            } else {
                Toast.makeText(this, "❌ Swipe nicht platziert!", Toast.LENGTH_SHORT).show();
            }
        });
        
        btnRefillNow.setOnClickListener(v -> {
            if (refillPlaced) {
                updateStatus("🔄 Refill...");
                clickRefillButton();
            } else {
                Toast.makeText(this, "❌ Refill nicht platziert!", Toast.LENGTH_SHORT).show();
            }
        });
        
        btnOcrNow.setOnClickListener(v -> {
            if (ocrPlaced) {
                updateStatus("📷 OCR...");
                performOcrNow();
            } else {
                Toast.makeText(this, "❌ OCR nicht platziert!", Toast.LENGTH_SHORT).show();
            }
        });
        
        btnStopAuto.setOnClickListener(v -> {
            if (isRunning) {
                stopAutomation();
                Toast.makeText(this, "⏹ Gestoppt!", Toast.LENGTH_SHORT).show();
            }
        });
        
        btnStartAuto.setOnClickListener(v -> {
            if (isRunning) {
                Toast.makeText(this, "⚠️ Läuft bereits", Toast.LENGTH_SHORT).show();
            } else if (checkAllPlaced()) {
                startAutomation();
            } else {
                Toast.makeText(this, "⚠️ Alle Elemente platzieren!", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    private boolean checkAllPlaced() {
        if (!swipePlaced) {
            Toast.makeText(this, "❌ Swipe fehlt", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!ocrPlaced) {
            Toast.makeText(this, "❌ OCR fehlt", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!refillPlaced) {
            Toast.makeText(this, "❌ Refill fehlt", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }
    
    // ============ PLATZIERUNG ============
    
    private void savePosition(int x, int y) {
        switch (currentMode) {
            case SWIPE_PLACE:
                swipeStart.set(x + 60, y + 10);
                swipeEnd.set(x + 60, y + 250);
                swipePlaced = true;
                currentMode = Mode.NONE;
                activeVisual = null;
                hideVisuals();
                updateStatus("✅ Swipe platziert");
                Toast.makeText(this, "✅ Swipe platziert!", Toast.LENGTH_SHORT).show();
                break;
                
            case OCR_PLACE:
                ocrRect.set(x, y, x + 180, y + 180);
                ocrPlaced = true;
                currentMode = Mode.NONE;
                activeVisual = null;
                hideVisuals();
                updateStatus("✅ OCR platziert");
                Toast.makeText(this, "✅ OCR-Rechteck platziert!", Toast.LENGTH_SHORT).show();
                break;
                
            case REFILL_PLACE:
                refillButton.set(x + 40, y + 40);
                refillPlaced = true;
                currentMode = Mode.NONE;
                activeVisual = null;
                hideVisuals();
                updateStatus("✅ Refill platziert");
                Toast.makeText(this, "✅ Refill-Button platziert!", Toast.LENGTH_SHORT).show();
                break;
        }
    }
    
    // ============ VISUELLE HILFEN ============
    
    private void createVisualHelpers() {
        swipeVisual = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth();
                int h = getHeight();
                
                Paint paint = new Paint();
                paint.setColor(Color.YELLOW);
                paint.setStrokeWidth(8);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(w/2, 20, w/2, h - 40, paint);
                
                Paint arrowPaint = new Paint();
                arrowPaint.setColor(Color.YELLOW);
                arrowPaint.setStyle(Paint.Style.FILL);
                Path arrowPath = new Path();
                arrowPath.moveTo(w/2, h - 10);
                arrowPath.lineTo(w/2 - 30, h - 45);
                arrowPath.lineTo(w/2 + 30, h - 45);
                arrowPath.close();
                canvas.drawPath(arrowPath, arrowPaint);
                
                Paint textPaint = new Paint();
                textPaint.setColor(Color.YELLOW);
                textPaint.setTextSize(35);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("⬇", w/2, h/2 + 12, textPaint);
            }
        };
        
        ocrVisual = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Paint paint = new Paint();
                paint.setColor(Color.CYAN);
                paint.setStrokeWidth(4);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRect(10, 10, getWidth()-10, getHeight()-10, paint);
                
                Paint fillPaint = new Paint();
                fillPaint.setColor(Color.argb(50, 0, 255, 255));
                fillPaint.setStyle(Paint.Style.FILL);
                canvas.drawRect(10, 10, getWidth()-10, getHeight()-10, fillPaint);
                
                Paint textPaint = new Paint();
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(24);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("OCR", getWidth()/2, getHeight()/2 + 8, textPaint);
            }
        };
        
        refillVisual = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int size = Math.min(getWidth(), getHeight());
                Paint paint = new Paint();
                paint.setColor(Color.RED);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(size/2, size/2, size/2 - 8, paint);
                
                Paint borderPaint = new Paint();
                borderPaint.setColor(Color.WHITE);
                borderPaint.setStrokeWidth(2);
                borderPaint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(size/2, size/2, size/2 - 8, borderPaint);
                
                Paint textPaint = new Paint();
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(size * 0.35f);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("R", size/2, size/2 + size * 0.12f, textPaint);
            }
        };
        
        hideVisuals();
    }
    
    private void showSwipeVisual() {
        addVisual(swipeVisual, 100, 250);
        dragOffsetX = 50;
        dragOffsetY = 10;
    }
    
    private void showOcrVisual() {
        addVisual(ocrVisual, 180, 180);
        dragOffsetX = 90;
        dragOffsetY = 90;
    }
    
    private void showRefillVisual() {
        addVisual(refillVisual, 80, 80);
        dragOffsetX = 40;
        dragOffsetY = 40;
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
        
        visual.setLayoutParams(params);
        windowManager.addView(visual, params);
        
        visual.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    dragOffsetX = event.getRawX() - params.x;
                    dragOffsetY = event.getRawY() - params.y;
                    return true;
                    
                case MotionEvent.ACTION_MOVE:
                    params.x = (int)(event.getRawX() - dragOffsetX);
                    params.y = (int)(event.getRawY() - dragOffsetY);
                    windowManager.updateViewLayout(visual, params);
                    updateStatus("📌 (" + params.x + ", " + params.y + ")");
                    updateCoordinates(params.x, params.y);
                    return true;
                    
                case MotionEvent.ACTION_UP:
                    savePosition(params.x, params.y);
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
            } catch (Exception e) {}
        }
    }
    
    private void updateStatus(String text) {
        tvStatus.setText(text);
    }
    
    private void updateCoordinates(int x, int y) {
        tvCoordinates.setText("(" + x + ", " + y + ")");
    }
    
    private void updateLearningStatus() {
        if (tvLearning == null) return;
        
        String status = "🔄 " + totalSwipes + "x";
        if (cycleCount > 0) {
            status += " | ⚡ " + df.format(averageConsumptionRate) + " GB/min";
            status += " | 📊 " + df.format(lastDataValue) + " GB";
            if (usePrediction) {
                status += " | 🔮 Prognose";
            }
        } else {
            status += " | ⏳ Lerne...";
        }
        tvLearning.setText(status);
    }
    
    // ============ ULTIMATIVE LERN-FUNKTION ============
    
    private double calculateAverageConsumption() {
        if (consumptionHistory.isEmpty()) return 0;
        double sum = 0;
        for (double val : consumptionHistory) {
            sum += val;
        }
        return sum / consumptionHistory.size();
    }
    
    private long calculateUltimateWaitTime(double currentData, double consumptionRate) {
        if (consumptionRate <= 0.001) {
            return Math.min(MAX_WAIT_TIME, (long)(currentWaitTime * 1.5));
        }
        
        double remainingUntilRefill = currentData - REFILL_THRESHOLD - BUFFER_SAFETY;
        
        if (remainingUntilRefill <= 0) {
            return 0;
        }
        
        long calculatedTime = (long)((remainingUntilRefill / consumptionRate) * 60000);
        
        calculatedTime = Math.max(MIN_WAIT_TIME, calculatedTime);
        calculatedTime = Math.min(MAX_WAIT_TIME, calculatedTime);
        
        if (!actualWaitTimes.isEmpty() && actualWaitTimes.size() >= 2) {
            double avgWait = actualWaitTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(calculatedTime);
            
            calculatedTime = (long)((calculatedTime * 0.2) + (avgWait * 0.8));
        }
        
        if (consumptionHistory.size() >= 2 && confidenceLevel > 0.7) {
            double estimatedTimeToRefill = remainingUntilRefill / consumptionRate;
            if (estimatedTimeToRefill > 0 && estimatedTimeToRefill < 120) {
                usePrediction = true;
                predictedRefillTime = estimatedTimeToRefill;
                
                long predictionWait = (long)((estimatedTimeToRefill - 0.1) * 60000);
                predictionWait = Math.max(MIN_WAIT_TIME, Math.min(MAX_WAIT_TIME, predictionWait));
                
                if (predictionWait < calculatedTime) {
                    calculatedTime = predictionWait;
                }
            }
        }
        
        if (consumptionRate < 0.005) {
            calculatedTime = Math.min(MAX_WAIT_TIME, (long)(calculatedTime * 1.8));
        } else if (consumptionRate > 0.1) {
            calculatedTime = Math.max(MIN_WAIT_TIME, (long)(calculatedTime * 0.6));
        }
        
        calculatedTime = Math.max(MIN_WAIT_TIME, Math.min(MAX_WAIT_TIME, calculatedTime));
        
        currentWaitTime = calculatedTime;
        
        return calculatedTime;
    }
    
    private void learnFromCycle(double dataValue, double consumptionRate, long waitTime) {
        consumptionHistory.add(consumptionRate);
        actualWaitTimes.add(waitTime);
        cycleCount++;
        
        if (consumptionHistory.size() >= 3) {
            double variance = 0;
            double mean = calculateAverageConsumption();
            for (double val : consumptionHistory) {
                variance += Math.pow(val - mean, 2);
            }
            variance /= consumptionHistory.size();
            confidenceLevel = 1.0 / (1.0 + variance * 10);
            confidenceLevel = Math.min(1.0, confidenceLevel);
        }
        
        averageConsumptionRate = calculateAverageConsumption();
        totalWaitTime += waitTime;
        
        updateLearningStatus();
        
        Log.d("LidlRefill", "=== ULTIMATIVE EFFIZIENZ ZYKLUS " + cycleCount + " ===");
        Log.d("LidlRefill", "📊 Stand: " + df.format(dataValue) + " GB");
        Log.d("LidlRefill", "⚡ Verbrauch: " + df.format(consumptionRate) + " GB/min");
        Log.d("LidlRefill", "📈 Ø Verbrauch: " + df.format(averageConsumptionRate) + " GB/min");
        Log.d("LidlRefill", "⏱ Wartezeit: " + (waitTime/1000) + "s");
        Log.d("LidlRefill", "🔮 Confidence: " + df.format(confidenceLevel * 100) + "%");
        Log.d("LidlRefill", "🔄 Swipes: " + totalSwipes);
        Log.d("LidlRefill", "================================");
    }
    
    // ============ ULTIMATIVE AUSFÜHRUNG ============
    
    private void performSwipeGesture() {
        if (!swipePlaced) return;
        
        totalSwipes++;
        updateStatus("🔄 Swipe #" + totalSwipes + "...");
        
        Path path = new Path();
        path.moveTo(swipeStart.x, swipeStart.y);
        path.lineTo(swipeEnd.x, swipeEnd.y);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 500));
        
        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                updateStatus("✅ Swipe OK (#" + totalSwipes + ")");
                if (isRunning) {
                    handler.postDelayed(() -> performUltimateOcr(), OCR_DURATION);
                }
            }
        }, null);
    }
    
    private void performUltimateOcr() {
        if (!ocrPlaced || !isRunning) return;
        
        double currentData = simulateRealisticData();
        long currentTime = System.currentTimeMillis();
        
        if (isFirstMeasurement) {
            lastDataValue = currentData;
            lastDataTime = currentTime;
            isFirstMeasurement = false;
            isSecondMeasurement = true;
            updateStatus("📊 Start: " + df.format(currentData) + " GB");
            Toast.makeText(this, "📊 Start: " + df.format(currentData) + " GB", Toast.LENGTH_SHORT).show();
            
            handler.postDelayed(() -> {
                if (isRunning) {
                    performSwipeGesture();
                }
            }, INITIAL_WAIT_TIME);
            return;
        }
        
        if (isSecondMeasurement) {
            double timeDiffMinutes = (currentTime - lastDataTime) / 60000.0;
            double dataDiff = lastDataValue - currentData;
            double consumptionRate = dataDiff / timeDiffMinutes;
            
            if (consumptionRate > 0.001) {
                isSecondMeasurement = false;
                lastDataValue = currentData;
                lastDataTime = currentTime;
                
                long waitTime = calculateUltimateWaitTime(currentData, consumptionRate);
                learnFromCycle(currentData, consumptionRate, waitTime);
                
                updateStatus("📊 2. Messung: " + df.format(currentData) + " GB");
                Toast.makeText(this, 
                    "📊 2. Messung: " + df.format(currentData) + " GB\n" +
                    "⚡ Verbrauch: " + df.format(consumptionRate) + " GB/min", 
                    Toast.LENGTH_LONG).show();
                
                if (currentData <= REFILL_THRESHOLD) {
                    triggerRefill(currentData);
                    return;
                }
                
                handler.postDelayed(() -> {
                    if (isRunning) {
                        performSwipeGesture();
                    }
                }, waitTime);
                return;
            } else {
                updateStatus("⚠️ Kein Verbrauch - warte länger");
                handler.postDelayed(() -> {
                    if (isRunning) {
                        performSwipeGesture();
                    }
                }, Math.min(MAX_WAIT_TIME, (long)(MIN_WAIT_TIME * 2)));
                return;
            }
        }
        
        double timeDiffMinutes = (currentTime - lastDataTime) / 60000.0;
        double dataDiff = lastDataValue - currentData;
        double consumptionRate = dataDiff / timeDiffMinutes;
        
        if (consumptionRate <= 0.001) {
            updateStatus("⚠️ Kein Verbrauch - warte");
            handler.postDelayed(() -> {
                if (isRunning) {
                    performSwipeGesture();
                }
            }, Math.min(MAX_WAIT_TIME, (long)(currentWaitTime * 1.5)));
            return;
        }
        
        if (usePrediction && confidenceLevel > 0.7) {
            double currentRemaining = currentData - REFILL_THRESHOLD;
            if (currentRemaining < 0.05) {
                updateStatus("🔮 PROGNOSE: Refill in ~" + Math.round(predictedRefillTime) + "min");
                triggerRefill(currentData);
                return;
            }
        }
        
        long waitTime = calculateUltimateWaitTime(currentData, consumptionRate);
        learnFromCycle(currentData, consumptionRate, waitTime);
        
        if (currentData <= REFILL_THRESHOLD) {
            triggerRefill(currentData);
            return;
        }
        
        double remainingToRefill = currentData - REFILL_THRESHOLD;
        double estimatedMinutes = remainingToRefill / consumptionRate;
        
        if (estimatedMinutes > 0 && estimatedMinutes < 180) {
            usePrediction = true;
            predictedRefillTime = estimatedMinutes;
            updateStatus("🔮 Prognose: Refill in ~" + Math.round(estimatedMinutes) + "min");
        } else {
            usePrediction = false;
        }
        
        long waitSeconds = waitTime / 1000;
        long waitMinutes = waitSeconds / 60;
        String timeString = waitMinutes > 0 ? waitMinutes + "m " + (waitSeconds % 60) + "s" : waitSeconds + "s";
        
        String predictionInfo = usePrediction ? " | 🔮 " + Math.round(predictedRefillTime) + "min" : "";
        
        updateStatus("⏱ " + timeString + " bis " + df.format(REFILL_THRESHOLD) + " GB" + predictionInfo);
        Toast.makeText(this, 
            "📊 " + df.format(currentData) + " GB\n" +
            "⚡ " + df.format(consumptionRate) + " GB/min\n" +
            "⏱ Warte " + timeString + "\n" +
            "🎯 Refill bei <= " + REFILL_THRESHOLD + " GB" +
            (usePrediction ? "\n🔮 Prognose: " + Math.round(predictedRefillTime) + "min" : ""), 
            Toast.LENGTH_LONG).show();
        
        handler.postDelayed(() -> {
            if (isRunning) {
                performSwipeGesture();
            }
        }, waitTime);
        
        lastDataValue = currentData;
        lastDataTime = currentTime;
    }
    
    private void triggerRefill(double currentData) {
        updateStatus("🔴 REFILL! (" + df.format(currentData) + " GB <= " + REFILL_THRESHOLD + " GB)");
        Toast.makeText(this, 
            "🔴 REFILL!\n" +
            df.format(currentData) + " GB <= " + REFILL_THRESHOLD + " GB\n" +
            "🔄 Nur " + totalSwipes + " Swipes benötigt!\n" +
            "📊 " + cycleCount + " Zyklen gelernt", 
            Toast.LENGTH_LONG).show();
        
        handler.postDelayed(() -> {
            if (isRunning) {
                clickRefillButton();
                handler.postDelayed(() -> {
                    if (isRunning) {
                        isFirstMeasurement = true;
                        isSecondMeasurement = true;
                        usePrediction = false;
                        lastDataTime = 0;
                        lastDataValue = 0;
                        currentWaitTime = INITIAL_WAIT_TIME;
                        performSwipeGesture();
                    }
                }, 3000);
            }
        }, 1000);
    }
    
    private double simulateRealisticData() {
        double baseValue;
        if (cycleCount == 0) {
            baseValue = 0.85 + (Math.random() * 0.15);
        } else {
            double decrease = 0.005 + (Math.random() * 0.025);
            baseValue = Math.max(0.05, lastDataValue - decrease);
        }
        
        double noise = (Math.random() - 0.5) * 0.01;
        baseValue = Math.max(0.05, Math.min(1.5, baseValue + noise));
        
        return baseValue;
    }
    
    private void clickRefillButton() {
        if (!refillPlaced) return;
        
        updateStatus("🔄 Refill wird ausgeführt...");
        
        Path clickPath = new Path();
        clickPath.moveTo(refillButton.x, refillButton.y);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 1));
        
        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                updateStatus("✅ REFILL! (" + totalSwipes + " Swipes)");
                Toast.makeText(OverlayService.this, 
                    "✅ REFILL DURCHGEFÜHRT!\n" +
                    "🔄 Nur " + totalSwipes + " Swipes!\n" +
                    "📊 " + cycleCount + " Zyklen gelernt", 
                    Toast.LENGTH_LONG).show();
            }
        }, null);
    }
    
    private void performOcrNow() {
        if (!ocrPlaced) return;
        
        handler.postDelayed(() -> {
            double randomData = 0.3 + Math.random() * 0.7;
            String result = df.format(randomData) + " GB";
            updateStatus("📊 " + result);
            Toast.makeText(this, "📊 " + result, Toast.LENGTH_SHORT).show();
        }, 1000);
    }
    
    private void startAutomation() {
        isRunning = true;
        isFirstMeasurement = true;
        isSecondMeasurement = true;
        usePrediction = false;
        btnStartAuto.setText("▶ Läuft");
        btnStartAuto.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6D00")));
        updateStatus("🟢 ULTIMATIV gestartet");
        Toast.makeText(this, 
            "🚀 ULTIMATIVE EFFIZIENZ!\n" +
            "📊 Refill bei <= " + REFILL_THRESHOLD + " GB\n" +
            "⏱ Min: 2min | Max: 30min\n" +
            "🔮 Mit Prognose-Funktion", 
            Toast.LENGTH_LONG).show();
        
        lastDataTime = 0;
        lastDataValue = 0;
        currentWaitTime = INITIAL_WAIT_TIME;
        totalSwipes = 0;
        cycleCount = 0;
        confidenceLevel = 0;
        
        handler.postDelayed(() -> {
            if (isRunning) {
                performSwipeGesture();
            }
        }, 2000);
    }
    
    private void stopAutomation() {
        isRunning = false;
        btnStartAuto.setText("▶ Start");
        btnStartAuto.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336")));
        updateStatus("🔴 Gestoppt - " + totalSwipes + " Swipes");
        handler.removeCallbacksAndMessages(null);
        
        if (cycleCount > 0) {
            Toast.makeText(this, 
                "📊 ULTIMATIVE STATISTIK:\n" +
                "🔄 Swipes: " + totalSwipes + "\n" +
                "📊 Zyklen: " + cycleCount + "\n" +
                "⚡ Ø Verbrauch: " + df.format(averageConsumptionRate) + " GB/min\n" +
                "🔮 Prognosen: " + (usePrediction ? "Aktiv" : "Inaktiv"), 
                Toast.LENGTH_LONG).show();
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
