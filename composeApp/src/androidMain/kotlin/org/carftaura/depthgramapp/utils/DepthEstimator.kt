package org.carftaura.depthgramapp.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set

class DepthEstimator() {
    private val interpreter: Interpreter

    init {
        val assetManager = AndroidContext.appContext.assets
        val model = assetManager.open("1.tflite").readBytes()
        val buffer = ByteBuffer.allocateDirect(model.size)
        buffer.order(ByteOrder.nativeOrder())
        buffer.put(model)
        buffer.rewind()
        val compatList = CompatibilityList()

        val options = Interpreter.Options().apply{
            if(compatList.isDelegateSupportedOnThisDevice){
                val delegateOptions = compatList.bestOptionsForThisDevice
                this.addDelegate(GpuDelegate(delegateOptions))
            } else {
                this.setNumThreads(4)
            }
        }

        interpreter = Interpreter(buffer, options)
    }


    fun estimateDepth(bitmap: Bitmap): Bitmap {
        val inputSize = 256

        val scaledBitmap = bitmap.scale(inputSize, inputSize)
        val image = TensorImage(DataType.FLOAT32).apply { load(scaledBitmap) }

        val outputBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize).apply {
            order(ByteOrder.nativeOrder())
        }


        interpreter.run(image.buffer, outputBuffer)


        outputBuffer.rewind()
        val depthArray = FloatArray(inputSize * inputSize)
        outputBuffer.asFloatBuffer().get(depthArray)

        return depthMapToBitmap(depthArray, inputSize, inputSize)
    }

    private fun depthMapToBitmap(depthArray: FloatArray, width: Int, height: Int): Bitmap {
        val output = createBitmap(width, height)

        val normalized = normalize(depthArray)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val value = (normalized[index] * 255).toInt().coerceIn(0, 255)

                val color = Color.argb(255, value, value, value)

                output[x, y] = color
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
    private fun heatmapColor(v: Int): Triple<Int, Int, Int> {
        val value = v.coerceIn(0, 255)

        val r = when {
            value < 128 -> 0
            value < 192 -> ((value - 128) * 4).coerceIn(0, 255)
            else -> 255
        }

        val g = when {
            value < 64 -> 0
            value < 192 -> ((value - 64) * 4).coerceIn(0, 255)
            else -> ((255 - value) * 4).coerceIn(0, 255)
        }

        val b = when {
            value < 64 -> 255
            value < 128 -> ((128 - value) * 4).coerceIn(0, 255)
            else -> 0
        }

        return Triple(r, g, b)
    }

}
