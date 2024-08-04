import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

object SocketManager {
    private var clientSocket: Socket? = null

    fun setClientSocket(socket: Socket) {
        clientSocket = socket
    }

    fun getClientSocket(): Socket? {
        return clientSocket
    }

    fun getOutputStream(): OutputStream? {
        return clientSocket?.getOutputStream()
    }

    fun getInputStream(): InputStream? {
        return clientSocket?.getInputStream()
    }

    fun closeSocket() {
        clientSocket?.close()
        clientSocket = null
    }
}