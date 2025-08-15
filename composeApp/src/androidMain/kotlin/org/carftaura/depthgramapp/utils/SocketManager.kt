package org.carftaura.depthgramapp.utils

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

object SocketManager {
    private const val HOST = "192.168.0.203"
    private const val PORT = 8080

    lateinit var socket: Socket
    lateinit var output: DataOutputStream
    lateinit var input: DataInputStream

    @Volatile
    var isConnected = false

    fun initConnection() {
        if (isConnected) return

        try {
            socket = Socket(HOST, PORT)
            output = DataOutputStream(socket.getOutputStream())
            input = DataInputStream(socket.getInputStream())
            isConnected = true
            Log.i("SocketManager", "Connected to $HOST:$PORT")
        } catch (e: Exception) {
            Log.e("SocketManager", "Connection failed", e)
        }
    }

    fun sendImage(data: ByteArray) {
        if (!isConnected) return
        try {
            output.writeInt(1)
            output.writeInt(data.size)
            output.write(data)
            output.flush()
        } catch (e: Exception) {
            Log.e("SocketManager", "Failed to send image", e)
        }
    }

    fun listenForMessages(onTouch: (Int, Int) -> Unit) {
        Thread {
            try {
                while (isConnected) {
                    val msgType = input.readInt()
                    if (msgType == 3) {
                        val x = input.readInt()
                        val y = input.readInt()
                        onTouch(x, y)
                    }
                }
            } catch (e: Exception) {
                Log.e("SocketManager", "Listen loop stopped", e)
                isConnected = false
            }
        }.start()
    }

    fun sendDistance(distance: Float) {
        if (!isConnected) return
        try {
            output.writeInt(2)
            output.writeFloat(distance)
            output.flush()
        } catch (e: Exception) {
            Log.e("SocketManager", "Failed to send distance", e)
        }
    }
}
