package com.example.lidlrefill;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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

    // Bekannte Package-Namen der Lidl Connect App
    private static final String[] KNOWN_LIDL_PACKAGES = {
        "de.lidlconnect.android",
        "de.lidl.connect",
        "com.lidlconnect.app",
        "com.lidlconnect.android"
    };

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
        // Accessibility-Einstellungen öffnen
        btnOpenSettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        // 📱 Lidl App öffnen
        btnSwitchToLidl.setOnClickListener(v -> {
            openLidlApp();
        });

        // 🔍 Alle offenen Apps anzeigen (mit Usage Stats)
        btnDetectApp.setOnClickListener(v -> {
            // Prüfen ob Usage Stats Berechtigung aktiv ist
            if (!hasUsageStatsPermission()) {
                new AlertDialog.Builder(this)
                    .setTitle("⚠️ Berechtigung benötigt")
                    .setMessage("Um alle offenen Apps zu erkennen, benötigt die App die Berechtigung 'Nutzungszugriff'.\n\n" +
                               "Bitte aktiviere sie in den Einstellungen.")
                    .setPositiveButton("⚙️ Einstellungen öffnen", (d, w) -> {
                        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    })
                    .setNegativeButton("Abbrechen", null)
                    .show();
                return;
            }
            showAllRunningApps();
        });

        // Lidl App öffnen
        btnOpenApp.setOnClickListener(v -> {
            if (selectedAppPackage != null) {
                openSelectedApp();
            } else {
                Toast.makeText(this, "⚠️ Bitte zuerst eine App auswählen!", Toast.LENGTH_SHORT).show();
                if (hasUsageStatsPermission()) {
                    showAllRunningApps();
                } else {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                }
            }
        });

        // Starten
        btnStart.setOnClickListener(v -> {
            if (!isServiceEnabled) {
                Toast.makeText(this, "⚠️ Bitte zuerst Accessibility Service aktivieren!", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }

            if (selectedAppPackage == null) {
                Toast.makeText(this, "⚠️ Bitte zuerst eine App auswählen!", Toast.LENGTH_LONG).show();
                if (hasUsageStatsPermission()) {
                    showAllRunningApps();
                } else {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                }
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

    // 🔍 PRÜFEN OB USAGE STATS PERMISSION AKTIV IST
    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    // 🔍 ALLE OFFENEN APPS ANZEIGEN (MIT USAGE STATS)
    private void showAllRunningApps() {
        try {
            UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long endTime = System.currentTimeMillis();
            long startTime = endTime - 1000 * 60 * 5; // Letzte 5 Minuten

            List<UsageStats> stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, startTime, endTime);

            if (stats == null || stats.isEmpty()) {
                Toast.makeText(this, "⚠️ Keine App-Nutzungsdaten gefunden!", Toast.LENGTH_SHORT).show();
                // Fallback: Alle installierten Apps anzeigen
                showAllInstalledApps();
                return;
            }

            // Nach letzter Nutzung sortieren (neueste zuerst)
            Collections.sort(stats, new Comparator<UsageStats>() {
                @Override
                public int compare(UsageStats o1, UsageStats o2) {
                    return Long.compare(o2.getLastTimeUsed(), o1.getLastTimeUsed());
                }
            });

            // Eindeutige Apps sammeln
            Set<String> uniquePackages = new HashSet<>();
            List<String> appList = new ArrayList<>();
            List<String> packageList = new ArrayList<>();

            for (UsageStats stat : stats) {
                String pkg = stat.getPackageName();

                // Eigene App überspringen
                if (pkg.equals(getPackageName())) continue;

                // Doppelte vermeiden
                if (uniquePackages.contains(pkg)) continue;
                uniquePackages.add(pkg);

                String appName = getAppName(pkg);

                // Lidl Connect hervorheben
                if (isLidlApp(pkg, appName)) {
                    appList.add("⭐ " + appName + " ⭐ (" + pkg + ")");
                } else {
                    appList.add("📱 " + appName + " (" + pkg + ")");
                }
                packageList.add(pkg);
            }

            // Wenn keine Apps gefunden wurden -> alle installierten Apps anzeigen
            if (appList.isEmpty()) {
                showAllInstalledApps();
                return;
            }

            // Dialog mit allen offenen Apps
            new AlertDialog.Builder(this)
                .setTitle("📱 Wähle die Lidl Connect App aus")
                .setMessage("⭐ = Lidl App erkannt")
                .setItems(appList.toArray(new String[0]), (dialog, which) -> {
                    String selectedPackage = packageList.get(which);
                    String appName = getAppName(selectedPackage);
                    selectApp(selectedPackage, appName);
                    Toast.makeText(this, "✅ Ausgewählt: " + appName, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Abbrechen", null)
                .setNeutralButton("🔄 Alle Apps anzeigen", (d, w) -> showAllInstalledApps())
                .show();

        } catch (SecurityException e) {
            Toast.makeText(this, "⚠️ Keine Berechtigung! Bitte in Einstellungen aktivieren.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "⚠️ Fehler: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            // Fallback: Alle installierten Apps anzeigen
            showAllInstalledApps();
        }
    }

    // 🔍 ALLE INSTALLIERTEN APPS ANZEIGEN (FALLBACK)
    private void showAllInstalledApps() {
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            
            List<String> appList = new ArrayList<>();
            List<String> packageList = new ArrayList<>();
            
            for (ApplicationInfo info : apps) {
                String pkg = info.packageName;
                String appName = pm.getApplicationLabel(info).toString();
                
                // Eigene App überspringen
                if (pkg.equals(getPackageName())) continue;
                
                // Lidl Connect hervorheben
                if (isLidlApp(pkg, appName)) {
                    appList.add("⭐ " + appName + " ⭐ (" + pkg + ")");
                } else {
                    appList.add("📱 " + appName + " (" + pkg + ")");
                }
                packageList.add(pkg);
            }
            
            if (appList.isEmpty()) {
                Toast.makeText(this, "⚠️ Keine Apps gefunden!", Toast.LENGTH_SHORT).show();
                return;
            }
            
            new AlertDialog.Builder(this)
                .setTitle("📱 Alle installierten Apps")
                .setMessage("⭐ = Lidl App erkannt")
                .setItems(appList.toArray(new String[0]), (dialog, which) -> {
                    String selectedPackage = packageList.get(which);
                    String appName = getAppName(selectedPackage);
                    selectApp(selectedPackage, appName);
                    Toast.makeText(this, "✅ Ausgewählt: " + appName, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
                
        } catch (Exception e) {
            Toast.makeText(this, "⚠️ Fehler: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isLidlApp(String packageName, String appName) {
        if (packageName == null) return false;
        
        String pkgLower = packageName.toLowerCase();
        String nameLower = appName != null ? appName.toLowerCase() : "";
        
        // Lidl Plus ausschließen
        if (pkgLower.contains("plus") || nameLower.contains("plus")) {
            return false;
        }
        
        // Lidl Connect erkennen
        return pkgLower.contains("lidlconnect") || 
               pkgLower.contains("lidl.connect") ||
               (pkgLower.contains("lidl") && pkgLower.contains("connect")) ||
               nameLower.contains("lidl connect");
    }

    private void openLidlApp() {
        for (String pkg : KNOWN_LIDL_PACKAGES) {
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
