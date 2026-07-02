package com.lidlrefill.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SCREENSHOT = 1;

    private Button btnStartBot, btnStopBot;
    private TextView tvStatus, tvVerbrauch, tvAktion;

    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private Handler handler = new Handler(Looper.getMainLooper());
    private RefillBot refillBot;
    private boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStartBot = findViewById(R.id.btn_start_bot);
        btnStopBot = findViewById(R.id.btn_stop_bot);
        tvStatus = findViewById(R.id.tv_status);
        tvVerbrauch = findViewById(R.id.tv_verbrauch);
        tvAktion = findViewById(R.id.tv_aktion);

        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        btnStartBot.setOnClickListener(v -> startBot());
        btnStopBot.setOnClickListener(v -> stopBot());

        refillBot = new RefillBot(this, this::updateUI, this::takeScreenshot);
    }

    private void startBot() {
        if (!isRunning) {
            // Prüfen ob Accessibility Service aktiv ist
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Bitte Accessibility Service aktivieren!", Toast.LENGTH_LONG).show();
                startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            requestScreenshotPermission();
        }
    }

    private void stopBot() {
        isRunning = false;
        refillBot.stop();
        handler.removeCallbacksAndMessages(null);
        tvStatus.setText("Bot gestoppt");
        tvAktion.setText("");
        Toast.makeText(this, "Bot wurde gestoppt", Toast.LENGTH_SHORT).show();
    }

    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/" + RefillAccessibilityService.class.getCanonicalName();
        try {
            String enabledServices = android.provider.Settings.Secure.getString(
                getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            return enabledServices != null && enabledServices.contains(service);
        } catch (Exception e) {
            return false;
        }
    }

    private void requestScreenshotPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = mediaProjectionManager.createScreenCaptureIntent();
            startActivityForResult(intent, REQUEST_CODE_SCREENSHOT);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_SCREENSHOT) {
            if (resultCode == Activity.RESULT_OK) {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
                isRunning = true;
                tvStatus.setText("Bot läuft...");
                refillBot.start();
            } else {
                Toast.makeText(this, "Berechtigung benötigt!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void updateUI(String status, String verbrauch, String aktion) {
        runOnUiThread(() -> {
            if (status != null) tvStatus.setText(status);
            if (verbrauch != null) tvVerbrauch.setText("Verbrauch: " + verbrauch);
            if (aktion != null) tvAktion.setText("Aktion: " + aktion);
        });
    }

    private void takeScreenshot(RefillBot.ScreenshotCallback callback) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        int density = getResources().getDisplayMetrics().densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "Screenshot",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                null
        );

        handler.postDelayed(() -> {
            Image image = imageReader.acquireLatestImage();
            if (image != null) {
                Bitmap bitmap = imageToBitmap(image);
                image.close();
                callback.onScreenshotTaken(bitmap);
            } else {
                callback.onScreenshotTaken(null);
            }
            stopScreenshot();
        }, 300);
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        Bitmap bitmap = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(),
                Bitmap.Config.ARGB_8888
        );
        bitmap.copyPixelsFromBuffer(buffer);
        return Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
    }

    private void stopScreenshot() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBot();
        stopScreenshot();
    }
}
