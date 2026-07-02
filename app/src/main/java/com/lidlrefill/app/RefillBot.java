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
import java.util.concurrent.ExecutionException;

public class RefillBot {
    private static final String TAG = "RefillBot";
    private final TextRecognizer recognizer;

    public RefillBot() {
        // Initialisiert den TextRecognizer mit lateinischer Schrift
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    /**
     * Extrahiert Text aus einem Bild (Bitmap)
     */
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

    /**
     * Extrahiert Text aus einem Bild-URL
     */
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

    /**
     * Extrahiert Text aus einem Byte-Array
     */
    public CompletableFuture<String> extractTextFromBytes(byte[] imageData) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
        return extractTextFromImage(bitmap);
    }

    /**
     * Extrahiert bestimmte Informationen aus dem Text (z.B. Preise, Produktnamen)
     */
    public List<String> extractPrices(String text) {
        List<String> prices = new ArrayList<>();
        if (text == null) return prices;

        // Einfache Regex für Preise (z.B. 1.99€, 2,50 €, 3.49)
        String[] lines = text.split("\n");
        for (String line : lines) {
            if (line.matches(".*[0-9]+[.,][0-9]{2}\\s?[€$]?.*")) {
                prices.add(line.trim());
            }
        }
        return prices;
    }

    /**
     * Extrahiert Produktnamen aus dem Text
     */
    public List<String> extractProductNames(String text) {
        List<String> products = new ArrayList<>();
        if (text == null) return products;

        String[] lines = text.split("\n");
        for (String line : lines) {
            // Filtert Zeilen, die wahrscheinlich Produktnamen sind (mehr als 3 Zeichen, keine Zahlen-only)
            String trimmed = line.trim();
            if (trimmed.length() > 3 && !trimmed.matches("[0-9.,\\s]+")) {
                products.add(trimmed);
            }
        }
        return products;
    }

    /**
     * Lädt ein Bild von einer URL herunter
     */
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

    /**
     * Schließt die Ressourcen
     */
    public void close() {
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Exception e) {
                Log.e(TAG, "Fehler beim Schließen des Recognizers", e);
            }
        }
    }
}
