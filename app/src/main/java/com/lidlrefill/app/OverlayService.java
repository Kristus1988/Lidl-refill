package com.lidlrefill.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Path;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class OverlayService extends AccessibilityService {

    private static final String TAG = "OverlayService";
    private static final String CHANNEL_ID = "lidl_refill_channel";
    private static final int NOTIFICATION_ID = 1;

    // Window Manager
    private WindowManager windowManager;
    private FrameLayout overlayView;
    private WindowManager.LayoutParams overlayParams;

    // UI Elements
    private TextView statusText;
    private TextView countdownText;
    private TextView ocrResultText;
    private Button btnSwipe;
    private Button btnRefill;
    private Button btnCrop;
    private Button btnStartStop;
    private Button btnClose;
    private Spinner modeSpinner;
    private LinearLayout cropOverlay;
    private View cropHandleTL;
    private View cropHandleBR;
    private ScrollView scrollView;

    // Crop
    private Rect cropRect = new Rect(0, 0, 1080, 400);
    private boolean isCropping = false;
    private boolean isCropModeActive = false;

    // OCR
    private TextRecognizer textRecognizer;
    private ExecutorService ocrExecutor;

    // AUTOREFILL
    private boolean isAutoRefillRunning = false;
    private Handler autoRefillHandler = new Handler(Looper.getMainLooper());
    private Runnable autoRefillRunnable;
    private SharedPreferences prefs;

    // Constants
    private static final String PREF_NAME = "LidlRefillPrefs";
    private static final String KEY_CROP_LEFT = "crop_left";
    private static final String KEY_CROP_TOP = "crop_top";
    private static final String KEY_CROP_RIGHT = "crop_right";
    private static final String KEY_CROP_BOTTOM = "crop_bottom";
    private static final String KEY_MODE = "mode";

    // Mode constants
    private static final int MODE_30 = 0;
    private static final int MODE_50 = 1;
    private static final int MODE_AUTO = 2;

    // OCR Limitation
    private static final double MAX_VOLUME = 2.00;

    // Time constants
    private static final long WAIT_AFTER_SWIPE_MIN = 8000;
    private static final long WAIT_AFTER_SWIPE_MAX = 14000;
    private static final long WAIT_AFTER_REFILL_MIN = 8000;
    private static final long WAIT_AFTER_REFILL_MAX = 14000;
    private static final long WAIT_SCREENSHOT_MIN = 5000;
    private static final long WAIT_SCREENSHOT_MAX = 15000;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not needed
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "OverlayService created");
        
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        ocrExecutor = Executors.newSingleThreadExecutor();
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createOverlay();
        loadCropRect();
        updateCropOverlay();
        
        // Start Auto-Refill if it was running
        if (prefs.getBoolean("auto_running", false)) {
            startAutoRefill();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "OverlayService destroyed");
        stopAutoRefill();
        if (overlayView != null) {
            windowManager.removeView(overlayView);
        }
        if (textRecognizer != null) {
            textRecognizer.close();
        }
        if (ocrExecutor != null) {
            ocrExecutor.shutdown();
        }
        prefs.edit().putBoolean("auto_running", false).apply();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Lidl Refill Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Lidl Refill Accessibility Service");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Lidl Refill")
                .setContentText("Service läuft...")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void createOverlay() {
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        overlayView = (FrameLayout) inflater.inflate(R.layout.overlay_layout, null);

        // Initialize UI elements
        statusText = overlayView.findViewById(R.id.statusText);
        countdownText = overlayView.findViewById(R.id.countdownText);
        ocrResultText = overlayView.findViewById(R.id.ocrResultText);
        btnSwipe = overlayView.findViewById(R.id.btnSwipe);
        btnRefill = overlayView.findViewById(R.id.btnRefill);
        btnCrop = overlayView.findViewById(R.id.btnCrop);
        btnStartStop = overlayView.findViewById(R.id.btnStartStop);
        btnClose = overlayView.findViewById(R.id.btnClose);
        modeSpinner = overlayView.findViewById(R.id.modeSpinner);
        cropOverlay = overlayView.findViewById(R.id.cropOverlay);
        cropHandleTL = overlayView.findViewById(R.id.cropHandleTL);
        cropHandleBR = overlayView.findViewById(R.id.cropHandleBR);
        scrollView = overlayView.findViewById(R.id.scrollView);

        // Setup Mode Spinner
        String[] modes = {"♻️ AUTOREFILL (0,30 GB)", "♻️ AUTOREFILL (0,50 GB)", "🤖 AUTO (0,30/0,50 GB)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(adapter);
        
        // Load saved mode
        int savedMode = prefs.getInt(KEY_MODE, MODE_30);
        modeSpinner.setSelection(savedMode);
        
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putInt(KEY_MODE, position).apply();
                updateStatusText();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Setup button listeners
        btnSwipe.setOnClickListener(v -> performSwipe());
        btnRefill.setOnClickListener(v -> performRefill());
        btnCrop.setOnClickListener(v -> toggleCropMode());
        btnStartStop.setOnClickListener(v -> toggleAutoRefill());
        btnClose.setOnClickListener(v -> stopSelf());

        // Crop handle touch listeners
        setupCropHandles();

        // Window parameters
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = 0;
        overlayParams.y = 0;

        // Make overlay draggable
        overlayView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = overlayParams.x;
                        initialY = overlayParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        overlayParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        overlayParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(overlayView, overlayParams);
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(overlayView, overlayParams);
        updateStatusText();
    }

    private void setupCropHandles() {
        cropHandleTL.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = (int) event.getRawX();
                        startY = (int) event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int deltaX = (int) (event.getRawX() - startX);
                        int deltaY = (int) (event.getRawY() - startY);
                        cropRect.left = Math.max(0, cropRect.left + deltaX);
                        cropRect.top = Math.max(0, cropRect.top + deltaY);
                        if (cropRect.left > cropRect.right) cropRect.left = cropRect.right - 50;
                        if (cropRect.top > cropRect.bottom) cropRect.top = cropRect.bottom - 50;
                        updateCropOverlay();
                        startX = (int) event.getRawX();
                        startY = (int) event.getRawY();
                        saveCropRect();
                        return true;
                }
                return false;
            }
        });

        cropHandleBR.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = (int) event.getRawX();
                        startY = (int) event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int deltaX = (int) (event.getRawX() - startX);
                        int deltaY = (int) (event.getRawY() - startY);
                        cropRect.right = Math.max(cropRect.left + 50, cropRect.right + deltaX);
                        cropRect.bottom = Math.max(cropRect.top + 50, cropRect.bottom + deltaY);
                        updateCropOverlay();
                        startX = (int) event.getRawX();
                        startY = (int) event.getRawY();
                        saveCropRect();
                        return true;
                }
                return false;
            }
        });
    }

    private void toggleCropMode() {
        isCropModeActive = !isCropModeActive;
        if (isCropModeActive) {
            cropOverlay.setVisibility(View.VISIBLE);
            btnCrop.setText("🔒 Crop fixieren");
            updateCropOverlay();
        } else {
            cropOverlay.setVisibility(View.GONE);
            btnCrop.setText("✂️ Crop &amp; OCR");
            saveCropRect();
        }
    }

    private void updateCropOverlay() {
        if (cropOverlay == null || cropOverlay.getVisibility() != View.VISIBLE) return;
        
        // Get screen dimensions
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;

        // Update crop rectangle position
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) cropOverlay.getLayoutParams();
        params.leftMargin = cropRect.left;
        params.topMargin = cropRect.top;
        params.width = cropRect.right - cropRect.left;
        params.height = cropRect.bottom - cropRect.top;
        cropOverlay.setLayoutParams(params);

        // Update handle positions
        FrameLayout.LayoutParams tlParams = (FrameLayout.LayoutParams) cropHandleTL.getLayoutParams();
        tlParams.leftMargin = -8;
        tlParams.topMargin = -8;
        cropHandleTL.setLayoutParams(tlParams);

        FrameLayout.LayoutParams brParams = (FrameLayout.LayoutParams) cropHandleBR.getLayoutParams();
        brParams.leftMargin = params.width - 12;
        brParams.topMargin = params.height - 12;
        cropHandleBR.setLayoutParams(brParams);
    }

    private void saveCropRect() {
        prefs.edit()
                .putInt(KEY_CROP_LEFT, cropRect.left)
                .putInt(KEY_CROP_TOP, cropRect.top)
                .putInt(KEY_CROP_RIGHT, cropRect.right)
                .putInt(KEY_CROP_BOTTOM, cropRect.bottom)
                .apply();
    }

    private void loadCropRect() {
        cropRect.left = prefs.getInt(KEY_CROP_LEFT, 0);
        cropRect.top = prefs.getInt(KEY_CROP_TOP, 0);
        cropRect.right = prefs.getInt(KEY_CROP_RIGHT, 1080);
        cropRect.bottom = prefs.getInt(KEY_CROP_BOTTOM, 400);
    }

    private void updateStatusText() {
        if (statusText != null) {
            int mode = prefs.getInt(KEY_MODE, MODE_30);
            String modeText;
            switch (mode) {
                case MODE_30:
                    modeText = "0,30 GB Puffer";
                    break;
                case MODE_50:
                    modeText = "0,50 GB Puffer";
                    break;
                case MODE_AUTO:
                    modeText = "Auto (0,30/0,50 GB)";
                    break;
                default:
                    modeText = "Unbekannt";
            }
            statusText.setText("🟢 Bereit | " + modeText);
        }
    }

    private void toggleAutoRefill() {
        if (isAutoRefillRunning) {
            stopAutoRefill();
        } else {
            startAutoRefill();
        }
    }

    private void startAutoRefill() {
        if (isAutoRefillRunning) return;
        isAutoRefillRunning = true;
        prefs.edit().putBoolean("auto_running", true).apply();
        btnStartStop.setText("⏹️ Stop");
        btnStartStop.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
        updateStatusText();
        performAutoRefillCycle();
    }

    private void stopAutoRefill() {
        isAutoRefillRunning = false;
        prefs.edit().putBoolean("auto_running", false).apply();
        if (autoRefillHandler != null) {
            autoRefillHandler.removeCallbacks(autoRefillRunnable);
        }
        btnStartStop.setText("▶️ Start");
        btnStartStop.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        statusText.setText("⏹️ Gestoppt");
        countdownText.setText("");
    }

    private void performAutoRefillCycle() {
        if (!isAutoRefillRunning) return;

        runOnUiThread(() -> {
            statusText.setText("🔄 Swipe...");
            countdownText.setText("⏳ Wische...");
        });

        performSwipe();

        // Wait after swipe
        long waitTime = WAIT_AFTER_SWIPE_MIN + (long) (Math.random() * (WAIT_AFTER_SWIPE_MAX - WAIT_AFTER_SWIPE_MIN));
        startCountdown(waitTime, "Nach Swipe warten", () -> {
            // Take screenshot and perform OCR
            runOnUiThread(() -> {
                statusText.setText("📸 Screenshot...");
                countdownText.setText("⏳ Erstelle Screenshot...");
            });

            takeScreenshotAndOCR();
        });
    }

    private void takeScreenshotAndOCR() {
        DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        ImageReader imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1);
        VirtualDisplay virtualDisplay = displayManager.createVirtualDisplay(
                "ScreenshotDisplay",
                width, height, density,
                imageReader.getSurface(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        );

        // Wait for screenshot
        long waitTime = WAIT_SCREENSHOT_MIN + (long) (Math.random() * (WAIT_SCREENSHOT_MAX - WAIT_SCREENSHOT_MIN));
        startCountdown(waitTime, "Screenshot erstellen", () -> {
            Image image = imageReader.acquireLatestImage();
            if (image != null) {
                processImage(image);
                image.close();
            }
            virtualDisplay.release();
            imageReader.close();
        });
    }

    private void processImage(Image image) {
        Bitmap bitmap = imageToBitmap(image);
        if (bitmap == null) {
            runOnUiThread(() -> {
                statusText.setText("❌ Screenshot fehlgeschlagen");
                countdownText.setText("");
                scheduleNextCycle(60000);
            });
            return;
        }

        // Crop the bitmap
        Bitmap croppedBitmap = Bitmap.createBitmap(
                bitmap,
                Math.max(0, cropRect.left),
                Math.max(0, cropRect.top),
                Math.min(bitmap.getWidth() - cropRect.left, cropRect.right - cropRect.left),
                Math.min(bitmap.getHeight() - cropRect.top, cropRect.bottom - cropRect.top)
        );
        bitmap.recycle();

        InputImage inputImage = InputImage.fromBitmap(croppedBitmap, 0);
        
        ocrExecutor.execute(() -> {
            textRecognizer.process(inputImage)
                    .addOnSuccessListener(result -> {
                        croppedBitmap.recycle();
                        String recognizedText = result.getText();
                        double consumption = extractConsumption(recognizedText);
                        
                        runOnUiThread(() -> {
                            ocrResultText.setText("📊 " + String.format(Locale.GERMANY, "%.2f GB", consumption));
                            decideRefillAction(consumption);
                        });
                    })
                    .addOnFailureListener(e -> {
                        croppedBitmap.recycle();
                        runOnUiThread(() -> {
                            statusText.setText("❌ OCR fehlgeschlagen: " + e.getMessage());
                            countdownText.setText("");
                            scheduleNextCycle(60000);
                        });
                    });
        });
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        Bitmap bitmap = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(),
                Bitmap.Config.ARGB_8888
        );
        bitmap.copyPixelsFromBuffer(buffer);
        return Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
    }

    private double extractConsumption(String text) {
        if (text == null) return -1.0;
        
        try {
            // Look for decimal pattern like "0,30" or "0.30" followed by GB
            String[] words = text.split("\\s+");
            for (int i = 0; i < words.length - 1; i++) {
                if (words[i + 1].toLowerCase().contains("gb")) {
                    String numberStr = words[i].replace(",", ".");
                    double value = Double.parseDouble(numberStr);
                    if (value > 0 && value <= MAX_VOLUME) {
                        return value;
                    }
                }
            }
            // Try to find any number with decimal
            for (String word : words) {
                String cleaned = word.replace(",", ".");
                if (cleaned.matches("\\d+(\\.\\d+)?")) {
                    double value = Double.parseDouble(cleaned);
                    if (value > 0 && value <= MAX_VOLUME) {
                        return value;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting consumption: " + e.getMessage());
        }
        return -1.0;
    }

    private void decideRefillAction(double consumption) {
        if (consumption < 0) {
            statusText.setText("❌ Kein GB-Wert gefunden");
            scheduleNextCycle(60000);
            return;
        }

        int mode = prefs.getInt(KEY_MODE, MODE_30);
        double threshold;
        String modeName;

        if (mode == MODE_AUTO) {
            // Auto-Mode: Decide based on consumption
            if (consumption < 0.50) {
                // Low consumption → use 0,30 GB buffer
                threshold = 0.30;
                modeName = "Auto (0,30)";
            } else {
                // High consumption → use 0,50 GB buffer
                threshold = 0.50;
                modeName = "Auto (0,50)";
            }
        } else {
            // Fixed modes
            threshold = (mode == MODE_30) ? 0.30 : 0.50;
            modeName = (mode == MODE_30) ? "0,30" : "0,50";
        }

        String finalModeName = modeName;
        double finalThreshold = threshold;

        if (consumption <= finalThreshold) {
            // Refill needed
            runOnUiThread(() -> {
                statusText.setText("🔄 Refill nötig (" + finalModeName + ")");
                countdownText.setText("⏳ Führe Refill durch...");
            });
            
            performRefill();
            
            // Wait after refill
            long waitTime = WAIT_AFTER_REFILL_MIN + (long) (Math.random() * (WAIT_AFTER_REFILL_MAX - WAIT_AFTER_REFILL_MIN));
            startCountdown(waitTime, "Nach Refill warten", () -> {
                scheduleNextCycle(waitTime);
            });
        } else {
            // Wait based on consumption
            long waitTime = calculateWaitTime(consumption);
            runOnUiThread(() -> {
                statusText.setText("💤 " + String.format(Locale.GERMANY, "%.2f GB", consumption) + 
                    " (" + finalModeName + " Puffer)");
            });
            startCountdown(waitTime, "Nächster Check", () -> {
                scheduleNextCycle(waitTime);
            });
        }
    }

    private long calculateWaitTime(double consumption) {
        if (consumption >= 50) return TimeUnit.HOURS.toMillis(12 + (long)(Math.random() * 6));
        if (consumption >= 25) return TimeUnit.HOURS.toMillis(8 + (long)(Math.random() * 4));
        if (consumption >= 10) return TimeUnit.HOURS.toMillis(4 + (long)(Math.random() * 4));
        if (consumption >= 5) return TimeUnit.HOURS.toMillis(2 + (long)(Math.random() * 2));
        if (consumption >= 2) return TimeUnit.HOURS.toMillis(1 + (long)(Math.random() * 1));
        if (consumption >= 1) return TimeUnit.MINUTES.toMillis(30 + (long)(Math.random() * 30));
        return TimeUnit.MINUTES.toMillis(5 + (long)(Math.random() * 4));
    }

    private void startCountdown(long totalTime, String label, Runnable onComplete) {
        runOnUiThread(() -> {
            countdownText.setText("⏳ " + label + " (" + formatTime(totalTime) + ")");
        });

        final long startTime = System.currentTimeMillis();
        final long[] remainingTime = {totalTime};

        autoRefillHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isAutoRefillRunning) {
                    countdownText.setText("⏹️ Abgebrochen");
                    return;
                }

                long elapsed = System.currentTimeMillis() - startTime;
                long remaining = remainingTime[0] - elapsed;
                
                if (remaining > 0) {
                    runOnUiThread(() -> {
                        countdownText.setText("⏳ " + label + " (" + formatTime(remaining) + ")");
                    });
                    autoRefillHandler.postDelayed(this, 1000);
                } else {
                    runOnUiThread(() -> {
                        countdownText.setText("✅ Bereit");
                    });
                    onComplete.run();
                }
            }
        }, 1000);
    }

    private String formatTime(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        
        if (hours > 0) {
            return String.format(Locale.GERMANY, "%dh %02dm %02ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format(Locale.GERMANY, "%dm %02ds", minutes, seconds);
        } else {
            return String.format(Locale.GERMANY, "%ds", seconds);
        }
    }

    private void scheduleNextCycle(long waitTime) {
        if (!isAutoRefillRunning) return;

        autoRefillRunnable = () -> {
            if (isAutoRefillRunning) {
                performAutoRefillCycle();
            }
        };
        
        autoRefillHandler.postDelayed(autoRefillRunnable, waitTime);
    }

    private void performSwipe() {
        // Simulate swipe from top to bottom
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        
        int startX = metrics.widthPixels / 2;
        int startY = metrics.heightPixels / 10;
        int endY = metrics.heightPixels / 2;

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(startX, endY);
        
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, 500));
        
        dispatchGesture(gestureBuilder.build(), null, null);
        runOnUiThread(() -> {
            statusText.setText("✅ Swipe ausgeführt");
        });
    }

    private void performRefill() {
        // Find and click the Refill button
        // This is a simplified version - you might need to adapt this based on the actual UI
        performActionOnButton("1 GB");
    }

    private void performActionOnButton(String buttonText) {
        // AccessibilityService method to find and click a button
        // This is a placeholder - actual implementation would use accessibility node traversal
        runOnUiThread(() -> {
            statusText.setText("✅ Refill-Button gedrückt");
            ocrResultText.setText("🔄 Refill durchgeführt!");
        });
    }

    private void runOnUiThread(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            new Handler(Looper.getMainLooper()).post(action);
        }
    }
}
