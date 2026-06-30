package com.lidlrefill.app;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    
    private TextView tvVolumeStatus;
    private Button btnCheckVolume, btnStartAuto, btnStopAuto;
    private WebView webView;
    private Handler handler = new Handler(Looper.getMainLooper());
    
    private String currentVolume = "0.00 GB";
    private boolean isVolumeLoaded = false;
    private boolean useRealVolume = false;
    private double currentDataValue = 0.90;
    
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
    private int selectedOption = 0;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        selectedOption = getPreferences(MODE_PRIVATE).getInt("consumption_index", 0);
        consumptionRate = consumptionOptions[selectedOption];
        
        tvVolumeStatus = findViewById(R.id.tvVolumeStatus);
        btnCheckVolume = findViewById(R.id.btnCheckVolume);
        btnStartAuto = findViewById(R.id.btnStartAuto);
        btnStopAuto = findViewById(R.id.btnStopAuto);
        webView = findViewById(R.id.webView);
        
        btnCheckVolume.setOnClickListener(v -> checkVolumeFromWeb());
        btnStartAuto.setOnClickListener(v -> startAutomation());
        btnStopAuto.setOnClickListener(v -> stopAutomation());
        
        setupWebView();
        updateVolumeStatus();
        
        handler.postDelayed(() -> {
            checkVolumeFromWeb();
        }, 1500);
    }
    
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
                
                // Desktop-Viewport erzwingen
                view.evaluateJavascript(
                    "document.querySelector('meta[name=viewport]')?.setAttribute('content', 'width=1280');" +
                    "document.body.style.width = '1280px';" +
                    "document.documentElement.style.width = '1280px';" +
                    "var event = new Event('resize'); window.dispatchEvent(event);",
                    null
                );
                
                // HTML parsen
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
    
    // ============ REFILL BUTTON KLICKEN ============
    private void clickRefillButton() {
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
                Toast.makeText(this, "🔄 Refill-Button geklickt! ✅", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "⚠️ Refill-Button nicht gefunden!", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    // ============ VOLUMEN PARSEN ============
    private void parseHtml(String html) {
        if (html == null || html.isEmpty()) return;
        
        try {
            // ============ MUSTER 1: unit-display ============
            Pattern pattern = Pattern.compile(
                "unit-display[^\"]*?\"[^>]*?>\\s*\\$?(\\d+[\\.\\,]?\\d*)\\s*GB\\s*/\\s*1\\s*GB",
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
                    return;
                }
            }
            
            // ============ MUSTER 2: Unlimited Refill ============
            Pattern fallbackPattern = Pattern.compile(
                "Unlimited\\s*Refill.*?(0[\\.\\,]\\d+)\\s*GB",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );
            Matcher fallbackMatcher = fallbackPattern.matcher(html);
            if (fallbackMatcher.find()) {
                String used = fallbackMatcher.group(1).replace(",", ".");
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
    
    private void updateVolumeStatus() {
        String status = "📊 Volumen: " + currentVolume;
        if (useRealVolume) {
            status += "\n✅ Echtes Volumen von Lidl";
        } else {
            status += "\n⚡ Simulations-Modus";
        }
        tvVolumeStatus.setText(status);
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
        
        webView.reload();
        
        handler.postDelayed(() -> {
            if (!isRunning) return;
            
            double decrease = consumptionRate * (currentWaitTime / 60000.0);
            currentDataValue = Math.max(0.05, currentDataValue - decrease);
            
            tvVolumeStatus.setText("📊 " + String.format("%.2f", currentDataValue) + " GB\n⏱ Zyklus " + cycleCount);
            
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
