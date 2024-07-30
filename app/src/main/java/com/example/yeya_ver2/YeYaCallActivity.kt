package com.example.yeya_ver2

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageView

class YeYaCallActivity : AppCompatActivity() {
    private lateinit var screenShareImageView: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yeyacall)
        screenShareImageView = findViewById(R.id.screenShareImageView)
    }

    fun updateScreenShare(imageData: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
        runOnUiThread {
            screenShareImageView.setImageBitmap(bitmap)
        }
    }
}