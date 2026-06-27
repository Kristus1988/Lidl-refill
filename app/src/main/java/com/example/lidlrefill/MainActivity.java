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

        // 🔍 Lidl Connect App suchen (NICHT über ActivityManager!)
        btnDetectApp.setOnClickListener(v -> {
            detectLidlApp();
        });

        // Lidl App öffnen
        btnOpenApp.setOnClickListener(v -> {
            if (selectedAppPackage != null) {
                openSelectedApp();
            } else {
                Toast.makeText(this, "⚠️ Bitte zuerst eine App erkennen!", Toast.LENGTH_SHORT).show();
                detectLidlApp();
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
                detectLidlApp();
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

    // 🔍 LIDL CONNECT APP SUCHEN (NUR ÜBER PACKAGE MANAGER)
    private void detectLidlApp() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        
        List<String> foundApps = new ArrayList<>();
        List<String> foundPackages = new ArrayList<>();
        
        // Lidl Connect Package-Namen
        String[] lidlPackages = {
            "de.lidlconnect.android",
            "de.lidl.connect",
            "com.lidlconnect.app",
            "com.lidlconnect.android"
        };
        
        // 1. Direkt nach bekannten Package-Namen suchen
        for (String pkg : lidlPackages) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                String name = pm.getApplicationLabel(info).toString();
                foundApps.add("⭐ " + name + " (" + pkg + ")");
                foundPackages.add(pkg);
            } catch (Exception e) {
                // Nicht gefunden
            }
        }
        
        // 2. Nach "Lidl Connect" im Namen suchen
        for (ApplicationInfo info : apps) {
            String appName = pm.getApplicationLabel(info).toString().toLowerCase();
            String pkg = info.packageName.toLowerCase();
            
            // Lidl Plus ausschließen
            if (appName.contains("plus") || pkg.contains("plus")) {
                continue;
            }
            
            if (appName.contains("lidl connect") || 
                appName.contains("lidlconnect") ||
                pkg.contains("lidlconnect") ||
                pkg.contains("lidl.connect")) {
                
                String name = pm.getApplicationLabel(info).toString();
                // Nur hinzufügen, wenn nicht schon in der Liste
                if (!foundPackages.contains(info.packageName)) {
                    foundApps.add("📱 " + name + " (" + info.packageName + ")");
                    foundPackages.add(info.packageName);
                }
            }
        }
        
        // 3. Falls gefunden -> auswählen lassen
        if (!foundApps.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("✅ Lidl Connect App gefunden!")
                .setItems(foundApps.toArray(new String[0]), (dialog, which) -> {
                    String pkg = foundPackages.get(which);
                    String name = getAppName(pkg);
                    selectApp(pkg, name);
                    Toast.makeText(this, "✅ Ausgewählt: " + name, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
        } else {
            // 4. Nichts gefunden -> manuelle Eingabe
            new AlertDialog.Builder(this)
                .setTitle("⚠️ Lidl Connect nicht gefunden")
                .setMessage("Die Lidl Connect App wurde nicht automatisch gefunden.\n\n" +
                           "Mögliche Gründe:\n" +
                           "• App ist nicht installiert\n" +
                           "• App heißt anders\n\n" +
                           "Möchtest du den Package-Namen manuell eingeben?")
                .setPositiveButton("📝 Manuell eingeben", (d, w) -> showPackageInputDialog())
                .setNegativeButton("Abbrechen", null)
                .show();
        }
    }

    private void openLidlApp() {
        String[] possiblePackages = {
            "de.lidlconnect.android",
            "de.lidl.connect",
            "com.lidlconnect.app"
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
        
        // Falls nicht gefunden -> Play Store
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
