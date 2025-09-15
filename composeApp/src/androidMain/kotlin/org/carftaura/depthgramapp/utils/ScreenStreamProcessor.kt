package org.carftaura.depthgramapp.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object ScreenStreamProcessor {

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var isStreaming = false

    // Coroutine scope for background processing
    private val processingScope = CoroutineScope(Dispatchers.IO + Job())
    private var processingJob: Job? = null

    fun startProjection(mediaProjection: MediaProjection, context: Context) {
        projection = mediaProjection
        isStreaming = true

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

        // Create a job to handle the image processing
        processingJob = processingScope.launch {
            imageReader?.setOnImageAvailableListener({ reader ->
                // Process each image in the background
                launch {
                    processImage(reader)
                }
            }, Handler(Looper.getMainLooper()))
        }

        projection?.createVirtualDisplay(
            "ARScreenStream",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private suspend fun processImage(reader: ImageReader) {
        if (!isStreaming) return

        var image: Image? = null
        try {
            image = withContext(Dispatchers.IO) {
                reader.acquireLatestImage()
            } ?: return

            val planes = image.planes
            val buffer = planes[0].buffer
            val width = reader.width
            val height = reader.height

            // Process image on IO thread
            val imageData = withContext(Dispatchers.IO) {
                processImageData(buffer, width, height)
            }

            // Send via socket (ensure SocketManager is thread-safe)
            withContext(Dispatchers.IO) {
                SocketManager.sendImage(imageData)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image?.close()
        }
    }

    private fun processImageData(buffer: java.nio.ByteBuffer, width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        val imageData = out.toByteArray()

        bitmap.recycle()
        out.close()

        return imageData
    }

    fun stopProjection() {
        isStreaming = false
        processingJob?.cancel()
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        projection?.stop()
        projection = null
    }
}