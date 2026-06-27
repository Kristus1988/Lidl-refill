package com.example.lidlrefill;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
    private static final String KEY_SELECTED_APP = "selected_app_package";

    private String selectedAppPackage = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupButtons();
        checkAccessibilityService();

        // Gespeicherte App aus Preferences laden
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        refillCount = prefs.getInt(KEY_REFILL_COUNT, 0);
        selectedAppPackage = prefs.getString(KEY_SELECTED_APP, null);
        tvRefillCount.setText("🔄 Refills: " + refillCount);

        // Wenn bereits eine App gespeichert ist, Button-Text anpassen
        if (selectedAppPackage != null) {
            btnOpenApp.setText("📱 Lidl App öffnen ✓");
        }
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

        // Lidl App öffnen (mit App-Picker)
        btnOpenApp.setOnClickListener(v -> {
            if (selectedAppPackage != null) {
                // Gespeicherte App direkt öffnen
                try {
                    Intent launchIntent = getPackageManager().getLaunchIntentForPackage(selectedAppPackage);
                    if (launchIntent != null) {
                        startActivity(launchIntent);
                        Toast.makeText(this, "📱 Öffne Lidl App", Toast.LENGTH_SHORT).show();
                        return;
                    } else {
                        // Gespeicherte App nicht mehr vorhanden
                        selectedAppPackage = null;
                        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        prefs.edit().remove(KEY_SELECTED_APP).apply();
                        btnOpenApp.setText("📱 Lidl App öffnen");
                        Toast.makeText(this, "⚠️ Gespeicherte App nicht gefunden! Bitte neu auswählen.", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "⚠️ Fehler: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
            
            // App-Picker öffnen
            showAppPicker();
        });

        // Starten
        btnStart.setOnClickListener(v -> {
            if (!isServiceEnabled) {
                Toast.makeText(this, "⚠️ Bitte zuerst Accessibility Service aktivieren!", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }

            if (selectedAppPackage == null) {
                Toast.makeText(this, "⚠️ Bitte zuerst die Lidl App auswählen!", Toast.LENGTH_LONG).show();
                showAppPicker();
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
            serviceIntent.putExtra("target_package", selectedAppPackage);
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

    private void showAppPicker() {
        // Alle installierten Apps auflisten, die gestartet werden können
        Intent mainIntent = new Intent(Intent.ACTION_MAIN);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        
        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
        
        // Erstelle eine Liste mit App-Namen und Package-Namen
        String[] appNames = new String[apps.size()];
        String[] packageNames = new String[apps.size()];
        
        for (int i = 0; i < apps.size(); i++) {
            ResolveInfo info = apps.get(i);
            appNames[i] = info.loadLabel(pm).toString() + " (" + info.activityInfo.packageName + ")";
            packageNames[i] = info.activityInfo.packageName;
        }
        
        // Dialog mit App-Liste anzeigen
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("📱 Wähle die Lidl Connect App aus");
        builder.setItems(appNames, (dialog, which) -> {
            String selectedPackage = packageNames[which];
            
            // Prüfen, ob es sich um eine Lidl App handelt
            if (selectedPackage.toLowerCase().contains("lidl")) {
                selectedAppPackage = selectedPackage;
                
                // In Preferences speichern
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_SELECTED_APP, selectedPackage).apply();
                
                btnOpenApp.setText("📱 Lidl App öffnen ✓");
                Toast.makeText(this, "✅ Lidl App ausgewählt: " + selectedPackage, Toast.LENGTH_LONG).show();
                
                // App direkt öffnen
                try {
                    Intent launchIntent = getPackageManager().getLaunchIntentForPackage(selectedPackage);
                    if (launchIntent != null) {
                        startActivity(launchIntent);
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "⚠️ Fehler beim Öffnen: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "⚠️ Das ist nicht die Lidl App! Bitte wähle die richtige App aus.", Toast.LENGTH_LONG).show();
                // Erneut den Picker öffnen
                showAppPicker();
            }
        });
        
        builder.setNegativeButton("Abbrechen", (dialog, which) -> dialog.dismiss());
        builder.show();
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
                serviceIntent.putExtra("target_package", selectedAppPackage);
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
