package org.carftaura.depthgramapp.utils

import android.Manifest
import android.content.pm.PackageManager
import android.media.Image
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

    var logText by remember { mutableStateOf("Starting...") }
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

    // Initialize ARCore session
    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission && session == null) {
            try {
                Session(context).also { arSession ->
                    Config(arSession).apply {
                        depthMode = Config.DepthMode.AUTOMATIC
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    }.let { arSession.configure(it) }
                    session = arSession
                    logText = "AR session initialized."
                }
            } catch (e: Exception) {
                val error = "ARCore session setup failed: ${e.message}"
                Log.e("ARDepthPreview", error, e)
                logText = error
            }
        }
    }

    // UI layout
    Column(modifier = modifier.fillMaxSize()) {
        var arSceneView: ArSceneView? = remember { null }

        if (session != null) {
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { ctx ->
                    try {
                        ArSceneView(ctx).apply {
                            setupSession(session!!)
                            arSceneView = this

                            this.scene.addOnUpdateListener {
                                try {
                                    val frame = this.arFrame ?: return@addOnUpdateListener
                                    val depthImage = frame.acquireDepthImage16Bits()
                                    val confidence = frame.acquireRawDepthConfidenceImage()
                                    val cameraIntrinsics = frame.camera.imageIntrinsics
                                    depthImage.close()
                                } catch (e: Exception) {
                                    val err = "Depth image unavailable: ${e.message}"
                                    Log.e("DepthStream", err, e)
                                    logText = err
                                }
                            }
                        }
                    } catch (e: Exception) {
                        val err = "ArSceneView failed: ${e.message}"
                        Log.e("ARDepthPreview", err, e)
                        logText = err
                        ArSceneView(ctx)
                    }
                }
            )

            // Proper lifecycle handling
            DisposableEffect(Unit) {
                try {
                    arSceneView?.resume()
                } catch (e: Exception) {
                    val err = "Failed to resume AR view: ${e.message}"
                    Log.e("ARDepthPreview", err, e)
                    logText = err
                }

                onDispose {
                    try {
                        arSceneView?.pause()
                    } catch (e: Exception) {
                        Log.e("ARDepthPreview", "Pause failed", e)
                    }
                }
            }
        }

        // Display logs on screen
        Text(
            text = logText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = MaterialTheme.typography.bodyLarge
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

// Optional: socket function (unused here)
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
