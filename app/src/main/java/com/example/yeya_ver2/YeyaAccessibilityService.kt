package com.example.yeya_ver2

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import android.graphics.Path
import android.view.MotionEvent

class YeyaAccessibilityService : AccessibilityService() {
    private val TAG = "YeyaAccessibilityService"
    private var currentCaptureId: Int = 0
    private var currentGesture: GestureDescription? = null
    private val gestureLock = Any()

    companion object {
        private var instance: YeyaAccessibilityService? = null

        fun getInstance(): YeyaAccessibilityService? {
            Log.d("YeyaAccessibilityService", "getInstance called, instance is ${if (instance == null) "null" else "not null"}")
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
        // We don't need to implement this for now..
    }

    fun performClick(targetId: Int) {
        Log.d(TAG, "Attempting to click on element with id: $targetId, CaptureID: $currentCaptureId")
        val rootNode = rootInActiveWindow ?: return
        findAndClickTargetNode(rootNode, targetId)
    }

    private fun findAndClickTargetNode(rootNode: AccessibilityNodeInfo, targetId: Int) {
        var currentId = 0
        processNode(rootNode, targetId, currentId)
    }

    private fun processNode(node: AccessibilityNodeInfo, targetId: Int, currentId: Int): Int {
        var updatedId = currentId
        val text = node.text?.toString() ?: ""
        val contentDescription = node.contentDescription?.toString() ?: ""

        if (node.isClickable || text.isNotEmpty() || contentDescription.isNotEmpty()) {
            Log.d(TAG, "Founding target -> id : $updatedId, text : $text contentDescription : $contentDescription, isClickable: ${node.isClickable}")

            if (updatedId == targetId) {
                Log.d(TAG, "Found target node with id $targetId")
                Log.d(TAG, "Found target node's text: $text")
                Log.d(TAG, "Found target node's contentDescription: $contentDescription")
                Log.d(TAG, "Found target node's isClickable: ${node.isClickable}")

                val clickResult = if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    findClickableParent(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                }

                Log.d(TAG, "Click performed on element with id $targetId, result: $clickResult")
                return -1  // Signal that we've found and clicked the target
            }
            updatedId++
        }

        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i) ?: continue
            val result = processNode(childNode, targetId, updatedId)
            if (result == -1) return -1  // Target found and clicked, propagate the signal
            updatedId = result
            childNode.recycle()
        }

        return updatedId
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                return parent
            }
            val newParent = parent.parent
            parent.recycle()
            parent = newParent
        }
        return null
    }

    private val gestureCallback = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            Log.d(TAG, "Gesture completed: $gestureDescription")
            synchronized(gestureLock) {
                currentGesture = null
            }
        }
        override fun onCancelled(gestureDescription: GestureDescription?) {
            Log.d(TAG, "Gesture cancelled: $gestureDescription")
            synchronized(gestureLock) {
                currentGesture = null
            }
        }
    }

    fun simulateTouch(x: Int, y: Int, action: Int) {
        Log.d(TAG, "Simulating touch: action=$action, x=$x, y=$y")

        synchronized(gestureLock) {
            if (currentGesture != null) {
                Log.d(TAG, "Previous gesture still in progress, skipping")
                return
            }

            val path = Path()
            val gestureBuilder = GestureDescription.Builder()
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    path.moveTo(x.toFloat(), y.toFloat())
                    val pressStroke = GestureDescription.StrokeDescription(path, 0, 50)
                    gestureBuilder.addStroke(pressStroke)
                }
                MotionEvent.ACTION_MOVE -> {
                    path.moveTo(x.toFloat(), y.toFloat())
                    path.lineTo(x.toFloat(), y.toFloat())
                    val moveStroke = GestureDescription.StrokeDescription(path, 0, 1)
                    gestureBuilder.addStroke(moveStroke)
                }
                MotionEvent.ACTION_UP -> {
                    path.moveTo(x.toFloat(), y.toFloat())
                    val releaseStroke = GestureDescription.StrokeDescription(path, 0, 1)
                    gestureBuilder.addStroke(releaseStroke)
                }
            }

            currentGesture = gestureBuilder.build()
            val result = dispatchGesture(currentGesture!!, gestureCallback, null)
            Log.d(TAG, "dispatchGesture result: $result")
        }
    }

    fun simulateClick(x: Int, y: Int) {
        Log.d(TAG, "Simulating click at ($x, $y)")

        synchronized(gestureLock) {
            if (currentGesture != null) {
                Log.d(TAG, "Previous gesture still in progress, skipping")
                return
            }

            val clickPath = Path()
            clickPath.moveTo(x.toFloat(), y.toFloat())

            val clickBuilder = GestureDescription.Builder()
            val clickStroke = GestureDescription.StrokeDescription(clickPath, 0L, 150L) // Specify Long type
            clickBuilder.addStroke(clickStroke)

            currentGesture = clickBuilder.build()
            val result = dispatchGesture(currentGesture!!, gestureCallback, null)
            Log.d(TAG, "dispatchGesture result: $result")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}