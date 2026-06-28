package com.lidlrefill.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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

public class MainActivity extends AppCompatActivity {
    private static final int OVERLAY_PERMISSION_REQUEST = 1;
    private static final int ACCESSIBILITY_PERMISSION_REQUEST = 2;
    private static final int MEDIA_PROJECTION_REQUEST = 1001;
    
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
        
        // 3. MediaProjection Permission
        requestMediaProjection();
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
        
        Toast.makeText(this, "✅ Alle Berechtigungen erteilt!", Toast.LENGTH_SHORT).show();
        return true;
    }
    
    private void startOverlayService() {
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "🚀 Overlay wird gestartet...", Toast.LENGTH_SHORT).show();
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
                // MediaProjection an OverlayService übergeben
                if (mediaProjectionManager != null) {
                    MediaProjection projection = mediaProjectionManager.getMediaProjection(resultCode, data);
                    
                    // OverlayService starten und MediaProjection übergeben
                    Intent intent = new Intent(this, OverlayService.class);
                    intent.putExtra("media_projection", projection);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                    
                    Toast.makeText(this, "🚀 Overlay mit Screen-Capture gestartet!", Toast.LENGTH_LONG).show();
                    finish();
                }
            } else {
                Toast.makeText(this, "❌ Screen-Capture Berechtigung benötigt!", Toast.LENGTH_LONG).show();
                // Nochmal versuchen
                requestMediaProjection();
            }
        }
    }
}
