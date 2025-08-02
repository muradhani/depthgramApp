package org.carftaura.depthgramapp.utils

import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.image.TensorImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set

class DepthEstimator() {
    private val interpreter: Interpreter
    private lateinit var tensorImage: TensorImage

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
        if (!::tensorImage.isInitialized) {
            tensorImage = TensorImage(DataType.FLOAT32)
        }

        tensorImage.load(scaledBitmap)

        val outputBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize).apply {
            order(ByteOrder.nativeOrder())
        }

        interpreter.run(tensorImage.buffer, outputBuffer)

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

}
