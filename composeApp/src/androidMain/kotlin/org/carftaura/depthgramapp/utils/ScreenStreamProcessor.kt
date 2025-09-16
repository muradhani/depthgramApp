package org.carftaura.depthgramapp.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object ScreenStreamProcessor {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var isStreaming = false

    private val processingScope = CoroutineScope(Dispatchers.Default + Job())

    fun startProjection(mediaProjection: MediaProjection, context: Context) {
        projection = mediaProjection
        isStreaming = true

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // 1️⃣ Create MediaCodec encoder
        encoder = createVideoEncoder(width, height)
        inputSurface = encoder?.createInputSurface()
        encoder?.start()

        // 2️⃣ Create VirtualDisplay with encoder surface as target
        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenStream",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )

        // 3️⃣ Start frame extraction loop
        processingScope.launch {
            drainEncoderLoop()
        }
    }

    // Create H.264 encoder
    private fun createVideoEncoder(width: Int, height: Int, bitrate: Int = 4_000_000, fps: Int = 30): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // key frame every 1 sec

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    // Loop to drain encoder output
    private suspend fun drainEncoderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isStreaming) {
            val outputBufferId = encoder?.dequeueOutputBuffer(bufferInfo, 10_000) ?: -1
            if (outputBufferId >= 0) {
                val outputBuffer = encoder?.getOutputBuffer(outputBufferId)
                outputBuffer?.let {
                    val encodedBytes = ByteArray(bufferInfo.size)
                    it.get(encodedBytes)
                    it.clear()

                    // Send over network
                    SocketManager.sendImage(encodedBytes)
                }
                encoder?.releaseOutputBuffer(outputBufferId, false)
            } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Encoder output format changed
            }
            // Optional small delay to avoid tight loop
            delay(1)
        }
    }

    fun stopProjection() {
        isStreaming = false
        processingScope.coroutineContext.cancelChildren()

        encoder?.stop()
        encoder?.release()
        encoder = null

        virtualDisplay?.release()
        virtualDisplay = null

        inputSurface?.release()
        inputSurface = null

        projection?.stop()
        projection = null
    }
}
