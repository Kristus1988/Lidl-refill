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

import java.util.Random;

public class OverlayService extends AccessibilityService {
    private static final String TAG = "LidlRefill";
    
    private WindowManager windowManager;
    private FrameLayout floatingView;
    private TextView tvStatus, tvCountdown, tvCycle;
    private Spinner spinnerConsumption;
    private Button btnSwipePlace, btnRefillPlace;
    private Button btnSwipeTest, btnRefillTest, btnStopAuto, btnStartAuto;
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
    
    // ============ VERBRAUCHS-OPTIONEN ============
    private static final double[] CONSUMPTION_OPTIONS = {0.03, 0.05, 0.10};
    private static final String[] CONSUMPTION_LABELS = {
        "📱 Surfen (18-22 Min)",
        "📺 FullHD (11-14 Min)",
        "🎬 4K (6-9 Min)"
    };
    private int currentModeIndex = 0;
    
    // ============ ZEITEN ============
    private static final long MIN_WAIT_AFTER_SWIPE = 6000;   // 6 Sekunden Minimum
    private static final long MAX_WAIT_AFTER_SWIPE = 7000;   // 7 Sekunden Maximum
    private static final long MIN_WAIT_AFTER_REFILL = 6000;  // 6 Sekunden Minimum
    private static final long MAX_WAIT_AFTER_REFILL = 7000;  // 7 Sekunden Maximum
    private static final long MIN_WAIT_TIME = 60000;
    private static final long MAX_WAIT_TIME = 1800000;
    
    // ============ OPTIMIERTE WARTEZEITEN ============
    private static final long[][] WAIT_RANGES = {
        {1080000, 1320000},  // Surfen: 18-22 Minuten (4 Min Puffer)
        {660000, 840000},    // FullHD: 11-14 Minuten (3 Min Puffer)
        {360000, 540000}     // 4K: 6-9 Minuten (3 Min Puffer)
    };
    
    private int cycleCount = 0;
    private int totalSwipes = 0;
    private long currentWaitTime = 900000;
    private long countdownStartTime = 0;
    private boolean isWaiting = false;
    
    private enum Phase { IDLE, SWIPE_1, WAIT_AFTER_SWIPE_1, REFILL, WAIT_AFTER_REFILL, SWIPE_2, COUNTDOWN }
    private Phase currentPhase = Phase.IDLE;
    
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
        
        loadPositions();
        
        int savedIndex = prefs.getInt("consumption_index", 0);
        currentModeIndex = savedIndex;
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.packageNames = null;
        setServiceInfo(info);
        
        createOverlay();
        createVisualHelpers();
        
        updateStatus("● Bereit");
        updateCountdown("⏱ Warte: --:--");
        updateCycle();
    }
    
    private void loadPositions() {
        swipeStart.x = prefs.getInt(PREF_SWIPE_START_X, screenWidth / 2);
        swipeStart.y = prefs.getInt(PREF_SWIPE_START_Y, 100);
        swipeEnd.x = prefs.getInt(PREF_SWIPE_END_X, screenWidth / 2);
        swipeEnd.y = prefs.getInt(PREF_SWIPE_END_Y, screenHeight - 100);
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
        tvCountdown = controlView.findViewById(R.id.tvCountdown);
        tvCycle = controlView.findViewById(R.id.tvCycle);
        spinnerConsumption = controlView.findViewById(R.id.spinnerConsumption);
        btnSwipePlace = controlView.findViewById(R.id.btnSwipePlace);
        btnRefillPlace = controlView.findViewById(R.id.btnRefillPlace);
        btnSwipeTest = controlView.findViewById(R.id.btnSwipeTest);
        btnRefillTest = controlView.findViewById(R.id.btnRefillTest);
        btnStopAuto = controlView.findViewById(R.id.btnStopAuto);
        btnStartAuto = controlView.findViewById(R.id.btnStartAuto);
        btnClose = controlView.findViewById(R.id.btnClose);
        
        // ============ SPINNER ============
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
                text.setTextSize(12);
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
                prefs.edit().putInt("consumption_index", position).apply();
                Toast.makeText(OverlayService.this, 
                    "📊 " + CONSUMPTION_LABELS[position], 
                    Toast.LENGTH_SHORT).show();
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        // ============ BUTTONS ============
        btnSwipePlace.setOnClickListener(v -> {
            if (currentMode == Mode.SWIPE_PLACE) {
                currentMode = Mode.NONE;
                hideVisuals();
                updateStatus("● Swipe-Modus beendet");
                return;
            }
            currentMode = Mode.SWIPE_PLACE;
            updateStatus("🟡 S: Pfeil auf Swipe-Bereich ziehen");
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
            updateStatus("🟡 R: Kreis auf Refill-Button ziehen");
            showRefillVisual();
            activeVisual = refillVisual;
        });
        
        btnSwipeTest.setOnClickListener(v -> {
            if (!swipePlaced) {
                Toast.makeText(this, "❌ Swipe nicht platziert! Bitte zuerst 'S' positionieren.", Toast.LENGTH_LONG).show();
                return;
            }
            updateStatus("🔄 Swipe Test...");
            performSwipeGesture();
        });
        
        btnRefillTest.setOnClickListener(v -> {
            if (!refillPlaced) {
                Toast.makeText(this, "❌ Refill nicht platziert! Bitte zuerst 'R' positionieren.", Toast.LENGTH_LONG).show();
                return;
            }
            updateStatus("🔄 Refill Test...");
            clickRefillButton();
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
                Toast.makeText(this, "⚠️ Swipe nicht platziert!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!refillPlaced) {
                Toast.makeText(this, "⚠️ Refill nicht platziert!", Toast.LENGTH_SHORT).show();
                return;
            }
            startAutomation();
        });
        
        btnClose.setOnClickListener(v -> {
            stopAutomation();
            hideVisuals();
            savePositions();
            if (floatingView != null && windowManager != null) {
                try { windowManager.removeView(floatingView); } catch (Exception e) {}
            }
            stopSelf();
        });
        
        // Overlay verschiebbar
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
        params.y = 40;
        params.alpha = 0.92f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        floatingView = mainContainer;
        floatingView.setElevation(999);
        windowManager.addView(floatingView, params);
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
                canvas.drawText("R", size/2, size/2 + size * 0.15f, textPaint);
            }
        };
        
        hideVisuals();
    }
    
    private void showSwipeVisual() { addVisual(swipeVisual, 100, 250); dragOffsetX = 50; dragOffsetY = 10; }
    private void showRefillVisual() { addVisual(refillVisual, 80, 80); dragOffsetX = 40; dragOffsetY = 40; }
    
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
                    return true;
                case MotionEvent.ACTION_UP:
                    if (currentMode != Mode.NONE) {
                        switch (currentMode) {
                            case SWIPE_PLACE:
                                swipeStart.set(params.x + 50, params.y + 10);
                                swipeEnd.set(params.x + 50, params.y + 250);
                                swipePlaced = true;
                                currentMode = Mode.NONE;
                                activeVisual = null;
                                hideVisuals();
                                savePositions();
                                updateStatus("● Swipe gespeichert");
                                Toast.makeText(OverlayService.this, "✅ Swipe platziert!", Toast.LENGTH_SHORT).show();
                                break;
                            case REFILL_PLACE:
                                refillButton.set(params.x + 40, params.y + 40);
                                refillPlaced = true;
                                currentMode = Mode.NONE;
                                activeVisual = null;
                                hideVisuals();
                                savePositions();
                                updateStatus("● Refill gespeichert");
                                Toast.makeText(OverlayService.this, "✅ Refill platziert!", Toast.LENGTH_SHORT).show();
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
    private void updateCountdown(String text) { tvCountdown.setText(text); }
    private void updateCycle() { tvCycle.setText("🔄 " + cycleCount + " Zyklen | ⬇ " + totalSwipes); }
    
    // ============ ZUFÄLLIGE WARTEZEITEN ============
    private long randomWaitAfterSwipe() {
        return MIN_WAIT_AFTER_SWIPE + (long)(random.nextDouble() * (MAX_WAIT_AFTER_SWIPE - MIN_WAIT_AFTER_SWIPE));
    }
    
    private long randomWaitAfterRefill() {
        return MIN_WAIT_AFTER_REFILL + (long)(random.nextDouble() * (MAX_WAIT_AFTER_REFILL - MIN_WAIT_AFTER_REFILL));
    }
    
    // ============ GESTEN ============
    
    private void performSwipeGesture() {
        if (!swipePlaced) {
            Toast.makeText(this, "❌ Swipe nicht platziert!", Toast.LENGTH_SHORT).show();
            return;
        }
        
        totalSwipes++;
        
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
                    updateCycle();
                    
                    if (!isRunning) return;
                    
                    if (currentPhase == Phase.SWIPE_2) {
                        currentPhase = Phase.COUNTDOWN;
                        currentWaitTime = calculateHumanWaitTime();
                        long minutes = currentWaitTime / 60000;
                        updateStatus("⏱ Warte " + minutes + " Min");
                        startCountdown(currentWaitTime);
                        return;
                    }
                    
                    if (currentPhase == Phase.SWIPE_1) {
                        currentPhase = Phase.WAIT_AFTER_SWIPE_1;
                        long waitTime = randomWaitAfterSwipe();
                        long seconds = waitTime / 1000;
                        updateStatus("⏳ Warte " + seconds + "s...");
                        handler.postDelayed(() -> {
                            if (isRunning) {
                                currentPhase = Phase.REFILL;
                                updateStatus("🔴 Refill wird ausgeführt...");
                                clickRefillButton();
                            }
                        }, waitTime);
                    }
                }
            }, null);
        }, randomDelay);
    }
    
    private void clickRefillButton() {
        if (!refillPlaced) {
            Toast.makeText(this, "❌ Refill nicht platziert!", Toast.LENGTH_SHORT).show();
            return;
        }
        
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
                    updateStatus("✅ Refill geklickt! (menschlich)");
                    Toast.makeText(OverlayService.this, "✅ Refill-Button geklickt!", Toast.LENGTH_SHORT).show();
                    
                    if (!isRunning) return;
                    
                    currentPhase = Phase.WAIT_AFTER_REFILL;
                    long waitTime = randomWaitAfterRefill();
                    long seconds = waitTime / 1000;
                    updateStatus("⏳ Warte " + seconds + "s...");
                    handler.postDelayed(() -> {
                        if (isRunning) {
                            currentPhase = Phase.SWIPE_2;
                            updateStatus("🔄 Swipe...");
                            performSwipeGesture();
                        }
                    }, waitTime);
                }
            }, null);
        }, randomDelay);
    }
    
    // ============ COUNTDOWN ============
    
    private void startCountdown(long waitTime) {
        isWaiting = true;
        countdownStartTime = System.currentTimeMillis();
        currentWaitTime = waitTime;
        
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isWaiting || !isRunning) {
                    isWaiting = false;
                    return;
                }
                
                long elapsed = System.currentTimeMillis() - countdownStartTime;
                long remaining = Math.max(0, currentWaitTime - elapsed);
                
                if (remaining <= 0) {
                    updateCountdown("⏱ Warte: 00:00");
                    isWaiting = false;
                    if (isRunning) {
                        cycleCount++;
                        updateCycle();
                        currentPhase = Phase.SWIPE_1;
                        updateStatus("🔄 Neuer Zyklus " + cycleCount + " - Swipe...");
                        handler.postDelayed(() -> {
                            if (isRunning) {
                                performSwipeGesture();
                            }
                        }, 2000);
                    }
                    return;
                }
                
                long seconds = remaining / 1000;
                long minutes = seconds / 60;
                seconds = seconds % 60;
                
                updateCountdown(String.format("⏱ Warte: %02d:%02d", minutes, seconds));
                
                handler.postDelayed(this, 1000);
            }
        });
    }
    
    // ============ WARTEZEIT ============
    private long calculateHumanWaitTime() {
        long minWait = WAIT_RANGES[currentModeIndex][0];
        long maxWait = WAIT_RANGES[currentModeIndex][1];
        long waitTime = minWait + (long)(random.nextDouble() * (maxWait - minWait));
        waitTime += (long)((random.nextDouble() - 0.5) * 30000);
        return Math.max(MIN_WAIT_TIME, Math.min(MAX_WAIT_TIME, waitTime));
    }
    
    // ============ AUTOMATIK START ============
    
    private void startAutomation() {
        isRunning = true;
        cycleCount = 0;
        totalSwipes = 0;
        btnStartAuto.setText("▶ Läuft");
        btnStartAuto.setEnabled(false);
        btnStopAuto.setEnabled(true);
        updateStatus("🟢 Automatik läuft");
        updateCycle();
        
        currentPhase = Phase.SWIPE_1;
        handler.postDelayed(() -> {
            if (isRunning) {
                updateStatus("🔄 Swipe...");
                performSwipeGesture();
            }
        }, 2000);
    }
    
    // ============ STOP ============
    
    private void stopAutomation() {
        isRunning = false;
        isWaiting = false;
        currentPhase = Phase.IDLE;
        btnStartAuto.setText("▶ Start");
        btnStartAuto.setEnabled(true);
        btnStopAuto.setEnabled(false);
        updateStatus("● Gestoppt");
        updateCountdown("⏱ Warte: --:--");
        handler.removeCallbacksAndMessages(null);
        
        if (cycleCount > 0) {
            Toast.makeText(this, 
                "📊 " + cycleCount + " Zyklen\n⬇ " + totalSwipes + " Swipes", 
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
