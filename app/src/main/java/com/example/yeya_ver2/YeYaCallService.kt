package com.example.yeya_ver2

import android.app.Service
import android.content.Intent
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

class YeYaCallService : Service() {
    private val TAG = "YeYaCallService"
    private lateinit var windowManager: WindowManager
    private lateinit var imageView: ImageView
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupImageView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, "YeYaCallServiceChannel")
            .setContentTitle("YeYa Call")
            .setContentText("Screen sharing in progress")
            .setSmallIcon(R.drawable.ic_notification)
            .build()

        startForeground(2, notification)

        return START_STICKY
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