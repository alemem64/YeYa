package com.example.yeya_ver2

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageView
import java.io.OutputStream


class YeYaCallActivity : AppCompatActivity() {
    private lateinit var screenShareImageView: ImageView
    private var clientScreenWidth: Int = 0
    private var clientScreenHeight: Int = 0
    private lateinit var socketOutputStream: OutputStream

    companion object {
        var instance: YeYaCallActivity? = null
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_yeyacall)

        screenShareImageView = findViewById(R.id.screenShareImageView)
        if (screenShareImageView == null) {
            Log.e("YeYaCallActivity", "screenShareImageView not found in layout")
            finish()
            return
        }

        screenShareImageView.setOnTouchListener(::onTouchEvent)

        handleIntent(intent)
    }

    fun setClientScreenInfo(width: Int, height: Int, outputStream: OutputStream) {
        clientScreenWidth = width
        clientScreenHeight = height
        socketOutputStream = outputStream
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun onTouchEvent(view: View, event: MotionEvent): Boolean {
        if (clientScreenWidth == 0 || clientScreenHeight == 0) {
            Log.e("YeYaCallActivity", "Client screen dimensions not set")
            return false
        }

        val imageWidth = screenShareImageView.width
        val imageHeight = screenShareImageView.height

        // Calculate the touch position relative to the client's screen
        val touchX = (event.x / imageWidth * clientScreenWidth).toInt()
        val touchY = (event.y / imageHeight * clientScreenHeight).toInt()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                Log.d("YeYaCallActivity", "TouchDetected ($touchX, $touchY)")
                sendTouchEventToClient("TOUCH_DOWN|$touchX,$touchY")
            }
            MotionEvent.ACTION_UP -> {
                Log.d("YeYaCallActivity", "TouchReleased ($touchX, $touchY)")
                sendTouchEventToClient("TOUCH_UP|$touchX,$touchY")
            }
            MotionEvent.ACTION_MOVE -> {
                Log.d("YeYaCallActivity", "TouchMoved ($touchX, $touchY)")
                sendTouchEventToClient("TOUCH_MOVE|$touchX,$touchY")
            }
        }

        return true
    }

    private fun sendTouchEventToClient(message: String) {
        try {
            socketOutputStream.write((message + "\n").toByteArray())
            socketOutputStream.flush()
        } catch (e: Exception) {
            Log.e("YeYaCallActivity", "Error sending touch event to client", e)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "UPDATE_SCREEN_SHARE") {
            val imageData = intent.getByteArrayExtra("imageData")
            if (imageData != null) {
                updateScreenShare(imageData)
            }
        }
    }

    private fun updateScreenShare(imageData: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
        runOnUiThread {
            screenShareImageView.setImageBitmap(bitmap)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

}