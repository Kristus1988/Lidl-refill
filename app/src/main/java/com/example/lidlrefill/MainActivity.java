package com.example.lidlrefill;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;  // 🔥 IMPORTANT: IOException hinzugefügt!
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.GeneralSecurityException;

public class MainActivity extends AppCompatActivity {
    
    // UI-Elemente
    private EditText etUsername, etPassword;
    private TextView tvStatus, tvCurrentGb, tvRefillCount, tvDisplayNumber;
    private TextView tvLoginStatus, tvPhaseInfo, tvInklusiv, tvRefill, tvTarifInfo;
    private TextView tvChromeDriverStatus;
    private ProgressBar progressChromeDriver;
    private View vStatusIndicator;
    private Button btnStart, btnStop, btnDownloadChromeDriver;
    private LinearLayout layoutBattery;
    
    private TextView tvInternetStatus, tvBatteryStatus;
    
    private RefillService refillService;
    private SharedPreferences sharedPreferences;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // Feste Werte
    private static final int DEFAULT_INTERVAL = 2;
    private static final float DEFAULT_TARGET = 0.15f;
    private static final int DEFAULT_WAIT_AFTER = 25;
    
    // Berechtigungen
    private static final int REQUEST_WRITE_STORAGE = 1001;
    
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
        
        // Prüfe ob ChromeDriver bereits installiert ist
        checkChromeDriver();
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
        btnDownloadChromeDriver = findViewById(R.id.btn_download_chromedriver);
        layoutBattery = findViewById(R.id.layout_battery);
        tvChromeDriverStatus = findViewById(R.id.tv_chromedriver_status);
        progressChromeDriver = findViewById(R.id.progress_chromedriver);
        
        tvInternetStatus = findViewById(R.id.tv_internet_status);
        tvBatteryStatus = findViewById(R.id.tv_battery_status);
        
        // ChromeDriver Download Button
        btnDownloadChromeDriver.setOnClickListener(v -> {
            // Prüfe Speicherberechtigung
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_STORAGE);
                    return;
                }
            }
            downloadChromeDriver();
        });
        
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
            
            int interval = DEFAULT_INTERVAL;
            float target = DEFAULT_TARGET;
            int waitAfter = DEFAULT_WAIT_AFTER;
            
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
        
        boolean batteryGranted = PermissionHelper.isBatteryOptimizationDisabled(this);
        updatePermissionStatus(tvBatteryStatus, batteryGranted);
        if (!batteryGranted) allGranted = false;
        
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
    
    private void openBatterySettings() {
        Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }
    
    // ==========================================
    // CHROMEDRIVER DOWNLOAD MIT STATUSANZEIGE
    // ==========================================
    
    private void checkChromeDriver() {
        // 🔥 HIER: context durch MainActivity.this ersetzen!
        String[] possiblePaths = {
            "/data/local/tmp/chromedriver",
            MainActivity.this.getFilesDir().getAbsolutePath() + "/chromedriver",
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/chromedriver"
        };
        
        boolean found = false;
        for (String path : possiblePaths) {
            File file = new File(path);
            if (file.exists() && file.canExecute()) {
                found = true;
                break;
            }
        }
        
        if (found) {
            btnDownloadChromeDriver.setText("✅ ChromeDriver ist installiert");
            btnDownloadChromeDriver.setEnabled(false);
            tvChromeDriverStatus.setText("✅ ChromeDriver ist installiert und bereit!");
            tvChromeDriverStatus.setTextColor(Color.parseColor("#4CAF50"));
            progressChromeDriver.setVisibility(View.GONE);
        } else {
            btnDownloadChromeDriver.setText("⬇️ ChromeDriver installieren");
            btnDownloadChromeDriver.setEnabled(true);
            tvChromeDriverStatus.setText("⏳ ChromeDriver wird benötigt. Klicke auf 'Installieren'.");
            tvChromeDriverStatus.setTextColor(Color.parseColor("#FFA726"));
            progressChromeDriver.setVisibility(View.GONE);
        }
    }
    
    private void downloadChromeDriver() {
        // UI aktualisieren
        btnDownloadChromeDriver.setEnabled(false);
        btnDownloadChromeDriver.setText("⬇️ Lade herunter...");
        progressChromeDriver.setVisibility(View.VISIBLE);
        progressChromeDriver.setProgress(0);
        tvChromeDriverStatus.setText("⏳ Starte Download...");
        tvChromeDriverStatus.setTextColor(Color.parseColor("#4FC3F7"));
        
        new Thread(() -> {
            try {
                String url = "https://github.com/TeamAmaze/AmazeFileManager/releases/download/3.8.4/amaze-3.8.4.apk";
                URL downloadUrl = new URL(url);
                URLConnection connection = downloadUrl.openConnection();
                connection.connect();
                
                int fileSize = connection.getContentLength();
                File downloadFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "chromedriver.apk");
                
                InputStream inputStream = new BufferedInputStream(connection.getInputStream());
                OutputStream outputStream = new FileOutputStream(downloadFile);
                
                byte[] buffer = new byte[4096];
                int length;
                long totalDownloaded = 0;
                
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                    totalDownloaded += length;
                    
                    final int progress = fileSize > 0 ? (int) ((totalDownloaded * 100) / fileSize) : 0;
                    mainHandler.post(() -> {
                        progressChromeDriver.setProgress(progress);
                        tvChromeDriverStatus.setText("⬇️ Download: " + progress + "%");
                    });
                }
                outputStream.close();
                inputStream.close();
                
                mainHandler.post(() -> {
                    tvChromeDriverStatus.setText("✅ Download abgeschlossen! Bitte installiere die APK.");
                    tvChromeDriverStatus.setTextColor(Color.parseColor("#4CAF50"));
                    progressChromeDriver.setProgress(100);
                    btnDownloadChromeDriver.setText("📂 APK installieren");
                    btnDownloadChromeDriver.setEnabled(true);
                    btnDownloadChromeDriver.setOnClickListener(v -> {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(Uri.fromFile(downloadFile), "application/vnd.android.package-archive");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(intent);
                    });
                    Toast.makeText(MainActivity.this, "✅ ChromeDriver heruntergeladen! Bitte installiere die APK.", Toast.LENGTH_LONG).show();
                });
                
            } catch (Exception e) {
                mainHandler.post(() -> {
                    tvChromeDriverStatus.setText("❌ Fehler: " + e.getMessage());
                    tvChromeDriverStatus.setTextColor(Color.parseColor("#F44336"));
                    btnDownloadChromeDriver.setEnabled(true);
                    btnDownloadChromeDriver.setText("⬇️ Erneut versuchen");
                    progressChromeDriver.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "❌ Fehler beim Download: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WRITE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                downloadChromeDriver();
            } else {
                Toast.makeText(this, "❌ Speicherberechtigung benötigt für den Download!", Toast.LENGTH_LONG).show();
                tvChromeDriverStatus.setText("❌ Speicherberechtigung verweigert!");
                tvChromeDriverStatus.setTextColor(Color.parseColor("#F44336"));
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        checkAllPermissions();
        checkChromeDriver();
    }
}
