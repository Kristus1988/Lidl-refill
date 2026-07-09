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
    private TextView tvStatus, tvCountdown, tvCycle, tvOcrResult;
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
    private double averageConsumptionRate = 0.03;
    
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
    
    // ============ CROP ============
    private int cropLeft = 0, cropTop = 0, cropRight = 0, cropBottom = 0;
    private View cropOverlayView = null;
    private float cropStartX = 0, cropStartY = 0;
    private float cropEndX = 0, cropEndY = 0;
    private boolean isDrawingCrop = false;
    
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
    private static final long WAIT_AFTER_REFILL_MIN = 8000;
    private static final long WAIT_AFTER_REFILL_MAX = 14000;
    private static final long AUTOREFILL_WAIT_MIN = 240000;
    private static final long AUTOREFILL_WAIT_MAX = 540000;
    
    // ============ NEUE STUFEN MIT PUFFERN ============
    private static final double[][] STAGES = {
        {50.00, 25.00},   // Stufe 1: > 50 GB → Ziel 25 GB
        {25.00, 10.00},   // Stufe 2: > 25 GB → Ziel 10 GB
        {10.00, 5.00},    // Stufe 3: > 10 GB → Ziel 5 GB
        {5.00, 1.00},     // Stufe 4: > 5 GB → Ziel 1 GB
        {1.00, 0.30}      // Stufe 5: > 1 GB → Ziel 0,30 GB
    };
    
    private static final double REFILL_THRESHOLD_HIGH = 0.50;   // Bei viel Verbrauch → Refill bei ≤ 0,50
    private static final double REFILL_THRESHOLD_LOW = 0.30;    // Bei wenig Verbrauch → Refill bei ≤ 0,30
    
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
    private Runnable countdownCallback = null;
    
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
            isCropMode = false;
            if (cropOverlayView != null) {
                try { windowManager.removeView(cropOverlayView); } catch (Exception e) {}
                cropOverlayView = null;
            }
            updateStatus("● Crop-Modus beendet");
            Toast.makeText(this, "✂️ Crop-Modus beendet", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "❌ Overlay-Berechtigung fehlt!", Toast.LENGTH_LONG).show();
            return;
        }
        
        isCropMode = true;
        updateStatus("✂️ Ziehe Bereich für OCR");
        Toast.makeText(this, "✂️ Ziehe den Bereich für den Teilscreenshot", Toast.LENGTH_LONG).show();
        createCropOverlay();
    }
    
    private void createCropOverlay() {
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.TRANSPARENT);
        
        cropOverlayView = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (isDrawingCrop) {
                    float left = Math.min(cropStartX, cropEndX);
                    float top = Math.min(cropStartY, cropEndY);
                    float right = Math.max(cropStartX, cropEndX);
                    float bottom = Math.max(cropStartY, cropEndY);
                    
                    Paint textPaint = new Paint();
                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(30);
                    textPaint.setStyle(Paint.Style.FILL);
                    textPaint.setShadowLayer(5, 0, 0, Color.BLACK);
                    
                    String coords = (int)left + "," + (int)top + " - " + (int)right + "," + (int)bottom;
                    String size = "Größe: " + (int)(right-left) + "x" + (int)(bottom-top);
                    canvas.drawText(coords, left + 10, top + 50, textPaint);
                    canvas.drawText(size, left + 10, top + 100, textPaint);
                    
                    Paint borderPaint = new Paint();
                    borderPaint.setColor(Color.argb(100, 255, 255, 255));
                    borderPaint.setStrokeWidth(2);
                    borderPaint.setStyle(Paint.Style.STROKE);
                    canvas.drawRect(left, top, right, bottom, borderPaint);
                }
            }
        };
        
        cropOverlayView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    cropStartX = event.getRawX();
                    cropStartY = event.getRawY();
                    cropEndX = event.getRawX();
                    cropEndY = event.getRawY();
                    isDrawingCrop = true;
                    cropOverlayView.invalidate();
                    return true;
                    
                case MotionEvent.ACTION_MOVE:
                    cropEndX = event.getRawX();
                    cropEndY = event.getRawY();
                    cropOverlayView.invalidate();
                    return true;
                    
                case MotionEvent.ACTION_UP:
                    cropEndX = event.getRawX();
                    cropEndY = event.getRawY();
                    isDrawingCrop = false;
                    cropOverlayView.invalidate();
                    
                    int left = (int)Math.min(cropStartX, cropEndX);
                    int top = (int)Math.min(cropStartY, cropEndY);
                    int right = (int)Math.max(cropStartX, cropEndX);
                    int bottom = (int)Math.max(cropStartY, cropEndY);
                    
                    if (right - left > 50 && bottom - top > 50) {
                        cropLeft = left;
                        cropTop = top;
                        cropRight = right;
                        cropBottom = bottom;
                        saveCropCoordinates();
                        Toast.makeText(OverlayService.this, 
                            "✅ Crop gespeichert!\n" + left + "," + top + " - " + right + "," + bottom, 
                            Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(OverlayService.this, "⚠️ Bereich zu klein!", Toast.LENGTH_LONG).show();
                        return true;
                    }
                    
                    handler.postDelayed(() -> {
                        isCropMode = false;
                        if (cropOverlayView != null) {
                            try { windowManager.removeView(cropOverlayView); } catch (Exception e) {}
                            cropOverlayView = null;
                        }
                        updateStatus("● Crop-Modus beendet");
                    }, 500);
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
            isCropMode = false;
            if (cropOverlayView != null) {
                try { windowManager.removeView(cropOverlayView); } catch (Exception e) {}
                cropOverlayView = null;
            }
            handler.postDelayed(() -> performScreenshotAndOcr(), 300);
            return;
        }
        
        isProcessing = true;
        screenshotTime = System.currentTimeMillis();
        
        updateStatus("📸 Native Screenshot wird ausgelöst...");
        updateOcrResult("📸 Screenshot...");
        
        performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
        Log.d(TAG, "✅ Native Screenshot wurde ausgelöst");
        
        updateStatus("⏳ Warte auf Screenshot (5-15 Sekunden)...");
        
        handler.postDelayed(() -> {
            findScreenshotInAllFolders(1);
        }, 5000);
    }
    
    // ============ SCREENSHOT FINDEN ============
    private void findScreenshotInAllFolders(int attempt) {
        if (attempt > 20) {
            updateStatus("❌ Screenshot nicht gefunden (25s)");
            updateOcrResult("❌ Zeitüberschreitung");
            Toast.makeText(this, "❌ Screenshot nicht gefunden nach 25 Sekunden!", Toast.LENGTH_LONG).show();
            isProcessing = false;
            return;
        }
        
        Log.d(TAG, "🔍 Suche nach Screenshot (Versuch " + attempt + "/20)");
        updateStatus("🔍 Suche nach Screenshot (" + attempt + "/20)...");
        
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
            handler.postDelayed(() -> {
                findScreenshotInAllFolders(attempt + 1);
            }, 1000);
            return;
        }
        
        lastScreenshotFile = latestFile;
        long waitTime = (System.currentTimeMillis() - screenshotTime) / 1000;
        Log.d(TAG, "📸 Screenshot gefunden nach " + waitTime + "s: " + latestFile.getAbsolutePath());
        updateStatus("📸 Screenshot gefunden nach " + waitTime + "s");
        
        Bitmap fullBitmap = BitmapFactory.decodeFile(latestFile.getAbsolutePath());
        if (fullBitmap == null) {
            updateStatus("❌ Screenshot konnte nicht geladen werden");
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
            
            return Bitmap.createBitmap(fullScreenshot, left, top, right - left, bottom - top);
        } catch (Exception e) {
            Log.e(TAG, "❌ Teilscreenshot Fehler: " + e.getMessage());
            return null;
        }
    }
    
    // ============ OCR ============
    private void performOcrOnBitmap(Bitmap screenshot) {
        if (screenshot == null) {
            updateStatus("❌ Bitmap ist null");
            updateOcrResult("❌ Bitmap null");
            isProcessing = false;
            return;
        }
        
        updateStatus("📸 OCR wird ausgeführt...");
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
                    updateStatus("📸 OCR: " + volume + " GB");
                    Toast.makeText(OverlayService.this, "📸 OCR: " + volume + " GB", Toast.LENGTH_LONG).show();
                    
                    if (isAutoRefillSelected || isAutoRefillMode) {
                        handleAutoRefillLogic(currentVolume);
                    }
                } else {
                    lastDetectedVolume = 0.0;
                    ocrResult = "📸 Kein GB-Wert";
                    updateOcrResult(ocrResult);
                    updateStatus("📸 Kein GB-Wert");
                    Toast.makeText(OverlayService.this, "⚠️ Kein GB-Wert gefunden", Toast.LENGTH_LONG).show();
                    
                    if (isAutoRefillSelected || isAutoRefillMode) {
                        Toast.makeText(OverlayService.this, "♻️ Kein Wert erkannt → Refill", Toast.LENGTH_SHORT).show();
                        handler.postDelayed(() -> {
                            if (isRunning) {
                                clickRefillButton();
                            }
                        }, 1000);
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
            averageConsumptionRate = Math.max(0.005, Math.min(0.15, averageConsumptionRate));
            Log.d(TAG, "📊 Neue Verbrauchsrate: " + averageConsumptionRate + " GB/Min");
        }
    }
    
    // ============ GB-EXTRACTION ============
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
    
    // ============ NEUE ADAPTIVE AUTOREFILL LOGIK MIT STUFEN ============
    private void handleAutoRefillLogic(double volume) {
        if (!isRunning) return;
        
        Log.d(TAG, "♻️ AUTOREFILL: Erkanntes Volumen = " + volume + " GB");
        
        // ===== STUFE 1: > 50 GB → Ziel 25 GB =====
        if (volume > 50.00) {
            calculateWaitTime(volume, 25.00);
            return;
        }
        
        // ===== STUFE 2: > 25 GB → Ziel 10 GB =====
        if (volume > 25.00) {
            calculateWaitTime(volume, 10.00);
            return;
        }
        
        // ===== STUFE 3: > 10 GB → Ziel 5 GB =====
        if (volume > 10.00) {
            calculateWaitTime(volume, 5.00);
            return;
        }
        
        // ===== STUFE 4: > 5 GB → Ziel 1 GB =====
        if (volume > 5.00) {
            calculateWaitTime(volume, 1.00);
            return;
        }
        
        // ===== STUFE 5: > 1 GB → Ziel 0,30 GB =====
        if (volume > 1.00) {
            // Bei viel Verbrauch (Rate > 0,04 GB/Min) → Refill bei 0,50
            // Bei wenig Verbrauch (Rate ≤ 0,04 GB/Min) → Refill bei 0,30
            double threshold = (averageConsumptionRate > 0.04) ? REFILL_THRESHOLD_HIGH : REFILL_THRESHOLD_LOW;
            
            if (volume > threshold) {
                calculateWaitTime(volume, threshold);
            } else {
                // Volume ist unterhalb der Schwelle → Refill
                updateStatus("♻️ Volumen ≤ " + threshold + " → Refill");
                Toast.makeText(this, "♻️ Volumen ≤ " + threshold + " → Refill wird gedrückt", Toast.LENGTH_SHORT).show();
                currentPhase = Phase.REFILL;
                handler.postDelayed(() -> {
                    if (isRunning) {
                        clickRefillButton();
                    }
                }, 1000);
            }
            return;
        }
        
        // ===== ≤ 1 GB → Refill bei ≤ 0,30 =====
        if (volume <= 0.30) {
            updateStatus("♻️ Volumen ≤ 0,30 → Refill");
            Toast.makeText(this, "♻️ Volumen ≤ 0,30 → Refill wird gedrückt", Toast.LENGTH_SHORT).show();
            currentPhase = Phase.REFILL;
            handler.postDelayed(() -> {
                if (isRunning) {
                    clickRefillButton();
                }
            }, 1000);
        } else {
            // Zwischen 0,30 und 1,00 → warten bis 0,30
            calculateWaitTime(volume, 0.30);
        }
    }
    
    private void calculateWaitTime(double currentVolume, double targetVolume) {
        double diff = currentVolume - targetVolume;
        double consumptionRate = averageConsumptionRate;
        
        if (consumptionRate <= 0.001 || consumptionRate > 0.2) {
            switch (currentModeIndex) {
                case 0: consumptionRate = 0.025; break;
                case 1: consumptionRate = 0.04; break;
                case 2: consumptionRate = 0.06; break;
                case 3: 
                default: consumptionRate = 0.03; break;
            }
            Log.d(TAG, "📊 Fallback auf Standardrate: " + consumptionRate);
        }
        
        double minutesDouble = diff / consumptionRate;
        double randomFactor = 0.70 + (random.nextDouble() * 0.60);
        minutesDouble = minutesDouble * randomFactor;
        
        long minutes = Math.round(Math.max(4, Math.min(9, minutesDouble)));
        long waitTime = minutes * 60000;
        waitTime += (long)(random.nextDouble() * 60000);
        
        updateStatus("♻️ Adaptiv: " + minutes + " Min (Rate: " + 
            String.format("%.3f", consumptionRate) + " GB/Min)");
        Toast.makeText(this, 
            "♻️ Bis " + targetVolume + " GB: " + minutes + " Min (aktuell " + currentVolume + " GB)", 
            Toast.LENGTH_SHORT).show();
        
        currentPhase = Phase.WAIT_AFTER_OCR;
        startCountdown(waitTime, () -> {
            if (isRunning) {
                currentPhase = Phase.SWIPE;
                performSwipeGesture();
            }
        });
    }
    
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
        averageConsumptionRate = 0.03;
        
        Toast.makeText(this, "♻️ AUTOREFILL gestartet!", Toast.LENGTH_LONG).show();
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
    
    // ============ SWIPE TEST (JE NACH MODUS) ============
    private void performSwipeTestGesture() {
        if (!swipePlaced) {
            Toast.makeText(this, "❌ Swipe nicht platziert!", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // ===== WENN AUTOMATIK LÄUFT → KOMPLETTEN ABLAUF =====
        if (isRunning) {
            updateStatus("🔄 Automatik-Swipe Test...");
            // Führe die normale Swipe-Geste aus (mit Screenshot + OCR)
            performSwipeGesture();
            return;
        }
        
        // ===== WENN AUTOMATIK NICHT LÄUFT → NUR GESTE =====
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
    
    // ============ SWIPE GESTE (FÜR AUTOMATIK) ============
    private void performSwipeGesture() {
        if (!swipePlaced) {
            Toast.makeText(this, "❌ Swipe nicht platziert!", Toast.LENGTH_SHORT).show();
            return;
        }
        
        totalSwipes++;
        
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
            dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    updateStatus("✅ Swipe #" + totalSwipes);
                    updateCycle();
                    
                    if (!isRunning) return;
                    
                    if (isAutoRefillSelected || isAutoRefillMode) {
                        currentPhase = Phase.WAIT_AFTER_SWIPE;
                        long waitTime = WAIT_AFTER_SWIPE_MIN + 
                            (long)(random.nextDouble() * (WAIT_AFTER_SWIPE_MAX - WAIT_AFTER_SWIPE_MIN));
                        updateStatus("⏳ Warte nach Swipe");
                        startCountdown(waitTime, () -> {
                            if (isRunning) {
                                currentPhase = Phase.OCR;
                                updateStatus("♻️ OCR wird ausgeführt...");
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
            dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    updateStatus("✅ Refill geklickt!");
                    Toast.makeText(OverlayService.this, "✅ Refill-Button geklickt!", Toast.LENGTH_SHORT).show();
                    
                    if (!isRunning) return;
                    
                    if (isAutoRefillSelected || isAutoRefillMode) {
                        currentPhase = Phase.WAIT_AFTER_REFILL;
                        long waitTime = WAIT_AFTER_REFILL_MIN + 
                            (long)(random.nextDouble() * (WAIT_AFTER_REFILL_MAX - WAIT_AFTER_REFILL_MIN));
                        updateStatus("⏳ Warte nach Refill");
                        startCountdown(waitTime, () -> {
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
    
    // ============ COUNTDOWN ============
    private void startCountdown(long waitTime, Runnable onFinish) {
        isWaiting = true;
        countdownStartTime = System.currentTimeMillis();
        currentWaitTime = waitTime;
        countdownCallback = onFinish;
        
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
                    if (isRunning && countdownCallback != null) {
                        Runnable callback = countdownCallback;
                        countdownCallback = null;
                        callback.run();
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
    
    private void startCountdown(long waitTime) {
        startCountdown(waitTime, null);
    }
    
    // ============ AUTOMATIK ============
    private void startAutomation() {
        isRunning = true;
        cycleCount = 0;
        totalSwipes = 0;
        btnStartAuto.setText("▶ Läuft");
        btnStartAuto.setEnabled(false);
        btnStopAuto.setEnabled(true);
        updateStatus("🟢 Automatik läuft" + (isAutoRefillMode ? " ♻️" : ""));
        updateCycle();
        
        if (isAutoRefillSelected || isAutoRefillMode) {
            currentPhase = Phase.SWIPE;
            handler.postDelayed(() -> {
                if (isRunning) {
                    updateStatus("🔄 Starte mit Swipe...");
                    performSwipeGesture();
                }
            }, 2000);
        } else {
            currentPhase = Phase.SWIPE;
            handler.postDelayed(() -> {
                if (isRunning) {
                    updateStatus("🔄 Swipe...");
                    performSwipeGesture();
                }
            }, 2000);
        }
    }
    
    private void stopAutomation() {
        isRunning = false;
        isWaiting = false;
        isAutoRefillMode = false;
        currentPhase = Phase.IDLE;
        btnStartAuto.setText("▶ Start");
        btnStartAuto.setEnabled(true);
        btnStopAuto.setEnabled(false);
        updateStatus("● Gestoppt");
        updateCountdown("⏱ Warte: --:--");
        handler.removeCallbacksAndMessages(null);
    }
    
    // ============ UI HELPERS ============
    private void updateStatus(String text) { if (tvStatus != null) tvStatus.setText(text); }
    private void updateCountdown(String text) { if (tvCountdown != null) tvCountdown.setText(text); }
    private void updateCycle() { if (tvCycle != null) tvCycle.setText("🔄 " + cycleCount + " Zyklen | ⬇ " + totalSwipes); }
    private void updateOcrResult(String text) { if (tvOcrResult != null) tvOcrResult.setText(text); }
    
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
        
        btnCrop.setOnClickListener(v -> startCropMode());
        
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
                    Toast.makeText(OverlayService.this, "♻️ AUTOREFILL-Modus aktiviert!", Toast.LENGTH_LONG).show();
                }
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
        
        btnOcrNow.setOnClickListener(v -> {
            if (!isScreenshotReady) {
                Toast.makeText(this, "⚠️ Screenshot noch nicht bereit", Toast.LENGTH_SHORT).show();
                return;
            }
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
    private void showRefillVisual() { addVisual(refillVisual, 100, 100); }
    
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
                                refillButton.set(params.x + 50, params.y + 50);
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
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        savePositions();
        hideVisuals();
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
