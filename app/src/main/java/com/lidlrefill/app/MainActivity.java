package com.lidlrefill.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
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
    private static final int STORAGE_PERMISSION_REQUEST = 3;
    
    private TextView tvPermissionStatus;
    private Button btnStartService, btnRestartApp, btnRefreshAccessibility, btnForceStart;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus);
        btnStartService = findViewById(R.id.btnStartService);
        btnRestartApp = findViewById(R.id.btnRestartApp);
        btnRefreshAccessibility = findViewById(R.id.btnRefreshAccessibility);
        btnForceStart = findViewById(R.id.btnForceStart);
        
        Button btnRequestPermissions = findViewById(R.id.btnRequestPermissions);
        Button btnCheckPermissions = findViewById(R.id.btnCheckPermissions);
        
        btnRequestPermissions.setOnClickListener(v -> requestAllPermissions());
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
        
        btnRefreshAccessibility.setOnClickListener(v -> {
            Toast.makeText(this, "🔧 Accessibility wird aktualisiert...", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST);
        });
        
        // ============ FORCE START (UMGEHT HONOR PROBLEM) ============
        btnForceStart.setOnClickListener(v -> {
            Toast.makeText(this, 
                "⚠️ FORCE START:\n" +
                "Accessibility wird ignoriert!\n" +
                "Stelle sicher, dass es in den Einstellungen aktiviert ist.", 
                Toast.LENGTH_LONG).show();
            startOverlayService();
        });
        
        updatePermissionStatus();
    }
    
    private void updatePermissionStatus() {
        StringBuilder status = new StringBuilder();
        status.append("📋 Berechtigungen:\n");
        
        boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        status.append(overlayOk ? "✅" : "❌").append(" Overlay (Fenster einblenden)\n");
        
        // ============ VERBESSERTE ACCESSIBILITY-ERKENNUNG FÜR HONOR ============
        boolean accOk = isAccessibilityEnabled();
        status.append(accOk ? "✅" : "❌").append(" Accessibility (Sonderfunktionen)\n");
        
        if (!accOk && isHonorOrHuawei()) {
            status.append("\n🔧 HONOR ACCESSIBILITY FIX:\n");
            status.append("1. Einstellungen → Barrierefreiheit → Bedienungshilfen\n");
            status.append("2. Lidl Refill AUS-schalten\n");
            status.append("3. Wieder EIN-schalten\n");
            status.append("4. Muster/Passwort bestätigen\n");
            status.append("5. '🔄 App neu starten' klicken\n");
            status.append("6. Oder '⚠️ FORCE START' verwenden!");
        }
        
        boolean storageOk = checkStoragePermission();
        status.append(storageOk ? "✅" : "❌").append(" Speicher (für OCR)\n");
        
        tvPermissionStatus.setText(status.toString());
    }
    
    // ============ HONOR-ERKENNUNG ============
    private boolean isHonorOrHuawei() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        return manufacturer.contains("honor") || 
               manufacturer.contains("huawei") ||
               manufacturer.contains("hihonor");
    }
    
    // ============ VERBESSERTE ACCESSIBILITY-ERKENNUNG ============
    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        
        // Methode 1: Standard-Prüfung
        boolean isEnabled = am.isEnabled();
        
        // Methode 2: Prüfen ob der Service läuft (für Honor)
        if (!isEnabled) {
            try {
                java.util.List<android.view.accessibility.AccessibilityServiceInfo> services = 
                    am.getEnabledAccessibilityServiceList(
                        android.view.accessibility.AccessibilityServiceInfo.FEEDBACK_GENERIC);
                for (android.view.accessibility.AccessibilityServiceInfo info : services) {
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
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                == PackageManager.PERMISSION_GRANTED;
        }
    }
    
    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, 
                new String[]{Manifest.permission.READ_MEDIA_IMAGES}, 
                STORAGE_PERMISSION_REQUEST);
        } else {
            ActivityCompat.requestPermissions(this, 
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 
                STORAGE_PERMISSION_REQUEST);
        }
    }
    
    private void requestAllPermissions() {
        // ============ 1. OVERLAY PERMISSION ============
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
                return;
            }
        }
        
        // ============ 2. STORAGE PERMISSION ============
        if (!checkStoragePermission()) {
            requestStoragePermission();
            return;
        }
        
        // ============ 3. ACCESSIBILITY PERMISSION ============
        requestAccessibilityPermission();
    }
    
    private void requestAccessibilityPermission() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) {
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
                    "6. 'FORCE START' verwenden!", 
                    Toast.LENGTH_LONG).show();
            }
        } else {
            updatePermissionStatus();
            checkAllPermissions();
        }
    }
    
    private boolean checkAllPermissions() {
        boolean allOk = true;
        StringBuilder missing = new StringBuilder();
        
        // 1. Overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            missing.append("❌ Overlay fehlt\n");
            allOk = false;
        }
        
        // 2. Accessibility (mit Honor-Fix)
        if (!isAccessibilityEnabled()) {
            missing.append("❌ Accessibility fehlt (Honor-Problem!)\n");
            missing.append("→ '🔄 Accessibility refresh' oder 'FORCE START' verwenden!\n");
            allOk = false;
        }
        
        // 3. Storage
        if (!checkStoragePermission()) {
            missing.append("❌ Speicher fehlt (für OCR)\n");
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
                // Alles erledigt
            } else {
                if (!checkStoragePermission()) {
                    requestStoragePermission();
                } else {
                    requestAccessibilityPermission();
                }
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✅ Speicherberechtigung erteilt!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "⚠️ Speicherberechtigung benötigt für OCR!", Toast.LENGTH_LONG).show();
            }
            requestAccessibilityPermission();
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }
}
