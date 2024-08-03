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




    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        setupOverlay()
        initializeSpeechRecognizer()
        tts = TextToSpeech(this, this)
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

    private fun processEvent(message: String) {
        val parts = message.split("|")
        if (parts.size != 2 || parts[0] != "CLICK") {
            Log.e(TAG, "Invalid event message: $message")
            return
        }

        try {
            val (x, y) = parts[1].split(",").map { it.toInt() }
            Log.d(TAG, "Processing click event: x=$x, y=$y")
            YeyaAccessibilityService.getInstance()?.performClickAtCoordinates(x, y)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing click coordinates: ${e.message}")
        }
    }

    private fun performRemoteClick(x: Int, y: Int) {
        YeyaAccessibilityService.getInstance()?.let { service ->
            val clickPath = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }

            val clickStroke = GestureDescription.StrokeDescription(clickPath, 0, 100) // 100ms duration for click
            val gestureBuilder = GestureDescription.Builder().addStroke(clickStroke)
            val gesture = gestureBuilder.build()

            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Remote click completed at ($x, $y)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.e(TAG, "Remote click cancelled at ($x, $y)")
                }
            }, null)
        } ?: Log.e(TAG, "YeyaAccessibilityService instance is null")
    }

//    private fun handleIncomingTouchEvents() {
//        coroutineScope.launch(Dispatchers.IO) {
//            try {
//                val reader = BufferedReader(InputStreamReader(clientSocket?.inputStream))
//                while (isActive) {
//                    val message = reader.readLine() ?: break
//                    Log.d(TAG, "Received touch event: $message")
//                    processTouchEvent(message)
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error handling incoming touch events", e)
//            }
//        }
//    }
//
//    private fun processTouchEvent(message: String) {
//        val parts = message.split("|")
//        if (parts.size != 3 || parts[0] != "TOUCH") {
//            Log.e(TAG, "Invalid touch event message: $message")
//            return
//        }
//
//        val action = when (parts[1]) {
//            "DOWN" -> MotionEvent.ACTION_DOWN
//            "MOVE" -> MotionEvent.ACTION_MOVE
//            "UP" -> MotionEvent.ACTION_UP
//            else -> {
//                Log.e(TAG, "Unknown touch action: ${parts[1]}")
//                return
//            }
//        }
//
//        val (x, y) = parts[2].split(",").map { it.toInt() }
//        Log.d(TAG, "Processing touch event: action=$action, x=$x, y=$y")
//
//        // Use the same method that works for AI agent clicks
//        performTouchAction(x, y, action)
//    }
//
//    private fun performTouchAction(x: Int, y: Int, action: Int) {
//        val path = Path()
//        path.moveTo(x.toFloat(), y.toFloat())
//
//        val gestureBuilder = GestureDescription.Builder()
//        val gestureStroke = GestureDescription.StrokeDescription(path, 0, 1)
//        gestureBuilder.addStroke(gestureStroke)
//
//        val gesture = gestureBuilder.build()
//        YeyaAccessibilityService.getInstance()?.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
//            override fun onCompleted(gestureDescription: GestureDescription?) {
//                Log.d(TAG, "Gesture completed: action=$action, x=$x, y=$y")
//            }
//            override fun onCancelled(gestureDescription: GestureDescription?) {
//                Log.d(TAG, "Gesture cancelled: action=$action, x=$x, y=$y")
//            }
//        }, null)
//    }


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
    }

    private fun stopScreenSharing() {
        virtualDisplay?.release()
        imageReader?.close()
        clientSocket?.close()
        clientSocket = null
    }

}

