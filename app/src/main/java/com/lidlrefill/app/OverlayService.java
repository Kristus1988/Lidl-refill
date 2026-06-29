package com.lidlrefill.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;
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

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OverlayService extends AccessibilityService {
    private static final String TAG = "LidlRefill";
    
    private WindowManager windowManager;
    private FrameLayout floatingView;
    private TextView tvStatus, tvCoordinates, tvLearning;
    private Button btnSwipePlace, btnOcrPlace, btnRefillPlace;
    private Button btnSwipeNow, btnRefillNow, btnOcrNow, btnStopAuto, btnStartAuto;
    private Button btnClose;
    
    private TextRecognizer textRecognizer;
    private static MediaProjection sMediaProjection = null;
    
    public static void setMediaProjection(MediaProjection projection) {
        sMediaProjection = projection;
        Log.d(TAG, "MediaProjection gesetzt");
    }
    
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth, screenHeight;
    private int screenDensity;
    private boolean isScreenshotReady = false;
    
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
    
    private Point swipeStart = new Point(0, 0);
    private Point swipeEnd = new Point(0, 0);
    private boolean swipePlaced = false;
    private Rect ocrRect = new Rect(100, 100, 320, 280);
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
    private boolean isRunning = false;
    private boolean isOverlayDragging = false;
    private float overlayDragX, overlayDragY;
    
    private List<Double> consumptionHistory = new ArrayList<>();
    private List<Long> actualWaitTimes = new ArrayList<>();
    private double averageConsumptionRate = 0;
    private double lastDataValue = 0;
    private long lastDataTime = 0;
    private int cycleCount = 0;
    
    private static final double REFILL_THRESHOLD = 0.30;
    private static final double BUFFER_SAFETY = 0.10;
    private static final long MIN_WAIT_TIME = 120000;
    private static final long MAX_WAIT_TIME = 1800000;
    private static final long INITIAL_WAIT_TIME = 600000;
    private static final long SWIPE_DURATION = 3000;
    private static final long OCR_DURATION = 1500;
    
    private static final boolean USE_SIMULATION_MODE = true;
    private static final double SIMULATED_CONSUMPTION_RATE = 0.05;
    private static final double SIMULATED_START_DATA = 0.90;
    
    private long currentWaitTime = INITIAL_WAIT_TIME;
    private boolean isFirstMeasurement = true;
    private boolean isSecondMeasurement = true;
    private int totalSwipes = 0;
    private long totalWaitTime = 0;
    private DecimalFormat df = new DecimalFormat("0.000");
    
    private int ocrRetryCount = 0;
    private static final int MAX_OCR_RETRIES = 3;
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override
    public void onInterrupt() {}
    
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        screenWidth = size.x;
        screenHeight = size.y;
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        screenDensity = metrics.densityDpi;
        
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
        
        if (sMediaProjection != null) {
            setupVirtualDisplay(sMediaProjection);
        }
        
        updateStatus(ocrPlaced ? "● Bereit - OCR platziert" : "● Simulations-Modus (kein OCR)");
        updateLearningStatus();
    }
    
    // ============ MEDIAPROJECTION ============
    
    private void setupVirtualDisplay(MediaProjection projection) {
        if (projection == null) {
            Log.w(TAG, "MediaProjection ist null!");
            return;
        }
        try {
            if (imageReader != null) imageReader.close();
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
            if (virtualDisplay != null) virtualDisplay.release();
            virtualDisplay = projection.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null, null
            );
            isScreenshotReady = true;
            Log.d(TAG, "VirtualDisplay erfolgreich erstellt");
            Toast.makeText(this, "✅ Screen-Capture aktiv!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "VirtualDisplay Fehler: " + e.getMessage());
        }
    }
    
    private Bitmap takeScreenshot() {
        if (!isScreenshotReady || imageReader == null) {
            Log.w(TAG, "Screenshot nicht bereit");
            return null;
        }
        try {
            Image image = imageReader.acquireLatestImage();
            if (image == null) return null;
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;
            Bitmap bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);
            image.close();
            return Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
        } catch (Exception e) {
            Log.e(TAG, "Screenshot Fehler: " + e.getMessage());
            return null;
        }
    }
    
    private void requestScreenCaptureIfNeeded() {
        if (!isScreenshotReady) {
            updateStatus("📷 Screen-Capture wird benötigt...");
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(this, 
                "⚠️ Screen-Capture benötigt!\n" +
                "Bitte in der App 'JETZT STARTEN' erlauben.\n" +
                "Danach zurück zur Lidl-App.", 
                Toast.LENGTH_LONG).show();
        }
    }
    
    // ============ POSITIONEN ============
    
    private void loadPositions() {
        swipeStart.x = prefs.getInt(PREF_SWIPE_START_X, screenWidth / 2);
        swipeStart.y = prefs.getInt(PREF_SWIPE_START_Y, 100);
        swipeEnd.x = prefs.getInt(PREF_SWIPE_END_X, screenWidth / 2);
        swipeEnd.y = prefs.getInt(PREF_SWIPE_END_Y, screenHeight - 100);
        swipePlaced = prefs.getBoolean(PREF_SWIPE_PLACED, true);
        ocrRect.left = prefs.getInt(PREF_OCR_LEFT, 100);
        ocrRect.top = prefs.getInt(PREF_OCR_TOP, 100);
        ocrRect.right = prefs.getInt(PREF_OCR_RIGHT, 320);
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
    
    // ============ OVERLAY ============
    
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
        btnOcrNow.setOnClickListener(v -> {
            if (!ocrPlaced) {
                Toast.makeText(this, "❌ OCR nicht platziert! Nutze Simulations-Modus.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isScreenshotReady) {
                requestScreenCaptureIfNeeded();
                return;
            }
            updateStatus("📷 OCR...");
            performOcrNow();
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
            
            if (ocrPlaced && !isScreenshotReady) {
                requestScreenCaptureIfNeeded();
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
        ocrVisual = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth(), h = getHeight();
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
                textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("📷 OCR", w/2, h/2 + 12, textPaint);
                Paint sizePaint = new Paint();
                sizePaint.setColor(Color.argb(200, 255, 255, 255));
                sizePaint.setTextSize(16);
                sizePaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(w + " x " + h, w/2, h - 15, sizePaint);
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
    private void showOcrVisual() { addVisual(ocrVisual, 220, 180); dragOffsetX = 110; dragOffsetY = 90; }
    private void showRefillVisual() { addVisual(refillVisual, 100, 100); dragOffsetX = 50; dragOffsetY = 50; }
    
    // ============ KORRIGIERTE addVisual METHODE ============
    private void addVisual(View visual, int width, int height) {
        if (visual == swipeVisual) { removeVisual(ocrVisual); removeVisual(refillVisual); }
        else if (visual == ocrVisual) { removeVisual(swipeVisual); removeVisual(refillVisual); }
        else if (visual == refillVisual) { removeVisual(swipeVisual); removeVisual(ocrVisual); }
        else { hideVisuals(); }
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = (visual == ocrVisual && ocrPlaced) ? ocrRect.left : screenWidth / 2 - width / 2;
        params.y = (visual == ocrVisual && ocrPlaced) ? ocrRect.top : screenHeight / 2 - height / 2;
        
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
                    // Nur speichern wenn ein Modus aktiv ist
                    if (currentMode != Mode.NONE) {
                        // Direkt die Position je nach Modus speichern
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
                            case OCR_PLACE:
                                ocrRect.left = params.x;
                                ocrRect.top = params.y;
                                ocrRect.right = params.x + 220;
                                ocrRect.bottom = params.y + 180;
                                ocrPlaced = true;
                                currentMode = Mode.NONE;
                                activeVisual = null;
                                hideVisuals();
                                savePositions();
                                updateStatus("● OCR bei (" + params.x + ", " + params.y + ")");
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
    
    private void hideVisuals() { removeVisual(swipeVisual); removeVisual(ocrVisual); removeVisual(refillVisual); activeVisual = null; }
    private void removeVisual(View visual) { if (visual != null && visual.getParent() != null) { try { windowManager.removeView(visual); } catch (Exception e) {} } }
    
    private void updateStatus(String text) { tvStatus.setText(text); }
    private void updateCoordinates(int x, int y) { tvCoordinates.setText("(" + x + ", " + y + ")"); }
    
    private void updateLearningStatus() {
        if (tvLearning == null) return;
        String status = "🔄 " + totalSwipes + "x";
        if (cycleCount > 0) {
            status += " | ⚡ " + df.format(averageConsumptionRate) + " GB/min";
            status += " | 📊 " + df.format(lastDataValue) + " GB";
            status += " | ⏱ " + (currentWaitTime/1000) + "s";
        }
        status += ocrPlaced ? " | OCR✅" : " | 🔮SIM";
        status += isScreenshotReady ? " | 📷✅" : " | 📷⏳";
        tvLearning.setText(status);
    }
    
    // ============ SIMULATIONS-MODUS ============
    
    private double getSimulatedData() {
        if (cycleCount == 0) {
            return SIMULATED_START_DATA;
        }
        double timeSinceLast = (System.currentTimeMillis() - lastDataTime) / 60000.0;
        double decrease = SIMULATED_CONSUMPTION_RATE * timeSinceLast;
        double newValue = lastDataValue - decrease;
        return Math.max(0.05, newValue);
    }
    
    private void performSimulatedOcr() {
        if (!isRunning) return;
        
        double currentData = getSimulatedData();
        long currentTime = System.currentTimeMillis();
        
        if (isFirstMeasurement) {
            lastDataValue = currentData;
            lastDataTime = currentTime;
            isFirstMeasurement = false;
            isSecondMeasurement = true;
            updateStatus("📊 Simulation: " + df.format(currentData) + " GB (Start)");
            handler.postDelayed(() -> {
                if (isRunning) performSwipeGesture();
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
                long waitTime = calculateWaitTime(currentData, consumptionRate);
                learnFromCycle(currentData, consumptionRate, waitTime);
                updateStatus("📊 Simulation: " + df.format(currentData) + " GB | ⚡ " + df.format(consumptionRate) + " GB/min");
                if (currentData <= REFILL_THRESHOLD) {
                    triggerRefill(currentData);
                    return;
                }
                handler.postDelayed(() -> {
                    if (isRunning) performSwipeGesture();
                }, waitTime);
            } else {
                updateStatus("⚠️ Kein Verbrauch (Simulation)");
                handler.postDelayed(() -> {
                    if (isRunning) performSwipeGesture();
                }, Math.min(MAX_WAIT_TIME, MIN_WAIT_TIME * 2));
            }
            return;
        }
        
        double timeDiffMinutes = (currentTime - lastDataTime) / 60000.0;
        double dataDiff = lastDataValue - currentData;
        double consumptionRate = dataDiff / timeDiffMinutes;
        
        if (consumptionRate <= 0.001) {
            updateStatus("⚠️ Kein Verbrauch (Simulation)");
            handler.postDelayed(() -> {
                if (isRunning) performSwipeGesture();
            }, Math.min(MAX_WAIT_TIME, (long)(currentWaitTime * 1.5)));
            return;
        }
        
        long waitTime = calculateWaitTime(currentData, consumptionRate);
        learnFromCycle(currentData, consumptionRate, waitTime);
        if (currentData <= REFILL_THRESHOLD) {
            triggerRefill(currentData);
            return;
        }
        
        long waitSeconds = waitTime / 1000;
        long waitMinutes = waitSeconds / 60;
        String timeString = waitMinutes > 0 ? waitMinutes + "m " + (waitSeconds % 60) + "s" : waitSeconds + "s";
        updateStatus("⏱ Simulation: " + timeString + " bis " + df.format(REFILL_THRESHOLD) + " GB");
        
        handler.postDelayed(() -> {
            if (isRunning) performSwipeGesture();
        }, waitTime);
        lastDataValue = currentData;
        lastDataTime = currentTime;
    }
    
    // ============ OCR ============
    
    private Bitmap captureOcrArea() {
        Bitmap full = takeScreenshot();
        if (full == null) return null;
        try {
            int left = Math.max(0, ocrRect.left);
            int top = Math.max(0, ocrRect.top);
            int right = Math.min(screenWidth, ocrRect.right);
            int bottom = Math.min(screenHeight, ocrRect.bottom);
            int width = right - left;
            int height = bottom - top;
            if (width <= 0 || height <= 0) return null;
            Bitmap cropped = Bitmap.createBitmap(full, left, top, width, height);
            Bitmap scaled = Bitmap.createScaledBitmap(cropped, width * 2, height * 2, true);
            cropped.recycle();
            return scaled;
        } catch (Exception e) { return null; } finally { if (full != null) full.recycle(); }
    }
    
    private double parseDataFromText(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            String[] patterns = {
                "(\\d+[\\.\\,]?\\d*)\\s*(GB|G)",
                "(\\d+[\\.\\,]?\\d*)\\s*(MB|M)",
                "(\\d+[\\.\\,]?\\d*)\\s*GB",
                "(\\d+[\\.\\,]?\\d*)\\s*MB",
                "(\\d+[\\.\\,]?\\d*)\\s*G",
                "(\\d+[\\.\\,]?\\d*)\\s*M"
            };
            for (String patternStr : patterns) {
                Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    String valueStr = matcher.group(1).replace(",", ".");
                    double value = Double.parseDouble(valueStr);
                    String unit = matcher.group(2) != null ? matcher.group(2).toLowerCase() : "";
                    if (unit.contains("m")) value = value / 1024;
                    if (value > 0 && value < 10) return value;
                }
            }
        } catch (Exception e) {}
        return 0;
    }
    
    private void performRealOcr() {
        if (!ocrPlaced || !isRunning) {
            performSimulatedOcr();
            return;
        }
        
        if (!isScreenshotReady) {
            requestScreenCaptureIfNeeded();
            return;
        }
        
        Bitmap ocrBitmap = captureOcrArea();
        if (ocrBitmap == null) {
            updateStatus("⚠️ Screenshot fehlgeschlagen - Simulation");
            performSimulatedOcr();
            return;
        }
        InputImage image = InputImage.fromBitmap(ocrBitmap, 0);
        textRecognizer.process(image)
            .addOnSuccessListener(visionText -> {
                String resultText = visionText.getText();
                double dataValue = parseDataFromText(resultText);
                ocrBitmap.recycle();
                ocrRetryCount = 0;
                if (dataValue > 0) {
                    updateStatus("📊 " + resultText);
                    processOcrResult(dataValue, resultText);
                } else {
                    updateStatus("⚠️ Kein GB-Wert - Simulation");
                    performSimulatedOcr();
                }
            })
            .addOnFailureListener(e -> {
                ocrBitmap.recycle();
                updateStatus("❌ OCR Fehler - Simulation");
                Log.e(TAG, "OCR Fehler: " + e.getMessage());
                performSimulatedOcr();
            });
    }
    
    // ============ BERECHNUNGEN ============
    
    private double calculateStdDev() {
        if (consumptionHistory.size() < 2) return 0;
        double mean = calculateAverageConsumption();
        double sum = 0;
        for (double val : consumptionHistory) sum += Math.pow(val - mean, 2);
        return Math.sqrt(sum / consumptionHistory.size());
    }
    
    private long calculateWaitTime(double currentData, double consumptionRate) {
        if (consumptionRate <= 0.001) return MIN_WAIT_TIME * 2;
        if (consumptionRate < 0.002) return Math.min(MAX_WAIT_TIME, 900000);
        double remainingUntilRefill = currentData - REFILL_THRESHOLD - BUFFER_SAFETY;
        if (remainingUntilRefill <= 0) return 0;
        long calculatedTime = (long)((remainingUntilRefill / consumptionRate) * 60000);
        calculatedTime = Math.max(MIN_WAIT_TIME, Math.min(MAX_WAIT_TIME, calculatedTime));
        if (!actualWaitTimes.isEmpty()) {
            double avgWait = actualWaitTimes.stream().mapToLong(Long::longValue).average().orElse(calculatedTime);
            calculatedTime = (long)((calculatedTime * 0.5) + (avgWait * 0.5));
        }
        if (consumptionRate < 0.005) calculatedTime = Math.min(MAX_WAIT_TIME, (long)(calculatedTime * 1.2));
        else if (consumptionRate > 0.08) calculatedTime = Math.max(MIN_WAIT_TIME, (long)(calculatedTime * 0.8));
        if (consumptionHistory.size() >= 3) {
            double mean = calculateAverageConsumption();
            double stdDev = calculateStdDev();
            if (stdDev > 0 && Math.abs(consumptionRate - mean) > 2 * stdDev) {
                calculatedTime = Math.max(MIN_WAIT_TIME, Math.min(MAX_WAIT_TIME, (long)((remainingUntilRefill / mean) * 60000)));
            }
        }
        calculatedTime = Math.max(30000, calculatedTime);
        currentWaitTime = calculatedTime;
        return calculatedTime;
    }
    
    private double calculateAverageConsumption() {
        if (consumptionHistory.isEmpty()) return 0;
        double sum = 0;
        for (double val : consumptionHistory) sum += val;
        return sum / consumptionHistory.size();
    }
    
    private void learnFromCycle(double dataValue, double consumptionRate, long waitTime) {
        consumptionHistory.add(consumptionRate);
        actualWaitTimes.add(waitTime);
        cycleCount++;
        averageConsumptionRate = calculateAverageConsumption();
        totalWaitTime += waitTime;
        updateLearningStatus();
    }
    
    // ============ AUSFÜHRUNG ============
    
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
                    if (isRunning) {
                        handler.postDelayed(() -> performRealOcr(), OCR_DURATION + (long)(Math.random() * 300));
                    }
                }
            }, null);
        }, randomDelay);
    }
    
    private void processOcrResult(double currentData, String fullText) {
        long currentTime = System.currentTimeMillis();
        
        if (isFirstMeasurement) {
            lastDataValue = currentData;
            lastDataTime = currentTime;
            isFirstMeasurement = false;
            isSecondMeasurement = true;
            updateStatus("📊 Start: " + df.format(currentData) + " GB");
            handler.postDelayed(() -> {
                if (isRunning) performSwipeGesture();
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
                long waitTime = calculateWaitTime(currentData, consumptionRate);
                learnFromCycle(currentData, consumptionRate, waitTime);
                updateStatus("📊 2. Messung: " + df.format(currentData) + " GB");
                if (currentData <= REFILL_THRESHOLD) {
                    triggerRefill(currentData);
                    return;
                }
                handler.postDelayed(() -> {
                    if (isRunning) performSwipeGesture();
                }, waitTime);
            } else {
                updateStatus("⚠️ Kein Verbrauch");
                handler.postDelayed(() -> {
                    if (isRunning) performSwipeGesture();
                }, Math.min(MAX_WAIT_TIME, MIN_WAIT_TIME * 2));
            }
            return;
        }
        
        double timeDiffMinutes = (currentTime - lastDataTime) / 60000.0;
        double dataDiff = lastDataValue - currentData;
        double consumptionRate = dataDiff / timeDiffMinutes;
        
        if (consumptionRate <= 0.001) {
            updateStatus("⚠️ Kein Verbrauch");
            handler.postDelayed(() -> {
                if (isRunning) performSwipeGesture();
            }, Math.min(MAX_WAIT_TIME, (long)(currentWaitTime * 1.5)));
            return;
        }
        
        long waitTime = calculateWaitTime(currentData, consumptionRate);
        learnFromCycle(currentData, consumptionRate, waitTime);
        if (currentData <= REFILL_THRESHOLD) {
            triggerRefill(currentData);
            return;
        }
        
        long waitSeconds = waitTime / 1000;
        long waitMinutes = waitSeconds / 60;
        String timeString = waitMinutes > 0 ? waitMinutes + "m " + (waitSeconds % 60) + "s" : waitSeconds + "s";
        updateStatus("⏱ " + timeString + " bis " + df.format(REFILL_THRESHOLD) + " GB");
        
        handler.postDelayed(() -> {
            if (isRunning) performSwipeGesture();
        }, waitTime);
        lastDataValue = currentData;
        lastDataTime = currentTime;
    }
    
    private void triggerRefill(double currentData) {
        updateStatus("🔴 REFILL!");
        handler.postDelayed(() -> {
            if (isRunning) {
                clickRefillButton();
                handler.postDelayed(() -> {
                    if (isRunning) {
                        isFirstMeasurement = true;
                        isSecondMeasurement = true;
                        lastDataTime = 0;
                        lastDataValue = 0;
                        currentWaitTime = INITIAL_WAIT_TIME;
                        performSwipeGesture();
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
                }
            }, null);
        }, randomDelay);
    }
    
    private void performOcrNow() {
        if (!ocrPlaced || !isScreenshotReady) {
            if (!isScreenshotReady) {
                updateStatus("⚠️ Screen-Capture nicht bereit!");
                requestScreenCaptureIfNeeded();
            }
            return;
        }
        updateStatus("📷 OCR wird ausgelesen...");
        Bitmap ocrBitmap = captureOcrArea();
        if (ocrBitmap == null) {
            updateStatus("⚠️ Screenshot fehlgeschlagen");
            Toast.makeText(this, "⚠️ Screenshot fehlgeschlagen", Toast.LENGTH_SHORT).show();
            return;
        }
        InputImage image = InputImage.fromBitmap(ocrBitmap, 0);
        textRecognizer.process(image)
            .addOnSuccessListener(visionText -> {
                String resultText = visionText.getText();
                double dataValue = parseDataFromText(resultText);
                ocrBitmap.recycle();
                updateStatus("📊 OCR: " + resultText);
                Toast.makeText(this, "📊 OCR Ergebnis:\n" + resultText + "\n📍 " + ocrRect.left + "," + ocrRect.top + (dataValue > 0 ? "\n📈 Wert: " + df.format(dataValue) + " GB" : "\n⚠️ Kein Wert"), Toast.LENGTH_LONG).show();
            })
            .addOnFailureListener(e -> {
                ocrBitmap.recycle();
                updateStatus("❌ OCR Fehler");
                Toast.makeText(this, "❌ OCR Fehler", Toast.LENGTH_LONG).show();
            });
    }
    
    private void startAutomation() {
        if (ocrPlaced && !isScreenshotReady) {
            Toast.makeText(this, "⚠️ Screen-Capture nicht bereit für OCR!", Toast.LENGTH_LONG).show();
            requestScreenCaptureIfNeeded();
            return;
        }
        
        isRunning = true;
        isFirstMeasurement = true;
        isSecondMeasurement = true;
        btnStartAuto.setText("▶");
        btnStartAuto.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6D00")));
        updateStatus(ocrPlaced ? "🟢 Automatik (OCR)" : "🟢 Automatik (Simulation)");
        Toast.makeText(this, ocrPlaced ? "🚀 Automatik mit OCR gestartet!" : "🚀 Automatik mit Simulation gestartet!", Toast.LENGTH_LONG).show();
        
        lastDataTime = 0;
        lastDataValue = 0;
        currentWaitTime = INITIAL_WAIT_TIME;
        totalSwipes = 0;
        cycleCount = 0;
        consumptionHistory.clear();
        actualWaitTimes.clear();
        
        handler.postDelayed(() -> {
            if (isRunning) performSwipeGesture();
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
                "📊 Zyklen: " + cycleCount + "\n" +
                "⚡ Ø Verbrauch: " + df.format(averageConsumptionRate) + " GB/min\n" +
                "⏱ Ø Wartezeit: " + (totalWaitTime/cycleCount/1000) + "s", 
                Toast.LENGTH_LONG).show();
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        savePositions();
        hideVisuals();
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (floatingView != null && windowManager != null) { try { windowManager.removeView(floatingView); } catch (Exception e) {} }
        handler.removeCallbacksAndMessages(null);
        if (textRecognizer != null) textRecognizer.close();
    }
}
