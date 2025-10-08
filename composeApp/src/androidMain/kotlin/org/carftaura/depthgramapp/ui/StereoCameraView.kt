package org.carftaura.depthgramapp.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.carftaura.depthgramapp.utils.ImageUtils
import org.carftaura.depthgramapp.utils.StereoCameraHandler

@Composable
fun StereoCameraPreview() {
    var hasPermission by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasPermission = granted
        }
    )

    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.CAMERA)
    }

    if (hasPermission) {
        StereoCameraFeed()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission is required.")
        }
    }
}

@Composable
private fun StereoCameraFeed() {
    val context = LocalContext.current
    var leftBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var rightBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var statusText by remember { mutableStateOf("Initializing...") }

    val cameraHandler = remember { Handler(Looper.getMainLooper()) }

    val stereoCameraHandler = remember {
        StereoCameraHandler(
            context = context,
            cameraHandler = cameraHandler,
            onFramesReady = { leftImage, rightImage, _, _ ->
                leftBitmap = ImageUtils.yuvToBitmap(leftImage)
                rightBitmap = ImageUtils.yuvToBitmap(rightImage)
                leftImage.close()
                rightImage.close()
            },
            onStatusUpdate = { status ->
                statusText = status
            }
        )
    }

    DisposableEffect(Unit) {
        stereoCameraHandler.openStereoCamera()
        onDispose {
            stereoCameraHandler.close()
        }
    }

    if (leftBitmap != null && rightBitmap != null) {
        Row(Modifier.fillMaxSize()) {
            Image(
                bitmap = leftBitmap!!.asImageBitmap(),
                contentDescription = "Left camera",
                modifier = Modifier.weight(1f)
            )
            Image(
                bitmap = rightBitmap!!.asImageBitmap(),
                contentDescription = "Right camera",
                modifier = Modifier.weight(1f)
            )
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Camera Preview")
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = statusText)
            }
        }
    }
}
