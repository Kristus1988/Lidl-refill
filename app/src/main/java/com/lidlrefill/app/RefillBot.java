package com.lidlrefill.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class RefillBot {
    private static final String TAG = "RefillBot";
    private final TextRecognizer recognizer;
    private boolean isRunning = false;

    public RefillBot() {
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    public void start() {
        if (!isRunning) {
            isRunning = true;
            Log.d(TAG, "RefillBot gestartet");
        } else {
            Log.d(TAG, "RefillBot läuft bereits");
        }
    }

    public void stop() {
        if (isRunning) {
            isRunning = false;
            Log.d(TAG, "RefillBot gestoppt");
            close();
        } else {
            Log.d(TAG, "RefillBot läuft nicht");
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    public CompletableFuture<String> extractTextFromImage(Bitmap bitmap) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        if (bitmap == null) {
            future.completeExceptionally(new IllegalArgumentException("Bitmap darf nicht null sein"));
            return future;
        }

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        
        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    String extractedText = visionText.getText();
                    Log.d(TAG, "Text extrahiert: " + extractedText);
                    future.complete(extractedText);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Fehler bei Texterkennung", e);
                    future.completeExceptionally(e);
                });

        return future;
    }

    public CompletableFuture<String> extractTextFromUrl(String imageUrl) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Bitmap bitmap = downloadImage(imageUrl);
                return extractTextFromImage(bitmap).get();
            } catch (Exception e) {
                Log.e(TAG, "Fehler beim Herunterladen/Verarbeiten des Bildes", e);
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<String> extractTextFromBytes(byte[] imageData) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
        return extractTextFromImage(bitmap);
    }

    public List<String> extractPrices(String text) {
        List<String> prices = new ArrayList<>();
        if (text == null) return prices;

        String[] lines = text.split("\n");
        for (String line : lines) {
            if (line.matches(".*[0-9]+[.,][0-9]{2}\\s?[€$]?.*")) {
                prices.add(line.trim());
            }
        }
        return prices;
    }

    public List<String> extractProductNames(String text) {
        List<String> products = new ArrayList<>();
        if (text == null) return products;

        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() > 3 && !trimmed.matches("[0-9.,\\s]+")) {
                products.add(trimmed);
            }
        }
        return products;
    }

    private Bitmap downloadImage(String imageUrl) throws IOException {
        URL url = new URL(imageUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP-Fehler: " + connection.getResponseCode());
        }

        try (InputStream inputStream = connection.getInputStream()) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            byte[] imageData = outputStream.toByteArray();
            return BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
        }
    }

    public void close() {
        if (recognizer != null) {
            try {
                recognizer.close();
                Log.d(TAG, "Recognizer geschlossen");
            } catch (Exception e) {
                Log.e(TAG, "Fehler beim Schließen des Recognizers", e);
            }
        }
    }
}
