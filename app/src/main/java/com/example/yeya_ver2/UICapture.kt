package com.example.yeya_ver2

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

object UICapture {
    private const val TAG = "UICapture"
    private var latestUIElements: JSONObject? = null
    private var elementId = 0
    private var captureId = 0

    fun captureUIElements(rootNode: AccessibilityNodeInfo?): JSONObject? {
        rootNode ?: return null
        val elements = JSONArray()
        elementId = 0
        captureId++
        processNode(rootNode, elements)
        latestUIElements = JSONObject().apply {
            put("captureID", captureId)
            put("capturedResult", elements)
        }
        Log.d(TAG, "CaptureID-$captureId: Total UI elements captured: ${elements.length()}")
        return latestUIElements
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

            Log.d(TAG, "CaptureID-$captureId Captured UI element: $element")
        }

        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i) ?: continue
            processNode(childNode, elements)
            childNode.recycle()
        }
    }

    fun getLatestUIElements(): JSONObject? {
        return latestUIElements
    }
}