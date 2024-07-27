package com.example.yeya_ver2

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

class YeyaAccessibilityService : AccessibilityService() {
    private val TAG = "YeyaAccessibilityService"
    private var currentCaptureId: Int = 0

    companion object {
        private var instance: YeyaAccessibilityService? = null

        fun getInstance(): YeyaAccessibilityService? {
            return instance
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        Log.d(TAG, "YeyaAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            when (it.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    val capturedUI = UICapture.captureUIElements(rootInActiveWindow)
                    if (capturedUI != null) {
                        currentCaptureId = capturedUI.optInt("captureID", 0)
                        Log.d(TAG, "onAccessibilityEvent: Captured UI with CaptureID-$currentCaptureId")
                    } else {
                        Log.d(TAG, "onAccessibilityEvent: Failed to capture UI")
                    }
                }
                else -> {
                    // Handle other event types if needed
                }
            }
        }
    }

    override fun onInterrupt() {
        // We don't need to implement this for now
    }

    fun performClick(id: Int) {
        Log.d(TAG, "Attempting to click on element with id: $id, CaptureID: $currentCaptureId")
        val rootNode = rootInActiveWindow ?: return
        val targetNode = findNodeById(rootNode, id)
        targetNode?.let {
            val clickResult = it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Click performed on element with id $id, CaptureID: $currentCaptureId, result: $clickResult")
        } ?: Log.e(TAG, "Target node with id $id not found for CaptureID: $currentCaptureId")
    }

    private fun findNodeById(node: AccessibilityNodeInfo, targetId: Int): AccessibilityNodeInfo? {
        Log.d(TAG, "findNodeById: Searching for id $targetId in CaptureID-$currentCaptureId")
        if (node.viewIdResourceName?.endsWith("/$targetId") == true) {
            Log.d(TAG, "findNodeById: Found node with id $targetId in CaptureID-$currentCaptureId")
            return node
        }
        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i)
            val result = findNodeById(childNode, targetId)
            if (result != null) {
                return result
            }
            childNode.recycle()
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}