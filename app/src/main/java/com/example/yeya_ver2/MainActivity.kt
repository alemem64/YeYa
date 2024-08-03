package com.example.yeya_ver2

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private val SCREEN_CAPTURE_PERMISSION_REQUEST_CODE = 1
    private val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 2
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 3
    private val WIFI_PERMISSION_REQUEST_CODE = 4
    private val MULTICAST_PERMISSION_REQUEST_CODE = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "onCreate called")

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        if (!isAccessibilityServiceEnabled()) {
            requestAccessibilityService()
        } else if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        } else if (!isRecordAudioPermissionGranted()) {
            requestRecordAudioPermission()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotificationPermissionGranted()) {
            requestNotificationPermission()
        } else if (!isWifiPermissionGranted()) {
            requestWifiPermission()
        } else if (!isMulticastPermissionGranted()) {
            requestMulticastPermission()
        } else {
            startScreenCaptureIntent()
        }
    }

    private fun isRecordAudioPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRecordAudioPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            RECORD_AUDIO_PERMISSION_REQUEST_CODE
        )
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun isWifiPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestWifiPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_WIFI_STATE),
            WIFI_PERMISSION_REQUEST_CODE
        )
    }

    private fun isMulticastPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMulticastPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE),
            MULTICAST_PERMISSION_REQUEST_CODE
        )
    }

    private fun requestAccessibilityService() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivityForResult(intent, ACCESSIBILITY_SETTINGS_REQUEST_CODE)
        Toast.makeText(this, "Please enable Yeya Accessibility Service", Toast.LENGTH_LONG).show()
    }

    private fun requestOverlayPermission() {
        Log.d(TAG, "Requesting overlay permission")
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
    }

    private fun startScreenCaptureIntent() {
        Log.d(TAG, "Starting screen capture intent")
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            RECORD_AUDIO_PERMISSION_REQUEST_CODE,
            WIFI_PERMISSION_REQUEST_CODE,
            MULTICAST_PERMISSION_REQUEST_CODE,
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    checkAndRequestPermissions()
                } else {
                    Log.e(TAG, "Permission denied: $requestCode")
                    Toast.makeText(this, "This permission is required for the app to function properly", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            ACCESSIBILITY_SETTINGS_REQUEST_CODE -> {
                if (isAccessibilityServiceEnabled()) {
                    checkAndRequestPermissions()
                } else {
                    Toast.makeText(this, "Accessibility service is required", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            OVERLAY_PERMISSION_REQUEST_CODE -> {
                if (Settings.canDrawOverlays(this)) {
                    checkAndRequestPermissions()
                } else {
                    Log.e(TAG, "Overlay permission denied")
                    Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            SCREEN_CAPTURE_PERMISSION_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Log.d(TAG, "Screen capture permission granted")
                    startOverlayService(resultCode, data)
                } else {
                    Log.e(TAG, "Screen capture permission denied")
                    Toast.makeText(this, "Screen capture permission is required", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        Log.d(TAG, "Starting OverlayService")
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }
        startForegroundService(intent)
        finish()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { it.id == "$packageName/.YeyaAccessibilityService" }
    }

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 0
        private const val ACCESSIBILITY_SETTINGS_REQUEST_CODE = 3
    }
}