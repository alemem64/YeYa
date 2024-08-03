package com.example.yeya_ver2

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import android.hardware.camera2.CameraManager as Camera2Manager

class CameraManager(private val context: Context) {
    private val TAG = "CameraManager"
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var imageReader: ImageReader
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private lateinit var cameraManager: Camera2Manager

    private fun checkCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startCamera(onImageAvailable: (ByteArray) -> Unit) {
        if (!checkCameraPermission(context)) {
            Log.e(TAG, "Camera permission not granted")
            return
        }

        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as Camera2Manager
        val cameraId = cameraManager.cameraIdList.first {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }

        imageReader = ImageReader.newInstance(360, 480, ImageFormat.JPEG, 2)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            val compressedImageData = compressImage(image)
            onImageAvailable(compressedImageData)
            image.close()
        }, cameraHandler)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession()
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
                camera.close()
            }
        }, cameraHandler)
    }

    private fun createCaptureSession() {
        val surface = imageReader.surface
        cameraDevice.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                }.build()
                session.setRepeatingRequest(captureRequest, null, cameraHandler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Failed to configure camera session")
            }
        }, cameraHandler)
    }

    fun stopCamera() {
        try {
            captureSession.close()
            cameraDevice.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera: ${e.message}")
        }
        cameraThread.quitSafely()
    }

    private fun compressImage(image: Image): ByteArray {
        val planes = image.planes
        val buffer = planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val yuvImage = YuvImage(
            data,
            ImageFormat.NV21,
            image.width,
            image.height,
            null
        )
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
        return out.toByteArray()
    }
}