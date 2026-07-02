package com.lidlrefill.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private RefillBot refillBot;
    private TextView textViewResult;
    private ImageView imageViewPreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Views initialisieren
        textViewResult = findViewById(R.id.textViewResult);
        imageViewPreview = findViewById(R.id.imageViewPreview);

        // RefillBot initialisieren
        refillBot = new RefillBot();

        // Beispiel: Text aus einem Bild extrahieren (z.B. von einer URL)
        // String imageUrl = "https://example.com/image.jpg";
        // extractTextFromImageUrl(imageUrl);

        // Oder: Text aus einem lokalen Bild extrahieren (z.B. aus res/drawable)
        // extractTextFromResource(R.drawable.sample_image);
    }

    /**
     * Extrahiert Text aus einem Bild von einer URL
     */
    private void extractTextFromImageUrl(String imageUrl) {
        Toast.makeText(this, "Text wird extrahiert...", Toast.LENGTH_SHORT).show();
        
        refillBot.extractTextFromUrl(imageUrl)
                .thenAccept(text -> {
                    runOnUiThread(() -> {
                        displayResult(text);
                        // Zusätzliche Analyse
                        analyzeText(text);
                    });
                })
                .exceptionally(e -> {
                    runOnUiThread(() -> {
                        Log.e(TAG, "Fehler bei Texterkennung", e);
                        Toast.makeText(this, "Fehler: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        textViewResult.setText("Fehler bei der Texterkennung");
                    });
                    return null;
                });
    }

    /**
     * Extrahiert Text aus einem lokalen Resource-Bild
     */
    private void extractTextFromResource(int resourceId) {
        Bitmap bitmap = BitmapFactory.decodeResource(getResources(), resourceId);
        if (bitmap != null) {
            imageViewPreview.setImageBitmap(bitmap);
            
            refillBot.extractTextFromImage(bitmap)
                    .thenAccept(text -> {
                        runOnUiThread(() -> {
                            displayResult(text);
                            analyzeText(text);
                        });
                    })
                    .exceptionally(e -> {
                        runOnUiThread(() -> {
                            Log.e(TAG, "Fehler bei Texterkennung", e);
                            Toast.makeText(this, "Fehler: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                        return null;
                    });
        }
    }

    /**
     * Extrahiert Text aus einem Byte-Array (z.B. von einem API-Call)
     */
    private void extractTextFromBytes(byte[] imageData) {
        refillBot.extractTextFromBytes(imageData)
                .thenAccept(text -> {
                    runOnUiThread(() -> displayResult(text));
                })
                .exceptionally(e -> {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Fehler: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                    return null;
                });
    }

    /**
     * Zeigt das Ergebnis im TextView an
     */
    private void displayResult(String text) {
        if (text != null && !text.isEmpty()) {
            textViewResult.setText(text);
            Toast.makeText(this, "Text extrahiert: " + text.length() + " Zeichen", Toast.LENGTH_SHORT).show();
        } else {
            textViewResult.setText("Kein Text gefunden");
        }
    }

    /**
     * Analysiert den extrahierten Text (Preise, Produkte)
     */
    private void analyzeText(String text) {
        if (text == null || text.isEmpty()) return;

        // Preise extrahieren
        List<String> prices = refillBot.extractPrices(text);
        if (!prices.isEmpty()) {
            Log.d(TAG, "Gefundene Preise: " + prices);
            // Hier können Sie die Preise weiterverarbeiten
        }

        // Produktnamen extrahieren
        List<String> products = refillBot.extractProductNames(text);
        if (!products.isEmpty()) {
            Log.d(TAG, "Gefundene Produkte: " + products);
            // Hier können Sie die Produkte weiterverarbeiten
        }
    }

    /**
     * Lädt ein Bild von einer URL herunter und zeigt es an
     */
    private void downloadAndShowImage(String imageUrl) {
        new Thread(() -> {
            try {
                URL url = new URL(imageUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    try (InputStream inputStream = connection.getInputStream()) {
                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                        }
                        byte[] imageData = outputStream.toByteArray();
                        Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
                        
                        runOnUiThread(() -> {
                            imageViewPreview.setImageBitmap(bitmap);
                            // Text aus dem Bild extrahieren
                            extractTextFromBytes(imageData);
                        });
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Fehler beim Herunterladen des Bildes", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Bild konnte nicht geladen werden", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Ressourcen freigeben
        if (refillBot != null) {
            refillBot.close();
        }
    }
}
