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
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.sceneform.ArSceneView
import java.io.DataOutputStream
import java.net.Socket

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
                                    val thisFrameHasNewDepthData = frame.timestamp == depthImage.timestamp
                                    if (thisFrameHasNewDepthData) {
                                        val confidenceMap = frame.acquireRawDepthConfidenceImage()
                                        val cameraIntrinsics = frame.camera.imageIntrinsics
                                        val width = depthImage.width
                                        val height = depthImage.height
                                        integrateNewImage(confidenceMap,cameraIntrinsics,width,height)
                                    }
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

fun integrateNewImage(
    confidenceMap: Image,
    cameraIntrinsics: CameraIntrinsics,
    width: Int,
    height: Int
) {

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

/**
 * Returns the depth (in meters) and confidence at a given pixel (x, y).
 *
 * @param depthImage ARCore raw depth image (uint16, mm).
 * @param confidenceImage ARCore raw depth confidence image (uint8).
 * @param px X coordinate (0..width-1).
 * @param py Y coordinate (0..height-1).
 * @return Pair(depthMeters, confidenceLevel) or null if invalid pixel.
 */
fun getDepthAndConfidenceAtPixel(
    depthImage: Image,
    confidenceImage: Image,
    px: Int,
    py: Int
): Pair<Float, String>? {
    val width = depthImage.width
    val height = depthImage.height
    if (px !in 0 until width || py !in 0 until height) {
        return null
    }
    val depthBuffer = depthImage.planes[0].buffer.asShortBuffer()
    val index = py * width + px
    val depthMm = depthBuffer.get(index).toInt() and 0xFFFF

    if (depthMm == 0 || depthMm == 65535) {
        return null
    }

    val depthMeters = depthMm / 1000f

    val confBuffer = confidenceImage.planes[0].buffer
    val confVal = confBuffer.get(index).toInt() and 0xFF
    val confidenceLevel = when {
        confVal in 0..85 -> "Low"
        confVal in 86..170 -> "Medium"
        confVal in 171..255 -> "High"
        else -> "Unknown"
    }

    return depthMeters to confidenceLevel
}
