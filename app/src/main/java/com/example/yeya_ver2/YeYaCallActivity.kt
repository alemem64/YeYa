package com.example.yeya_ver2

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageView

class YeYaCallActivity : AppCompatActivity() {
    private lateinit var screenShareImageView: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yeyacall)

        screenShareImageView = findViewById(R.id.screenShareImageView)
        if (screenShareImageView == null) {
            Log.e("YeYaCallActivity", "screenShareImageView not found in layout")
            finish()
            return
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
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
        }
    }
}