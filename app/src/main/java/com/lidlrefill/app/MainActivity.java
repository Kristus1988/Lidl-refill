package com.lidlrefill.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int OVERLAY_PERMISSION_REQUEST = 1;
    private static final int ACCESSIBILITY_PERMISSION_REQUEST = 2;
    
    private TextView tvPermissionStatus;
    private Button btnStartService, btnRestartApp, btnRefreshAccessibility;
    private Button btnRequestPermissions, btnCheckPermissions;
    
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
        
        // ===== OVERLAY & ACCESSIBILITY =====
        btnRequestPermissions.setOnClickListener(v -> requestAllPermissions());
        btnCheckPermissions.setOnClickListener(v -> checkAllPermissions());
        
        // ===== REFRESH ACCESSIBILITY (für Honor) =====
        btnRefreshAccessibility.setOnClickListener(v -> {
            Toast.makeText(this, "🔧 Accessibility wird aktualisiert...", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST);
        });
        
        // ===== OVERLAY STARTEN =====
        btnStartService.setOnClickListener(v -> {
            if (checkAllPermissions()) {
                startOverlayService();
            } else {
                Toast.makeText(this, "❌ Bitte alle Berechtigungen erteilen", Toast.LENGTH_LONG).show();
            }
        });
        
        // ===== APP NEU STARTEN =====
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
    
    private void updatePermissionStatus() {
        StringBuilder status = new StringBuilder();
        status.append("📋 Berechtigungen:\n");
        
        boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        status.append(overlayOk ? "✅" : "❌").append(" Overlay (Fenster einblenden)\n");
        
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        boolean accOk = am != null && am.isEnabled();
        status.append(accOk ? "✅" : "❌").append(" Accessibility (Sonderfunktionen)\n");
        
        status.append("\n📸 Screenshot (Accessibility):\n");
        status.append(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? "✅ Verfügbar (Android 11+)" : "❌ Nicht verfügbar");
        
        tvPermissionStatus.setText(status.toString());
    }
    
    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
                return;
            }
        }
        requestAccessibilityPermission();
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
    
    private boolean checkAllPermissions() {
        boolean allOk = true;
        StringBuilder missing = new StringBuilder();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            missing.append("❌ Overlay fehlt\n");
            allOk = false;
        }
        
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) {
            missing.append("❌ Accessibility fehlt\n");
            allOk = false;
        }
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            missing.append("❌ Android 11+ benötigt für Screenshots\n");
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
        finish();
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            updatePermissionStatus();
            if (checkAllPermissions()) {
                // Alles schon erledigt
            } else {
                requestAccessibilityPermission();
            }
            return;
        }
        
        if (requestCode == ACCESSIBILITY_PERMISSION_REQUEST) {
            updatePermissionStatus();
            checkAllPermissions();
            return;
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }
}
