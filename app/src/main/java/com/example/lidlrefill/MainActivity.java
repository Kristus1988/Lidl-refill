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
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private Button btnStart, btnStop, btnOpenApp, btnOpenSettings, btnManualSelect;
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

    // Bekannte Package-Namen der Lidl Connect App
    private static final String[] KNOWN_LIDL_PACKAGES = {
        "de.lidlconnect.android",
        "de.lidl.connect"
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
        tvRefillCount.setText("🔄 Refills: " + refillCount);

        // Wenn bereits eine App gespeichert ist, Button-Text anpassen
        if (selectedAppPackage != null) {
            btnOpenApp.setText("📱 Lidl App öffnen ✓");
            tvServiceStatus.setText("✅ App ausgewählt: " + selectedAppPackage);
            tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"));
            tvServiceStatus.setVisibility(View.VISIBLE);
        }
    }

    private void initViews() {
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnOpenApp = findViewById(R.id.btn_open_app);
        btnOpenSettings = findViewById(R.id.btn_open_settings);
        btnManualSelect = findViewById(R.id.btn_manual_select);
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

        // Manuelle Auswahl der Lidl App
        btnManualSelect.setOnClickListener(v -> showManualSelection());

        // Lidl App öffnen
        btnOpenApp.setOnClickListener(v -> {
            if (selectedAppPackage != null) {
                openSelectedApp();
            } else {
                Toast.makeText(this, "⚠️ Bitte zuerst die Lidl App auswählen!", Toast.LENGTH_SHORT).show();
                showManualSelection();
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
                Toast.makeText(this, "⚠️ Bitte zuerst die Lidl App auswählen!", Toast.LENGTH_LONG).show();
                showManualSelection();
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

    private void showManualSelection() {
        // Dialog mit Auswahlmöglichkeiten
        String[] options = new String[KNOWN_LIDL_PACKAGES.length + 2];
        System.arraycopy(KNOWN_LIDL_PACKAGES, 0, options, 0, KNOWN_LIDL_PACKAGES.length);
        options[KNOWN_LIDL_PACKAGES.length] = "📝 Anderen Package-Namen eingeben";
        options[KNOWN_LIDL_PACKAGES.length + 1] = "🔍 Package-Namen aus App-Liste suchen";

        new AlertDialog.Builder(this)
            .setTitle("📱 Lidl Connect App auswählen")
            .setItems(options, (dialog, which) -> {
                if (which < KNOWN_LIDL_PACKAGES.length) {
                    // Bekannter Package-Name
                    String pkg = KNOWN_LIDL_PACKAGES[which];
                    if (isAppInstalled(pkg)) {
                        selectApp(pkg);
                    } else {
                        Toast.makeText(this, "⚠️ App nicht gefunden: " + pkg, Toast.LENGTH_SHORT).show();
                    }
                } else if (which == KNOWN_LIDL_PACKAGES.length) {
                    // Manuelle Eingabe
                    showPackageInputDialog();
                } else {
                    // App-Liste anzeigen (nur Apps mit "Lidl" im Namen)
                    showFilteredAppList();
                }
            })
            .setNegativeButton("Abbrechen", null)
            .show();
    }

    private void showPackageInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📝 Package-Namen eingeben");

        final EditText input = new EditText(this);
        input.setHint("z.B. de.lidlconnect.android");
        input.setText("de.lidlconnect.android");
        builder.setView(input);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String pkg = input.getText().toString().trim();
            if (!pkg.isEmpty()) {
                if (isAppInstalled(pkg)) {
                    selectApp(pkg);
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
        java.util.List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        
        java.util.List<String> lidlApps = new java.util.ArrayList<>();
        java.util.List<String> lidlPackages = new java.util.ArrayList<>();
        
        for (ApplicationInfo info : apps) {
            String appName = pm.getApplicationLabel(info).toString().toLowerCase();
            String pkg = info.packageName.toLowerCase();
            
            // Suche nach "lidl" oder "connect" im Namen oder Package
            if (appName.contains("lidl") || appName.contains("connect") ||
                pkg.contains("lidl") || pkg.contains("connect")) {
                lidlApps.add(pm.getApplicationLabel(info).toString() + " (" + info.packageName + ")");
                lidlPackages.add(info.packageName);
            }
        }
        
        // Falls keine Lidl-App gefunden wurde, zeige alle Apps an
        if (lidlApps.isEmpty()) {
            Toast.makeText(this, "⚠️ Keine Lidl-App gefunden! Bitte Package-Namen manuell eingeben.", Toast.LENGTH_SHORT).show();
            showPackageInputDialog();
            return;
        }
        
        new AlertDialog.Builder(this)
            .setTitle("📱 Gefundene Lidl-Apps")
            .setItems(lidlApps.toArray(new String[0]), (dialog, which) -> {
                String pkg = lidlPackages.get(which);
                selectApp(pkg);
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

    private void selectApp(String packageName) {
        selectedAppPackage = packageName;
        
        // In Preferences speichern
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_SELECTED_APP, packageName).apply();
        
        btnOpenApp.setText("📱 Lidl App öffnen ✓");
        
        // App-Namen für Toast ermitteln
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            String appName = pm.getApplicationLabel(info).toString();
            tvServiceStatus.setText("✅ Ausgewählt: " + appName);
            tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"));
            tvServiceStatus.setVisibility(View.VISIBLE);
            Toast.makeText(this, "✅ Ausgewählt: " + appName, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            tvServiceStatus.setText("✅ Ausgewählt: " + packageName);
            tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"));
            tvServiceStatus.setVisibility(View.VISIBLE);
            Toast.makeText(this, "✅ Ausgewählt: " + packageName, Toast.LENGTH_LONG).show();
        }
        
        // App direkt öffnen
        openSelectedApp();
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
        java.util.List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(
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
