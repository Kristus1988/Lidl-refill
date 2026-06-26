package com.example.lidlrefill;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private Button btnStart, btnStop;
    private TextView tvStatus, tvVolume, tvRefillCount, tvNextCheck;
    private SharedPreferences prefs;
    private boolean isServiceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupButtons();
        updateStatusFromPrefs();

        // Prüfe ob Accessibility Service aktiviert ist
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "⚠️ Bitte Accessibility Service aktivieren!", Toast.LENGTH_LONG).show();
        }
    }

    private void initViews() {
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        tvStatus = findViewById(R.id.tv_status);
        tvVolume = findViewById(R.id.tv_volume);
        tvRefillCount = findViewById(R.id.tv_refill_count);
        tvNextCheck = findViewById(R.id.tv_next_check);
        prefs = getSharedPreferences("refill_status", MODE_PRIVATE);
    }

    private void setupButtons() {
        btnStart.setOnClickListener(v -> {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "⚠️ Bitte zuerst Accessibility Service aktivieren!", Toast.LENGTH_LONG).show();
                return;
            }

            // OverlayService starten
            Intent intent = new Intent(this, OverlayService.class);
            startService(intent);
            isServiceRunning = true;
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            Toast.makeText(this, "✅ Overlay aktiviert! Ziehe die Felder auf die Lidl App.", Toast.LENGTH_LONG).show();
        });

        btnStop.setOnClickListener(v -> {
            Intent intent = new Intent(this, OverlayService.class);
            stopService(intent);
            isServiceRunning = false;
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
            Toast.makeText(this, "⏹️ Service gestoppt!", Toast.LENGTH_SHORT).show();
        });

        btnStop.setEnabled(false);
    }

    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/" + OverlayService.class.getCanonicalName();
        try {
            String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            return enabledServices != null && enabledServices.contains(service);
        } catch (Exception e) {
            return false;
        }
    }

    private void updateStatusFromPrefs() {
        String status = prefs.getString("status", "⏳ Warte auf Start...");
        float volume = prefs.getFloat("volume", -1);
        int count = prefs.getInt("refill_count", 0);

        tvStatus.setText(status);
        if (volume > 0) {
            tvVolume.setText("📦 Volumen: " + String.format("%.2f", volume) + " GB");
        } else {
            tvVolume.setText("📦 Volumen: -- GB");
        }
        tvRefillCount.setText("🔄 Refills: " + count);
        tvNextCheck.setText("⏱️ Nächste Prüfung: automatisch");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusFromPrefs();
    }
}
