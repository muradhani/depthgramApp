package org.carftaura.depthgramapp.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.sceneform.ArSceneView
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer

@Composable
actual fun CameraPreview(modifier: Modifier) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<Session?>(null) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    // Request permission
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Initialize ARCore Session
    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission && session == null) {
            try {
                Session(context).also { arSession ->
                    Config(arSession).apply {
                        depthMode = Config.DepthMode.AUTOMATIC
                    }.let { arSession.configure(it) }
                    session = arSession
                }
            } catch (e: Exception) {
                Log.e("ARDepthPreview", "ARCore session setup failed", e)
            }
        }
    }

    if (session != null) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                ArSceneView(ctx).apply {
                    setupSession(session!!)
                    this.scene.addOnUpdateListener {
                        val frame = this.arFrame ?: return@addOnUpdateListener
                        try {
                            val depthImage = frame.acquireDepthImage16Bits()
                            val centerDepth = getCenterDepth(depthImage)
                            Log.d("DepthStream", "Center depth: $centerDepth meters")
                            depthImage.close()
                        } catch (e: Exception) {
                            Log.e("DepthStream", "Depth image unavailable", e)
                        }
                    }
                }
            }
        )
    }
}

fun getCenterDepth(image: Image): Float {
    val width = image.width
    val height = image.height
    val centerX = width / 2
    val centerY = height / 2
    val buffer: ByteBuffer = image.planes[0].buffer
    val shortBuffer = buffer.asShortBuffer()
    val idx = centerY * width + centerX
    val depthMM = shortBuffer.get(idx).toInt() and 0xFFFF
    return depthMM / 1000f
}

fun sendImageToPC(data: ByteArray) {
    Thread {
        try {
            val socket = Socket("127.0.0.1", 8080)
            val output = DataOutputStream(socket.getOutputStream())
            output.writeInt(data.size)
            output.write(data)
            output.flush()
            socket.close()
        } catch (e: Exception) {
            Log.e("SocketSend", "Failed to send image", e)
        }
    }.start()
}