package org.carftaura.depthgramapp.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
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
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.sceneform.ArSceneView
import java.io.DataOutputStream
import java.net.Socket

@SuppressLint("ClickableViewAccessibility")
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
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
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
                        focusMode = Config.FocusMode.AUTO
                        instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
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
                    ArSceneView(ctx).apply {
                        setupSession(session!!)
                        resume()
                        arSceneView = this
                        this.setOnTouchListener { _, event ->
                            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                                val hits = latestFrame?.hitTest(event.x, event.y)
                                if (!hits.isNullOrEmpty()) {
                                    val hit = hits[0]
                                    val distanceMeters = hit.distance
                                    val hitPose = hit.hitPose
                                    logText = "Distance: $distanceMeters m, Pose: $hitPose"
                                }
                            }
                            true
                        }
                        this.scene.addOnUpdateListener {
                            val frame = this.arFrame ?: return@addOnUpdateListener
                            frame.camera.imageIntrinsics
                        }
                    }
                }
            )
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