package com.example.yeya_ver2

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.net.BindException
import java.net.InetAddress
import java.nio.ByteBuffer
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import android.Manifest

class ServerService : Service() {
    private val TAG = "SeverService"
    private val CHANNEL_ID = "ServerServiceChannel"
    private val NOTIFICATION_ID = 2
    private var serverSocket: ServerSocket? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val networkCoroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private var clientScreenWidth: Int = 0
    private var clientScreenHeight: Int = 0
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var serverCameraImageReader: ImageReader? = null
    private val cameraHandler = Handler(HandlerThread("CameraThread").apply { start() }.looper)
    private val serverCameraImageQueue = Channel<ByteArray>(Channel.BUFFERED)
    private var isServerCameraRecording = false


    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        coroutineScope.launch {
            startServer()
        }

        startUdpDiscoveryListener()

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Server Service Channel",
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
            .setContentTitle("Yeya Server")
            .setContentText("Server is running")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .build()
    }

    private suspend fun startServer() {
        withContext(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(0) // 0 means the system will pick an available port
                tcpServerPort = serverSocket?.localPort ?: 0
                val ip = getIPAddress()
                Log.d("ServerService", "Server started on $ip:$tcpServerPort")

                startUdpDiscoveryListener()

                while (true) {
                    val client = serverSocket?.accept()
                    client?.let {
                        handleClient(it)
                    }
                }
            } catch (e: Exception) {
                Log.e("ServerService", "Error starting server", e)
            }
        }
    }

    private fun handleClient(client: Socket) {
        coroutineScope.launch {
            val reader = BufferedReader(InputStreamReader(client.inputStream))
            val writer = PrintWriter(client.outputStream, true)

            try {
                val message = reader.readLine()
                if (message == "Client connected to Server") {
                    Log.d(TAG, "Received: $message")
                    writer.println("Server connected to Client")
                    Log.d(TAG, "Sent: Server connected to Client")

                    // Wait for screen dimensions
                    val dimensionsMessage = reader.readLine()
                    if (dimensionsMessage.startsWith("SCREEN_DIMENSIONS|")) {
                        val (width, height) = dimensionsMessage.split("|")[1].split(",").map { it.toInt() }
                        clientScreenWidth = width
                        clientScreenHeight = height
                        Log.d(TAG, "Received client screen dimensions: $width x $height")
                    }

                    // Wait for startYeYaCall message
                    val startYeYaCallMessage = reader.readLine()
                    if (startYeYaCallMessage == "startYeYaCall") {
                        Log.d(TAG, "Received startYeYaCall message")
                        startYeYaCallService(client)  // Pass the client socket here
                        stopOverlayService()
                        receiveScreenSharing(client)



                        startServerCameraSharing()






                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client", e)
            }
        }
    }

    private fun startServerCameraSharing() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        setupServerCamera()
    }

    private fun setupServerCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted")
            return
        }
        val cameraId = cameraManager.cameraIdList.find {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: throw RuntimeException("Front camera not found")

        serverCameraImageReader = ImageReader.newInstance(480, 480, ImageFormat.YUV_420_888, 2)
        serverCameraImageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                serverCameraImageQueue.trySend(bytes)
                image.close()
            }
        }, cameraHandler)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCameraCaptureSession()
            }
            override fun onDisconnected(camera: CameraDevice) {
                cameraDevice?.close()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
            }
        }, cameraHandler)
    }

    private fun createCameraCaptureSession() {
        val surface = serverCameraImageReader?.surface
        cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                cameraCaptureSession = session
                val captureRequest = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequest?.addTarget(surface!!)
                cameraCaptureSession?.setRepeatingRequest(captureRequest!!.build(), null, cameraHandler)
                isServerCameraRecording = true
                launchServerCameraImageProducer()
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Failed to configure camera session")
            }
        }, cameraHandler)
    }

    private fun launchServerCameraImageProducer() {
        coroutineScope.launch(Dispatchers.IO) {
            while (isServerCameraRecording) {
                val imageData = serverCameraImageQueue.receive()
                sendServerCameraImageToClient(imageData)
            }
        }
    }

    private suspend fun sendServerCameraImageToClient(imageData: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                val socket = SocketManager.getClientSocket()
                if (socket != null && !socket.isClosed) {
                    val outputStream = socket.getOutputStream()
                    Log.d(TAG, "Sending SERVER_CAMERA_IMAGE header")
                    outputStream.write("SERVER_CAMERA_IMAGE".toByteArray())
                    Log.d(TAG, "Sending image size: ${imageData.size}")
                    outputStream.write(ByteBuffer.allocate(4).putInt(imageData.size).array())
                    Log.d(TAG, "Sending image data")
                    outputStream.write(imageData)
                    outputStream.flush()
                    Log.d(TAG, "Image data sent successfully")
                } else {
                    Log.e(TAG, "Socket is null or closed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending server camera image to client", e)
            }
        }
    }




    private fun startYeYaCallService(client: Socket) {
        SocketManager.setClientSocket(client)
        val intent = Intent(this, YeYaCallService::class.java).apply {
            putExtra("clientScreenWidth", clientScreenWidth)
            putExtra("clientScreenHeight", clientScreenHeight)
        }
        startForegroundService(intent)
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        stopService(intent)
    }

    private fun receiveScreenSharing(client: Socket) {
        coroutineScope.launch(Dispatchers.IO) {
            val inputStream = BufferedInputStream(client.inputStream)
            val sizeBuffer = ByteArray(4)

            try {
                while (!client.isClosed) {
                    // Read image size
                    if (inputStream.read(sizeBuffer) == -1) break
                    val imageSize = ByteBuffer.wrap(sizeBuffer).int

                    // Read image data
                    val imageData = ByteArray(imageSize)
                    var totalBytesRead = 0
                    while (totalBytesRead < imageSize) {
                        val bytesRead = inputStream.read(imageData, totalBytesRead, imageSize - totalBytesRead)
                        if (bytesRead == -1) break
                        totalBytesRead += bytesRead
                    }

                    if (totalBytesRead == imageSize) {
                        Log.d(TAG, "Image Received (Size: $imageSize bytes)")
                        // Update image in YeYaCallService
                        val intent = Intent(this@ServerService, YeYaCallService::class.java)
                        intent.action = "UPDATE_SCREEN_SHARE"
                        intent.putExtra("imageData", imageData)
                        startService(intent)
                    } else {
                        Log.e(TAG, "Incomplete image received")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in receiveScreenSharing", e)
            } finally {
                client.close()
            }
        }
    }

    private fun getIPAddress(): String {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e("ServerService", "Error getting IP address", ex)
        }
        return "Unknown"
    }

    private val UDP_DISCOVERY_PORT_START = 8888
    private var udpDiscoveryPort = UDP_DISCOVERY_PORT_START
    private var tcpServerPort: Int = 0

    private fun startUdpDiscoveryListener() {
        networkCoroutineScope.launch {
            var socket: DatagramSocket? = null
            while (socket == null && udpDiscoveryPort < UDP_DISCOVERY_PORT_START + 100) {
                try {
                    socket = DatagramSocket(udpDiscoveryPort)
                } catch (e: BindException) {
                    Log.w(TAG, "Port $udpDiscoveryPort is in use, trying next port")
                    udpDiscoveryPort++
                }
            }

            if (socket == null) {
                Log.e(TAG, "Failed to find an available UDP port")
                return@launch
            }

            Log.d(TAG, "UDP Discovery listener started on port $udpDiscoveryPort")

            socket.use { datagramSocket ->
                val receiveData = ByteArray(1024)
                val receivePacket = DatagramPacket(receiveData, receiveData.size)

                while (true) {
                    try {
                        datagramSocket.receive(receivePacket)
                        val message = String(receivePacket.data, 0, receivePacket.length)

                        if (message == "DISCOVER_YEYA_SERVER") {
                            val clientAddress = receivePacket.address
                            val clientPort = receivePacket.port

                            val sendData = "$tcpServerPort:$udpDiscoveryPort".toByteArray()
                            val sendPacket = DatagramPacket(sendData, sendData.size, clientAddress, clientPort)
                            datagramSocket.send(sendPacket)

                            Log.d(TAG, "Responded to discovery request from $clientAddress:$clientPort with TCP port $tcpServerPort and UDP port $udpDiscoveryPort")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in UDP discovery listener", e)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serverSocket?.close()
        coroutineScope.cancel()
        networkCoroutineScope.cancel()
    }
}