import java.net.Socket

object SocketManager {
    private var clientSocket: Socket? = null

    fun setClientSocket(socket: Socket) {
        clientSocket = socket
    }

    fun getClientSocket(): Socket? = clientSocket

    fun getOutputStream() = clientSocket?.getOutputStream()
}