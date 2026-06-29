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
    private Button btnStartService, btnRestartApp;
    private static MediaProjection sPendingProjection = null;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus);
        btnStartService = findViewById(R.id.btnStartService);
        btnRestartApp = findViewById(R.id.btnRestartApp);
        
        Button btnRequestPermissions = findViewById(R.id.btnRequestPermissions);
        Button btnCheckPermissions = findViewById(R.id.btnCheckPermissions);
        
        btnRequestPermissions.setOnClickListener(v -> requestAllPermissions());
        btnCheckPermissions.setOnClickListener(v -> checkAllPermissions());
        
        // ============ NEU: Overlay starten OHNE Screen-Capture ============
        btnStartService.setOnClickListener(v -> {
            if (checkBasicPermissions()) {
                startOverlayOnly();
            } else {
                Toast.makeText(this, "❌ Bitte Overlay & Accessibility erteilen", Toast.LENGTH_LONG).show();
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
        
        // Prüfen ob eine pendente Projection vorhanden ist (von Screen-Capture Rückkehr)
        if (sPendingProjection != null) {
            // Projection an OverlayService übergeben und starten
            OverlayService.setMediaProjection(sPendingProjection);
            sPendingProjection = null;
            Toast.makeText(this, "✅ Screen-Capture aktiviert!", Toast.LENGTH_SHORT).show();
            // OverlayService ist bereits gestartet, jetzt kann OCR verwendet werden
            finish();
        }
    }
    
    private void updatePermissionStatus() {
        StringBuilder status = new StringBuilder();
        status.append("📋 Berechtigungen:\n");
        
        boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        status.append(overlayOk ? "✅" : "❌").append(" Overlay (Fenster einblenden)\n");
        
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        boolean accOk = am != null && am.isEnabled();
        status.append(accOk ? "✅" : "❌").append(" Accessibility (Sonderfunktionen)\n");
        
        // Screen-Capture wird erst bei Bedarf aktiviert
        status.append("⏳ Screen-Capture (wird bei OCR/Start aktiviert)\n");
        
        if (isXiaomiOrHonor()) {
            status.append("\n⚠️ XIAOMI/HONOR HINWEIS:\n");
            status.append("1. 'Overlay starten' klicken\n");
            status.append("2. Overlay erscheint über allen Apps\n");
            status.append("3. In der Lidl-App: Swipe, OCR, Refill platzieren\n");
            status.append("4. Bei 'Start' oder 'OCR jetzt' wird Screen-Capture aktiviert");
        }
        
        tvPermissionStatus.setText(status.toString());
    }
    
    private boolean isXiaomiOrHonor() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        return manufacturer.contains("xiaomi") || 
               manufacturer.contains("redmi") ||
               manufacturer.contains("poco") ||
               manufacturer.contains("honor") ||
               manufacturer.contains("huawei") ||
               manufacturer.contains("hihonor");
    }
    
    private boolean checkBasicPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "❌ Overlay-Berechtigung fehlt!", Toast.LENGTH_SHORT).show();
            return false;
        }
        
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) {
            Toast.makeText(this, "❌ Accessibility-Berechtigung fehlt!", Toast.LENGTH_SHORT).show();
            return false;
        }
        
        return true;
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
    
    private boolean checkAllPermissions() {
        boolean allOk = true;
        StringBuilder missing = new StringBuilder();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            missing.append("❌ Overlay fehlt\n");
            allOk = false;
        }
        
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) {
            missing.append("❌ Accessibility fehlt\n");
            allOk = false;
        }
        
        if (!allOk) {
            Toast.makeText(this, missing.toString(), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "✅ Alle Berechtigungen erteilt!", Toast.LENGTH_SHORT).show();
        }
        
        updatePermissionStatus();
        return allOk;
    }
    
    // ============ NEU: Overlay starten OHNE Screen-Capture ============
    private void startOverlayOnly() {
        Intent intent = new Intent(this, OverlayService.class);
        // Keine MediaProjection - Overlay startet ohne Screen-Capture
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        
        Toast.makeText(this, 
            "✅ Overlay gestartet!\n" +
            "Öffne Lidl-App und platziere Elemente.\n" +
            "Bei 'Start' wird Screen-Capture aktiviert.", 
            Toast.LENGTH_LONG).show();
        finish();
    }
    
    // ============ Screen-Capture für OCR/Start anfordern ============
    public static void requestScreenCapture(Context context) {
        if (context instanceof MainActivity) {
            ((MainActivity) context).requestMediaProjection();
        } else {
            // Falls von OverlayService aufgerufen
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }
    
    private void requestMediaProjection() {
        if (mediaProjectionManager != null) {
            Intent intent = mediaProjectionManager.createScreenCaptureIntent();
            startActivityForResult(intent, MEDIA_PROJECTION_REQUEST);
        }
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
                
                // Projection an OverlayService übergeben
                OverlayService.setMediaProjection(projection);
                Toast.makeText(this, "✅ Screen-Capture aktiviert! OCR funktioniert jetzt.", Toast.LENGTH_LONG).show();
                
                // OverlayService informieren dass Screen-Capture bereit ist
                // OverlayService.notifyScreenshotReady();
                finish();
            } else {
                Toast.makeText(this, 
                    "❌ Screen-Capture benötigt für OCR!\n" +
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
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updatePermissionStatus();
    }
}
