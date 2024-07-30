import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Socket

class YeYaCallService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var callView: ImageView

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupCallView()
    }

    private fun setupCallView() {
        callView = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        )

        windowManager.addView(callView, params)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val clientAddress = intent?.getStringExtra("clientAddress")
        val clientPort = intent?.getIntExtra("clientPort", -1)

        if (clientAddress != null && clientPort != null && clientPort != -1) {
            CoroutineScope(Dispatchers.IO).launch {
                val socket = Socket(clientAddress, clientPort)
                receiveScreenSharing(socket)
            }
        }
        return START_STICKY
    }

    private suspend fun receiveScreenSharing(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = socket.getInputStream()
                val buffer = ByteArray(1024 * 1024) // Adjust buffer size as needed

                while (true) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break

                    val bitmap = BitmapFactory.decodeByteArray(buffer, 0, bytesRead)
                    withContext(Dispatchers.Main) {
                        callView.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                Log.e("YeYaCallService", "Error in screen sharing", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        windowManager.removeView(callView)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}