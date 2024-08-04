package com.example.yeya_ver2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.net.Socket
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Looper
import java.io.OutputStream
import android.os.Handler


class YeYaCallService : Service() {
    private val TAG = "YeYaCallService"
    private lateinit var windowManager: WindowManager
    private lateinit var imageView: ImageView
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val CHANNEL_ID = "YeYaCallServiceChannel"

    companion object {
        var instance: YeYaCallService? = null
    }


    override fun onCreate() {
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == "UPDATE_SCREEN_SHARE") {
            val imageData = intent.getByteArrayExtra("imageData")
            if (imageData != null) {
                updateScreenShare(imageData)
            }
        } else {
            val clientScreenWidth = intent?.getIntExtra("clientScreenWidth", 0) ?: 0
            val clientScreenHeight = intent?.getIntExtra("clientScreenHeight", 0) ?: 0
            val outputStream = SocketManager.getOutputStream()

            if (outputStream != null) {
                startYeYaCallActivity(clientScreenWidth, clientScreenHeight, outputStream)
            } else {
                Log.e(TAG, "Client socket output stream is null")
            }
        }

        return START_STICKY
    }

    private fun startYeYaCallActivity(width: Int, height: Int, outputStream: OutputStream) {
        val activityIntent = Intent(this, YeYaCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("clientScreenWidth", width)
            putExtra("clientScreenHeight", height)
        }
        startActivity(activityIntent)

        Handler(Looper.getMainLooper()).postDelayed({
            val activity = YeYaCallActivity.instance
            if (activity != null) {
                activity.setClientScreenInfo(width, height, outputStream)
                Log.d(TAG, "YeYaCallActivity initialized with screen info")
            } else {
                Log.e(TAG, "YeYaCallActivity instance is null")
            }
        }, 500) // Delay to ensure activity is created
    }

    fun updateScreenShare(imageData: ByteArray) {
        val intent = Intent(this, YeYaCallActivity::class.java)
        intent.action = "UPDATE_SCREEN_SHARE"
        intent.putExtra("imageData", imageData)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "YeYa Call Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun setupImageView() {
        imageView = ImageView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        windowManager.addView(imageView, params)
    }

    fun updateImage(byteArray: ByteArray) {
        coroutineScope.launch(Dispatchers.Main) {
            val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
            imageView.setImageBitmap(bitmap)
        }
    }



    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        windowManager.removeView(imageView)
        coroutineScope.cancel()
        instance = null
    }
}