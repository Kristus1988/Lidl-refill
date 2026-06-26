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

    private static final float TARGET_VOLUME = 0.35f;

    private float currentRefillGb = 0.99f;
    private float currentInklusivGb = 25.0f;
    private float lastRefillGb = 0.99f;
    private int consecutiveNoChange = 0;

    // Intelligente Wartezeit
    private long lastCheckTime = 0;
    private float lastVolumeForRate = 0.99f;
    private float consumptionRate = 0.05f;
    private boolean isFirstCheck = true;
    private boolean isLearningPhase = true;

    private boolean isWaitingForRefill = false;
    private boolean isManualRefill = false;
    private boolean isRefreshing = false;
    private int countdownSeconds = 0;
    private Runnable countdownRunnable;

    private float buttonX = 0;
    private float buttonY = 0;
    private float buttonWidth = 0;
    private float buttonHeight = 0;
    private boolean isTouchPending = false;

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

                if (isRunning && isLoggedIn && !isWaitingForRefill && !isManualRefill && !isRefreshing) {
                    checkAndClickRefillWithTouch();
                }
            });
        }

        @JavascriptInterface
        public void onRefillClicked() {
            runOnUiThread(() -> {
                isWaitingForRefill = true;
                refillCount++;
                tvRefillCount.setText("🔄 Refills: " + refillCount);

                tvStatus.setText("✅ Refill #" + refillCount + " aktiviert!");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                Toast.makeText(MainActivity.this, "✅ Refill #" + refillCount + " aktiviert!", Toast.LENGTH_SHORT).show();

                currentRefillGb = 1.00f;
                lastRefillGb = 1.00f;
                consecutiveNoChange = 0;
                tvRefill.setText("🔄 Refill: 1.00 GB");
                lastVolumeForRate = 1.00f;

                isFirstCheck = true;
                isLearningPhase = true;

                int delay = random.nextInt(1000) + 2000;
                tvStatus.setText("⏳ Refill verarbeitet. Warte " + (delay/1000) + "s...");

                mainHandler.postDelayed(() -> {
                    if (webView != null) {
                        webView.reload();
                        tvStatus.setText("🔄 Seite wird neu geladen...");
                        isWaitingForRefill = false;
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
                updateNextCheckTime();
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
                    mainHandler.postDelayed(() -> {
                        if (isRunning && isLoggedIn && !isWaitingForRefill) {
                            checkAndClickRefillWithTouch();
                            updateNextCheckTime();
                        }
                    }, 3000);
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
                    mainHandler.postDelayed(() -> {
                        if (isRunning && isLoggedIn && !isWaitingForRefill) {
                            checkAndClickRefillWithTouch();
                            updateNextCheckTime();
                        }
                    }, 3000);
                }
            });
        }

        @JavascriptInterface
        public void onManualRefillSuccess() {
            runOnUiThread(() -> {
                isManualRefill = false;
                tvStatus.setText("✅ Manueller Refill erfolgreich!");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));

                if (webView != null) {
                    mainHandler.postDelayed(() -> {
                        webView.reload();
                        tvStatus.setText("🔄 Prüfe neues Volumen...");
                    }, 2000);
                }
            });
        }

        @JavascriptInterface
        public void onDebugLoginStatus(String status) {
            runOnUiThread(() -> {
                tvStatus.setText("🔍 " + status);
                tvStatus.setTextColor(Color.parseColor("#9C27B0"));
            });
        }

        @JavascriptInterface
        public void onButtonPosition(float x, float y, float width, float height) {
            runOnUiThread(() -> {
                buttonX = x;
                buttonY = y;
                buttonWidth = width;
                buttonHeight = height;
                performTouchClick();
            });
        }

        // 🔥 NEU: Scrollt zum Refill-Button und gibt dann die Position zurück
        @JavascriptInterface
        public void scrollToRefillButton() {
            runOnUiThread(() -> {
                tvStatus.setText("🔽 Scrolle zum Refill-Button...");
                String js = "javascript:(function() {" +
                        "try {" +
                        "  var found = false;" +
                        "  var elements = document.querySelectorAll('button, div, a, span');" +
                        "  for(var i=0; i<elements.length; i++) {" +
                        "    var text = elements[i].innerText || elements[i].textContent || '';" +
                        "    if(text && text.includes('Refill aktivieren')) {" +
                        "      elements[i].scrollIntoView({behavior: 'smooth', block: 'center'});" +
                        "      setTimeout(function() {" +
                        "        var rect = elements[i].getBoundingClientRect();" +
                        "        Android.onButtonPosition(rect.left, rect.top, rect.width, rect.height);" +
                        "      }, 500);" +
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
            });
        }
    }

    // ==========================================
    // INTELLIGENTE WARTEZEIT
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
            consumptionRate = Math.max(0.001f, Math.min(consumptionRate, 0.2f));
            isLearningPhase = false;

            tvStatus.setText("📊 Verbrauch: " + String.format("%.3f", consumptionRate) + " GB/min");
        }

        lastCheckTime = System.currentTimeMillis();
        lastVolumeForRate = currentRefillGb;
    }

    private int calculateIntelligentDelay() {
        float refill = currentRefillGb;

        if (isLearningPhase || consumptionRate <= 0.001) {
            if (refill > 0.80) {
                return random.nextInt(120) + 120;
            } else if (refill > 0.40) {
                return random.nextInt(90) + 90;
            } else if (refill > 0.15) {
                return random.nextInt(60) + 60;
            } else {
                return random.nextInt(30) + 30;
            }
        }

        float gbRemaining = refill - TARGET_VOLUME;

        if (gbRemaining <= 0) {
            return random.nextInt(30) + 30;
        }

        float estimatedMinutes = gbRemaining / consumptionRate;
        estimatedMinutes = Math.max(1, Math.min(estimatedMinutes, 30));

        double variation = 0.75 + (random.nextDouble() * 0.5);
        int finalDelay = (int) (estimatedMinutes * 60 * variation);

        return Math.max(30, Math.min(finalDelay, 1800));
    }

    // ==========================================
    // TOUCH-SIMULATION
    // ==========================================

    private void performTouchClick() {
        if (buttonX == 0 || buttonY == 0) return;

        isTouchPending = true;

        int delay = random.nextInt(600) + 200;

        mainHandler.postDelayed(() -> {
            if (webView == null) return;

            float centerX = buttonX + buttonWidth / 2;
            float centerY = buttonY + buttonHeight / 2;

            float touchX = centerX + (random.nextFloat() - 0.5f) * buttonWidth * 0.3f;
            float touchY = centerY + (random.nextFloat() - 0.5f) * buttonHeight * 0.3f;

            String js = "javascript:(function() {" +
                    "try {" +
                    "  var x = " + touchX + ";" +
                    "  var y = " + touchY + ";" +
                    "  var element = document.elementFromPoint(x, y);" +
                    "  if (element) {" +
                    "    var rect = element.getBoundingClientRect();" +
                    "    var touch = new Touch({" +
                    "      identifier: Date.now()," +
                    "      target: element," +
                    "      clientX: x," +
                    "      clientY: y," +
                    "      radiusX: 10," +
                    "      radiusY: 10," +
                    "      rotationAngle: 0," +
                    "      force: 1" +
                    "    });" +
                    "    var touchEvent = new TouchEvent('touchstart', {" +
                    "      touches: [touch]," +
                    "      targetTouches: [touch]," +
                    "      changedTouches: [touch]," +
                    "      bubbles: true," +
                    "      cancelable: true" +
                    "    });" +
                    "    element.dispatchEvent(touchEvent);" +
                    "    setTimeout(function() {" +
                    "      var touchEnd = new TouchEvent('touchend', {" +
                    "        touches: []," +
                    "        targetTouches: []," +
                    "        changedTouches: [touch]," +
                    "        bubbles: true," +
                    "        cancelable: true" +
                    "      });" +
                    "      element.dispatchEvent(touchEnd);" +
                    "      Android.onRefillClicked();" +
                    "    }, " + (random.nextInt(200) + 100) + ");" +
                    "  } else {" +
                    "    Android.onRefillNotFound();" +
                    "  }" +
                    "} catch(e) {" +
                    "  Android.onStatus('⚠️ Touch-Fehler: ' + e.message);" +
                    "}" +
                    "})();";
            webView.loadUrl(js);
            isTouchPending = false;
        }, delay);
    }

    // ==========================================
    // REFILL ÜBERWACHUNG (MIT SCROLL ZUM BUTTON)
    // ==========================================

    private void checkAndClickRefillWithTouch() {
        if (!isLoggedIn || isWaitingForRefill || isManualRefill || isTouchPending) {
            return;
        }

        checkCount++;
        tvStatus.setText("🔍 Prüfung #" + checkCount);

        // 🔥 1. Volumen prüfen
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
                "    if (refillValue <= " + TARGET_VOLUME + ") {" +
                "      Android.onStatus('🎯 Ziel: ' + refillValue + ' GB → Scrolle & Tippe...');" +
                "      setTimeout(function() {" +
                "        Android.scrollToRefillButton();" +
                "      }, 500);" +
                "    } else {" +
                "      Android.onStatus('⏳ Warte auf " + TARGET_VOLUME + " GB (aktuell: ' + refillValue + ' GB)');" +
                "    }" +
                "  }" +
                "} catch(e) {" +
                "  Android.onStatus('⚠️ Fehler: ' + e.message);" +
                "}" +
                "})();";
        webView.loadUrl(js);
    }

    // ==========================================
    // MANUELLER REFILL TEST (MIT SCROLL)
    // ==========================================

    private void manualRefillTest() {
        if (!isLoggedIn) {
            Toast.makeText(this, "⚠️ Bitte zuerst einloggen!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isWaitingForRefill || isTouchPending) {
            Toast.makeText(this, "⏳ Refill wird bereits verarbeitet...", Toast.LENGTH_SHORT).show();
            return;
        }

        isManualRefill = true;
        tvStatus.setText("🧪 Suche Refill-Button...");
        tvStatus.setTextColor(Color.parseColor("#9C27B0"));

        // 🔥 Direkt scrollen und Button suchen
        if (webView != null) {
            String scrollJs = "javascript:(function() {" +
                    "try {" +
                    "  var found = false;" +
                    "  var elements = document.querySelectorAll('button, div, a, span');" +
                    "  for(var i=0; i<elements.length; i++) {" +
                    "    var text = elements[i].innerText || elements[i].textContent || '';" +
                    "    if(text && text.includes('Refill aktivieren')) {" +
                    "      elements[i].scrollIntoView({behavior: 'smooth', block: 'center'});" +
                    "      setTimeout(function() {" +
                    "        var rect = elements[i].getBoundingClientRect();" +
                    "        Android.onButtonPosition(rect.left, rect.top, rect.width, rect.height);" +
                    "      }, 500);" +
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
            webView.loadUrl(scrollJs);
        }
    }

    // ==========================================
    // COUNTDOWN
    // ==========================================

    private void startCountdown(int seconds) {
        if (countdownRunnable != null) {
            mainHandler.removeCallbacks(countdownRunnable);
        }

        countdownSeconds = seconds;
        tvNextCheck.setText("⏱️ Nächste Prüfung: " + seconds + "s");

        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning || !isLoggedIn) {
                    return;
                }

                countdownSeconds--;
                if (countdownSeconds > 0) {
                    tvNextCheck.setText("⏱️ Nächste Prüfung: " + countdownSeconds + "s");
                    mainHandler.postDelayed(this, 1000);
                } else {
                    tvNextCheck.setText("⏱️ Prüfung jetzt!");
                    if (isRunning && isLoggedIn && !isWaitingForRefill && !isManualRefill && !isRefreshing) {
                        isRefreshing = true;
                        webView.reload();
                    }
                }
            }
        };
        mainHandler.post(countdownRunnable);
    }

    // ==========================================
    // WARTEZEIT AKTUALISIEREN
    // ==========================================

    private void updateNextCheckTime() {
        int delay = calculateIntelligentDelay();
        startCountdown(delay);
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

                    if (isLoggedIn && isRunning && !isWaitingForRefill && !isManualRefill) {
                        isRefreshing = false;
                        checkAndClickRefillWithTouch();
                        updateNextCheckTime();
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
                "  var pageText = document.body.innerText;" +
                "  var isLoggedIn = pageText.includes('Eingeloggt als:') || " +
                "                    pageText.includes('Mein Guthaben') || " +
                "                    pageText.includes('Übersicht') || " +
                "                    pageText.includes('Guthaben') || " +
                "                    pageText.includes('Tarif');" +
                "  if (isLoggedIn) {" +
                "    Android.onAlreadyLoggedIn();" +
                "  } else {" +
                "    Android.onLoginFailed();" +
                "  }" +
                "} catch(e) {" +
                "  Android.onStatus('⚠️ Fehler: ' + e.message);" +
                "}" +
                "})();";
        webView.loadUrl(js);
    }

    private void startMonitoring() {
        tvStatus.setText("🔍 Überwache Volumen...");
        tvStatus.setTextColor(Color.parseColor("#4FC3F7"));
        mainHandler.postDelayed(() -> {
            if (isRunning && isLoggedIn && !isWaitingForRefill) {
                checkAndClickRefillWithTouch();
                updateNextCheckTime();
            }
        }, 3000);
    }

    // ==========================================
    // BUTTONS
    // ==========================================

    private void setupButtons() {
        btnRefillTest.setOnClickListener(v -> manualRefillTest());

        btnRefresh.setOnClickListener(v -> {
            if (webView != null) {
                isRefreshing = true;
                webView.reload();
                tvStatus.setText("🔄 Seite wird aktualisiert...");
                tvStatus.setTextColor(Color.parseColor("#4FC3F7"));
                Toast.makeText(this, "🔄 Seite aktualisiert! Prüfe Refill...", Toast.LENGTH_SHORT).show();

                mainHandler.postDelayed(() -> {
                    if (isLoggedIn && !isWaitingForRefill && !isManualRefill) {
                        checkAndClickRefillWithTouch();
                        updateNextCheckTime();
                        tvStatus.setText("🔍 Prüfung nach manuellem Refresh");
                    } else if (isWaitingForRefill || isManualRefill) {
                        tvStatus.setText("⏳ Refill wird bereits verarbeitet...");
                    } else {
                        tvStatus.setText("⚠️ Bitte zuerst einloggen!");
                    }
                    isRefreshing = false;
                }, 3000);
            }
        });

        btnStart.setOnClickListener(v -> {
            if (!isLoggedIn) {
                Toast.makeText(this, "⚠️ Bitte zuerst in der WebView einloggen!", Toast.LENGTH_SHORT).show();
                return;
            }

            if (isWaitingForRefill || isManualRefill || isTouchPending) {
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
            isLearningPhase = true;
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
            if (countdownRunnable != null) {
                mainHandler.removeCallbacks(countdownRunnable);
            }
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
