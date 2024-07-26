package com.example.yeya_ver2

import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

object UICapture {
    private var latestUIElements: JSONArray? = null

    fun captureUIElements(rootNode: AccessibilityNodeInfo?) {
        rootNode ?: return
        val elements = JSONArray()

        fun processNode(node: AccessibilityNodeInfo) {
            if (node.isClickable || (!node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty())) {
                val element = JSONObject().apply {
                    put("id", node.viewIdResourceName ?: "")
                    put("class", node.className)
                    put("text", node.text ?: "")
                    put("contentDescription", node.contentDescription ?: "")
                    put("clickable", node.isClickable)
                }
                elements.put(element)
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { processNode(it) }
            }
        }

        processNode(rootNode)
        latestUIElements = elements
    }

    fun getLatestUIElements(): JSONArray? {
        return latestUIElements
    }
}