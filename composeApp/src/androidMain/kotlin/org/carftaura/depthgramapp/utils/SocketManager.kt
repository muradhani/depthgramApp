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
                listenForMessages (
                    onTouch = { x, y ->
                        launch(Dispatchers.Default) {
                            FrameProcessor.imagePointToScreenTransform(x,y)?.let { point ->
                                val distance = FrameProcessor.getDistanceAtPixel(point.first, point.second)
                                distance?.let { sendDistance(it) }
                            }
                        }
                    },
                    calcDistance = { x1, y1, x2, y2 ->
                        launch(Dispatchers.Default) {
                            val startPoint = FrameProcessor.imagePointToScreenTransform(x1,y1)
                            val endPoint = FrameProcessor.imagePointToScreenTransform(x2,y2)
                            if (startPoint != null && endPoint != null){
                                val distance = FrameProcessor
                                    .calculateTwoPointsDistance(
                                        startPoint.first,
                                        startPoint.second,
                                        endPoint.first,
                                        endPoint.second)
                                Log.e("ImagePointTransform", "the distance between 2 points $distance")

                                distance?.let { sendLength(it) }
                            }
                        }
                    }
                )
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

    suspend fun listenForMessages(onTouch: (Float, Float) -> Unit, calcDistance: (Float, Float, Float, Float) -> Unit) {
        withContext(Dispatchers.IO){
            try {
                while (isConnected) {
                    val msgType = input.readInt()
                    when(msgType){
                        3 -> {
                            val size = input.readInt()
                            val x = input.readFloat()
                            val y = input.readFloat()
                            Log.e("SocketManagerPC", "on touch x :$x and y $y")
                            onTouch(x, y)
                        }
                        4 -> {
                            val size = input.readInt()
                            val x1 = input.readFloat()
                            val y1 = input.readFloat()
                            val x2 = input.readFloat()
                            val y2 = input.readFloat()
                            calcDistance(x1, y1, x2, y2)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SocketManager", "Listen loop stopped", e)
                isConnected = false
            }
        }
    }

    fun sendDistance(distance: HashMap<String,Float>) {
        synchronized(writeLock) {
            if (!isConnected) return
            try {
                output.writeInt(2)
                output.writeFloat(distance.get("distance")!!)
                output.writeFloat(distance.get("dx")!!)
                output.writeFloat(distance.get("dy")!!)
                output.writeFloat(distance.get("dz")!!)
                output.flush()
            } catch (e: Exception) {
                Log.e("SocketManager", "Failed to send distance", e)
            }
        }
    }

    fun sendLength(distance:Float) {
        synchronized(writeLock) {
            if (!isConnected) return
            try {
                output.writeInt(5)
                output.writeFloat(distance)
                output.flush()
            } catch (e: Exception) {
                Log.e("SocketManager", "Failed to send distance", e)
            }
        }
    }
}
