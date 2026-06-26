package com.example.displayreader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        isServiceRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isServiceRunning = false
    }

    fun performSwipeToRefresh(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long) {
        if (!isServiceRunning) return
        
        try {
            val path = Path()
            path.moveTo(startX.toFloat(), startY.toFloat())
            
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
