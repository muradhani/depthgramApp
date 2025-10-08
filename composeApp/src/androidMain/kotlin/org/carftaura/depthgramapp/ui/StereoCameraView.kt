package org.carftaura.depthgramapp.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import org.carftaura.depthgramapp.utils.ImageUtils
import org.carftaura.depthgramapp.utils.StereoCameraHandler

@Composable
fun StereoCameraPreview() {
    val context = LocalContext.current
    var leftBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var rightBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var intrinsicsText by remember { mutableStateOf("Intrinsics: N/A") }

    val cameraHandler = remember { Handler(Looper.getMainLooper()) }
    val stereoCameraHandler = remember {
        StereoCameraHandler(context, cameraHandler) { leftImage, rightImage, leftIntrinsics, rightIntrinsics ->
            leftBitmap = ImageUtils.yuvToBitmap(leftImage)
            rightBitmap = ImageUtils.yuvToBitmap(rightImage)
            leftImage.close()
            rightImage.close()

            // For demonstration, just display the focal length and principal point.
            val leftIntrinsicsText = leftIntrinsics?.let { "Left: fx=${it[0]}, fy=${it[1]}, cx=${it[2]}, cy=${it[3]}" } ?: "Left: Not available"
            val rightIntrinsicsText = rightIntrinsics?.let { "Right: fx=${it[0]}, fy=${it[1]}, cx=${it[2]}, cy=${it[3]}" } ?: "Right: Not available"
            intrinsicsText = "$leftIntrinsicsText\n$rightIntrinsicsText"
        }
    }

    DisposableEffect(Unit) {
        stereoCameraHandler.openStereoCamera()
        onDispose {
            stereoCameraHandler.close()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f)) {
            leftBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Left camera",
                    modifier = Modifier.weight(1f)
                )
            }
            rightBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Right camera",
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(text = intrinsicsText)
    }
}
