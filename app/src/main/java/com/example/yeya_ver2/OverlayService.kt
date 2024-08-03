package com.example.yeya_ver2

import AudioManager
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
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

import com.example.yeya_ver2.UICapture

import kotlinx.coroutines.*

import android.speech.tts.TextToSpeech
import java.util.*
import android.Manifest
import android.accessibilityservice.GestureDescription
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.util.DisplayMetrics
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
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
import android.os.SystemClock
import android.widget.ImageView
import java.io.IOException
import java.io.InputStream



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

    private var lastTouchDownTime: Long = 0
    private var lastTouchDownX: Int = 0
    private var lastTouchDownY: Int = 0
    private val CLICK_TIME_THRESHOLD = 200 // milliseconds

    private lateinit var cameraManager: CameraManager
    private lateinit var audioManager: AudioManager
    private lateinit var videoOverlayView: View
    private lateinit var remoteVideoView: ImageView
    private var isFullScreen = false
    private var originalParams: WindowManager.LayoutParams? = null






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
        cameraManager = CameraManager(this)
        audioManager = AudioManager(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupVideoOverlay()
        cameraManager = CameraManager(this)
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
    private fun setupVideoOverlay() {
        videoOverlayView = LayoutInflater.from(this).inflate(R.layout.video_overlay, null)
        remoteVideoView = videoOverlayView.findViewById(R.id.remoteVideoView)

        val params = WindowManager.LayoutParams(
            360, 480,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100

        windowManager.addView(videoOverlayView, params)

        setupDragListener(videoOverlayView, params)

        videoOverlayView.setOnClickListener {
            toggleFullScreen()
        }
    }

    private fun toggleFullScreen() {
        if (isFullScreen) {
            // Restore original size and position
            originalParams?.let { params ->
                windowManager.updateViewLayout(videoOverlayView, params)
            }
        } else {
            // Save original params and go full screen
            val currentParams = videoOverlayView.layoutParams as WindowManager.LayoutParams
            originalParams = WindowManager.LayoutParams().apply {
                copyFrom(currentParams)
            }
            val fullScreenParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            windowManager.updateViewLayout(videoOverlayView, fullScreenParams)
        }
        isFullScreen = !isFullScreen
    }

    private fun setupDragListener(view: View, params: WindowManager.LayoutParams) {
        var initialX: Int = 0
        var initialY: Int = 0
        var initialTouchX: Float = 0f
        var initialTouchY: Float = 0f

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(videoOverlayView, params)
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
                SocketManager.setClientSocket(clientSocket!!)
                Log.d(TAG, "Successfully connected to server")

                val out = PrintWriter(clientSocket?.getOutputStream(), true)
                val input = BufferedReader(InputStreamReader(clientSocket?.getInputStream()))

                out.println("Client connected to Server")
                val response = input.readLine()

                if (response == "Server connected to Client") {
                    Log.d(TAG, "Server acknowledged connection")

                    // Send screen dimensions
                    val (width, height) = getScreenDimensions()
                    out.println("SCREEN_DIMENSIONS|$width,$height")
                    Log.d(TAG, "Sent screen dimensions: $width x $height")

                    // Send startYeYaCall message
                    out.println("startYeYaCall")
                    Log.d(TAG, "Sent startYeYaCall message to server")

                    // Start screen recording and sharing
                    startScreenRecordingAndSharing()

                    // Start video call components
                    YVC_startComponents()

                    // Handle incoming events
                    handleIncomingEvents()
                } else {
                    Log.e(TAG, "Unexpected response from server: $response")
                    clientSocket?.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to server", e)
                clientSocket?.close()
            }
        }
    }



    private val imageLock = Any()
    private var isProcessingImage = false

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
            while (isRecording) {
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
            while (isRecording) {
                val imageData = imageQueue.receive()
                sendImageToServer(imageData)
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

                    val compressQuality = 20 // Increase compression (lower quality)
                    val compressedImageData = resizeAndCompressBitmap(bitmap, screenWidth, screenHeight, compressQuality)

                    // Recycle the original bitmap to free up memory
                    bitmap.recycle()

                    imageQueue.send(compressedImageData)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing frame", e)
            }
        }
    }

    private fun captureAndSendFrame() {
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

            val targetWidth = screenWidth / 2
            val targetHeight = screenHeight / 2
            val compressQuality = 50
            val compressedImageData = resizeAndCompressBitmap(bitmap, targetWidth, targetHeight, compressQuality)

            coroutineScope.launch {
                sendImageToServer(compressedImageData)
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

    private suspend fun sendImageToServer(imageData: ByteArray) {
        try {
            clientSocket?.let { socket ->
                if (!socket.isClosed) {
                    val outputStream = socket.getOutputStream()
                    val dataSize = imageData.size
                    outputStream.write(ByteBuffer.allocate(4).putInt(dataSize).array())
                    Log.d(TAG, "Image Sent (Size: $dataSize bytes)")
                    outputStream.write(imageData)
                    outputStream.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending image to server", e)
            // Implement reconnection logic here if needed
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
            while (isActive) {
                try {
                    val reader = BufferedReader(InputStreamReader(clientSocket?.inputStream))
                    while (isActive) {
                        val message = reader.readLine() ?: break
                        Log.d(TAG, "Received event: $message")
                        processEvent(message)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling incoming events", e)
                    delay(5000) // Wait before attempting to reconnect
                    reconnectToServer()
                }
            }
        }
    }

    private fun reconnectToServer() {
        coroutineScope.launch {
            while (isActive) {
                try {
                    connectToServer()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reconnect", e)
                    delay(5000) // Wait before next attempt
                }
            }
        }
    }

    private var currentPath: Path? = null
    private var gestureBuilder: GestureDescription.Builder? = null
    private var gestureStartTime: Long = 0
    private var lastX: Float = 0f
    private var lastY: Float = 0f

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

    private fun startVideoCallComponents() {
        startCameraCapture()
        startAudioCapture()
        receiveVideoAndAudio()
    }

    private fun startCameraCapture() {
        cameraManager.startCamera { imageData ->
            coroutineScope.launch {
                sendVideoFrame(imageData)
            }
        }
    }

    private suspend fun sendVideoFrame(imageData: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                val outputStream = clientSocket?.getOutputStream() ?: return@withContext
                val frameData = buildFrame(1, imageData) // 1 for video type
                outputStream.write(frameData)
                outputStream.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending video frame", e)
            }
        }
    }

    private fun stopCameraCapture() {
        cameraManager.stopCamera()
    }

    private fun startAudioCapture() {
        coroutineScope.launch {
            audioManager.startAudioCapture { audioData ->
                sendAudioData(audioData)
            }
        }
    }

    private suspend fun sendAudioData(audioData: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                val outputStream = clientSocket?.getOutputStream() ?: return@withContext
                val frameData = buildFrame(2, audioData) // 2 for audio type
                outputStream.write(frameData)
                outputStream.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending audio data", e)
            }
        }
    }

    private fun buildFrame(type: Int, data: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(13 + data.size)
        buffer.putInt(0xFFFFFFFF.toInt()) // Start marker
        buffer.put(type.toByte())
        buffer.putInt(data.size)
        buffer.put(data)
        buffer.putInt(0xEEEEEEEE.toInt()) // End marker
        return buffer.array()
    }

    private fun receiveVideoAndAudio() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val inputStream = clientSocket?.getInputStream() ?: return@launch
                val buffer = ByteArray(4096) // Adjust buffer size as needed
                while (isActive) {
                    val frame = readFrame(inputStream, buffer)
                    when (frame.type) {
                        1 -> updateVideoDisplay(frame.data)
                        2 -> playAudioData(frame.data)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error receiving video/audio data", e)
            }
        }
    }

    private fun readFrame(inputStream: InputStream, buffer: ByteArray): Frame {
        // Read start marker
        var bytesRead = inputStream.read(buffer, 0, 4)
        if (bytesRead != 4 || ByteBuffer.wrap(buffer, 0, 4).int != 0xFFFFFFFF.toInt()) {
            throw IOException("Invalid start marker")
        }

        // Read type
        bytesRead = inputStream.read(buffer, 0, 1)
        val type = buffer[0].toInt()

        // Read data length
        bytesRead = inputStream.read(buffer, 0, 4)
        val length = ByteBuffer.wrap(buffer, 0, 4).int

        // Read data
        val data = ByteArray(length)
        var totalBytesRead = 0
        while (totalBytesRead < length) {
            bytesRead = inputStream.read(data, totalBytesRead, length - totalBytesRead)
            if (bytesRead == -1) break
            totalBytesRead += bytesRead
        }

        // Read end marker
        bytesRead = inputStream.read(buffer, 0, 4)
        if (bytesRead != 4 || ByteBuffer.wrap(buffer, 0, 4).int != 0xEEEEEEEE.toInt()) {
            throw IOException("Invalid end marker")
        }

        return Frame(type, data)
    }



    data class Frame(val type: Int, val data: ByteArray)

    private val YVC_videoQueue = Channel<ByteArray>(Channel.BUFFERED)
    private val YVC_audioQueue = Channel<ByteArray>(Channel.BUFFERED)
    private val YVC_FPS = 15
    private val YVC_frameInterval = 1000L / YVC_FPS
    private var YVC_isActive = false

    private fun YVC_startComponents() {
        YVC_isActive = true
        cameraManager.startCamera { imageData ->
            coroutineScope.launch {
                YVC_videoQueue.send(imageData)
            }
        }
        YVC_launchVideoCaptureProducer()
        YVC_launchAudioCaptureProducer()
        YVC_launchVideoConsumer()
        YVC_launchAudioConsumer()
        YVC_receiveVideoAndAudio()
    }

    private fun YVC_launchVideoCaptureProducer() {
        coroutineScope.launch(Dispatchers.Default) {
            var lastCaptureTime = System.currentTimeMillis()
            while (YVC_isActive) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastCaptureTime >= YVC_frameInterval) {
                    YVC_captureAndEnqueueVideoFrame()
                    lastCaptureTime = currentTime
                }
                delay(1)
            }
        }
    }

    private fun YVC_launchAudioCaptureProducer() {
        coroutineScope.launch(Dispatchers.Default) {
            audioManager.startAudioCapture { audioData ->
                YVC_audioQueue.send(audioData)
            }
        }
    }

    private fun YVC_launchVideoConsumer() {
        coroutineScope.launch(Dispatchers.IO) {
            while (YVC_isActive) {
                val videoData = YVC_videoQueue.receive()
                YVC_sendVideoToServer(videoData)
            }
        }
    }

    private fun YVC_launchAudioConsumer() {
        coroutineScope.launch(Dispatchers.IO) {
            while (YVC_isActive) {
                val audioData = YVC_audioQueue.receive()
                YVC_sendAudioToServer(audioData)
            }
        }
    }

    private suspend fun YVC_captureAndEnqueueVideoFrame() {
        cameraManager.captureImage { imageData ->
            coroutineScope.launch {
                YVC_videoQueue.send(imageData)
            }
        }
    }
    private suspend fun YVC_sendVideoToServer(videoData: ByteArray) {
        try {
            clientSocket?.let { socket ->
                if (!socket.isClosed) {
                    val outputStream = socket.getOutputStream()
                    outputStream.write("VIDEO".toByteArray())
                    outputStream.write(ByteBuffer.allocate(4).putInt(videoData.size).array())
                    outputStream.write(videoData)
                    outputStream.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending video to server", e)
        }
    }

    private suspend fun YVC_sendAudioToServer(audioData: ByteArray) {
        try {
            clientSocket?.let { socket ->
                if (!socket.isClosed) {
                    val outputStream = socket.getOutputStream()
                    outputStream.write("AUDIO".toByteArray())
                    outputStream.write(ByteBuffer.allocate(4).putInt(audioData.size).array())
                    outputStream.write(audioData)
                    outputStream.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio to server", e)
        }
    }

    private fun YVC_receiveVideoAndAudio() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val inputStream = clientSocket?.getInputStream()
                val buffer = ByteArray(1024)
                while (YVC_isActive) {
                    val type = String(buffer, 0, inputStream?.read(buffer, 0, 5) ?: 0)
                    val sizeBuffer = ByteArray(4)
                    inputStream?.read(sizeBuffer)
                    val size = ByteBuffer.wrap(sizeBuffer).int
                    val data = ByteArray(size)
                    inputStream?.read(data)

                    when (type) {
                        "VIDEO" -> YVC_updateVideoDisplay(data)
                        "AUDIO" -> YVC_playAudioData(data)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error receiving video/audio data", e)
            }
        }
    }

    private suspend fun YVC_updateVideoDisplay(imageData: ByteArray) {
        withContext(Dispatchers.Main) {
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            remoteVideoView.setImageBitmap(bitmap)
        }
    }

    private suspend fun YVC_playAudioData(audioData: ByteArray) {
        audioManager.playAudio(audioData)
    }


    private suspend fun updateVideoDisplay(imageData: ByteArray) {
        withContext(Dispatchers.Main) {
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            remoteVideoView.setImageBitmap(bitmap)
        }
    }

    private suspend fun playAudioData(audioData: ByteArray) {
        audioManager.playAudio(audioData)
    }

    private suspend fun sendAudioToServer(audioData: ByteArray) {
        // Implement the logic to send audio data to the server
        // You can use the existing socket connection or create a new one for audio
    }

    private fun stopAudioCapture() {
        audioManager.stopAudioCapture()
    }

    private suspend fun receiveAudioFromServer(audioData: ByteArray) {
        audioManager.playAudio(audioData)
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
        networkCoroutineScope.cancel() // Add this line
        tts.stop()
        tts.shutdown()
        clientSocket?.close()
        coroutineScope.cancel()
        stopScreenSharing()
        windowManager.removeView(videoOverlayView)
    }

    private fun stopScreenSharing() {
        virtualDisplay?.release()
        imageReader?.close()
        clientSocket?.close()
        clientSocket = null
    }

}

