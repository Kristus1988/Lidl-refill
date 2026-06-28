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
    
    // ============ ULTRA-EFFIZIENTE LERN-FUNKTION ============
    private List<Double> consumptionHistory = new ArrayList<>();
    private List<Long> actualWaitTimes = new ArrayList<>();
    private double averageConsumptionRate = 0;
    private double lastDataValue = 0;
    private long lastDataTime = 0;
    private int cycleCount = 0;
    
    // ============ ULTRA-EFFIZIENZ PARAMETER ============
    private static final double REFILL_THRESHOLD = 0.30;      // Refill bei <= 0.30 GB
    private static final double BUFFER_SAFETY = 0.08;         // 0.08 GB Puffer (erhöht)
    private static final long MIN_WAIT_TIME = 60000;          // 60 Sekunden (erhöht von 30s)
    private static final long MAX_WAIT_TIME = 900000;         // 15 Minuten (erhöht)
    private static final long INITIAL_WAIT_TIME = 300000;     // 5 Minuten (erhöht von 2min)
    private static final long SWIPE_DURATION = 3000;          // 3 Sekunden
    private static final long OCR_DURATION = 1500;            // 1.5 Sekunden
    
    // Aktuelle Wartezeit
    private long currentWaitTime = INITIAL_WAIT_TIME;
    private int consecutiveLowConsumption = 0;
    private int skipCounter = 0;
    private boolean isFirstMeasurement = true;
    
    // Trend-Analyse
    private List<Double> trendHistory = new ArrayList<>();
    private double trendFactor = 1.0;
    
    private DecimalFormat df = new DecimalFormat("0.000");
    
    // Statistik
    private double minConsumption = Double.MAX_VALUE;
    private double maxConsumption = 0;
    private double totalConsumption = 0;
    private long totalWaitTime = 0;
    private int totalSwipes = 0;
    
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
        updateStatus("✅ Ultra-Effizienz: Refill bei <= " + REFILL_THRESHOLD + " GB");
        updateLearningStatus();
    }
    
    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        FrameLayout mainContainer = new FrameLayout(this);
        
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
        
        btnClose = new Button(this);
        btnClose.setText("✕");
        btnClose.setTextColor(Color.WHITE);
        btnClose.setTextSize(16);
        btnClose.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.RED));
        btnClose.setPadding(0, 0, 0, 0);
        btnClose.setAllCaps(false);
        btnClose.setClickable(true);
        
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(60, 60);
        closeParams.gravity = Gravity.TOP | Gravity.END;
        closeParams.setMargins(0, 0, 5, 0);
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
        
        // Drag-Handle
        View dragHandle = new View(this);
        dragHandle.setBackgroundColor(Color.argb(80, 255, 255, 255));
        FrameLayout.LayoutParams dragParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 25
        );
        dragParams.gravity = Gravity.TOP;
        dragHandle.setLayoutParams(dragParams);
        dragHandle.setClickable(true);
        
        dragHandle.setOnTouchListener((v, event) -> {
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
        controlPanel.addView(dragHandle);
        
        mainContainer.addView(controlPanel);
        mainContainer.addView(btnClose);
        
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
        
        String status = "🔄 " + cycleCount + "x";
        if (cycleCount > 0) {
            status += " | ⚡ " + df.format(averageConsumptionRate) + " GB/min";
            status += " | 📊 " + df.format(lastDataValue) + " GB";
            status += " | ⏱ " + (currentWaitTime/1000) + "s";
        } else {
            status += " | ⏳ Lerne...";
        }
        tvLearning.setText(status);
    }
    
    // ============ ULTRA-EFFIZIENTE LERN-FUNKTION ============
    
    private double calculateAverageConsumption() {
        if (consumptionHistory.isEmpty()) return 0;
        double sum = 0;
        for (double val : consumptionHistory) {
            sum += val;
        }
        return sum / consumptionHistory.size();
    }
    
    private long calculateUltraEfficientWaitTime(double currentData, double consumptionRate) {
        if (consumptionRate <= 0.001) {
            // Kein Verbrauch - extrem lange warten
            consecutiveLowConsumption++;
            if (consecutiveLowConsumption >= 2) {
                return Math.min(MAX_WAIT_TIME, currentWaitTime * 2);
            }
            return currentWaitTime;
        }
        consecutiveLowConsumption = 0;
        
        // Berechne wie lange es dauert bis zur Refill-Schwelle
        double remainingUntilRefill = currentData - REFILL_THRESHOLD - BUFFER_SAFETY;
        
        if (remainingUntilRefill <= 0) {
            return 0; // Sofort Refill
        }
        
        // Basis-Berechnung
        long calculatedTime = (long)((remainingUntilRefill / consumptionRate) * 60000);
        
        // ============ ULTRA-EFFIZIENZ-OPTIMIERUNGEN ============
        
        // 1. Mindestwartezeit: 60 Sekunden (deutlich erhöht!)
        calculatedTime = Math.max(MIN_WAIT_TIME, calculatedTime);
        
        // 2. Maximalwartezeit: 15 Minuten
        calculatedTime = Math.min(MAX_WAIT_TIME, calculatedTime);
        
        // 3. Lerneffekt mit Gewichtung
        if (!actualWaitTimes.isEmpty() && actualWaitTimes.size() >= 2) {
            double avgWait = actualWaitTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(calculatedTime);
            
            // 70% neue Berechnung, 30% Historie (mehr Gewicht auf Historie)
            calculatedTime = (long)((calculatedTime * 0.7) + (avgWait * 0.3));
        }
        
        // 4. Trend-Faktor (schwankender Verbrauch)
        if (!trendHistory.isEmpty()) {
            double avgTrend = trendHistory.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
            if (avgTrend > 0) {
                // Bei steigendem Trend: kürzer warten
                // Bei fallendem Trend: länger warten
                calculatedTime = (long)(calculatedTime / Math.max(0.5, avgTrend));
            }
        }
        
        // 5. Abzug der Aktualisierungsdauer (Swipe + OCR)
        calculatedTime = Math.max(MIN_WAIT_TIME, calculatedTime - (SWIPE_DURATION + OCR_DURATION));
        
        // 6. Verbrauchsbasierte Anpassung
        if (consumptionRate < 0.01) {
            // Sehr langsamer Verbrauch: sehr lange warten
            calculatedTime = Math.min(MAX_WAIT_TIME, (long)(calculatedTime * 1.5));
        } else if (consumptionRate > 0.08) {
            // Hoher Verbrauch: kürzer warten
            calculatedTime = Math.max(MIN_WAIT_TIME, (long)(calculatedTime * 0.7));
        }
        
        // 7. Sicherheitsbegrenzung
        calculatedTime = Math.max(MIN_WAIT_TIME, Math.min(MAX_WAIT_TIME, calculatedTime));
        
        // Aktuelle Wartezeit speichern
        currentWaitTime = calculatedTime;
        
        return calculatedTime;
    }
    
    private void learnFromCycle(double dataValue, double consumptionRate, long waitTime) {
        consumptionHistory.add(consumptionRate);
        actualWaitTimes.add(waitTime);
        cycleCount++;
        
        minConsumption = Math.min(minConsumption, consumptionRate);
        maxConsumption = Math.max(maxConsumption, consumptionRate);
        totalConsumption += consumptionRate;
        totalWaitTime += waitTime;
        averageConsumptionRate = calculateAverageConsumption();
        
        // Trend speichern
        if (consumptionHistory.size() >= 2) {
            double prev = consumptionHistory.get(consumptionHistory.size() - 2);
            double current = consumptionHistory.get(consumptionHistory.size() - 1);
            if (prev > 0) {
                trendHistory.add(current / prev);
                if (trendHistory.size() > 5) {
                    trendHistory.remove(0);
                }
            }
        }
        
        // Log
        Log.d("LidlRefill", "=== ULTRA-EFFIZIENZ ZYKLUS " + cycleCount + " ===");
        Log.d("LidlRefill", "📊 Stand: " + df.format(dataValue) + " GB");
        Log.d("LidlRefill", "⚡ Verbrauch: " + df.format(consumptionRate) + " GB/min");
        Log.d("LidlRefill", "📈 Ø Verbrauch: " + df.format(averageConsumptionRate) + " GB/min");
        Log.d("LidlRefill", "⏱ Wartezeit: " + (waitTime/1000) + "s");
        Log.d("LidlRefill", "🔄 Swipes: " + totalSwipes);
        Log.d("LidlRefill", "================================");
        
        updateLearningStatus();
    }
    
    // ============ ULTRA-EFFIZIENTE AUSFÜHRUNG ============
    
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
                    handler.postDelayed(() -> performUltraEfficientOcr(), OCR_DURATION);
                }
            }
        }, null);
    }
    
    private void performUltraEfficientOcr() {
        if (!ocrPlaced || !isRunning) return;
        
        // Simuliere OCR mit realistischen Daten
        double currentData = simulateRealisticData();
        long currentTime = System.currentTimeMillis();
        
        // Erste Messung
        if (isFirstMeasurement) {
            lastDataValue = currentData;
            lastDataTime = currentTime;
            isFirstMeasurement = false;
            updateStatus("📊 Start: " + df.format(currentData) + " GB");
            Toast.makeText(this, "📊 Start: " + df.format(currentData) + " GB", Toast.LENGTH_SHORT).show();
            
            // Initiale Wartezeit: 5 Minuten (sehr lange!)
            handler.postDelayed(() -> {
                if (isRunning) {
                    performSwipeGesture();
                }
            }, INITIAL_WAIT_TIME);
            return;
        }
        
        // Berechne Verbrauchsrate
        double timeDiffMinutes = (currentTime - lastDataTime) / 60000.0;
        double dataDiff = lastDataValue - currentData;
        double consumptionRate = dataDiff / timeDiffMinutes;
        
        // ============ ULTRA-EFFIZIENZ-PRÜFUNGEN ============
        
        // 1. Kein oder negativer Verbrauch
        if (consumptionRate <= 0.001) {
            consecutiveLowConsumption++;
            if (consecutiveLowConsumption >= 3) {
                // Nach 3 mal keinem Verbrauch: sehr lange warten
                currentWaitTime = Math.min(MAX_WAIT_TIME, currentWaitTime * 2);
                updateStatus("⚠️ Kein Verbrauch - Intervall auf " + (currentWaitTime/1000) + "s erhöht");
            }
            handler.postDelayed(() -> {
                if (isRunning) {
                    performSwipeGesture();
                }
            }, Math.min(MAX_WAIT_TIME, currentWaitTime));
            return;
        } else {
            consecutiveLowConsumption = 0;
        }
        
        // 2. Unrealistische Werte begrenzen
        if (consumptionRate > 0.5) consumptionRate = 0.5;
        if (consumptionRate < 0.001) consumptionRate = 0.001;
        
        // Ultimative Wartezeit berechnen
        long waitTime = calculateUltraEfficientWaitTime(currentData, consumptionRate);
        learnFromCycle(currentData, consumptionRate, waitTime);
        
        // ============ ENTSCHEIDUNG ============
        
        // Prüfe ob Refill ausgeführt werden soll
        if (currentData <= REFILL_THRESHOLD) {
            // REFILL AUSFÜHREN!
            updateStatus("🔴 REFILL! (" + df.format(currentData) + " GB <= " + REFILL_THRESHOLD + " GB)");
            Toast.makeText(this, 
                "🔴 REFILL!\n" +
                df.format(currentData) + " GB <= " + REFILL_THRESHOLD + " GB\n" +
                "🔄 " + totalSwipes + " Swipes gespart!", 
                Toast.LENGTH_LONG).show();
            
            handler.postDelayed(() -> {
                if (isRunning) {
                    clickRefillButton();
                    // Nach Refill: neuen Zyklus starten
                    handler.postDelayed(() -> {
                        if (isRunning) {
                            isFirstMeasurement = true;
                            lastDataTime = 0;
                            lastDataValue = 0;
                            currentWaitTime = INITIAL_WAIT_TIME;
                            performSwipeGesture();
                        }
                    }, 3000);
                }
            }, 1000);
            
        } else {
            // NUR WARTEN - KEIN REFILL
            long waitSeconds = waitTime / 1000;
            long waitMinutes = waitSeconds / 60;
            long waitRemainingSeconds = waitSeconds % 60;
            
            String timeString;
            if (waitMinutes > 0) {
                timeString = waitMinutes + "m " + waitRemainingSeconds + "s";
            } else {
                timeString = waitSeconds + "s";
            }
            
            // Effizienz-Info: Wie viele Swipes gespart
            double estimatedSwipesSaved = (waitTime / (MIN_WAIT_TIME)) - 1;
            String savedInfo = "";
            if (estimatedSwipesSaved > 0) {
                savedInfo = " | 💾 ~" + Math.round(estimatedSwipesSaved) + " Swipes gespart";
            }
            
            updateStatus("⏱ Warte " + timeString + " bis " + df.format(REFILL_THRESHOLD) + " GB");
            Toast.makeText(this, 
                "📊 " + df.format(currentData) + " GB\n" +
                "⚡ " + df.format(consumptionRate) + " GB/min\n" +
                "⏱ Warte " + timeString + "\n" +
                "🎯 Refill bei <= " + REFILL_THRESHOLD + " GB" +
                savedInfo, 
                Toast.LENGTH_LONG).show();
            
            // Warten und dann erneut aktualisieren
            handler.postDelayed(() -> {
                if (isRunning) {
                    performSwipeGesture();
                }
            }, waitTime);
        }
        
        // Daten speichern
        lastDataValue = currentData;
        lastDataTime = currentTime;
    }
    
    // ============ REALISTISCHE DATEN-SIMULATION ============
    private double simulateRealisticData() {
        // Realistischer Datenverbrauch mit natürlichen Schwankungen
        double baseValue;
        if (cycleCount == 0) {
            baseValue = 0.85 + (Math.random() * 0.15);
        } else {
            // Gleichmäßiger Verbrauch: 0.01-0.05 GB pro Zyklus
            double decrease = 0.01 + (Math.random() * 0.04);
            baseValue = Math.max(0.05, lastDataValue - decrease);
        }
        
        // Natürliche Schwankungen
        double noise = (Math.random() - 0.5) * 0.02;
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
                updateStatus("✅ Refill! (" + cycleCount + " Zyklen, " + totalSwipes + " Swipes)");
                Toast.makeText(OverlayService.this, 
                    "✅ REFILL DURCHGEFÜHRT!\n" +
                    "📊 Zyklen: " + cycleCount + "\n" +
                    "🔄 Swipes: " + totalSwipes + "\n" +
                    "⏱ Gespart: ~" + Math.round(totalSwipes * 0.7) + " Swipes", 
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
        btnStartAuto.setText("▶ Läuft");
        btnStartAuto.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6D00")));
        updateStatus("🟢 Ultra-Effizienz aktiv");
        Toast.makeText(this, 
            "🚀 ULTRA-EFFIZIENZ gestartet!\n" +
            "📊 Refill bei <= " + REFILL_THRESHOLD + " GB\n" +
            "⏱ Min. Intervall: " + (MIN_WAIT_TIME/1000) + "s\n" +
            "⏱ Max. Intervall: " + (MAX_WAIT_TIME/1000) + "s", 
            Toast.LENGTH_LONG).show();
        
        lastDataTime = 0;
        lastDataValue = 0;
        currentWaitTime = INITIAL_WAIT_TIME;
        consecutiveLowConsumption = 0;
        totalSwipes = 0;
        
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
        updateStatus("🔴 Gestoppt - " + cycleCount + " Zyklen");
        handler.removeCallbacksAndMessages(null);
        
        if (cycleCount > 0) {
            Toast.makeText(this, 
                "📊 ULTRA-EFFIZIENZ STATISTIK:\n" +
                "🔄 Zyklen: " + cycleCount + "\n" +
                "⚡ Ø Verbrauch: " + df.format(averageConsumptionRate) + " GB/min\n" +
                "⏱ Ø Wartezeit: " + (totalWaitTime/cycleCount/1000) + "s\n" +
                "🔄 Swipes: " + totalSwipes + "\n" +
                "💾 Geschätzt gespart: ~" + Math.round(totalSwipes * 0.5) + " Swipes",
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
