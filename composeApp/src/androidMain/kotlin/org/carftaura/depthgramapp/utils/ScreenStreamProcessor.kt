package org.carftaura.depthgramapp.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object ScreenStreamProcessor {

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var isStreaming = false

    // Coroutine scope for background processing
    private val processingScope = CoroutineScope(Dispatchers.Default + Job())
    private var processingJob: Job? = null

    fun startProjection(mediaProjection: MediaProjection, context: Context) {
        projection = mediaProjection
        isStreaming = true

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // Create ImageReader
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 10)
        val handlerThread = HandlerThread("ScreenCaptureThread")
        handlerThread.start()
        val backgroundHandler = Handler(handlerThread.looper)
        // Register callback
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
            }
        }, Handler(Looper.getMainLooper()))

        // Setup image listener
        imageReader?.setOnImageAvailableListener({ reader ->
            Log.e("ScreenStream", "sending image")
            processingScope.launch {
                processImage(reader)
            }
        }, backgroundHandler)

        // Create virtual display
        virtualDisplay = projection?.createVirtualDisplay(
            "ARScreenStream",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        if (virtualDisplay == null) {
            Log.e("ScreenStream", "Failed to create virtual display")
            stopProjection()
        }
    }

    private suspend fun processImage(reader: ImageReader) {
        if (!isStreaming) return

        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return

            // Process while image is still open
            val imageData = withContext(Dispatchers.Default) {
                convertRgba8888ImageToJpeg(image)
            }

            // ✅ Now it’s safe to close
            image.close()
            image = null

            withContext(Dispatchers.IO) {
                imageData?.let {
                    SocketManager.sendImage(it)
                }
            }

        } catch (e: Exception) {
            Log.e("ScreenStream", "Error processing image", e)
            image?.close()
        }
    }

    private suspend fun convertRgba8888ImageToJpeg(image: Image, quality: Int = 80): ByteArray? {
        return withContext(Dispatchers.Default) {
            try {
                val width = image.width
                val height = image.height
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                // ✅ Only use real width/height
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                buffer.rewind()
                val pixels = IntArray(width * height)
                var offset = 0
                for (i in 0 until height) {
                    for (j in 0 until width) {
                        val r = buffer.get(offset).toInt() and 0xFF
                        val g = buffer.get(offset + 1).toInt() and 0xFF
                        val b = buffer.get(offset + 2).toInt() and 0xFF
                        val a = buffer.get(offset + 3).toInt() and 0xFF
                        pixels[i * width + j] =
                            (a shl 24) or (r shl 16) or (g shl 8) or b
                        offset += pixelStride
                    }
                    offset += rowPadding
                }

                // ✅ stride = width (not padded width)
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

                // Compress to JPEG
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                output.toByteArray()
            } catch (e: Exception) {
                Log.e("ScreenStream", "Failed to convert RGBA_8888 to JPEG", e)
                null
            } finally {
                image.close()
            }
        }
    }


    fun stopProjection() {
        isStreaming = false
        processingJob?.cancel()
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        virtualDisplay?.release()
        projection?.stop()
        projection = null
    }
}