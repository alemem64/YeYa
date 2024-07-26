package com.example.yeya_ver2

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class YeyaAccessibilityService : AccessibilityService() {
    private val TAG = "YeyaAccessibilityService"

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
        // We don't need to implement this for now
    }

    override fun onInterrupt() {
        // We don't need to implement this for now
    }

    fun performClick(id: Int) {
        Log.d(TAG, "Attempting to click on element with id: $id")
        val rootNode = rootInActiveWindow ?: return
        val targetNode = findNodeById(rootNode, id)
        targetNode?.let {
            val clickResult = it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Click performed on element with id $id, result: $clickResult")
        } ?: Log.e(TAG, "Target node with id $id not found")
    }

    private fun findNodeById(node: AccessibilityNodeInfo, targetId: Int): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.endsWith("/$targetId") == true) {
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