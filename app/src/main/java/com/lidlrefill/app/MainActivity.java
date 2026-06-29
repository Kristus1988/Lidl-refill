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
import android.os.PowerManager;
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
    private static final int BATTERY_OPTIMIZATION_REQUEST = 1003;
    
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
        status.append(overlayOk ? "✅" : "❌").append(" Overlay\n");
        
        // Accessibility - VERBESSERTE ERKENNUNG für Honor
        boolean accOk = isAccessibilityEnabled();
        status.append(accOk ? "✅" : "❌").append(" Accessibility\n");
        
        // Storage
        boolean storageOk = checkStoragePermission();
        status.append(storageOk ? "✅" : "❌").append(" Speicher\n");
        
        // Honor spezifisch
        if (isHonorOrHuawei()) {
            status.append("\n⚠️ HONOR/HUAWEI erkannt:\n");
            if (!accOk) {
                status.append("🔧 ACCESSIBILITY FIX:\n");
                status.append("1. Einstellungen → Barrierefreiheit → Bedienungshilfen\n");
                status.append("2. Lidl Refill AUS- und wieder EIN schalten\n");
                status.append("3. Bei Muster/Passwort: Bestätigen\n");
                status.append("4. App NEU STARTEN\n");
                status.append("5. Falls Problem bleibt: Gerät NEU STARTEN");
            }
        }
        
        tvPermissionStatus.setText(status.toString());
    }
    
    private boolean isHonorOrHuawei() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        return manufacturer.contains("honor") || 
               manufacturer.contains("huawei") ||
               manufacturer.contains("hihonor");
    }
    
    // ============ VERBESSERTE ACCESSIBILITY-ERKENNUNG ============
    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        
        // Methode 1: Standard-Prüfung
        boolean isEnabled = am.isEnabled();
        
        // Methode 2: Prüfen ob der Service läuft (für Honor)
        if (!isEnabled) {
            try {
                // Prüfe ob unser Service in der Liste der aktiven Dienste ist
                java.util.List<AccessibilityServiceInfo> services = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_GENERIC);
                for (AccessibilityServiceInfo info : services) {
                    if (info.getResolveInfo() != null && 
                        info.getResolveInfo().serviceInfo != null) {
                        String packageName = info.getResolveInfo().serviceInfo.packageName;
                        if (getPackageName().equals(packageName)) {
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                // Ignorieren
            }
        }
        
        return isEnabled;
    }
    
    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) 
                == PackageManager.PERMISSION_GRANTED;
        }
        return true; // Honor mit Scoped Storage
    }
    
    private void requestAllPermissions() {
        // 1. OVERLAY PERMISSION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
            }
        }
        
        // 2. ACCESSIBILITY PERMISSION - HONOR SPEZIFISCH
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null || !isAccessibilityEnabled()) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST);
            
            if (isHonorOrHuawei()) {
                Toast.makeText(this, 
                    "🔧 HONOR ACCESSIBILITY FIX:\n" +
                    "1. Gehe zu 'Bedienungshilfen'\n" +
                    "2. 'Lidl Refill' AUS-schalten\n" +
                    "3. Wieder EIN-schalten\n" +
                    "4. Muster/Passwort bestätigen\n" +
                    "5. Zurück zur App\n" +
                    "6. 'Berechtigungen prüfen' klicken", 
                    Toast.LENGTH_LONG).show();
            }
        }
        
        // 3. STORAGE PERMISSION (nur bei Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String[] permissions = {
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS
            };
            ActivityCompat.requestPermissions(this, permissions, STORAGE_PERMISSION_REQUEST);
        }
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
        
        if (!isAccessibilityEnabled()) {
            missing.append("❌ Accessibility fehlt (Honor-Problem!)\n");
            missing.append("→ Einstellungen → Barrierefreiheit → Bedienungshilfen\n");
            missing.append("→ Lidl Refill AUS/EIN schalten\n");
            missing.append("→ Danach App NEU STARTEN\n");
            allOk = false;
        }
        
        if (!allOk) {
            Toast.makeText(this, missing.toString(), Toast.LENGTH_LONG).show();
            if (isHonorOrHuawei()) {
                Toast.makeText(this, 
                    "🔧 HONOR ACCESSIBILITY FIX:\n" +
                    "1. Gerät NEU STARTEN\n" +
                    "2. Einstellungen → Barrierefreiheit\n" +
                    "3. Lidl Refill AUS/EIN schalten\n" +
                    "4. App schließen und neu öffnen\n" +
                    "5. 'Berechtigungen prüfen' klicken", 
                    Toast.LENGTH_LONG).show();
            }
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
            requestCode == ACCESSIBILITY_PERMISSION_REQUEST ||
            requestCode == BATTERY_OPTIMIZATION_REQUEST) {
            // Wichtig: Bei Honor muss die App neu gestartet werden!
            if (requestCode == ACCESSIBILITY_PERMISSION_REQUEST && isHonorOrHuawei()) {
                Toast.makeText(this, 
                    "🔄 Bitte App NEU STARTEN für Aktivierung!", 
                    Toast.LENGTH_LONG).show();
            }
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
                    "❌ Screen-Capture Berechtigung benötigt!", 
                    Toast.LENGTH_LONG).show();
                requestMediaProjection();
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updatePermissionStatus();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }
}
