@Override
public void onCreate() {
    super.onCreate();
    Log.d(TAG, "onCreate - Service wird initialisiert");
    
    prefs = PreferenceManager.getDefaultSharedPreferences(this);
    
    WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    Display display = wm.getDefaultDisplay();
    Point size = new Point();
    display.getSize(size);
    screenWidth = size.x;
    screenHeight = size.y;
    DisplayMetrics metrics = new DisplayMetrics();
    display.getMetrics(metrics);
    screenDensity = metrics.densityDpi;
    
    loadPositions();
    
    int savedIndex = prefs.getInt("consumption_index", 0);
    currentModeIndex = savedIndex;
    
    textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    
    // ===== MEDIAPROJECTION PRÜFEN =====
    if (sMediaProjection != null) {
        setupVirtualDisplay(sMediaProjection);
    } else {
        Log.w(TAG, "⚠️ MediaProjection ist null – zeige Hinweis");
        updateStatus("⚠️ Bitte Screen-Capture aktivieren");
        // Zeige Hinweis im Overlay
        handler.postDelayed(() -> {
            Toast.makeText(this, "⚠️ Screen-Capture fehlt!\nKlicke auf den orangenen Button in der Haupt-App.", Toast.LENGTH_LONG).show();
        }, 1000);
    }
    
    createOverlay();
    createVisualHelpers();
    
    updateStatus("● Bereit");
    updateCountdown("⏱ Warte: --:--");
    updateCycle();
    updateOcrResult("📸 OCR: --");
}

// ===== NEUE METHODE: Screen-Capture neu anfordern =====
private void requestScreenCaptureAgain() {
    MediaProjectionManager projectionManager = 
        (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    if (projectionManager != null) {
        Intent intent = projectionManager.createScreenCaptureIntent();
        // Kann nicht direkt aus dem Service gestartet werden – zeige Hinweis
        Toast.makeText(this, 
            "📸 Bitte in der Haupt-App auf 'Screen-Capture aktivieren' klicken!", 
            Toast.LENGTH_LONG).show();
    }
}

// OCR-Methode mit erneuter Prüfung
private void performOcr() {
    // Prüfe ob MediaProjection noch aktiv ist (Honor setzt es zurück)
    if (!isScreenshotReady || imageReader == null || sMediaProjection == null) {
        Toast.makeText(this, 
            "⚠️ Screen-Capture nicht aktiv!\nBitte in der Haupt-App neu aktivieren.", 
            Toast.LENGTH_LONG).show();
        updateOcrResult("⚠️ Screen-Capture fehlt");
        updateStatus("⚠️ Screen-Capture fehlt");
        
        // Versuche neu zu initialisieren
        if (sMediaProjection != null) {
            setupVirtualDisplay(sMediaProjection);
        }
        return;
    }
    
    updateStatus("📸 Screenshot wird gemacht...");
    updateOcrResult("📸 Screenshot...");
    
    Bitmap screenshot = takeScreenshot();
    if (screenshot == null) {
        updateStatus("⚠️ Screenshot fehlgeschlagen (5x)");
        updateOcrResult("⚠️ Screenshot fehlgeschlagen");
        Toast.makeText(this, "❌ Screenshot fehlgeschlagen!\nVersuche es nochmal.", Toast.LENGTH_LONG).show();
        return;
    }
    
    updateStatus("📸 OCR wird ausgeführt...");
    updateOcrResult("📸 OCR...");
    
    InputImage image = InputImage.fromBitmap(screenshot, 0);
    textRecognizer.process(image)
        .addOnSuccessListener(visionText -> {
            String resultText = visionText.getText();
            Log.d(TAG, "OCR Ergebnis: " + resultText);
            
            String volume = extractVolume(resultText);
            if (volume != null) {
                ocrResult = "📸 " + volume + " GB";
                updateOcrResult(ocrResult);
                updateStatus("📸 OCR: " + volume + " GB");
                Toast.makeText(this, "📸 OCR Ergebnis: " + volume + " GB", Toast.LENGTH_LONG).show();
            } else {
                ocrResult = "📸 Kein GB-Wert";
                updateOcrResult(ocrResult);
                updateStatus("📸 Kein GB-Wert gefunden");
                Toast.makeText(this, "⚠️ Kein GB-Wert im Screenshot gefunden", Toast.LENGTH_LONG).show();
            }
            screenshot.recycle();
        })
        .addOnFailureListener(e -> {
            updateStatus("❌ OCR Fehler: " + e.getMessage());
            updateOcrResult("❌ OCR Fehler");
            Toast.makeText(this, "❌ OCR Fehler: " + e.getMessage(), Toast.LENGTH_LONG).show();
            screenshot.recycle();
        });
}
