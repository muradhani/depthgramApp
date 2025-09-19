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
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.ArSceneView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.carftaura.depthgramapp.utils.FrameProcessor.calculateTwoPointsDistance


@SuppressLint("ClickableViewAccessibility")
@Composable
actual fun CameraPreview(modifier: Modifier) {
    val context = LocalContext.current
    var logText by remember { mutableStateOf("Starting...") }
    var session by remember { mutableStateOf<Session?>(null) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    LaunchedEffect(Unit) {
        SocketManager.initConnection()
    }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission && session == null) {
            try {
                Session(context).also { arSession ->
                    Config(arSession).apply {
                        depthMode = Config.DepthMode.AUTOMATIC
                        instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        focusMode = Config.FocusMode.AUTO
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
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    var firstPoint: Pair<Float, Float>? by remember { mutableStateOf(null) }
    var secondPoint: Pair<Float, Float>? by remember { mutableStateOf(null) }
    var arSceneView: ArSceneView? by remember { mutableStateOf(null) }

    // UI layout
    Column(modifier = modifier.fillMaxSize()) {
        if (session != null) {
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                factory = { ctx ->
                    ArSceneView(ctx).apply {
                        setupSession(session!!)
                        resume()
                        arSceneView = this
                        FrameProcessor.arSceneView = this
                        this.setOnTouchListener { _, event ->
                            val frame = this.arFrame ?: return@setOnTouchListener true
                            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                                if (frame.camera.trackingState == TrackingState.TRACKING) {
                                    if (firstPoint == null) {
                                        firstPoint = Pair(event.x, event.y)
                                        logText = "First point selected at (${event.x}, ${event.y})"
                                        Log.e("ImagePointTransform", "First point selected at (${event.x}, ${event.y})")
                                    } else {
                                        secondPoint = Pair(event.x, event.y)
                                        val distance = FrameProcessor.calculateTwoPointsDistance(
                                            firstPoint!!.first, firstPoint!!.second,
                                            secondPoint!!.first, secondPoint!!.second
                                        )
                                        Log.e("ImagePointTransform", "second point selected at (${event.x}, ${event.y})")
                                        Log.e("ImagePointTransform", "distance $distance")
                                        logText = "Distance: $distance meters"
                                        firstPoint = null
                                        secondPoint = null
                                    }
                                } else {
                                    logText = "AR not tracking yet"
                                }
                            }
                            true
                        }
                        this.scene.addOnUpdateListener {
                            val frame = this.arFrame ?: return@addOnUpdateListener
                            FrameProcessor.lastFrame = frame
                            if (frame.camera.trackingState == TrackingState.TRACKING) {
                                try {
                                    frame.acquireCameraImage().use { cameraImage ->
                                        val bytes = FrameProcessor.convertFrameToBytes(cameraImage, frame)
                                        if (bytes != null) {
                                            CoroutineScope(Dispatchers.IO).launch {
                                                SocketManager.sendImage(bytes)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("ARDepthPreview", "Image processing skipped", e)
                                }
                            }
                        }
                    }
                }
            )

            DisposableEffect(Unit) {
                onDispose {
                    try {
                        arSceneView?.pause()
                        arSceneView?.destroy()
                        arSceneView = null
                        FrameProcessor.lastFrame = null
                        session?.close()
                        session = null
                        Log.i("ARDepthPreview", "AR resources cleaned up")
                    } catch (e: Exception) {
                        Log.e("ARDepthPreview", "Dispose failed", e)
                    }
                }
            }
        }

//        Text(
//            text = logText,
//            modifier = Modifier
//                .fillMaxWidth()
//                .padding(16.dp),
//            style = MaterialTheme.typography.bodyLarge
//        )
    }
}

