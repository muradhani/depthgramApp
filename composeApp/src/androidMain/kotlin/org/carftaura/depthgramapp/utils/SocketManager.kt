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
                listenForMessages({ x,y ->
                    scope.launch {
                        FrameProcessor.getDistanceAtPixel(
                            x.toFloat(), y.toFloat(),
                            onResult = { distance ->
                                distance?.let {
                                    scope.launch { sendDistance(it) }
                                }
                                Log.e("SocketManagerPC", "the distance Pc $distance")
                            }
                        )

                    }
                })
            isConnected = true

            controlSocket = Socket(HOST, CONTROL_PORT)
            controlOutput = DataOutputStream(controlSocket!!.getOutputStream())
            controlInput = DataInputStream(controlSocket!!.getInputStream())


            Log.i("SocketManager", "Connected to $HOST:$PORT")
        } catch (e: Exception) {
            Log.e("SocketManager", "Connection failed", e)
        }
    }

     fun sendImage(data: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            if (!isConnected) return@launch
            try {
                output.writeInt(data.size)
                output.write(data)
                output.flush()
            } catch (e: Exception) {
                Log.e("SocketManager", "Failed to send image", e)
            }
        }
    }

    suspend fun listenForMessages(onTouch: (Int, Int) -> Unit) {
        withContext(Dispatchers.IO){
            try {
                while (isConnected) {
                    controlInput?.let {
                        val size = it.readInt()
                        val x = it.readInt()
                        val y = it.readInt()
                        Log.e("SocketManagerPC", "on touch x :$x and y $y")
                        withContext(Dispatchers.Default){
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

    suspend fun sendDistance(distance: Float) {
        withContext(Dispatchers.IO) {
            if (!isConnected) return@withContext
            try {
                controlOutput?.let {
                    it.writeFloat(distance)
                    it.flush()
                }
            } catch (e: Exception) {
                Log.e("SocketManager", "Failed to send distance", e)
            }
        }
    }
}
