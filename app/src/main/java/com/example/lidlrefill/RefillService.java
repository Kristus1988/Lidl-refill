package com.example.lidlrefill;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.bonigarcia.wdm.WebDriverManager;

public class RefillService {
    
    private Context context;
    private WebDriver driver;
    private HumanBehavior human;
    private ExecutorService executor;
    private Handler mainHandler;
    private boolean isRunning = false;
    private int refillCount = 0;
    private int consecutiveErrors = 0;
    private int consecutiveRefills = 0;
    private float lastKnownGb = 0.99f;
    private float previousGb = 0.99f;
    private float lastKnownInklusivGb = 25.0f;
    private int checkCount = 0;
    private long lastCheckTime = 0;
    private StatusListener listener;
    private final Random random = new Random();
    
    // ==========================================
    // AUTOMATISCHE TARIF-ERKENNUNG
    // ==========================================
    
    public enum TarifType {
        S(25, 20.0f, 10.0f, 2.0f, 0.5f, 2880, 1440, 360, 120),
        M(40, 32.0f, 16.0f, 4.0f, 1.0f, 4320, 2160, 480, 120),
        L(60, 48.0f, 24.0f, 6.0f, 1.0f, 5760, 2880, 600, 120),
        XL(80, 64.0f, 32.0f, 8.0f, 1.0f, 7200, 3600, 720, 120);
        
        public final int maxInklusivGb;
        public final float thresholdHigh;
        public final float thresholdMedium;
        public final float thresholdLow;
        public final float thresholdCritical;
        public final int intervalHigh;
        public final int intervalMedium;
        public final int intervalLow;
        public final int intervalCritical;
        
        TarifType(int maxInklusivGb, float thresholdHigh, float thresholdMedium,
                  float thresholdLow, float thresholdCritical,
                  int intervalHigh, int intervalMedium, int intervalLow, int intervalCritical) {
            this.maxInklusivGb = maxInklusivGb;
            this.thresholdHigh = thresholdHigh;
            this.thresholdMedium = thresholdMedium;
            this.thresholdLow = thresholdLow;
            this.thresholdCritical = thresholdCritical;
            this.intervalHigh = intervalHigh;
            this.intervalMedium = intervalMedium;
            this.intervalLow = intervalLow;
            this.intervalCritical = intervalCritical;
        }
        
        public static TarifType fromMaxGb(int maxGb) {
            for (TarifType type : TarifType.values()) {
                if (type.maxInklusivGb == maxGb) {
                    return type;
                }
            }
            return S;
        }
    }
    
    private TarifType currentTarif = TarifType.S;
    
    // ==========================================
    // OPTIMIERTE KONFIGURATION (Refill-Volumen)
    // ==========================================
    private static final int MAX_CONSECUTIVE_REFILLS = 10;
    private static final int MAX_CONSECUTIVE_ERRORS = 10;
    private static final float MIN_GB_THRESHOLD = 0.05f;
    private static final float MAX_GB_THRESHOLD = 2.0f;
    private static final float REFILL_TARGET = 0.15f;
    
    private static final float REFILL_PHASE_HIGH = 0.80f;
    private static final float REFILL_PHASE_MEDIUM = 0.50f;
    private static final float REFILL_PHASE_LOW = 0.25f;
    private static final float REFILL_PHASE_CRITICAL = 0.15f;
    
    private static final int REFILL_INTERVAL_HIGH = 8;
    private static final int REFILL_INTERVAL_MEDIUM = 4;
    private static final int REFILL_INTERVAL_LOW = 2;
    private static final int REFILL_INTERVAL_CRITICAL = 1;
    
    public enum LoginStatus {
        NOT_LOGGED_IN, LOGGING_IN, LOGGED_IN, 
        REFILL_ACTIVATED, REFILL_FAILED,
        INKLUSIV_AVAILABLE, INKLUSIV_DEPLETED,
        ERROR, UNKNOWN
    }
    
    private LoginStatus currentLoginStatus = LoginStatus.UNKNOWN;
    
    private static final String LIDL_URL = "https://konto.lidl-connect.de";
    
    public interface StatusListener {
        void onStatusUpdate(String status);
        void onGbUpdate(String gb);
        void onRefillCountUpdate(int count);
        void onLoginNumber(String number);
        void onLoginStatusUpdate(LoginStatus status, String details);
        void onPhaseUpdate(String phase, String details);
        void onInklusivUpdate(String inklusiv, String refill);
        void onTarifDetected(String tarifName, int maxGb);
        void onChromeDriverProgress(int progress); // NEU: Fortschritt für ChromeDriver
    }
    
    public RefillService(Context context) {
        this.context = context;
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    public void setStatusListener(StatusListener listener) {
        this.listener = listener;
    }
    
    // ==========================================
    // ADAPTIVES INTERVALL (mit automatischem Tarif)
    // ==========================================
    
    private int getAdaptiveInterval(float currentRefillGb, float currentInklusivGb, float targetGb) {
        int baseInterval;
        String phaseName;
        
        // 1. INKLUSIV-VOLUMEN VORHANDEN?
        if (currentInklusivGb > currentTarif.thresholdHigh) {
            baseInterval = currentTarif.intervalHigh;
            phaseName = "🟢 Inklusiv: " + String.format("%.1f", currentInklusivGb) + " GB (fast voll)";
            String timeText = formatInterval(baseInterval);
            updateStatus("📊 " + phaseName + " - Nächste Prüfung in ~" + timeText);
            updatePhase(phaseName, timeText);
            return baseInterval * 60;
        }
        
        if (currentInklusivGb > currentTarif.thresholdMedium) {
            baseInterval = currentTarif.intervalMedium;
            phaseName = "🟢 Inklusiv: " + String.format("%.1f", currentInklusivGb) + " GB (mittel)";
            String timeText = formatInterval(baseInterval);
            updateStatus("📊 " + phaseName + " - Nächste Prüfung in ~" + timeText);
            updatePhase(phaseName, timeText);
            return baseInterval * 60;
        }
        
        if (currentInklusivGb > currentTarif.thresholdLow) {
            baseInterval = currentTarif.intervalLow;
            phaseName = "🟡 Inklusiv: " + String.format("%.1f", currentInklusivGb) + " GB (niedrig)";
            String timeText = formatInterval(baseInterval);
            updateStatus("📊 " + phaseName + " - Nächste Prüfung in ~" + timeText);
            updatePhase(phaseName, timeText);
            return baseInterval * 60;
        }
        
        if (currentInklusivGb > currentTarif.thresholdCritical) {
            baseInterval = currentTarif.intervalCritical;
            phaseName = "🟠 Inklusiv: " + String.format("%.1f", currentInklusivGb) + " GB (kritisch!)";
            String timeText = formatInterval(baseInterval);
            updateStatus("📊 " + phaseName + " - Nächste Prüfung in ~" + timeText);
            updatePhase(phaseName, timeText);
            return baseInterval * 60;
        }
        
        // 2. INKLUSIV AUFGEBRAUCHT → REFILL PRÜFEN
        if (currentRefillGb > REFILL_PHASE_HIGH) {
            baseInterval = REFILL_INTERVAL_HIGH;
            phaseName = "🟡 Refill: Voll (" + String.format("%.2f", currentRefillGb) + " GB)";
        } else if (currentRefillGb > REFILL_PHASE_MEDIUM) {
            baseInterval = REFILL_INTERVAL_MEDIUM;
            phaseName = "🟡 Refill: Mittel (" + String.format("%.2f", currentRefillGb) + " GB)";
        } else if (currentRefillGb > REFILL_PHASE_LOW) {
            baseInterval = REFILL_INTERVAL_LOW;
            phaseName = "🟠 Refill: Niedrig (" + String.format("%.2f", currentRefillGb) + " GB)";
        } else {
            baseInterval = REFILL_INTERVAL_CRITICAL;
            phaseName = "🔴 Refill: Kritisch (" + String.format("%.2f", currentRefillGb) + " GB)";
        }
        
        double variation = 0.7 + (random.nextDouble() * 0.6);
        int seconds = (int) (baseInterval * 60 * variation);
        seconds += random.nextInt(60) - 30;
        seconds = Math.max(30, Math.min(seconds, 1800));
        
        if (currentRefillGb <= REFILL_PHASE_CRITICAL) {
            seconds = Math.min(seconds, 120);
        }
        
        updatePhase(phaseName, seconds + " Sekunden");
        return seconds;
    }
    
    private String formatInterval(int minutes) {
        if (minutes >= 1440) {
            int days = minutes / 1440;
            return days + " Tag" + (days > 1 ? "e" : "");
        } else if (minutes >= 60) {
            int hours = minutes / 60;
            return hours + " Stunde" + (hours > 1 ? "n" : "");
        } else {
            return minutes + " Minute" + (minutes > 1 ? "n" : "");
        }
    }
    
    // ==========================================
    // VOLUMEN-DATEN AUSLESEN (mit Tarif-Erkennung)
    // ==========================================
    
    private VolumeData getVolumeData() {
        try {
            String pageSource = driver.getPageSource();
            
            if (!pageSource.contains("Eingeloggt als:")) {
                return new VolumeData(0.99f, 25.0f);
            }
            
            float refillGb = 0.99f;
            float inklusivGb = 25.0f;
            
            // Refill-Volumen
            Pattern refillPattern = Pattern.compile(
                "Unlimited Refill\\s*([\\d,]+)\\s*GB",
                Pattern.CASE_INSENSITIVE
            );
            Matcher refillMatcher = refillPattern.matcher(pageSource);
            if (refillMatcher.find()) {
                String gbText = refillMatcher.group(1).replace(',', '.');
                refillGb = Float.parseFloat(gbText);
            } else {
                Pattern fallbackPattern = Pattern.compile(
                    "Refill[^\\d]*([\\d,]+)\\s*GB",
                    Pattern.CASE_INSENSITIVE
                );
                Matcher fallbackMatcher = fallbackPattern.matcher(pageSource);
                if (fallbackMatcher.find()) {
                    String gbText = fallbackMatcher.group(1).replace(',', '.');
                    refillGb = Float.parseFloat(gbText);
                }
            }
            
            // Inklusiv-Volumen
            Pattern inklusivPattern = Pattern.compile(
                "([\\d,]+)\\s*GB\\s*/\\s*(\\d+)\\s*GB",
                Pattern.CASE_INSENSITIVE
            );
            Matcher inklusivMatcher = inklusivPattern.matcher(pageSource);
            if (inklusivMatcher.find()) {
                String gbText = inklusivMatcher.group(1).replace(',', '.');
                inklusivGb = Float.parseFloat(gbText);
                
                if (currentTarif == TarifType.S && checkCount <= 1) {
                    int maxGb = Integer.parseInt(inklusivMatcher.group(2));
                    currentTarif = TarifType.fromMaxGb(maxGb);
                    updateStatus("📊 Tarif erkannt: Unlimited on Demand " + currentTarif.name() + " (" + maxGb + " GB)");
                    updateTarifDetected(currentTarif.name(), maxGb);
                }
            } else {
                Pattern fallbackPattern2 = Pattern.compile(
                    "Verfügbares Gesamtvolumen\\s*([\\d,]+)\\s*GB",
                    Pattern.CASE_INSENSITIVE
                );
                Matcher fallbackMatcher2 = fallbackPattern2.matcher(pageSource);
                if (fallbackMatcher2.find()) {
                    String gbText = fallbackMatcher2.group(1).replace(',', '.');
                    inklusivGb = Float.parseFloat(gbText);
                }
            }
            
            if (inklusivGb < 0) inklusivGb = 0;
            if (inklusivGb > currentTarif.maxInklusivGb) inklusivGb = currentTarif.maxInklusivGb;
            if (refillGb < 0.01) refillGb = 0.99f;
            if (refillGb > 2.0) refillGb = 0.99f;
            
            return new VolumeData(refillGb, inklusivGb);
            
        } catch (Exception e) {
            return new VolumeData(0.99f, 25.0f);
        }
    }
    
    private static class VolumeData {
        float refillGb;
        float inklusivGb;
        
        VolumeData(float refillGb, float inklusivGb) {
            this.refillGb = refillGb;
            this.inklusivGb = inklusivGb;
        }
    }
    
    // ==========================================
    // MAIN LOGIC
    // ==========================================
    
    public void start(String username, String password, int interval, float target, int waitAfter) {
        if (isRunning) return;
        isRunning = true;
        refillCount = 0;
        consecutiveErrors = 0;
        consecutiveRefills = 0;
        lastKnownGb = 0.99f;
        previousGb = 0.99f;
        lastKnownInklusivGb = 25.0f;
        checkCount = 0;
        lastCheckTime = System.currentTimeMillis();
        currentTarif = TarifType.S;
        
        executor.execute(() -> {
            try {
                // 🔥 NEU: ChromeDriver Download mit Fortschritt
                updateStatus("⬇️ Prüfe ChromeDriver...");
                setupDriver();
                human = new HumanBehavior(driver);
                
                updateLoginStatus(LoginStatus.LOGGING_IN, "🔄 Verbinde zu Lidl Connect...");
                
                if (login(username, password)) {
                    monitorLoop(interval, target, waitAfter);
                } else {
                    updateLoginStatus(LoginStatus.NOT_LOGGED_IN, "❌ Login fehlgeschlagen!");
                    updateStatus("❌ Login fehlgeschlagen!");
                    stop();
                }
            } catch (Exception e) {
                e.printStackTrace();
                updateLoginStatus(LoginStatus.ERROR, "❌ Fehler: " + e.getMessage());
                updateStatus("❌ Fehler: " + e.getMessage());
                stop();
            }
        });
    }
    
    private void setupDriver() {
        ChromeOptions options = new ChromeOptions();
        
        // options.addArguments("--headless");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--disable-features=ChromeWhatsNewUI");
        options.addArguments("--disable-infobars");
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-plugins");
        
        int width = random.nextInt(400) + 1200;
        int height = random.nextInt(200) + 800;
        options.addArguments("--window-size=" + width + "," + height);
        
        String[] userAgents = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
        };
        options.addArguments("--user-agent=" + userAgents[random.nextInt(userAgents.length)]);
        
        String[] languages = {"de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7", "de;q=0.9,en;q=0.8"};
        options.addArguments("--lang=" + languages[random.nextInt(languages.length)]);
        
        // 🔥 NEU: WebDriverManager mit Fortschrittsanzeige
        updateStatus("⬇️ Lade ChromeDriver herunter... (0%)");
        
        WebDriverManager.chromedriver().setup();
        
        updateStatus("✅ ChromeDriver bereit!");
        
        driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(
            Duration.ofSeconds(random.nextInt(7) + 8)
        );
        
        if (random.nextBoolean()) {
            driver.manage().deleteAllCookies();
        }
    }
    
    private boolean login(String username, String password) {
        updateStatus("🔐 Anmeldung mit: " + username);
        
        driver.get(LIDL_URL);
        human.waitForPageLoad();
        human.humanReadingPause();
        
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
            
            LoginStatus initialStatus = checkLoginStatus();
            if (initialStatus == LoginStatus.LOGGED_IN) {
                updateLoginStatus(LoginStatus.LOGGED_IN, "✅ Bereits eingeloggt!");
                updateStatus("✅ Bereits eingeloggt!");
                return true;
            }
            
            WebElement usernameField = wait.until(
                ExpectedConditions.presenceOfElementLocated(By.id("username"))
            );
            
            updateLoginStatus(LoginStatus.LOGGING_IN, "📝 Gebe Benutzername ein...");
            human.humanThinkingPause();
            human.humanType(usernameField, username);
            human.humanThinkingPause();
            
            WebElement passwordField = driver.findElement(By.id("password"));
            updateLoginStatus(LoginStatus.LOGGING_IN, "📝 Gebe Passwort ein...");
            human.humanType(passwordField, password);
            human.humanThinkingPause();
            
            WebElement loginBtn = driver.findElement(By.id("login-btn"));
            updateLoginStatus(LoginStatus.LOGGING_IN, "🔑 Sende Anmeldedaten...");
            human.humanClick(loginBtn);
            
            human.waitForPageLoad();
            human.humanPause();
            
            LoginStatus loginResult = checkLoginStatus();
            
            if (loginResult == LoginStatus.LOGGED_IN) {
                String pageSource = driver.getPageSource();
                
                Pattern pattern = Pattern.compile("Eingeloggt als:\\s*([\\d\\s]+)");
                Matcher matcher = pattern.matcher(pageSource);
                if (matcher.find()) {
                    String displayedNumber = matcher.group(1).trim();
                    updateLoginNumber("✅ Angemeldet als: " + displayedNumber);
                    updateLoginStatus(LoginStatus.LOGGED_IN, "✅ Angemeldet als: " + displayedNumber);
                } else {
                    updateLoginNumber("✅ Angemeldet");
                    updateLoginStatus(LoginStatus.LOGGED_IN, "✅ Erfolgreich angemeldet!");
                }
                
                human.randomPageInteraction();
                human.randomScroll();
                
                updateStatus("✅ Angemeldet erfolgreich");
                consecutiveErrors = 0;
                return true;
                
            } else {
                updateLoginStatus(LoginStatus.NOT_LOGGED_IN, "❌ Login fehlgeschlagen! Prüfe Nummer/Passwort");
                updateStatus("❌ Login fehlgeschlagen! Prüfe Nummer/Passwort");
                return false;
            }
            
        } catch (Exception e) {
            updateLoginStatus(LoginStatus.ERROR, "❌ Login Fehler: " + e.getMessage());
            updateStatus("❌ Login Fehler: " + e.getMessage());
            return false;
        }
    }
    
    private LoginStatus checkLoginStatus() {
        try {
            String pageSource = driver.getPageSource();
            
            boolean hasLoginForm = pageSource.contains("username") && 
                                  pageSource.contains("password");
            
            boolean isLoggedIn = pageSource.contains("Eingeloggt als:") || 
                                pageSource.contains("Mein Guthaben") ||
                                pageSource.contains("Übersicht");
            
            boolean hasError = pageSource.contains("Falsche Zugangsdaten") || 
                              pageSource.contains("fehlerhaft") ||
                              pageSource.contains("Ungültig");
            
            boolean refillSuccess = pageSource.contains("erfolgreich") && 
                                   pageSource.contains("Refill") ||
                                   pageSource.contains("aktiviert");
            
            boolean sessionTimeout = pageSource.contains("Sitzung abgelaufen") ||
                                    pageSource.contains("session") ||
                                    pageSource.contains("timeout");
            
            if (refillSuccess) {
                return LoginStatus.REFILL_ACTIVATED;
            } else if (hasError || sessionTimeout) {
                return LoginStatus.NOT_LOGGED_IN;
            } else if (isLoggedIn) {
                return LoginStatus.LOGGED_IN;
            } else if (hasLoginForm) {
                return LoginStatus.NOT_LOGGED_IN;
            } else {
                return LoginStatus.UNKNOWN;
            }
            
        } catch (Exception e) {
            return LoginStatus.UNKNOWN;
        }
    }
    
    private void monitorLoop(int interval, float target, int waitAfter) {
        int retryCount = 0;
        boolean isFirstRun = true;
        
        updateStatus("🔄 Starte Überwachung...");
        human.humanReadingPause();
        
        while (isRunning) {
            checkCount++;
            long checkStartTime = System.currentTimeMillis();
            
            try {
                LoginStatus status = checkLoginStatus();
                
                if (status == LoginStatus.NOT_LOGGED_IN) {
                    updateLoginStatus(LoginStatus.NOT_LOGGED_IN, "⚠️ Nicht eingeloggt! Versuche erneut...");
                    updateStatus("⚠️ Nicht eingeloggt! Versuche erneut...");
                    sleep(30 * 1000);
                    continue;
                } else if (status == LoginStatus.LOGGED_IN) {
                    updateLoginStatus(LoginStatus.LOGGED_IN, "✅ Eingeloggt - Prüfe Volumen...");
                }
                
                if (random.nextBoolean()) {
                    human.randomPageInteraction();
                }
                
                sleep(random.nextInt(1000) + 500);
                
                driver.navigate().refresh();
                human.waitForPageLoad();
                human.humanPause();
                
                if (random.nextBoolean()) {
                    human.randomScroll();
                }
                
                VolumeData data = getVolumeData();
                float currentRefillGb = data.refillGb;
                float currentInklusivGb = data.inklusivGb;
                
                lastKnownGb = currentRefillGb;
                lastKnownInklusivGb = currentInklusivGb;
                
                updateInklusiv(
                    String.format("%.1f", currentInklusivGb) + " GB / " + currentTarif.maxInklusivGb + " GB",
                    String.format("%.2f", currentRefillGb) + " GB"
                );
                updateGb("Refill: " + String.format("%.2f", currentRefillGb) + " GB | Inklusiv: " + String.format("%.1f", currentInklusivGb) + " GB");
                
                // INKLUSIV-VOLUMEN VORHANDEN?
                if (currentInklusivGb > currentTarif.thresholdCritical) {
                    updateLoginStatus(LoginStatus.INKLUSIV_AVAILABLE, 
                        "✅ Inklusiv-Volumen: " + String.format("%.1f", currentInklusivGb) + " GB verfügbar");
                    updateStatus("📊 Inklusiv-Volumen vorhanden (" + String.format("%.1f", currentInklusivGb) + " GB) - Warte auf Verbrauch");
                    
                    int waitTime = getAdaptiveInterval(currentRefillGb, currentInklusivGb, target);
                    updatePhase("🟢 Inklusiv: " + String.format("%.1f", currentInklusivGb) + " GB", waitTime + " Sekunden");
                    
                    int segments = random.nextInt(3) + 2;
                    int segmentMs = waitTime * 1000 / segments;
                    
                    for (int i = 0; i < segments && isRunning; i++) {
                        sleep(segmentMs);
                        if (isRunning && random.nextBoolean()) {
                            performHumanInteractions();
                        }
                    }
                    continue;
                }
                
                // INKLUSIV AUFGEBRAUCHT
                updateLoginStatus(LoginStatus.INKLUSIV_DEPLETED, 
                    "🟠 Inklusiv-Volumen aufgebraucht! Refill-Volumen: " + String.format("%.2f", currentRefillGb) + " GB");
                
                // REFILL-VOLUMEN PRÜFEN (<= 0.15 GB)
                if (currentRefillGb <= target) {
                    updateStatus("🎯 Ziel erreicht! (" + currentRefillGb + " GB <= " + target + " GB)");
                    updateLoginStatus(LoginStatus.LOGGED_IN, "🎯 Ziel erreicht! Führe Refill durch...");
                    
                    human.humanThinkingPause();
                    
                    if (clickRefillButton()) {
                        refillCount++;
                        consecutiveRefills++;
                        updateRefillCount(refillCount);
                        updateStatus("✅ Refill #" + refillCount + " aktiviert!");
                        updateLoginStatus(LoginStatus.REFILL_ACTIVATED, "✅ Refill #" + refillCount + " aktiviert!");
                        
                        sleep(5000);
                        driver.navigate().refresh();
                        human.waitForPageLoad();
                        VolumeData newData = getVolumeData();
                        float newRefillGb = newData.refillGb;
                        
                        if (newRefillGb > currentRefillGb + 0.02) {
                            updateStatus("✅ Refill bestätigt! Neues Volumen: " + String.format("%.2f", newRefillGb) + " GB");
                            retryCount = 0;
                            consecutiveErrors = 0;
                            
                            int waitTime;
                            if (refillCount <= 2) {
                                waitTime = random.nextInt(60) + 60;
                                updateStatus("🔄 Erste Refills: Längere Pause (" + (waitTime/60) + " Stunden)");
                            } else {
                                waitTime = random.nextInt(15) + 25;
                            }
                            
                            updateStatus("💤 Warte " + waitTime + " Minuten...");
                            sleep(waitTime * 60 * 1000);
                            
                        } else {
                            updateStatus("⚠️ Refill wurde nicht wirksam! Volumen: " + newRefillGb + " GB");
                            updateLoginStatus(LoginStatus.REFILL_FAILED, "⚠️ Refill nicht wirksam!");
                            
                            retryCount++;
                            consecutiveErrors++;
                            int waitTime = VariableInterval.getBackoffInterval(retryCount);
                            sleep(waitTime * 1000L);
                        }
                        
                    } else {
                        retryCount++;
                        consecutiveErrors++;
                        updateStatus("⚠️ Refill fehlgeschlagen, Versuch " + retryCount);
                        updateLoginStatus(LoginStatus.REFILL_FAILED, "⚠️ Refill fehlgeschlagen! Versuch " + retryCount);
                        
                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            updateStatus("❌ Zu viele Fehler! Breche ab...");
                            updateLoginStatus(LoginStatus.ERROR, "❌ Zu viele Fehler! Bitte App neustarten");
                            stop();
                            return;
                        }
                        
                        int waitTime = VariableInterval.getBackoffInterval(retryCount);
                        updateStatus("💤 Warte " + (waitTime/60) + " Minuten...");
                        sleep(waitTime * 1000L);
                    }
                    
                    if (consecutiveRefills >= MAX_CONSECUTIVE_REFILLS) {
                        updateStatus("ℹ️ " + MAX_CONSECUTIVE_REFILLS + " Refills durchgeführt. Warte 2 Stunden...");
                        updateLoginStatus(LoginStatus.LOGGED_IN, "⏸️ Pause nach " + MAX_CONSECUTIVE_REFILLS + " Refills");
                        sleep(120 * 60 * 1000);
                        consecutiveRefills = 0;
                    }
                    
                } else {
                    int waitTime = getAdaptiveInterval(currentRefillGb, currentInklusivGb, target);
                    
                    if (random.nextInt(10) == 0) {
                        waitTime += random.nextInt(300) + 60;
                        updateStatus("🧠 Kurze Ablenkung...");
                    }
                    
                    String phaseText = "";
                    if (currentRefillGb > REFILL_PHASE_HIGH) {
                        phaseText = "🟡 Refill: Volle Ladung";
                    } else if (currentRefillGb > REFILL_PHASE_MEDIUM) {
                        phaseText = "🟡 Refill: Läuft noch";
                    } else if (currentRefillGb > REFILL_PHASE_LOW) {
                        phaseText = "🟠 Refill: Wird knapp";
                    } else {
                        phaseText = "🔴 Refill: Gleich leer!";
                    }
                    
                    updateStatus("⏳ " + phaseText + " - Nächste Prüfung in ~" + (waitTime/60) + " Minuten");
                    
                    int totalWaitMs = waitTime * 1000;
                    int segments = random.nextInt(3) + 2;
                    int segmentMs = totalWaitMs / segments;
                    
                    for (int i = 0; i < segments && isRunning; i++) {
                        sleep(segmentMs);
                        
                        if (isRunning) {
                            performHumanInteractions();
                            
                            if (random.nextInt(5) == 0) {
                                LoginStatus midStatus = checkLoginStatus();
                                if (midStatus != LoginStatus.LOGGED_IN) {
                                    updateLoginStatus(midStatus, "⚠️ Status geändert!");
                                }
                            }
                        }
                    }
                }
                
                long checkDuration = System.currentTimeMillis() - checkStartTime;
                lastCheckTime = checkStartTime;
                
            } catch (Exception e) {
                consecutiveErrors++;
                updateStatus("⚠️ Fehler: " + e.getMessage());
                updateLoginStatus(LoginStatus.ERROR, "⚠️ Fehler: " + e.getMessage());
                
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    updateStatus("❌ Zu viele Fehler! Breche ab...");
                    updateLoginStatus(LoginStatus.ERROR, "❌ Zu viele Fehler! Bitte App neustarten");
                    stop();
                    return;
                }
                
                int waitTime = VariableInterval.getBackoffInterval(consecutiveErrors);
                updateStatus("💤 Warte " + (waitTime/60) + " Minuten...");
                sleep(waitTime * 1000L);
            }
        }
    }
    
    private void performHumanInteractions() {
        if (random.nextInt(100) < 30) {
            int action = random.nextInt(4);
            switch (action) {
                case 0:
                    human.randomScroll();
                    break;
                case 1:
                    human.randomHover();
                    break;
                case 2:
                    human.randomMouseMovement();
                    break;
                case 3:
                    try {
                        Thread.sleep(random.nextInt(3000) + 2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    break;
            }
        }
    }
    
    // ==========================================
    // CLICK REFILL BUTTON
    // ==========================================
    
    private boolean clickRefillButton() {
        try {
            if (random.nextBoolean()) {
                human.randomScroll();
            }
            
            WebElement button = findRefillButton();
            
            if (button != null && button.isDisplayed() && button.isEnabled()) {
                human.humanThinkingPause();
                human.humanClickSafe(button);
                human.humanPause();
                human.waitForPageLoad();
                
                String pageSource = driver.getPageSource();
                boolean success = pageSource.contains("erfolgreich") || 
                                 pageSource.contains("aktiviert") ||
                                 pageSource.contains("bestätigt") ||
                                 !pageSource.contains("Fehler");
                
                if (success) {
                    human.randomScroll();
                    return true;
                }
            }
            
            try {
                JavascriptExecutor js = (JavascriptExecutor) driver;
                Boolean clicked = (Boolean) js.executeScript(
                    "var buttons = document.querySelectorAll('button, div, a');" +
                    "for(var i=0; i<buttons.length; i++) {" +
                    "  if(buttons[i].innerText && buttons[i].innerText.includes('Refill aktivieren')) {" +
                    "    buttons[i].scrollIntoView({behavior: 'smooth', block: 'center'});" +
                    "    buttons[i].click();" +
                    "    return true;" +
                    "  }" +
                    "}" +
                    "return false;"
                );
                if (clicked != null && clicked) {
                    human.humanPause();
                    return true;
                }
            } catch (Exception e) {
                // Ignorieren
            }
            
            return false;
            
        } catch (Exception e) {
            return false;
        }
    }
    
    private WebElement findRefillButton() {
        List<String> selectors = Arrays.asList(
            "//button[contains(text(), 'Refill aktivieren')]",
            "//button[contains(translate(., 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'refill aktivieren')]",
            "//div[contains(text(), 'Refill aktivieren')]",
            "//*[contains(@class, 'refill') and contains(text(), 'aktivieren')]",
            "//button[contains(@class, 'refill')]",
            "//a[contains(text(), 'Refill aktivieren')]",
            "//*[@id='refill-button']",
            "//*[@id='refill-btn']"
        );
        Collections.shuffle(selectors, random);
        
        for (String selector : selectors) {
            try {
                WebElement element = driver.findElement(By.xpath(selector));
                if (element.isDisplayed() && element.isEnabled()) {
                    return element;
                }
            } catch (Exception e) {
                // Versuche nächsten Selektor
            }
        }
        return null;
    }
    
    public void stop() {
        isRunning = false;
        if (driver != null) {
            try {
                driver.quit();
            } catch (Exception e) {
                // Ignorieren
            }
            driver = null;
        }
        updateLoginStatus(LoginStatus.NOT_LOGGED_IN, "⏹️ Gestoppt");
        updateStatus("⏹️ Gestoppt");
    }
    
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void updateStatus(String status) {
        if (listener != null) {
            mainHandler.post(() -> listener.onStatusUpdate(status));
        }
    }
    
    private void updateGb(String gb) {
        if (listener != null) {
            mainHandler.post(() -> listener.onGbUpdate(gb));
        }
    }
    
    private void updateRefillCount(int count) {
        if (listener != null) {
            mainHandler.post(() -> listener.onRefillCountUpdate(count));
        }
    }
    
    private void updateLoginNumber(String number) {
        if (listener != null) {
            mainHandler.post(() -> listener.onLoginNumber(number));
        }
    }
    
    private void updateLoginStatus(LoginStatus status, String details) {
        currentLoginStatus = status;
        if (listener != null) {
            mainHandler.post(() -> listener.onLoginStatusUpdate(status, details));
        }
    }
    
    private void updatePhase(String phase, String details) {
        if (listener != null) {
            mainHandler.post(() -> listener.onPhaseUpdate(phase, details));
        }
    }
    
    private void updateInklusiv(String inklusiv, String refill) {
        if (listener != null) {
            mainHandler.post(() -> listener.onInklusivUpdate(inklusiv, refill));
        }
    }
    
    private void updateTarifDetected(String tarifName, int maxGb) {
        if (listener != null) {
            mainHandler.post(() -> listener.onTarifDetected(tarifName, maxGb));
        }
    }
}
