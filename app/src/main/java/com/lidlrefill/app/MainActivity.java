package com.lidlrefill.app;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    
    private TextView tvVolumeStatus, tvRefillPos;
    private Button btnCheckVolume, btnStartAuto, btnStopAuto, btnSetRefillPos;
    private WebView webView;
    private FrameLayout overlayContainer;
    private View refillMarker;
    private Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    
    // Refill-Position
    private float refillX = 500;
    private float refillY = 500;
    private boolean refillPlaced = false;
    private boolean isDragging = false;
    private float dragOffsetX, dragOffsetY;
    
    // Volumen
    private String currentVolume = "0.00 GB";
    private boolean isVolumeLoaded = false;
    private boolean useRealVolume = false;
    private double currentDataValue = 0.90;
    
    // Automatik
    private boolean isRunning = false;
    private int cycleCount = 0;
    private double consumptionRate = 0.05;
    private long currentWaitTime = 600000;
    
    // Parameter
    private static final double REFILL_THRESHOLD = 0.30;
    private static final double SIMULATED_START_DATA = 0.90;
    private static final long MIN_WAIT_TIME = 120000;
    private static final long MAX_WAIT_TIME = 1800000;
    private static final long INITIAL_WAIT_TIME = 600000;
    private static final long MIN_HUMAN_WAIT = 900000;
    private static final long MAX_HUMAN_WAIT = 1200000;
    
    // Verbrauchs-Optionen
    private double[] consumptionOptions = {0.05, 0.08, 0.15};
    private String[] consumptionLabels = {"Standard (17-20 Min)", "FullHD (10-13 Min)", "4K (5-8 Min)"};
    private int selectedOption = 0;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        loadRefillPosition();
        selectedOption = prefs.getInt("consumption_index", 0);
        consumptionRate = consumptionOptions[selectedOption];
        
        tvVolumeStatus = findViewById(R.id.tvVolumeStatus);
        tvRefillPos = findViewById(R.id.tvRefillPos);
        btnCheckVolume = findViewById(R.id.btnCheckVolume);
        btnStartAuto = findViewById(R.id.btnStartAuto);
        btnStopAuto = findViewById(R.id.btnStopAuto);
        btnSetRefillPos = findViewById(R.id.btnSetRefillPos);
        webView = findViewById(R.id.webView);
        overlayContainer = findViewById(R.id.overlayContainer);
        
        // Refill-Marker (roter Kreis) erstellen
        refillMarker = new View(this);
        refillMarker.setBackgroundResource(R.drawable.refill_marker);
        refillMarker.setVisibility(View.GONE);
        FrameLayout.LayoutParams markerParams = new FrameLayout.LayoutParams(40, 40);
        refillMarker.setLayoutParams(markerParams);
        refillMarker.setOnTouchListener(touchListener);
        overlayContainer.addView(refillMarker);
        
        // Refill-Position setzen Button
        btnSetRefillPos.setOnClickListener(v -> {
            if (refillMarker.getVisibility() == View.VISIBLE) {
                refillMarker.setVisibility(View.GONE);
                btnSetRefillPos.setText("📍 Refill positionieren");
                refillPlaced = true;
                saveRefillPosition();
                updateRefillStatus();
                Toast.makeText(this, "✅ Refill-Button Position gespeichert!", Toast.LENGTH_SHORT).show();
            } else {
                refillMarker.setVisibility(View.VISIBLE);
                refillMarker.setX(refillX - 20);
                refillMarker.setY(refillY - 20);
                btnSetRefillPos.setText("✅ Position bestätigen");
                Toast.makeText(this, "Ziehe den roten Kreis auf den Refill-Button", Toast.LENGTH_LONG).show();
            }
        });
        
        btnCheckVolume.setOnClickListener(v -> checkVolumeFromWeb());
        btnStartAuto.setOnClickListener(v -> startAutomation());
        btnStopAuto.setOnClickListener(v -> stopAutomation());
        
        setupWebView();
        updateVolumeStatus();
        updateRefillStatus();
        
        handler.postDelayed(() -> {
            checkVolumeFromWeb();
        }, 1500);
    }
    
    private View.OnTouchListener touchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isDragging = true;
                    dragOffsetX = event.getX();
                    dragOffsetY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (isDragging) {
                        float newX = event.getRawX() - dragOffsetX;
                        float newY = event.getRawY() - dragOffsetY;
                        refillMarker.setX(newX);
                        refillMarker.setY(newY);
                        refillX = newX + 20;
                        refillY = newY + 20;
                        tvRefillPos.setText("📍 Refill: (" + (int)refillX + ", " + (int)refillY + ")");
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    isDragging = false;
                    updateRefillStatus();
                    return true;
            }
            return false;
        }
    };
    
    private void setupWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setUserAgentString(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
        );
        
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                
                view.evaluateJavascript(
                    "document.documentElement.outerHTML",
                    value -> {
                        if (value != null && !value.equals("null")) {
                            parseHtml(value);
                        }
                    }
                );
            }
        });
        
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("https://kundenkonto.lidl-connect.de/mein-lidl-connect/uebersicht.html");
    }
    
    private void checkVolumeFromWeb() {
        tvVolumeStatus.setText("📊 Lade Lidl-Seite...");
        webView.reload();
        
        handler.postDelayed(() -> {
            if (!isVolumeLoaded) {
                double simulatedValue = 0.3 + Math.random() * 0.7;
                currentVolume = String.format("%.2f GB", simulatedValue);
                currentDataValue = simulatedValue;
                useRealVolume = false;
                updateVolumeStatus();
                Toast.makeText(this, "📊 Volumen: " + currentVolume + " (Simulation)", Toast.LENGTH_SHORT).show();
            }
        }, 8000);
    }
    
    private void parseHtml(String html) {
        if (html == null || html.isEmpty()) return;
        
        try {
            Pattern pattern = Pattern.compile(
                "Unlimited\\s*Refill.*?(0[\\.\\,]\\d+)\\s*GB",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );
            Matcher matcher = pattern.matcher(html);
            
            if (matcher.find()) {
                String used = matcher.group(1).replace(",", ".");
                double value = Double.parseDouble(used);
                
                if (value > 0 && value <= 1.0) {
                    currentVolume = used + " GB";
                    currentDataValue = value;
                    isVolumeLoaded = true;
                    useRealVolume = true;
                    updateVolumeStatus();
                    Toast.makeText(this, "📊 Volumen: " + currentVolume + " ✅", Toast.LENGTH_SHORT).show();
                }
            }
            
        } catch (Exception e) {
            // Parsen fehlgeschlagen
        }
    }
    
    private void updateVolumeStatus() {
        String status = "📊 Volumen: " + currentVolume;
        if (useRealVolume) {
            status += "\n✅ Echtes Volumen von Lidl";
        } else {
            status += "\n⚡ Simulations-Modus";
        }
        tvVolumeStatus.setText(status);
    }
    
    private void updateRefillStatus() {
        if (refillPlaced) {
            tvRefillPos.setText("📍 Refill: (" + (int)refillX + ", " + (int)refillY + ") ✅");
        } else {
            tvRefillPos.setText("📍 Refill: nicht platziert");
        }
    }
    
    private void loadRefillPosition() {
        refillX = prefs.getFloat("refill_x", 500);
        refillY = prefs.getFloat("refill_y", 500);
        refillPlaced = prefs.getBoolean("refill_placed", false);
    }
    
    private void saveRefillPosition() {
        prefs.edit().putFloat("refill_x", refillX).apply();
        prefs.edit().putFloat("refill_y", refillY).apply();
        prefs.edit().putBoolean("refill_placed", refillPlaced).apply();
    }
    
    private void clickRefillButton() {
        // Simuliere Klick auf die gespeicherte Position
        // In einer echten App würde hier ein Accessibility-Klick oder JavaScript-Event ausgeführt
        Toast.makeText(this, "🔄 Refill-Button geklickt! (" + (int)refillX + ", " + (int)refillY + ")", Toast.LENGTH_SHORT).show();
        
        // Versuche über JavaScript zu klicken
        webView.evaluateJavascript(
            "document.querySelector('button:contains(\"Refill aktivieren\")')?.click();",
            null
        );
    }
    
    private long calculateHumanWaitTime() {
        long minWait = MIN_HUMAN_WAIT;
        long maxWait = MAX_HUMAN_WAIT;
        long waitTime = minWait + (long)(Math.random() * (maxWait - minWait));
        waitTime += (long)((Math.random() - 0.5) * 30000);
        return Math.max(MIN_WAIT_TIME, Math.min(MAX_WAIT_TIME, waitTime));
    }
    
    private void startAutomation() {
        if (isRunning) {
            Toast.makeText(this, "⚠️ Läuft bereits", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (!refillPlaced) {
            Toast.makeText(this, "⚠️ Bitte zuerst Refill-Button positionieren!", Toast.LENGTH_LONG).show();
            return;
        }
        
        isRunning = true;
        btnStartAuto.setText("▶ Läuft...");
        btnStartAuto.setEnabled(false);
        btnStopAuto.setEnabled(true);
        
        Toast.makeText(this, "🚀 Automatik gestartet!", Toast.LENGTH_LONG).show();
        
        cycleCount = 0;
        currentDataValue = useRealVolume ? currentDataValue : SIMULATED_START_DATA;
        
        performCycle();
    }
    
    private void performCycle() {
        if (!isRunning) return;
        
        cycleCount++;
        
        // WebView aktualisieren
        webView.reload();
        
        handler.postDelayed(() -> {
            if (!isRunning) return;
            
            // Verbrauch berechnen
            double decrease = consumptionRate * (currentWaitTime / 60000.0);
            currentDataValue = Math.max(0.05, currentDataValue - decrease);
            
            tvVolumeStatus.setText("📊 " + String.format("%.2f", currentDataValue) + " GB\n⏱ Zyklus " + cycleCount);
            
            // Prüfen ob Refill
            if (currentDataValue <= REFILL_THRESHOLD) {
                clickRefillButton();
                handler.postDelayed(() -> {
                    if (isRunning) {
                        currentDataValue = useRealVolume ? currentDataValue : SIMULATED_START_DATA;
                        performCycle();
                    }
                }, 5000);
                return;
            }
            
            // Wartezeit berechnen
            currentWaitTime = calculateHumanWaitTime();
            long minutes = currentWaitTime / 60000;
            
            tvVolumeStatus.setText("⏱ Warte ca. " + minutes + " Minuten\n📊 " + String.format("%.2f", currentDataValue) + " GB");
            
            handler.postDelayed(() -> {
                if (isRunning) {
                    performCycle();
                }
            }, currentWaitTime);
            
        }, 3000);
    }
    
    private void stopAutomation() {
        isRunning = false;
        btnStartAuto.setText("▶ Start");
        btnStartAuto.setEnabled(true);
        btnStopAuto.setEnabled(false);
        handler.removeCallbacksAndMessages(null);
        Toast.makeText(this, "⏹ Gestoppt - " + cycleCount + " Zyklen", Toast.LENGTH_SHORT).show();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
