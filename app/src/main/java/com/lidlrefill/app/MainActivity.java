package com.lidlrefill.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int OVERLAY_PERMISSION_REQUEST = 1;
    private static final int ACCESSIBILITY_PERMISSION_REQUEST = 2;
    private static final int STORAGE_PERMISSION_REQUEST = 100;
    private static final int MANAGE_STORAGE_REQUEST = 101;
    
    private TextView tvPermissionStatus;
    private Button btnStartService, btnRestartApp, btnRefreshAccessibility;
    private Button btnRequestPermissions, btnCheckPermissions;
    private Button btnRequestStorage;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus);
        btnStartService = findViewById(R.id.btnStartService);
        btnRestartApp = findViewById(R.id.btnRestartApp);
        btnRefreshAccessibility = findViewById(R.id.btnRefreshAccessibility);
        btnRequestPermissions = findViewById(R.id.btnRequestPermissions);
        btnCheckPermissions = findViewById(R.id.btnCheckPermissions);
        btnRequestStorage = findViewById(R.id.btnRequestStorage);
        
        // ===== 1. OVERLAY =====
        btnRequestPermissions.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
                    return;
                }
            }
            // Nach Overlay → Speicher
            requestStoragePermission();
        });
        
        // ===== 2. SPEICHER =====
        btnRequestStorage.setOnClickListener(v -> {
            requestStoragePermission();
        });
        
        // ===== 3. ACCESSIBILITY =====
        btnRefreshAccessibility.setOnClickListener(v -> {
            Toast.makeText(this, "🔧 Accessibility wird aktualisiert...", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST);
        });
        
        btnCheckPermissions.setOnClickListener(v -> checkAllPermissions());
        
        btnStartService.setOnClickListener(v -> {
            if (checkAllPermissions()) {
                startOverlayService();
            } else {
                Toast.makeText(this, "❌ Bitte alle Berechtigungen erteilen", Toast.LENGTH_LONG).show();
            }
        });
        
        btnRestartApp.setOnClickListener(v -> {
            Toast.makeText(this, "🔄 App wird neu gestartet...", Toast.LENGTH_SHORT).show();
            Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
            finish();
            android.os.Process.killProcess(android.os.Process.myPid());
        });
        
        updatePermissionStatus();
    }
    
    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, MANAGE_STORAGE_REQUEST);
            } else {
                Toast.makeText(this, "✅ Speicherzugriff bereits gewährt!", Toast.LENGTH_SHORT).show();
                // Nach Speicher → Accessibility
                requestAccessibilityPermission();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_REQUEST);
            } else {
                Toast.makeText(this, "✅ Speicherzugriff bereits gewährt!", Toast.LENGTH_SHORT).show();
                requestAccessibilityPermission();
            }
        }
    }
    
    private void requestAccessibilityPermission() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST);
        } else {
            updatePermissionStatus();
            checkAllPermissions();
        }
    }
    
    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }
    
    private void updatePermissionStatus() {
        StringBuilder status = new StringBuilder();
        status.append("📋 Berechtigungen:\n");
        
        boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        status.append(overlayOk ? "✅" : "❌").append(" 1. Overlay (Fenster einblenden)\n");
        
        boolean storageOk = hasStoragePermission();
        status.append(storageOk ? "✅" : "❌").append(" 2. Speicherzugriff (Screenshots)\n");
        
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        boolean accOk = am != null && am.isEnabled();
        status.append(accOk ? "✅" : "❌").append(" 3. Accessibility (Sonderfunktionen)\n");
        
        tvPermissionStatus.setText(status.toString());
    }
    
    private boolean checkAllPermissions() {
        boolean allOk = true;
        StringBuilder missing = new StringBuilder();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            missing.append("❌ Overlay fehlt\n");
            allOk = false;
        }
        
        if (!hasStoragePermission()) {
            missing.append("❌ Speicherzugriff fehlt\n");
            allOk = false;
        }
        
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) {
            missing.append("❌ Accessibility fehlt\n");
            allOk = false;
        }
        
        if (!allOk) {
            Toast.makeText(this, missing.toString(), Toast.LENGTH_LONG).show();
            btnStartService.setEnabled(false);
        } else {
            Toast.makeText(this, "✅ Alle Berechtigungen erteilt!", Toast.LENGTH_SHORT).show();
            btnStartService.setEnabled(true);
        }
        
        updatePermissionStatus();
        return allOk;
    }
    
    private void startOverlayService() {
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "🚀 Overlay gestartet!", Toast.LENGTH_LONG).show();
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        updatePermissionStatus();
        checkAllPermissions();
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updatePermissionStatus();
        checkAllPermissions();
        if (requestCode == STORAGE_PERMISSION_REQUEST && grantResults.length > 0 
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestAccessibilityPermission();
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }
}
