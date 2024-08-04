package com.example.yeya_ver2

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream




class YeYaCallActivity : AppCompatActivity() {
    private var TAG = "YeYaCallActivity"
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

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private lateinit var imageReader: ImageReader
    private val cameraHandler = Handler(HandlerThread("CameraThread").apply { start() }.looper)
    private val serverCameraQueue = Channel<ByteArray>(Channel.BUFFERED)



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
        Log.d(TAG, "YeYaCallActivity onCreate completed")
        startServerCamera()
    }

    private fun startServerCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted")
            return
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.find {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }

        if (cameraId != null) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = streamConfigurationMap?.getOutputSizes(ImageFormat.JPEG)
            val size = sizes?.firstOrNull { it.width >= 480 && it.height >= 480 } ?: Size(640, 480)

            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                image?.let {
                    val buffer = it.planes[0].buffer
                    val bytes = ByteArray(buffer.capacity())
                    buffer.get(bytes)
                    coroutineScope.launch {
                        processAndEnqueueImage(bytes)
                    }
                    it.close()
                }
            }, cameraHandler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraDevice?.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    cameraDevice?.close()
                }
            }, cameraHandler)
        }
    }

    private fun createCameraPreviewSession() {
        val surface = imageReader.surface
        cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                val captureRequest = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequest?.addTarget(surface)
                session.setRepeatingRequest(captureRequest!!.build(), null, cameraHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Failed to configure camera session")
            }
        }, cameraHandler)
    }

    private suspend fun processAndEnqueueImage(imageBytes: ByteArray) {
        withContext(Dispatchers.Default) {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            val croppedBitmap = cropBitmapToSquare(bitmap)
            val outputStream = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            val compressedBytes = outputStream.toByteArray()
            serverCameraQueue.send(compressedBytes)
            sendServerCameraImageToClient()
        }
    }

    private fun cropBitmapToSquare(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val size = minOf(width, height)
        val x = (width - size) / 2
        val y = (height - size) / 2
        val croppedBitmap = Bitmap.createBitmap(bitmap, x, y, size, size)
        val scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, 480, 480, true)
        croppedBitmap.recycle()
        bitmap.recycle()
        Log.d(TAG, "Cropped Bitmap To Square")
        return scaledBitmap
    }

    private suspend fun sendServerCameraImageToClient() {
        val imageData = serverCameraQueue.receive()
        try {
            SocketManager.getClientSocket()?.let { socket ->
                if (!socket.isClosed) {
                    val outputStream = socket.getOutputStream()
                    val dataSize = imageData.size
                    outputStream.write("4".toByteArray())
                    outputStream.write(ByteBuffer.allocate(4).putInt(dataSize).array())
                    outputStream.write(imageData)
                    outputStream.flush()
                    Log.d(TAG, "Server camera image sent (Size: $dataSize bytes)")
                } else {
                    Log.e(TAG, "Socket is closed. Attempting to reconnect...")
                    reconnectToClient()
                }
            } ?: run {
                Log.e(TAG, "Client socket is null. Attempting to reconnect...")
                reconnectToClient()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending server camera image to client", e)
            reconnectToClient()
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
                    SocketManager.getOutputStream()?.let { outputStream ->
                        val multiplexedMessage = "0${message.length}|$message\n"
                        outputStream.write(multiplexedMessage.toByteArray())
                        outputStream.flush()
                        Log.d(TAG, "Sent touch event: $multiplexedMessage")
                    } ?: Log.e(TAG, "OutputStream is null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending touch event to client", e)
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