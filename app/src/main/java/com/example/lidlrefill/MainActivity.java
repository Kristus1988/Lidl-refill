package com.example.lidlrefill;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
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
            // Versuche Lidl App zu öffnen
            for (String pkg : KNOWN_LIDL_PACKAGES) {
                try {
                    Intent launchIntent = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launchIntent != null) {
                        startActivity(launchIntent);
                        Toast.makeText(this, "📱 Lidl App geöffnet! Kehre zurück und klicke auf '🔍 Erkennen'", Toast.LENGTH_LONG).show();
                        return;
                    }
                } catch (Exception e) {
                    // Ignorieren
                }
            }
            
            // Fallback: Play Store
            Toast.makeText(this, "⚠️ Lidl App nicht gefunden! Bitte manuell öffnen.", Toast.LENGTH_SHORT).show();
            try {
                Intent playStoreIntent = new Intent(Intent.ACTION_VIEW);
                playStoreIntent.setData(android.net.Uri.parse("market://details?id=de.lidlconnect.android"));
                startActivity(playStoreIntent);
            } catch (Exception e) {
                // Ignorieren
            }
        });

        // 🔍 Aktuelle App erkennen (über mehrere Methoden)
        btnDetectApp.setOnClickListener(v -> {
            detectCurrentApp();
        });

        // Lidl App öffnen
        btnOpenApp.setOnClickListener(v -> {
            if (selectedAppPackage != null) {
                openSelectedApp();
            } else {
                Toast.makeText(this, "⚠️ Bitte zuerst eine App erkennen!", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "⚠️ Bitte zuerst die Lidl App erkennen!", Toast.LENGTH_LONG).show();
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

    // 🔍 AKTUELLE APP ERKENNEN (MEHRERE METHODEN)
    private void detectCurrentApp() {
        String detectedPackage = null;
        String detectedName = null;

        // Methode 1: Über ActivityManager (geöffnete Apps)
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(50);
            
            for (ActivityManager.RunningTaskInfo task : tasks) {
                String pkg = task.topActivity.getPackageName();
                if (pkg.equals(getPackageName())) continue;
                
                String name = getAppName(pkg);
                if (isLidlApp(pkg, name)) {
                    detectedPackage = pkg;
                    detectedName = name;
                    break;
                }
            }
        } catch (Exception e) {
            // Ignorieren
        }

        // Methode 2: Über installierte Apps (falls Methode 1 nichts gefunden hat)
        if (detectedPackage == null) {
            try {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                
                for (ApplicationInfo info : apps) {
                    String pkg = info.packageName;
                    String name = pm.getApplicationLabel(info).toString();
                    
                    if (isLidlApp(pkg, name)) {
                        detectedPackage = pkg;
                        detectedName = name;
                        break;
                    }
                }
            } catch (Exception e) {
                // Ignorieren
            }
        }

        // Methode 3: Manuelle Auswahl (falls nichts gefunden)
        if (detectedPackage != null) {
            selectApp(detectedPackage, detectedName);
            Toast.makeText(this, "✅ Lidl App erkannt: " + detectedName, Toast.LENGTH_LONG).show();
        } else {
            // Frage ob manuell auswählen
            new AlertDialog.Builder(this)
                .setTitle("⚠️ Keine Lidl App erkannt")
                .setMessage("Die Lidl App konnte nicht automatisch erkannt werden.\n\n" +
                           "Möchtest du sie manuell auswählen oder eingeben?")
                .setPositiveButton("📝 Manuell auswählen", (d, w) -> showManualSelection())
                .setNegativeButton("Abbrechen", null)
                .show();
        }
    }

    private boolean isLidlApp(String packageName, String appName) {
        if (packageName == null) return false;
        
        String pkgLower = packageName.toLowerCase();
        String nameLower = appName != null ? appName.toLowerCase() : "";
        
        // Bekannte Package-Namen prüfen
        for (String known : KNOWN_LIDL_PACKAGES) {
            if (pkgLower.equals(known)) return true;
        }
        
        // Enthält "lidl" oder "connect"
        return pkgLower.contains("lidl") || pkgLower.contains("connect") ||
               nameLower.contains("lidl") || nameLower.contains("connect");
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

    private void showManualSelection() {
        String[] options = {
            "📝 Package-Namen eingeben",
            "🔍 Gefundene Lidl-Apps anzeigen",
            "📱 Lidl App öffnen und dann erkennen"
        };

        new AlertDialog.Builder(this)
            .setTitle("📱 Lidl Connect App auswählen")
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    showPackageInputDialog();
                } else if (which == 1) {
                    showFilteredAppList();
                } else {
                    btnSwitchToLidl.performClick();
                }
            })
            .setNegativeButton("Abbrechen", null)
            .show();
    }

    private void showPackageInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📝 Package-Namen eingeben");

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("z.B. de.lidlconnect.android");
        input.setText("de.lidlconnect.android");
        builder.setView(input);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String pkg = input.getText().toString().trim();
            if (!pkg.isEmpty()) {
                if (isAppInstalled(pkg)) {
                    String name = getAppName(pkg);
                    selectApp(pkg, name);
                } else {
                    Toast.makeText(this, "⚠️ App nicht gefunden: " + pkg, Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("Abbrechen", null);
        builder.show();
    }

    private void showFilteredAppList() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        
        java.util.List<String> lidlApps = new java.util.ArrayList<>();
        java.util.List<String> lidlPackages = new java.util.ArrayList<>();
        
        for (ApplicationInfo info : apps) {
            String appName = pm.getApplicationLabel(info).toString();
            String pkg = info.packageName;
            
            if (isLidlApp(pkg, appName)) {
                lidlApps.add(appName + " (" + pkg + ")");
                lidlPackages.add(pkg);
            }
        }
        
        if (lidlApps.isEmpty()) {
            Toast.makeText(this, "⚠️ Keine Lidl-App gefunden!", Toast.LENGTH_SHORT).show();
            showPackageInputDialog();
            return;
        }
        
        new AlertDialog.Builder(this)
            .setTitle("📱 Gefundene Lidl-Apps")
            .setItems(lidlApps.toArray(new String[0]), (dialog, which) -> {
                String pkg = lidlPackages.get(which);
                String name = getAppName(pkg);
                selectApp(pkg, name);
            })
            .setNegativeButton("Abbrechen", null)
            .show();
    }

    private boolean isAppInstalled(String packageName) {
        try {
            getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
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
