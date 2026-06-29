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
import android.os.Process;
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
    private Button btnStartService, btnRestartApp, btnForceStart, btnRefreshAccessibility;
    private static MediaProjection sPendingProjection = null;
    private static boolean isWaitingForResume = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus);
        btnStartService = findViewById(R.id.btnStartService);
        btnRestartApp = findViewById(R.id.btnRestartApp);
        btnForceStart = findViewById(R.id.btnForceStart);
        btnRefreshAccessibility = findViewById(R.id.btnRefreshAccessibility);
        
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
        
        btnRestartApp.setOnClickListener(v -> {
            Toast.makeText(this, "🔄 App wird neu gestartet...", Toast.LENGTH_SHORT).show();
            restartApp();
        });
        
        // ============ NEU: REFRESH ACCESSIBILITY ============
        btnRefreshAccessibility.setOnClickListener(v -> {
            Toast.makeText(this, "🔄 Accessibility wird aktualisiert...", Toast.LENGTH_SHORT).show();
            refreshAccessibility();
        });
        
        btnForceStart.setOnClickListener(v -> {
            if (sPendingProjection != null) {
                startOverlayService(sPendingProjection);
                sPendingProjection = null;
                return;
            }
            Toast.makeText(this, "⚠️ Screen-Capture wird gestartet...", Toast.LENGTH_LONG).show();
            requestMediaProjection();
        });
        
        updatePermissionStatus();
        
        if (isWaitingForResume && sPendingProjection != null) {
            isWaitingForResume = false;
            startOverlayService(sPendingProjection);
            sPendingProjection = null;
        }
    }
    
    private void restartApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        finish();
        Process.killProcess(Process.myPid());
    }
    
    // ============ NEU: ACCESSIBILITY REFRESH ============
    private void refreshAccessibility() {
        // Methode 1: AccessibilityService neu starten
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                // Kurz deaktivieren und reaktivieren (wird von Honor erkannt)
                // Wir können den Service nicht direkt steuern, aber wir können die App neu starten lassen
                Toast.makeText(this, 
                    "🔧 HONOR ACCESSIBILITY REFRESH:\n" +
                    "1. Gehe zu Einstellungen → Barrierefreiheit\n" +
                    "2. Lidl Refill AUS-schalten\n" +
                    "3. Wieder EIN-schalten\n" +
                    "4. Muster/Passwort bestätigen\n" +
                    "5. Hier '✅ Berechtigungen prüfen' klicken", 
                    Toast.LENGTH_LONG).show();
                
                // Öffne Accessibility Einstellungen
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST);
            }
        } catch (Exception e) {
            Toast.makeText(this, "❌ Fehler: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void updatePermissionStatus() {
        StringBuilder status = new StringBuilder();
        status.append("📋 Berechtigungen:\n");
        
        boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        status.append(overlayOk ? "✅" : "❌").append(" Overlay (Fenster einblenden)\n");
        
        // Accessibility - mit Refresh-Hinweis
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        boolean accOk = am != null && am.isEnabled();
        status.append(accOk ? "✅" : "❌").append(" Accessibility (Sonderfunktionen)\n");
        
        if (!accOk && isHonorOrHuawei()) {
            status.append("\n🔧 HONOR ACCESSIBILITY REFRESH:\n");
            status.append("1. Einstellungen → Barrierefreiheit\n");
            status.append("2. Lidl Refill AUS-schalten\n");
            status.append("3. Wieder EIN-schalten\n");
            status.append("4. Muster/Passwort bestätigen\n");
            status.append("5. '🔄 Accessibility refresh' klicken\n");
            status.append("6. '✅ Berechtigungen prüfen' klicken");
        }
        
        boolean storageOk = checkStoragePermission();
        status.append(storageOk ? "✅" : "❌").append(" Speicher\n");
        
        if (isHonorOrHuawei()) {
            status.append("\n⚠️ HONOR SCREEN-CAPTURE:\n");
            status.append("1. 'FORCE START' klicken\n");
            status.append("2. 'JETZT STARTEN' → 'Gesamter Bildschirm'\n");
            status.append("3. App NEU ÖFFNEN\n");
            status.append("4. 'FORCE START' klicken\n");
            status.append("5. ✅ Overlay erscheint!");
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
        }
        return true;
    }
    
    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
            }
        }
        
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST);
        }
        
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
            missing.append("❌ Overlay fehlt\n");
            allOk = false;
        }
        
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        boolean accOk = am != null && am.isEnabled();
        if (!accOk) {
            missing.append("❌ Accessibility fehlt (Honor-Problem!)\n");
            missing.append("→ '🔄 Accessibility refresh' verwenden!\n");
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
    
    private void startOverlayService(MediaProjection projection) {
        OverlayService.setMediaProjection(projection);
        
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        
        Toast.makeText(this, "🚀 Overlay mit Screen-Capture gestartet!", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
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
                
                if (isHonorOrHuawei()) {
                    sPendingProjection = projection;
                    isWaitingForResume = true;
                    Toast.makeText(this, 
                        "✅ Screen-Capture erteilt!\n" +
                        "Öffne App neu und klicke 'FORCE START'", 
                        Toast.LENGTH_LONG).show();
                    moveTaskToBack(true);
                } else {
                    startOverlayService(projection);
                }
            } else {
                Toast.makeText(this, 
                    "❌ Screen-Capture benötigt!\n" +
                    "Bitte 'JETZT STARTEN' auswählen.", 
                    Toast.LENGTH_LONG).show();
                requestMediaProjection();
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
        
        if (isWaitingForResume && sPendingProjection != null) {
            isWaitingForResume = false;
            startOverlayService(sPendingProjection);
            sPendingProjection = null;
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updatePermissionStatus();
    }
}
