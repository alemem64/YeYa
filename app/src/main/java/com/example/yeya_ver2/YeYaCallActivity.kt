package com.example.yeya_ver2

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
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

    private lateinit var videoCallOverlayView: View
    private var isVideoCallFullscreen = false
    private var originalX = 0
    private var originalY = 0



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

    private var isTracking = false
    private val touchPath = Path()

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
        val touchTime = SystemClock.uptimeMillis()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isTracking = true
                touchPath.moveTo(touchX.toFloat(), touchY.toFloat())
                sendTouchEventToClient("DOWN|$touchX,$touchY|$touchTime")
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTracking) {
                    touchPath.lineTo(touchX.toFloat(), touchY.toFloat())
                    sendTouchEventToClient("MOVE|$touchX,$touchY|$touchTime")
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTracking = false
                sendTouchEventToClient("UP|$touchX,$touchY|$touchTime")
                touchPath.reset()
            }
        }


        return true
    }

    private fun sendClickEventToClient(x: Int, y: Int) {
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    socketOutputStream?.let { outputStream ->
                        val message = "CLICK|$x,$y\n"
                        outputStream.write(message.toByteArray())
                        outputStream.flush()
                        Log.d("YeYaCallActivity", "Sent click event: $message")
                    } ?: Log.e("YeYaCallActivity", "OutputStream is null")
                }
            } catch (e: Exception) {
                Log.e("YeYaCallActivity", "Error sending click event to client", e)
                reconnectToClient()
            }
        }
    }

    private fun sendTouchEventToClient(message: String) {
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    socketOutputStream?.let { outputStream ->
                        outputStream.write(("TOUCH|$message\n").toByteArray())
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
        when (intent?.action) {
            "UPDATE_SCREEN_SHARE" -> {
                val imageData = intent.getByteArrayExtra("imageData")
                if (imageData != null) {
                    updateScreenShare(imageData)
                }
            }
            "SETUP_VIDEO_CALL_OVERLAY" -> {
                setupVideoCallOverlay()
            }
            else -> {
                // Handle any other intents or default behavior
            }
        }
    }

    private fun setupVideoCallOverlay() {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        videoCallOverlayView = inflater.inflate(R.layout.video_call_overlay, null)

        val params = FrameLayout.LayoutParams(
            360, 480
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = 100
            topMargin = 100
        }

        val rootLayout = findViewById<FrameLayout>(R.id.root_layout)
        rootLayout.addView(videoCallOverlayView, params)

        setupVideoCallOverlayTouchListener(params)
    }

    private fun setupVideoCallOverlayTouchListener(params: FrameLayout.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var startClickTime = 0L
        var totalMoveDistance = 0f

        videoCallOverlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.leftMargin
                    initialY = params.topMargin
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    startClickTime = System.currentTimeMillis()
                    totalMoveDistance = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    params.leftMargin = initialX + dx
                    params.topMargin = initialY + dy
                    videoCallOverlayView.layoutParams = params

                    totalMoveDistance += Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val clickDuration = System.currentTimeMillis() - startClickTime
                    if (clickDuration < 200 && totalMoveDistance < 20) {
                        toggleFullscreen(params)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleFullscreen(params: FrameLayout.LayoutParams) {
        if (isVideoCallFullscreen) {
            params.width = 360
            params.height = 480
            params.leftMargin = originalX
            params.topMargin = originalY
        } else {
            originalX = params.leftMargin
            originalY = params.topMargin
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            params.leftMargin = 0
            params.topMargin = 0
        }
        isVideoCallFullscreen = !isVideoCallFullscreen
        videoCallOverlayView.layoutParams = params
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