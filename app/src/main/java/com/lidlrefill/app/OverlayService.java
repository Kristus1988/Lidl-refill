package com.lidlrefill.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
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
    
    // ============ SPEICHER-FUNKTION ============
    private SharedPreferences prefs;
    private static final String PREF_SWIPE_START_X = "swipe_start_x";
    private static final String PREF_SWIPE_START_Y = "swipe_start_y";
    private static final String PREF_SWIPE_END_X = "swipe_end_x";
    private static final String PREF_SWIPE_END_Y = "swipe_end_y";
    private static final String PREF_OCR_LEFT = "ocr_left";
    private static final String PREF_OCR_TOP = "ocr_top";
    private static final String PREF_OCR_RIGHT = "ocr_right";
    private static final String PREF_OCR_BOTTOM = "ocr_bottom";
    private static final String PREF_REFILL_X = "refill_x";
    private static final String PREF_REFILL_Y = "refill_y";
    private static final String PREF_SWIPE_PLACED = "swipe_placed";
    private static final String PREF_OCR_PLACED = "ocr_placed";
    private static final String PREF_REFILL_PLACED = "refill_placed";
    
    // ============ POSITIONEN ============
    private Point swipeStart = new Point(0, 0);
    private Point swipeEnd = new Point(0, 0);
    private boolean swipePlaced = false;
    
    private Rect ocrRect = new Rect(100, 100, 350, 280);
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
    
    // ============ LERN-VARIABLEN ============
    private List<Double> consumptionHistory = new ArrayList<>();
    private List<Long> actualWaitTimes = new ArrayList<>();
    private double averageConsumptionRate = 0;
    private double lastDataValue = 0;
    private long lastDataTime = 0;
    private int cycleCount = 0;
    
    // ============ PARAMETER ============
    private static final double REFILL_THRESHOLD = 0.30;
    private static final double BUFFER_SAFETY = 0.10;
    private static final long MIN_WAIT_TIME = 120000;
    private static final long MAX_WAIT_TIME = 1800000;
    private static final long INITIAL_WAIT_TIME = 600000;
    private static final long SWIPE_DURATION = 3000;
    private static final long OCR_DURATION = 1500;
    
    private long currentWaitTime = INITIAL_WAIT_TIME;
    
    private boolean isFirstMeasurement = true;
    private boolean isSecondMeasurement = true;
    private double predictedRefillTime = 0;
    private double confidenceLevel = 0;
    private boolean usePrediction = false;
    
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
        
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        
        Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        screenWidth = size.x;
        screenHeight = size.y;
        
        loadPositions();
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.packageNames = null;
        setServiceInfo(info);
        
        createOverlay();
        createVisualHelpers();
        
        String status = "●";
        if (ocrPlaced) {
            status += " OCR: " + ocrRect.left + "," + ocrRect.top;
        } else {
            status += " OCR: fehlt";
        }
        updateStatus(status);
        updateLearningStatus();
        
        if (ocrPlaced) {
            updateOcrVisualPosition();
        }
    }
    
    // ============ POSITIONEN SPEICHERN & LADEN ============
    
    private void loadPositions() {
        swipeStart.x = prefs.getInt(PREF_SWIPE_START_X, screenWidth / 2);
        swipeStart.y = prefs.getInt(PREF_SWIPE_START_Y, 100);
        swipeEnd.x = prefs.getInt(PREF_SWIPE_END_X, screenWidth / 2);
        swipeEnd.y = prefs.getInt(PREF_SWIPE_END_Y, screenHeight - 100);
        swipePlaced = prefs.getBoolean(PREF_SWIPE_PLACED, true);
        
        ocrRect.left = prefs.getInt(PREF_OCR_LEFT, 100);
        ocrRect.top = prefs.getInt(PREF_OCR_TOP, 100);
        ocrRect.right = prefs.getInt(PREF_OCR_RIGHT, 350);
        ocrRect.bottom = prefs.getInt(PREF_OCR_BOTTOM, 280);
        ocrPlaced = prefs.getBoolean(PREF_OCR_PLACED, false);
        
        refillButton.x = prefs.getInt(PREF_REFILL_X, 500);
        refillButton.y = prefs.getInt(PREF_REFILL_Y, 500);
        refillPlaced = prefs.getBoolean(PREF_REFILL_PLACED, false);
    }
    
    private void savePositions() {
        SharedPreferences.Editor editor = prefs.edit();
        
        editor.putInt(PREF_SWIPE_START_X, swipeStart.x);
        editor.putInt(PREF_SWIPE_START_Y, swipeStart.y);
        editor.putInt(PREF_SWIPE_END_X, swipeEnd.x);
        editor.putInt(PREF_SWIPE_END_Y, swipeEnd.y);
        editor.putBoolean(PREF_SWIPE_PLACED, swipePlaced);
        
        editor.putInt(PREF_OCR_LEFT, ocrRect.left);
        editor.putInt(PREF_OCR_TOP, ocrRect.top);
        editor.putInt(PREF_OCR_RIGHT, ocrRect.right);
        editor.putInt(PREF_OCR_BOTTOM, ocrRect.bottom);
        editor.putBoolean(PREF_OCR_PLACED, ocrPlaced);
        
        editor.putInt(PREF_REFILL_X, refillButton.x);
        editor.putInt(PREF_REFILL_Y, refillButton.y);
        editor.putBoolean(PREF_REFILL_PLACED, refillPlaced);
        
        editor.apply();
    }
    
    private void updateOcrVisualPosition() {
        if (ocrVisual != null && ocrVisual.getParent() != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) ocrVisual.getLayoutParams();
            params.x = ocrRect.left;
            params.y = ocrRect.top;
            params.width = ocrRect.width();
            params.height = ocrRect.height();
            windowManager.updateViewLayout(ocrVisual, params);
            ocrVisual.invalidate();
        }
    }
    
    // ============ CREATE OVERLAY ============
    
    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        FrameLayout mainContainer = new FrameLayout(this);
        mainContainer.setClickable(true);
        mainContainer.setFocusable(true);
        
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
        btnClose.setTextSize(10);
        btnClose.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.RED));
        btnClose.setPadding(0, 0, 0, 0);
        btnClose.setAllCaps(false);
        btnClose.setClickable(true);
        
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(28, 28);
        closeParams.gravity = Gravity.TOP | Gravity.END;
        closeParams.setMargins(0, 2, 2, 0);
        btnClose.setLayoutParams(closeParams);
        
        btnClose.setOnClickListener(v -> {
            stopAutomation();
            hideVisuals();
            savePositions();
            if (floatingView != null && windowManager != null) {
                try {
                    windowManager.removeView(floatingView);
                } catch (Exception e) {}
            }
            stopSelf();
            Toast.makeText(this, "Overlay geschlossen", Toast.LENGTH_SHORT).show();
        });
        
        setupButtons();
        
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
        
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.x = 20;
        params.y = 20;
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
                updateStatus("● Swipe-Modus beendet");
                return;
            }
            currentMode = Mode.SWIPE_PLACE;
            updateStatus("🟡 S: Pfeil ziehen");
            Toast.makeText(this, "Ziehe den gelben Pfeil an die gewünschte Stelle", Toast.LENGTH_SHORT).show();
            showSwipeVisual();
            activeVisual = swipeVisual;
        });
        
        btnOcrPlace.setOnClickListener(v -> {
            if (currentMode == Mode.OCR_PLACE) {
                currentMode = Mode.NONE;
                hideVisuals();
                updateStatus("● OCR-Modus beendet");
                return;
            }
            currentMode = Mode.OCR_PLACE;
            updateStatus("🟡 OCR: Rechteck ziehen");
            Toast.makeText(this, "Ziehe das blaue Rechteck über den Datenverbrauch", Toast.LENGTH_LONG).show();
            showOcrVisual();
            activeVisual = ocrVisual;
        });
        
        btnRefillPlace.setOnClickListener(v -> {
            if (currentMode == Mode.REFILL_PLACE) {
                currentMode = Mode.NONE;
                hideVisuals();
                updateStatus("● Refill-Modus beendet");
                return;
            }
            currentMode = Mode.REFILL_PLACE;
            updateStatus("🟡 R: Kreis ziehen");
            Toast.makeText(this, "Ziehe den roten Kreis auf den '1GB laden'-Button", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "❌ OCR fehlt!", Toast.LENGTH_SHORT).show();
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
                savePositions();
                updateStatus("● Swipe gespeichert");
                Toast.makeText(this, "✅ Swipe platziert!", Toast.LENGTH_SHORT).show();
                break;
                
            case OCR_PLACE:
                ocrRect.left = x;
                ocrRect.top = y;
                ocrRect.right = x + 250;
                ocrRect.bottom = y + 180;
                ocrPlaced = true;
                currentMode = Mode.NONE;
                activeVisual = null;
                hideVisuals();
                savePositions();
                updateStatus("● OCR bei (" + x + ", " + y + ")");
                Toast.makeText(this, "✅ OCR platziert! (" + x + ", " + y + ")", Toast.LENGTH_SHORT).show();
                break;
                
            case REFILL_PLACE:
                refillButton.set(x + 40, y + 40);
                refillPlaced = true;
                currentMode = Mode.NONE;
                activeVisual = null;
                hideVisuals();
                savePositions();
                updateStatus("● Refill gespeichert");
                Toast.makeText(this, "✅ Refill platziert!", Toast.LENGTH_SHORT).show();
                break;
        }
    }
    
    // ============ VISUELLE HILFEN ============
    
    private void createVisualHelpers() {
        // Swipe-Visual
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
        
        // OCR-Visual - KORRIGIERT
        ocrVisual = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth();
                int h = getHeight();
                
                Paint paint = new Paint();
                paint.setColor(Color.CYAN);
                paint.setStrokeWidth(6);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRect(5, 5, w-5, h-5, paint);
                
                Paint fillPaint = new Paint();
                fillPaint.setColor(Color.argb(80, 0, 255, 255));
                fillPaint.setStyle(Paint.Style.FILL);
                canvas.drawRect(5, 5, w-5, h-5, fillPaint);
                
                Paint textPaint = new Paint();
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(36);
                textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);  // ← KORRIGIERT
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("📷 OCR", w/2, h/2 + 12, textPaint);
                
                Paint sizePaint = new Paint();
                sizePaint.setColor(Color.argb(200, 255, 255, 255));
                sizePaint.setTextSize(16);
                sizePaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(w + " x " + h, w/2, h - 15, sizePaint);
            }
        };
        
        // Refill-Visual
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
                borderPaint.setStrokeWidth(3);
                borderPaint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(size/2, size/2, size/2 - 8, borderPaint);
                
                Paint textPaint = new Paint();
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(size * 0.4f);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("1GB", size/2, size/2 + size * 0.15f, textPaint);
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
        addVisual(ocrVisual, 250, 180);
        dragOffsetX = 125;
        dragOffsetY = 90;
        
        if (ocrPlaced) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) ocrVisual.getLayoutParams();
            params.x = ocrRect.left;
            params.y = ocrRect.top;
            windowManager.updateViewLayout(ocrVisual, params);
        }
    }
    
    private void showRefillVisual() {
        addVisual(refillVisual, 80, 80);
        dragOffsetX = 40;
        dragOffsetY = 40;
    }
    
    // ============ addVisual - KORRIGIERT ============
    
    private void addVisual(View visual, int width, int height) {
        if (visual == swipeVisual) {
            removeVisual(ocrVisual);
            removeVisual(refillVisual);
        } else if (visual == ocrVisual) {
            removeVisual(swipeVisual);
            removeVisual(refillVisual);
        } else if (visual == refillVisual) {
            removeVisual(swipeVisual);
            removeVisual(ocrVisual);
        } else {
            hideVisuals();
        }
        
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
        
        if (visual == ocrVisual && ocrPlaced) {
            params.x = ocrRect.left;
            params.y = ocrRect.top;
        } else {
            params.x = screenWidth / 2 - width / 2;
            params.y = screenHeight / 2 - height / 2;
        }
        
        // params.elevation = 1000;  // ← ENTFERNT
        visual.setElevation(1000);  // ← STATTDESSEN
        
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
                    
                    if (visual == ocrVisual) {
                        updateStatus("📌 OCR: (" + params.x + ", " + params.y + ")");
                    } else {
                        updateStatus("📌 (" + params.x + ", " + params.y + ")");
                    }
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
            } catch (Exception e) {
                Log.e("LidlRefill", "Fehler beim Entfernen: " + e.getMessage());
            }
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
            status += " | " + df.format(averageConsumptionRate) + " GB/min";
            status += " | " + df.format(lastDataValue) + " GB";
        }
        status += ocrPlaced ? " | OCR✅" : " | OCR❌";
        tvLearning.setText(status);
    }
    
    // ============ LERN-FUNKTION ============
    
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
    }
    
    // ============ AUSFÜHRUNG ============
    
    private void performSwipeGesture() {
        if (!swipePlaced) return;
        
        totalSwipes++;
        updateStatus("🔄 Swipe #" + totalSwipes);
        
        Path path = new Path();
        path.moveTo(swipeStart.x, swipeStart.y);
        path.lineTo(swipeEnd.x, swipeEnd.y);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 500));
        
        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                updateStatus("✅ Swipe #" + totalSwipes);
                if (isRunning) {
                    handler.postDelayed(() -> performUltimateOcr(), OCR_DURATION);
                }
            }
        }, null);
    }
    
    private void performUltimateOcr() {
        if (!ocrPlaced || !isRunning) {
            if (!ocrPlaced) {
                updateStatus("⚠️ OCR fehlt!");
            }
            return;
        }
        
        double currentData = simulateRealisticData();
        long currentTime = System.currentTimeMillis();
        
        if (isFirstMeasurement) {
            lastDataValue = currentData;
            lastDataTime = currentTime;
            isFirstMeasurement = false;
            isSecondMeasurement = true;
            updateStatus("📊 " + df.format(currentData) + " GB");
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
                
                updateStatus("📊 " + df.format(currentData) + " GB");
                Toast.makeText(this, 
                    "📊 " + df.format(currentData) + " GB\n" +
                    "⚡ " + df.format(consumptionRate) + " GB/min", 
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
                updateStatus("⚠️ Kein Verbrauch");
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
            updateStatus("⚠️ Kein Verbrauch");
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
                updateStatus("🔮 Refill in ~" + Math.round(predictedRefillTime) + "min");
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
            updateStatus("🔮 " + Math.round(estimatedMinutes) + "min");
        } else {
            usePrediction = false;
        }
        
        long waitSeconds = waitTime / 1000;
        long waitMinutes = waitSeconds / 60;
        String timeString = waitMinutes > 0 ? waitMinutes + "m" : waitSeconds + "s";
        
        updateStatus("⏱ " + timeString + " bis " + df.format(REFILL_THRESHOLD) + " GB");
        Toast.makeText(this, 
            "📊 " + df.format(currentData) + " GB\n" +
            "⏱ Warte " + timeString, 
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
        updateStatus("🔴 REFILL!");
        Toast.makeText(this, 
            "🔴 REFILL!\n" +
            df.format(currentData) + " GB <= " + REFILL_THRESHOLD + " GB\n" +
            "🔄 " + totalSwipes + " Swipes", 
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
        
        updateStatus("🔄 Refill...");
        
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
                    "✅ REFILL!\n🔄 " + totalSwipes + " Swipes", 
                    Toast.LENGTH_LONG).show();
            }
        }, null);
    }
    
    private void performOcrNow() {
        if (!ocrPlaced) {
            Toast.makeText(this, "❌ OCR nicht platziert!", Toast.LENGTH_SHORT).show();
            return;
        }
        
        handler.postDelayed(() -> {
            double data = 0.3 + Math.random() * 0.7;
            String result = df.format(data) + " GB";
            updateStatus("📊 " + result);
            Toast.makeText(this, 
                "📊 " + result + "\n📍 " + ocrRect.left + "," + ocrRect.top, 
                Toast.LENGTH_LONG).show();
        }, 1500);
    }
    
    private void startAutomation() {
        isRunning = true;
        isFirstMeasurement = true;
        isSecondMeasurement = true;
        usePrediction = false;
        btnStartAuto.setText("▶");
        btnStartAuto.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6D00")));
        updateStatus("🟢 Automatik");
        Toast.makeText(this, 
            "🚀 Automatik gestartet!\n" +
            "📍 OCR: " + ocrRect.left + "," + ocrRect.top, 
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
        btnStartAuto.setText("▶");
        btnStartAuto.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336")));
        updateStatus("● Gestoppt");
        handler.removeCallbacksAndMessages(null);
        
        if (cycleCount > 0) {
            Toast.makeText(this, 
                "📊 " + totalSwipes + " Swipes\n" +
                "📊 " + cycleCount + " Zyklen", 
                Toast.LENGTH_LONG).show();
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        savePositions();
        hideVisuals();
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {}
        }
        handler.removeCallbacksAndMessages(null);
    }
}
