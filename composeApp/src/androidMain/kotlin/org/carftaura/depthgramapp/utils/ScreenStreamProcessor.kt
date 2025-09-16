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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

object ScreenStreamProcessor {

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val isStreaming = AtomicBoolean(false)

    // Use a supervisor job to handle failures in individual coroutines without cancelling all
    private val supervisorJob = SupervisorJob()
    private val processingScope = CoroutineScope(Dispatchers.Default + supervisorJob)

    // Use a channel to control the rate of image processing
    private val imageProcessingChannel = Channel<Image>(capacity = 2)

    // Handler thread for image reader
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    fun startProjection(mediaProjection: MediaProjection, context: Context) {
        if (isStreaming.getAndSet(true)) {
            Log.w("ScreenStream", "Already streaming, ignoring start request")
            return
        }

        projection = mediaProjection

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // Create handler thread for image reader
        handlerThread = HandlerThread("ScreenCaptureThread").apply {
            start()
            backgroundHandler = Handler(looper)
        }

        // Create ImageReader with optimal configuration
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 5).apply {
            setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        // Non-blocking offer to the channel
                        if (!imageProcessingChannel.trySend(image).isSuccess) {
                            Log.w("ScreenStream", "Dropping frame, channel full")
                            image.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ScreenStream", "Error acquiring image", e)
                }
            }, backgroundHandler)
        }

        // Register callback
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w("ScreenStream", "MediaProjection stopped unexpectedly")
                stopProjection()
            }
        }, Handler(Looper.getMainLooper()))

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
            return
        }

        // Start the image processing pipeline
        startImageProcessingPipeline()

        Log.i("ScreenStream", "Screen streaming started successfully")
    }

    private fun startImageProcessingPipeline() {
        processingScope.launch {
            imageProcessingChannel.consumeEach { image ->
                try {
                    processImage(image)
                } catch (e: Exception) {
                    Log.e("ScreenStream", "Error in processing pipeline", e)
                } finally {
                    image.close()
                }
            }
        }
    }

    private suspend fun processImage(image: Image) {
        if (!isStreaming.get()) return

        try {
            val imageData = withContext(Dispatchers.Default) {
                convertImageToJpeg(image, 75) // Reduced quality for better performance
            }

            if (imageData != null) {
                withContext(Dispatchers.IO) {
                    try {
                        SocketManager.sendImage(imageData)
                    } catch (e: Exception) {
                        Log.e("ScreenStream", "Failed to send image", e)
                        // Consider stopping projection on persistent network errors
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ScreenStream", "Error processing image", e)
        }
    }

    private fun convertImageToJpeg(image: Image, quality: Int): ByteArray? {
        return try {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            // Optimized conversion without creating an intermediate IntArray
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            // Use a more efficient approach to copy pixels
            buffer.rewind()

            // For RGBA_8888, pixel stride should be 4
            if (pixelStride == 4) {
                // Direct copy if no row padding
                if (rowStride == width * 4) {
                    bitmap.copyPixelsFromBuffer(buffer)
                } else {
                    // Handle row padding
                    val rowData = ByteArray(rowStride)
                    val pixels = IntArray(width * height)

                    for (y in 0 until height) {
                        buffer.position(y * rowStride)
                        buffer.get(rowData, 0, rowStride)

                        for (x in 0 until width) {
                            val offset = x * 4
                            val r = rowData[offset].toInt() and 0xFF
                            val g = rowData[offset + 1].toInt() and 0xFF
                            val b = rowData[offset + 2].toInt() and 0xFF
                            val a = rowData[offset + 3].toInt() and 0xFF
                            pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }

                    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                }
            } else {
                // Fallback for non-standard pixel stride
                val pixels = IntArray(width * height)
                var offset = 0
                val rowPadding = rowStride - pixelStride * width

                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val r = buffer.get(offset).toInt() and 0xFF
                        val g = buffer.get(offset + 1).toInt() and 0xFF
                        val b = buffer.get(offset + 2).toInt() and 0xFF
                        val a = buffer.get(offset + 3).toInt() and 0xFF
                        pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        offset += pixelStride
                    }
                    offset += rowPadding
                }

                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            }

            // Compress to JPEG
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            bitmap.recycle()

            output.toByteArray()
        } catch (e: Exception) {
            Log.e("ScreenStream", "Failed to convert image to JPEG", e)
            null
        }
    }

    fun stopProjection() {
        if (!isStreaming.getAndSet(false)) {
            return // Already stopped
        }

        Log.i("ScreenStream", "Stopping projection")

        // Close resources in proper order
        imageProcessingChannel.close()
        supervisorJob.cancel()

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        virtualDisplay?.release()
        virtualDisplay = null

        projection?.stop()
        projection = null

        // Clean up handler thread
        backgroundHandler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handlerThread = null
        backgroundHandler = null

        Log.i("ScreenStream", "Screen streaming stopped")
    }
}