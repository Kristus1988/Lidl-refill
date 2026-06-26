package com.example.lidlrefill;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class MainActivity extends AppCompatActivity {
    
    // UI-Elemente
    private EditText etUsername, etPassword;
    private TextView tvStatus, tvCurrentGb, tvRefillCount, tvDisplayNumber;
    private TextView tvLoginStatus, tvPhaseInfo, tvInklusiv, tvRefill, tvTarifInfo;
    private TextView tvChromeDriverStatus;
    private ProgressBar progressChromeDriver;
    private View vStatusIndicator;
    private Button btnStart, btnStop;
    private LinearLayout layoutBattery;
    private LinearLayout layoutChromeDriver;
    
    private TextView tvInternetStatus, tvBatteryStatus;
    
    private RefillService refillService;
    private SharedPreferences sharedPreferences;
    
    // Feste Werte
    private static final int DEFAULT_INTERVAL = 2;
    private static final float DEFAULT_TARGET = 0.15f;
    private static final int DEFAULT_WAIT_AFTER = 25;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        initEncryptedStorage();
        loadSavedData();
        setupButtons();
        updateDisplayNumber();
        checkAllPermissions();
        resetChromeDriverStatus();
    }
    
    private void initViews() {
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        tvStatus = findViewById(R.id.tv_status);
        tvCurrentGb = findViewById(R.id.tv_status);
        tvRefillCount = findViewById(R.id.tv_refill_count);
        tvDisplayNumber = findViewById(R.id.tv_display_number);
        tvLoginStatus = findViewById(R.id.tv_login_status);
        tvPhaseInfo = findViewById(R.id.tv_phase_info);
        tvInklusiv = findViewById(R.id.tv_inklusiv);
        tvRefill = findViewById(R.id.tv_refill);
        tvTarifInfo = findViewById(R.id.tv_tarif_info);
        vStatusIndicator = findViewById(R.id.v_status_indicator);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        layoutBattery = findViewById(R.id.layout_battery);
        layoutChromeDriver = findViewById(R.id.layout_chromedriver);
        tvChromeDriverStatus = findViewById(R.id.tv_chromedriver_status);
        progressChromeDriver = findViewById(R.id.progress_chromedriver);
        
        tvInternetStatus = findViewById(R.id.tv_internet_status);
        tvBatteryStatus = findViewById(R.id.tv_battery_status);
        
        // Akku-Optimierung: Klick öffnet Einstellungen
        layoutBattery.setOnClickListener(v -> openBatterySettings());
        
        refillService = new RefillService(this);
        refillService.setStatusListener(new RefillService.StatusListener() {
            @Override
            public void onStatusUpdate(String status) {
                runOnUiThread(() -> tvStatus.setText(status));
            }
            
            @Override
            public void onGbUpdate(String gb) {
                runOnUiThread(() -> tvCurrentGb.setText("📊 " + gb));
            }
            
            @Override
            public void onRefillCountUpdate(int count) {
                runOnUiThread(() -> tvRefillCount.setText("🔄 Refills: " + count));
            }
            
            @Override
            public void onLoginNumber(String number) {
                runOnUiThread(() -> tvDisplayNumber.setText("📱 " + number));
            }
            
            @Override
            public void onLoginStatusUpdate(RefillService.LoginStatus status, String details) {
                runOnUiThread(() -> updateLoginStatusUI(status, details));
            }
            
            @Override
            public void onPhaseUpdate(String phase, String details) {
                runOnUiThread(() -> tvPhaseInfo.setText("📍 " + phase + " | ⏱️ " + details));
            }
            
            @Override
            public void onInklusivUpdate(String inklusiv, String refill) {
                runOnUiThread(() -> {
                    tvInklusiv.setText("📦 Inklusiv: " + inklusiv);
                    tvRefill.setText("🔄 Refill: " + refill);
                });
            }
            
            @Override
            public void onTarifDetected(String tarifName, int maxGb) {
                runOnUiThread(() -> tvTarifInfo.setText("📶 Tarif: Unlimited on Demand " + tarifName + " (" + maxGb + " GB)"));
            }
            
            @Override
            public void onChromeDriverProgress(int progress) {
                runOnUiThread(() -> {
                    progressChromeDriver.setProgress(progress);
                    progressChromeDriver.setVisibility(View.VISIBLE);
                    if (progress < 100) {
                        tvChromeDriverStatus.setText("⬇️ Lade ChromeDriver herunter... " + progress + "%");
                        tvChromeDriverStatus.setTextColor(Color.parseColor("#4FC3F7"));
                    } else {
                        tvChromeDriverStatus.setText("✅ ChromeDriver bereit!");
                        tvChromeDriverStatus.setTextColor(Color.parseColor("#4CAF50"));
                    }
                });
            }
        });
    }
    
    private void resetChromeDriverStatus() {
        tvChromeDriverStatus.setText("⏳ Bereit zum Download");
        tvChromeDriverStatus.setTextColor(Color.parseColor("#FFA726"));
        progressChromeDriver.setProgress(0);
        progressChromeDriver.setVisibility(View.GONE);
    }
    
    private void updateLoginStatusUI(RefillService.LoginStatus status, String details) {
        tvLoginStatus.setText(details);
        
        int color;
        switch (status) {
            case LOGGED_IN:
            case INKLUSIV_AVAILABLE:
                color = Color.parseColor("#4CAF50");
                break;
            case LOGGING_IN:
                color = Color.parseColor("#FFC107");
                break;
            case NOT_LOGGED_IN:
                color = Color.parseColor("#F44336");
                break;
            case REFILL_ACTIVATED:
                color = Color.parseColor("#2196F3");
                break;
            case REFILL_FAILED:
            case ERROR:
                color = Color.parseColor("#FF5722");
                break;
            case INKLUSIV_DEPLETED:
                color = Color.parseColor("#FF9800");
                break;
            default:
                color = Color.parseColor("#9E9E9E");
                break;
        }
        vStatusIndicator.setBackgroundColor(color);
        tvLoginStatus.setTextColor(color);
    }
    
    private void initEncryptedStorage() {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            sharedPreferences = EncryptedSharedPreferences.create(
                "secure_prefs",
                masterKeyAlias,
                this,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            sharedPreferences = getSharedPreferences("prefs", Context.MODE_PRIVATE);
        }
    }
    
    private void loadSavedData() {
        etUsername.setText(sharedPreferences.getString("username", ""));
        etPassword.setText(sharedPreferences.getString("password", ""));
    }
    
    private void saveData() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("username", etUsername.getText().toString());
        editor.putString("password", etPassword.getText().toString());
        editor.apply();
    }
    
    private void updateDisplayNumber() {
        String username = etUsername.getText().toString();
        if (!username.isEmpty()) {
            String formatted = cleanPhoneNumber(username);
            tvDisplayNumber.setText("📱 Login mit: " + formatted);
        } else {
            tvDisplayNumber.setText("📱 Bitte Nummer eingeben");
        }
    }
    
    private String cleanPhoneNumber(String number) {
        if (TextUtils.isEmpty(number)) return number;
        number = number.replaceAll("[\\s.-]", "");
        if (number.startsWith("00")) {
            number = "+" + number.substring(2);
        }
        if (number.startsWith("0") && !number.startsWith("+")) {
            number = "+49" + number.substring(1);
        }
        if (!number.startsWith("+") && !number.startsWith("00")) {
            if (number.matches("\\d{10,11}")) {
                number = "+49" + number;
            }
        }
        if (number.startsWith("+49+49")) {
            number = "+49" + number.substring(6);
        }
        return number;
    }
    
    private void setupButtons() {
        btnStart.setOnClickListener(v -> {
            // 1. Prüfe Berechtigungen
            if (!PermissionHelper.isBatteryOptimizationDisabled(this)) {
                Toast.makeText(this, "⚠️ Bitte Akku-Optimierung deaktivieren!", Toast.LENGTH_LONG).show();
                return;
            }
            
            // 2. Prüfe Zugangsdaten
            String username = etUsername.getText().toString();
            String password = etPassword.getText().toString();
            
            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Bitte Benutzername und Passwort eingeben!", Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (!isValidPhoneNumber(username)) {
                Toast.makeText(this, "Ungültige Telefonnummer!", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 3. Alles ok -> starten
            saveData();
            
            int interval = DEFAULT_INTERVAL;
            float target = DEFAULT_TARGET;
            int waitAfter = DEFAULT_WAIT_AFTER;
            
            String loginUsername = cleanPhoneNumber(username);
            tvDisplayNumber.setText("📱 Login mit: " + loginUsername);
            
            tvStatus.setText("🔄 Starte...");
            tvLoginStatus.setText("🔄 Starte...");
            
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            
            refillService.start(loginUsername, password, interval, target, waitAfter);
        });
        
        btnStop.setOnClickListener(v -> {
            refillService.stop();
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
            tvStatus.setText("⏹️ Gestoppt");
            tvDisplayNumber.setText("📱 " + cleanPhoneNumber(etUsername.getText().toString()));
            resetChromeDriverStatus();
        });
        
        btnStop.setEnabled(false);
        
        etUsername.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                updateDisplayNumber();
            }
        });
    }
    
    private boolean isValidPhoneNumber(String number) {
        if (TextUtils.isEmpty(number)) return false;
        String cleaned = number.replaceAll("[\\s.-]", "");
        if (cleaned.startsWith("+49") || cleaned.startsWith("0049") || cleaned.startsWith("0")) {
            String digits = cleaned.replaceAll("[^0-9+]", "");
            if (digits.startsWith("+49")) {
                int digitCount = digits.substring(3).length();
                return digitCount >= 10 && digitCount <= 11;
            }
            return true;
        }
        return false;
    }
    
    private void checkAllPermissions() {
        if (tvInternetStatus != null) {
            tvInternetStatus.setText("✅ Aktiviert");
            tvInternetStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        }
        
        boolean batteryGranted = PermissionHelper.isBatteryOptimizationDisabled(this);
        if (tvBatteryStatus != null) {
            if (batteryGranted) {
                tvBatteryStatus.setText("✅ Aktiviert");
                tvBatteryStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
                tvBatteryStatus.setOnClickListener(null);
            } else {
                tvBatteryStatus.setText("❌ Nicht aktiviert (Zum Aktivieren klicken)");
                tvBatteryStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                tvBatteryStatus.setOnClickListener(v -> openBatterySettings());
            }
        }
    }
    
    private void openBatterySettings() {
        Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        checkAllPermissions();
    }
}
