package com.lidlrefill.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int OVERLAY_PERMISSION_REQUEST = 1;
    private static final int ACCESSIBILITY_PERMISSION_REQUEST = 2;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        Button btnStartService = findViewById(R.id.btnStartService);
        Button btnRequestPermissions = findViewById(R.id.btnRequestPermissions);
        
        btnRequestPermissions.setOnClickListener(v -> requestPermissions());
        
        btnStartService.setOnClickListener(v -> {
            if (checkPermissions()) {
                startOverlayService();
            } else {
                Toast.makeText(this, "Bitte erteilen Sie alle Berechtigungen", Toast.LENGTH_LONG).show();
            }
        });
    }
    
    private void requestPermissions() {
        // Overlay Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
            }
        }
        
        // Accessibility Permission
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST);
    }
    
    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                return false;
            }
        }
        return true;
    }
    
    private void startOverlayService() {
        Intent intent = new Intent(this, OverlayService.class);
        intent.putExtra("command", "start");
        startService(intent);
        finish();
    }
}
