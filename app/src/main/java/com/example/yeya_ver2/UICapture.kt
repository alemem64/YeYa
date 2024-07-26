package com.example.yeya_ver2

import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

object UICapture {
    private var latestUIElements: JSONArray? = null
    private var elementId = 0

    fun captureUIElements(rootNode: AccessibilityNodeInfo?) {
        rootNode ?: return
        val elements = JSONArray()
        elementId = 0  // Reset elementId before processing
        processNode(rootNode, elements)
        latestUIElements = elements
    }

    private fun processNode(node: AccessibilityNodeInfo, elements: JSONArray) {
        val text = node.text?.toString() ?: ""
        val contentDescription = node.contentDescription?.toString() ?: ""

        // Only process nodes that have either text or content description
        if (node.isClickable || text.isNotEmpty() || contentDescription.isNotEmpty()) {
            val element = JSONObject().apply {
                put("id", elementId++)
                put("class", node.className)
                put("text", text)
                put("contentDescription", contentDescription)
                put("isClickable", node.isClickable)
            }
            elements.put(element)
        }

        // Process child nodes
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