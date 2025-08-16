package org.carftaura.depthgramapp.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

object SocketManager {
    private const val HOST = "192.168.0.203"
    private const val PORT = 8080

    private const val CONTROL_PORT = 8081
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    lateinit var socket: Socket

    private var controlSocket: Socket? = null

    lateinit var output: DataOutputStream
    lateinit var input: DataInputStream

    private var controlOutput: DataOutputStream? = null

    private var controlInput: DataInputStream? = null

    @Volatile
    var isConnected = false

    suspend fun initConnection() = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext

        try {
            socket = Socket(HOST, PORT)
            output = DataOutputStream(socket.getOutputStream())
            input = DataInputStream(socket.getInputStream())
            scope.launch{
                listenForMessages({ x,y ->
                    val distance = FrameProcessor.getDistanceAtPixel(x.toFloat(),y.toFloat())
                    distance?.let {  sendDistance(it) }
                    Log.e("SocketManagerPC", "the distance Pc $distance")

                })
            }

            controlSocket = Socket(HOST, CONTROL_PORT)
            controlOutput = DataOutputStream(controlSocket!!.getOutputStream())
            controlInput = DataInputStream(controlSocket!!.getInputStream())

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

    suspend fun listenForMessages(onTouch: (Int, Int) -> Unit) {
        withContext(Dispatchers.IO){
            try {
                while (isConnected) {
                    controlInput?.let {
                        val msgType = it.readInt()
                        if (msgType == 3) {
                            val size = it.readInt()
                            val x = it.readInt()
                            val y = it.readInt()
                            Log.e("SocketManagerPC", "on touch x :$x and y $y")
                            onTouch(x, y)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SocketManager", "Listen loop stopped", e)
                isConnected = false
            }
        }
    }

    fun sendDistance(distance: Float) {
        if (!isConnected) return
        try {
            controlOutput?.let {
                it.writeInt(2)
                it.writeFloat(distance)
                it.flush()
            }
        } catch (e: Exception) {
            Log.e("SocketManager", "Failed to send distance", e)
        }
    }
}
