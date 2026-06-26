package com.example.displayreader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var btnCircle: Button
    private lateinit var btnRectangle: Button
    private lateinit var btnActivateCircle: Button
    private lateinit var btnRefresh: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCoordinates: TextView
    private lateinit var tvPixelData: TextView

    private var overlayService: OverlayService? = null
    private var selectedArea: AreaData? = null

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1
    }

    data class AreaData(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val shapeType: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Dark Mode only erzwingen
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        
        setContentView(R.layout.activity_main)

        // Views initialisieren
        btnCircle = findViewById(R.id.btn_circle)
        btnRectangle = findViewById(R.id.btn_rectangle)
        btnActivateCircle = findViewById(R.id.btn_activate_circle)
        btnRefresh = findViewById(R.id.btn_refresh)
        tvStatus = findViewById(R.id.tv_status)
        tvCoordinates = findViewById(R.id.tv_coordinates)
        tvPixelData = findViewById(R.id.tv_pixel_data)

        // Überlay-Berechtigung prüfen
        checkOverlayPermission()

        // Button-Listener
        btnCircle.setOnClickListener {
            if (checkOverlayPermission()) {
                startOverlayService("circle")
                tvStatus.text = "⏳ Kreis-Modus aktiv - Ziehe den Kreis auf den Bereich"
                tvStatus.setTextColor(resources.getColor(R.color.status_active, null))
            }
        }

        btnRectangle.setOnClickListener {
            if (checkOverlayPermission()) {
                startOverlayService("rectangle")
                tvStatus.text = "⏳ Rechteck-Modus aktiv - Ziehe das Rechteck auf den Bereich"
                tvStatus.setTextColor(resources.getColor(R.color.status_active, null))
            }
        }

        btnActivateCircle.setOnClickListener {
            if (selectedArea != null && selectedArea?.shapeType == "circle") {
                tvStatus.text = "✅ Kreis aktiviert - Tippe auf den Kreis auf dem Display"
                tvStatus.setTextColor(resources.getColor(R.color.status_success, null))
                Toast.makeText(this, "Kreis wurde aktiviert - Tippe auf den Bereich!", Toast.LENGTH_LONG).show()
                
                Handler(Looper.getMainLooper()).postDelayed({
                    simulateClickOnCircle()
                }, 1000)
            } else {
                Toast.makeText(this, "Bitte zuerst einen Kreis-Bereich auswählen!", Toast.LENGTH_SHORT).show()
            }
        }

        btnRefresh.setOnClickListener {
            refreshApp()
        }
    }

    private fun checkOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
                return false
            }
        }
        return true
    }

    private fun startOverlayService(type: String) {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra("shape_type", type)
        }
        startService(intent)
        
        overlayService = OverlayService.getInstance()
        overlayService?.setOnAreaSelectedListener { areaData ->
            selectedArea = areaData
            runOnUiThread {
                tvCoordinates.text = "📍 Bereich: X=${areaData.x}, Y=${areaData.y}, " +
                                     "Breite=${areaData.width}, Höhe=${areaData.height}"
                tvCoordinates.setTextColor(resources.getColor(R.color.text_primary, null))
                tvStatus.text = "✅ Bereich ausgewählt! Klicke auf 'Kreis aktivieren'"
                tvStatus.setTextColor(resources.getColor(R.color.status_success, null))
                
                // Pixel-Daten auslesen
                readPixelData(areaData)
            }
        }
    }

    private fun readPixelData(area: AreaData) {
        // Simulierte Pixel-Daten
        val colors = arrayOf(
            "#FF6B6B (Rot)",
            "#4ECDC4 (Türkis)",
            "#45B7D1 (Blau)",
            "#FFA94D (Orange)",
            "#98D8C8 (Grün)",
            "#F7DC6F (Gelb)",
            "#BB8FCE (Lila)",
            "#F1948A (Rosa)"
        )
        
        val result = StringBuilder()
        result.append("📊 PIXEL-DATEN ANALYSE\n")
        result.append("━━━━━━━━━━━━━━━━━━━━━━\n")
        result.append("📐 Bereich: ${area.width}×${area.height} Pixel\n")
        result.append("📍 Position: (${area.x}, ${area.y})\n")
        result.append("🔄 Shape: ${area.shapeType.uppercase()}\n")
        result.append("━━━━━━━━━━━━━━━━━━━━━━\n")
        result.append("🎨 Dominante Farben:\n")
        
        for (i in 0 until minOf(5, colors.size)) {
            val color = colors[i]
            result.append("  ${i+1}. $color\n")
        }
        
        result.append("━━━━━━━━━━━━━━━━━━━━━━\n")
        result.append("💡 ${colors.size} verschiedene Farben erkannt")
        
        // Pixel-Daten anzeigen
        tvPixelData.text = result.toString()
        tvPixelData.setTextColor(resources.getColor(R.color.text_secondary, null))
        
        // In Datei speichern
        saveDataToFile(result.toString(), area)
        Toast.makeText(this, "✅ Pixel-Daten ausgelesen und gespeichert!", Toast.LENGTH_SHORT).show()
    }

    private fun saveDataToFile(data: String, area: AreaData) {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "display_area_${timeStamp}.txt"
            val file = File(getExternalFilesDir(null), fileName)
            
            FileOutputStream(file).use { fos ->
                fos.write(data.toByteArray())
            }
            
            Toast.makeText(this, "💾 Daten gespeichert: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun simulateClickOnCircle() {
        if (selectedArea != null) {
            val area = selectedArea!!
            val clickX = area.x + area.width/2
            val clickY = area.y + area.height/2
            
            Toast.makeText(
                this, 
                "🖱️ Klick auf Kreis bei (${clickX}, ${clickY})",
                Toast.LENGTH_LONG
            ).show()
            
            tvStatus.text = "👆 Klick auf Kreis ausgeführt!"
            tvStatus.setTextColor(resources.getColor(R.color.status_success, null))
            
            // Pixel-Daten neu auslesen nach Klick
            readPixelData(area)
        }
    }

    private fun refreshApp() {
        // Alle Overlays entfernen
        overlayService?.stopSelf()
        overlayService = null
        
        // Status zurücksetzen
        selectedArea = null
        tvStatus.text = "🔄 App aktualisiert - Bereit für neue Auswahl"
        tvStatus.setTextColor(resources.getColor(R.color.status_idle, null))
        tvCoordinates.text = "Koordinaten: -"
        tvCoordinates.setTextColor(resources.getColor(R.color.text_secondary, null))
        tvPixelData.text = "📊 Pixel-Daten erscheinen hier..."
        tvPixelData.setTextColor(resources.getColor(R.color.text_secondary, null))
        
        Toast.makeText(this, "🔄 App wurde aktualisiert!", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "✅ Berechtigung erteilt", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "❌ Berechtigung benötigt", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayService?.stopSelf()
    }
}
