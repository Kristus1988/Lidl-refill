// ============ SWIPE MIT MENSCHLICHEN ABWEICHUNGEN ============
private void performSwipeGesture() {
    if (!swipePlaced) return;
    
    totalSwipes++;
    updateStatus("🔄 Swipe #" + totalSwipes);
    
    // Zufällige Abweichung: ±20 Pixel
    int randomOffsetX = (int)((Math.random() - 0.5) * 40);
    int randomOffsetY = (int)((Math.random() - 0.5) * 40);
    
    // Zufällige Dauer: 400-800ms
    long randomDuration = 400 + (long)(Math.random() * 400);
    
    // Zufällige Verzögerung: 0-500ms
    long randomDelay = (long)(Math.random() * 500);
    
    int startX = swipeStart.x + randomOffsetX;
    int startY = swipeStart.y + randomOffsetY;
    int endX = swipeEnd.x + randomOffsetX;
    int endY = swipeEnd.y + randomOffsetY;
    
    Path path = new Path();
    path.moveTo(startX, startY);
    path.lineTo(endX, endY);
    
    // Leichte Krümmung für natürlichen Swipe
    path.quadTo(
        (startX + endX) / 2 + (int)((Math.random() - 0.5) * 100),
        (startY + endY) / 2 + (int)((Math.random() - 0.5) * 50)
    );
    
    GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
    gestureBuilder.addStroke(new GestureDescription.StrokeDescription(path, 0, randomDuration));
    
    handler.postDelayed(() -> {
        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                updateStatus("✅ Swipe #" + totalSwipes + " (menschlich)");
                if (isRunning) handler.postDelayed(() -> performRealOcr(), OCR_DURATION + (long)(Math.random() * 300));
            }
        }, null);
    }, randomDelay);
}

// ============ REFILL MIT MENSCHLICHEN ABWEICHUNGEN ============
private void clickRefillButton() {
    if (!refillPlaced) return;
    
    updateStatus("🔄 Refill...");
    
    // Zufällige Abweichung: ±15 Pixel
    int randomOffsetX = (int)((Math.random() - 0.5) * 30);
    int randomOffsetY = (int)((Math.random() - 0.5) * 30);
    
    // Zufällige Klick-Dauer: 50-200ms
    long clickDuration = 50 + (long)(Math.random() * 150);
    
    // Zufällige Verzögerung: 100-500ms
    long randomDelay = 100 + (long)(Math.random() * 400);
    
    int clickX = refillButton.x + randomOffsetX;
    int clickY = refillButton.y + randomOffsetY;
    
    Path clickPath = new Path();
    clickPath.moveTo(clickX, clickY);
    
    GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
    gestureBuilder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, clickDuration));
    
    handler.postDelayed(() -> {
        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                updateStatus("✅ REFILL! (menschlich)");
                Toast.makeText(OverlayService.this, "✅ REFILL!", Toast.LENGTH_LONG).show();
            }
        }, null);
    }, randomDelay);
}
