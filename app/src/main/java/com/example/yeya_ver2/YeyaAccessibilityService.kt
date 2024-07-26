package com.example.yeya_ver2

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class YeyaAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                UICapture.captureUIElements(rootInActiveWindow)
            }
        }
    }

    override fun onInterrupt() {
        // We don't need to implement this for now
    }

    override fun onServiceConnected() {
        Log.d("YeyaAccessibilityService", "Service connected")
    }
}