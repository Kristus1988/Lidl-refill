package com.lidlrefill.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DecimalFormat;
import java.util.Random;

public class OverlayService extends AccessibilityService {
    private static final String TAG = "LidlRefill";
    
    private WindowManager windowManager;
    private FrameLayout floatingView;
    private TextView tvStatus, tvCoordinates, tvLearning;
    private Spinner spinnerConsumption;
    private Button btnSwipePlace, btnRefillPlace;
    private Button btnSwipeNow, btnRefillNow, btnStopAuto, btnStartAuto;
    private Button btnClose;
    
    private SharedPreferences prefs;
    private static final String PREF_SWIPE_START_X = "swipe_start_x";
    private static final String PREF_SWIPE_START_Y = "swipe_start_y";
    private static final String PREF_SWIPE_END_X = "swipe_end_x";
    private static final String PREF_SWIPE_END_Y = "swipe_end_y";
    private static final String PREF_REFILL_X = "refill_x";
    private static final String PREF_REFILL_Y = "refill_y";
    private static final String PREF_SWIPE_PLACED = "swipe_placed";
    private static final String PREF_REFILL_PLACED = "refill_placed";
    
    private int screenWidth;
    private int screenHeight;
    
    private Point swipeStart = new Point(0, 0);
    private Point swipeEnd = new Point(0, 0);
    private boolean swipePlaced = false;
    private Point refillButton = new Point(500, 500);
    private boolean refillPlaced = false;
    
    private enum Mode { NONE, SWIPE_PLACE, REFILL_PLACE }
    private Mode currentMode = Mode.NONE;
    
    private boolean isDragging = false;
    private float lastX, lastY;
    private float dragOffsetX, dragOffsetY;
    private View activeVisual = null;
    private View swipeVisual, refillVisual;
    
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private boolean isOverlayDragging = false;
    private float overlayDragX, overlayDragY;
    
    private static final double[] CONSUMPTION_OPTIONS = {
        0.05, 0.08, 0.15
    };
    private static final String[] CONSUMPTION_LABELS = {
        "📱 Standard (17-20 Min)",
        "📺 FullHD (10-13 Min)",
        "🎬 4K (5-8 Min)"
    };
    private double selectedConsumptionRate = 0.05;
    
    private static final double REFILL_THRESHOLD = 0.30;
    private static final double SIMULATED_START_DATA = 0.90;
    private static final long MIN_WAIT_TIME = 120000;
    private static final long MAX_WAIT_TIME = 1800000;
    private static final long INITIAL_WAIT_TIME = 600000;
    
    private static final long[][] WAIT_RANGES = {
        {900000, 1200000},
        {600000, 780000},
        {300000, 480000}
    };
    
    private double currentDataValue = SIMULATED_START_DATA;
    private double consumptionRate = 0.05;
    private int cycleCount = 0;
    private int totalSwipes = 0;
    private long currentWaitTime = INITIAL_WAIT_TIME;
    private int currentModeIndex = 0;
    
    private DecimalFormat df = new DecimalFormat("0.000");
    private Random random = new Random();
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override
    public void onInterrupt() {}
    
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        screenWidth = size.x;
        screenHeight = size.y;
        
        swipeStart.set(screenWidth / 2, 100);
        swipeEnd.set(screenWidth / 2, screenHeight - 100);
        swipePlaced = true;
        
        loadPositions();
        
        int savedIndex = prefs.getInt("consumption_index", 0);
        currentModeIndex = savedIndex;
        selectedConsumptionRate = CONSUMPTION_OPTIONS[savedIndex];
        consumptionRate = selectedConsumptionRate;
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.packageNames = null;
        setServiceInfo(info);
        
        createOverlay();
        createVisualHelpers();
        
        updateStatus("● " + CONSUMPTION_LABELS[savedIndex]);
        updateLearningStatus();
    }
    
    private void loadPositions() {
        swipeStart.x = prefs.getInt(PREF_SWIPE_START_X, swipeStart.x);
        swipeStart.y = prefs.getInt(PREF_SWIPE_START_Y, swipeStart.y);
        swipeEnd.x = prefs.getInt(PREF_SWIPE_END_X, swipeEnd.x);
        swipeEnd.y = prefs.getInt(PREF_SWIPE_END_Y, swipeEnd.y);
        swipePlaced = prefs.getBoolean(PREF_SWIPE_PLACED, true);
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
        editor.putInt(PREF_REFILL_X, refillButton.x);
        editor.putInt(PREF_REFILL_Y, refillButton.y);
        editor.putBoolean(PREF_REFILL_PLACED, refillPlaced);
        editor.apply();
    }
    
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
        spinnerConsumption = controlView.findViewById(R.id.spinnerConsumption);
        btnSwipePlace = controlView.findViewById(R.id.btnSwipePlace);
        btnRefillPlace = controlView.findViewById(R.id.btnRefillPlace);
        btnSwipeNow = controlView.findViewById(R.id.btnSwipeNow);
        btnRefillNow = controlView.findViewById(R.id.btnRefillNow);
        btnStopAuto = controlView.findViewById(R.id.btnStopAuto);
        btnStartAuto = controlView.findViewById(R.id.btnStartAuto);
        
        // ============ SPINNER MIT WEISSEM TEXT ============
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
            this, 
            android.R.layout.simple_spinner_dropdown_item,
            CONSUMPTION_LABELS
        ) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text = (TextView) view;
                text.setTextColor(Color.WHITE);
                text.setTextSize(14);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerConsumption.setAdapter(adapter);
        
        int savedIndex = prefs.getInt("consumption_index", 0);
        spinnerConsumption.setSelection(savedIndex);
        
        spinnerConsumption.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentModeIndex = position;
                selectedConsumptionRate = CONSUMPTION_OPTIONS[position];
                consumptionRate = selectedConsumptionRate;
                prefs.edit().putInt("consumption_index", position).apply();
                updateStatus("● " + CONSUMPTION_LABELS[position]);
                updateLearningStatus();
                Toast.makeText(OverlayService.this, 
                    "📊 " + CONSUMPTION_LABELS[position], 
                    Toast.LENGTH_SHORT).show();
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        // ============ GRÖSSERER SCHLIEßEN-BUTTON ============
        btnClose = new Button(this);
        btnClose.setText("✕");
        btnClose.setTextColor(Color.WHITE);
        btnClose.setTextSize(22);
        btnClose.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.RED));
        btnClose.setPadding(0, 0, 0, 0);
        btnClose.setAllCaps(false);
        btnClose.setClickable(true);
        btnClose.setElevation(15);
        
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(52, 52);
        closeParams.gravity = Gravity.TOP | Gravity.END;
        closeParams.setMargins(0, 6, 6, 0);
        btnClose.setLayoutParams(closeParams);
        
        btnClose.setOnClickListener(v -> {
            stopAutomation();
            hideVisuals();
            savePositions();
            if (floatingView != null && windowManager != null) {
                try { windowManager.removeView(floatingView); } catch (Exception e) {}
            }
            stopSelf();
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
                        WindowManager.LayoutParams params = (WindowManager.LayoutParams) floatingView.getLayoutParams();
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
        params.y = 40;   // 0,5cm höher
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
            updateStatus("🟡 Swipe: Pfeil ziehen");
            showSwipeVisual();
            activeVisual = swipeVisual;
        });
        
        btnRefillPlace.setOnClickListener(v -> {
            if (currentMode == Mode.REFILL_PLACE) {
                currentMode = Mode.NONE;
                hideVisuals();
                updateStatus("● Refill-Modus beendet");
                return;
            }
            currentMode = Mode.REFILL_PLACE;
            updateStatus("🟡 Refill: Kreis ziehen");
            showRefillVisual();
            activeVisual = refillVisual;
        });
        
        btnSwipeNow.setOnClickListener(v -> {
            if (swipePlaced) {
                updateStatus("🔄 Swipe...");
                performSwipeGesture();
            }
        });
        
        btnRefillNow.setOnClickListener(v -> {
            if (refillPlaced) {
                updateStatus("🔄 Refill...");
                clickRefillButton();
            }
        });
        
        btnStopAuto.setOnClickListener(v -> {
            if (isRunning) {
                stopAutomation();
            }
        });
        
        btnStartAuto.setOnClickListener(v -> {
            if (isRunning) {
                Toast.makeText(this, "⚠️ Läuft bereits", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!swipePlaced) {
                Toast.makeText(this, "⚠️ Swipe platzieren!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!refillPlaced) {
                Toast.makeText(this, "⚠️ Refill platzieren!", Toast.LENGTH_SHORT).show();
                return;
            }
            startAutomation();
        });
    }
    
    // ============ VISUELLE HILFEN ============
    
    private void createVisualHelpers() {
        swipeVisual = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth(), h = getHeight();
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
    
    private void showSwipeVisual() { addVisual(swipeVisual, 100, 250); dragOffsetX = 50; dragOffsetY = 10; }
    private void showRefillVisual() { addVisual(refillVisual, 100, 100); dragOffsetX = 50; dragOffsetY = 50; }
    
    private void addVisual(View visual, int width, int height) {
        if (visual == swipeVisual) { removeVisual(refillVisual); }
        else if (visual == refillVisual) { removeVisual(swipeVisual); }
        else { hideVisuals(); }
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = screenWidth / 2 - width / 2;
        params.y = screenHeight / 2 - height / 2;
        
        visual.setElevation(1000);
        visual.setLayoutParams(params);
        windowManager.addView(visual, params);
        
        visual.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getRawX(); lastY = event.getRawY();
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
                    if (currentMode != Mode.NONE) {
                        switch (currentMode) {
                            case SWIPE_PLACE:
                                swipeStart.set(params.x + 60, params.y + 10);
                                swipeEnd.set(params.x + 60, params.y + 250);
                                swipePlaced = true;
                                currentMode = Mode.NONE;
                                activeVisual = null;
                                hideVisuals();
                                savePositions();
                                updateStatus("● Swipe gespeichert");
                                break;
                            case REFILL_PLACE:
                                refillButton.set(params.x + 50, params.y + 50);
                                refillPlaced = true;
                                currentMode = Mode.NONE;
                                activeVisual = null;
                                hideVisuals();
                                savePositions();
                                updateStatus("● Refill gespeichert");
                                break;
                        }
                    }
                    return true;
            }
            return false;
        });
    }
    
    private void hideVisuals() { removeVisual(swipeVisual); removeVisual(refillVisual); activeVisual = null; }
    private void removeVisual(View visual) { if (visual != null && visual.getParent() != null) { try { windowManager.removeView(visual); } catch (Exception e) {} } }
    
    private void updateStatus(String text) { tvStatus.setText(text); }
    private void updateCoordinates(int x, int y) { tvCoordinates.setText("(" + x + ", " + y + ")"); }
    
    private void updateLearningStatus() {
        if (tvLearning == null) return;
        String status = "🔄 " + totalSwipes + "x";
        status += " | 📊 " + df.format(currentDataValue) + " GB";
        status += " | ⚡ " + df.format(consumptionRate) + " GB/min";
        status += " | ⏱ " + (currentWaitTime/1000) + "s";
        tvLearning.setText(status);
    }
    
    private void performSimulationCycle() {
        if (!isRunning) return;
        
        double timeSinceLast = currentWaitTime / 60000.0;
        double decrease = consumptionRate * timeSinceLast;
        currentDataValue = Math.max(0.05, currentDataValue - decrease);
        
        if (currentDataValue <= REFILL_THRESHOLD) {
            triggerRefill();
            return;
        }
        
        currentWaitTime = calculateHumanWaitTime();
        
        updateLearningStatus();
        long minutes = currentWaitTime / 60000;
        updateStatus("⏱ Warte ca. " + minutes + " Minuten bis Refill");
        
        handler.postDelayed(() -> {
            if (isRunning) {
                performSwipeGesture();
                handler.postDelayed(() -> {
                    performSimulationCycle();
                }, 2000);
            }
        }, currentWaitTime);
    }
    
    private long calculateHumanWaitTime() {
        long minWait = WAIT_RANGES[currentModeIndex][0];
        long maxWait = WAIT_RANGES[currentModeIndex][1];
        long waitTime = minWait + (long)(random.nextDouble() * (maxWait - minWait));
        waitTime += (long)((random.nextDouble() - 0.5) * 30000);
        return Math.max(MIN_WAIT_TIME, Math.min(MAX_WAIT_TIME, waitTime));
    }
    
    private void performSwipeGesture() {
        if (!swipePlaced) return;
        totalSwipes++;
        updateStatus("🔄 Swipe #" + totalSwipes);
        
        int randomOffsetX = (int)((Math.random() - 0.5) * 40);
        int randomOffsetY = (int)((Math.random() - 0.5) * 40);
        long randomDuration = 400 + (long)(Math.random() * 400);
        long randomDelay = (long)(Math.random() * 500);
        
        int startX = swipeStart.x + randomOffsetX;
        int startY = swipeStart.y + randomOffsetY;
        int endX = swipeEnd.x + randomOffsetX;
        int endY = swipeEnd.y + randomOffsetY;
        
        Path path = new Path();
        path.moveTo(startX, startY);
        path.quadTo(
            (startX + endX) / 2 + (int)((Math.random() - 0.5) * 100),
            (startY + endY) / 2 + (int)((Math.random() - 0.5) * 50),
            endX,
            endY
        );
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, randomDuration));
        
        handler.postDelayed(() -> {
            dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    updateStatus("✅ Swipe #" + totalSwipes + " (menschlich)");
                }
            }, null);
        }, randomDelay);
    }
    
    private void triggerRefill() {
        updateStatus("🔴 REFILL! (" + df.format(currentDataValue) + " GB <= " + REFILL_THRESHOLD + " GB)");
        Toast.makeText(this, 
            "🔴 REFILL!\n" +
            df.format(currentDataValue) + " GB <= " + REFILL_THRESHOLD + " GB\n" +
            "🔄 " + totalSwipes + " Swipes", 
            Toast.LENGTH_LONG).show();
        
        handler.postDelayed(() -> {
            if (isRunning) {
                clickRefillButton();
                handler.postDelayed(() -> {
                    if (isRunning) {
                        currentDataValue = SIMULATED_START_DATA;
                        cycleCount++;
                        updateStatus("📊 Neuer Zyklus #" + cycleCount);
                        performSimulationCycle();
                    }
                }, 3000);
            }
        }, 1000);
    }
    
    private void clickRefillButton() {
        if (!refillPlaced) return;
        updateStatus("🔄 Refill...");
        
        int randomOffsetX = (int)((Math.random() - 0.5) * 30);
        int randomOffsetY = (int)((Math.random() - 0.5) * 30);
        long clickDuration = 50 + (long)(Math.random() * 150);
        long randomDelay = 100 + (long)(Math.random() * 400);
        
        int clickX = refillButton.x + randomOffsetX;
        int clickY = refillButton.y + randomOffsetY;
        
        Path clickPath = new Path();
        clickPath.moveTo(clickX, clickY);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, clickDuration));
        
        handler.postDelayed(() -> {
            dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    updateStatus("✅ REFILL! (menschlich)");
                    Toast.makeText(OverlayService.this, "✅ Refill durchgeführt!", Toast.LENGTH_SHORT).show();
                }
            }, null);
        }, randomDelay);
    }
    
    private void startAutomation() {
        isRunning = true;
        btnStartAuto.setText("▶");
        btnStartAuto.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6D00")));
        updateStatus("🟢 Automatik läuft");
        Toast.makeText(this, 
            "🚀 Automatik gestartet!\n" +
            "📊 " + CONSUMPTION_LABELS[currentModeIndex] + "\n" +
            "⏱ Refill ca. alle " + ((WAIT_RANGES[currentModeIndex][0] + WAIT_RANGES[currentModeIndex][1]) / 2 / 60000) + " Minuten", 
            Toast.LENGTH_LONG).show();
        
        currentDataValue = SIMULATED_START_DATA;
        cycleCount = 0;
        totalSwipes = 0;
        
        handler.postDelayed(() -> {
            if (isRunning) {
                performSwipeGesture();
                handler.postDelayed(() -> {
                    performSimulationCycle();
                }, 2000);
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
                "📊 Statistik:\n" +
                "🔄 Swipes: " + totalSwipes + "\n" +
                "📊 Zyklen: " + cycleCount, 
                Toast.LENGTH_LONG).show();
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        savePositions();
        hideVisuals();
        if (floatingView != null && windowManager != null) {
            try { windowManager.removeView(floatingView); } catch (Exception e) {}
        }
        handler.removeCallbacksAndMessages(null);
    }
}
