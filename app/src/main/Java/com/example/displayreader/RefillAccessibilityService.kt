package com.example.displayreader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import kotlin.random.Random

class RefillAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RefillAccessibilityService? = null
        var isServiceRunning = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nicht benötigt
    }

    override fun onInterrupt() {
        isServiceRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isServiceRunning = false
    }

    // ===== MENSCHLICHER SWIPE =====
    fun performSwipeToRefresh(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long) {
        if (!isServiceRunning) return
        
        try {
            val path = Path()
            // Menschlicher Swipe: leichte Kurve
            path.moveTo(startX.toFloat(), startY.toFloat())
            
            // Leichte Biegung für menschlichen Swipe
            val midX = (startX + endX) / 2f + Random.nextInt(-30, 30)
            val midY = (startY + endY) / 2f + Random.nextInt(-30, 30)
            path.quadTo(midX, midY, endX.toFloat(), endY.toFloat())
            
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            
            val gesture = gestureBuilder.build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ===== MENSCHLICHER KLICK =====
    fun performClick(x: Int, y: Int, duration: Long) {
        if (!isServiceRunning) return
        
        try {
            val path = Path()
            path.moveTo(x.toFloat(), y.toFloat())
            
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            
            val gesture = gestureBuilder.build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
