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
    private Button btnClose, btnAutoRefill;
    
    private SharedPreferences prefs;
    private static final String PREF_SWIPE_START_X = "swipe_start_x";
    private static final String PREF_SWIPE_START_Y = "swipe_start_y";
    private static final String PREF_SWIPE_END_X = "swipe_end_x";
    private static final String PREF_SWIPE_END_Y = "swipe_end_y";
    private static final String PREF_REFILL_X = "refill_x";
    private static final String PREF_REFILL_Y = "refill_y";
    private static final String PREF_SWIPE_PLACED = "swipe_placed";
    private static final String PREF_REFILL_PLACED = "refill_placed";
    
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
    
    // ============ KOORDINATEN FÜR TEILSCREENSHOT ============
    // Diese Werte müssen an dein Display angepasst werden!
    // Für 1080x2400 (FullHD+) sind das ungefähr:
    private int cropLeft = 200;
    private int cropTop = 500;
    private int cropRight = 800;
    private int cropBottom = 650;
    
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
    private static final long MIN_WAIT_AFTER_SWIPE = 8000;
    private static final long MAX_WAIT_AFTER_SWIPE = 10000;
    private static final long MIN_WAIT_AFTER_REFILL = 8000;
    private static final long MAX_WAIT_AFTER_REFILL = 10000;
    private static final long MIN_WAIT_TIME = 60000;
    private static final long MAX_WAIT_TIME = 1800000;
    private static final long AUTOREFILL_WAIT_MIN = 300000; // 5 Minuten
    private static final long AUTOREFILL_WAIT_MAX = 480000; // 8 Minuten
    private static final double AUTOREFILL_THRESHOLD = 0.35;
    
    private static final long[][] WAIT_RANGES = {
        {1080000, 1320000},
        {660000, 840000},
        {360000, 540000},
        {300000, 480000}
    };
    
    private int cycleCount = 0;
    private int totalSwipes = 0;
    private long currentWaitTime = 900000;
    private long countdownStartTime = 0;
    private boolean isWaiting = false;
    
    private enum Phase { IDLE, SWIPE_1, WAIT_AFTER_SWIPE_1, REFILL, WAIT_AFTER_REFILL, SWIPE_2, COUNTDOWN, AUTOREFILL_OCR, AUTOREFILL_WAIT }
    private Phase currentPhase = Phase.IDLE;
    
    private Random random = new Random();
    
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
        
        // ===== KOORDINATEN AN BILDGRÖSSE ANPASSEN =====
        adjustCropCoordinates();
        
        loadPositions();
        
        int savedIndex = prefs.getInt("consumption_index", 0);
        currentModeIndex = savedIndex;
        isAutoRefillSelected = (savedIndex == 3);
        
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        
        // ===== ALLE MÖGLICHEN SCREENSHOT-ORDNER =====
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
    
    // ============ KOORDINATEN AN BILDGRÖSSE ANPASSEN ============
    private void adjustCropCoordinates() {
        // Für 1080x2400 (FullHD+)
        if (screenWidth == 1080 && screenHeight == 2400) {
            cropLeft = 200;
            cropTop = 500;
            cropRight = 800;
            cropBottom = 650;
        }
        // Für 1440x3200 (WQHD+)
        else if (screenWidth == 1440 && screenHeight == 3200) {
            cropLeft = 300;
            cropTop = 650;
            cropRight = 1000;
            cropBottom = 850;
        }
        // Für 720x1600 (HD+)
        else if (screenWidth == 720 && screenHeight == 1600) {
            cropLeft = 150;
            cropTop = 350;
            cropRight = 550;
            cropBottom = 450;
        }
        // Standard-Fallback (prozentual)
        else {
            cropLeft = screenWidth / 5;
            cropTop = screenHeight / 5;
            cropRight = screenWidth * 4 / 5;
            cropBottom = screenHeight * 3 / 10;
        }
        
        Log.d(TAG, "📐 Crop-Koordinaten: " + cropLeft + "," + cropTop + " - " + cropRight + "," + cropBottom);
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
    
    // ============ NATIVE SCREENSHOT ============
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
    
    // ============ SCREENSHOT IN ALLEN ORDNERN SUCHEN ============
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
        
        // ===== TEILSCREENSHOT ERSTELLEN =====
        Bitmap croppedBitmap = createPartialScreenshot(fullBitmap);
        fullBitmap.recycle();
        
        if (croppedBitmap == null) {
            updateStatus("❌ Teilscreenshot fehlgeschlagen");
            updateOcrResult("❌ Cropping fehlgeschlagen");
            Toast.makeText(this, "❌ Teilscreenshot fehlgeschlagen!", Toast.LENGTH_LONG).show();
            isProcessing = false;
            return;
        }
        
        // ===== SCREENSHOT SPEICHERN (für Debug) =====
        saveScreenshotToInternalStorage(croppedBitmap);
        
        performOcrOnBitmap(croppedBitmap);
    }
    
    // ============ TEILSCREENSHOT ERSTELLEN ============
    private Bitmap createPartialScreenshot(Bitmap fullScreenshot) {
        try {
            // Koordinaten validieren
            int left = Math.max(0, cropLeft);
            int top = Math.max(0, cropTop);
            int right = Math.min(fullScreenshot.getWidth(), cropRight);
            int bottom = Math.min(fullScreenshot.getHeight(), cropBottom);
            
            // Mindestgröße prüfen
            if (right - left < 50 || bottom - top < 50) {
                Log.e(TAG, "❌ Teilscreenshot zu klein: " + (right-left) + "x" + (bottom-top));
                return null;
            }
            
            Log.d(TAG, "✂️ Teilscreenshot: " + left + "," + top + " - " + right + "," + bottom);
            Log.d(TAG, "✂️ Größe: " + (right-left) + "x" + (bottom-top));
            
            // Teilscreenshot erstellen
            return Bitmap.createBitmap(fullScreenshot, left, top, right - left, bottom - top);
        } catch (Exception e) {
            Log.e(TAG, "❌ Teilscreenshot Fehler: " + e.getMessage());
            return null;
        }
    }
    
    // ============ SCREENSHOT SPEICHERN (Debug) ============
    private void saveScreenshotToInternalStorage(Bitmap bitmap) {
        try {
            File tempDir = new File(getFilesDir(), "screenshots");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "cropped_" + timeStamp + ".jpg";
            lastScreenshotFile = new File(tempDir, fileName);
            
            FileOutputStream fos = new FileOutputStream(lastScreenshotFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
            fos.close();
            
            Log.d(TAG, "✅ Cropped Screenshot gespeichert: " + lastScreenshotFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Fehler beim Speichern: " + e.getMessage());
        }
    }
    
    // ============ SCREENSHOT LÖSCHEN ============
    private void deleteScreenshot() {
        if (lastScreenshotFile != null && lastScreenshotFile.exists()) {
            boolean deleted = lastScreenshotFile.delete();
            if (deleted) {
                Log.d(TAG, "🗑️ Screenshot gelöscht: " + lastScreenshotFile.getName());
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    android.content.ContentResolver resolver = getContentResolver();
                    android.net.Uri uri = android.net.Uri.fromFile(lastScreenshotFile);
                    resolver.delete(uri, null, null);
                    Log.d(TAG, "🗑️ Screenshot über ContentResolver gelöscht");
                } catch (Exception e) {
                    Log.e(TAG, "❌ Löschen fehlgeschlagen: " + e.getMessage());
                }
            }
            lastScreenshotFile = null;
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
        
        // Screenshot auf 2x vergrößern für bessere Erkennung
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
                    
                    deleteScreenshot();
                    isProcessing = false;
                    
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
                    deleteScreenshot();
                    isProcessing = false;
                    updateStatus("❌ OCR Fehler: " + e.getMessage());
                    updateOcrResult("❌ OCR Fehler");
                    Toast.makeText(OverlayService.this, "❌ OCR Fehler: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    scaledBitmap.recycle();
                    screenshot.recycle();
                }
            });
    }
    
    // ============ VERBESSERTE GB-EXTRACTION ============
    private String extractVolumeImproved(String text) {
        if (text == null || text.isEmpty()) {
            Log.d(TAG, "OCR Text ist leer");
            return null;
        }
        
        Log.d(TAG, "🔍 Suche nach GB-Wert in:\n" + text);
        
        // ===== PATTERNS FÜR ZAHLEN MIT KOMMA/PUNKT =====
        String[] patterns = {
            "(\\d+[\\.,]?\\d*)\\s*(GB|Gb|gB|gb)",
            "(\\d+[\\.,]?\\d*)(GB|Gb|gB|gb)",
            "(0[\\.,]\\d{2})",
            "(\\d+[\\.,]\\d+)\\s*(GB|Gb|gB|gb)",
            "(\\d+)\\s*(GB|Gb|gB|gb)"
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
        
        // ===== SPEZIALFALL: "0,73" in der Nähe von "GB" =====
        Pattern specialPattern = Pattern.compile(
            "(\\d+[\\.,]\\d+)\\s*GB",
            Pattern.CASE_INSENSITIVE
        );
        Matcher specialMatcher = specialPattern.matcher(text);
        if (specialMatcher.find()) {
            String value = specialMatcher.group(1).replace(",", ".");
            try {
                double val = Double.parseDouble(value);
                Log.d(TAG, "🔍 Spezialfall gefunden: " + value);
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
        
        if (volume > AUTOREFILL_THRESHOLD) {
            long waitTime = AUTOREFILL_WAIT_MIN + (long)(random.nextDouble() * (AUTOREFILL_WAIT_MAX - AUTOREFILL_WAIT_MIN));
            long minutes = waitTime / 60000;
            updateStatus("♻️ Warte " + minutes + " Min (Volumen > 0,35)");
            Toast.makeText(this, "♻️ Volumen > 0,35 GB → Warte " + minutes + " Min", Toast.LENGTH_SHORT).show();
            
            currentPhase = Phase.AUTOREFILL_WAIT;
            startCountdown(waitTime);
        } else {
            updateStatus("♻️ Volumen ≤ 0,35 → Refill");
            Toast.makeText(this, "♻️ Volumen ≤ 0,35 → Refill wird gedrückt", Toast.LENGTH_SHORT).show();
            
            currentPhase = Phase.REFILL;
            handler.postDelayed(() -> {
                if (isRunning) {
                    clickRefillButton();
                }
            }, 1000);
        }
    }
    
    // ============ AUTOREFILL STARTEN ============
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
        // ... (Overlay-Creation bleibt gleich wie vorher)
        // Der gesamte Overlay-Code ist identisch mit der vorherigen Version
        // Aus Platzgründen hier ausgelassen – bitte aus der vorherigen Antwort übernehmen
    }
    
    // ============ VISUELLE HILFEN ============
    private void createVisualHelpers() {
        // ... (Visual-Helpers bleiben gleich)
    }
    
    private void showSwipeVisual() { addVisual(swipeVisual, 100, 250); }
    private void showRefillVisual() { addVisual(refillVisual, 80, 80); }
    
    private void addVisual(View visual, int width, int height) {
        // ... (AddVisual bleibt gleich)
    }
    
    private void hideVisuals() { removeVisual(swipeVisual); removeVisual(refillVisual); activeVisual = null; }
    private void removeVisual(View visual) { if (visual != null && visual.getParent() != null) { try { windowManager.removeView(visual); } catch (Exception e) {} } }
    
    private void updateStatus(String text) { if (tvStatus != null) tvStatus.setText(text); }
    private void updateCountdown(String text) { if (tvCountdown != null) tvCountdown.setText(text); }
    private void updateCycle() { if (tvCycle != null) tvCycle.setText("🔄 " + cycleCount + " Zyklen | ⬇ " + totalSwipes); }
    private void updateOcrResult(String text) { if (tvOcrResult != null) tvOcrResult.setText(text); }
    
    // ============ GESTEN ============
    private void performSwipeGesture() {
        // ... (Swipe-Geste bleibt gleich)
    }
    
    private void clickRefillButton() {
        // ... (Refill-Button bleibt gleich)
    }
    
    // ============ COUNTDOWN ============
    private void startCountdown(long waitTime) {
        // ... (Countdown bleibt gleich)
    }
    
    // ============ WARTEZEIT ============
    private long calculateHumanWaitTime() {
        // ... (Wartezeit bleibt gleich)
    }
    
    private long randomWaitAfterSwipe() {
        return MIN_WAIT_AFTER_SWIPE + (long)(random.nextDouble() * (MAX_WAIT_AFTER_SWIPE - MIN_WAIT_AFTER_SWIPE));
    }
    
    private long randomWaitAfterRefill() {
        return MIN_WAIT_AFTER_REFILL + (long)(random.nextDouble() * (MAX_WAIT_AFTER_REFILL - MIN_WAIT_AFTER_REFILL));
    }
    
    // ============ AUTOMATIK ============
    private void startAutomation() {
        // ... (Automation bleibt gleich)
    }
    
    private void stopAutomation() {
        // ... (Stop bleibt gleich)
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
        if (textRecognizer != null) {
            textRecognizer.close();
        }
        if (lastScreenshotFile != null && lastScreenshotFile.exists()) {
            lastScreenshotFile.delete();
        }
    }
}
