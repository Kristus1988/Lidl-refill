package com.lidlrefill.app;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RefillBot {

    // ========== KONFIGURATION ==========
    private static final float SCHWELLWERT = 0.35f;

    // SWIPE: BASIS-KOORDINATEN (mit Zufallsabweichung)
    private static final int SWIPE_BASE_START_X = 540;
    private static final int SWIPE_BASE_START_Y = 200;
    private static final int SWIPE_BASE_END_X = 540;
    private static final int SWIPE_BASE_END_Y = 1000;

    // REFILL-BUTTON POSITION
    private static final int REFILL_BASE_X = 236;
    private static final int REFILL_BASE_Y = 1057;

    // Zufallsbereiche
    private static final int SWIPE_ABWEICHUNG = 40;
    private static final int TAP_ABWEICHUNG = 25;

    // Wartezeiten
    private static final int WARTEN_MIN = 8000;
    private static final int WARTEN_MAX = 10000;
    private static final int WARTEN_NACH_REFILL_MIN = 10000;
    private static final int WARTEN_NACH_REFILL_MAX = 12000;
    private static final int WARTEN_ZWISCHEN_CHECKS = 300000;

    // Swipe-Geschwindigkeit
    private static final int SWIPE_DURATION_MIN = 300;
    private static final int SWIPE_DURATION_MAX = 800;

    // OCR-Fehlerquote (5%)
    private static final float OCR_FEHLER_QUOTE = 0.05f;
    // ====================================

    private final MainActivity activity;
    private final UIUpdateCallback uiCallback;
    private final ScreenshotProvider screenshotProvider;

    private Handler handler = new Handler(Looper.getMainLooper());
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private TextRecognizer textRecognizer;
    private Random random = new Random();

    // ========== INTERFACES ==========
    public interface UIUpdateCallback {
        void onUpdate(String status, String verbrauch, String aktion);
    }

    public interface ScreenshotProvider {
        void takeScreenshot(ScreenshotCallback callback);
    }

    public interface ScreenshotCallback {
        void onScreenshotTaken(Bitmap bitmap);
    }
    // ================================

    public RefillBot(MainActivity activity, UIUpdateCallback uiCallback,
                     ScreenshotProvider screenshotProvider) {
        this.activity = activity;
        this.uiCallback = uiCallback;
        this.screenshotProvider = screenshotProvider;
        this.textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    public void start() {
        isRunning.set(true);
        uiCallback.onUpdate("Bot gestartet", "Warte...", "Initialisiere");
        startCycle();
    }

    public void stop() {
        isRunning.set(false);
        handler.removeCallbacksAndMessages(null);
        uiCallback.onUpdate("Bot gestoppt", "", "");
    }

    // ========== ZUFALLSFUNKTIONEN ==========
    private int getRandomWaitTime() {
        return WARTEN_MIN + random.nextInt(WARTEN_MAX - WARTEN_MIN + 1);
    }

    private int getRandomWaitTimeAfterRefill() {
        return WARTEN_NACH_REFILL_MIN + random.nextInt(WARTEN_NACH_REFILL_MAX - WARTEN_NACH_REFILL_MIN + 1);
    }

    private int getRandomSwipeDuration() {
        return SWIPE_DURATION_MIN + random.nextInt(SWIPE_DURATION_MAX - SWIPE_DURATION_MIN + 1);
    }

    private int getRandomOffset(int maxOffset) {
        return -maxOffset + random.nextInt(2 * maxOffset + 1);
    }

    private String getWaitTimeString(int millis) {
        float sekunden = millis / 1000.0f;
        return String.format("%.1f", sekunden);
    }

    private void kleinePause(Runnable onComplete) {
        int pause = 100 + random.nextInt(400);
        handler.postDelayed(onComplete, pause);
    }

    private boolean shouldFailOCR() {
        return random.nextFloat() < OCR_FEHLER_QUOTE;
    }
    // ========================================

    // ========== HAUPTLOGIK ==========
    private void startCycle() {
        if (!isRunning.get()) return;

        uiCallback.onUpdate("Bot läuft", "Warte...", "1. Swipe (aktualisiere)");
        performHumanSwipe(() -> {
            if (!isRunning.get()) return;

            int waitTime = getRandomWaitTime();
            uiCallback.onUpdate("Bot läuft", "Warte...", "2. Warte " + getWaitTimeString(waitTime) + " Sekunden");

            handler.postDelayed(() -> {
                if (!isRunning.get()) return;
                uiCallback.onUpdate("Bot läuft", "Prüfe...", "3. Screenshot & OCR");
                checkVerbrauchAndRefill();
            }, waitTime);
        });
    }

    private void performHumanSwipe(Runnable onComplete) {
        int startX = SWIPE_BASE_START_X + getRandomOffset(SWIPE_ABWEICHUNG);
        int startY = SWIPE_BASE_START_Y + getRandomOffset(SWIPE_ABWEICHUNG);
        int endX = SWIPE_BASE_END_X + getRandomOffset(SWIPE_ABWEICHUNG);
        int endY = SWIPE_BASE_END_Y + getRandomOffset(SWIPE_ABWEICHUNG);
        int duration = getRandomSwipeDuration();

        uiCallback.onUpdate("Bot läuft", "Warte...",
                "Swipe: (" + startX + "," + startY + ") → (" + endX + "," + endY + ") " + duration + "ms");

        // Accessibility Service für Swipe
        RefillAccessibilityService service = RefillAccessibilityService.getInstance();
        if (service != null) {
            service.performHumanSwipe(startX, startY, endX, endY, duration);
        }

        kleinePause(onComplete);
    }

    private void performHumanTap(int baseX, int baseY, String label) {
        int tapX = baseX + getRandomOffset(TAP_ABWEICHUNG);
        int tapY = baseY + getRandomOffset(TAP_ABWEICHUNG);

        uiCallback.onUpdate("Bot läuft", "Warte...",
                label + " Tap: (" + tapX + "," + tapY + ")");

        RefillAccessibilityService service = RefillAccessibilityService.getInstance();
        if (service != null) {
            service.performHumanTap(tapX, tapY);
        }
    }

    private void checkVerbrauchAndRefill() {
        if (!isRunning.get()) return;

        screenshotProvider.takeScreenshot(bitmap -> {
            if (bitmap == null || !isRunning.get()) {
                uiCallback.onUpdate("Fehler", "Kein Screenshot", "Wiederhole...");
                handler.postDelayed(this::startCycle, getRandomWaitTime());
                return;
            }

            if (shouldFailOCR()) {
                uiCallback.onUpdate("Bot läuft", "OCR Fehler", "Übersehen...");
                handler.postDelayed(this::startCycle, getRandomWaitTime());
                return;
            }

            recognizeText(bitmap, text -> {
                if (!isRunning.get()) return;
                float verbrauch = parseVerbrauch(text);

                if (verbrauch >= 0) {
                    String verbrauchStr = String.format("%.2f GB", verbrauch);
                    uiCallback.onUpdate("Bot läuft", verbrauchStr, "Verbrauch erkannt");

                    if (verbrauch < SCHWELLWERT) {
                        uiCallback.onUpdate("Bot läuft", verbrauchStr, "4. REFILL klicken!");

                        handler.postDelayed(() -> {
                            if (!isRunning.get()) return;
                            performHumanTap(REFILL_BASE_X, REFILL_BASE_Y, "REFILL");

                            handler.postDelayed(() -> {
                                if (!isRunning.get()) return;
                                uiCallback.onUpdate("Bot läuft", verbrauchStr, "5. Swipe (prüfe)");
                                performHumanSwipe(() -> {
                                    if (!isRunning.get()) return;

                                    int waitTime = getRandomWaitTimeAfterRefill();
                                    uiCallback.onUpdate("Bot läuft", verbrauchStr,
                                            "6. Warte " + getWaitTimeString(waitTime) + " Sekunden");

                                    handler.postDelayed(() -> {
                                        if (!isRunning.get()) return;
                                        startLongWaitCycle(verbrauch);
                                    }, waitTime);
                                });
                            }, 800 + random.nextInt(400));
                        }, 500 + random.nextInt(500));

                    } else {
                        uiCallback.onUpdate("Bot läuft", verbrauchStr, "Verbrauch zu hoch (" + SCHWELLWERT + " GB)");
                        startLongWaitCycle(verbrauch);
                    }
                } else {
                    uiCallback.onUpdate("Bot läuft", "Nicht erkannt", "Wiederhole...");
                    handler.postDelayed(this::startCycle, getRandomWaitTime());
                }
            });
        });
    }

    private void startLongWaitCycle(float aktuellerVerbrauch) {
        if (!isRunning.get()) return;
        String verbrauchStr = String.format("%.2f GB", aktuellerVerbrauch);
        uiCallback.onUpdate("Bot läuft", verbrauchStr, "Warte 5 Minuten...");

        handler.postDelayed(() -> {
            if (!isRunning.get()) return;
            uiCallback.onUpdate("Bot läuft", verbrauchStr, "1. Swipe (aktualisiere)");
            performHumanSwipe(() -> {
                if (!isRunning.get()) return;

                int waitTime = getRandomWaitTime();
                uiCallback.onUpdate("Bot läuft", verbrauchStr,
                        "2. Warte " + getWaitTimeString(waitTime) + " Sekunden");

                handler.postDelayed(() -> {
                    if (!isRunning.get()) return;
                    checkVerbrauchAndRefill();
                }, waitTime);
            });
        }, WARTEN_ZWISCHEN_CHECKS);
    }

    // ========== OCR ==========
    private void recognizeText(Bitmap bitmap, TextCallback callback) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        textRecognizer.process(image)
                .addOnSuccessListener(text -> callback.onTextRecognized(text.getText()))
                .addOnFailureListener(e -> callback.onTextRecognized(""));
    }

    interface TextCallback {
        void onTextRecognized(String text);
    }

    // ========== VERBRAUCH PARSEN ==========
    private float parseVerbrauch(String text) {
        Pattern pattern = Pattern.compile(
                "Verfügbares\\s*Gesamtvolumen.*?(\\d+[,.]\\d+)\\s*GB",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            String zahl = matcher.group(1).replace(",", ".");
            try {
                float wert = Float.parseFloat(zahl);
                if (wert >= 0 && wert <= 10) {
                    return wert;
                }
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        Pattern fallbackPattern = Pattern.compile("(\\d+[,.]\\d+)\\s*GB");
        Matcher fallbackMatcher = fallbackPattern.matcher(text);

        if (fallbackMatcher.find()) {
            String zahl = fallbackMatcher.group(1).replace(",", ".");
            try {
                float wert = Float.parseFloat(zahl);
                if (wert >= 0 && wert <= 10) {
                    return wert;
                }
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        return -1;
    }
}
