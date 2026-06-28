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
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        
        Button btnStartService = findViewById(R.id.btnStartService);
        Button btnRequestPermissions = findViewById(R.id.btnRequestPermissions);
        Button btnCheckPermissions = findViewById(R.id.btnCheckPermissions);
        
        btnRequestPermissions.setOnClickListener(v -> requestAllPermissions());
        btnCheckPermissions.setOnClickListener(v -> checkAllPermissions());
        btnStartService.setOnClickListener(v -> {
            if (checkAllPermissions()) {
                requestMediaProjection();
            } else {
                Toast.makeText(this, "Bitte alle Berechtigungen erteilen", Toast.LENGTH_LONG).show();
            }
        });
    }
    
    private void requestAllPermissions() {
        // 1. Overlay Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
            }
        }
        
        // 2. Accessibility Permission
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am != null && !am.isEnabled()) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST);
        }
        
        // 3. Storage Permission (für Screenshots)
        requestStoragePermission();
    }
    
    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            String[] permissions = {
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS
            };
            ActivityCompat.requestPermissions(this, permissions, STORAGE_PERMISSION_REQUEST);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-12
            String[] permissions = {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
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
        // Overlay prüfen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "❌ Overlay-Berechtigung fehlt", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        
        // Accessibility prüfen
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) {
            Toast.makeText(this, "❌ Accessibility-Berechtigung fehlt", Toast.LENGTH_SHORT).show();
            return false;
        }
        
        // Storage prüfen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) 
                != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "❌ Speicher-Berechtigung fehlt", Toast.LENGTH_SHORT).show();
                return false;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "❌ Speicher-Berechtigung fehlt", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        
        Toast.makeText(this, "✅ Alle Berechtigungen erteilt!", Toast.LENGTH_SHORT).show();
        return true;
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
            checkAllPermissions();
            return;
        }
        
        if (requestCode == MEDIA_PROJECTION_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                MediaProjection projection = mediaProjectionManager.getMediaProjection(resultCode, data);
                startOverlayService(projection);
            } else {
                Toast.makeText(this, "❌ Screen-Capture Berechtigung benötigt!", Toast.LENGTH_LONG).show();
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
                Toast.makeText(this, "❌ Speicher-Berechtigung verweigert!", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
