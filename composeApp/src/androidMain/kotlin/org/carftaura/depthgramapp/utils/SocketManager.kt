package org.carftaura.depthgramapp.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.carftaura.depthgramapp.DistanceInfo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

object SocketManager {
    private const val HOST = "192.168.0.203"
    private const val PORT = 8080
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    lateinit var socket: Socket
    lateinit var output: DataOutputStream
    lateinit var input: DataInputStream
    private val writeLock = Any()
    @Volatile
    var isConnected = false

    suspend fun initConnection() = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext

        try {
            socket = Socket(HOST, PORT)
            output = DataOutputStream(socket.getOutputStream())
            input = DataInputStream(socket.getInputStream())
            isConnected = true
            scope.launch {
                listenForMessages { points ->
                    launch(Dispatchers.Default) {
                        val distancesInfo = points.mapNotNull { (x, y) ->
                            FrameProcessor.getDistanceAtPixel(x.toFloat(), y.toFloat())
                        }
                        if (distancesInfo.isNotEmpty()) {
                            sendDistancesInfo(distancesInfo)
                        }
                    }

                }
            }

            Log.i("SocketManager", "Connected to $HOST:$PORT")
        } catch (e: Exception) {
            Log.e("SocketManager", "Connection failed", e)
        }
    }

    fun sendImage(data: ByteArray) {
        synchronized(writeLock) {
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
    }

    suspend fun listenForMessages(onTouches: (List<Pair<Int, Int>>) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                while (isConnected) {
                    val msgType = input.readInt()
                    if (msgType == 3) {
                        val size = input.readInt()
                        val points = mutableListOf<Pair<Int, Int>>()

                        repeat(size / 2) {
                            val x = input.readInt()
                            val y = input.readInt()
                            Log.e("SocketManagerPC", "on touch x :$x and y $y")
                            points.add(x to y)
                        }

                        onTouches(points)
                    }
                }
            } catch (e: Exception) {
                Log.e("SocketManager", "Listen loop stopped", e)
                isConnected = false
            }
        }
    }


    fun sendDistancesInfo(distances: List<DistanceInfo>) {
        synchronized(writeLock) {
            if (!isConnected) return
            try {
                output.writeInt(2)
                output.writeInt(distances.size)
                distances.forEach { info ->
                    output.writeFloat(info.dx)
                    output.writeFloat(info.dy)
                    output.writeFloat(info.dz)
                    output.writeFloat(info.distance)
                }
                output.flush()
            } catch (e: Exception) {
                Log.e("SocketManager", "Failed to send distances info", e)
            }
        }
    }
}
