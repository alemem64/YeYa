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
                val port = serverSocket?.localPort
                val ip = getIPAddress()
                Log.d("ServerService", "Server started on $ip:$port")

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
                }
            } catch (e: Exception) {
                Log.e("ServerService", "Error handling client", e)
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

    private fun startUdpDiscoveryListener() {
        networkCoroutineScope.launch {
            val udpPort = 8888
            DatagramSocket(udpPort).use { socket ->
                val receiveData = ByteArray(1024)
                val receivePacket = DatagramPacket(receiveData, receiveData.size)

                while (true) {
                    try {
                        socket.receive(receivePacket)
                        val message = String(receivePacket.data, 0, receivePacket.length)

                        if (message == "DISCOVER_YEYA_SERVER") {
                            val clientAddress = receivePacket.address
                            val clientPort = receivePacket.port
                            val serverPort = serverSocket?.localPort ?: continue

                            val sendData = serverPort.toString().toByteArray()
                            val sendPacket = DatagramPacket(sendData, sendData.size, clientAddress, clientPort)
                            socket.send(sendPacket)

                            Log.d(TAG, "Responded to discovery request from $clientAddress:$clientPort")
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