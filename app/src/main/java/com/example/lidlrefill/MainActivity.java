package com.example.lidlrefill;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
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
    private Button btnStart, btnStop, btnRefresh, btnRefillTest, btnRecordRefill;
    private TextView tvStatus, tvVolume, tvRefill, tvRefillCount, tvNextCheck, tvLoginHint, tvRecordStatus;
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

    // ==========================================
    // 📝 RECORDER VARIABLEN
    // ==========================================

    private static final String PREFS_NAME = "RefillRecorderPrefs";
    private static final String KEY_SAVED_X = "saved_x";
    private static final String KEY_SAVED_Y = "saved_y";
    private static final String KEY_SAVED_WIDTH = "saved_width";
    private static final String KEY_SAVED_HEIGHT = "saved_height";
    private static final String KEY_SAVED_URL = "saved_url";
    private static final String KEY_IS_RECORDED = "is_recorded";

    private float savedButtonX = 0;
    private float savedButtonY = 0;
    private float savedButtonWidth = 0;
    private float savedButtonHeight = 0;
    private String savedUrl = "";
    private boolean isButtonRecorded = false;

    private boolean isRecordingMode = false;
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
                    findAndClickRefillButton();
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
                            findAndClickRefillButton();
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
                            findAndClickRefillButton();
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

        // ==========================================
        // 📝 RECORDER - BUTTON POSITION SPEICHERN
        // ==========================================

        @JavascriptInterface
        public void onRecordButtonPosition(float x, float y, float width, float height) {
            runOnUiThread(() -> {
                savedButtonX = x;
                savedButtonY = y;
                savedButtonWidth = width;
                savedButtonHeight = height;
                savedUrl = webView.getUrl();
                isButtonRecorded = true;
                isRecordingMode = false;

                // Speichern in SharedPreferences
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putFloat(KEY_SAVED_X, x);
                editor.putFloat(KEY_SAVED_Y, y);
                editor.putFloat(KEY_SAVED_WIDTH, width);
                editor.putFloat(KEY_SAVED_HEIGHT, height);
                editor.putString(KEY_SAVED_URL, savedUrl);
                editor.putBoolean(KEY_IS_RECORDED, true);
                editor.apply();

                tvRecordStatus.setText("✅ Button gespeichert bei (" + (int)x + ", " + (int)y + ")");
                tvRecordStatus.setTextColor(Color.parseColor("#4CAF50"));
                tvStatus.setText("📝 Button-Position gespeichert!");
                Toast.makeText(MainActivity.this, "✅ Refill-Button Position gespeichert!", Toast.LENGTH_SHORT).show();

                btnRecordRefill.setText("📝 Refill-Recorder (gespeichert)");
            });
        }

        // ==========================================
        // BUTTON POSITION (für normalen Klick)
        // ==========================================

        @JavascriptInterface
        public void onButtonPosition(float x, float y, float width, float height) {
            runOnUiThread(() -> {
                // Wenn Recorder aktiv ist, speichern
                if (isRecordingMode) {
                    onRecordButtonPosition(x, y, width, height);
                    return;
                }
                
                // Normaler Klick
                performTouchClick(x, y, width, height);
            });
        }
    }

    // ==========================================
    // TOUCH-SIMULATION (mit übergebenen Koordinaten)
    // ==========================================

    private void performTouchClick(float x, float y, float width, float height) {
        if (x == 0 || y == 0) return;

        isTouchPending = true;

        int delay = random.nextInt(600) + 200;

        mainHandler.postDelayed(() -> {
            if (webView == null) return;

            float centerX = x + width / 2;
            float centerY = y + height / 2;

            float touchX = centerX + (random.nextFloat() - 0.5f) * width * 0.3f;
            float touchY = centerY + (random.nextFloat() - 0.5f) * height * 0.3f;

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
    // 📝 RECORDER - GESPEICHERTE POSITION VERWENDEN
    // ==========================================

    private void useRecordedPosition() {
        if (!isButtonRecorded) {
            tvStatus.setText("⚠️ Kein Button gespeichert! Bitte zuerst aufnehmen.");
            return;
        }

        tvStatus.setText("📌 Verwende gespeicherte Position (" + (int)savedButtonX + ", " + (int)savedButtonY + ")");
        tvStatus.setTextColor(Color.parseColor("#9C27B0"));
        
        // Klick an gespeicherter Position
        performTouchClick(savedButtonX, savedButtonY, savedButtonWidth, savedButtonHeight);
    }

    // ==========================================
    // 📝 RECORDER - STARTEN
    // ==========================================

    private void startRecording() {
        if (!isLoggedIn) {
            Toast.makeText(this, "⚠️ Bitte zuerst einloggen!", Toast.LENGTH_SHORT).show();
            return;
        }

        isRecordingMode = true;
        tvRecordStatus.setText("📝 Bitte manuell zum Refill-Button scrollen und klicken!");
        tvRecordStatus.setTextColor(Color.parseColor("#FF9800"));
        tvStatus.setText("📝 Aufnahme-Modus aktiv! Klicke auf den Refill-Button.");
        tvStatus.setTextColor(Color.parseColor("#FF9800"));
        Toast.makeText(this, "📝 Klicke jetzt auf den Refill-Button!", Toast.LENGTH_LONG).show();

        btnRecordRefill.setText("⏹️ Aufnahme beenden");
    }

    // ==========================================
    // MANUELLER REFILL TEST
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

        // Wenn gespeicherte Position vorhanden, verwende sie
        if (isButtonRecorded) {
            useRecordedPosition();
            return;
        }

        // Sonst normale Suche
        isManualRefill = true;
        tvStatus.setText("🧪 Suche Refill-Button...");
        tvStatus.setTextColor(Color.parseColor("#9C27B0"));

        if (webView != null) {
            findAndClickRefillButton();
        }
    }

    // ==========================================
    // 🔥 HAUPT-METHODE: REFILL BUTTON FINDEN
    // ==========================================

    private void findAndClickRefillButton() {
        if (webView == null) return;
        
        // Wenn gespeicherte Position vorhanden, sofort verwenden
        if (isButtonRecorded) {
            useRecordedPosition();
            return;
        }
        
        tvStatus.setText("🔍 Suche Refill-Button...");
        
        String js = "javascript:(function() {" +
                "try {" +
                "  var found = false;" +
                "  var elements = document.querySelectorAll('*');" +
                "  for(var i=0; i<elements.length; i++) {" +
                "    var el = elements[i];" +
                "    var text = el.innerText || el.textContent || '';" +
                "    var className = el.className || '';" +
                "    var id = el.id || '';" +
                "    if(text && text.includes('Refill aktivieren')) {" +
                "      el.scrollIntoView({behavior: 'smooth', block: 'center'});" +
                "      setTimeout(function(e) {" +
                "        var rect = e.getBoundingClientRect();" +
                "        Android.onButtonPosition(rect.left, rect.top, rect.width, rect.height);" +
                "      }, 600, el);" +
                "      found = true;" +
                "      break;" +
                "    } else if(className && className.toLowerCase().includes('refill')) {" +
                "      el.scrollIntoView({behavior: 'smooth', block: 'center'});" +
                "      setTimeout(function(e) {" +
                "        var rect = e.getBoundingClientRect();" +
                "        Android.onButtonPosition(rect.left, rect.top, rect.width, rect.height);" +
                "      }, 600, el);" +
                "      found = true;" +
                "      break;" +
                "    } else if(id && id.toLowerCase().includes('refill')) {" +
                "      el.scrollIntoView({behavior: 'smooth', block: 'center'});" +
                "      setTimeout(function(e) {" +
                "        var rect = e.getBoundingClientRect();" +
                "        Android.onButtonPosition(rect.left, rect.top, rect.width, rect.height);" +
                "      }, 600, el);" +
                "      found = true;" +
                "      break;" +
                "    }" +
                "  }" +
                "  if(!found) {" +
                "    Android.onRefillNotFound();" +
                "  }" +
                "} catch(e) {" +
                "  Android.onStatus('⚠️ Fehler: ' + e.message);" +
                "}" +
                "})();";
        
        webView.loadUrl(js);
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
    // GESPEICHERTE POSITION LADEN
    // ==========================================

    private void loadSavedPosition() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        isButtonRecorded = prefs.getBoolean(KEY_IS_RECORDED, false);
        
        if (isButtonRecorded) {
            savedButtonX = prefs.getFloat(KEY_SAVED_X, 0);
            savedButtonY = prefs.getFloat(KEY_SAVED_Y, 0);
            savedButtonWidth = prefs.getFloat(KEY_SAVED_WIDTH, 0);
            savedButtonHeight = prefs.getFloat(KEY_SAVED_HEIGHT, 0);
            savedUrl = prefs.getString(KEY_SAVED_URL, "");
            
            tvRecordStatus.setText("✅ Gespeichert bei (" + (int)savedButtonX + ", " + (int)savedButtonY + ")");
            tvRecordStatus.setTextColor(Color.parseColor("#4CAF50"));
            btnRecordRefill.setText("📝 Refill-Recorder (gespeichert)");
        } else {
            tvRecordStatus.setText("❌ Kein Button gespeichert");
            tvRecordStatus.setTextColor(Color.parseColor("#B0BEC5"));
            btnRecordRefill.setText("📝 Refill-Recorder");
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
        loadSavedPosition();
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
        btnRecordRefill = findViewById(R.id.btn_record_refill);
        tvStatus = findViewById(R.id.tv_status);
        tvVolume = findViewById(R.id.tv_volume);
        tvRefill = findViewById(R.id.tv_refill);
        tvRefillCount = findViewById(R.id.tv_refill_count);
        tvNextCheck = findViewById(R.id.tv_next_check);
        tvLoginHint = findViewById(R.id.tv_login_hint);
        tvRecordStatus = findViewById(R.id.tv_record_status);
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
                        findAndClickRefillButton();
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
                findAndClickRefillButton();
                updateNextCheckTime();
            }
        }, 3000);
    }

    // ==========================================
    // BUTTONS
    // ==========================================

    private void setupButtons() {
        // 📝 Refill-Recorder Button
        btnRecordRefill.setOnClickListener(v -> {
            if (isRecordingMode) {
                // Aufnahme beenden
                isRecordingMode = false;
                btnRecordRefill.setText("📝 Refill-Recorder");
                tvRecordStatus.setText("⏹️ Aufnahme beendet");
                tvRecordStatus.setTextColor(Color.parseColor("#FF9800"));
                tvStatus.setText("⏹️ Aufnahme beendet");
                return;
            }
            
            // Wenn bereits gespeichert, frage ob neu aufnehmen
            if (isButtonRecorded) {
                Toast.makeText(this, "🗑️ Gespeicherte Position löschen? Nochmal klicken zum Überschreiben.", Toast.LENGTH_LONG).show();
                // Bei zweitem Klick löschen
                btnRecordRefill.postDelayed(() -> {
                    if (btnRecordRefill.isPressed()) {
                        // Nochmal geklickt -> löschen
                        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        prefs.edit().clear().apply();
                        isButtonRecorded = false;
                        tvRecordStatus.setText("🗑️ Gespeicherte Position gelöscht");
                        tvRecordStatus.setTextColor(Color.parseColor("#F44336"));
                        btnRecordRefill.setText("📝 Refill-Recorder");
                        Toast.makeText(this, "🗑️ Gespeicherte Position gelöscht!", Toast.LENGTH_SHORT).show();
                    }
                }, 500);
                return;
            }
            
            startRecording();
        });

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
                        findAndClickRefillButton();
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
