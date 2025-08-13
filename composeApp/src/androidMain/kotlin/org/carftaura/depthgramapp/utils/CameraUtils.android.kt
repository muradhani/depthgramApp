package org.carftaura.depthgramapp.utils

import android.Manifest
import android.R.id.input
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
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
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.ArSceneView
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder


private var globalLatestFrame: Frame? = null

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


    LaunchedEffect(latestFrame) {
        globalLatestFrame = latestFrame
    }

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
                            latestFrame = frame

                            // Get camera intrinsics
                            val intrinsics = frame.camera.imageIntrinsics
                            val fx = intrinsics.focalLength[0]
                            val fy = intrinsics.focalLength[1]
                            val cx = intrinsics.principalPoint[0]
                            val cy = intrinsics.principalPoint[1]
                            val width = intrinsics.imageDimensions[0]
                            val height = intrinsics.imageDimensions[1]
                            try {
                                if (frame.camera.trackingState == TrackingState.PAUSED) {

                                    frame.acquireCameraImage().use { cameraImage ->  // <-- use{} ensures close()

                                        val jpegData = convertYuvToJpeg(cameraImage, 80)
                                        if (jpegData != null) {
                                            // Create combined data: [intrinsics] + [jpeg]
                                            val intrinsicsData = ByteArray(24).apply {
                                                val buffer = ByteBuffer.wrap(this)
                                                    .order(ByteOrder.LITTLE_ENDIAN)
                                                buffer.putFloat(fx)
                                                buffer.putFloat(fy)
                                                buffer.putFloat(cx)
                                                buffer.putFloat(cy)
                                                buffer.putInt(width)
                                                buffer.putInt(height)
                                            }

                                            val combinedData = intrinsicsData + jpegData
                                            sendImageToPC(combinedData)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("CameraPreview", "Error processing frame: ${e.message}")
                            }

                        }
                    }
                }
            )
        }
        Text(
            text = logText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

private fun convertYuvToJpeg(image: Image, quality: Int): ByteArray? {
    return try {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(
            nv21, ImageFormat.NV21,
            image.width, image.height, null
        )

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, image.width, image.height),
            quality,
            out
        )
        val options = BitmapFactory.Options().apply {
            inSampleSize = 2
        }
        val scaledBitmap = BitmapFactory.decodeByteArray(
            out.toByteArray(), 0, out.size(), options
        )
        val scaledOut = ByteArrayOutputStream()
        scaledBitmap?.compress(Bitmap.CompressFormat.JPEG, quality, scaledOut)

        scaledOut.toByteArray()
    } catch (e: Exception) {
        Log.e("ImageConvert", "YUV to JPEG failed", e)
        null
    }
}

fun sendImageToPC(data: ByteArray) {
    Thread {
        try {
            val socket = Socket("192.168.0.203", 8080)
            val output = DataOutputStream(socket.getOutputStream())
            output.writeInt(1)
            output.writeInt(data.size)
            output.write(data)

            while (!socket.isClosed) {
                val msgType = input

                if (msgType == 3) {
                    val x = input
                    val y = input
                    println("📍 Click from desktop: $x, $y")


                    val distance = getDistanceAtPixel(x, y)


                    output.writeInt(2)
                    output.writeFloat(distance!!)
                    output.flush()
                }
            }
            output.flush()
            socket.close()
        } catch (e: Exception) {
            Log.e("SocketSend", "Failed to send image", e)
        }
    }.start()
}

fun getDistanceAtPixel(x: Int, y: Int): Float? {
    return globalLatestFrame?.hitTest(x.toFloat(), y.toFloat())[0]?.distance
}
