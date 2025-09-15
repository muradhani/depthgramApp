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
                processImageData(image)
            }

            // ✅ Now it’s safe to close
            image.close()
            image = null

            withContext(Dispatchers.IO) {
                SocketManager.sendImage(imageData)
            }

        } catch (e: Exception) {
            Log.e("ScreenStream", "Error processing image", e)
            image?.close()
        }
    }


    // Reusable objects (initialize once at startProjection)
    private var reusableBitmap: Bitmap? = null
    private var scaledBitmap: Bitmap? = null
    private var reusableStream = ByteArrayOutputStream()
    private var reusableBuffer: ByteBuffer? = null

    private fun initBuffers(width: Int, height: Int) {
        if (reusableBitmap == null || reusableBitmap?.width != width || reusableBitmap?.height != height) {
            reusableBitmap?.recycle()
            reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            // Optional scaling buffer (half res)
            scaledBitmap?.recycle()
            scaledBitmap = Bitmap.createBitmap(width / 2, height / 2, Bitmap.Config.ARGB_8888)

            reusableBuffer = ByteBuffer.allocateDirect(width * height * 4) // ARGB_8888 = 4 bytes/pixel
        }
    }

    private fun processImageData(image: Image): ByteArray {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height

        // Ensure reusable buffer is big enough
        if (reusableBuffer == null || reusableBuffer!!.capacity() < plane.rowStride * height) {
            reusableBuffer = ByteBuffer.allocateDirect(plane.rowStride * height)
        }
        if (reusableBitmap == null || reusableBitmap!!.width != width || reusableBitmap!!.height != height) {
            reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        // Copy into reusable buffer
        val buffer = reusableBuffer!!
        buffer.clear()
        buffer.put(plane.buffer)
        buffer.flip()

        // Copy to bitmap
        val bitmap = reusableBitmap!!
        bitmap.copyPixelsFromBuffer(buffer)

        // Compress
        if (reusableStream == null) reusableStream = ByteArrayOutputStream()
        val out = reusableStream
        out.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)

        return out.toByteArray()
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