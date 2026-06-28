package com.lidlrefill.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
    private static final int MEDIA_PROJECTION_REQUEST = 1001;
    private static final int STORAGE_PERMISSION_REQUEST = 1002;
    
    private MediaProjectionManager mediaProjectionManager;
    private TextView tvPermissionStatus;
    private Button btnStartService;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus);
        btnStartService = findViewById(R.id.btnStartService);
        Button btnRequestPermissions = findViewById(R.id.btnRequestPermissions);
        Button btnCheckPermissions = findViewById(R.id.btnCheckPermissions);
        
        btnRequestPermissions.setOnClickListener(v -> requestAllPermissions());
        btnCheckPermissions.setOnClickListener(v -> checkAllPermissions());
        btnStartService.setOnClickListener(v -> {
            if (checkAllPermissions()) {
                requestMediaProjection();
            } else {
                Toast.makeText(this, "❌ Bitte alle Berechtigungen erteilen", Toast.LENGTH_LONG).show();
            }
        });
        
        updatePermissionStatus();
    }
    
    private void updatePermissionStatus() {
        StringBuilder status = new StringBuilder();
        status.append("📋 Berechtigungen:\n");
        
        // Overlay
        boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        status.append(overlayOk ? "✅" : "❌").append(" Overlay (über Apps anzeigen)\n");
        
        // Accessibility
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        boolean accOk = am != null && am.isEnabled();
        status.append(accOk ? "✅" : "❌").append(" Accessibility (Touch-Steuerung)\n");
        
        // Storage
        boolean storageOk = checkStoragePermission();
        status.append(storageOk ? "✅" : "❌").append(" Speicher\n");
        
        // Honor/Huawei spezifisch
        if (isHonorOrHuawei()) {
            status.append("\n⚠️ HONOR/HUAWEI erkannt:\n");
            status.append("→ Accessibility muss in den Einstellungen aktiviert werden\n");
            status.append("→ Muster/Passwort kann erforderlich sein\n");
            status.append("→ Bei Problemen: Einstellungen → Sicherheit → Geräteadministratoren");
        }
        
        tvPermissionStatus.setText(status.toString());
    }
    
    private boolean isHonorOrHuawei() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        return manufacturer.contains("honor") || 
               manufacturer.contains("huawei") ||
               manufacturer.contains("hihonor");
    }
    
    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) 
                == PackageManager.PERMISSION_GRANTED;
        } else {
            // Für Honor/Huawei: Speicherzugriff immer erlauben, da sie Scoped Storage verwenden
            return true;
        }
    }
    
    private void requestAllPermissions() {
        // 1. OVERLAY PERMISSION - FÜR HONOR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                // Für Honor: Direkt zu den Einstellungen
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
            }
        }
        
        // 2. ACCESSIBILITY PERMISSION - FÜR HONOR
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) {
            // Für Honor: Öffne Accessibility Einstellungen
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST);
            
            // Honor-spezifischer Hinweis
            Toast.makeText(this, 
                "🔧 HONOR-HINWEIS:\n" +
                "1. Scrolle zu 'Lidl Refill'\n" +
                "2. 'An/Deaktivieren'\n" +
                "3. Bei Muster/Passwort: Bitte eingeben\n" +
                "4. Nach Aktivierung zurück zur App", 
                Toast.LENGTH_LONG).show();
        }
        
        // 3. STORAGE PERMISSION - NUR BEI ANDROID 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String[] permissions = {
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS
            };
            ActivityCompat.requestPermissions(this, permissions, STORAGE_PERMISSION_REQUEST);
        }
        
        // 4. FÜR HONOR: In den Einstellungen "App oben entfernen" deaktivieren
        Toast.makeText(this, 
            "🔧 HONOR-HINWEIS:\n" +
            "Falls Berechtigung nicht funktioniert:\n" +
            "Einstellungen → Apps → Lidl Refill →\n" +
            "'App oben entfernen' deaktivieren", 
            Toast.LENGTH_LONG).show();
    }
    
    private void requestMediaProjection() {
        if (mediaProjectionManager != null) {
            Intent intent = mediaProjectionManager.createScreenCaptureIntent();
            startActivityForResult(intent, MEDIA_PROJECTION_REQUEST);
        }
    }
    
    private boolean checkAllPermissions() {
        boolean allOk = true;
        StringBuilder missing = new StringBuilder();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            missing.append("❌ Overlay-Berechtigung fehlt\n");
            allOk = false;
        }
        
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) {
            missing.append("❌ Accessibility-Berechtigung fehlt\n");
            allOk = false;
        }
        
        if (!allOk) {
            Toast.makeText(this, missing.toString(), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "✅ Alle Berechtigungen erteilt!", Toast.LENGTH_SHORT).show();
            btnStartService.setEnabled(true);
        }
        
        updatePermissionStatus();
        return allOk;
    }
    
    private void startOverlayService(MediaProjection projection) {
        OverlayService.setMediaProjection(projection);
        
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        
        Toast.makeText(this, "🚀 Overlay mit Screen-Capture gestartet!", Toast.LENGTH_LONG).show();
        finish();
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == OVERLAY_PERMISSION_REQUEST || 
            requestCode == ACCESSIBILITY_PERMISSION_REQUEST) {
            updatePermissionStatus();
            checkAllPermissions();
            return;
        }
        
        if (requestCode == MEDIA_PROJECTION_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                MediaProjection projection = mediaProjectionManager.getMediaProjection(resultCode, data);
                startOverlayService(projection);
            } else {
                Toast.makeText(this, 
                    "❌ Screen-Capture Berechtigung benötigt!\n" +
                    "Bitte erneut versuchen.", 
                    Toast.LENGTH_LONG).show();
                requestMediaProjection();
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Toast.makeText(this, "✅ Speicher-Berechtigung erteilt!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, 
                    "⚠️ Speicher-Berechtigung verweigert!\n" +
                    "Für Honor/Huawei: Scoped Storage wird verwendet.\n" +
                    "OCR funktioniert trotzdem über Screen-Capture.", 
                    Toast.LENGTH_LONG).show();
            }
            updatePermissionStatus();
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }
}
