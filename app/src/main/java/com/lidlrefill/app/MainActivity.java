package com.lidlrefill.app;

import android.content.SharedPreferences;
import android.os.Build;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    
    private TextView tvVolumeStatus, tvRefillPos;
    private Spinner spinnerConsumption;
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
    
    // Automatik
    private boolean isRunning = false;
    private int cycleCount = 0;
    private double consumptionRate = 0.05;
    private long currentWaitTime = 600000;
    private double currentDataValue = 0.90;
    
    // Parameter
    private static final double REFILL_THRESHOLD = 0.30;
    private static final double SIMULATED_START_DATA = 0.90;
    private static final long MIN_WAIT_TIME = 120000;
    private static final long MAX_WAIT_TIME = 1800000;
    private static final long INITIAL_WAIT_TIME = 600000;
    private static final long MIN_HUMAN_WAIT = 900000;
    private static final long MAX_HUMAN_WAIT = 1200000;
    
    // Verbrauchs-Optionen
    private static final double[] CONSUMPTION_OPTIONS = {0.05, 0.08, 0.15};
    private static final String[] CONSUMPTION_LABELS = {
        "📱 Standard (17-20 Min)",
        "📺 FullHD (10-13 Min)",
        "🎬 4K (5-8 Min)"
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        loadRefillPosition();
        
        tvVolumeStatus = findViewById(R.id.tvVolumeStatus);
        tvRefillPos = findViewById(R.id.tvRefillPos);
        spinnerConsumption = findViewById(R.id.spinnerConsumption);
        btnCheckVolume = findViewById(R.id.btnCheckVolume);
        btnStartAuto = findViewById(R.id.btnStartAuto);
        btnStopAuto = findViewById(R.id.btnStopAuto);
        btnSetRefillPos = findViewById(R.id.btnSetRefillPos);
        webView = findViewById(R.id.webView);
        overlayContainer = findViewById(R.id.overlayContainer);
        
        // ============ REFILL-MARKER (roter Kreis) ============
        refillMarker = new View(this);
        refillMarker.setBackgroundResource(R.drawable.refill_marker);
        refillMarker.setVisibility(View.GONE);
        FrameLayout.LayoutParams markerParams = new FrameLayout.LayoutParams(60, 60);
        refillMarker.setLayoutParams(markerParams);
        refillMarker.setOnTouchListener(touchListener);
        overlayContainer.addView(refillMarker);
        
        // ============ REFILL POSITIONIEREN BUTTON ============
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
                refillMarker.setX(refillX - 30);
                refillMarker.setY(refillY - 30);
                btnSetRefillPos.setText("✅ Position bestätigen");
                Toast.makeText(this, "Ziehe den roten Kreis auf den Refill-Button", Toast.LENGTH_LONG).show();
            }
        });
        
        // ============ SPINNER ============
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, 
            android.R.layout.simple_spinner_dropdown_item, CONSUMPTION_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerConsumption.setAdapter(adapter);
        
        int savedIndex = prefs.getInt("consumption_index", 0);
        spinnerConsumption.setSelection(savedIndex);
        consumptionRate = CONSUMPTION_OPTIONS[savedIndex];
        
        spinnerConsumption.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                consumptionRate = CONSUMPTION_OPTIONS[position];
                prefs.edit().putInt("consumption_index", position).apply();
                Toast.makeText(MainActivity.this, 
                    "📊 " + CONSUMPTION_LABELS[position], 
                    Toast.LENGTH_SHORT).show();
                updateVolumeStatus();
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        btnCheckVolume.setOnClickListener(v -> checkVolumeFromWeb());
        btnStartAuto.setOnClickListener(v -> startAutomation());
        btnStopAuto.setOnClickListener(v -> stopAutomation());
        
        setupWebView();
        updateVolumeStatus();
        updateRefillStatus();
    }
    
    // ============ TOUCH-LISTENER FÜR REFILL-MARKER ============
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
                        refillX = newX + 30;
                        refillY = newY + 30;
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
        webView.getSettings().setLoadWithOverviewMode(false);
        webView.getSettings().setUseWideViewPort(true);
        webView.setInitialScale(100);
        
        String desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36";
        webView.getSettings().setUserAgentString(desktopUserAgent);
        
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }
        
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                
                view.evaluateJavascript(
                    "document.querySelector('meta[name=viewport]')?.setAttribute('content', 'width=1280');" +
                    "document.body.style.width = '1280px';" +
                    "document.documentElement.style.width = '1280px';" +
                    "var event = new Event('resize'); window.dispatchEvent(event);",
                    null
                );
            }
        });
        
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("https://kundenkonto.lidl-connect.de/mein-lidl-connect/uebersicht.html");
    }
    
    private void checkVolumeFromWeb() {
        Toast.makeText(this, "📊 WebView wird aktualisiert...", Toast.LENGTH_SHORT).show();
        webView.reload();
        updateVolumeStatus();
    }
    
    private void updateVolumeStatus() {
        String status = "📊 Verbrauch: " + CONSUMPTION_LABELS[prefs.getInt("consumption_index", 0)];
        status += "\n⚡ Simulations-Modus";
        if (isRunning) {
            status += "\n🔄 Zyklus: " + cycleCount;
            status += "\n📊 " + String.format("%.2f", currentDataValue) + " GB";
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
    
    // ============ REFILL BUTTON MIT MENSCHLICHEN ABWEICHUNGEN ============
    private void clickRefillButton() {
        if (!refillPlaced) {
            Toast.makeText(this, "⚠️ Refill-Button nicht positioniert!", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // ============ MENSCHLICHE ABWEICHUNGEN ============
        long randomDelay = 200 + (long)(Math.random() * 600);
        
        handler.postDelayed(() -> {
            // Klick über JavaScript auf den Button
            String jsCode = 
                "var buttons = document.querySelectorAll('button[aria-label=\"Datenvolumen per Refill wieder auffüllen\"]');" +
                "if (buttons.length > 0) {" +
                "    buttons[0].click();" +
                "    'clicked';" +
                "} else {" +
                "    var allButtons = document.querySelectorAll('button');" +
                "    for (var i = 0; i < allButtons.length; i++) {" +
                "        if (allButtons[i].textContent.trim() === 'Refill aktivieren') {" +
                "            allButtons[i].click();" +
                "            'clicked';" +
                "            break;" +
                "        }" +
                "    }" +
                "    'not found';" +
                "}";
            
            webView.evaluateJavascript(jsCode, value -> {
                if (value != null && value.contains("clicked")) {
                    Toast.makeText(MainActivity.this, "🔄 Refill-Button geklickt! ✅", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "⚠️ Refill-Button nicht gefunden!", Toast.LENGTH_SHORT).show();
                }
            });
        }, randomDelay);
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
        
        Toast.makeText(this, "🚀 Automatik gestartet!\n📊 " + CONSUMPTION_LABELS[spinnerConsumption.getSelectedItemPosition()], Toast.LENGTH_LONG).show();
        
        cycleCount = 0;
        currentDataValue = SIMULATED_START_DATA;
        currentWaitTime = INITIAL_WAIT_TIME;
        updateVolumeStatus();
        
        performCycle();
    }
    
    private void performCycle() {
        if (!isRunning) return;
        
        cycleCount++;
        
        webView.reload();
        
        handler.postDelayed(() -> {
            if (!isRunning) return;
            
            double timeHours = currentWaitTime / 3600000.0;
            double decrease = consumptionRate * timeHours;
            currentDataValue = Math.max(0.05, currentDataValue - decrease);
            
            updateVolumeStatus();
            
            if (currentDataValue <= REFILL_THRESHOLD) {
                long randomRefillDelay = 1000 + (long)(Math.random() * 2000);
                handler.postDelayed(() -> {
                    if (isRunning) {
                        clickRefillButton();
                        handler.postDelayed(() -> {
                            if (isRunning) {
                                currentDataValue = SIMULATED_START_DATA;
                                performCycle();
                            }
                        }, 5000);
                    }
                }, randomRefillDelay);
                return;
            }
            
            currentWaitTime = calculateHumanWaitTime();
            long minutes = currentWaitTime / 60000;
            
            tvVolumeStatus.setText("📊 " + CONSUMPTION_LABELS[spinnerConsumption.getSelectedItemPosition()] +
                "\n⏱ Warte ca. " + minutes + " Minuten" +
                "\n📊 " + String.format("%.2f", currentDataValue) + " GB");
            
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
        updateVolumeStatus();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
