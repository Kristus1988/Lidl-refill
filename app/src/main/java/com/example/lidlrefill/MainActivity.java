package com.example.lidlrefill;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private EditText etUsername, etPassword;
    private Button btnStart, btnStop;
    private TextView tvStatus, tvVolume, tvRefill, tvRefillCount;
    private ProgressBar progressStatus;
    private LinearLayout layoutStatus;

    private SharedPreferences sharedPreferences;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private int refillCount = 0;
    private boolean isLoggedIn = false;
    private Random random = new Random();
    private int checkCount = 0;

    // Aktuelle Volumen-Werte (werden von der WebView aktualisiert)
    private float currentRefillGb = 0.99f;
    private float currentInklusivGb = 25.0f;

    private static final String LIDL_URL = "https://kundenkonto.lidl-connect.de/mein-lidl-connect.html";

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
        public void onLoginSuccess() {
            runOnUiThread(() -> {
                isLoggedIn = true;
                tvStatus.setText("✅ Angemeldet!");
                tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                startMonitoring();
            });
        }

        @JavascriptInterface
        public void onLoginFailed() {
            runOnUiThread(() -> {
                isLoggedIn = false;
                tvStatus.setText("❌ Login fehlgeschlagen!");
                tvStatus.setTextColor(Color.parseColor("#F44336"));
                btnStart.setEnabled(true);
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
                startMonitoring();
            });
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initEncryptedStorage();
        loadSavedData();
        setupWebView();
        setupButtons();
    }

    private void initViews() {
        webView = findViewById(R.id.webview_lidl);
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        tvStatus = findViewById(R.id.tv_status);
        tvVolume = findViewById(R.id.tv_volume);
        tvRefill = findViewById(R.id.tv_refill);
        tvRefillCount = findViewById(R.id.tv_refill_count);
        progressStatus = findViewById(R.id.progress_status);
        layoutStatus = findViewById(R.id.layout_status);
    }

    private void initEncryptedStorage() {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            sharedPreferences = EncryptedSharedPreferences.create(
                    "secure_prefs",
                    masterKeyAlias,
                    this,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            sharedPreferences = getSharedPreferences("prefs", Context.MODE_PRIVATE);
        }
    }

    private void loadSavedData() {
        etUsername.setText(sharedPreferences.getString("username", ""));
        etPassword.setText(sharedPreferences.getString("password", ""));
    }

    private void saveData() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("username", etUsername.getText().toString());
        editor.putString("password", etPassword.getText().toString());
        editor.apply();
    }

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
                if (url.contains("lidl-connect.de") && isRunning) {
                    if (!isLoggedIn) {
                        checkLoginStatus();
                    } else {
                        int delay = random.nextInt(2000) + 500;
                        mainHandler.postDelayed(() -> {
                            checkAndClickRefill();
                        }, delay);
                    }
                }
            }
        });

        webView.addJavascriptInterface(new LidlJSInterface(), "Android");
    }

    private void checkLoginStatus() {
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        String js = "javascript:(function() {" +
                "try {" +
                "  var loggedIn = document.body.innerText.includes('Eingeloggt als:') || document.body.innerText.includes('Mein Guthaben');" +
                "  if (loggedIn) {" +
                "    Android.onAlreadyLoggedIn();" +
                "  } else {" +
                "    var userField = document.getElementById('username');" +
                "    var passField = document.getElementById('password');" +
                "    if (userField && passField) {" +
                "      userField.value = '" + username + "';" +
                "      passField.value = '" + password + "';" +
                "      Android.onStatus('🔐 Login wird ausgeführt...');" +
                "      var btns = document.querySelectorAll('button, input[type=\"submit\"]');" +
                "      for(var i=0; i<btns.length; i++) {" +
                "        if(btns[i].innerText && (btns[i].innerText.includes('Anmelden') || btns[i].innerText.includes('Login'))) {" +
                "          btns[i].click();" +
                "          break;" +
                "        }" +
                "      }" +
                "      setTimeout(function() {" +
                "        if (document.body.innerText.includes('Eingeloggt als:') || document.body.innerText.includes('Mein Guthaben')) {" +
                "          Android.onLoginSuccess();" +
                "        } else {" +
                "          Android.onLoginFailed();" +
                "        }" +
                "      }, 5000);" +
                "    } else {" +
                "      Android.onLoginFailed();" +
                "    }" +
                "  }" +
                "} catch(e) {" +
                "  Android.onStatus('⚠️ Fehler: ' + e.message);" +
                "}" +
                "})();";
        webView.loadUrl(js);
    }

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
                    "      Android.onStatus('🎯 Refill-Volumen: ' + refillValue + ' GB → Warte ' + (delay/1000) + 's...');" +
                    "      setTimeout(function() {" +
                    "        var found = false;" +
                    "        var elements = document.querySelectorAll('button, div, a, span');" +
                    "        for(var i=0; i<elements.length; i++) {" +
                    "          var text = elements[i].innerText || elements[i].textContent || '';" +
                    "          if(text.includes('Refill aktivieren')) {" +
                    "            elements[i].scrollIntoView({behavior: 'smooth', block: 'center'});" +
                    "            setTimeout(function(el) {" +
                    "              el.click();" +
                    "              Android.onRefillClicked();" +
                    "            }, 500, elements[i]);" +
                    "            found = true;" +
                    "            break;" +
                    "          }" +
                    "        }" +
                    "        if (!found) {" +
                    "          Android.onStatus('⚠️ Refill-Button nicht gefunden!');" +
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

    private void startMonitoring() {
        tvStatus.setText("🔍 Überwache Volumen...");
        tvStatus.setTextColor(Color.parseColor("#4FC3F7"));
        checkRefillDelayed();
    }

    // 🧠 NEUE LOGIK: Berechnet adaptive Wartezeiten basierend auf dem aktuellen Volumen
    private int calculateAdaptiveDelay() {
        float refill = currentRefillGb;

        // Basis-Intervall in Sekunden
        int baseDelay;

        if (refill > 0.80) {
            // 🟢 Volle Ladung – sehr selten prüfen (wie ein Mensch, der nicht ständig nachschaut)
            baseDelay = random.nextInt(60) + 60; // 60-120 Sekunden
        } else if (refill > 0.40) {
            // 🟡 Normal – gelegentlich prüfen
            baseDelay = random.nextInt(30) + 30; // 30-60 Sekunden
        } else if (refill > 0.15) {
            // 🟠 Wird knapp – öfter prüfen
            baseDelay = random.nextInt(15) + 10; // 10-25 Sekunden
        } else {
            // 🔴 Kritisch! – Sehr oft prüfen
            baseDelay = random.nextInt(5) + 3; // 3-8 Sekunden
        }

        // 🧠 Menschliche Variation: 20% Chance auf eine "Ablenkung" (längere Pause)
        if (random.nextInt(5) == 0) {
            baseDelay += random.nextInt(30) + 20; // +20-50 Sekunden extra
        }

        return baseDelay;
    }

    private void checkRefillDelayed() {
        if (!isRunning || !isLoggedIn) return;

        // 🔥 Adaptive Wartezeit basierend auf aktuellen Volumen
        int delay = calculateAdaptiveDelay() * 1000; // in Millisekunden

        // Status anzeigen
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
        tvStatus.setText(phaseText + " | ⏱️ " + (delay/1000) + "s");

        mainHandler.postDelayed(() -> {
            if (isRunning && isLoggedIn) {
                webView.reload();
                checkRefillDelayed();
            }
        }, delay);
    }

    private void setupButtons() {
        btnStart.setOnClickListener(v -> {
            String username = etUsername.getText().toString().trim();
            String password = etPassword.getText().toString().trim();

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Bitte Benutzername und Passwort eingeben!", Toast.LENGTH_SHORT).show();
                return;
            }

            saveData();
            isRunning = true;
            isLoggedIn = false;
            refillCount = 0;
            checkCount = 0;
            currentRefillGb = 0.99f;
            tvRefillCount.setText("🔄 Refills: 0");
            tvStatus.setText("🔄 Starte...");
            tvStatus.setTextColor(Color.parseColor("#FFC107"));
            btnStart.setEnabled(false);

            webView.setVisibility(View.VISIBLE);
            layoutStatus.setVisibility(View.VISIBLE);
            webView.loadUrl(LIDL_URL);
        });

        btnStop.setOnClickListener(v -> {
            isRunning = false;
            isLoggedIn = false;
            mainHandler.removeCallbacksAndMessages(null);
            webView.setVisibility(View.GONE);
            tvStatus.setText("⏹️ Gestoppt");
            tvStatus.setTextColor(Color.parseColor("#B0BEC5"));
            btnStart.setEnabled(true);
            progressStatus.setVisibility(View.GONE);
        });
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
