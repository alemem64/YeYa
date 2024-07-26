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

        // Start foreground service first
        startForeground(NOTIFICATION_ID, createNotification())

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
        captureButton.setOnTouchListener { v, event ->
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
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (Math.abs(event.rawX - initialTouchX) < 10 && Math.abs(event.rawY - initialTouchY) < 10) {
                        vibrate()
                        takeScreenshot()
                    }
                    true
                }
                else -> false
            }
        }
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
            // Notify the media scanner about the new file
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(imageFile)
            sendBroadcast(mediaScanIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving screenshot: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun captureUIElements() {
        val uiElements = UICapture.getLatestUIElements()
        Log.d(TAG, "Captured UI elements: $uiElements")
        // Here you can handle the captured UI elements as needed
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Yeya Overlay Service")
            .setContentText("Running")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
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
        val uiElements = UICapture.getLatestUIElements()?.toString() ?: "[]"

        coroutineScope.launch {
            try {
                val claudeResponse = ClaudeApiClient.sendMessageToClaude(recognizedText, uiElements)
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
        tts.stop()
        tts.shutdown()
    }
}