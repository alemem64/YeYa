package com.example.yeya_ver2

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

object UICapture {
    private const val TAG = "UICapture"
    private var latestUIElements: JSONArray? = null
    private var elementId = 0
    private var captureId = 0  // New variable to track capture sessions

    fun captureUIElements(rootNode: AccessibilityNodeInfo?) {
        rootNode ?: return
        val elements = JSONArray()
        elementId = 0  // Reset elementId before processing
        captureId++  // Increment captureId for each capture session
        processNode(rootNode, elements)
        latestUIElements = elements
        Log.d(TAG, "Total UI elements captured: ${elements.length()}")
    }

    private fun processNode(node: AccessibilityNodeInfo, elements: JSONArray) {
        val text = node.text?.toString() ?: ""
        val contentDescription = node.contentDescription?.toString() ?: ""

        if (node.isClickable || text.isNotEmpty() || contentDescription.isNotEmpty()) {
            val element = JSONObject().apply {
                put("id", elementId++)
                put("class", node.className)
                put("text", text)
                put("contentDescription", contentDescription)
                put("isClickable", node.isClickable)
            }
            elements.put(element)

            // Modified log message with CaptureID
            Log.d(TAG, "CaptureID-$captureId Captured UI element: $element")
        }

        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i) ?: continue
            processNode(childNode, elements)
            childNode.recycle()
        }
    }

    fun getLatestUIElements(): JSONArray? {
        return latestUIElements
    }
}