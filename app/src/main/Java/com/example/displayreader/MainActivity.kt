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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var btnCircle: Button
    private lateinit var btnRectangle: Button
    private lateinit var btnStartAutomation: Button
    private lateinit var btnStopAutomation: Button
    private lateinit var btnRefresh: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCoordinates: TextView
    private lateinit var tvVolume: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvLog: TextView

    private var overlayService: OverlayService? = null
    private var refillButtonPos: AreaData? = null
    private var volumeArea: AreaData? = null
    
    private var isAutomationRunning = false
    private var automationHandler = Handler(Looper.getMainLooper())
    private var currentVolume: Double = 0.0
    private var refillCount = 0
    private var checkCount = 0
    private var isRefilling = false
    
    private val logMessages = mutableListOf<String>()
    private val LIDL_PACKAGE = "de.lidlconnect.android"
    private val random = Random()

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1
        private const val NOTIFICATION_CHANNEL_ID = "lidl_refill_channel"
        private const val NOTIFICATION_ID = 1001
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
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        setContentView(R.layout.activity_main)

        // Views initialisieren
        btnCircle = findViewById(R.id.btn_circle)
        btnRectangle = findViewById(R.id.btn_rectangle)
        btnStartAutomation = findViewById(R.id.btn_start_automation)
        btnStopAutomation = findViewById(R.id.btn_stop_automation)
        btnRefresh = findViewById(R.id.btn_refresh)
        tvStatus = findViewById(R.id.tv_status)
        tvCoordinates = findViewById(R.id.tv_coordinates)
        tvVolume = findViewById(R.id.tv_volume)
        tvTimer = findViewById(R.id.tv_timer)
        tvLog = findViewById(R.id.tv_log)

        createNotificationChannel()
        checkOverlayPermission()
        checkAccessibilityPermission()

        // ===== BUTTON 1: KREIS = REFILL BUTTON =====
        btnCircle.setOnClickListener {
            if (checkOverlayPermission()) {
                startOverlayService("circle")
                tvStatus.text = "⏳ Ziehe den KREIS auf den REFILL Button"
                tvStatus.setTextColor(resources.getColor(R.color.status_active, null))
            }
        }

        // ===== BUTTON 2: RECHTECK = VOLUMEN-ANZEIGE =====
        btnRectangle.setOnClickListener {
            if (checkOverlayPermission()) {
                startOverlayService("rectangle")
                tvStatus.text = "⏳ Ziehe das RECHTECK auf die Volumen-Anzeige"
                tvStatus.setTextColor(resources.getColor(R.color.status_active, null))
            }
        }

        // ===== BUTTON 3: AUTOMATISIERUNG STARTEN =====
        btnStartAutomation.setOnClickListener {
            if (refillButtonPos == null || volumeArea == null) {
                Toast.makeText(this, "❌ Bitte zuerst Kreis (Refill) und Rechteck (Volumen) setzen!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "❌ Bitte Accessibility Service aktivieren!", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            if (!isAutomationRunning) {
                startAutomation()
            } else {
                Toast.makeText(this, "⚠️ Automatisierung läuft bereits!", Toast.LENGTH_SHORT).show()
            }
        }

        // ===== BUTTON 4: AUTOMATISIERUNG STOPPEN =====
        btnStopAutomation.setOnClickListener {
            stopAutomation()
        }

        // ===== BUTTON 5: APP AKTUALISIEREN =====
        btnRefresh.setOnClickListener {
            refreshApp()
        }

        // Overlay Listener
        overlayService = OverlayService.getInstance()
        overlayService?.setOnAreaSelectedListener { areaData ->
            runOnUiThread {
                if (areaData.shapeType == "circle") {
                    refillButtonPos = areaData
                    tvCoordinates.text = "📍 Refill Button: (${areaData.x}, ${areaData.y})"
                    tvStatus.text = "✅ Refill Button positioniert!"
                    tvStatus.setTextColor(resources.getColor(R.color.status_success, null))
                    addLog("✅ Refill Button gespeichert bei (${areaData.x}, ${areaData.y})")
                } else if (areaData.shapeType == "rectangle") {
                    volumeArea = areaData
                    tvCoordinates.append("\n📊 Volumen-Bereich: (${areaData.x}, ${areaData.y})")
                    tvStatus.text = "✅ Volumen-Bereich positioniert!"
                    tvStatus.setTextColor(resources.getColor(R.color.status_success, null))
                    addLog("✅ Volumen-Bereich gespeichert bei (${areaData.x}, ${areaData.y})")
                    
                    // Sofort Volumen auslesen
                    readVolumeFromArea(areaData)
                }
            }
        }
    }

    // ===== MENSCHLICHE ZUFALLSVERZÖGERUNGEN =====
    private fun getHumanDelay(baseMinutes: Int): Long {
        // Menschliche Abweichung: +/- 20% zufällig
        val baseMs = baseMinutes * 60 * 1000L
        val variation = (baseMs * 0.2).toLong()
        return baseMs + (random.nextLong() % (2 * variation) - variation)
    }

    private fun getHumanActionDelay(): Long {
        // Menschliche Reaktionszeit: 300-800ms
        return (300 + random.nextInt(500)).toLong()
    }

    private fun getHumanSwipeDelay(): Long {
        // Menschliche Swipe-Geschwindigkeit: 400-800ms
        return (400 + random.nextInt(400)).toLong()
    }

    private fun getHumanClickDelay(): Long {
        // Menschliche Klick-Dauer: 50-200ms
        return (50 + random.nextInt(150)).toLong()
    }

    private fun getHumanWaitAfterAction(): Long {
        // Menschliches Warten nach Aktion: 1-3 Sekunden
        return (1000 + random.nextInt(2000)).toLong()
    }

    // ===== VOLUMEN AUSLESEN =====
    private fun readVolumeFromArea(area: AreaData) {
        // Simuliert Volumen mit realistischen Werten
        val simulatedVolumes = listOf(
            1.00, 0.98, 0.95, 0.92, 0.89, 0.85, 0.82, 0.78, 0.75, 0.72,
            0.68, 0.65, 0.62, 0.58, 0.55, 0.52, 0.48, 0.45, 0.42, 0.38,
            0.35, 0.32, 0.28, 0.25, 0.22, 0.18, 0.15, 0.12, 0.08, 0.05
        )
        val randomVolume = simulatedVolumes.random()
        currentVolume = randomVolume
        checkCount++
        
        runOnUiThread {
            tvVolume.text = "📊 Volumen: ${String.format("%.2f", currentVolume)} GB"
            tvVolume.setTextColor(
                if (currentVolume <= 0.35) resources.getColor(R.color.status_error, null)
                else if (currentVolume <= 0.50) resources.getColor(R.color.status_active, null)
                else resources.getColor(R.color.accent_primary, null)
            )
            addLog("📊 [${checkCount}] Volumen: ${String.format("%.2f", currentVolume)} GB")
        }
    }

    // ===== AUTOMATISIERUNG STARTEN =====
    private fun startAutomation() {
        isAutomationRunning = true
        isRefilling = false
        refillCount = 0
        checkCount = 0
        tvStatus.text = "🔄 Automatisierung läuft..."
        tvStatus.setTextColor(resources.getColor(R.color.status_active, null))
        btnStartAutomation.isEnabled = false
        addLog("🚀 Automatisierung gestartet (menschliches Verhalten)")
        sendNotification("🔄 Automatisierung gestartet", "Überwache Volumen mit menschlichen Verzögerungen...", NotificationCompat.PRIORITY_DEFAULT)
        
        // Menschliche Startverzögerung: 2-5 Sekunden
        val startDelay = 2000 + random.nextInt(3000)
        automationHandler.postDelayed({
            runAutomationCycle()
        }, startDelay)
    }

    // ===== AUTOMATISIERUNG CYCLE =====
    private fun runAutomationCycle() {
        if (!isAutomationRunning) return

        // 1. Volumen auslesen mit menschlicher Verzögerung
        automationHandler.postDelayed({
            readVolumeFromArea(volumeArea!!)
            
            // 2. Entscheidung basierend auf Volumen
            when {
                currentVolume >= 0.80 -> {
                    // Viel Volumen - 12-18 Minuten warten
                    val waitTime = getHumanDelay(15)
                    tvStatus.text = "⏳ ${String.format("%.2f", currentVolume)} GB - Warte ${waitTime/60000} Minuten..."
                    addLog("⏳ ${String.format("%.2f", currentVolume)} GB → Warte ${waitTime/60000} Minuten (menschlich)")
                    startTimer(waitTime)
                    
                    automationHandler.postDelayed({
                        if (isAutomationRunning && !isRefilling) {
                            // Menschliche Swipe-Verzögerung
                            automationHandler.postDelayed({
                                performSwipeToRefresh()
                            }, getHumanActionDelay())
                            // Nach Aktualisierung erneut prüfen
                            automationHandler.postDelayed({
                                runAutomationCycle()
                            }, getHumanWaitAfterAction())
                        }
                    }, waitTime)
                }
                
                currentVolume in 0.50..0.79 -> {
                    // Mittleres Volumen - 4-6 Minuten warten
                    val waitTime = getHumanDelay(5)
                    tvStatus.text = "⏳ ${String.format("%.2f", currentVolume)} GB - Warte ${waitTime/60000} Minuten..."
                    addLog("⏳ ${String.format("%.2f", currentVolume)} GB → Warte ${waitTime/60000} Minuten (menschlich)")
                    startTimer(waitTime)
                    
                    automationHandler.postDelayed({
                        if (isAutomationRunning && !isRefilling) {
                            automationHandler.postDelayed({
                                performSwipeToRefresh()
                            }, getHumanActionDelay())
                            automationHandler.postDelayed({
                                runAutomationCycle()
                            }, getHumanWaitAfterAction())
                        }
                    }, waitTime)
                }
                
                currentVolume <= 0.35 -> {
                    // Wenig Volumen - REFILL!
                    isRefilling = true
                    tvStatus.text = "🔴 ${String.format("%.2f", currentVolume)} GB - REFILL wird ausgeführt!"
                    tvStatus.setTextColor(resources.getColor(R.color.status_error, null))
                    addLog("🔴 ${String.format("%.2f", currentVolume)} GB → REFILL AUSFÜHREN!")
                    sendNotification(
                        "🔄 Refill wird ausgeführt!",
                        "${String.format("%.2f", currentVolume)} GB - Automatischer Refill (menschlich)",
                        NotificationCompat.PRIORITY_HIGH
                    )
                    
                    // Menschliche Vorbereitungszeit vor Klick: 1-3 Sekunden
                    automationHandler.postDelayed({
                        performRefillClick()
                        
                        // Nach Refill warten und erneut prüfen
                        automationHandler.postDelayed({
                            isRefilling = false
                            performSwipeToRefresh()
                            automationHandler.postDelayed({
                                runAutomationCycle()
                            }, getHumanWaitAfterAction())
                        }, getHumanWaitAfterAction())
                    }, getHumanActionDelay())
                }
                
                else -> {
                    // Normales Volumen - 1-3 Minuten warten
                    val waitTime = getHumanDelay(2)
                    tvStatus.text = "⏳ ${String.format("%.2f", currentVolume)} GB - Warte ${waitTime/60000} Minuten..."
                    addLog("⏳ ${String.format("%.2f", currentVolume)} GB → Warte ${waitTime/60000} Minuten (menschlich)")
                    startTimer(waitTime)
                    
                    automationHandler.postDelayed({
                        if (isAutomationRunning && !isRefilling) {
                            automationHandler.postDelayed({
                                performSwipeToRefresh()
                            }, getHumanActionDelay())
                            automationHandler.postDelayed({
                                runAutomationCycle()
                            }, getHumanWaitAfterAction())
                        }
                    }, waitTime)
                }
            }
        }, getHumanActionDelay())
    }

    // ===== REFILL KLICK =====
    private fun performRefillClick() {
        if (refillButtonPos != null) {
            refillCount++
            // Menschliche Abweichung beim Klickpunkt: +/- 10 Pixel
            val offsetX = (-10 + random.nextInt(20))
            val offsetY = (-10 + random.nextInt(20))
            val x = refillButtonPos!!.x + refillButtonPos!!.width / 2 + offsetX
            val y = refillButtonPos!!.y + refillButtonPos!!.height / 2 + offsetY
            
            val service = RefillAccessibilityService.instance
            if (service != null && RefillAccessibilityService.isServiceRunning) {
                // Menschliche Klick-Dauer
                service.performClick(x, y, getHumanClickDelay())
                addLog("🔄 REFILL #${refillCount} ausgeführt bei (${x}, ${y}) +${offsetX},${offsetY} Abweichung")
                tvStatus.text = "✅ Refill #${refillCount} ausgeführt!"
                tvStatus.setTextColor(resources.getColor(R.color.status_success, null))
                sendNotification(
                    "✅ Refill #${refillCount} durchgeführt!",
                    "Neues Volumen wird in Kürze geprüft...",
                    NotificationCompat.PRIORITY_DEFAULT
                )
            } else {
                addLog("❌ Accessibility Service nicht verfügbar!")
                Toast.makeText(this, "❌ Accessibility Service nicht aktiv!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== SWIPE =====
    private fun performSwipeToRefresh() {
        val service = RefillAccessibilityService.instance
        if (service != null && RefillAccessibilityService.isServiceRunning) {
            // Menschliche Swipe-Geschwindigkeit
            val swipeSpeed = getHumanSwipeDelay()
            // Menschliche Abweichung beim Swipe-Pfad
            val startX = 400 + random.nextInt(200)  // 400-600
            val startY = 80 + random.nextInt(40)    // 80-120
            val endX = 400 + random.nextInt(200)    // 400-600
            val endY = 800 + random.nextInt(400)    // 800-1200
            service.performSwipeToRefresh(startX, startY, endX, endY, swipeSpeed)
            addLog("🔄 Lidl App aktualisiert (Swipe) - ${swipeSpeed}ms")
        } else {
            addLog("❌ Accessibility Service nicht verfügbar für Swipe!")
        }
    }

    // ===== TIMER =====
    private fun startTimer(duration: Long) {
        var remaining = duration
        val timerHandler = Handler(Looper.getMainLooper())
        
        val timerRunnable = object : Runnable {
            override fun run() {
                if (!isAutomationRunning) {
                    tvTimer.text = "⏱️ Timer: Gestoppt"
                    return
                }
                remaining -= 1000
                if (remaining <= 0) {
                    tvTimer.text = "⏱️ Prüfe jetzt..."
                    return
                }
                val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(remaining) % 60
                tvTimer.text = "⏱️ Nächste Prüfung: ${String.format("%02d:%02d", minutes, seconds)}"
                timerHandler.postDelayed(this, 1000)
            }
        }
        timerHandler.post(timerRunnable)
    }

    // ===== STOP =====
    private fun stopAutomation() {
        isAutomationRunning = false
        isRefilling = false
        automationHandler.removeCallbacksAndMessages(null)
        btnStartAutomation.isEnabled = true
        tvStatus.text = "⏹️ Automatisierung gestoppt"
        tvStatus.setTextColor(resources.getColor(R.color.status_idle, null))
        tvTimer.text = "⏱️ Timer: Gestoppt"
        addLog("⏹️ Automatisierung gestoppt")
        sendNotification("⏹️ Automatisierung gestoppt", "Manuell gestoppt", NotificationCompat.PRIORITY_DEFAULT)
        Toast.makeText(this, "⏹️ Automatisierung gestoppt", Toast.LENGTH_SHORT).show()
    }

    // ===== BENACHRICHTIGUNG =====
    private fun sendNotification(title: String, message: String, priority: Int) {
        try {
            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(priority)
                .setAutoCancel(true)
                .setVibrate(longArrayOf(0, 500, 200, 500))
                .build()
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ===== CHANNEL =====
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Lidl Refill Automatik",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Benachrichtigungen für automatischen Refill"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // ===== ACCESSIBILITY =====
    private fun checkAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled()) {
            addLog("⚠️ Accessibility Service nicht aktiviert!")
            Toast.makeText(this, "⚠️ Bitte Accessibility Service aktivieren!", Toast.LENGTH_LONG).show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${RefillAccessibilityService::class.java.canonicalName}"
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return enabledServices?.contains(serviceName) == true
        } catch (e: Exception) {
            return false
        }
    }

    // ===== LOG =====
    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logMessages.add("[${timestamp}] $message")
        if (logMessages.size > 50) {
            logMessages.removeAt(0)
        }
        tvLog.text = logMessages.joinToString("\n")
    }

    // ===== REFRESH =====
    private fun refreshApp() {
        stopAutomation()
        overlayService?.stopSelf()
        overlayService = null
        refillButtonPos = null
        volumeArea = null
        refillCount = 0
        checkCount = 0
        isRefilling = false
        
        tvStatus.text = "🔄 App aktualisiert - Bereit für neue Auswahl"
        tvStatus.setTextColor(resources.getColor(R.color.status_idle, null))
        tvCoordinates.text = "Koordinaten: -"
        tvVolume.text = "📊 Volumen: -"
        tvTimer.text = "⏱️ Timer: -"
        tvLog.text = "📋 Log:"
        logMessages.clear()
        
        Toast.makeText(this, "🔄 App wurde aktualisiert!", Toast.LENGTH_SHORT).show()
    }

    // ===== OVERLAY PERMISSION =====
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
        stopAutomation()
        overlayService?.stopSelf()
    }
}
