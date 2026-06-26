package com.example.displayreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.displayreader.MainActivity.AreaData
import kotlin.random.Random

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout
    private var shapeType: String = "circle"
    private var onAreaSelectedListener: ((AreaData) -> Unit)? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var isDragging = false
    private var areaData: AreaData? = null
    private val random = Random

    companion object {
        private var instance: OverlayService? = null
        private const val CHANNEL_ID = "OverlayServiceChannel"
        private const val NOTIFICATION_ID = 1

        fun getInstance(): OverlayService? = instance
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        shapeType = intent?.getStringExtra("shape_type") ?: "circle"
        createOverlayView()
        return START_STICKY
    }

    fun setOnAreaSelectedListener(listener: (AreaData) -> Unit) {
        onAreaSelectedListener = listener
    }

    private fun createOverlayView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_circle, null) as LinearLayout
        
        val shapeView = overlayView.findViewById<View>(R.id.shape_view)
        val size = if (shapeType == "circle") 160 else 200
        
        val layoutParams = shapeView.layoutParams
        layoutParams.width = size
        layoutParams.height = if (shapeType == "circle") size else (size * 0.6).toInt()
        shapeView.layoutParams = layoutParams
        
        if (shapeType == "circle") {
            shapeView.setBackgroundResource(R.drawable.circle_shape)
        } else {
            shapeView.setBackgroundResource(R.drawable.rectangle_shape)
        }

        val tvStatus = overlayView.findViewById<TextView>(R.id.tv_overlay_status)
        tvStatus.text = if (shapeType == "circle") "🔵 REFILL" else "🟩 VOLUMEN"
        tvStatus.setTextColor(Color.WHITE)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100 + random.nextInt(50)
        params.y = 100 + random.nextInt(50)
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var lastUpdateTime = 0L

        overlayView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = true
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    lastUpdateTime = System.currentTimeMillis()
                    
                    tvStatus.text = if (shapeType == "circle") "🔵 ZIEHEN..." else "🟩 ZIEHEN..."
                    true
                }
                
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    
                    params.x = initialX + deltaX
                    params.y = initialY + deltaY
                    
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUpdateTime > 100) {
                        val tvCoords = overlayView.findViewById<TextView>(R.id.tv_coords)
                        tvCoords.text = "📍 ${params.x}, ${params.y}"
                        lastUpdateTime = currentTime
                    }
                    
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    
                    val width = if (shapeType == "circle") 160 else 200
                    val height = if (shapeType == "circle") 160 else 120
                    
                    areaData = AreaData(
                        x = params.x,
                        y = params.y,
                        width = width,
                        height = height,
                        shapeType = shapeType
                    )
                    
                    areaData?.let { data ->
                        onAreaSelectedListener?.invoke(data)
                    }
                    
                    tvStatus.text = if (shapeType == "circle") "✅ FIXIERT" else "✅ FIXIERT"
                    tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                    
                    Toast.makeText(
                        this,
                        "✅ ${if (shapeType == "circle") "Refill Button" else "Volumen-Bereich"} ausgewählt!",
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    // Menschliche Verzögerung vor dem Schließen: 2-4 Sekunden
                    handler.postDelayed({
                        if (isDragging) return@postDelayed
                        stopSelf()
                    }, (2000 + random.nextInt(2000)).toLong())
                    
                    true
                }
                
                MotionEvent.ACTION_OUTSIDE -> {
                    false
                }
                
                else -> false
            }
        }

        val closeBtn = overlayView.findViewById<Button>(R.id.btn_close)
        closeBtn.setOnClickListener {
            stopSelf()
        }

        windowManager.addView(overlayView, params)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Wird für Display-Overlays benötigt"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lidl Refill")
            .setContentText("Overlay ist aktiv...")
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (::overlayView.isInitialized) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
