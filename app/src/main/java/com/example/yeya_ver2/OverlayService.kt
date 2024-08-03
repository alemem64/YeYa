package com.example.yeya_ver2

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat
import android.graphics.Bitmap
import android.media.Image
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import org.json.JSONObject
import kotlinx.coroutines.*
import android.speech.tts.TextToSpeech
import android.Manifest
import android.accessibilityservice.GestureDescription
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.util.DisplayMetrics
import androidx.core.content.ContextCompat
import java.net.Socket
import java.io.PrintWriter
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlinx.coroutines.channels.Channel
import android.graphics.Path
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.core.app.ActivityCompat


class OverlayService : Service(), TextToSpeech.OnInitListener {
    private val TAG = "OverlayService"
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())

    private val CHANNEL_ID = "YeyaOverlayServiceChannel"
    private val NOTIFICATION_ID = 1

    private lateinit var speechRecognizer: SpeechRecognizer
    private var isListening = false

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var tts: TextToSpeech

    private val networkCoroutineScope = CoroutineScope(Dispatchers.IO + Job())

    private var imageReader: ImageReader? = null
    private var screenDensity: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private val imageQueue = Channel<ByteArray>(Channel.BUFFERED)
    private val FPS = 30// Adjust this value for desired frame rate
    private val frameInterval = 1000L / FPS
    private var isRecording = false

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private lateinit var ydpImageReader: ImageReader
    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread
    private var isYDPRecording = false
    private var isSocketConnected = false
    private var isCapturing = false
    private val ydpImageQueue = Channel<ByteArray>(Channel.BUFFERED)
    private var isYdpCapturing = false




    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        setupOverlay()
        initializeSpeechRecognizer()
        tts = TextToSpeech(this, this)
        startBufferProcessing()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.KOREAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Korean language is not supported for TTS")
            } else {
                Log.d(TAG, "TTS initialized successfully")
            }
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")

        if (isNotificationPermissionGranted()) {
            startForeground(NOTIFICATION_ID, createNotification())
        } else {
            Log.w(TAG, "Notification permission not granted, running without foreground service")
        }

        intent?.let {
            val resultCode = it.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra("data")
            }

            if (data != null) {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                Log.d(TAG, "Media projection created")
            } else {
                Log.e(TAG, "Failed to get media projection data")
            }
        }

        return START_STICKY
    }

    private fun isNotificationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun setupOverlay() {
        Log.d(TAG, "Setting up overlay")
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager.addView(overlayView, params)

        val captureButton = overlayView.findViewById<Button>(R.id.captureButton)
        var longPressStartTime: Long = 0
        var isLongPressFired = false
        val longPressDuration = 2000L // 2 seconds in milliseconds

        val longPressRunnable = Runnable {
            if (!isLongPressFired) {
                isLongPressFired = true
                handleLongPress()
            }
        }

        captureButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressStartTime = System.currentTimeMillis()
                    isLongPressFired = false
                    handler.postDelayed(longPressRunnable, longPressDuration)
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isLongPressFired) {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!isLongPressFired) {
                        if (System.currentTimeMillis() - longPressStartTime < 200) {
                            // Short click
                            vibrate()
                            takeScreenshot()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }



    private fun handleLongPress() {
        Log.d(TAG, "Long press detected - 2 seconds")
        networkCoroutineScope.launch {
            connectToServer()
            withContext(Dispatchers.Main) {
                startCamera()
            }
            // startYdpImageSender is no longer needed here as it's called in startCamera
        }
    }

    private suspend fun discoverServer(): Triple<String, Int, Int>? {
        return withContext(Dispatchers.IO) {
            val broadcastAddress = "255.255.255.255"
            val UDP_DISCOVERY_PORT_START = 8888

            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 10000 // 10 seconds timeout

                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val multicastLock = wifiManager.createMulticastLock("multicastLock")
                multicastLock.acquire()

                try {
                    for (port in UDP_DISCOVERY_PORT_START until UDP_DISCOVERY_PORT_START + 100) {
                        val sendData = "DISCOVER_YEYA_SERVER".toByteArray()
                        val sendPacket = DatagramPacket(sendData, sendData.size, InetAddress.getByName(broadcastAddress), port)
                        socket.send(sendPacket)
                    }

                    val receiveData = ByteArray(1024)
                    val receivePacket = DatagramPacket(receiveData, receiveData.size)

                    socket.receive(receivePacket)
                    val serverAddress = receivePacket.address.hostAddress
                    val response = String(receivePacket.data, 0, receivePacket.length).split(":")
                    val serverPort = response[0].toInt()
                    val udpPort = response[1].toInt()
                    Log.d(TAG, "Discovered server at $serverAddress:$serverPort (UDP: $udpPort)")
                    Triple(serverAddress, serverPort, udpPort)
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "No server found", e)
                    null
                } finally {
                    multicastLock.release()
                }
            }
        }
    }

    private var clientSocket: Socket? = null

    private suspend fun connectToServer() {
        val serverInfo = discoverServer()
        if (serverInfo == null) {
            Log.e(TAG, "No server found")
            return
        }

        val (serverAddress, serverPort, _) = serverInfo
        withContext(Dispatchers.IO) {
            try {
                clientSocket = Socket(serverAddress, serverPort)
                isSocketConnected = true
                val out = PrintWriter(clientSocket?.getOutputStream(), true)
                val input = BufferedReader(InputStreamReader(clientSocket?.getInputStream()))

                out.println("Client connected to Server")
                val response = input.readLine()

                if (response == "Server connected to Client") {
                    Log.d(TAG, "Successfully connected to server")

                    // Send screen dimensions
                    val (width, height) = getScreenDimensions()
                    out.println("SCREEN_DIMENSIONS|$width,$height")
                    Log.d(TAG, "Sent screen dimensions: $width x $height")

                    // Send startYeYaCall message
                    out.println("startYeYaCall")
                    Log.d(TAG, "Sent startYeYaCall message to server")

                    // Start screen recording and sharing
                    startScreenRecordingAndSharing()

                    // Handle incoming click events
                    handleIncomingEvents()
                } else {
                    Log.e(TAG, "Unexpected response from server: $response")
                    clientSocket?.close()
                }
                startScreenRecordingAndSharing()
                startYDPImageSender()
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to server", e)
                clientSocket?.close()
            }
        }
    }





    private fun startScreenRecordingAndSharing() {
        val metrics = resources.displayMetrics
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        screenWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            bounds.width()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.width
        }

        screenHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.height
        }

        screenDensity = metrics.densityDpi

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        isRecording = true
        launchImageProducer()
        launchImageConsumer()
    }

    private fun launchImageProducer() {
        coroutineScope.launch(Dispatchers.Default) {
            var lastCaptureTime = System.currentTimeMillis()
            while (isCapturing) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastCaptureTime >= frameInterval) {
                    captureAndEnqueueFrame()
                    lastCaptureTime = currentTime
                }
                delay(1) // Small delay to prevent busy waiting
            }
        }
    }

    private fun launchImageConsumer() {
        coroutineScope.launch(Dispatchers.IO) {
            while (isCapturing) {
                try {
                    val imageData = imageQueue.receive()
                    sendImageToServer(imageData)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in image consumer", e)
                    delay(1000) // Wait before retrying
                }
            }
        }
    }

    private suspend fun captureAndEnqueueFrame() {
        withContext(Dispatchers.Default) {
            try {
                imageReader?.acquireLatestImage()?.use { image ->
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    val bitmap = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride, screenHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    val compressedImageData = resizeAndCompressBitmap(bitmap, screenWidth, screenHeight, 20)
                    bitmap.recycle()

                    imageQueue.send(compressedImageData)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing frame", e)
            }
        }
    }


    private fun resizeAndCompressBitmap(original: Bitmap, targetWidth: Int, targetHeight: Int, quality: Int): ByteArray {
        val scaleFactor = 0.5f // Reduce to 25% of original size
        val newWidth = (original.width * scaleFactor).toInt()
        val newHeight = (original.height * scaleFactor).toInt()

        val resized = Bitmap.createScaledBitmap(original, newWidth, newHeight, true)
        val outputStream = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

        // Recycle the resized bitmap to free up memory
        resized.recycle()

        return outputStream.toByteArray()
    }

    private fun sendImageToServer(imageData: ByteArray) {
        if (!isSocketConnected) {
            Log.d(TAG, "Socket is not connected, skipping image send")
            return
        }
        try {
            clientSocket?.let { socket ->
                if (!socket.isClosed) {
                    val outputStream = socket.getOutputStream()
                    val dataSize = imageData.size
                    outputStream.write(ByteBuffer.allocate(4).putInt(dataSize).array())
                    outputStream.write(imageData)
                    outputStream.flush()
                } else {
                    isSocketConnected = false
                    Log.d(TAG, "Socket is closed, stopping image send")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending image to server", e)
            isSocketConnected = false
        }
    }

    private fun getScreenDimensions(): Pair<Int, Int> {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            return Pair(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            return Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
    }

    private fun handleIncomingEvents() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(clientSocket?.inputStream))
                while (isActive) {
                    val message = reader.readLine() ?: break
                    Log.d(TAG, "Received event: $message")
                    processEvent(message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming events", e)
            }
        }
    }

    private var currentPath: Path? = null
    private var gestureBuilder: GestureDescription.Builder? = null
    private var gestureStartTime: Long = 0


    private fun processEvent(message: String) {
        val parts = message.split("|")
        if (parts.size != 4 || parts[0] != "TOUCH") {
            Log.e(TAG, "Invalid event message: $message")
            return
        }

        try {
            val action = parts[1]
            val (x, y) = parts[2].split(",").map { it.toFloat() }
            val touchTime = parts[3].toLong()

            addTouchEventToBuffer(action, x, y, touchTime)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing touch event: ${e.message}")
        }
    }


    private fun startGesture(x: Float, y: Float, touchTime: Long) {
        gestureStartTime = touchTime
        currentPath = Path().apply { moveTo(x, y) }
        gestureBuilder = GestureDescription.Builder()
    }

    private fun updateGesture(x: Float, y: Float, touchTime: Long) {
        currentPath?.lineTo(x, y)
    }

    private fun endGesture(x: Float, y: Float, touchTime: Long) {
        currentPath?.let { path ->
            path.lineTo(x, y)
            val duration = touchTime - gestureStartTime
            gestureBuilder?.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            gestureBuilder?.build()?.let { gesture ->
                YeyaAccessibilityService.getInstance()?.dispatchGesture(gesture, null, null)
            }
        }
        currentPath = null
        gestureBuilder = null
    }

    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private val touchEventBuffer = mutableListOf<Quadruple<String, Float, Float, Long>>()
    private val bufferLock = Any()
    private val bufferProcessingJob = Job()
    private val bufferProcessingScope = CoroutineScope(Dispatchers.Default + bufferProcessingJob)

    private fun addTouchEventToBuffer(action: String, x: Float, y: Float, time: Long) {
        synchronized(bufferLock) {
            touchEventBuffer.add(Quadruple(action, x, y, time))
        }
    }

    private fun startBufferProcessing() {
        bufferProcessingScope.launch {
            while (isActive) {
                processBufferedEvents()
                delay(16) // Approximately 60fps
            }
        }
    }

    private fun processBufferedEvents() {
        synchronized(bufferLock) {
            if (touchEventBuffer.isNotEmpty()) {
                val events = touchEventBuffer.toList()
                touchEventBuffer.clear()

                events.forEach { (action, x, y, time) ->
                    when (action) {
                        "DOWN" -> startGesture(x, y, time)
                        "MOVE" -> updateGesture(x, y, time)
                        "UP" -> endGesture(x, y, time)
                    }
                }
            }
        }
    }

    private fun startYdpCapture() {
        isYdpCapturing = true
        launchYdpImageProducer()
        launchYdpImageConsumer()
    }

    private fun launchYdpImageProducer() {
        coroutineScope.launch(Dispatchers.Default) {
            while (isYdpCapturing) {
                captureAndEnqueueYdpFrame()
                delay(33) // Approximately 30 FPS
            }
        }
    }

    private fun launchYdpImageConsumer() {
        coroutineScope.launch(Dispatchers.IO) {
            while (isYdpCapturing) {
                try {
                    val imageData = ydpImageQueue.receive()
                    sendYdpImageToServer(imageData)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in YDP image consumer", e)
                    delay(1000) // Wait before retrying
                }
            }
        }
    }

    private suspend fun captureAndEnqueueYdpFrame() {
        withContext(Dispatchers.Default) {
            try {
                ydpImageReader.acquireLatestImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.capacity())
                    buffer.get(bytes)
                    val compressedImageData = compressImage(bytes)
                    ydpImageQueue.send(compressedImageData)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing YDP frame", e)
            }
        }
    }

    private fun sendYdpImageToServer(imageData: ByteArray) {
        if (!isSocketConnected) {
            Log.d(TAG, "Socket is not connected, skipping YDP image send")
            return
        }
        try {
            clientSocket?.let { socket ->
                if (!socket.isClosed) {
                    val outputStream = socket.getOutputStream()
                    val dataSize = imageData.size
                    outputStream.write(ByteBuffer.allocate(4).putInt(dataSize).array())
                    outputStream.write("YDP".toByteArray())
                    outputStream.write(imageData)
                    outputStream.flush()
                    Log.d(TAG, "YDP Image Sent (Size: $dataSize bytes)")
                } else {
                    isSocketConnected = false
                    Log.d(TAG, "Socket is closed, stopping YDP image send")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending YDP image to server", e)
            isSocketConnected = false
        }
    }

    private fun startCamera() {
        if (isYDPRecording) return  // Prevent multiple initializations

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)

        val cameraId = cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: run {
            Log.e(TAG, "No front-facing camera found")
            return
        }

        ydpImageReader = ImageReader.newInstance(360, 480, ImageFormat.YUV_420_888, 2)
        ydpImageReader.setOnImageAvailableListener({ reader ->
            // We'll handle image capture in captureAndEnqueueYdpFrame
        }, backgroundHandler)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCameraPreviewSession()
                        startYdpCapture()  // Start YDP capture when camera is ready
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        cameraDevice?.close()
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "Camera open error: $error")
                        cameraDevice?.close()
                        cameraDevice = null
                    }
                }, backgroundHandler)
            } catch (e: CameraAccessException) {
                Log.e(TAG, "Failed to open camera", e)
            }
        }

        isYDPRecording = true
    }

    private fun createCameraPreviewSession() {
        val surface = ydpImageReader.surface
        val previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewRequestBuilder?.addTarget(surface)

        cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                previewRequestBuilder?.build()?.let { request ->
                    session.setRepeatingRequest(request, null, backgroundHandler)
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Failed to configure camera preview session")
            }
        }, backgroundHandler)
    }



    private fun sendYDPImageToServer(imageData: ByteArray) {
        coroutineScope.launch {
            ydpImageQueue.send(imageData)
        }
    }

    private fun startYDPImageSender() {
        coroutineScope.launch(Dispatchers.IO) {
            while (isActive && isSocketConnected) {
                try {
                    val imageData = ydpImageQueue.receive()
                    val compressedImageData = compressImage(imageData)
                    sendImageToServer(compressedImageData)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in YDP image sender", e)
                    delay(1000) // Wait before retrying
                }
            }
        }
    }

    private fun compressImage(imageData: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
        return outputStream.toByteArray()
    }



    private fun vibrate() {
        Log.d(TAG, "Vibrating")
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(200)
        }
    }

    private fun takeScreenshot() {
        Log.d(TAG, "Taking screenshot")
        overlayView.visibility = View.INVISIBLE
        handler.postDelayed({
            captureScreen()
            captureUIElements()
            overlayView.visibility = View.VISIBLE
            startVoiceRecognition()
        }, 100)
    }

    private fun captureScreen() {
        val metrics = resources.displayMetrics
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val width: Int
        val height: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            width = windowManager.defaultDisplay.width
            @Suppress("DEPRECATION")
            height = windowManager.defaultDisplay.height
        }

        val density = metrics.densityDpi

        var imageReader: ImageReader? = null

        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            mediaProjection?.let { projection ->
                virtualDisplay = projection.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface, null, null
                )

                imageReader?.setOnImageAvailableListener({ reader ->
                    val image: Image? = reader.acquireLatestImage()
                    image?.let {
                        val planes = it.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride, height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        saveBitmapToGallery(bitmap)
                        it.close()
                    }
                    virtualDisplay?.release()
                    imageReader?.close()
                }, handler)

            } ?: run {
                Log.e(TAG, "Error: MediaProjection is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in captureScreen: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "YEYA_Screenshot_$timeStamp.jpg"
        val storageDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "yeya")

        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }

        val imageFile = File(storageDir, imageFileName)

        try {
            FileOutputStream(imageFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
            }
            Log.d(TAG, "Screenshot saved: ${imageFile.absolutePath}")
            // Notify the media scanner about the new file.
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(imageFile)
            sendBroadcast(mediaScanIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving screenshot: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun captureUIElements() {
        Log.d(TAG, "Starting UI element capture")
        val uiElements = UICapture.getLatestUIElements()
        if (uiElements != null) {
            Log.d(TAG, "UI elements captured successfully. Total elements: ${uiElements.length()}")
        } else {
            Log.e(TAG, "Failed to capture UI elements")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Yeya Overlay Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }


    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val startServerIntent = Intent(this, ServerService::class.java)
        val startServerPendingIntent = PendingIntent.getService(
            this, 0, startServerIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Yeya Overlay Service")
            .setContentText("Running")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_notification, "Start Server", startServerPendingIntent)
            .build()
    }

    private fun initializeSpeechRecognizer() {
        Log.d(TAG, "Initializing SpeechRecognizer")
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition is not available on this device")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
            }
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech begun")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
            }
            override fun onError(error: Int) {
                Log.e(TAG, "Error in speech recognition: $error")
                isListening = false
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    handleSpeechResult(recognizedText)
                } else {
                    Log.d(TAG, "No speech recognized")
                }
                isListening = false
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startVoiceRecognition() {
        Log.d(TAG, "Starting voice recognition")
        if (!isListening) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                speechRecognizer.startListening(intent)
                isListening = true
            } catch (e: Exception) {
                Log.e(TAG, "Error starting voice recognition", e)
            }
        } else {
            Log.d(TAG, "Voice recognition already in progress")
        }
    }

    private fun handleSpeechResult(recognizedText: String) {
        Log.d(TAG, "Recognized text: $recognizedText")
        val uiElements = UICapture.getLatestUIElements()
        if (uiElements != null) {
            Log.d(TAG, "Using captured UI elements. CaptureID: ${uiElements.optInt("captureID")}")
        } else {
            Log.w(TAG, "No UI elements captured. Using empty object.")
        }

        coroutineScope.launch {
            try {
                // Use the uiElements directly, which is already a JSONObject
                val claudeResponse = ClaudeApiClient.sendMessageToClaude(recognizedText, uiElements ?: JSONObject())
                Log.d(TAG, "Claude response: $claudeResponse")
                processClaudeResponse(claudeResponse)
            } catch (e: Exception) {
                Log.e(TAG, "Error communicating with Claude API", e)
                val errorMessage = "죄송합니다. 지금 잠시 문제가 있어요. 다시 한 번 말씀해 주시겠어요?"
                textToSpeech(errorMessage)
            }
        }
    }


    private fun processClaudeResponse(response: String) {
        try {
            val jsonResponse = JSONObject(response)
            val description = jsonResponse.optString("description")
            val id = jsonResponse.optInt("id", -1)
            val action = jsonResponse.optString("action")

            Log.d(TAG, "Claude description: $description")
            Log.d(TAG, "Claude id: $id")
            Log.d(TAG, "Claude action: $action")

            if (description.isNotEmpty() && id != -1 && action.isNotEmpty()) {
                // Speak the description using TTS
                textToSpeech(description)

                // Perform the action
                when (action) {
                    "Click" -> performClick(id)
                    else -> Log.w(TAG, "Unknown action: $action")
                }
            } else {
                Log.e(TAG, "Invalid response format from Claude")
                textToSpeech("죄송합니다. 응답을 처리하는 데 문제가 있습니다. 다시 시도해 주세요.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing Claude response", e)
            textToSpeech("죄송합니다. 응답을 처리하는 데 문제가 있습니다. 다시 시도해 주세요.")
        }
    }

    private fun textToSpeech(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
    }

    private fun performClick(id: Int) {
        Log.d(TAG, "Performing click on element with id: $id")
        YeyaAccessibilityService.getInstance()?.performClick(id) ?: run {
            Log.e(TAG, "YeyaAccessibilityService is not available")
            textToSpeech("죄송합니다. 클릭 기능을 수행할 수 없습니다.")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        windowManager.removeView(overlayView)
        mediaProjection?.stop()
        virtualDisplay?.release()
        speechRecognizer.destroy()
        coroutineScope.cancel()
        networkCoroutineScope.cancel()
        tts.stop()
        tts.shutdown()
        stopScreenSharing()
        stopCamera()
    }

    private fun stopCamera() {
        isYDPRecording = false
        isYdpCapturing = false
        cameraDevice?.close()
        cameraDevice = null
        if (::backgroundThread.isInitialized) {
            backgroundThread.quitSafely()
            try {
                backgroundThread.join()
            } catch (e: InterruptedException) {
                Log.e(TAG, "Error stopping background thread", e)
            }
        }
        if (::ydpImageReader.isInitialized) {
            ydpImageReader.close()
        }
    }


    private fun stopScreenSharing() {
        isRecording = false
        isSocketConnected = false
        virtualDisplay?.release()
        imageReader?.close()
        clientSocket?.close()
        clientSocket = null
        isCapturing = false
        isSocketConnected = false
    }

}

