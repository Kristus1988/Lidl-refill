package com.lidlrefill.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import android.view.accessibility.AccessibilityManager;
import android.content.Context;

public class MainActivity extends AppCompatActivity {
    private static final int OVERLAY_PERMISSION_REQUEST = 1;
    private static final int ACCESSIBILITY_PERMISSION_REQUEST = 2;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        Button btnStartService = findViewById(R.id.btnStartService);
        Button btnRequestPermissions = findViewById(R.id.btnRequestPermissions);
        Button btnCheckPermissions = findViewById(R.id.btnCheckPermissions);
        
        btnRequestPermissions.setOnClickListener(v -> requestAllPermissions());
        btnCheckPermissions.setOnClickListener(v -> checkAllPermissions());
        btnStartService.setOnClickListener(v -> {
            if (checkAllPermissions()) {
                startOverlayService();
            } else {
                Toast.makeText(this, "Bitte alle Berechtigungen erteilen", Toast.LENGTH_LONG).show();
            }
        });
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
        if (am != null) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST);
        }
    }
    
    private boolean checkAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "❌ Overlay-Berechtigung fehlt", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        
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
        finish();
    }
}
