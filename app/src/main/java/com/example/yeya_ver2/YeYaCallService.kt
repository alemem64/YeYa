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

class YeYaCallService : Service() {
    private val TAG = "YeYaCallService"
    private lateinit var windowManager: WindowManager
    private lateinit var imageView: ImageView
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val CHANNEL_ID = "YeYaCallServiceChannel"


    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "UPDATE_SCREEN_SHARE") {
            val imageData = intent.getByteArrayExtra("imageData")
            if (imageData != null) {
                updateScreenShare(imageData)
            }
        } else {
            val notificationIntent = Intent(this, YeYaCallActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("YeYa Call")
                .setContentText("Screen sharing in progress")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .build()

            startForeground(1, notification)

            // Start YeYaCallActivity
            val activityIntent = Intent(this, YeYaCallActivity::class.java)
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(activityIntent)
        }

        return START_STICKY
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
    }
}