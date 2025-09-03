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
import org.json.JSONObject

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
                // Here assume listenForMessages now gives a list of points instead of one x, y
                listenForMessages { points: List<Pair<Float, Float>> ->
                    launch(Dispatchers.Default) {
                        val distances = FrameProcessor.getDistancesAtPixels(points)
                        if (distances.isNotEmpty()) {
                            sendDistances(distances)
                        }
                    }
                }
            }

            Log.i("SocketManager", "Connected to $HOST:$PORT")
        } catch (e: Exception) {
            Log.e("SocketManager", "Connection failed", e)
        }
    }

    /*suspend fun initConnection() = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext

        try {
            socket = Socket(HOST, PORT)
            output = DataOutputStream(socket.getOutputStream())
            input = DataInputStream(socket.getInputStream())
            isConnected = true
            scope.launch {
                listenForMessages { x, y ->
                    launch(Dispatchers.Default) {
                        val distance = FrameProcessor.getDistanceAtPixels(x.toFloat(), y.toFloat())
                        distance?.let { sendDistance(it) }
                    }
                }
            }
            Log.i("SocketManager", "Connected to $HOST:$PORT")
        } catch (e: Exception) {
            Log.e("SocketManager", "Connection failed", e)
        }
    }*/

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

    suspend fun listenForMessages(onTouch: (List<Pair<Float, Float>>) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                while (isConnected) {
                    val msgType = input.readInt()
                    if (msgType == 3) {
                        // number of points in the list
                        val size = input.readInt()
                        val points = mutableListOf<Pair<Float, Float>>()

                        for (i in 0 until size) {
                            val x = input.readFloat()
                            val y = input.readFloat()
                            points.add(Pair(x, y))
                        }

                        Log.e("SocketManagerPC", "Received points: $points")
                        onTouch(points)
                    }
                }
            } catch (e: Exception) {
                Log.e("SocketManager", "Listen loop stopped", e)
                isConnected = false
            }
        }
    }
    /*suspend fun listenForMessages(onTouch: (Int, Int) -> Unit) {
        withContext(Dispatchers.IO){
            try {
                while (isConnected) {
                    val msgType = input.readInt()
                    if (msgType == 3) {
                        val size = input.readInt()
                        val x = input.readInt()
                        val y = input.readInt()
                        Log.e("SocketManagerPC", "on touch x :$x and y $y")
                        onTouch(x, y)
                    }
                }
            } catch (e: Exception) {
                Log.e("SocketManager", "Listen loop stopped", e)
                isConnected = false
            }
        }
    }*/
    private fun sendDistances(points: List<Map<String, Float>>) {
        try {
            if (points.size < 3) return  // Make sure we have TL, TR, BR, BL

            val json = JSONObject()
            json.put("TL", JSONObject(points[0]))
            json.put("TR", JSONObject(points[1]))
            json.put("BR", JSONObject(points[2]))
            //json.put("BL", JSONObject(points[3]))

            // Convert to string and send
            val message = json.toString() + "\n"  // newline for delimiter
            Log.d("SocketManager", "Accurate points JSON: $message")

            synchronized(writeLock) {
                if (!isConnected) return
                output.write(message.toByteArray(Charsets.UTF_8))
                output.flush()
            }

        } catch (e: Exception) {
            Log.e("SocketManager", "Failed to send distances", e)
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
}
