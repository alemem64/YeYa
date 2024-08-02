package com.example.yeya_ver2

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlinx.coroutines.*


class YeYaCallActivity : AppCompatActivity() {
    private lateinit var screenShareImageView: ImageView
    private var clientScreenWidth: Int = 0
    private var clientScreenHeight: Int = 0
    private var socketOutputStream: OutputStream? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private var isReconnecting = false
    private val reconnectDelay = 5000L // 5 seconds

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

    fun setClientScreenInfo(width: Int, height: Int, outputStream: OutputStream?) {
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

        val drawable = screenShareImageView.drawable
        if (drawable == null) {
            Log.e("YeYaCallActivity", "No image loaded in ImageView")
            return false
        }

        val imageRect = RectF()
        screenShareImageView.imageMatrix.mapRect(imageRect, RectF(drawable.bounds))

        if (event.x < imageRect.left || event.x > imageRect.right ||
            event.y < imageRect.top || event.y > imageRect.bottom) {
            return false
        }

        val touchX = ((event.x - imageRect.left) / imageRect.width() * clientScreenWidth).toInt()
        val touchY = ((event.y - imageRect.top) / imageRect.height() * clientScreenHeight).toInt()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> sendTouchEventToClient("TOUCH|DOWN|$touchX,$touchY")
            MotionEvent.ACTION_MOVE -> sendTouchEventToClient("TOUCH|MOVE|$touchX,$touchY")
            MotionEvent.ACTION_UP -> sendTouchEventToClient("TOUCH|UP|$touchX,$touchY")
        }

        return true
    }

    private fun sendTouchEventToClient(message: String) {
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    socketOutputStream?.let { outputStream ->
                        outputStream.write((message + "\n").toByteArray())
                        outputStream.flush()
                    } ?: Log.e("YeYaCallActivity", "OutputStream is null")
                }
            } catch (e: Exception) {
                Log.e("YeYaCallActivity", "Error sending touch event to client", e)
                reconnectToClient()
            }
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
            screenShareImageView.scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    private fun reconnectToClient() {
        if (isReconnecting) return

        isReconnecting = true
        coroutineScope.launch {
            Log.d("YeYaCallActivity", "Attempting to reconnect to client")
            while (isReconnecting) {
                try {
                    // Attempt to reestablish the connection
                    // This part depends on how your initial connection is set up
                    // For example, you might need to call a method in ServerService to reconnect
                    val intent = Intent(this@YeYaCallActivity, ServerService::class.java)
                    intent.action = "RECONNECT_CLIENT"
                    startService(intent)

                    // Wait for the reconnection attempt
                    delay(reconnectDelay)

                    // Check if the connection is reestablished
                    if (SocketManager.getClientSocket()?.isConnected == true) {
                        Log.d("YeYaCallActivity", "Reconnected to client")
                        isReconnecting = false
                        socketOutputStream = SocketManager.getOutputStream()
                        break
                    }
                } catch (e: Exception) {
                    Log.e("YeYaCallActivity", "Failed to reconnect", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        coroutineScope.cancel()
    }

}