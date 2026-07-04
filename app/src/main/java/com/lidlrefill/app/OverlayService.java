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
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OverlayService extends AccessibilityService {
    private static final String TAG = "LidlRefill";
    
    private WindowManager windowManager;
    private FrameLayout floatingView;
    private TextView tvStatus, tvCountdown, tvCycle, tvOcrResult;
    private Spinner spinnerConsumption;
    private Button btnSwipePlace, btnRefillPlace, btnOcrNow;
    private Button btnSwipeTest, btnRefillTest, btnStopAuto, btnStartAuto;
    private Button btnClose, btnCrop;
    
    private SharedPreferences prefs;
    private static final String PREF_SWIPE_START_X = "swipe_start_x";
    private static final String PREF_SWIPE_START_Y = "swipe_start_y";
    private static final String PREF_SWIPE_END_X = "swipe_end_x";
    private static final String PREF_SWIPE_END_Y = "swipe_end_y";
    private static final String PREF_REFILL_X = "refill_x";
    private static final String PREF_REFILL_Y = "refill_y";
    private static final String PREF_SWIPE_PLACED = "swipe_placed";
    private static final String PREF_REFILL_PLACED = "refill_placed";
    
    // ============ CROP-KOORDINATEN ============
    private static final String PREF_CROP_LEFT = "crop_left";
    private static final String PREF_CROP_TOP = "crop_top";
    private static final String PREF_CROP_RIGHT = "crop_right";
    private static final String PREF_CROP_BOTTOM = "crop_bottom";
    
    private int screenWidth, screenHeight;
    private boolean isScreenshotReady = false;
    private File lastScreenshotFile = null;
    private String lastOcrText = "";
    private boolean isProcessing = false;
    private long screenshotTime = 0;
    
    // ============ SCREENSHOT-ORDNER ============
    private File[] screenshotFolders = null;
    private String foundFolderPath = "";
    
    // ============ OCR ============
    private TextRecognizer textRecognizer;
    private String ocrResult = "📸 OCR: --";
    private double lastDetectedVolume = 0.0;
    
    private Point swipeStart = new Point(0, 0);
    private Point swipeEnd = new Point(0, 0);
    private boolean swipePlaced = false;
    private Point refillButton = new Point(500, 500);
    private boolean refillPlaced = false;
    
    // ============ CROP ============
    private int cropLeft = 0;
    private int cropTop = 0;
    private int cropRight = 0;
    private int cropBottom = 0;
    private boolean cropSet = false;
    
    // ============ CROP-MODUS ============
    private boolean isCropMode = false;
    private View cropOverlayView = null;
    private float cropStartX = 0, cropStartY = 0;
    private float cropEndX = 0, cropEndY = 0;
    private boolean isDrawingCrop = false;
    
    private enum Mode { NONE, SWIPE_PLACE, REFILL_PLACE }
    private Mode currentMode = Mode.NONE;
    
    private float lastX, lastY;
    private float dragOffsetX, dragOffsetY;
    private View activeVisual = null;
    private View swipeVisual, refillVisual;
    
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private boolean isAutoRefillMode = false;
    private boolean isOverlayDragging = false;
    private float overlayDragX, overlayDragY;
    
    // ============ VERBRAUCHS-OPTIONEN ============
    private static final String[] CONSUMPTION_LABELS = {
        "📱 Surfen (18-22 Min)",
        "📺 FullHD (11-14 Min)",
        "🎬 4K (6-9 Min)",
        "♻️ AUTOREFILL (0,35 GB)"
    };
    private int currentModeIndex = 0;
    private boolean isAutoRefillSelected = false;
    
    // ============ ZEITEN ============
    private static final long WAIT_AFTER_SWIPE_MIN = 9000;
    private static final long WAIT_AFTER_SWIPE_MAX = 11000;
    private static final long WAIT_AFTER_REFILL_MIN = 9000;
    private static final long WAIT_AFTER_REFILL_MAX = 11000;
    private static final long AUTOREFILL_WAIT_MIN = 300000;
    private static final long AUTOREFILL_WAIT_MAX = 480000;
    private static final long AUTOREFILL_WAIT_MEDIUM_MIN = 120000;
    private static final long AUTOREFILL_WAIT_MEDIUM_MAX = 180000;
    
    private static final double AUTOREFILL_THRESHOLD = 0.35;
    private static final double AUTOREFILL_MEDIUM_THRESHOLD_MIN = 0.40;
    private static final double AUTOREFILL_MEDIUM_THRESHOLD_MAX = 0.45;
    
    private int cycleCount = 0;
    private int totalSwipes = 0;
    private long currentWaitTime = 900000;
    private long countdownStartTime = 0;
    private boolean isWaiting = false;
    
    private enum Phase { IDLE, SWIPE, WAIT_AFTER_SWIPE, OCR, WAIT_AFTER_OCR, REFILL, WAIT_AFTER_REFILL }
    private Phase currentPhase = Phase.IDLE;
    
    private Random random = new Random();
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
        
        int savedIndex = prefs.getInt("consumption_index", 0);
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
                Log.d(TAG, "📁 Gefundener Ordner: " + foundFolderPath);
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
            Log.d(TAG, "📐 Geladene Crop-Koordinaten: left=" + cropLeft + ", top=" + cropTop + ", right=" + cropRight + ", bottom=" + cropBottom);
        } else {
            cropSet = false;
            cropLeft = screenWidth / 4;
            cropTop = screenHeight / 4;
            cropRight = screenWidth * 3 / 4;
            cropBottom = screenHeight * 2 / 3;
            Log.d(TAG, "📐 Standard-Crop-Koordinaten gesetzt");
        }
    }
    
    private void saveCropCoordinates() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(PREF_CROP_LEFT, cropLeft);
        editor.putInt(PREF_CROP_TOP, cropTop);
        editor.putInt(PREF_CROP_RIGHT, cropRight);
        editor.putInt(PREF_CROP_BOTTOM, cropBottom);
        editor.apply();
        cropSet = true;
        Log.d(TAG, "💾 Crop-Koordinaten gespeichert: " + cropLeft + "," + cropTop + " - " + cropRight + "," + cropBottom);
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
    
    // ============ CROP-MODUS (OHNE VERDUNKELUNG) ============
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
        Toast.makeText(this, "✂️ Ziehe den Bereich, der beim OCR ausgelesen werden soll", Toast.LENGTH_LONG).show();
        createCropOverlay();
    }
    
    private void createCropOverlay() {
        // ===== KOMPLETT TRANSPARENTER HINTERGRUND (KEINE VERDUNKELUNG) =====
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.TRANSPARENT);
        
        // ===== VIEW ZUM ZIEHEN =====
        cropOverlayView = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (isDrawingCrop) {
                    float left = Math.min(cropStartX, cropEndX);
                    float top = Math.min(cropStartY, cropEndY);
                    float right = Math.max(cropStartX, cropEndX);
                    float bottom = Math.max(cropStartY, cropEndY);
                    
                    // Koordinaten anzeigen
                    Paint textPaint = new Paint();
                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(30);
                    textPaint.setStyle(Paint.Style.FILL);
                    textPaint.setShadowLayer(5, 0, 0, Color.BLACK);
                    
                    String coords = (int)left + "," + (int)top + " - " + (int)right + "," + (int)bottom;
                    String size = "Größe: " + (int)(right-left) + "x" + (int)(bottom-top);
                    canvas.drawText(coords, left + 10, top + 50, textPaint);
                    canvas.drawText(size, left + 10, top + 100, textPaint);
                    
                    // Dezente Umrandung
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
            Log.e(TAG, "Fehler beim Hinzufügen des Crop-Overlays: " + e.getMessage());
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
            .addOnSuccessListener(new OnSuccessListener<com.google.mlkit.vision.text.Text>() {
                @Override
                public void onSuccess(com.google.mlkit.vision.text.Text visionText) {
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
                        lastDetectedVolume = Double.parseDouble(volume.replace(",", "."));
                        ocrResult = "📸 " + volume + " GB";
                        updateOcrResult(ocrResult);
                        updateStatus("📸 OCR: " + volume + " GB");
                        Toast.makeText(OverlayService.this, "📸 OCR: " + volume + " GB", Toast.LENGTH_LONG).show();
                        
                        if (isAutoRefillSelected || isAutoRefillMode) {
                            handleAutoRefillLogic(lastDetectedVolume);
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
                }
            })
            .addOnFailureListener(new OnFailureListener() {
                @Override
                public void onFailure(Exception e) {
                    isProcessing = false;
                    updateStatus("❌ OCR Fehler: " + e.getMessage());
                    updateOcrResult("❌ OCR Fehler");
                    Toast.makeText(OverlayService.this, "❌ OCR Fehler: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    scaledBitmap.recycle();
                    screenshot.recycle();
                }
            });
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
            "([0-9]+[\\.,][0-9]{2})",
            "(\\d+)\\s*(GB|Gb|gB|gb)",
            "(\\d+[\\.,]\\d+)\\s*(GB|Gb|gB|gb)",
            "Verfügbares Gesamtvolumen[\\s\\S]*?(\\d+[\\.,]?\\d*)",
            "Volumen[\\s\\S]*?(\\d+[\\.,]?\\d*)\\s*(GB|Gb|gB|gb)",
            "Gesamtvolumen[\\s\\S]*?(\\d+[\\.,]?\\d*)"
        };
        
        for (String patternStr : patterns) {
            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String value = matcher.group(1).replace(",", ".");
                try {
                    double val = Double.parseDouble(value);
                    Log.d(TAG, "🔍 Pattern gefunden: " + value);
                    if (val > 0 && val < 10) {
                        return value;
                    }
                } catch (Exception e) {}
            }
        }
        
        Pattern specialPattern = Pattern.compile("(\\d+[\\.,]\\d+)\\s*GB", Pattern.CASE_INSENSITIVE);
        Matcher specialMatcher = specialPattern.matcher(text);
        if (specialMatcher.find()) {
            String value = specialMatcher.group(1).replace(",", ".");
            try {
                double val = Double.parseDouble(value);
                if (val > 0 && val < 10) {
                    return value;
                }
            } catch (Exception e) {}
        }
        
        Pattern standalonePattern = Pattern.compile("([0-9]+[\\.,][0-9]{2})");
        Matcher standaloneMatcher = standalonePattern.matcher(text);
        if (standaloneMatcher.find()) {
            String value = standaloneMatcher.group(1).replace(",", ".");
            try {
                double val = Double.parseDouble(value);
                if (val > 0 && val < 10) {
                    return value;
                }
            } catch (Exception e) {}
        }
        
        Log.d(TAG, "❌ Kein GB-Wert gefunden");
        return null;
    }
    
    // ============ AUTOREFILL LOGIK ============
    private void handleAutoRefillLogic(double volume) {
        if (!isRunning) return;
        
        Log.d(TAG, "♻️ AUTOREFILL: Erkanntes Volumen = " + volume + " GB");
        
        if (volume > 0.45) {
            long waitTime = AUTOREFILL_WAIT_MIN + (long)(random.nextDouble() * (AUTOREFILL_WAIT_MAX - AUTOREFILL_WAIT_MIN));
            long minutes = waitTime / 60000;
            updateStatus("♻️ Warte " + minutes + " Min (Volumen > 0,45)");
            Toast.makeText(this, "♻️ Volumen > 0,45 GB → Warte " + minutes + " Min", Toast.LENGTH_SHORT).show();
            currentPhase = Phase.WAIT_AFTER_OCR;
            startCountdown(waitTime, () -> {
                if (isRunning) {
                    currentPhase = Phase.SWIPE;
                    performSwipeGesture();
                }
            });
            
        } else if (volume >= AUTOREFILL_MEDIUM_THRESHOLD_MIN && volume <= AUTOREFILL_MEDIUM_THRESHOLD_MAX) {
            long waitTime = AUTOREFILL_WAIT_MEDIUM_MIN + (long)(random.nextDouble() * (AUTOREFILL_WAIT_MEDIUM_MAX - AUTOREFILL_WAIT_MEDIUM_MIN));
            long minutes = waitTime / 60000;
            updateStatus("♻️ Warte " + minutes + " Min (0,40-0,45)");
            Toast.makeText(this, "♻️ Volumen 0,40-0,45 GB → Warte " + minutes + " Min", Toast.LENGTH_SHORT).show();
            currentPhase = Phase.WAIT_AFTER_OCR;
            startCountdown(waitTime, () -> {
                if (isRunning) {
                    currentPhase = Phase.SWIPE;
                    performSwipeGesture();
                }
            });
            
        } else if (volume <= AUTOREFILL_THRESHOLD) {
            updateStatus("♻️ Volumen ≤ 0,35 → Refill");
            Toast.makeText(this, "♻️ Volumen ≤ 0,35 → Refill wird gedrückt", Toast.LENGTH_SHORT).show();
            currentPhase = Phase.REFILL;
            handler.postDelayed(() -> {
                if (isRunning) {
                    clickRefillButton();
                }
            }, 1000);
        } else {
            long waitTime = AUTOREFILL_WAIT_MIN + (long)(random.nextDouble() * (AUTOREFILL_WAIT_MAX - AUTOREFILL_WAIT_MIN));
            long minutes = waitTime / 60000;
            updateStatus("♻️ Warte " + minutes + " Min (0,35-0,40)");
            Toast.makeText(this, "♻️ Volumen 0,35-0,40 GB → Warte " + minutes + " Min", Toast.LENGTH_SHORT).show();
            currentPhase = Phase.WAIT_AFTER_OCR;
            startCountdown(waitTime, () -> {
                if (isRunning) {
                    currentPhase = Phase.SWIPE;
                    performSwipeGesture();
                }
            });
        }
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
        
        Toast.makeText(this, "♻️ AUTOREFILL gestartet!", Toast.LENGTH_LONG).show();
        startAutomation();
    }
    
    // ============ POSITIONEN ============
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
    
    // ============ OVERLAY ============
    private void createOverlay() {
        // ... (Overlay-Code bleibt unverändert) ...
        // Ich lasse ihn hier aus Platzgründen weg, da er identisch ist
    }
    
    // ============ VISUELLE HILFEN ============
    private void createVisualHelpers() {
        // ... (Visual-Helpers bleiben unverändert) ...
    }
    
    private void showSwipeVisual() { addVisual(swipeVisual, 100, 250); }
    private void showRefillVisual() { addVisual(refillVisual, 80, 80); }
    
    private void addVisual(View visual, int width, int height) {
        // ... (addVisual bleibt unverändert) ...
    }
    
    private void hideVisuals() { removeVisual(swipeVisual); removeVisual(refillVisual); activeVisual = null; }
    private void removeVisual(View visual) { if (visual != null && visual.getParent() != null) { try { windowManager.removeView(visual); } catch (Exception e) {} } }
    
    private void updateStatus(String text) { if (tvStatus != null) tvStatus.setText(text); }
    private void updateCountdown(String text) { if (tvCountdown != null) tvCountdown.setText(text); }
    private void updateCycle() { if (tvCycle != null) tvCycle.setText("🔄 " + cycleCount + " Zyklen | ⬇ " + totalSwipes); }
    private void updateOcrResult(String text) { if (tvOcrResult != null) tvOcrResult.setText(text); }
    
    // ============ GESTEN ============
    private void performSwipeGesture() {
        // ... (performSwipeGesture bleibt unverändert) ...
    }
    
    private void clickRefillButton() {
        // ... (clickRefillButton bleibt unverändert) ...
    }
    
    // ============ COUNTDOWN ============
    private void startCountdown(long waitTime, Runnable onFinish) {
        // ... (Countdown bleibt unverändert) ...
    }
    
    private void startCountdown(long waitTime) {
        startCountdown(waitTime, null);
    }
    
    // ============ START AUTOMATION ============
    private void startAutomation() {
        // ... (startAutomation bleibt unverändert) ...
    }
    
    private void stopAutomation() {
        // ... (stopAutomation bleibt unverändert) ...
    }
    
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
