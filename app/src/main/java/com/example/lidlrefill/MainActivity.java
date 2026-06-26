package com.example.lidlrefill;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private Button btnStart, btnStop, btnRefresh, btnRefillTest;
    private TextView tvStatus, tvVolume, tvRefill, tvRefillCount, tvNextCheck, tvLoginHint;
    private ProgressBar progressStatus;

    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private int refillCount = 0;
    private boolean isLoggedIn = false;
    private Random random = new Random();
    private int checkCount = 0;

    private float currentRefillGb = 0.99f;
    private float currentInklusivGb = 25.0f;
    private float lastRefillGb = 0.99f;
    private int consecutiveNoChange = 0;

    private long lastCheckTime = 0;
    private float lastVolumeForRate = 0.99f;
    private boolean isFirstCheck = true;
    private float consumptionRate = 0.05f;

    private boolean isWaitingForRefill = false; // 🔥 NEU: Warten auf Refill-Bestätigung

    private static final String LIDL_URL = "https://kundenkonto.lidl-connect.de/mein-lidl-connect.html";

    // ==========================================
    // JAVASCRIPT-INTERFACE
    // ==========================================

    private class LidlJSInterface {
        @JavascriptInterface
        public void onVolumeUpdate(String inklusiv, String refill) {
            runOnUiThread(() -> {
                try {
                    currentInklusivGb = Float.parseFloat(inklusiv.replace(",", "."));
                } catch (NumberFormatException e) {
                    currentInklusivGb = 25.0f;
                }
                try {
                    currentRefillGb = Float.parseFloat(refill.replace(",", "."));
                } catch (NumberFormatException e) {
                    currentRefillGb = 0.99f;
                }

                tvVolume.setText("📦 Inklusiv: " + inklusiv + " GB / 25 GB");
                tvRefill.setText("🔄 Refill: " + refill + " GB");

                calculateConsumptionRate();
            });
        }

        @JavascriptInterface
        public void onRefillClicked() {
            runOnUiThread(() -> {
                // 🔥 Refill wurde geklickt – jetzt warten wir 2-3 Sekunden
                isWaitingForRefill = true;
                tvStatus.setText("⏳ Refill geklickt! Warte 2-3 Sekunden...");
                tvStatus.setTextColor(Color.parseColor("#FF9800"));

                // 🔥 Nach 2-3 Sekunden Seite neu laden und Volumen prüfen
                int delay = random.nextInt(1000) + 2000; // 2000-3000 ms
                mainHandler.postDelayed(() -> {
                    if (webView != null) {
                        webView.reload();
                        tvStatus.setText("🔄 Seite wird neu geladen...");
                        isWaitingForRefill = false;

                        // Nach dem Neuladen wird onPageFinished aufgerufen und prüft das Volumen
                    }
                }, delay);
            });
        }

        @JavascriptInterface
        public void onRefillNotFound() {
            runOnUiThread(() -> {
                tvStatus.setText("⚠️ Refill-Button nicht gefunden!");
                tvStatus.setTextColor(Color.parseColor("#FF5722"));
                Toast.makeText(MainActivity.this, "⚠️ Refill-Button nicht gefunden!", Toast.LENGTH_SHORT).show();
                isWaitingForRefill = false;
            });
        }

        @JavascriptInterface
        public void onLoginSuccess() {
            runOnUiThread(() -> {
                isLoggedIn = true;
                tvStatus.setText("✅ Angemeldet!");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                tvLoginHint.setVisibility(View.GONE);
                if (isRunning) {
                    startMonitoring();
                }
            });
        }

        @JavascriptInterface
        public void onLoginFailed() {
            runOnUiThread(() -> {
                isLoggedIn = false;
                tvStatus.setText("❌ Login fehlgeschlagen!");
                tvStatus.setTextColor(Color.parseColor("#F44336"));
                tvLoginHint.setVisibility(View.VISIBLE);
                tvLoginHint.setText("⚠️ Login fehlgeschlagen! Bitte manuell einloggen.");
            });
        }

        @JavascriptInterface
        public void onStatus(String status) {
            runOnUiThread(() -> tvStatus.setText(status));
        }

        @JavascriptInterface
        public void onAlreadyLoggedIn() {
            runOnUiThread(() -> {
                isLoggedIn = true;
                tvStatus.setText("✅ Bereits eingeloggt!");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                tvLoginHint.setVisibility(View.GONE);
                if (isRunning) {
                    startMonitoring();
                }
            });
        }
    }

    // ==========================================
    // VERBRAUCHS-ANALYSE
    // ==========================================

    private void calculateConsumptionRate() {
        if (isFirstCheck) {
            isFirstCheck = false;
            lastCheckTime = System.currentTimeMillis();
            lastVolumeForRate = currentRefillGb;
            return;
        }

        long timeDiff = System.currentTimeMillis() - lastCheckTime;
        if (timeDiff < 30000) return;

        float volumeDiff = lastVolumeForRate - currentRefillGb;
        if (volumeDiff > 0.001) {
            float minutes = timeDiff / 60000f;
            consumptionRate = volumeDiff / minutes;
            consumptionRate = Math.max(0.01f, Math.min(consumptionRate, 0.2f));
        }

        lastCheckTime = System.currentTimeMillis();
        lastVolumeForRate = currentRefillGb;
    }

    // ==========================================
    // LEBENSZYKLUS
    // ==========================================

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupWebView();
        setupButtons();
        loadLidlPage();
    }

    private void initViews() {
        webView = findViewById(R.id.webview_lidl);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnRefillTest = findViewById(R.id.btn_refill_test);
        tvStatus = findViewById(R.id.tv_status);
        tvVolume = findViewById(R.id.tv_volume);
        tvRefill = findViewById(R.id.tv_refill);
        tvRefillCount = findViewById(R.id.tv_refill_count);
        tvNextCheck = findViewById(R.id.tv_next_check);
        tvLoginHint = findViewById(R.id.tv_login_hint);
        progressStatus = findViewById(R.id.progress_status);
    }

    private void loadLidlPage() {
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(LIDL_URL);
        tvStatus.setText("🔄 Lade Lidl Connect...");
        tvLoginHint.setText("🔑 Bitte logge dich in der WebView ein");
        tvLoginHint.setVisibility(View.VISIBLE);
    }

    // ==========================================
    // WEBVIEW EINRICHTEN
    // ==========================================

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.getSettings().setSafeBrowsingEnabled(false);
        }

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url.contains("lidl-connect.de")) {
                    checkLoginStatus();
                    if (isLoggedIn && isRunning && !isWaitingForRefill) {
                        checkAndClickRefill();
                    }
                }
            }
        });

        webView.addJavascriptInterface(new LidlJSInterface(), "Android");
    }

    // ==========================================
    // LOGIN-STATUS PRÜFEN
    // ==========================================

    private void checkLoginStatus() {
        String js = "javascript:(function() {" +
                "try {" +
                "  var loggedIn = document.body.innerText.includes('Eingeloggt als:') || document.body.innerText.includes('Mein Guthaben');" +
                "  if (loggedIn) {" +
                "    Android.onAlreadyLoggedIn();" +
                "  } else {" +
                "    Android.onStatus('🔐 Bitte manuell einloggen');" +
                "  }" +
                "} catch(e) {" +
                "  Android.onStatus('⚠️ Fehler: ' + e.message);" +
                "}" +
                "})();";
        webView.loadUrl(js);
    }

    // ==========================================
    // REFILL ÜBERWACHUNG (mit blauem Button)
    // ==========================================

    private void checkAndClickRefill() {
        if (!isLoggedIn || isWaitingForRefill) return;

        checkCount++;
        tvStatus.setText("🔍 Prüfung #" + checkCount);

        // 🔥 Jetzt mit genauer Erkennung des blauen Buttons
        String js = "javascript:(function() {" +
                "try {" +
                "  var pageText = document.body.innerText;" +
                "  var inklusivMatch = pageText.match(/(\\d+[\\,\\d]*)\\s*GB\\s*\\/\\s*25\\s*GB/);" +
                "  var refillMatch = pageText.match(/Unlimited Refill\\s*(\\d+[\\,\\d]*)\\s*GB/);" +
                "  var inklusiv = inklusivMatch ? inklusivMatch[1].replace(',', '.') : '--';" +
                "  var refill = refillMatch ? refillMatch[1].replace(',', '.') : '--';" +
                "  Android.onVolumeUpdate(inklusiv, refill);" +
                "  if (refillMatch) {" +
                "    var refillValue = parseFloat(refill);" +
                "    if (refillValue <= 0.15) {" +
                "      var delay = Math.floor(Math.random() * 3000) + 2000;" +
                "      Android.onStatus('🎯 Ziel: ' + refillValue + ' GB → Klicke...');" +
                "      setTimeout(function() {" +
                "        var found = false;" +
                "        var elements = document.querySelectorAll('button, div, a, span');" +
                "        for(var i=0; i<elements.length; i++) {" +
                "          var text = elements[i].innerText || elements[i].textContent || '';" +
                "          if(text && text.includes('Refill aktivieren')) {" +
                "            elements[i].scrollIntoView({behavior: 'smooth', block: 'center'});" +
                "            setTimeout(function(el) { el.click(); }, 500);" +
                "            Android.onRefillClicked();" +
                "            found = true;" +
                "            break;" +
                "          }" +
                "        }" +
                "        if (!found) {" +
                "          Android.onRefillNotFound();" +
                "        }" +
                "      }, delay);" +
                "    } else {" +
                "      Android.onStatus('⏳ Warte auf 0.15 GB (aktuell: ' + refillValue + ' GB)');" +
                "    }" +
                "  }" +
                "} catch(e) {" +
                "  Android.onStatus('⚠️ Fehler: ' + e.message);" +
                "}" +
                "})();";
        webView.loadUrl(js);
    }

    // ==========================================
    // MANUELLER REFILL TEST
    // ==========================================

    private void manualRefillTest() {
        if (!isLoggedIn) {
            Toast.makeText(this, "⚠️ Bitte zuerst einloggen!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isWaitingForRefill) {
            Toast.makeText(this, "⏳ Refill wird bereits verarbeitet...", Toast.LENGTH_SHORT).show();
            return;
        }

        tvStatus.setText("🧪 Refill-Test wird ausgeführt...");
        tvStatus.setTextColor(Color.parseColor("#9C27B0"));

        String js = "javascript:(function() {" +
                "try {" +
                "  var found = false;" +
                "  var elements = document.querySelectorAll('button, div, a, span');" +
                "  for(var i=0; i<elements.length; i++) {" +
                "    var text = elements[i].innerText || elements[i].textContent || '';" +
                "    if(text && text.includes('Refill aktivieren')) {" +
                "      elements[i].scrollIntoView({behavior: 'smooth', block: 'center'});" +
                "      setTimeout(function(el) { el.click(); }, 500);" +
                "      Android.onRefillClicked();" +
                "      found = true;" +
                "      break;" +
                "    }" +
                "  }" +
                "  if (!found) {" +
                "    Android.onRefillNotFound();" +
                "  }" +
                "} catch(e) {" +
                "  Android.onStatus('⚠️ Fehler: ' + e.message);" +
                "}" +
                "})();";
        webView.loadUrl(js);
    }

    // ==========================================
    // WARTEZEIT ANPASSEN
    // ==========================================

    private void updateNextCheckTime() {
        int delay = calculateAdaptiveDelay() * 1000;
        int minutes = delay / 60000;
        int seconds = (delay % 60000) / 1000;
        if (minutes > 0) {
            tvNextCheck.setText("⏱️ Nächste Prüfung: " + minutes + "m " + seconds + "s");
        } else {
            tvNextCheck.setText("⏱️ Nächste Prüfung: " + seconds + "s");
        }
    }

    // ==========================================
    // ADAPTIVE WARTEZEIT
    // ==========================================

    private int calculateAdaptiveDelay() {
        float refill = currentRefillGb;
        int baseDelay;

        if (refill > 0.80) {
            baseDelay = random.nextInt(600) + 900; // 15-25 Minuten
        } else if (refill > 0.40) {
            baseDelay = random.nextInt(420) + 480; // 8-15 Minuten
        } else if (refill > 0.15) {
            baseDelay = random.nextInt(240) + 240; // 4-8 Minuten
        } else {
            baseDelay = random.nextInt(120) + 60; // 1-3 Minuten
        }

        if (consumptionRate > 0.06) {
            baseDelay = (int) (baseDelay * 0.7);
        } else if (consumptionRate > 0.03) {
            baseDelay = (int) (baseDelay * 0.85);
        } else if (consumptionRate < 0.01) {
            baseDelay = (int) (baseDelay * 1.3);
        }

        if (Math.abs(currentRefillGb - lastRefillGb) < 0.01) {
            consecutiveNoChange++;
            if (consecutiveNoChange > 3) {
                baseDelay = (int) (baseDelay * 1.5);
            }
        } else {
            consecutiveNoChange = 0;
        }
        lastRefillGb = currentRefillGb;

        double variation = 0.7 + (random.nextDouble() * 0.6);
        int finalDelay = (int) (baseDelay * variation);

        if (random.nextInt(10) == 0) {
            finalDelay += random.nextInt(180) + 60;
        }

        if (random.nextInt(15) == 0) {
            finalDelay += random.nextInt(300) + 120;
        }

        return Math.max(30, Math.min(finalDelay, 1500));
    }

    private void startMonitoring() {
        tvStatus.setText("🔍 Überwache Volumen...");
        tvStatus.setTextColor(Color.parseColor("#4FC3F7"));
        checkRefillDelayed();
    }

    private void checkRefillDelayed() {
        if (!isRunning || !isLoggedIn || isWaitingForRefill) return;

        int delay = calculateAdaptiveDelay() * 1000;

        int minutes = delay / 60000;
        int seconds = (delay % 60000) / 1000;
        if (minutes > 0) {
            tvNextCheck.setText("⏱️ Nächste Prüfung: " + minutes + "m " + seconds + "s");
        } else {
            tvNextCheck.setText("⏱️ Nächste Prüfung: " + seconds + "s");
        }

        if (consumptionRate > 0.03) {
            tvStatus.setText("⚡ " + String.format("%.2f", consumptionRate) + " GB/min | ⏱️ " + (delay/1000) + "s");
        } else {
            tvStatus.setText("🐢 " + String.format("%.2f", consumptionRate) + " GB/min | ⏱️ " + (delay/1000) + "s");
        }

        mainHandler.postDelayed(() -> {
            if (isRunning && isLoggedIn && !isWaitingForRefill) {
                webView.reload();
                checkRefillDelayed();
            }
        }, delay);
    }

    // ==========================================
    // BUTTONS
    // ==========================================

    private void setupButtons() {
        // 🧪 MANUELLER REFILL TEST
        btnRefillTest.setOnClickListener(v -> manualRefillTest());

        // 🔄 MANUELLER REFRESH
        btnRefresh.setOnClickListener(v -> {
            if (webView != null) {
                webView.reload();
                tvStatus.setText("🔄 Seite wird aktualisiert...");
                tvStatus.setTextColor(Color.parseColor("#4FC3F7"));
                Toast.makeText(this, "🔄 Seite aktualisiert! Prüfe Refill...", Toast.LENGTH_SHORT).show();

                mainHandler.postDelayed(() -> {
                    if (isLoggedIn && !isWaitingForRefill) {
                        checkAndClickRefill();
                        tvStatus.setText("🔍 Prüfung nach manuellem Refresh");
                    } else if (isWaitingForRefill) {
                        tvStatus.setText("⏳ Refill wird bereits verarbeitet...");
                    } else {
                        tvStatus.setText("⚠️ Bitte zuerst einloggen!");
                    }
                }, 3000);
            }
        });

        btnStart.setOnClickListener(v -> {
            if (!isLoggedIn) {
                Toast.makeText(this, "⚠️ Bitte zuerst in der WebView einloggen!", Toast.LENGTH_SHORT).show();
                return;
            }

            if (isWaitingForRefill) {
                Toast.makeText(this, "⏳ Refill wird bereits verarbeitet...", Toast.LENGTH_SHORT).show();
                return;
            }

            isRunning = true;
            refillCount = 0;
            checkCount = 0;
            consecutiveNoChange = 0;
            currentRefillGb = 0.99f;
            lastRefillGb = 0.99f;
            isFirstCheck = true;
            consumptionRate = 0.05f;
            tvRefillCount.setText("🔄 Refills: 0");
            tvStatus.setText("🔄 Starte Überwachung...");
            tvStatus.setTextColor(Color.parseColor("#4FC3F7"));
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);

            startMonitoring();
        });

        btnStop.setOnClickListener(v -> {
            isRunning = false;
            mainHandler.removeCallbacksAndMessages(null);
            tvStatus.setText("⏹️ Gestoppt");
            tvStatus.setTextColor(Color.parseColor("#B0BEC5"));
            tvNextCheck.setText("⏱️ --");
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
        });

        btnStop.setEnabled(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
        if (isRunning) {
            checkLoginStatus();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.destroy();
        }
    }
}
