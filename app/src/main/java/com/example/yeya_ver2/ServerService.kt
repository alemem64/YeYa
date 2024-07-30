package com.example.yeya_ver2

import android.app.*
import android.content.Context
import android.content.Intent
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

class ServerService : Service() {
    private val TAG = "SeverService"
    private val CHANNEL_ID = "ServerServiceChannel"
    private val NOTIFICATION_ID = 2
    private var serverSocket: ServerSocket? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val networkCoroutineScope = CoroutineScope(Dispatchers.IO + Job())


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
                    Log.d("ServerService", "Received: $message")
                    writer.println("Server connected to Client")
                    Log.d("ServerService", "Sent: Server connected to Client")

                    // Wait for startYeYaCall message
                    val startYeYaCallMessage = reader.readLine()
                    if (startYeYaCallMessage == "startYeYaCall") {
                        Log.d("ServerService", "Received startYeYaCall message")
                        startYeYaCallService()
                        receiveScreenSharing(client)
                    }
                }
            } catch (e: Exception) {
                Log.e("ServerService", "Error handling client", e)
            }
        }
    }

    private fun startYeYaCallService() {
        val intent = Intent(this, YeYaCallService::class.java)
        startService(intent)
    }

    private fun receiveScreenSharing(client: Socket) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val inputStream = BufferedInputStream(client.inputStream)
                val buffer = ByteArray(1024)
                var bytesRead: Int

                while (!client.isClosed) {
                    try {
                        // Read image size
                        val sizeBuffer = StringBuilder()
                        while (true) {
                            bytesRead = inputStream.read(buffer, 0, 1)
                            if (bytesRead == -1 || buffer[0] == '\n'.toByte()) break
                            sizeBuffer.append(buffer[0].toChar())
                        }
                        val imageSize = sizeBuffer.toString().toInt()

                        // Read image data
                        val imageData = ByteArray(imageSize)
                        var totalBytesRead = 0
                        while (totalBytesRead < imageSize) {
                            bytesRead = inputStream.read(imageData, totalBytesRead, imageSize - totalBytesRead)
                            if (bytesRead == -1) break
                            totalBytesRead += bytesRead
                        }

                        // Update image in YeYaCallService
                        val intent = Intent(this@ServerService, YeYaCallService::class.java)
                        intent.putExtra("imageData", imageData)
                        startService(intent)
                    } catch (e: Exception) {
                        Log.e("ServerService", "Error receiving screen sharing data", e)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("ServerService", "Error in receiveScreenSharing", e)
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