package com.lidlrefill.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
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

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OverlayService extends AccessibilityService {
    private static final String TAG = "LidlRefill";
    
    // ============ UI ============
    private WindowManager windowManager;
    private FrameLayout floatingView;
    private TextView tvStatus, tvCountdown, tvCycle, tvOcrResult, tvRate;
    private Spinner spinnerConsumption;
    private Button btnSwipePlace, btnRefillPlace, btnOcrNow;
    private Button btnSwipeTest, btnRefillTest, btnStopAuto, btnStartAuto;
    private Button btnClose, btnCrop;
    
    // ============ PREFERENCES ============
    private SharedPreferences prefs;
    private static final String PREF_SWIPE_START_X = "swipe_start_x";
    private static final String PREF_SWIPE_START_Y = "swipe_start_y";
    private static final String PREF_SWIPE_END_X = "swipe_end_x";
    private static final String PREF_SWIPE_END_Y = "swipe_end_y";
    private static final String PREF_REFILL_X = "refill_x";
    private static final String PREF_REFILL_Y = "refill_y";
    private static final String PREF_SWIPE_PLACED = "swipe_placed";
    private static final String PREF_REFILL_PLACED = "refill_placed";
    private static final String PREF_CROP_LEFT = "crop_left";
    private static final String PREF_CROP_TOP = "crop_top";
    private static final String PREF_CROP_RIGHT = "crop_right";
    private static final String PREF_CROP_BOTTOM = "crop_bottom";
    
    // ============ VERBRAUCHS-HISTORIE ============
    private ArrayList<Double> volumeHistory = new ArrayList<>();
    private ArrayList<Long> timeHistory = new ArrayList<>();
    private static final int MAX_HISTORY = 10;
    private double averageConsumptionRate = 0.04;
    
    // ============ REFILL STATE ============
    private enum RefillState { IDLE, AFTER_REFILL_WAIT, AFTER_SWIPE_WAIT, CHECK_VOLUME, WAITING, REFILL }
    private RefillState refillState = RefillState.IDLE;
    private boolean justRefilled = false;
    private long lastRefillTime = 0;
    private int refillCycleCount = 0;
    
    // ============ SCREEN ============
    private int screenWidth, screenHeight;
    
    // ============ STATE ============
    private boolean isScreenshotReady = false;
    private boolean isProcessing = false;
    private boolean isRunning = false;
    private boolean isAutoRefillMode = false;
    private boolean isAutoRefillSelected = false;
    private boolean isWaiting = false;
    private boolean isCropMode = false;
    private boolean cropSet = false;
    private int cycleCount = 0;
    private int totalSwipes = 0;
    private File lastScreenshotFile = null;
    private String lastOcrText = "";
    private long screenshotTime = 0;
    private double lastDetectedVolume = 0.0;
    private long countdownStartTime = 0;
    private long currentWaitTime = 0;
    private boolean countdownActive = false;
    
    // ============ CROP ============
    private int cropLeft = 0, cropTop = 0, cropRight = 0, cropBottom = 0;
    private View cropOverlayView = null;
    private float cropStartX = 0, cropStartY = 0;
    private float cropEndX = 0, cropEndY = 0;
    private boolean isDrawingCrop = false;
    private boolean cropButtonClicked = false;
    private Handler cropAutoCloseHandler = new Handler(Looper.getMainLooper());
    private Runnable cropAutoCloseRunnable = null;
    
    // ============ CROP RAHMEN (SEPARATES VIEW) ============
    private View cropFrameView = null;
    private boolean cropFrameVisible = false;
    
    // ============ POSITIONEN ============
    private Point swipeStart = new Point(0, 0);
    private Point swipeEnd = new Point(0, 0);
    private Point refillButton = new Point(500, 500);
    private boolean swipePlaced = false;
    private boolean refillPlaced = false;
    
    // ============ VISUAL HELPERS ============
    private View swipeVisual, refillVisual;
    private View activeVisual = null;
    private float lastX, lastY, dragOffsetX, dragOffsetY;
    
    // ============ OVERLAY DRAGGING ============
    private boolean isOverlayDragging = false;
    private float overlayDragX = 0, overlayDragY = 0;
    
    // ============ HANDLER & RANDOM ============
    private Handler handler = new Handler(Looper.getMainLooper());
    private Random random = new Random();
    
    // ============ OCR ============
    private TextRecognizer textRecognizer;
    private String ocrResult = "📸 OCR: --";
    
    // ============ SCREENSHOT FOLDERS ============
    private File[] screenshotFolders = null;
    private String foundFolderPath = "";
    
    // ============ MODES ============
    private enum Mode { NONE, SWIPE_PLACE, REFILL_PLACE }
    private Mode currentMode = Mode.NONE;
    
    // ============ ZEITEN ============
    private static final long WAIT_AFTER_SWIPE_MIN = 8000;
    private static final long WAIT_AFTER_SWIPE_MAX = 14000;
    
    // NACH REFILL: 15-20 Minuten warten (menschlich)
    private static final long WAIT_AFTER_REFILL_MIN = 15 * 60 * 1000;   // 15 Minuten
    private static final long WAIT_AFTER_REFILL_MAX = 20 * 60 * 1000;   // 20 Minuten
    
    // ZWISCHEN SWIPE UND OCR: 10-15 Sekunden warten
    private static final long WAIT_BETWEEN_SWIPE_AND_OCR_MIN = 10 * 1000;   // 10 Sekunden
    private static final long WAIT_BETWEEN_SWIPE_AND_OCR_MAX = 15 * 1000;   // 15 Sekunden
    
    // NACH SCREENSHOT: 5-10 Sekunden warten (auf Screenshot-Datei)
    private static final long WAIT_AFTER_SCREENSHOT_MIN = 5 * 1000;   // 5 Sekunden
    private static final long WAIT_AFTER_SCREENSHOT_MAX = 10 * 1000;  // 10 Sekunden
    
    // Max Wartezeit bei niedrigem Volumen
    private static final long MAX_WAIT_LOW_VOLUME = 30 * 60 * 1000;    // 30 Minuten
    
    // ============ REFILL-SCHWELLWERT: 0,30 GB ============
    private static final double REFILL_THRESHOLD = 0.30;
    
    // ============ MINDEST-VERBRAUCHSRATE (Streaming) ============
    private static final double MIN_CONSUMPTION_RATE = 0.04; // 0,04 GB/Min (Streaming)
    
    // ============ CONSUMPTION OPTIONS ============
    private static final String[] CONSUMPTION_LABELS = {
        "📱 Surfen (18-22 Min)",
        "📺 FullHD (11-14 Min)",
        "🎬 4K (6-9 Min)",
        "♻️ AUTOREFILL (0,30 GB)"
    };
    private int currentModeIndex = 0;
    
    // ============ PHASES ============
    private enum Phase { IDLE, SWIPE, WAIT_AFTER_SWIPE, OCR, WAIT_AFTER_OCR, REFILL, WAIT_AFTER_REFILL }
    private Phase currentPhase = Phase.IDLE;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate - Service wird initialisiert");
        
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        screenWidth = size.x;
        screenHeight = size.y;
        
        loadCropCoordinates();
        loadPositions();
        
        int savedIndex = prefs.getInt("consumption_index", 3);
        currentModeIndex = savedIndex;
        isAutoRefillSelected = (savedIndex == 3);
        
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        
        screenshotFolders = new File[]{
            new File(Environment.getExternalStorageDirectory(), "Pictures/Screenshots"),
            new File(Environment.getExternalStorageDirectory(), "DCIM/Screenshots"),
            new File(Environment.getExternalStorageDirectory(), "Download"),
            new File(Environment.getExternalStorageDirectory(), "Pictures"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            new File(Environment.getExternalStorageDirectory(), "DCIM")
        };
        
        for (File folder : screenshotFolders) {
            if (folder != null && folder.exists()) {
                foundFolderPath = folder.getAbsolutePath();
                Log.d(TAG, "📁 Screenshot-Ordner gefunden: " + foundFolderPath);
                break;
            }
        }
        
        createOverlay();
        createVisualHelpers();
        
        updateStatus("● Bereit");
        updateCountdown("⏱ Warte: --:--");
        updateCycle();
        updateOcrResult("📸 OCR: --");
        updateRate();
    }
    
    // ============ CROP ============
    private void loadCropCoordinates() {
        cropLeft = prefs.getInt(PREF_CROP_LEFT, -1);
        cropTop = prefs.getInt(PREF_CROP_TOP, -1);
        cropRight = prefs.getInt(PREF_CROP_RIGHT, -1);
        cropBottom = prefs.getInt(PREF_CROP_BOTTOM, -1);
        
        if (cropLeft >= 0 && cropTop >= 0 && cropRight >= 0 && cropBottom >= 0) {
            cropSet = true;
        } else {
            cropSet = false;
            cropLeft = screenWidth / 4;
            cropTop = screenHeight / 4;
            cropRight = screenWidth * 3 / 4;
            cropBottom = screenHeight * 2 / 3;
        }
    }
    
    private void saveCropCoordinates() {
        prefs.edit()
            .putInt(PREF_CROP_LEFT, cropLeft)
            .putInt(PREF_CROP_TOP, cropTop)
            .putInt(PREF_CROP_RIGHT, cropRight)
            .putInt(PREF_CROP_BOTTOM, cropBottom)
            .apply();
        cropSet = true;
        Toast.makeText(this, "✅ Crop gespeichert!", Toast.LENGTH_SHORT).show();
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}
    
    @Override
    public void onInterrupt() {}
    
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "onServiceConnected - Accessibility verbunden");
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.packageNames = null;
        setServiceInfo(info);
        
        isScreenshotReady = true;
        updateStatus("✅ Screenshot bereit");
        Toast.makeText(this, "✅ Native Screenshot aktiv!", Toast.LENGTH_SHORT).show();
    }
    
    // ============ CROP-MODUS ============
    private void startCropMode() {
        if (isCropMode) {
            closeCropMode();
            return;
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "❌ Overlay-Berechtigung fehlt!", Toast.LENGTH_LONG).show();
            return;
        }
        
        isCropMode = true;
        cropButtonClicked = false;
        cropFrameVisible = false;
        updateStatus("✂️ Ziehe Bereich für OCR (3s Auto-Close)");
        Toast.makeText(this, "✂️ Ziehe Bereich auf dem Display", Toast.LENGTH_LONG).show();
        createCropOverlay();
    }
    
    private void closeCropMode() {
        isCropMode = false;
        cropButtonClicked = false;
        removeCropFrame();
        if (cropOverlayView != null) {
            try { windowManager.removeView(cropOverlayView); } catch (Exception e) {}
            cropOverlayView = null;
        }
        cropAutoCloseHandler.removeCallbacks(cropAutoCloseRunnable);
        cropAutoCloseRunnable = null;
        updateStatus("● Crop-Modus beendet");
        Toast.makeText(this, "✂️ Crop-Modus beendet", Toast.LENGTH_SHORT).show();
    }
    
    private void scheduleAutoClose() {
        cropAutoCloseHandler.removeCallbacks(cropAutoCloseRunnable);
        cropAutoCloseRunnable = () -> {
            if (isCropMode && !isDrawingCrop) {
                Log.d(TAG, "⏰ Auto-Close: Crop-Modus wird geschlossen");
                closeCropMode();
            }
        };
        cropAutoCloseHandler.postDelayed(cropAutoCloseRunnable, 3000);
    }
    
    // ===== SEPARATER FRAME VIEW =====
    private void showCropFrame(float left, float top, float right, float bottom) {
        if (cropFrameView != null && cropFrameVisible) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) cropFrameView.getLayoutParams();
            params.x = (int)left;
            params.y = (int)top;
            params.width = Math.max(10, (int)(right - left));
            params.height = Math.max(10, (int)(bottom - top));
            try {
                windowManager.updateViewLayout(cropFrameView, params);
            } catch (Exception e) {
                Log.e(TAG, "Frame aktualisieren fehlgeschlagen", e);
            }
            return;
        }
        
        removeCropFrame();
        
        int width = Math.max(10, (int)(right - left));
        int height = Math.max(10, (int)(bottom - top));
        
        cropFrameView = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Paint paint = new Paint();
                paint.setColor(Color.BLACK);
                paint.setStrokeWidth(4);
                paint.setStyle(Paint.Style.STROKE);
                paint.setAntiAlias(true);
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            }
        };
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            width,
            height,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = (int)left;
        params.y = (int)top;
        
        cropFrameView.setElevation(1000);
        try {
            windowManager.addView(cropFrameView, params);
            cropFrameVisible = true;
        } catch (Exception e) {
            Log.e(TAG, "Frame hinzufügen fehlgeschlagen", e);
            cropFrameView = null;
            cropFrameVisible = false;
        }
    }
    
    private void removeCropFrame() {
        if (cropFrameView != null) {
            try { 
                windowManager.removeView(cropFrameView); 
            } catch (Exception e) {
                Log.e(TAG, "Frame entfernen fehlgeschlagen", e);
            }
            cropFrameView = null;
        }
        cropFrameVisible = false;
    }
    
    private void createCropOverlay() {
        if (cropOverlayView != null) {
            try { windowManager.removeView(cropOverlayView); } catch (Exception e) {}
            cropOverlayView = null;
        }
        
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.TRANSPARENT);
        
        cropOverlayView = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
            }
        };
        
        cropOverlayView.setOnTouchListener((v, event) -> {
            if (cropButtonClicked) {
                return false;
            }
            
            float rawX = event.getRawX();
            float rawY = event.getRawY();
            
            rawX = Math.max(0, Math.min(screenWidth, rawX));
            rawY = Math.max(0, Math.min(screenHeight, rawY));
            
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    cropStartX = rawX;
                    cropStartY = rawY;
                    cropEndX = rawX;
                    cropEndY = rawY;
                    isDrawingCrop = true;
                    showCropFrame(cropStartX, cropStartY, cropEndX, cropEndY);
                    cropAutoCloseHandler.removeCallbacks(cropAutoCloseRunnable);
                    Log.d(TAG, "✂️ Crop START: " + cropStartX + ", " + cropStartY);
                    return true;
                    
                case MotionEvent.ACTION_MOVE:
                    cropEndX = rawX;
                    cropEndY = rawY;
                    if (isDrawingCrop) {
                        showCropFrame(
                            Math.min(cropStartX, cropEndX),
                            Math.min(cropStartY, cropEndY),
                            Math.max(cropStartX, cropEndX),
                            Math.max(cropStartY, cropEndY)
                        );
                    }
                    return true;
                    
                case MotionEvent.ACTION_UP:
                    cropEndX = rawX;
                    cropEndY = rawY;
                    isDrawingCrop = false;
                    
                    int left = (int)Math.min(cropStartX, cropEndX);
                    int top = (int)Math.min(cropStartY, cropEndY);
                    int right = (int)Math.max(cropStartX, cropEndX);
                    int bottom = (int)Math.max(cropStartY, cropEndY);
                    
                    Log.d(TAG, "✂️ Crop ENDE: " + left + "," + top + " - " + right + "," + bottom);
                    
                    if (right - left > 50 && bottom - top > 50) {
                        cropLeft = left;
                        cropTop = top;
                        cropRight = right;
                        cropBottom = bottom;
                        saveCropCoordinates();
                        Toast.makeText(OverlayService.this, 
                            "✅ Crop Bereich gespeichert!", 
                            Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(OverlayService.this, "⚠️ Bereich zu klein!", Toast.LENGTH_SHORT).show();
                    }
                    
                    handler.postDelayed(() -> {
                        removeCropFrame();
                        if (isCropMode) {
                            updateStatus("✂️ Crop-Modus aktiv - 3s Auto-Close");
                        }
                    }, 500);
                    
                    scheduleAutoClose();
                    return true;
            }
            return false;
        });
        
        container.addView(cropOverlayView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        
        try {
            windowManager.addView(container, params);
            cropOverlayView = container;
        } catch (Exception e) {
            Log.e(TAG, "Fehler beim Crop-Overlay: " + e.getMessage());
            Toast.makeText(this, "❌ Crop-Overlay konnte nicht erstellt werden!", Toast.LENGTH_LONG).show();
            isCropMode = false;
            cropOverlayView = null;
        }
    }
    
    // ============ SCREENSHOT & OCR ============
    private void performScreenshotAndOcr() {
        if (isProcessing) {
            Toast.makeText(this, "⏳ Bitte warten, OCR läuft noch...", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (!isScreenshotReady) {
            Toast.makeText(this, "⚠️ Screenshot noch nicht bereit", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this, "❌ Benötigt Android 9+ für Screenshot", Toast.LENGTH_LONG).show();
            updateStatus("❌ Android 9+ benötigt");
            return;
        }
        
        if (isCropMode) {
            Toast.makeText(this, "⚠️ Crop-Modus aktiv! Beende zuerst", Toast.LENGTH_SHORT).show();
            return;
        }
        
        isProcessing = true;
        screenshotTime = System.currentTimeMillis();
        
        // STEP 1: Screenshot auslösen
        updateStatus("📸 Schritt 1/5: Screenshot wird ausgelöst...");
        updateOcrResult("📸 Screenshot...");
        Log.d(TAG, "📸 Schritt 1/5: Screenshot wird ausgelöst");
        Toast.makeText(this, "📸 Screenshot wird ausgelöst", Toast.LENGTH_SHORT).show();
        
        performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
        Log.d(TAG, "✅ Native Screenshot wurde ausgelöst");
        
        // STEP 2: Warten auf Screenshot
        long waitTime = WAIT_AFTER_SCREENSHOT_MIN + 
            (long)(random.nextDouble() * (WAIT_AFTER_SCREENSHOT_MAX - WAIT_AFTER_SCREENSHOT_MIN));
        updateStatus("📸 Schritt 2/5: Warte auf Screenshot (" + (waitTime/1000) + "s)...");
        Log.d(TAG, "⏳ Schritt 2/5: Warte " + (waitTime/1000) + "s auf Screenshot");
        
        startCountdownThread(waitTime, () -> {
            Log.d(TAG, "📸 Schritt 2/5: Warte vorbei, suche Screenshot...");
            updateStatus("📸 Schritt 3/5: Suche Screenshot...");
            findScreenshotInAllFolders(1);
        });
    }
    
    private void findScreenshotInAllFolders(int attempt) {
        if (!isProcessing) {
            Log.d(TAG, "⚠️ Screenshot-Suche abgebrochen (isProcessing=false)");
            return;
        }
        
        if (attempt > 20) {
            updateStatus("❌ Screenshot nicht gefunden (25s)");
            updateOcrResult("❌ Zeitüberschreitung");
            Toast.makeText(this, "❌ Screenshot nicht gefunden nach 25 Sekunden!", Toast.LENGTH_LONG).show();
            isProcessing = false;
            return;
        }
        
        Log.d(TAG, "🔍 Suche Screenshot (Versuch " + attempt + "/20)");
        updateStatus("📸 Schritt 3/5: Suche Screenshot (" + attempt + "/20)...");
        
        // Neueste Screenshot-Datei finden
        File latestFile = null;
        long latestTime = 0;
        
        for (File folder : screenshotFolders) {
            if (folder == null || !folder.exists()) continue;
            
            File[] files = folder.listFiles((dir, name) -> 
                name.toLowerCase().endsWith(".png") || name.toLowerCase().endsWith(".jpg"));
            
            if (files == null || files.length == 0) continue;
            
            for (File file : files) {
                if (file.lastModified() > latestTime) {
                    latestTime = file.lastModified();
                    latestFile = file;
                }
            }
        }
        
        if (latestFile == null) {
            // Nach 1 Sekunde erneut versuchen
            startCountdownThread(1000, () -> {
                if (isProcessing) {
                    findScreenshotInAllFolders(attempt + 1);
                }
            });
            return;
        }
        
        lastScreenshotFile = latestFile;
        long waitTime = (System.currentTimeMillis() - screenshotTime) / 1000;
        Log.d(TAG, "📸 Screenshot gefunden nach " + waitTime + "s: " + latestFile.getAbsolutePath());
        updateStatus("📸 Schritt 3/5: Screenshot gefunden (" + waitTime + "s) ✅");
        Toast.makeText(this, "📸 Screenshot gefunden nach " + waitTime + "s", Toast.LENGTH_SHORT).show();
        
        // STEP 4: Screenshot laden und Crop-Bereich ausschneiden
        updateStatus("📸 Schritt 4/5: Lade Screenshot & Crop-Bereich...");
        Log.d(TAG, "📸 Schritt 4/5: Lade Screenshot & Crop-Bereich");
        
        Bitmap fullBitmap = BitmapFactory.decodeFile(latestFile.getAbsolutePath());
        if (fullBitmap == null) {
            updateStatus("❌ Screenshot laden fehlgeschlagen");
            updateOcrResult("❌ Laden fehlgeschlagen");
            Toast.makeText(this, "❌ Screenshot konnte nicht geladen werden!", Toast.LENGTH_LONG).show();
            isProcessing = false;
            return;
        }
        
        Bitmap croppedBitmap = createPartialScreenshot(fullBitmap);
        fullBitmap.recycle();
        
        if (croppedBitmap == null) {
            updateStatus("❌ Teilscreenshot fehlgeschlagen");
            updateOcrResult("❌ Cropping fehlgeschlagen");
            Toast.makeText(this, "❌ Teilscreenshot fehlgeschlagen!", Toast.LENGTH_LONG).show();
            isProcessing = false;
            return;
        }
        
        // STEP 5: OCR ausführen
        updateStatus("📸 Schritt 5/5: OCR wird ausgeführt...");
        Log.d(TAG, "📸 Schritt 5/5: OCR wird ausgeführt");
        Toast.makeText(this, "📸 OCR wird ausgeführt...", Toast.LENGTH_SHORT).show();
        
        performOcrOnBitmap(croppedBitmap);
    }
    
    private Bitmap createPartialScreenshot(Bitmap fullScreenshot) {
        try {
            if (!cropSet || cropLeft < 0 || cropTop < 0 || cropRight < 0 || cropBottom < 0) {
                cropLeft = screenWidth / 4;
                cropTop = screenHeight / 4;
                cropRight = screenWidth * 3 / 4;
                cropBottom = screenHeight * 2 / 3;
                cropSet = true;
            }
            
            int left = Math.max(0, Math.min(cropLeft, fullScreenshot.getWidth() - 10));
            int top = Math.max(0, Math.min(cropTop, fullScreenshot.getHeight() - 10));
            int right = Math.min(fullScreenshot.getWidth(), Math.max(cropRight, left + 50));
            int bottom = Math.min(fullScreenshot.getHeight(), Math.max(cropBottom, top + 50));
            
            if (right - left < 50 || bottom - top < 50) {
                Log.e(TAG, "❌ Teilscreenshot zu klein");
                return null;
            }
            
            Log.d(TAG, "✂️ Crop-Bereich: " + left + "," + top + " - " + right + "," + bottom);
            return Bitmap.createBitmap(fullScreenshot, left, top, right - left, bottom - top);
        } catch (Exception e) {
            Log.e(TAG, "❌ Teilscreenshot Fehler: " + e.getMessage());
            return null;
        }
    }
    
    private void performOcrOnBitmap(Bitmap screenshot) {
        if (screenshot == null) {
            updateStatus("❌ Bitmap ist null");
            updateOcrResult("❌ Bitmap null");
            isProcessing = false;
            return;
        }
        
        if (!isProcessing) {
            Log.d(TAG, "⚠️ OCR abgebrochen (isProcessing=false)");
            return;
        }
        
        updateStatus("📸 Schritt 5/5: OCR wird verarbeitet...");
        updateOcrResult("📸 OCR...");
        
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(screenshot, screenshot.getWidth() * 2, screenshot.getHeight() * 2, true);
        
        InputImage image = InputImage.fromBitmap(scaledBitmap, 0);
        textRecognizer.process(image)
            .addOnSuccessListener(visionText -> {
                String resultText = visionText.getText();
                lastOcrText = resultText;
                Log.d(TAG, "📝 OCR Rohergebnis:\n" + resultText);
                
                String volume = extractVolumeImproved(resultText);
                isProcessing = false;
                
                // Screenshot-Datei löschen
                if (lastScreenshotFile != null && lastScreenshotFile.exists()) {
                    lastScreenshotFile.delete();
                    lastScreenshotFile = null;
                }
                
                if (volume != null) {
                    double currentVolume = Double.parseDouble(volume.replace(",", "."));
                    lastDetectedVolume = currentVolume;
                    
                    updateConsumptionHistory(currentVolume);
                    
                    ocrResult = "📸 " + volume + " GB";
                    updateOcrResult(ocrResult);
                    updateStatus("✅ OCR: " + volume + " GB");
                    Toast.makeText(OverlayService.this, "✅ OCR: " + volume + " GB", Toast.LENGTH_LONG).show();
                    
                    // Wenn Auto-Modus läuft, Volume-Check ausführen
                    if (isAutoRefillSelected || isAutoRefillMode) {
                        handleVolumeCheck(currentVolume);
                    }
                } else {
                    lastDetectedVolume = 0.0;
                    ocrResult = "📸 Kein GB-Wert";
                    updateOcrResult(ocrResult);
                    updateStatus("⚠️ Kein GB-Wert gefunden");
                    Toast.makeText(OverlayService.this, "⚠️ Kein GB-Wert gefunden", Toast.LENGTH_LONG).show();
                    
                    if (isAutoRefillSelected || isAutoRefillMode) {
                        Toast.makeText(OverlayService.this, "♻️ Kein Wert erkannt → Refill", Toast.LENGTH_SHORT).show();
                        startCountdownThread(1000, () -> {
                            if (isRunning) {
                                performRefill();
                            }
                        });
                    }
                }
                scaledBitmap.recycle();
                screenshot.recycle();
            })
            .addOnFailureListener(e -> {
                isProcessing = false;
                updateStatus("❌ OCR Fehler: " + e.getMessage());
                updateOcrResult("❌ OCR Fehler");
                Toast.makeText(OverlayService.this, "❌ OCR Fehler: " + e.getMessage(), Toast.LENGTH_LONG).show();
                scaledBitmap.recycle();
                screenshot.recycle();
            });
    }
    
    // ============ VERBRAUCHS-HISTORIE ============
    private void updateConsumptionHistory(double currentVolume) {
        long currentTime = System.currentTimeMillis();
        
        // Prüfen ob gerade refillt wurde (Volumen-Sprung nach oben)
        if (volumeHistory.size() > 0) {
            double lastVolume = volumeHistory.get(volumeHistory.size() - 1);
            if (currentVolume > lastVolume + 0.5) {
                justRefilled = true;
                lastRefillTime = currentTime;
                refillCycleCount++;
                Log.d(TAG, "🔄 Refill erkannt! Volumen: " + lastVolume + " → " + currentVolume);
            }
        }
        
        volumeHistory.add(currentVolume);
        timeHistory.add(currentTime);
        
        if (volumeHistory.size() > MAX_HISTORY) {
            volumeHistory.remove(0);
            timeHistory.remove(0);
        }
        
        if (volumeHistory.size() >= 2) {
            calculateAverageConsumption();
        }
        
        Log.d(TAG, "📊 Verbrauchshistorie: " + volumeHistory.size() + " Werte");
        Log.d(TAG, "📊 Durchschnittlicher Verbrauch: " + averageConsumptionRate + " GB/Min");
    }
    
    private void calculateAverageConsumption() {
        if (volumeHistory.size() < 2 || timeHistory.size() < 2) return;
        
        double totalConsumption = 0;
        long totalTimeMinutes = 0;
        
        for (int i = 1; i < volumeHistory.size(); i++) {
            double diff = volumeHistory.get(i - 1) - volumeHistory.get(i);
            if (diff > 0) {
                totalConsumption += diff;
                long timeDiff = timeHistory.get(i) - timeHistory.get(i - 1);
                totalTimeMinutes += timeDiff / 60000;
            }
        }
        
        if (totalTimeMinutes > 0 && totalConsumption > 0) {
            averageConsumptionRate = totalConsumption / totalTimeMinutes;
            if (averageConsumptionRate < MIN_CONSUMPTION_RATE) {
                averageConsumptionRate = MIN_CONSUMPTION_RATE;
                Log.d(TAG, "📊 Verbrauchsrate auf Streaming-Minimum 0,04 GB/Min gesetzt");
            }
            averageConsumptionRate = Math.max(MIN_CONSUMPTION_RATE, Math.min(0.15, averageConsumptionRate));
            Log.d(TAG, "📊 Neue Verbrauchsrate: " + averageConsumptionRate + " GB/Min");
            
            updateRate();
        }
    }
    
    private String extractVolumeImproved(String text) {
        if (text == null || text.isEmpty()) {
            Log.d(TAG, "OCR Text ist leer");
            return null;
        }
        
        Log.d(TAG, "🔍 Suche nach GB-Wert in:\n" + text);
        
        String[] patterns = {
            "(\\d+[\\.,]?\\d*)\\s*(GB|Gb|gB|gb)",
            "(\\d+[\\.,]?\\d*)(GB|Gb|gB|gb)",
            "(\\d+[\\.,]\\d+)",
            "(\\d+)",
            "Verfügbares Gesamtvolumen[\\s\\S]*?(\\d+[\\.,]?\\d*)",
            "Volumen[\\s\\S]*?(\\d+[\\.,]?\\d*)\\s*(GB|Gb|gB|gb)",
            "Gesamtvolumen[\\s\\S]*?(\\d+[\\.,]?\\d*)",
            "(\\d+[\\.,]\\d+)\\s*(GB|Gb|gB|gb)",
            "(0[\\.,]\\d{2})",
            "(\\d{2}[\\.,]\\d{2})",
            "(\\d{3}[\\.,]\\d{2})"
        };
        
        for (String patternStr : patterns) {
            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String value = matcher.group(1).replace(",", ".");
                try {
                    double val = Double.parseDouble(value);
                    Log.d(TAG, "🔍 Pattern gefunden: " + value + " (aus: " + patternStr + ")");
                    if (val > 0 && val < 1000) {
                        return value;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Parse Fehler: " + e.getMessage());
                }
            }
        }
        
        Pattern specialPattern = Pattern.compile(
            "(\\d+[\\.,]\\d+)\\s*GB",
            Pattern.CASE_INSENSITIVE
        );
        Matcher specialMatcher = specialPattern.matcher(text);
        if (specialMatcher.find()) {
            String value = specialMatcher.group(1).replace(",", ".");
            try {
                double val = Double.parseDouble(value);
                Log.d(TAG, "🔍 Spezialfall gefunden: " + value + " GB");
                if (val > 0 && val < 1000) {
                    return value;
                }
            } catch (Exception e) {}
        }
        
        Pattern wholePattern = Pattern.compile(
            "(\\d+)\\s*GB",
            Pattern.CASE_INSENSITIVE
        );
        Matcher wholeMatcher = wholePattern.matcher(text);
        if (wholeMatcher.find()) {
            String value = wholeMatcher.group(1);
            try {
                double val = Double.parseDouble(value);
                Log.d(TAG, "🔍 Ganze Zahl gefunden: " + value + " GB");
                if (val > 0 && val < 1000) {
                    return value;
                }
            } catch (Exception e) {}
        }
        
        Log.d(TAG, "❌ Kein GB-Wert gefunden");
        return null;
    }
    
    // ============ KERNLOGIK ============
    private void handleVolumeCheck(double volume) {
        if (!isRunning) return;
        
        Log.d(TAG, "📊 Volume Check: " + volume + " GB, State: " + refillState);
        Log.d(TAG, "📊 Verbrauchsrate: " + averageConsumptionRate + " GB/Min");
        
        switch (refillState) {
            case IDLE:
                if (volume <= REFILL_THRESHOLD) {
                    Log.d(TAG, "⚡ Volumen ≤ 0,30 GB → Refill");
                    performRefill();
                } else {
                    long waitTime = calculateWaitTime(volume);
                    startCountdownWithState(waitTime, "⏳ Warte bis 0,30 GB", RefillState.WAITING);
                }
                break;
                
            case AFTER_REFILL_WAIT:
                Log.d(TAG, "📸 Nach Refill-Warte: Swipe ausführen");
                refillState = RefillState.AFTER_SWIPE_WAIT;
                performSwipeOnly();
                break;
                
            case AFTER_SWIPE_WAIT:
                Log.d(TAG, "📸 Nach Swipe-Warte: OCR ausführen");
                refillState = RefillState.CHECK_VOLUME;
                performScreenshotAndOcr();
                break;
                
            case CHECK_VOLUME:
                if (volume <= REFILL_THRESHOLD) {
                    Log.d(TAG, "⚡ Volumen ≤ 0,30 GB → Refill");
                    performRefill();
                } else {
                    long waitTime = calculateWaitTime(volume);
                    startCountdownWithState(waitTime, "⏳ Warte bis 0,30 GB", RefillState.WAITING);
                }
                break;
                
            case WAITING:
                break;
                
            case REFILL:
                break;
        }
    }
    
    // ===== SWIPE NUR AUSFÜHREN =====
    private void performSwipeOnly() {
        if (!swipePlaced || !isRunning) {
            return;
        }
        
        totalSwipes++;
        cycleCount++;
        updateCycle();
        updateStatus("🔄 Swipe wird ausgeführt...");
        
        int randomOffsetX = (int)((random.nextDouble() - 0.5) * 40);
        int randomOffsetY = (int)((random.nextDouble() - 0.5) * 40);
        long randomDuration = 400 + (long)(random.nextDouble() * 400);
        long randomDelay = (long)(random.nextDouble() * 500);
        
        int startX = swipeStart.x + randomOffsetX;
        int startY = swipeStart.y + randomOffsetY;
        int endX = swipeEnd.x + randomOffsetX;
        int endY = swipeEnd.y + randomOffsetY;
        
        Path path = new Path();
        path.moveTo(startX, startY);
        path.quadTo(
            (startX + endX) / 2 + (int)((random.nextDouble() - 0.5) * 100),
            (startY + endY) / 2 + (int)((random.nextDouble() - 0.5) * 50),
            endX,
            endY
        );
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, randomDuration));
        
        handler.postDelayed(() -> {
            if (!isRunning) return;
            
            dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    if (!isRunning) return;
                    
                    updateStatus("✅ Swipe #" + totalSwipes + " abgeschlossen");
                    updateCycle();
                    
                    // Wartezeit zwischen Swipe und OCR
                    long waitTime = WAIT_BETWEEN_SWIPE_AND_OCR_MIN + 
                        (long)(random.nextDouble() * (WAIT_BETWEEN_SWIPE_AND_OCR_MAX - WAIT_BETWEEN_SWIPE_AND_OCR_MIN));
                    updateStatus("⏳ Warte vor OCR (" + (waitTime/1000) + "s)");
                    
                    startCountdownThread(waitTime, () -> {
                        if (isRunning) {
                            Log.d(TAG, "📸 Nach Swipe-Warte: OCR ausführen");
                            refillState = RefillState.CHECK_VOLUME;
                            performScreenshotAndOcr();
                        }
                    });
                }
            }, null);
        }, randomDelay);
    }
    
    // ===== REFILL =====
    private void performRefill() {
        if (refillState == RefillState.REFILL || !isRunning) return;
        
        Log.d(TAG, "🔄 Refill wird ausgelöst (≤ 0,30 GB)");
        refillState = RefillState.REFILL;
        justRefilled = true;
        lastRefillTime = System.currentTimeMillis();
        refillCycleCount++;
        
        updateStatus("♻️ Refill wird durchgeführt (≤ 0,30 GB)...");
        Toast.makeText(this, "♻️ Refill wird gedrückt!", Toast.LENGTH_SHORT).show();
        
        clickRefillButton();
        
        // Wartezeit nach Refill (15-20 Minuten)
        long waitTime = WAIT_AFTER_REFILL_MIN + 
            (long)(random.nextDouble() * (WAIT_AFTER_REFILL_MAX - WAIT_AFTER_REFILL_MIN));
        Log.d(TAG, "⏱️ Nach Refill: " + (waitTime/60000) + " Minuten warten");
        updateStatus("⏳ Nach Refill: " + (waitTime/60000) + " Min warten");
        
        // State auf AFTER_REFILL_WAIT setzen, damit nach der Wartezeit der nächste Schritt kommt
        refillState = RefillState.AFTER_REFILL_WAIT;
        
        startCountdownThread(waitTime, () -> {
            if (isRunning) {
                Log.d(TAG, "⏱️ Nach Refill-Warte vorbei → Swipe ausführen");
                performSwipeOnly();
            }
        });
    }
    
    // ===== WARTEZEIT BERECHNEN =====
    private long calculateWaitTime(double currentVolume) {
        double rate = Math.max(averageConsumptionRate, MIN_CONSUMPTION_RATE);
        
        if (rate <= 0.001 || rate > 0.2) {
            switch (currentModeIndex) {
                case 0: rate = 0.025; break;
                case 1: rate = 0.04; break;
                case 2: rate = 0.06; break;
                case 3: 
                default: rate = MIN_CONSUMPTION_RATE; break;
            }
            Log.d(TAG, "📊 Fallback auf Rate: " + rate);
        }
        
        double diff = currentVolume - REFILL_THRESHOLD;
        if (diff < 0) diff = 0;
        
        double minutesDouble = diff / rate;
        
        double randomFactor = 0.70 + (random.nextDouble() * 0.60);
        minutesDouble = minutesDouble * randomFactor;
        
        long minWait = 5 * 60 * 1000;
        long maxWait = 6 * 60 * 60 * 1000;
        
        if (currentVolume <= 5.00) {
            maxWait = Math.min(maxWait, MAX_WAIT_LOW_VOLUME);
        }
        
        long minutes = Math.round(Math.max(minWait / 60000, Math.min(maxWait / 60000, minutesDouble)));
        long waitTime = minutes * 60000;
        
        waitTime += (long)((random.nextDouble() - 0.5) * 60000);
        waitTime = Math.max(minWait, Math.min(maxWait, waitTime));
        
        Log.d(TAG, "📊 Berechnete Wartezeit: " + (waitTime / 60000) + " Minuten");
        return waitTime;
    }
    
    // ===== COUNTDOWN MIT STATE =====
    private void startCountdownWithState(long waitTime, String statusText, RefillState nextState) {
        refillState = nextState;
        
        long minutes = waitTime / 60000;
        long seconds = (waitTime % 60000) / 1000;
        String timeStr = minutes + " Min " + seconds + " Sek";
        if (minutes >= 60) {
            long hours = minutes / 60;
            long mins = minutes % 60;
            timeStr = hours + "h " + mins + "min";
        }
        
        updateStatus(statusText + " (" + timeStr + ")");
        Toast.makeText(this, statusText + " (" + timeStr + ")", Toast.LENGTH_SHORT).show();
        
        currentPhase = Phase.WAIT_AFTER_OCR;
        
        startCountdownThread(waitTime, () -> {
            Log.d(TAG, "⏱️ Countdown abgelaufen! Führe nächsten Schritt aus...");
            if (isRunning) {
                refillState = RefillState.CHECK_VOLUME;
                performSwipeAndOcr();
            }
        });
    }
    
    // ===== ZENTRALER COUNTDOWN-THREAD =====
    private void startCountdownThread(long waitTime, Runnable onFinish) {
        // Alten Countdown beenden
        stopCountdownThread();
        
        if (waitTime <= 0) {
            if (onFinish != null) {
                handler.post(onFinish);
            }
            return;
        }
        
        countdownRunning = true;
        countdownActive = true;
        countdownStartTime = System.currentTimeMillis();
        currentWaitTime = waitTime;
        countdownCallback = onFinish;
        
        countdownThread = new Thread(() -> {
            try {
                long remaining = waitTime;
                while (countdownRunning && remaining > 0) {
                    Thread.sleep(100);
                    remaining = waitTime - (System.currentTimeMillis() - countdownStartTime);
                    
                    // UI aktualisieren
                    if (countdownRunning) {
                        final long finalRemaining = Math.max(0, remaining);
                        handler.post(() -> {
                            if (countdownActive) {
                                long seconds = finalRemaining / 1000;
                                long minutes = seconds / 60;
                                seconds = seconds % 60;
                                updateCountdown(String.format("⏱ Warte: %02d:%02d", minutes, seconds));
                            }
                        });
                    }
                }
                
                // Countdown abgelaufen
                if (countdownRunning && remaining <= 0) {
                    handler.post(() -> {
                        countdownActive = false;
                        updateCountdown("⏱ Warte: 00:00");
                        if (countdownCallback != null) {
                            Log.d(TAG, "⏱️ Countdown beendet, führe Callback aus");
                            Runnable callback = countdownCallback;
                            countdownCallback = null;
                            callback.run();
                        }
                    });
                }
            } catch (InterruptedException e) {
                Log.d(TAG, "⏱️ Countdown Thread unterbrochen");
            } finally {
                countdownRunning = false;
                countdownActive = false;
            }
        });
        countdownThread.start();
    }
    
    private void stopCountdownThread() {
        countdownRunning = false;
        countdownActive = false;
        countdownCallback = null;
        if (countdownThread != null) {
            countdownThread.interrupt();
            try {
                countdownThread.join(500);
            } catch (InterruptedException e) {
                Log.d(TAG, "⏱️ Countdown Thread join unterbrochen");
            }
            countdownThread = null;
        }
        handler.post(() -> updateCountdown("⏱ Warte: --:--"));
    }
    
    // ============ SWIPE + OCR ============
    private void performSwipeAndOcr() {
        if (!isRunning) return;
        
        Log.d(TAG, "🔄 Swipe + OCR wird ausgeführt");
        currentPhase = Phase.SWIPE;
        performSwipeGesture();
    }
    
    // ============ START AUTOREFILL ============
    private void startAutoRefill() {
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
        
        isAutoRefillMode = true;
        isAutoRefillSelected = true;
        currentModeIndex = 3;
        prefs.edit().putInt("consumption_index", 3).apply();
        spinnerConsumption.setSelection(3);
        
        volumeHistory.clear();
        timeHistory.clear();
        averageConsumptionRate = MIN_CONSUMPTION_RATE;
        justRefilled = false;
        refillState = RefillState.IDLE;
        refillCycleCount = 0;
        cycleCount = 0;
        totalSwipes = 0;
        
        updateRate();
        updateCycle();
        
        Toast.makeText(this, "♻️ AUTOREFILL gestartet! (Refill bei ≤ 0,30 GB)", Toast.LENGTH_LONG).show();
        startAutomation();
    }
    
    // ============ POSITIONEN ============
    private void loadPositions() {
        swipeStart.x = prefs.getInt(PREF_SWIPE_START_X, screenWidth / 2);
        swipeStart.y = prefs.getInt(PREF_SWIPE_START_Y, 10);
        swipeEnd.x = prefs.getInt(PREF_SWIPE_END_X, screenWidth / 2);
        swipeEnd.y = prefs.getInt(PREF_SWIPE_END_Y, screenHeight - 10);
        swipePlaced = prefs.getBoolean(PREF_SWIPE_PLACED, true);
        
        refillButton.x = prefs.getInt(PREF_REFILL_X, 500);
        refillButton.y = prefs.getInt(PREF_REFILL_Y, 500);
        refillPlaced = prefs.getBoolean(PREF_REFILL_PLACED, false);
    }
    
    private void savePositions() {
        prefs.edit()
            .putInt(PREF_SWIPE_START_X, swipeStart.x)
            .putInt(PREF_SWIPE_START_Y, swipeStart.y)
            .putInt(PREF_SWIPE_END_X, swipeEnd.x)
            .putInt(PREF_SWIPE_END_Y, swipeEnd.y)
            .putBoolean(PREF_SWIPE_PLACED, swipePlaced)
            .putInt(PREF_REFILL_X, refillButton.x)
            .putInt(PREF_REFILL_Y, refillButton.y)
            .putBoolean(PREF_REFILL_PLACED, refillPlaced)
            .apply();
    }
    
    // ============ SWIPE TEST ============
    private void performSwipeTestGesture() {
        if (!swipePlaced) {
            Toast.makeText(this, "❌ Swipe nicht platziert!", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (isRunning) {
            updateStatus("🔄 Manueller Check: Swipe + OCR...");
            Toast.makeText(this, "🔄 Manueller Check wird ausgeführt", Toast.LENGTH_SHORT).show();
            
            refillState = RefillState.CHECK_VOLUME;
            performSwipeAndOcr();
            return;
        }
        
        int randomOffsetX = (int)((random.nextDouble() - 0.5) * 40);
        int randomOffsetY = (int)((random.nextDouble() - 0.5) * 40);
        long randomDuration = 400 + (long)(random.nextDouble() * 400);
        
        int startX = swipeStart.x + randomOffsetX;
        int startY = swipeStart.y + randomOffsetY;
        int endX = swipeEnd.x + randomOffsetX;
        int endY = swipeEnd.y + randomOffsetY;
        
        Path path = new Path();
        path.moveTo(startX, startY);
        path.quadTo(
            (startX + endX) / 2 + (int)((random.nextDouble() - 0.5) * 100),
            (startY + endY) / 2 + (int)((random.nextDouble() - 0.5) * 50),
            endX,
            endY
        );
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, randomDuration));
        
        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                updateStatus("✅ Swipe Test ausgeführt");
                Toast.makeText(OverlayService.this, "✅ Swipe Test erfolgreich!", Toast.LENGTH_SHORT).show();
            }
        }, null);
    }
    
    // ============ SWIPE GESTURE ============
    private void performSwipeGesture() {
        if (!swipePlaced || !isRunning) {
            return;
        }
        
        totalSwipes++;
        cycleCount++;
        updateCycle();
        updateStatus("🔄 Swipe wird ausgeführt...");
        
        int randomOffsetX = (int)((random.nextDouble() - 0.5) * 40);
        int randomOffsetY = (int)((random.nextDouble() - 0.5) * 40);
        long randomDuration = 400 + (long)(random.nextDouble() * 400);
        long randomDelay = (long)(random.nextDouble() * 500);
        
        int startX = swipeStart.x + randomOffsetX;
        int startY = swipeStart.y + randomOffsetY;
        int endX = swipeEnd.x + randomOffsetX;
        int endY = swipeEnd.y + randomOffsetY;
        
        Path path = new Path();
        path.moveTo(startX, startY);
        path.quadTo(
            (startX + endX) / 2 + (int)((random.nextDouble() - 0.5) * 100),
            (startY + endY) / 2 + (int)((random.nextDouble() - 0.5) * 50),
            endX,
            endY
        );
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, randomDuration));
        
        handler.postDelayed(() -> {
            if (!isRunning) return;
            
            dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    if (!isRunning) return;
                    
                    updateStatus("✅ Swipe #" + totalSwipes + " abgeschlossen");
                    updateCycle();
                    
                    if (isAutoRefillSelected || isAutoRefillMode) {
                        currentPhase = Phase.WAIT_AFTER_SWIPE;
                        long waitTime = WAIT_AFTER_SWIPE_MIN + 
                            (long)(random.nextDouble() * (WAIT_AFTER_SWIPE_MAX - WAIT_AFTER_SWIPE_MIN));
                        updateStatus("⏳ Warte nach Swipe (" + (waitTime/1000) + "s)");
                        
                        startCountdownThread(waitTime, () -> {
                            if (isRunning) {
                                currentPhase = Phase.OCR;
                                updateStatus("📸 OCR wird ausgeführt...");
                                performScreenshotAndOcr();
                            }
                        });
                    } else {
                        currentPhase = Phase.OCR;
                        performScreenshotAndOcr();
                    }
                }
            }, null);
        }, randomDelay);
    }
    
    // ============ CLICK REFILL BUTTON ============
    private void clickRefillButton() {
        if (!refillPlaced) {
            Toast.makeText(this, "❌ Refill nicht platziert!", Toast.LENGTH_SHORT).show();
            return;
        }
        
        int randomOffsetX = (int)((random.nextDouble() - 0.5) * 30);
        int randomOffsetY = (int)((random.nextDouble() - 0.5) * 30);
        long clickDuration = 50 + (long)(random.nextDouble() * 150);
        long randomDelay = 100 + (long)(random.nextDouble() * 400);
        
        int clickX = refillButton.x + randomOffsetX;
        int clickY = refillButton.y + randomOffsetY;
        
        Path clickPath = new Path();
        clickPath.moveTo(clickX, clickY);
        
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, clickDuration));
        
        handler.postDelayed(() -> {
            if (!isRunning) return;
            
            dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    if (!isRunning) return;
                    
                    updateStatus("✅ Refill geklickt!");
                    Toast.makeText(OverlayService.this, "✅ Refill-Button geklickt!", Toast.LENGTH_SHORT).show();
                    
                    if (isAutoRefillSelected || isAutoRefillMode) {
                        currentPhase = Phase.WAIT_AFTER_REFILL;
                        long waitTime = WAIT_AFTER_SWIPE_MIN + 
                            (long)(random.nextDouble() * (WAIT_AFTER_SWIPE_MAX - WAIT_AFTER_SWIPE_MIN));
                        updateStatus("⏳ Warte nach Refill (" + (waitTime/1000) + "s)");
                        
                        startCountdownThread(waitTime, () -> {
                            if (isRunning) {
                                currentPhase = Phase.SWIPE;
                                updateStatus("🔄 Swipe...");
                                performSwipeGesture();
                            }
                        });
                    } else {
                        currentPhase = Phase.SWIPE;
                        performSwipeGesture();
                    }
                }
            }, null);
        }, randomDelay);
    }
    
    // ============ AUTOMATIK START/STOP ============
    private void startAutomation() {
        isRunning = true;
        cycleCount = 0;
        totalSwipes = 0;
        refillState = RefillState.IDLE;
        countdownActive = false;
        btnStartAuto.setText("▶ Läuft");
        btnStartAuto.setEnabled(false);
        btnStopAuto.setEnabled(true);
        updateStatus("🟢 Automatik läuft" + (isAutoRefillMode ? " ♻️" : ""));
        updateCycle();
        
        startCountdownThread(2000, () -> {
            if (isRunning) {
                if (isAutoRefillSelected || isAutoRefillMode) {
                    updateStatus("🔄 Starte mit Swipe...");
                    performSwipeGesture();
                } else {
                    updateStatus("🔄 Swipe...");
                    performSwipeGesture();
                }
            }
        });
    }
    
    private void stopAutomation() {
        isRunning = false;
        isWaiting = false;
        isAutoRefillMode = false;
        refillState = RefillState.IDLE;
        currentPhase = Phase.IDLE;
        countdownActive = false;
        stopCountdownThread();
        handler.removeCallbacksAndMessages(null);
        btnStartAuto.setText("▶ Start");
        btnStartAuto.setEnabled(true);
        btnStopAuto.setEnabled(false);
        updateStatus("● Gestoppt");
        updateCountdown("⏱ Warte: --:--");
    }
    
    // ============ UI HELPERS ============
    private void updateStatus(String text) { if (tvStatus != null) tvStatus.setText(text); }
    private void updateCountdown(String text) { if (tvCountdown != null) tvCountdown.setText(text); }
    private void updateCycle() { if (tvCycle != null) tvCycle.setText("🔄 " + cycleCount + " Zyklen | ⬇ " + totalSwipes); }
    private void updateOcrResult(String text) { if (tvOcrResult != null) tvOcrResult.setText(text); }
    
    private void updateRate() {
        if (tvRate != null) {
            String rateStr = String.format("%.3f", averageConsumptionRate);
            tvRate.setText("📊 " + rateStr + " GB/Min");
        }
    }
    
    // ============ OVERLAY ============
    private void createOverlay() {
        if (floatingView != null) {
            try { windowManager.removeView(floatingView); } catch (Exception e) {}
            floatingView = null;
        }
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        FrameLayout mainContainer = new FrameLayout(this);
        mainContainer.setClickable(true);
        mainContainer.setFocusable(true);
        
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        View controlView = inflater.inflate(R.layout.overlay_layout, null);
        
        tvStatus = controlView.findViewById(R.id.tvStatus);
        tvCountdown = controlView.findViewById(R.id.tvCountdown);
        tvCycle = controlView.findViewById(R.id.tvCycle);
        tvOcrResult = controlView.findViewById(R.id.tvOcrResult);
        tvRate = controlView.findViewById(R.id.tvRate);
        spinnerConsumption = controlView.findViewById(R.id.spinnerConsumption);
        btnSwipePlace = controlView.findViewById(R.id.btnSwipePlace);
        btnRefillPlace = controlView.findViewById(R.id.btnRefillPlace);
        btnOcrNow = controlView.findViewById(R.id.btnOcrNow);
        btnSwipeTest = controlView.findViewById(R.id.btnSwipeTest);
        btnRefillTest = controlView.findViewById(R.id.btnRefillTest);
        btnStopAuto = controlView.findViewById(R.id.btnStopAuto);
        btnStartAuto = controlView.findViewById(R.id.btnStartAuto);
        btnClose = controlView.findViewById(R.id.btnClose);
        btnCrop = controlView.findViewById(R.id.btnCrop);
        
        btnCrop.setOnClickListener(v -> {
            if (cropButtonClicked) {
                return;
            }
            cropButtonClicked = true;
            startCropMode();
            handler.postDelayed(() -> {
                cropButtonClicked = false;
            }, 500);
        });
        
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
            
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                TextView text = (TextView) view;
                text.setTextColor(Color.WHITE);
                text.setBackgroundColor(Color.parseColor("#333333"));
                text.setTextSize(14);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerConsumption.setAdapter(adapter);
        
        spinnerConsumption.setSelection(3);
        isAutoRefillSelected = true;
        
        spinnerConsumption.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentModeIndex = position;
                isAutoRefillSelected = (position == 3);
                prefs.edit().putInt("consumption_index", position).apply();
                Toast.makeText(OverlayService.this, 
                    "📊 " + CONSUMPTION_LABELS[position], 
                    Toast.LENGTH_SHORT).show();
                if (isAutoRefillSelected) {
                    Toast.makeText(OverlayService.this, "♻️ AUTOREFILL-Modus aktiviert! (Refill bei ≤ 0,30 GB)", Toast.LENGTH_LONG).show();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
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
        
        btnOcrNow.setOnClickListener(v -> {
            if (!isScreenshotReady) {
                Toast.makeText(this, "⚠️ Screenshot noch nicht bereit", Toast.LENGTH_SHORT).show();
                return;
            }
            if (isCropMode) {
                Toast.makeText(this, "⚠️ Crop-Modus aktiv! Beende zuerst", Toast.LENGTH_SHORT).show();
                return;
            }
            refillState = RefillState.CHECK_VOLUME;
            performScreenshotAndOcr();
        });
        
        btnSwipeTest.setOnClickListener(v -> {
            if (!swipePlaced) {
                Toast.makeText(this, "❌ Swipe nicht platziert!", Toast.LENGTH_LONG).show();
                return;
            }
            updateStatus("🔄 Swipe Test...");
            performSwipeTestGesture();
        });
        
        btnRefillTest.setOnClickListener(v -> {
            if (!refillPlaced) {
                Toast.makeText(this, "❌ Refill nicht platziert!", Toast.LENGTH_LONG).show();
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
            if (isCropMode) {
                Toast.makeText(this, "⚠️ Crop-Modus aktiv! Beende zuerst", Toast.LENGTH_SHORT).show();
                return;
            }
            if (isAutoRefillSelected) {
                startAutoRefill();
            } else {
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
                isAutoRefillMode = false;
                startAutomation();
            }
        });
        
        btnClose.setOnClickListener(v -> {
            stopAutomation();
            hideVisuals();
            removeCropFrame();
            stopCountdownThread();
            if (isCropMode) {
                isCropMode = false;
                cropAutoCloseHandler.removeCallbacks(cropAutoCloseRunnable);
                if (cropOverlayView != null) {
                    try { windowManager.removeView(cropOverlayView); } catch (Exception e) {}
                    cropOverlayView = null;
                }
            }
            savePositions();
            if (floatingView != null && windowManager != null) {
                try { windowManager.removeView(floatingView); } catch (Exception e) {}
            }
            stopSelf();
        });
        
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
    
    // ============ VISUAL HELPERS ============
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
                canvas.drawCircle(size/2, size/2, size/2 - 4, paint);
                Paint borderPaint = new Paint();
                borderPaint.setColor(Color.WHITE);
                borderPaint.setStrokeWidth(4);
                borderPaint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(size/2, size/2, size/2 - 4, borderPaint);
                Paint textPaint = new Paint();
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(size * 0.5f);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("R", size/2, size/2 + size * 0.18f, textPaint);
            }
        };
        
        hideVisuals();
    }
    
    private void showSwipeVisual() { addVisual(swipeVisual, 100, 250); }
    private void showRefillVisual() { addVisual(refillVisual, 150, 150); }
    
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
                                refillButton.set(params.x + 75, params.y + 75);
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
    
    // ============ COUNTDOWN THREAD VARIABLEN ============
    private Thread countdownThread = null;
    private volatile boolean countdownRunning = false;
    private Runnable countdownCallback = null;
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        stopCountdownThread();
        savePositions();
        hideVisuals();
        removeCropFrame();
        cropAutoCloseHandler.removeCallbacks(cropAutoCloseRunnable);
        if (floatingView != null && windowManager != null) {
            try { windowManager.removeView(floatingView); } catch (Exception e) {}
        }
        if (cropOverlayView != null) {
            try { windowManager.removeView(cropOverlayView); } catch (Exception e) {}
            cropOverlayView = null;
        }
        handler.removeCallbacksAndMessages(null);
        if (textRecognizer != null) {
            textRecognizer.close();
        }
        if (lastScreenshotFile != null && lastScreenshotFile.exists()) {
            lastScreenshotFile.delete();
        }
    }
}
