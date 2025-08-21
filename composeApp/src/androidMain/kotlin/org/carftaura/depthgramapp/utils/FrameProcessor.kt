package org.carftaura.depthgramapp.utils


import android.graphics.*
import android.media.Image
import android.util.Log
import com.google.ar.core.Frame
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object FrameProcessor {
    @Volatile
    var lastFrame : Frame? = null
    fun convertFrameToBytes(image: Image,frame: Frame): ByteArray? {
        return try {
            val jpegData = convertYuvToJpeg(image, 80) ?: return null
            val intrinsics = frame.camera.imageIntrinsics
            val fx = intrinsics.focalLength[0]
            val fy = intrinsics.focalLength[1]
            val cx = intrinsics.principalPoint[0]
            val cy = intrinsics.principalPoint[1]
            val width = intrinsics.imageDimensions[0]
            val height = intrinsics.imageDimensions[1]

            val intrinsicsData = ByteArray(24).apply {
                val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
                buffer.putFloat(fx)
                buffer.putFloat(fy)
                buffer.putFloat(cx)
                buffer.putFloat(cy)
                buffer.putInt(width)
                buffer.putInt(height)
            }

            intrinsicsData + jpegData
        } catch (e: Exception) {
            Log.e("FrameProcessor", "Frame conversion failed", e)
            null
        }
    }

    private fun convertYuvToJpeg(image: Image, quality: Int): ByteArray? {
        return try {
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)

            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)

            val scaledBitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
            val scaledOut = ByteArrayOutputStream()
            scaledBitmap?.compress(Bitmap.CompressFormat.JPEG, quality, scaledOut)

            scaledOut.toByteArray()
        } catch (e: Exception) {
            Log.e("FrameProcessor", "YUV to JPEG failed", e)
            null
        }

    }

    fun getDistanceAtPixel(x: Float,y: Float): Float? {
        try {
            lastFrame?.let {
                val cameraPose = lastFrame?.camera?.pose
                val hitPose =  lastFrame?.hitTest(x, y)[0]?.hitPose

                val hitInCameraCoords = FloatArray(3)
                cameraPose?.inverse()?.transformPoint(hitInCameraCoords, 0, hitPose?.translation, 0)
                return sqrt(
                    hitInCameraCoords[0] * hitInCameraCoords[0] +
                            hitInCameraCoords[1] * hitInCameraCoords[1] +
                            hitInCameraCoords[2] * hitInCameraCoords[2]
                )
            }

        }catch (e: Exception){
            Log.e("traccking","ditance not av")
        }
        return null
    }
}
