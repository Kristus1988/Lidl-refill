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
    private Button btnStart, btnStop;
    private TextView tvStatus, tvVolume, tvRefill, tvRefillCount, tvNextCheck, tvPhase;
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

    // Verbrauchsanalyse
    private float consumptionRate = 0.01f;
    private long lastCheckTime = 0;
    private float lastVolumeForRate = 0.99f;
    private boolean isFirstCheck = true;

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
                refillCount++;
                tvRefillCount.setText("🔄 Refills: " + refillCount);
                tvStatus.setText("✅ Refill #" + refillCount + " aktiviert!");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                Toast.makeText(MainActivity.this, "✅ Refill #" + refillCount + " aktiviert!", Toast.LENGTH_SHORT).show();

                // 🔥 Nach Refill: Volumen auf 1,00 GB setzen (Annahme)
                currentRefillGb = 1.00f;
                lastRefillGb = 1.00f;
                consecutiveNoChange = 0;
                tvRefill.setText("🔄 Refill: 1.00 GB");
                tvPhase.setText("📍 Refill erfolgreich! Volumen: 1.00 GB");
                tvNextCheck.setText("⏱️ Nächste Prüfung: in Kürze");

                // 🔥 Wichtig: Sofort nach dem Refill die Seite neu laden,
                // damit das neue Volumen erfasst wird
                webView.reload();
            });
        }

        @JavascriptInterface
        public void onStatus(String status) {
            runOnUiThread(() -> tvStatus.setText(status));
        }

        @JavascriptInterface
        public void onLoginDetected() {
            runOnUiThread(() -> {
                isLoggedIn = true;
                tvStatus.setText("✅ Eingeloggt!");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                if (isRunning) {
                    startMonitoring();
                }
            });
        }

        @JavascriptInterface
        public void onNotLoggedIn() {
            runOnUiThread(() -> {
                isLoggedIn = false;
                tvStatus.setText("🔐 Bitte einloggen");
                tvStatus.setTextColor(Color.parseColor("#FFA726"));
            });
        }
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
        tvStatus = findViewById(R.id.tv_status);
        tvVolume = findViewById(R.id.tv_volume);
        tvRefill = findViewById(R.id.tv_refill);
        tvRefillCount = findViewById(R.id.tv_refill_count);
        tvNextCheck = findViewById(R.id.tv_next_check);
        tvPhase = findViewById(R.id.tv_phase);
        progressStatus = findViewById(R.id.progress_status);
    }

    private void loadLidlPage() {
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(LIDL_URL);
        tvStatus.setText("🔄 Lade Lidl Connect...");
        tvPhase.setText("📍 Warte auf Login");
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
                    if (isRunning && isLoggedIn) {
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
                "    Android.onLoginDetected();" +
                "  } else {" +
                "    Android.onNotLoggedIn();" +
                "  }" +
                "} catch(e) {" +
                "  Android.onStatus('⚠️ Fehler: ' + e.message);" +
                "}" +
                "})();";
        webView.loadUrl(js);
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
        if (timeDiff < 60000) return;

        float volumeDiff = lastVolumeForRate - currentRefillGb;
        if (volumeDiff > 0) {
            float minutes = timeDiff / 60000f;
            consumptionRate = volumeDiff / minutes;
            consumptionRate = Math.max(0.001f, Math.min(consumptionRate, 0.1f));
            tvPhase.setText("📊 Verbrauch: " + String.format("%.3f", consumptionRate) + " GB/min");
        }

        lastCheckTime = System.currentTimeMillis();
        lastVolumeForRate = currentRefillGb;
    }

    // ==========================================
    // REFILL ÜBERWACHUNG
    // ==========================================

    private void checkAndClickRefill() {
        if (!isRunning || !isLoggedIn) return;

        checkCount++;
        tvStatus.setText("🔍 Prüfung #" + checkCount);

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
                "          if(text.includes('Refill aktivieren')) {" +
                "            elements[i].scrollIntoView({behavior: 'smooth', block: 'center'});" +
                "            setTimeout(function(el) { el.click(); }, 500);" +
                "            Android.onRefillClicked();" +
                "            found = true;" +
                "            break;" +
                "          }" +
                "        }" +
                "        if (!found) {" +
                "          Android.onStatus('⚠️ Button nicht gefunden!');" +
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
    // ADAPTIVE WARTEZEIT (OHNE EXTRA LANGE PAUSE NACH REFILL!)
    // ==========================================

    private int calculateAdaptiveDelay() {
        float refill = currentRefillGb;
        int baseDelay;

        // 1. Basis-Intervall basierend auf aktuellem Volumen
        if (refill > 0.80) {
            baseDelay = random.nextInt(600) + 600; // 10-20 Minuten
        } else if (refill > 0.40) {
            baseDelay = random.nextInt(300) + 300; // 5-10 Minuten
        } else if (refill > 0.15) {
            baseDelay = random.nextInt(180) + 120; // 2-5 Minuten
        } else {
            baseDelay = random.nextInt(30) + 30; // 30-60 Sekunden
        }

        // 2. Verbrauchsbasierte Anpassung
        if (consumptionRate > 0.03) {
            baseDelay = (int) (baseDelay * 0.7);
        } else if (consumptionRate < 0.005) {
            baseDelay = (int) (baseDelay * 1.5);
        }

        // 3. Backoff bei unverändertem Volumen
        if (Math.abs(currentRefillGb - lastRefillGb) < 0.01) {
            consecutiveNoChange++;
            if (consecutiveNoChange > 3) {
                baseDelay = baseDelay * 2;
                tvPhase.setText("📉 Keine Änderung → längere Pause");
            }
        } else {
            consecutiveNoChange = 0;
        }
        lastRefillGb = currentRefillGb;

        // 4. Menschliche Variation (+/- 30%)
        double variation = 0.7 + (random.nextDouble() * 0.6);
        int finalDelay = (int) (baseDelay * variation);

        // 5. "Ablenkung" simulieren (10% Chance)
        if (random.nextInt(10) == 0) {
            finalDelay += random.nextInt(180) + 120;
            tvPhase.setText("🧠 Kurze Ablenkung...");
        }

        // 6. "Lese"-Pause simulieren (selten)
        if (random.nextInt(15) == 0) {
            finalDelay += random.nextInt(120) + 60;
            tvPhase.setText("📖 Seite wird gelesen...");
        }

        // 7. Begrenzung: mindestens 15 Sekunden, maximal 20 Minuten
        return Math.max(15, Math.min(finalDelay, 1200));
    }

    // ==========================================
    // MONITORING
    // ==========================================

    private void startMonitoring() {
        tvStatus.setText("🔍 Überwache Volumen...");
        tvStatus.setTextColor(Color.parseColor("#4FC3F7"));
        checkRefillDelayed();
    }

    private void checkRefillDelayed() {
        if (!isRunning || !isLoggedIn) return;

        int delay = calculateAdaptiveDelay() * 1000;

        // Nächste Prüfung anzeigen
        int minutes = delay / 60000;
        int seconds = (delay % 60000) / 1000;
        if (minutes > 0) {
            tvNextCheck.setText("⏱️ Nächste Prüfung: " + minutes + "m " + seconds + "s");
        } else {
            tvNextCheck.setText("⏱️ Nächste Prüfung: " + seconds + "s");
        }

        // Phase anzeigen
        String phaseText = getPhaseText();
        if (!tvPhase.getText().toString().contains("Ablenkung") &&
            !tvPhase.getText().toString().contains("gelesen") &&
            !tvPhase.getText().toString().contains("Verbrauch")) {
            tvPhase.setText("📍 " + phaseText);
        }

        mainHandler.postDelayed(() -> {
            if (isRunning && isLoggedIn) {
                webView.reload();
                checkRefillDelayed();
            }
        }, delay);
    }

    private String getPhaseText() {
        if (currentRefillGb > 0.80) {
            return "🟢 Refill: " + String.format("%.2f", currentRefillGb) + " GB (voll)";
        } else if (currentRefillGb > 0.40) {
            return "🟡 Refill: " + String.format("%.2f", currentRefillGb) + " GB (mittel)";
        } else if (currentRefillGb > 0.15) {
            return "🟠 Refill: " + String.format("%.2f", currentRefillGb) + " GB (niedrig)";
        } else {
            return "🔴 Refill: " + String.format("%.2f", currentRefillGb) + " GB (kritisch!)";
        }
    }

    // ==========================================
    // BUTTONS
    // ==========================================

    private void setupButtons() {
        btnStart.setOnClickListener(v -> {
            if (!isLoggedIn) {
                Toast.makeText(this, "⚠️ Bitte zuerst einloggen!", Toast.LENGTH_SHORT).show();
                return;
            }

            isRunning = true;
            refillCount = 0;
            checkCount = 0;
            consecutiveNoChange = 0;
            currentRefillGb = 0.99f;
            lastRefillGb = 0.99f;
            isFirstCheck = true;
            consumptionRate = 0.01f;
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
            tvPhase.setText("📍 Gestoppt");
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
