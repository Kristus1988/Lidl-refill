package com.example.lidlrefill;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private Button btnStart, btnStop, btnOpenApp, btnOpenSettings, btnDetectApp, btnSwitchToLidl;
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
    private static final String KEY_APP_NAME = "selected_app_name";

    private String selectedAppPackage = null;
    private String selectedAppName = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupButtons();
        checkAccessibilityService();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        refillCount = prefs.getInt(KEY_REFILL_COUNT, 0);
        selectedAppPackage = prefs.getString(KEY_SELECTED_APP, null);
        selectedAppName = prefs.getString(KEY_APP_NAME, null);
        tvRefillCount.setText("🔄 Refills: " + refillCount);

        if (selectedAppPackage != null) {
            btnOpenApp.setText("📱 Lidl App öffnen ✓");
            if (selectedAppName != null) {
                tvServiceStatus.setText("✅ " + selectedAppName + " ausgewählt");
            } else {
                tvServiceStatus.setText("✅ App ausgewählt: " + selectedAppPackage);
            }
            tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"));
            tvServiceStatus.setVisibility(View.VISIBLE);
        }
    }

    private void initViews() {
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnOpenApp = findViewById(R.id.btn_open_app);
        btnOpenSettings = findViewById(R.id.btn_open_settings);
        btnDetectApp = findViewById(R.id.btn_detect_app);
        btnSwitchToLidl = findViewById(R.id.btn_switch_to_lidl);
        tvStatus = findViewById(R.id.tv_status);
        tvVolume = findViewById(R.id.tv_volume);
        tvRefill = findViewById(R.id.tv_refill);
        tvRefillCount = findViewById(R.id.tv_refill_count);
        tvNextCheck = findViewById(R.id.tv_next_check);
        tvServiceStatus = findViewById(R.id.tv_service_status);
        progressStatus = findViewById(R.id.progress_status);
    }

    private void setupButtons() {
        btnOpenSettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        btnSwitchToLidl.setOnClickListener(v -> {
            openLidlApp();
        });

        // 🔍 ALLE Apps anzeigen (OHNE FILTER!)
        btnDetectApp.setOnClickListener(v -> {
            showAllInstalledApps();
        });

        btnOpenApp.setOnClickListener(v -> {
            if (selectedAppPackage != null) {
                openSelectedApp();
            } else {
                Toast.makeText(this, "⚠️ Bitte zuerst eine App auswählen!", Toast.LENGTH_SHORT).show();
                showAllInstalledApps();
            }
        });

        btnStart.setOnClickListener(v -> {
            if (!isServiceEnabled) {
                Toast.makeText(this, "⚠️ Bitte zuerst Accessibility Service aktivieren!", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }

            if (selectedAppPackage == null) {
                Toast.makeText(this, "⚠️ Bitte zuerst eine App auswählen!", Toast.LENGTH_LONG).show();
                showAllInstalledApps();
                return;
            }

            isRunning = true;
            tvStatus.setText("🔄 Überwache Lidl App...");
            tvStatus.setTextColor(Color.parseColor("#4FC3F7"));
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);

            Intent serviceIntent = new Intent(this, RefillAccessibilityService.class);
            serviceIntent.putExtra("action", "start_monitoring");
            serviceIntent.putExtra("target_package", selectedAppPackage);
            startService(serviceIntent);

            updateNextCheckTime();
        });

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

    // 🔍 ALLE INSTALLIERTEN APPS ANZEIGEN (OHNE FILTER!)
    private void showAllInstalledApps() {
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            
            // Nach App-Namen sortieren
            Collections.sort(apps, (a, b) -> {
                String nameA = pm.getApplicationLabel(a).toString();
                String nameB = pm.getApplicationLabel(b).toString();
                return nameA.compareToIgnoreCase(nameB);
            });
            
            List<String> appList = new ArrayList<>();
            List<String> packageList = new ArrayList<>();
            
            for (ApplicationInfo info : apps) {
                String pkg = info.packageName;
                String appName = pm.getApplicationLabel(info).toString();
                
                // Eigene App überspringen
                if (pkg.equals(getPackageName())) continue;
                
                // ALLE Apps anzeigen - KEIN FILTER!
                appList.add("📱 " + appName + " (" + pkg + ")");
                packageList.add(pkg);
            }
            
            if (appList.isEmpty()) {
                Toast.makeText(this, "⚠️ Keine Apps gefunden!", Toast.LENGTH_SHORT).show();
                return;
            }
            
            new AlertDialog.Builder(this)
                .setTitle("📱 Alle installierten Apps (" + appList.size() + ")")
                .setMessage("🔍 Suche nach 'Lidl' oder 'Connect' in der Liste!")
                .setItems(appList.toArray(new String[0]), (dialog, which) -> {
                    String selectedPackage = packageList.get(which);
                    String appName = getAppName(selectedPackage);
                    selectApp(selectedPackage, appName);
                    Toast.makeText(this, "✅ Ausgewählt: " + appName, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Abbrechen", null)
                .setNeutralButton("📱 Play Store", (d, w) -> {
                    try {
                        Intent playStoreIntent = new Intent(Intent.ACTION_VIEW);
                        playStoreIntent.setData(android.net.Uri.parse("market://details?id=de.lidlconnect.android"));
                        startActivity(playStoreIntent);
                    } catch (Exception e) {
                        // Ignorieren
                    }
                })
                .show();
                
        } catch (Exception e) {
            Toast.makeText(this, "⚠️ Fehler: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void openLidlApp() {
        String[] possiblePackages = {
            "de.lidlconnect.android",
            "de.lidl.connect",
            "com.lidlconnect.app",
            "com.lidlconnect.android"
        };
        
        for (String pkg : possiblePackages) {
            try {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launchIntent != null) {
                    startActivity(launchIntent);
                    Toast.makeText(this, "📱 Lidl Connect geöffnet!", Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (Exception e) {
                // Ignorieren
            }
        }
        
        Toast.makeText(this, "⚠️ Lidl Connect nicht gefunden! Bitte installieren.", Toast.LENGTH_LONG).show();
        try {
            Intent playStoreIntent = new Intent(Intent.ACTION_VIEW);
            playStoreIntent.setData(android.net.Uri.parse("market://details?id=de.lidlconnect.android"));
            startActivity(playStoreIntent);
        } catch (Exception e) {
            // Ignorieren
        }
    }

    private String getAppName(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    private void selectApp(String packageName, String appName) {
        selectedAppPackage = packageName;
        selectedAppName = appName;
        
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putString(KEY_SELECTED_APP, packageName)
            .putString(KEY_APP_NAME, appName)
            .apply();
        
        btnOpenApp.setText("📱 Lidl App öffnen ✓");
        
        String displayName = appName != null ? appName : packageName;
        tvServiceStatus.setText("✅ Ausgewählt: " + displayName);
        tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"));
        tvServiceStatus.setVisibility(View.VISIBLE);
        Toast.makeText(this, "✅ Ausgewählt: " + displayName, Toast.LENGTH_LONG).show();
    }

    private void openSelectedApp() {
        if (selectedAppPackage == null) {
            Toast.makeText(this, "⚠️ Keine App ausgewählt!", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(selectedAppPackage);
            if (launchIntent != null) {
                startActivity(launchIntent);
                Toast.makeText(this, "📱 Öffne Lidl App", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "⚠️ App kann nicht gestartet werden", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "⚠️ Fehler: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
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
        int delay = random.nextInt(120) + 60;
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
