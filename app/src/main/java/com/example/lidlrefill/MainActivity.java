package com.example.lidlrefill;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private Button btnStart, btnStop, btnOpenApp, btnOpenSettings;
    private TextView tvStatus, tvVolume, tvRefill, tvRefillCount, tvNextCheck, tvServiceStatus;
    private ProgressBar progressStatus;

    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private int refillCount = 0;
    private boolean isServiceEnabled = false;
    private Random random = new Random();

    private static final String PREFS_NAME = "RefillRecorderPrefs";
    private static final String KEY_REFILL_COUNT = "refill_count";
    private static final String KEY_BUTTON_X = "button_x";
    private static final String KEY_BUTTON_Y = "button_y";
    private static final String KEY_BUTTON_WIDTH = "button_width";
    private static final String KEY_BUTTON_HEIGHT = "button_height";
    private static final String KEY_IS_RECORDED = "is_recorded";

    private static final String LIDL_APP_PACKAGE = "com.example.lidlconnect"; // Passe an!
    // Echter Package-Name der Lidl Connect App:
    // "de.lidlconnect.app" oder "com.lidlconnect.app"

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupButtons();
        checkAccessibilityService();

        // Letzten Refill-Count laden
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        refillCount = prefs.getInt(KEY_REFILL_COUNT, 0);
        tvRefillCount.setText("🔄 Refills: " + refillCount);
    }

    private void initViews() {
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnOpenApp = findViewById(R.id.btn_open_app);
        btnOpenSettings = findViewById(R.id.btn_open_settings);
        tvStatus = findViewById(R.id.tv_status);
        tvVolume = findViewById(R.id.tv_volume);
        tvRefill = findViewById(R.id.tv_refill);
        tvRefillCount = findViewById(R.id.tv_refill_count);
        tvNextCheck = findViewById(R.id.tv_next_check);
        tvServiceStatus = findViewById(R.id.tv_service_status);
        progressStatus = findViewById(R.id.progress_status);
    }

    private void setupButtons() {
        // Accessibility-Einstellungen öffnen
        btnOpenSettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        // Lidl App öffnen
        btnOpenApp.setOnClickListener(v -> {
            try {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage(LIDL_APP_PACKAGE);
                if (launchIntent != null) {
                    startActivity(launchIntent);
                } else {
                    Toast.makeText(this, "⚠️ Lidl Connect App nicht gefunden!", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "⚠️ Fehler: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        // Starten
        btnStart.setOnClickListener(v -> {
            if (!isServiceEnabled) {
                Toast.makeText(this, "⚠️ Bitte zuerst Accessibility Service aktivieren!", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }

            isRunning = true;
            tvStatus.setText("🔄 Überwache Lidl App...");
            tvStatus.setTextColor(Color.parseColor("#4FC3F7"));
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);

            // Service starten
            Intent serviceIntent = new Intent(this, RefillAccessibilityService.class);
            serviceIntent.putExtra("action", "start_monitoring");
            startService(serviceIntent);

            // Nächste Prüfung simulieren
            updateNextCheckTime();
        });

        // Stoppen
        btnStop.setOnClickListener(v -> {
            isRunning = false;
            Intent serviceIntent = new Intent(this, RefillAccessibilityService.class);
            serviceIntent.putExtra("action", "stop_monitoring");
            startService(serviceIntent);

            tvStatus.setText("⏹️ Gestoppt");
            tvStatus.setTextColor(Color.parseColor("#B0BEC5"));
            tvNextCheck.setText("⏱️ --");
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
        });

        btnStop.setEnabled(false);
    }

    private void checkAccessibilityService() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

        for (AccessibilityServiceInfo info : enabledServices) {
            if (info.getResolveInfo().serviceInfo.packageName.equals(getPackageName())) {
                isServiceEnabled = true;
                tvServiceStatus.setText("✅ Service AKTIV");
                tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"));
                tvServiceStatus.setVisibility(View.VISIBLE);
                return;
            }
        }

        isServiceEnabled = false;
        tvServiceStatus.setText("❌ Service INAKTIV – Bitte aktivieren!");
        tvServiceStatus.setTextColor(Color.parseColor("#F44336"));
        tvServiceStatus.setVisibility(View.VISIBLE);
    }

    private void updateNextCheckTime() {
        int delay = random.nextInt(120) + 60; // 60-180 Sekunden
        tvNextCheck.setText("⏱️ Nächste Prüfung in " + delay + "s");

        mainHandler.postDelayed(() -> {
            if (isRunning && isServiceEnabled) {
                Intent serviceIntent = new Intent(this, RefillAccessibilityService.class);
                serviceIntent.putExtra("action", "check_refill");
                startService(serviceIntent);
                updateNextCheckTime();
            }
        }, delay * 1000L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAccessibilityService();
    }
}
