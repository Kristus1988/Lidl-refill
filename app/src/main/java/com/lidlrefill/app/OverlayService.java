package com.lidlrefill.app;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

public class OverlayService extends Service {

    private WindowManager windowManager;
    private LinearLayout overlayView;
    private ImageView screenshotPreview;
    private TextView textResult;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Overlay erstellen
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        overlayView = (LinearLayout) inflater.inflate(R.layout.overlay_layout, null);

        screenshotPreview = overlayView.findViewById(R.id.screenshot_preview);
        textResult = overlayView.findViewById(R.id.text_result);

        // WindowManager LayoutParams
        int layoutFlags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                          WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
        } else {
            layoutFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                layoutFlags,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(overlayView, params);

        // Close Button
        overlayView.findViewById(R.id.close_button).setOnClickListener(v -> stopSelf());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("bitmap")) {
            Bitmap bitmap = intent.getParcelableExtra("bitmap");
            if (bitmap != null) {
                screenshotPreview.setImageBitmap(bitmap);
                recognizeText(bitmap);
            }
        }
        return START_STICKY;
    }

    private void recognizeText(Bitmap ocrBitmap) {
        // InputImage erstellen
        InputImage image = InputImage.fromBitmap(ocrBitmap, 0);

        TextRecognizer recognizer = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
        );

        recognizer.process(image)
                .addOnSuccessListener(this::handleText)
                .addOnFailureListener(e -> {
                    textResult.setText("Fehler: " + e.getMessage());
                    Toast.makeText(this, "Text-Erkennung fehlgeschlagen", Toast.LENGTH_SHORT).show();
                });
    }

    private void handleText(Text text) {
        String resultText = text.getText();
        if (resultText != null && !resultText.isEmpty()) {
            textResult.setText("Erkannt:\n" + resultText);
        } else {
            textResult.setText("Kein Text erkannt");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayView != null) {
            windowManager.removeView(overlayView);
        }
    }
}
