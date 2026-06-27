package com.example.lidlrefill;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.Random;

public class RefillAccessibilityService extends AccessibilityService {

    private static final String TAG = "RefillService";
    private static final String PREFS_NAME = "RefillRecorderPrefs";
    private static final String KEY_REFILL_COUNT = "refill_count";

    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isMonitoring = false;
    private int refillCount = 0;

    private float buttonX = 0, buttonY = 0;
    private float volumeX = 0, volumeY = 0;
    private float volumeWidth = 0, volumeHeight = 0;
    private boolean isRecorded = false;

    private float currentVolume = 0.0f;
    private float lastVolume = 0.0f;
    private long lastCheckTime = 0;
    private float consumptionRate = 0.0f;
    private boolean isLearningPhase = true;
    private boolean isWaitingForRefill = false;
    private static final float TARGET_VOLUME = 0.35f;
    private Random random = new Random();

    // ✅ OCR
    private TextRecognizer textRecognizer;
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth, screenHeight;
    private boolean isOcrRunning = false;

    // Overlay
    private WindowManager windowManager;
    private View overlayView;
    private TextView tvStatus, tvVolume, tvRefills, tvNext;
    private Button btnPause, btnStop, btnRefresh;
    private boolean isOverlayVisible = false;
    private boolean isPaused = false;

    @Override
    public void onCreate() {
        super.onCreate();
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isMonitoring || isPaused || !isRecorded) return;

        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!pkg.toLowerCase().contains("lidl") && !pkg.toLowerCase().contains("connect")) {
            return;
        }

        Log.d(TAG, "📱 Lidl App erkannt");
        performActions();
    }

    private void performActions() {
        // 1. Volumen per OCR auslesen
        if (!isOcrRunning) {
            readVolumeWithOcr();
        }

        // 2. Refill ausführen wenn nötig
        if (shouldClick()) {
            clickRefillAtPosition();
        }
    }

    // ✅ OCR: VOLUMEN AUSLESEN
    private void readVolumeWithOcr() {
        if (isOcrRunning) return;
        isOcrRunning = true;

        Log.d(TAG, "🔍 Starte OCR-Volumen-Erkennung...");

        // Screenshot machen
        takeScreenshot(bitmap -> {
            if (bitmap == null) {
                Log.e(TAG, "❌ Screenshot fehlgeschlagen");
                isOcrRunning = false;
                return;
            }

            // Rechteck-Bereich aus dem Screenshot ausschneiden
            int x = (int) (volumeX * screenWidth);
            int y = (int) (volumeY * screenHeight);
            int width = (int) (volumeWidth * screenWidth);
            int height = (int) (volumeHeight * screenHeight);
            
            // Sicherstellen, dass der Bereich nicht außerhalb des Bildes liegt
            if (x < 0) x = 0;
            if (y < 0) y = 0;
            if (x + width > bitmap.getWidth()) width = bitmap.getWidth() - x;
            if (y + height > bitmap.getHeight()) height = bitmap.getHeight() - y;
            if (width <= 0 || height <= 0) {
                Log.e(TAG, "❌ Ungültiger Bereich für OCR");
                isOcrRunning = false;
                return;
            }

            Bitmap cropBitmap = Bitmap.createBitmap(bitmap, x, y, width, height);
            bitmap.recycle();

            // OCR durchführen
            InputImage image = InputImage.fromBitmap(cropBitmap, 0);
            textRecognizer.process(image)
                .addOnSuccessListener(this::onOcrSuccess)
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ OCR fehlgeschlagen: " + e.getMessage());
                    isOcrRunning = false;
                });
        });
    }

    private void onOcrSuccess(@NonNull Text text) {
        isOcrRunning = false;
        
        String fullText = text.getText();
        Log.d(TAG, "📝 OCR-Ergebnis: " + fullText);

        // Nach Zahlen mit "GB" suchen
        float volume = extractVolumeFromText(fullText);
        if (volume > 0) {
            currentVolume = volume;
            updateOverlay();
            handleVolumeUpdate();
            Log.d(TAG, "📊 Volumen per OCR: " + volume + " GB");
            showToast("📊 " + String.format("%.2f", volume) + " GB");
        } else {
            Log.d(TAG, "⚠️ Kein Volumen im OCR-Text gefunden");
        }
    }

    private float extractVolumeFromText(String text) {
        try {
            // Suche nach "X,XX GB" oder "X.XX GB"
            String[] parts = text.split(" ");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].replace(",", ".").trim();
                if (part.matches("\\d+(\\.\\d+)?")) {
                    float value = Float.parseFloat(part);
                    if (value > 0 && value < 100) {
                        // Prüfen ob das nächste Wort "GB" ist
                        if (i + 1 < parts.length && parts[i + 1].toUpperCase().contains("GB")) {
                            return value;
                        }
                        // Oder ob "GB" im gleichen Wort ist
                        if (part.toLowerCase().contains("gb")) {
                            return value;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Fehler beim Extrahieren der Zahl: " + e.getMessage());
        }
        return 0;
    }

    // ✅ SCREENSHOT MIT MEDIAPROJECTION
    private void takeScreenshot(OnBitmapReady callback) {
        if (mediaProjection == null) {
            Log.e(TAG, "❌ MediaProjection nicht initialisiert");
            callback.onBitmapReady(null);
            return;
        }

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "Screenshot",
                screenWidth, screenHeight, 1,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null
        );

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                Bitmap bitmap = imageToBitmap(image);
                image.close();
                callback.onBitmapReady(bitmap);
            } else {
                callback.onBitmapReady(null);
            }
        }, handler);
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * screenWidth;

        Bitmap bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        return Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
    }

    interface OnBitmapReady {
        void onBitmapReady(Bitmap bitmap);
    }

    // ✅ REFILL KLICKEN (NUR PIXEL-KOORDINATE)
    private void clickRefillAtPosition() {
        try {
            int x = (int) (buttonX * screenWidth);
            int y = (int) (buttonY * screenHeight);

            // Prüfen ob die Position sichtbar ist
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                Log.d(TAG, "⏳ Kein Root für Refill-Klick");
                return;
            }

            // Scrolle zur Position
            scrollToPosition(root, x, y);

            // Node an Position finden
            AccessibilityNodeInfo node = findNodeAt(root, x, y);
            if (node != null && node.isClickable()) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                executeRefill();
                Log.d(TAG, "✅ Refill-Button geklickt an Position (" + x + "," + y + ")");
                return;
            }

            // Fallback: Textsuche
            java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Refill aktivieren");
            for (AccessibilityNodeInfo n : nodes) {
                if (n.isClickable()) {
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    executeRefill();
                    Log.d(TAG, "✅ Refill-Button via Text gefunden");
                    return;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Fehler beim Refill-Klick: " + e.getMessage());
        }
    }

    // ✅ SCROLLEN ZU POSITION
    private void scrollToPosition(AccessibilityNodeInfo root, int targetX, int targetY) {
        try {
            AccessibilityNodeInfo node = findNodeAt(root, targetX, targetY);
            if (node != null) {
                Rect rect = new Rect();
                node.getBoundsInScreen(rect);
                if (rect.contains(targetX, targetY)) {
                    return;
                }
            }

            for (int i = 0; i < 15; i++) {
                root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                
                AccessibilityNodeInfo newRoot = getRootInActiveWindow();
                if (newRoot != null) {
                    node = findNodeAt(newRoot, targetX, targetY);
                    if (node != null) {
                        Rect rect = new Rect();
                        node.getBoundsInScreen(rect);
                        if (rect.contains(targetX, targetY)) {
                            return;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // 🔍 NODE AN POSITION FINDEN
    private AccessibilityNodeInfo findNodeAt(AccessibilityNodeInfo root, int x, int y) {
        return findNodeRecursive(root, x, y);
    }

    private AccessibilityNodeInfo findNodeRecursive(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return null;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.contains(x, y) && node.getText() != null && node.getText().length() > 0) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findNodeRecursive(child, x, y);
            if (result != null) return result;
        }
        return null;
    }

    private void handleVolumeUpdate() {
        if (isWaitingForRefill) {
            handler.postDelayed(() -> {
                isWaitingForRefill = false;
                Log.d(TAG, "⏳ 2 Min Wartezeit vorbei");
            }, 120000);
            return;
        }

        long now = System.currentTimeMillis();

        if (isLearningPhase) {
            if (lastCheckTime > 0) {
                float diff = lastVolume - currentVolume;
                long minutes = (now - lastCheckTime) / 1000 / 60;
                if (minutes > 0 && diff > 0) {
                    consumptionRate = diff / minutes;
                    if (consumptionRate > 0.001) {
                        isLearningPhase = false;
                        showToast("✅ Verbrauch: " + String.format("%.3f", consumptionRate) + " GB/min");
                    }
                }
            }
            lastVolume = currentVolume;
            lastCheckTime = now;
            updateOverlay();
            return;
        }

        if (consumptionRate > 0.001) {
            float remaining = currentVolume - TARGET_VOLUME;
            if (remaining <= 0) return;
            float minutes = remaining / consumptionRate;
            int delay = (int) (minutes * 60 * (0.8 + random.nextDouble() * 0.4));
            delay = Math.max(30, Math.min(delay, 1800));
            updateOverlay();
        }

        lastVolume = currentVolume;
        lastCheckTime = now;
        updateOverlay();
    }

    private boolean shouldClick() {
        return currentVolume <= TARGET_VOLUME && !isWaitingForRefill;
    }

    private void executeRefill() {
        refillCount++;
        isWaitingForRefill = true;
        isLearningPhase = true;

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_REFILL_COUNT, refillCount).apply();

        Log.d(TAG, "✅ Refill #" + refillCount + " ausgeführt!");
        showToast("✅ Refill #" + refillCount + " ausgeführt!");
        updateOverlay();

        handler.postDelayed(() -> {
            isWaitingForRefill = false;
            showToast("⏳ Prüfe neues Volumen...");
            updateOverlay();
        }, 120000);
    }

    // Overlay
    private void showOverlay() {
        if (isOverlayVisible) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        overlayView = inflater.inflate(R.layout.watermark_overlay, null);

        tvStatus = overlayView.findViewById(R.id.tv_overlay_status);
        tvVolume = overlayView.findViewById(R.id.tv_overlay_volume);
        tvRefills = overlayView.findViewById(R.id.tv_overlay_refills);
        tvNext = overlayView.findViewById(R.id.tv_overlay_next_check);
        btnPause = overlayView.findViewById(R.id.btn_overlay_pause);
        btnStop = overlayView.findViewById(R.id.btn_overlay_stop);
        btnRefresh = overlayView.findViewById(R.id.btn_overlay_refresh);

        btnPause.setOnClickListener(v -> {
            isPaused = !isPaused;
            btnPause.setText(isPaused ? "▶️" : "⏸️");
            tvStatus.setText(isPaused ? "⏸️ PAUSIERT" : "🔄 AKTIV");
            tvStatus.setTextColor(isPaused ? Color.parseColor("#FF9800") : Color.parseColor("#4CAF50"));
        });

        btnStop.setOnClickListener(v -> {
            isMonitoring = false;
            removeOverlay();
            showToast("⏹️ Gestoppt");
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("service_running", false).apply();
        });

        btnRefresh.setOnClickListener(v -> {
            showToast("🔄 Aktualisiere...");
            readVolumeWithOcr();
        });

        overlayView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    return true;
                case MotionEvent.ACTION_MOVE:
                    v.setX(v.getX() + event.getRawX() - event.getX());
                    v.setY(v.getY() + event.getRawY() - event.getY());
                    return true;
            }
            return false;
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20;
        params.y = 20;

        windowManager.addView(overlayView, params);
        isOverlayVisible = true;
        updateOverlay();
    }

    private void removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
                overlayView = null;
                isOverlayVisible = false;
            } catch (Exception ignored) {}
        }
    }

    private void updateOverlay() {
        if (!isOverlayVisible) return;
        if (tvStatus != null) {
            tvStatus.setText(isPaused ? "⏸️ PAUSIERT" : "🔄 AKTIV");
            tvStatus.setTextColor(isPaused ? Color.parseColor("#FF9800") : Color.parseColor("#4CAF50"));
        }
        if (tvVolume != null) tvVolume.setText("📊 " + String.format("%.2f", currentVolume) + " GB");
        if (tvRefills != null) tvRefills.setText("🔄 " + refillCount);
        if (tvNext != null) {
            if (isLearningPhase) {
                tvNext.setText("📖 Lernphase");
            } else if (consumptionRate > 0.001) {
                float remaining = currentVolume - TARGET_VOLUME;
                int seconds = (int) ((remaining / consumptionRate) * 60);
                seconds = Math.max(30, Math.min(seconds, 1800));
                tvNext.setText("⏱️ " + seconds + "s");
            } else {
                tvNext.setText("⏱️ --");
            }
        }
    }

    private void showToast(String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "⏹️ Unterbrochen");
        removeOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("action")) {
            String action = intent.getStringExtra("action");
            switch (action) {
                case "start_monitoring":
                    isMonitoring = true;
                    isPaused = false;
                    buttonX = intent.getFloatExtra("button_x", 0);
                    buttonY = intent.getFloatExtra("button_y", 0);
                    volumeX = intent.getFloatExtra("volume_x", 0);
                    volumeY = intent.getFloatExtra("volume_y", 0);
                    volumeWidth = intent.getFloatExtra("volume_width", 0.15f);
                    volumeHeight = intent.getFloatExtra("volume_height", 0.08f);
                    isRecorded = buttonX > 0 && volumeX > 0;
                    
                    // MediaProjection starten (Benötigt Berechtigung von MainActivity)
                    if (isRecorded) {
                        showOverlay();
                        handler.postDelayed(() -> {
                            performActions();
                        }, 1500);
                    }
                    Log.d(TAG, "▶️ Monitoring gestartet");
                    break;
                case "stop_monitoring":
                    isMonitoring = false;
                    handler.removeCallbacksAndMessages(null);
                    removeOverlay();
                    if (mediaProjection != null) {
                        mediaProjection.stop();
                        mediaProjection = null;
                    }
                    if (virtualDisplay != null) {
                        virtualDisplay.release();
                        virtualDisplay = null;
                    }
                    Log.d(TAG, "⏹️ Gestoppt");
                    break;
                case "refresh":
                    showToast("🔄 Aktualisiere...");
                    performActions();
                    break;
                case "media_projection":
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        mediaProjection = projectionManager.getMediaProjection(
                                intent.getIntExtra("resultCode", 0),
                                intent.getParcelableExtra("data")
                        );
                        Log.d(TAG, "📷 MediaProjection initialisiert");
                    }
                    break;
            }
        }
        return START_STICKY;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);
        Log.d(TAG, "🔌 Service verbunden");
    }
}
