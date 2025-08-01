package org.carftaura.depthgramapp.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class DepthEstimator() {
    private val interpreter: Interpreter

    init {
        val assetManager = AndroidContext.appContext.assets
        val model = assetManager.open("1.tflite").readBytes()
        val buffer = ByteBuffer.allocateDirect(model.size)
        buffer.order(ByteOrder.nativeOrder())
        buffer.put(model)
        buffer.rewind()
        interpreter = Interpreter(buffer)
    }

    fun estimateDepth(bitmap: Bitmap): Bitmap {
        // Preprocess input
        val inputBuffer = convertBitmapToFloatBuffer(bitmap, 256, 256)

        // Prepare output
        val outputBuffer = ByteBuffer.allocateDirect(4 * 256 * 256)
        outputBuffer.order(ByteOrder.nativeOrder())
        interpreter.run(inputBuffer, outputBuffer)

        // Convert output to float array
        outputBuffer.rewind()
        val depthArray = FloatArray(256 * 256)
        outputBuffer.asFloatBuffer().get(depthArray)

        // Convert to visual Bitmap
        return depthMapToBitmap(depthArray, 256, 256)
    }

    private fun depthMapToBitmap(depthArray: FloatArray, width: Int, height: Int): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val normalized = normalize(depthArray)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = (normalized[y * width + x] * 255).toInt().coerceIn(0, 255)
                val gray = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                output.setPixel(x, y, gray)
            }
        }
        return output
    }

    private fun normalize(array: FloatArray): FloatArray {
        val min = array.minOrNull() ?: 0f
        val max = array.maxOrNull() ?: 1f
        return array.map { (it - min) / (max - min + 1e-6f) }.toFloatArray()
    }
    private fun convertBitmapToFloatBuffer(bitmap: Bitmap, width: Int, height: Int): ByteBuffer {
        val inputSize = width * height * 3
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize)
        byteBuffer.order(ByteOrder.nativeOrder())

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val intValues = IntArray(width * height)
        scaledBitmap.getPixels(intValues, 0, width, 0, 0, width, height)

        for (pixel in intValues) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }

        byteBuffer.rewind()
        return byteBuffer
    }


}
