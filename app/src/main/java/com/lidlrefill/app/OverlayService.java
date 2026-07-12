package com.lidlrefill.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.GestureDetectorCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OverlayService extends AccessibilityService {

    private static final String TAG = "LidlRefill";
    private static final String PREFS_NAME = "LidlRefillPrefs";
    private static final String KEY_AUTO_REFILL = "auto_refill";
    private static final String KEY_REMAINING_DATA = "remaining_data";
    private static final String KEY_LAST_REMAINING_DATA = "last_remaining_data";
    private static final String KEY_LAST_UPDATE_TIME = "last_update_time";
    private static final String KEY_CURRENT_STAGE = "current_stage";
    private static final String KEY_TOTAL_REFILLS = "total_refills";
    private static final String KEY_LAST_REFILL_TIME = "last_refill_time";
    private static final String KEY_IS_REFILLING = "is_refilling";
    private static final String KEY_VERIFICATION_ATTEMPTS = "verification_attempts";
    private static final String KEY_CONSUMPTION_RATE = "consumption_rate";
    private static final String KEY_LAST_VERIFICATION_TIME = "last_verification_time";

    // UI Komponenten
    private WindowManager windowManager;
    private View overlayView;
    private LinearLayout mainLayout, statusLayout, controlsLayout;
    private ScrollView scrollView;
    private TextView statusText, dataText, stageText, statsText, timeText, consumptionText;
    private Button refillButton, autoRefillToggle, verifyButton, screenshotButton, settingsButton;
    private ImageButton closeButton;
    private View colorIndicator;

    // Service Komponenten
    private Handler mainHandler;
    private Handler backgroundHandler;
    private ExecutorService executorService;
    private TextRecognizer textRecognizer;
    private SharedPreferences sharedPreferences;
    private GestureDetectorCompat gestureDetector;

    // State Variablen
    private boolean isAutoRefillEnabled = false;
    private boolean isRefilling = false;
    private double currentRemainingData = 0.0;
    private double lastRemainingData = 0.0;
    private int currentStage = 1;
    private int totalRefills = 0;
    private long lastUpdateTime = 0;
    private long lastRefillTime = 0;
    private long lastVerificationTime = 0;
    private int verificationAttempts = 0;
    private double consumptionRate = 0.0;
    private Runnable autoRefillRunnable;
    private Runnable stageUpdateRunnable;

    // Refill Konfiguration
    private static final long MIN_REFILL_INTERVAL = 30000; // 30 Sekunden
    private static final long MAX_REFILL_ATTEMPTS = 3;
    private static final long VERIFICATION_TIMEOUT = 30000; // 30 Sekunden

    // MediaProjection für Screenshots
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth, screenHeight;
    private int screenDensity;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Process accessibility events if needed
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
    }

    @Override
    public void onServiceConnected() {
        Log.d(TAG, "Accessibility Service connected");
        setupService();
        initializeComponents();
        createOverlay();
        loadSavedState();
        setupAutoRefill();
        startPeriodicUpdates();
        Log.d(TAG, "OverlayService fully initialized");
    }

    private void setupService() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE |
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                AccessibilityServiceInfo.DEFAULT;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK;
        info.notificationTimeout = 100;
        setServiceInfo(info);
    }

    private void initializeComponents() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        backgroundHandler = new Handler(Looper.getMainLooper());
        executorService = Executors.newSingleThreadExecutor();
        textRecognizer = TextRecognition.getClient(new TextRecognizerOptions.Builder().build());
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        gestureDetector = new GestureDetectorCompat(this, new SwipeGestureListener());

        // Screen metrics für Screenshots
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
    }

    private void createOverlay() {
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        overlayView = inflater.inflate(R.layout.overlay_layout, null);

        // UI Elemente initialisieren
        mainLayout = overlayView.findViewById(R.id.main_layout);
        statusLayout = overlayView.findViewById(R.id.status_layout);
        controlsLayout = overlayView.findViewById(R.id.controls_layout);
        scrollView = overlayView.findViewById(R.id.scroll_view);
        
        statusText = overlayView.findViewById(R.id.status_text);
        dataText = overlayView.findViewById(R.id.data_text);
        stageText = overlayView.findViewById(R.id.stage_text);
        statsText = overlayView.findViewById(R.id.stats_text);
        timeText = overlayView.findViewById(R.id.time_text);
        consumptionText = overlayView.findViewById(R.id.consumption_text);
        
        refillButton = overlayView.findViewById(R.id.refill_button);
        autoRefillToggle = overlayView.findViewById(R.id.auto_refill_toggle);
        verifyButton = overlayView.findViewById(R.id.verify_button);
        screenshotButton = overlayView.findViewById(R.id.screenshot_button);
        settingsButton = overlayView.findViewById(R.id.settings_button);
        closeButton = overlayView.findViewById(R.id.close_button);
        
        colorIndicator = overlayView.findViewById(R.id.color_indicator);

        // Setup UI
        setupUIListeners();
        setupAnimations();

        // WindowManager Parameter
        WindowManager.LayoutParams params = getOverlayParams();
        windowManager.addView(overlayView, params);

        // Animation beim Einblenden
        Animation fadeIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in);
        overlayView.startAnimation(fadeIn);
    }

    private WindowManager.LayoutParams getOverlayParams() {
        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 20;
        params.y = 80;
        params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        return params;
    }

    private void setupUIListeners() {
        // Refill Button
        refillButton.setOnClickListener(v -> performManualRefill());

        // Auto-Refill Toggle
        updateAutoRefillButton();
        autoRefillToggle.setOnClickListener(v -> toggleAutoRefill());

        // Verify Button
        verifyButton.setOnClickListener(v -> performVerification());

        // Screenshot Button
        screenshotButton.setOnClickListener(v -> takeScreenshot());

        // Settings Button
        settingsButton.setOnClickListener(v -> openSettings());

        // Close Button
        closeButton.setOnClickListener(v -> {
            Animation fadeOut = AnimationUtils.loadAnimation(this, android.R.anim.fade_out);
            overlayView.startAnimation(fadeOut);
            mainHandler.postDelayed(() -> {
                if (overlayView != null && windowManager != null) {
                    windowManager.removeView(overlayView);
                }
                stopSelf();
            }, 500);
        });

        // Swipe Gestures für Overlay
        overlayView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private void setupAnimations() {
        // Animation für Status-Änderungen
        Animation pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.pulse);
        colorIndicator.startAnimation(pulseAnimation);
    }

    private void loadSavedState() {
        isAutoRefillEnabled = sharedPreferences.getBoolean(KEY_AUTO_REFILL, false);
        currentRemainingData = Double.longBitsToDouble(sharedPreferences.getLong(KEY_REMAINING_DATA, Double.doubleToLongBits(0.0)));
        lastRemainingData = Double.longBitsToDouble(sharedPreferences.getLong(KEY_LAST_REMAINING_DATA, Double.doubleToLongBits(0.0)));
        currentStage = sharedPreferences.getInt(KEY_CURRENT_STAGE, 1);
        totalRefills = sharedPreferences.getInt(KEY_TOTAL_REFILLS, 0);
        lastRefillTime = sharedPreferences.getLong(KEY_LAST_REFILL_TIME, 0);
        lastUpdateTime = sharedPreferences.getLong(KEY_LAST_UPDATE_TIME, 0);
        isRefilling = sharedPreferences.getBoolean(KEY_IS_REFILLING, false);
        verificationAttempts = sharedPreferences.getInt(KEY_VERIFICATION_ATTEMPTS, 0);
        consumptionRate = Double.longBitsToDouble(sharedPreferences.getLong(KEY_CONSUMPTION_RATE, Double.doubleToLongBits(0.0)));
        lastVerificationTime = sharedPreferences.getLong(KEY_LAST_VERIFICATION_TIME, 0);

        // Aktuelle Stage berechnen falls nötig
        if (lastUpdateTime > 0) {
            updateStage();
        }
        
        updateDisplay();
        Log.d(TAG, "Loaded state: Data=" + currentRemainingData + "GB, Stage=" + currentStage + 
              ", AutoRefill=" + isAutoRefillEnabled + ", Refills=" + totalRefills);
    }

    private void saveState() {
        sharedPreferences.edit()
                .putBoolean(KEY_AUTO_REFILL, isAutoRefillEnabled)
                .putLong(KEY_REMAINING_DATA, Double.doubleToLongBits(currentRemainingData))
                .putLong(KEY_LAST_REMAINING_DATA, Double.doubleToLongBits(lastRemainingData))
                .putInt(KEY_CURRENT_STAGE, currentStage)
                .putInt(KEY_TOTAL_REFILLS, totalRefills)
                .putLong(KEY_LAST_REFILL_TIME, lastRefillTime)
                .putLong(KEY_LAST_UPDATE_TIME, lastUpdateTime)
                .putBoolean(KEY_IS_REFILLING, isRefilling)
                .putInt(KEY_VERIFICATION_ATTEMPTS, verificationAttempts)
                .putLong(KEY_CONSUMPTION_RATE, Double.doubleToLongBits(consumptionRate))
                .putLong(KEY_LAST_VERIFICATION_TIME, lastVerificationTime)
                .apply();
    }

    private void setupAutoRefill() {
        if (isAutoRefillEnabled) {
            startAutoRefill();
        }
    }

    private void startPeriodicUpdates() {
        // Periodische UI-Updates
        if (stageUpdateRunnable != null) {
            mainHandler.removeCallbacks(stageUpdateRunnable);
        }
        
        stageUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateDisplay();
                updateTimeDisplay();
                updateConsumptionRate();
                mainHandler.postDelayed(this, 60000); // Jede Minute updaten
            }
        };
        mainHandler.post(stageUpdateRunnable);
    }

    private void toggleAutoRefill() {
        isAutoRefillEnabled = !isAutoRefillEnabled;
        updateAutoRefillButton();
        
        if (isAutoRefillEnabled) {
            startAutoRefill();
            Toast.makeText(this, "🔄 AUTOREFILL aktiviert", Toast.LENGTH_SHORT).show();
            showStatus("🔄 AUTOREFILL aktiviert", Color.GREEN);
        } else {
            stopAutoRefill();
            Toast.makeText(this, "⏸️ AUTOREFILL deaktiviert", Toast.LENGTH_SHORT).show();
            showStatus("⏸️ AUTOREFILL deaktiviert", Color.YELLOW);
        }
        
        saveState();
    }

    private void startAutoRefill() {
        if (autoRefillRunnable != null) {
            mainHandler.removeCallbacks(autoRefillRunnable);
        }
        
        autoRefillRunnable = new Runnable() {
            @Override
            public void run() {
                if (isAutoRefillEnabled && !isRefilling) {
                    Log.d(TAG, "Auto-Refill check starting");
                    performVerification();
                    // Nächsten Check planen
                    long waitTime = calculateWaitTime(currentRemainingData);
                    mainHandler.postDelayed(this, waitTime);
                } else if (isAutoRefillEnabled) {
                    // Falls Refilling läuft, später nochmal versuchen
                    mainHandler.postDelayed(this, 30000);
                }
            }
        };
        
        // Ersten Check nach kurzer Verzögerung starten
        mainHandler.postDelayed(autoRefillRunnable, 5000);
        Log.d(TAG, "Auto-Refill started");
    }

    private void stopAutoRefill() {
        if (autoRefillRunnable != null) {
            mainHandler.removeCallbacks(autoRefillRunnable);
            autoRefillRunnable = null;
        }
        Log.d(TAG, "Auto-Refill stopped");
    }

    private void performVerification() {
        if (isRefilling) {
            Log.d(TAG, "Verification skipped - refill in progress");
            return;
        }

        // Aktuelle Daten prüfen - hier würde OCR oder andere Methode den Verbrauch prüfen
        // Simuliere Verbrauchsprüfung
        double newData = simulateDataCheck();
        
        if (newData >= 0) {
            lastRemainingData = currentRemainingData;
            setRemainingData(newData);
            
            // Prüfen ob Refill nötig ist
            if (shouldRefill(currentRemainingData)) {
                performRefill();
            } else {
                String status = String.format("✅ Daten ausreichend: %.2f GB", currentRemainingData);
                showStatus(status, Color.GREEN);
            }
        } else {
            // Fehler bei der Überprüfung - später wiederholen
            verificationAttempts++;
            if (verificationAttempts > 3) {
                verificationAttempts = 0;
                showStatus("⚠️ Überprüfung fehlgeschlagen", Color.YELLOW);
            }
        }
        
        lastVerificationTime = System.currentTimeMillis();
        saveState();
    }

    private double simulateDataCheck() {
        // Simuliert den aktuellen Datenverbrauch - in Realität würde hier OCR kommen
        // Hier könnte tatsächlicher Verbrauch aus der Lidl App extrahiert werden
        Random random = new Random();
        
        // Simuliere leichten Verbrauch (0.01 - 0.1 GB pro Check)
        double usage = 0.01 + (random.nextDouble() * 0.09);
        double newData = Math.max(0, currentRemainingData - usage);
        
        Log.d(TAG, "Simulated data check: " + currentRemainingData + " -> " + newData + " GB");
        return newData;
    }

    private boolean shouldRefill(double remainingData) {
        // Verschiedene Schwellwerte je nach Stufe
        switch (currentStage) {
            case 1: return remainingData <= 25.0;  // 50 GB -> 25 GB
            case 2: return remainingData <= 10.0;  // 25 GB -> 10 GB
            case 3: return remainingData <= 5.0;   // 10 GB -> 5 GB
            case 4: return remainingData <= 1.0;   // 5 GB -> 1 GB
            case 5: return remainingData <= 0.5;   // 1 GB -> 0.5 GB
            case 6: return remainingData <= 0.3;   // < 1 GB -> 0.3 GB
            default: return false;
        }
    }

    private void performRefill() {
        if (isRefilling) {
            Log.d(TAG, "Refill already in progress");
            return;
        }

        if (System.currentTimeMillis() - lastRefillTime < MIN_REFILL_INTERVAL) {
            Log.d(TAG, "Minimum refill interval not reached");
            showStatus("⏳ Bitte warten...", Color.YELLOW);
            return;
        }

        Log.d(TAG, "Starting refill process");
        isRefilling = true;
        showStatus("🔄 Refill wird durchgeführt...", Color.BLUE);
        saveState();

        // Accessibility Action für Refill ausführen
        boolean success = performAccessibilityRefill();
        
        if (success) {
            // Refill erfolgreich
            mainHandler.postDelayed(() -> {
                currentRemainingData += 1.0;
                totalRefills++;
                lastRefillTime = System.currentTimeMillis();
                isRefilling = false;
                
                // Animation für erfolgreichen Refill
                animateSuccessfulRefill();
                
                showStatus("✅ +1 GB aufgeladen! Neuer Stand: " + String.format("%.2f", currentRemainingData) + " GB", Color.GREEN);
                Toast.makeText(this, "✅ 1 GB erfolgreich aufgeladen!", Toast.LENGTH_LONG).show();
                
                updateDisplay();
                saveState();
                Log.d(TAG, "Refill completed successfully. New data: " + currentRemainingData + "GB, Total refills: " + totalRefills);
            }, 5000);
        } else {
            // Refill fehlgeschlagen
            isRefilling = false;
            showStatus("❌ Refill fehlgeschlagen", Color.RED);
            Toast.makeText(this, "❌ Refill konnte nicht durchgeführt werden", Toast.LENGTH_SHORT).show();
            saveState();
        }
    }

    private void performManualRefill() {
        if (!isRefilling) {
            performRefill();
        } else {
            Toast.makeText(this, "⏳ Refill läuft bereits", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean performAccessibilityRefill() {
        try {
            Log.d(TAG, "Performing accessibility refill action");
            
            // Hier würde die tatsächliche Interaktion mit der Lidl App stattfinden
            // Beispiel: Finde den Refill-Button in der App und klicke ihn
            
            // Simulierte Refill-Aktion
            Thread.sleep(2000);
            
            // Suche nach dem Refill-Button in der aktuellen App
            // In der Praxis müsste hier die Lidl App erkannt werden
            // und der passende Button gefunden werden
            
            // Test: Simuliere erfolgreichen Refill
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error performing accessibility refill", e);
            return false;
        }
    }

    private void setRemainingData(double data) {
        if (data >= 0) {
            // Vorherige Daten speichern für Verbrauchsberechnung
            lastRemainingData = currentRemainingData;
            currentRemainingData = Math.round(data * 100.0) / 100.0; // Auf 2 Dezimalstellen runden
            lastUpdateTime = System.currentTimeMillis();
            
            // Stage aktualisieren
            updateStage();
            
            // Verbrauchsrate berechnen
            updateConsumptionRate();
            
            // UI aktualisieren
            updateDisplay();
            
            // State speichern
            saveState();
            
            Log.d(TAG, "Data updated: " + currentRemainingData + " GB, Stage: " + currentStage);
        }
    }

    private void updateStage() {
        if (currentRemainingData >= 50.0) {
            currentStage = 1;
        } else if (currentRemainingData >= 25.0) {
            currentStage = 2;
        } else if (currentRemainingData >= 10.0) {
            currentStage = 3;
        } else if (currentRemainingData >= 5.0) {
            currentStage = 4;
        } else if (currentRemainingData >= 1.0) {
            currentStage = 5;
        } else {
            currentStage = 6;
        }
    }

    private void updateConsumptionRate() {
        if (lastUpdateTime > 0 && lastRemainingData > 0 && currentRemainingData >= 0) {
            long timeDiff = System.currentTimeMillis() - lastUpdateTime;
            if (timeDiff > 0) {
                double dataDiff = lastRemainingData - currentRemainingData;
                if (dataDiff > 0) {
                    // Verbrauch pro Minute
                    consumptionRate = (dataDiff / timeDiff) * 60 * 60 * 1000;
                    if (consumptionRate < 0) consumptionRate = 0;
                }
            }
        }
    }

    private long calculateWaitTime(double currentUsage) {
        Random random = new Random();
        long baseWait;
        int variation;
        
        if (currentUsage >= 50.0) {
            // Stufe 1: 50 GB → 25 GB
            baseWait = 2 * 60 * 60 * 1000; // 2 Stunden Basis
            variation = random.nextInt(4 * 60 * 60 * 1000); // +0-4 Stunden
            return baseWait + variation; // 2-6 Stunden
        } else if (currentUsage >= 25.0) {
            // Stufe 2: 25 GB → 10 GB
            baseWait = 1 * 60 * 60 * 1000; // 1 Stunde Basis
            variation = random.nextInt(2 * 60 * 60 * 1000); // +0-2 Stunden
            return baseWait + variation; // 1-3 Stunden
        } else if (currentUsage >= 10.0) {
            // Stufe 3: 10 GB → 5 GB
            baseWait = 30 * 60 * 1000; // 30 Minuten Basis
            variation = random.nextInt(60 * 60 * 1000); // +0-60 Minuten
            return baseWait + variation; // 30-90 Minuten
        } else if (currentUsage >= 5.0) {
            // Stufe 4: 5 GB → 1 GB
            baseWait = 15 * 60 * 1000; // 15 Minuten Basis
            variation = random.nextInt(30 * 60 * 1000); // +0-30 Minuten
            return baseWait + variation; // 15-45 Minuten
        } else if (currentUsage >= 1.0) {
            // Stufe 5: 1 GB → 0,50 GB
            baseWait = 5 * 60 * 1000; // 5 Minuten Basis
            variation = random.nextInt(10 * 60 * 1000); // +0-10 Minuten
            return baseWait + variation; // 5-15 Minuten
        } else {
            // Stufe 6: Unter 1 GB
            if (consumptionRate > 0.1) { // Hoher Verbrauch
                baseWait = 5 * 60 * 1000;
                variation = random.nextInt(10 * 60 * 1000);
                return baseWait + variation; // 5-15 Minuten
            } else { // Niedriger Verbrauch
                baseWait = 10 * 60 * 1000;
                variation = random.nextInt(20 * 60 * 1000);
                return baseWait + variation; // 10-30 Minuten
            }
        }
    }

    private void updateDisplay() {
        if (dataText != null) {
            dataText.setText(String.format("📊 %.2f GB", currentRemainingData));
        }
        if (stageText != null) {
            String stageInfo = String.format("📈 Stufe %d", currentStage);
            if (currentStage == 5) {
                stageInfo += " ⚠️ KRITISCH";
            } else if (currentStage == 6) {
                stageInfo += " 🚨 SEHR KRITISCH";
            }
            stageText.setText(stageInfo);
        }
        if (statsText != null) {
            statsText.setText(String.format("🔄 %d Refills", totalRefills));
        }
        if (consumptionText != null) {
            consumptionText.setText(String.format("⚡ %.2f GB/h", consumptionRate));
        }
        
        updateColorIndicator();
    }

    private void updateColorIndicator() {
        if (colorIndicator != null) {
            int color;
            switch (currentStage) {
                case 1: color = Color.GREEN; break;
                case 2: color = Color.parseColor("#4CAF50"); break;
                case 3: color = Color.parseColor("#8BC34A"); break;
                case 4: color = Color.parseColor("#FFC107"); break;
                case 5: color = Color.parseColor("#FF9800"); break;
                case 6: color = Color.RED; break;
                default: color = Color.GRAY;
            }
            colorIndicator.setBackgroundColor(color);
        }
    }

    private void updateTimeDisplay() {
        if (timeText != null) {
            String timeStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            timeText.setText("🕐 " + timeStr);
        }
    }

    private void updateAutoRefillButton() {
        if (autoRefillToggle != null) {
            autoRefillToggle.setText(isAutoRefillEnabled ? "🔄 AUTOREFILL AN" : "⏸️ AUTOREFILL AUS");
            autoRefillToggle.setBackgroundColor(isAutoRefillEnabled ? 
                    Color.parseColor("#4CAF50") : Color.parseColor("#FF5722"));
        }
    }

    private void showStatus(String message, int color) {
        if (statusText != null) {
            statusText.setText(message);
            statusText.setTextColor(color);
            // Animation für Statusänderung
            Animation fadeOut = AnimationUtils.loadAnimation(this, android.R.anim.fade_in);
            statusText.startAnimation(fadeOut);
        }
        // Kurzzeitiger Toast für wichtige Meldungen
        if (color == Color.RED || color == Color.GREEN) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    private void animateSuccessfulRefill() {
        // Animation für erfolgreichen Refill
        Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse);
        if (dataText != null) {
            dataText.startAnimation(pulse);
        }
        if (colorIndicator != null) {
            colorIndicator.startAnimation(pulse);
        }
    }

    private void takeScreenshot() {
        // Screenshot-Funktionalität für Debugging
        try {
            // Hier müsste die Screenshot-Funktionalität implementiert werden
            // Über MediaProjection oder andere Methoden
            Toast.makeText(this, "📸 Screenshot-Funktion in Entwicklung", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Screenshot functionality requested");
        } catch (Exception e) {
            Log.e(TAG, "Error taking screenshot", e);
            Toast.makeText(this, "❌ Screenshot fehlgeschlagen", Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings() {
        // Öffnet die Einstellungen der App
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        Toast.makeText(this, "⚙️ Einstellungen geöffnet", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAutoRefill();
        
        if (stageUpdateRunnable != null) {
            mainHandler.removeCallbacks(stageUpdateRunnable);
        }
        
        if (textRecognizer != null) {
            textRecognizer.close();
        }
        
        if (executorService != null) {
            executorService.shutdown();
        }
        
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                Log.e(TAG, "Error removing overlay view", e);
            }
        }
        
        saveState();
        Log.d(TAG, "Service destroyed");
    }

    // Swipe Gesture Listener
    private class SwipeGestureListener extends android.view.GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_THRESHOLD = 100;
        private static final int SWIPE_VELOCITY_THRESHOLD = 100;

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (e1 == null || e2 == null) return false;

            float diffX = e2.getX() - e1.getX();
            float diffY = e2.getY() - e1.getY();

            if (Math.abs(diffX) > Math.abs(diffY)) {
                // Horizontales Swipe
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        // Rechts-Swipe: Refill
                        performManualRefill();
                    } else {
                        // Links-Swipe: Verifizierung
                        performVerification();
                    }
                    return true;
                }
            } else {
                // Vertikales Swipe
                if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffY > 0) {
                        // Abwärts: Auto-Refill toggeln
                        toggleAutoRefill();
                    } else {
                        // Aufwärts: Status anzeigen
                        showStatus("📊 " + String.format("%.2f", currentRemainingData) + " GB", Color.WHITE);
                    }
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            // Einfacher Tap: Details anzeigen
            String details = String.format("📊 %.2f GB\n📈 Stufe %d\n🔄 %d Refills\n⚡ %.2f GB/h", 
                    currentRemainingData, currentStage, totalRefills, consumptionRate);
            Toast.makeText(OverlayService.this, details, Toast.LENGTH_LONG).show();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            // Doppel-Tap: Maximieren/Minimieren des Overlays
            if (scrollView != null) {
                if (scrollView.getVisibility() == View.VISIBLE) {
                    scrollView.setVisibility(View.GONE);
                    Toast.makeText(OverlayService.this, "Overlay minimiert", Toast.LENGTH_SHORT).show();
                } else {
                    scrollView.setVisibility(View.VISIBLE);
                    Toast.makeText(OverlayService.this, "Overlay maximiert", Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
    }

    // OCR-Funktionalität (für echte Verbrauchserkennung)
    private void performOCR(byte[] imageData) {
        if (imageData == null || textRecognizer == null) return;
        
        try {
            InputImage image = InputImage.fromByteArray(imageData, screenWidth, screenHeight, 0, null);
            textRecognizer.process(image)
                    .addOnSuccessListener(this::handleOCRResult)
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "OCR failed", e);
                        showStatus("❌ OCR fehlgeschlagen", Color.RED);
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error performing OCR", e);
        }
    }

    private void handleOCRResult(Text result) {
        String fullText = result.getText();
        if (fullText == null || fullText.isEmpty()) {
            Log.d(TAG, "No text found in OCR result");
            return;
        }
        
        Log.d(TAG, "OCR Result: " + fullText);
        
        // Extrahiere Datenvolumen aus OCR-Text
        double extractedData = extractDataFromText(fullText);
        if (extractedData >= 0) {
            setRemainingData(extractedData);
            showStatus("✅ OCR: " + String.format("%.2f", extractedData) + " GB", Color.GREEN);
        } else {
            showStatus("⚠️ Kein Datenvolumen gefunden", Color.YELLOW);
        }
    }

    private double extractDataFromText(String text) {
        // Verschiedene Muster für Datenvolumen erkennen
        String[] patterns = {
            "(\\d+[.,]?\\d*)\\s*GB",
            "(\\d+[.,]?\\d*)\\s*GByte",
            "(\\d+[.,]?\\d*)\\s*Giga",
            "Verbleibend[\\s:]+(\\d+[.,]?\\d*)\\s*GB",
            "Rest[\\s:]+(\\d+[.,]?\\d*)\\s*GB",
            "Datenvolumen[\\s:]+(\\d+[.,]?\\d*)\\s*GB",
            "(\\d+[.,]?\\d*)\\s*GB\\s*(?:verbleibend|rest)",
            "noch\\s+(\\d+[.,]?\\d*)\\s*GB"
        };
        
        try {
            java.util.regex.Pattern pattern;
            java.util.regex.Matcher matcher;
            
            for (String patternStr : patterns) {
                pattern = java.util.regex.Pattern.compile(patternStr, java.util.regex.Pattern.CASE_INSENSITIVE);
                matcher = pattern.matcher(text);
                if (matcher.find()) {
                    String numberStr = matcher.group(1).replace(",", ".");
                    double value = Double.parseDouble(numberStr);
                    if (value > 0 && value < 1000) { // Plausibilitätsprüfung
                        return value;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting data from text", e);
        }
        
        return -1; // Kein gültiges Datenvolumen gefunden
    }

    // Screenshot-Funktionalität (für Debugging und OCR)
    private void setupMediaProjection() {
        // Hier müsste die MediaProjection eingerichtet werden
        // Dies erfordert zusätzliche Berechtigungen und Intent-Start
        Log.d(TAG, "MediaProjection setup would go here");
    }

    private void captureScreen() {
        // Hier würde der tatsächliche Screenshot-Capture stattfinden
        // Benötigt MediaProjection und ImageReader
        Log.d(TAG, "Screen capture would go here");
    }
}
