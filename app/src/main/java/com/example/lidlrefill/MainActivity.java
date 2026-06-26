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

    // JavaScript-Interface für Kommunikation
    private class LidlJSInterface {
        @JavascriptInterface
        public void onVolumeUpdate(String inklusiv, String refill) {
            runOnUiThread(() -> {
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
            });
        }

        @JavascriptInterface
        public void onStatus(String status) {
            runOnUiThread(() -> tvStatus.setText(status));
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.getSettings().setSafeBrowsingEnabled(false);
        }

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Nach Seitenladung: Login prüfen oder ausführen
                if (url.contains("konto.lidl-connect.de")) {
                    if (!isLoggedIn && isRunning) {
                        performLogin();
                    } else if (isLoggedIn && isRunning) {
                        checkVolume();
                    }
                }
            }
        });

        // JavaScript-Interface registrieren
        webView.addJavascriptInterface(new LidlJSInterface(), "Android");
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
            tvRefillCount.setText("🔄 Refills: 0");
            tvStatus.setText("🔄 Starte...");
            tvStatus.setTextColor(Color.parseColor("#FFC107"));
            btnStart.setEnabled(false);

            // Lidl Connect Seite laden
            webView.setVisibility(View.VISIBLE);
            layoutStatus.setVisibility(View.VISIBLE);
            webView.loadUrl("https://konto.lidl-connect.de");
        });

        btnStop.setOnClickListener(v -> {
            isRunning = false;
            isLoggedIn = false;
            webView.setVisibility(View.GONE);
            tvStatus.setText("⏹️ Gestoppt");
            tvStatus.setTextColor(Color.parseColor("#B0BEC5"));
            btnStart.setEnabled(true);
            progressStatus.setVisibility(View.GONE);
        });
    }

    private void performLogin() {
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        // JavaScript-Code für Login
        String js = "javascript:(function() {" +
                "try {" +
                "  var userField = document.getElementById('username');" +
                "  var passField = document.getElementById('password');" +
                "  var loginBtn = document.getElementById('login-btn');" +
                "  if (userField && passField && loginBtn) {" +
                "    userField.value = '" + username + "';" +
                "    passField.value = '" + password + "';" +
                "    loginBtn.click();" +
                "    Android.onStatus('🔐 Login wird ausgeführt...');" +
                "  } else {" +
                "    Android.onLoginFailed();" +
                "  }" +
                "} catch(e) {" +
                "  Android.onLoginFailed();" +
                "}" +
                "})();";
        webView.loadUrl(js);
    }

    private void checkVolume() {
        if (!isRunning) return;

        // JavaScript-Code für Volumen-Erkennung
        String js = "javascript:(function() {" +
                "try {" +
                "  var pageText = document.body.innerText;" +
                "  var inklusivMatch = pageText.match(/(\\d+[\\,\\d]*)\\s*GB\\s*\\/\\s*25\\s*GB/);" +
                "  var refillMatch = pageText.match(/Unlimited Refill\\s*(\\d+[\\,\\d]*)\\s*GB/);" +
                "  var inklusiv = inklusivMatch ? inklusivMatch[1].replace(',', '.') : '--';" +
                "  var refill = refillMatch ? refillMatch[1].replace(',', '.') : '--';" +
                "  Android.onVolumeUpdate(inklusiv, refill);" +
                "  if (refillMatch && parseFloat(refill) <= 0.15) {" +
                "    Android.onRefillClicked();" +
                "    var btn = document.querySelector('button:contains(\"Refill aktivieren\")');" +
                "    if (btn) btn.click();" +
                "    var divs = document.querySelectorAll('div, button, a');" +
                "    for(var i=0; i<divs.length; i++) {" +
                "      if(divs[i].innerText && divs[i].innerText.includes('Refill aktivieren')) {" +
                "        divs[i].click();" +
                "        break;" +
                "      }" +
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
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isRunning && isLoggedIn) {
                    webView.reload();
                    int nextCheck = random.nextInt(30000) + 30000; // 30-60 Sekunden
                    mainHandler.postDelayed(this, nextCheck);
                }
            }
        }, 5000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // WebView im Hintergrund pausieren
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
}
