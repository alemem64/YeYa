package com.example.yeya_ver2

import AudioManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlinx.coroutines.*
import java.net.Socket
import java.nio.ByteBuffer


class YeYaCallActivity : AppCompatActivity() {
    private val TAG = "YeYaCallActivity"
    private lateinit var screenShareImageView: ImageView
    private var clientScreenWidth: Int = 0
    private var clientScreenHeight: Int = 0
    private var socketOutputStream: OutputStream? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private var isReconnecting = false
    private val reconnectDelay = 5000L // 5 seconds
    private lateinit var cameraManager: CameraManager
    private lateinit var audioManager: AudioManager
    private var clientSocket: Socket? = null
    private lateinit var remoteVideoView: ImageView
    private lateinit var localVideoView: ImageView
    private lateinit var endCallButton: Button
    companion object {
        var instance: YeYaCallActivity? = null
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_yeyacall)


        if (screenShareImageView == null) {
            Log.e("YeYaCallActivity", "screenShareImageView not found in layout")
            finish()
            return
        }

        screenShareImageView.setOnTouchListener(::onTouchEvent)

        handleIntent(intent)

        cameraManager = CameraManager(this)
        audioManager = AudioManager(this)

        remoteVideoView = findViewById(R.id.remoteVideoView)
        localVideoView = findViewById(R.id.localVideoView)
        endCallButton = findViewById(R.id.endCallButton)

        endCallButton.setOnClickListener {
            endCall()
        }
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

    private fun startCameraCapture() {
        cameraManager.startCamera { imageData ->
            // Send imageData to client
            coroutineScope.launch {
                sendImageToClient(imageData)
            }
        }
    }

    private fun stopCameraCapture() {
        cameraManager.stopCamera()
    }

    private suspend fun sendImageToClient(imageData: ByteArray) {
        try {
            val socket = clientSocket ?: run {
                Log.e(TAG, "Client socket is null")
                return
            }
            if (!socket.isClosed) {
                val outputStream = socket.getOutputStream()
                val dataSize = imageData.size
                outputStream.write(ByteBuffer.allocate(4).putInt(dataSize).array())
                outputStream.write(imageData)
                outputStream.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending image to client", e)
        }
    }

    private fun startAudioCapture() {
        coroutineScope.launch {
            audioManager.startAudioCapture { audioData ->
                sendAudioToClient(audioData)
            }
        }
    }

    private suspend fun sendAudioToClient(audioData: ByteArray) {
        // Implement the logic to send audio data to the client
        // You can use the existing socket connection or create a new one for audio
    }

    private fun stopAudioCapture() {
        audioManager.stopAudioCapture()
    }

    private suspend fun receiveAudioFromClient(audioData: ByteArray) {
        audioManager.playAudio(audioData)
    }

    private fun endCall() {
        // Implement call ending logic
        finish()
    }

    private suspend fun updateVideoDisplay(imageData: ByteArray) {
        withContext(Dispatchers.Main) {
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            remoteVideoView.setImageBitmap(bitmap)
        }
    }

    private suspend fun updateLocalVideoDisplay(imageData: ByteArray) {
        withContext(Dispatchers.Main) {
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            localVideoView.setImageBitmap(bitmap)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        coroutineScope.cancel()
    }

}