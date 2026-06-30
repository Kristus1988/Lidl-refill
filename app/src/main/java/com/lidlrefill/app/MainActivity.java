package com.lidlrefill.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private static final int OVERLAY_PERMISSION_REQUEST = 1;
    private static final int ACCESSIBILITY_PERMISSION_REQUEST = 2;
    
    private TextView tvPermissionStatus, tvVolumeStatus;
    private Button btnStartService, btnRestartApp, btnRefreshAccessibility, btnCheckVolume;
    private WebView webView;
    private ProgressBar progressBar;
    private SharedPreferences prefs;
    private Handler handler = new Handler(Looper.getMainLooper());
    
    private String currentVolume = "0.00 GB";
    private String currentRefill = "1 GB / 1 GB";
    private boolean isLoggedIn = false;
    private boolean isVolumeLoaded = false;
    private int parseAttempts = 0;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus);
        tvVolumeStatus = findViewById(R.id.tvVolumeStatus);
        btnStartService = findViewById(R.id.btnStartService);
        btnRestartApp = findViewById(R.id.btnRestartApp);
        btnRefreshAccessibility = findViewById(R.id.btnRefreshAccessibility);
        btnCheckVolume = findViewById(R.id.btnCheckVolume);
        progressBar = findViewById(R.id.progressBar);
        webView = findViewById(R.id.webView);
        
        Button btnRequestPermissions = findViewById(R.id.btnRequestPermissions);
        Button btnCheckPermissions = findViewById(R.id.btnCheckPermissions);
        
        btnRequestPermissions.setOnClickListener(v -> requestAllPermissions());
        btnCheckPermissions.setOnClickListener(v -> checkAllPermissions());
        
        btnStartService.setOnClickListener(v -> {
            if (checkAllPermissions()) {
                startOverlayService();
            } else {
                Toast.makeText(this, "❌ Bitte alle Berechtigungen erteilen", Toast.LENGTH_LONG).show();
            }
        });
        
        btnRestartApp.setOnClickListener(v -> {
            Toast.makeText(this, "🔄 App wird neu gestartet...", Toast.LENGTH_SHORT).show();
            Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
            finish();
            android.os.Process.killProcess(android.os.Process.myPid());
        });
        
        btnRefreshAccessibility.setOnClickListener(v -> {
            Toast.makeText(this, "🔧 Accessibility wird aktualisiert...", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST);
        });
        
        btnCheckVolume.setOnClickListener(v -> {
            Toast.makeText(this, "📊 Prüfe Volumen...", Toast.LENGTH_SHORT).show();
            checkVolumeFromWeb();
        });
        
        setupWebView();
        updatePermissionStatus();
        updateVolumeStatus();
        
        handler.postDelayed(() -> {
            checkVolumeFromWeb();
        }, 1500);
    }
    
    private void setupWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        
        // ============ ALS DESKTOP ANMELDEN ============
        webView.getSettings().setUserAgentString(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
        );
        
        webView.getSettings().setSupportZoom(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        
        webView.setVisibility(android.view.View.VISIBLE);
        
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }
        
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(android.view.View.GONE);
                
                view.evaluateJavascript(
                    "document.documentElement.outerHTML",
                    value -> {
                        if (value != null && !value.equals("null")) {
                            parseHtml(value);
                        }
                    }
                );
                
                if (url.contains("login") || url.contains("anmelden")) {
                    tvVolumeStatus.setText("🔑 Bitte in der Webseite anmelden...");
                    Toast.makeText(MainActivity.this, 
                        "🔑 Bitte logge dich in der Webseite ein!", 
                        Toast.LENGTH_LONG).show();
                }
            }
            
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(android.view.View.VISIBLE);
            }
        });
        
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("https://kundenkonto.lidl-connect.de/mein-lidl-connect/uebersicht.html");
    }
    
    private void checkVolumeFromWeb() {
        progressBar.setVisibility(android.view.View.VISIBLE);
        tvVolumeStatus.setText("📊 Lade Lidl-Seite...");
        webView.setVisibility(android.view.View.VISIBLE);
        parseAttempts = 0;
        
        webView.loadUrl("https://kundenkonto.lidl-connect.de/mein-lidl-connect/uebersicht.html");
        
        handler.postDelayed(() -> {
            if (!isVolumeLoaded) {
                webView.evaluateJavascript(
                    "document.documentElement.outerHTML",
                    value -> {
                        if (value != null && !value.equals("null")) {
                            parseHtml(value);
                        }
                    }
                );
            }
        }, 3000);
        
        handler.postDelayed(() -> {
            progressBar.setVisibility(android.view.View.GONE);
            if (!isVolumeLoaded) {
                double simulatedValue = 0.3 + Math.random() * 0.7;
                currentVolume = String.format("%.2f GB", simulatedValue);
                currentRefill = "1 GB / 1 GB";
                isLoggedIn = false;
                updateVolumeStatus();
                Toast.makeText(this, "📊 Volumen: " + currentVolume + " (Simulation)", Toast.LENGTH_SHORT).show();
            }
        }, 8000);
    }
    
    // ============ PARSING ============
    private void parseHtml(String html) {
        if (html == null || html.isEmpty()) return;
        
        parseAttempts++;
        
        try {
            String[] patterns = {
                "Unlimited\\s*Refill.*?(0[\\.\\,]\\d+)\\s*GB",
                "Unlimited\\s*Refill[\\s\\S]*?(0[\\.\\,]\\d+)\\s*GB",
                "Unlimited\\s*Refill\\s*(0[\\.\\,]\\d+)\\s*GB",
                "Refill.*?(0[\\.\\,]\\d+)\\s*GB",
                "(0[\\.\\,]\\d+)\\s*GB"
            };
            
            for (String patternStr : patterns) {
                Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                Matcher matcher = pattern.matcher(html);
                
                if (matcher.find()) {
                    String used = matcher.group(1).replace(",", ".");
                    double value = Double.parseDouble(used);
                    
                    if (value > 0 && value <= 1.0) {
                        currentVolume = used + " GB";
                        currentRefill = used + " GB / 1 GB";
                        isVolumeLoaded = true;
                        isLoggedIn = true;
                        
                        updateVolumeStatus();
                        prefs.edit().putString("current_volume", currentVolume).apply();
                        prefs.edit().putString("current_refill", currentRefill).apply();
                        prefs.edit().putBoolean("volume_loaded", true).apply();
                        
                        Toast.makeText(this, "📊 Volumen: " + currentVolume + " ✅", Toast.LENGTH_SHORT).show();
                        
                        handler.postDelayed(() -> {
                            webView.setVisibility(android.view.View.GONE);
                        }, 2000);
                        return;
                    }
                }
            }
            
            if (parseAttempts >= 3 && !isVolumeLoaded) {
                if (html.contains("login") || html.contains("anmelden")) {
                    tvVolumeStatus.setText("🔑 Bitte anmelden...");
                    isLoggedIn = false;
                }
            }
            
        } catch (Exception e) {
            // Parsen fehlgeschlagen
        }
    }
    
    private void updatePermissionStatus() {
        StringBuilder status = new StringBuilder();
        status.append("📋 Berechtigungen:\n");
        
        boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        status.append(overlayOk ? "✅" : "❌").append(" Overlay (Fenster einblenden)\n");
        
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        boolean accOk = am != null && am.isEnabled();
        status.append(accOk ? "✅" : "❌").append(" Accessibility (Sonderfunktionen)\n");
        
        status.append("\n📊 WebView-Modus:\n");
        status.append(isLoggedIn ? "✅ Eingeloggt" : "❌ Bitte anmelden!");
        status.append(isVolumeLoaded ? "\n✅ Volumen geladen: " + currentVolume : "\n⏳ Volumen wird geladen...");
        
        tvPermissionStatus.setText(status.toString());
    }
    
    private void updateVolumeStatus() {
        StringBuilder status = new StringBuilder();
        status.append("📊 Volumen:\n");
        status.append("📱 Unlimited Refill: ").append(currentRefill).append("\n");
        status.append("💾 Verfügbar: ").append(currentVolume);
        if (isLoggedIn) {
            status.append("\n✅ Eingeloggt!");
        }
        tvVolumeStatus.setText(status.toString());
    }
    
    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
            }
        }
        
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST);
        }
    }
    
    private boolean checkAllPermissions() {
        boolean allOk = true;
        StringBuilder missing = new StringBuilder();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            missing.append("❌ Overlay fehlt\n");
            allOk = false;
        }
        
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null || !am.isEnabled()) {
            missing.append("❌ Accessibility fehlt\n");
            allOk = false;
        }
        
        if (!allOk) {
            Toast.makeText(this, missing.toString(), Toast.LENGTH_LONG).show();
            btnStartService.setEnabled(false);
        } else {
            Toast.makeText(this, "✅ Alle Berechtigungen erteilt!", Toast.LENGTH_SHORT).show();
            btnStartService.setEnabled(true);
        }
        
        updatePermissionStatus();
        return allOk;
    }
    
    private void startOverlayService() {
        if (isVolumeLoaded) {
            prefs.edit().putString("current_volume", currentVolume).apply();
            prefs.edit().putBoolean("volume_loaded", true).apply();
        }
        
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "🚀 Overlay gestartet!", Toast.LENGTH_LONG).show();
        finish();
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        updatePermissionStatus();
        checkAllPermissions();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }
}
