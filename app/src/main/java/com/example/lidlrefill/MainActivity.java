package com.example.lidlrefill;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class MainActivity extends AppCompatActivity {
    
    private EditText etUsername, etPassword, etInterval, etTarget, etWaitAfter;
    private TextView tvStatus, tvCurrentGb, tvRefillCount, tvDisplayNumber;
    private TextView tvLoginStatus, tvPhaseInfo, tvInklusiv, tvRefill, tvTarifInfo;
    private View vStatusIndicator;
    private Button btnStart, btnStop;
    
    private LinearLayout layoutPermissionInternet, layoutPermissionNetwork;
    private LinearLayout layoutPermissionWakeLock, layoutPermissionForeground;
    private LinearLayout layoutPermissionBattery, layoutPermissionAutostart;
    
    private TextView tvInternetStatus, tvNetworkStatus, tvWakeLockStatus;
    private TextView tvForegroundStatus, tvBatteryStatus, tvAutostartStatus;
    
    private RefillService refillService;
    private SharedPreferences sharedPreferences;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        initPermissionViews();
        initEncryptedStorage();
        loadSavedData();
        setupButtons();
        updateDisplayNumber();
        checkAllPermissions();
    }
    
    private void initViews() {
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        etInterval = findViewById(R.id.et_interval);
        etTarget = findViewById(R.id.et_target);
        etWaitAfter = findViewById(R.id.et_wait_after);
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
        });
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
    
    private void initPermissionViews() {
        layoutPermissionInternet = findViewById(R.id.layout_permission_internet);
        layoutPermissionNetwork = findViewById(R.id.layout_permission_network);
        layoutPermissionWakeLock = findViewById(R.id.layout_permission_wakelock);
        layoutPermissionForeground = findViewById(R.id.layout_permission_foreground);
        layoutPermissionBattery = findViewById(R.id.layout_permission_battery);
        layoutPermissionAutostart = findViewById(R.id.layout_permission_autostart);
        
        tvInternetStatus = findViewById(R.id.tv_internet_status);
        tvNetworkStatus = findViewById(R.id.tv_network_status);
        tvWakeLockStatus = findViewById(R.id.tv_wakelock_status);
        tvForegroundStatus = findViewById(R.id.tv_foreground_status);
        tvBatteryStatus = findViewById(R.id.tv_battery_status);
        tvAutostartStatus = findViewById(R.id.tv_autostart_status);
        
        layoutPermissionInternet.setOnClickListener(v -> openAppSettings());
        layoutPermissionNetwork.setOnClickListener(v -> openAppSettings());
        layoutPermissionWakeLock.setOnClickListener(v -> openAppSettings());
        layoutPermissionForeground.setOnClickListener(v -> openAppSettings());
        
        layoutPermissionBattery.setOnClickListener(v -> {
            Intent intent = PermissionHelper.getBatterySettingsIntent();
            if (intent != null) {
                startActivity(intent);
            } else {
                openAppSettings();
            }
        });
        
        layoutPermissionAutostart.setOnClickListener(v -> {
            Intent intent = PermissionHelper.getAutostartIntent(this);
            if (intent != null) {
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, 
                        "Bitte manuell in den Einstellungen nach 'Autostart' suchen", 
                        Toast.LENGTH_LONG).show();
                    openAppSettings();
                }
            } else {
                openAppSettings();
            }
        });
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
        etInterval.setText(String.valueOf(sharedPreferences.getInt("interval", 2)));
        etTarget.setText(String.valueOf(sharedPreferences.getFloat("target", 0.15f)));
        etWaitAfter.setText(String.valueOf(sharedPreferences.getInt("wait_after", 25)));
    }
    
    private void saveData() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("username", etUsername.getText().toString());
        editor.putString("password", etPassword.getText().toString());
        
        try {
            editor.putInt("interval", Integer.parseInt(etInterval.getText().toString()));
        } catch (NumberFormatException e) {
            editor.putInt("interval", 2);
        }
        try {
            editor.putFloat("target", Float.parseFloat(etTarget.getText().toString()));
        } catch (NumberFormatException e) {
            editor.putFloat("target", 0.15f);
        }
        try {
            editor.putInt("wait_after", Integer.parseInt(etWaitAfter.getText().toString()));
        } catch (NumberFormatException e) {
            editor.putInt("wait_after", 25);
        }
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
            
            if (!checkAllPermissions()) {
                Toast.makeText(this, "Bitte alle Berechtigungen aktivieren!", Toast.LENGTH_LONG).show();
                return;
            }
            
            saveData();
            
            int interval = 2;
            float target = 0.15f;
            int waitAfter = 25;
            
            try {
                interval = Integer.parseInt(etInterval.getText().toString());
                target = Float.parseFloat(etTarget.getText().toString());
                waitAfter = Integer.parseInt(etWaitAfter.getText().toString());
            } catch (NumberFormatException e) {
                // Use defaults
            }
            
            String loginUsername = cleanPhoneNumber(username);
            tvDisplayNumber.setText("📱 Login mit: " + loginUsername);
            
            refillService.start(loginUsername, password, interval, target, waitAfter);
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            tvStatus.setText("🔄 Läuft...");
        });
        
        btnStop.setOnClickListener(v -> {
            refillService.stop();
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
            tvStatus.setText("⏹️ Gestoppt");
            tvDisplayNumber.setText("📱 " + cleanPhoneNumber(etUsername.getText().toString()));
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
    
    private boolean checkAllPermissions() {
        boolean allGranted = true;
        
        updatePermissionStatus(tvInternetStatus, true);
        
        boolean networkGranted = ContextCompat.checkSelfPermission(this,
            android.Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED;
        updatePermissionStatus(tvNetworkStatus, networkGranted);
        if (!networkGranted) allGranted = false;
        
        updatePermissionStatus(tvWakeLockStatus, true);
        updatePermissionStatus(tvForegroundStatus, true);
        
        boolean batteryGranted = PermissionHelper.isBatteryOptimizationDisabled(this);
        updatePermissionStatus(tvBatteryStatus, batteryGranted);
        if (!batteryGranted) allGranted = false;
        
        boolean autostartGranted = PermissionHelper.isAutostartAvailable();
        updatePermissionStatus(tvAutostartStatus, autostartGranted);
        
        btnStart.setEnabled(allGranted);
        return allGranted;
    }
    
    private void updatePermissionStatus(TextView tv, boolean granted) {
        if (granted) {
            tv.setText("✅ Aktiviert");
            tv.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        } else {
            tv.setText("❌ Nicht aktiviert (Zum Aktivieren klicken)");
            tv.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
        }
    }
    
    private void openAppSettings() {
        Intent intent = PermissionHelper.getAppSettingsIntent(this);
        startActivity(intent);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        checkAllPermissions();
    }
}
