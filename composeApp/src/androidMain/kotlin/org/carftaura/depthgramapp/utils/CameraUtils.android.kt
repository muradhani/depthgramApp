package org.carftaura.depthgramapp.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket

@Composable
actual fun CameraPreview(modifier: Modifier) {
    AndroidCameraPreview()
}

@Composable
fun AndroidCameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    val estimator = DepthEstimator()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    LaunchedEffect(Unit) { }
    if (hasPermission && lifecycleOwner != null) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                startCamera(
                    previewView = previewView,
                    context = ctx,
                    lifecycleOwner = lifecycleOwner,
                    estimator
                )
                previewView
            },
            modifier = modifier
        )
    }
}

private fun startCamera(
    previewView: PreviewView,
    context: Context,
    lifecycleOwner: LifecycleOwner,
    estimator: DepthEstimator) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { image ->
                    CoroutineScope(Dispatchers.Default).launch {
                    val bitmap = image.toBitmap()
                    val depth: Bitmap = estimator.estimateDepth(bitmap)
                    val stream = ByteArrayOutputStream()
                    depth.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    val byteArray: ByteArray = stream.toByteArray()
                    sendImageToPC(byteArray)
                    image.close()
                    }
                }
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e("CameraX", "Use case binding failed", e)
        }
    }, ContextCompat.getMainExecutor(context))
}

fun ImageProxy.toJpegByteArray(): ByteArray {
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

    val yuvImage = android.graphics.YuvImage(
        nv21,
        android.graphics.ImageFormat.NV21,
        width,
        height,
        null
    )

    val outputStream = java.io.ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 80, outputStream)

    return outputStream.toByteArray()
}



fun sendImageToPC(data: ByteArray) {
    Thread {
        try {
            val socket = Socket("127.0.0.1", 8080) // ← replace with your PC IP
            val output = DataOutputStream(socket.getOutputStream())
            output.writeInt(data.size)  // Send length first
            output.write(data)          // Then raw bytes
            output.flush()
            socket.close()
        } catch (e: Exception) {
            Log.e("SocketSend", "Failed to send image", e)
        }
    }.start()
}