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

        // Dein vorhandener Code hier
        // Beispiel für die Verwendung von AccessibilityServiceInfo:
        List<AccessibilityServiceInfo> services = 
            ((android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE))
            .getInstalledAccessibilityServiceList();

        for (AccessibilityServiceInfo info : services) {
            // Deine Logik hier
        }
    }
}
