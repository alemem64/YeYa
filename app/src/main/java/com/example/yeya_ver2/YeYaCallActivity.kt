package com.example.yeya_ver2

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
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
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer


class YeYaCallActivity : AppCompatActivity() {
    private var TAG = "YeYaCallActivity"
    private lateinit var screenShareImageView: ImageView
    private var clientScreenWidth: Int = 0
    private var clientScreenHeight: Int = 0
    private var socketOutputStream: OutputStream? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private var isReconnecting = false
    private val reconnectDelay = 5000L // 5 seconds

    private val imageQueue = Channel<ByteArray>(Channel.BUFFERED)
    private val FPS = 15 // Adjust this value for desired frame rate
    private val frameInterval = 1000L / FPS
    private var lastFrameTime = 0L

    companion object {
        var instance: YeYaCallActivity? = null
        private const val TAG = "YeYaCallActivity"
    }

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: ImageView
    private val touchEventQueue = Channel<String>(Channel.BUFFERED)



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_yeyacall)
        Log.d(TAG, "YeYaCallActivity onCreate called")

        screenShareImageView = findViewById(R.id.screenShareImageView)
        if (screenShareImageView == null) {
            Log.e(TAG, "screenShareImageView not found in layout")
            finish()
            return
        }

        Log.d(TAG, "screenShareImageView initialized")

        screenShareImageView.setOnTouchListener(::onTouchEvent)

        handleIntent(intent)

        // Initialize cameraExecutor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize previewView
        previewView = ImageView(this)
        previewView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        findViewById<ViewGroup>(R.id.root_layout).addView(previewView)

        setUpServerCamera()
        startImageSending()
        startTouchEventSending()

        Log.d(TAG, "YeYaCallActivity onCreate completed")
    }

    private fun setUpServerCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ImageAnalyzer { bitmap ->
                        runOnUiThread {
                            previewView.setImageBitmap(bitmap)
                            onNewFrameProcessed(bitmap)
                        }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun onNewFrameProcessed(bitmap: Bitmap) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameTime >= frameInterval) {
            lastFrameTime = currentTime
            coroutineScope.launch(Dispatchers.Default) {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
                val imageData = outputStream.toByteArray()
                imageQueue.send(imageData)
            }
        }
    }

    private fun startImageSending() {
        coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val imageData = imageQueue.receive()
                    sendImageToClient(imageData)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in image sending loop", e)
                }
            }
        }
    }

    private suspend fun sendImageToClient(imageData: ByteArray) {
        try {
            socketOutputStream?.let { output ->
                withContext(Dispatchers.IO) {
                    // Add multiplexing identifier '4' for server camera
                    output.write("4".toByteArray())
                    val sizeBytes = ByteBuffer.allocate(4).putInt(imageData.size).array()
                    output.write(sizeBytes)
                    output.write(imageData)
                    output.flush()
                    Log.d(TAG, "Sent server camera image: ${imageData.size} bytes")
                }
            } ?: Log.e(TAG, "SocketOutputStream is null")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending server camera image", e)
            reconnectToClient()
        }
    }



    private class ImageAnalyzer(private val listener: (Bitmap) -> Unit) : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            val bitmap = image.toBitmap()
            val processedBitmap = rotateFlipAndProcessBitmap(bitmap)
            listener(processedBitmap)
            image.close()
        }

        private fun rotateFlipAndProcessBitmap(originalBitmap: Bitmap): Bitmap {
            val matrix = Matrix().apply {
                postRotate(90f)
                postScale(-1f, 1f, originalBitmap.width / 2f, originalBitmap.height / 2f)
            }
            var rotatedFlippedBitmap = Bitmap.createBitmap(
                originalBitmap, 0, 0,
                originalBitmap.width, originalBitmap.height,
                matrix, true
            )

            // Crop to 480x480
            val dimension = minOf(rotatedFlippedBitmap.width, rotatedFlippedBitmap.height, 480)
            val x = (rotatedFlippedBitmap.width - dimension) / 2
            val y = (rotatedFlippedBitmap.height - dimension) / 2
            val croppedBitmap = Bitmap.createBitmap(rotatedFlippedBitmap, x, y, dimension, dimension)

            // Compress
            val outputStream = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 20, outputStream)
            val compressedByteArray = outputStream.toByteArray()

            // Clean up
            if (rotatedFlippedBitmap != originalBitmap) {
                originalBitmap.recycle()
            }
            if (croppedBitmap != rotatedFlippedBitmap) {
                rotatedFlippedBitmap.recycle()
            }

            return BitmapFactory.decodeByteArray(compressedByteArray, 0, compressedByteArray.size)
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



    private fun sendTouchEventToClient(message: String) {
        coroutineScope.launch {
            touchEventQueue.send(message)
        }
    }

    private fun startTouchEventSending() {
        coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val message = touchEventQueue.receive()
                    socketOutputStream?.let { outputStream ->
                        withContext(Dispatchers.IO) {
                            // Add multiplexing identifier '0' for remote control
                            outputStream.write("0".toByteArray())
                            val messageBytes = message.toByteArray(Charsets.UTF_8)
                            val sizeBytes = ByteBuffer.allocate(4).putInt(messageBytes.size).array()
                            outputStream.write(sizeBytes)
                            outputStream.write(messageBytes)
                            outputStream.flush()
                            Log.d(TAG, "Sent touch event: $message")
                        }
                    } ?: Log.e(TAG, "SocketOutputStream is null")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending touch event to client", e)
                    reconnectToClient()
                }
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
        cameraExecutor.shutdown()
    }


}

fun ImageProxy.toBitmap(): Bitmap {
    val buffer = planes[0].buffer
    buffer.rewind()
    val bytes = ByteArray(buffer.capacity())
    buffer.get(bytes)

    return when (format) {
        // ImageFormat.JPEG
        256 -> {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        // ImageFormat.YUV_420_888
        35 -> {
            val yuvImage = YuvImage(bytes, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
            val imageBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }
        else -> {
            throw IllegalArgumentException("Unsupported image format: $format")
        }
    }
}