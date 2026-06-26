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
    private TextView tvStatus, tvVolume, tvRefill, tvRefillCount, tvNextCheck;
    private ProgressBar progressStatus;

    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private int refillCount = 0;
    private boolean isLoggedIn = false;
    private Random random = new Random();
    private int checkCount = 0;

    private float currentRefillGb = 0.99f;
    private float currentInklusivGb = 25.0f;

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
                tvStatus.setText("✅ Eingeloggt! Überwache...");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                startMonitoring();
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
        progressStatus = findViewById(R.id.progress_status);
    }

    private void loadLidlPage() {
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(LIDL_URL);
        tvStatus.setText("🔄 Lade Lidl Connect...");
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
    // ADAPTIVE WARTEZEIT (menschlich & selten)
    // ==========================================

    private int calculateAdaptiveDelay() {
        float refill = currentRefillGb;

        int baseDelay;

        if (refill > 0.80) {
            baseDelay = random.nextInt(120) + 180; // 3-5 Minuten
        } else if (refill > 0.40) {
            baseDelay = random.nextInt(90) + 120; // 2-3,5 Minuten
        } else if (refill > 0.15) {
            baseDelay = random.nextInt(60) + 60; // 1-2 Minuten
        } else {
            baseDelay = random.nextInt(20) + 20; // 20-40 Sekunden (nur wenn kritisch!)
        }

        // Große zufällige "menschliche" Variation
        if (random.nextInt(3) == 0) {
            baseDelay += random.nextInt(120) + 60; // +1-3 Minuten extra
        }

        return baseDelay;
    }

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
        tvNextCheck.setText("⏱️ Nächste Prüfung: " + minutes + "m " + seconds + "s");

        String phaseText;
        if (currentRefillGb > 0.80) {
            phaseText = "🟢 Refill: " + String.format("%.2f", currentRefillGb) + " GB (voll)";
        } else if (currentRefillGb > 0.40) {
            phaseText = "🟡 Refill: " + String.format("%.2f", currentRefillGb) + " GB (mittel)";
        } else if (currentRefillGb > 0.15) {
            phaseText = "🟠 Refill: " + String.format("%.2f", currentRefillGb) + " GB (niedrig)";
        } else {
            phaseText = "🔴 Refill: " + String.format("%.2f", currentRefillGb) + " GB (kritisch!)";
        }
        tvStatus.setText(phaseText);

        mainHandler.postDelayed(() -> {
            if (isRunning && isLoggedIn) {
                webView.reload();
                checkRefillDelayed();
            }
        }, delay);
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
            currentRefillGb = 0.99f;
            tvRefillCount.setText("🔄 Refills: 0");
            tvStatus.setText("🔄 Starte Überwachung...");
            tvStatus.setTextColor(Color.parseColor("#4FC3F7"));
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);

            checkAndClickRefill();
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
        // Beim Zurückkehren prüfen, ob noch eingeloggt
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
