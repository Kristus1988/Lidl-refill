package com.lidlrefill.app;

import android.os.Bundle;
import android.view.accessibility.AccessibilityServiceInfo;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Beispiel für AccessibilityServiceInfo
        android.view.accessibility.AccessibilityManager am = 
            (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        
        List<AccessibilityServiceInfo> services = am.getInstalledAccessibilityServiceList();
        
        for (AccessibilityServiceInfo info : services) {
            // Deine Logik hier
        }
    }
}
