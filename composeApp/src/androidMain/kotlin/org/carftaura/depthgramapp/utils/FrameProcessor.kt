package org.carftaura.depthgramapp.utils


import android.graphics.*
import android.media.Image
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.pow

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

    fun getDistanceAtPixel(x: Float,y: Float): HashMap<String, Float>? {
        try {
            lastFrame?.let {
                val cameraPose = it.camera.pose

                val camX = cameraPose.tx()
                val camY = cameraPose.ty()
                val camZ = cameraPose.tz()

                val hitResult = it.hitTest(x, y).firstOrNull()

                hitResult?.let {
                    val hitPose = it.hitPose
                    val dx = hitPose.tx() - camX
                    val dy = hitPose.ty() - camY
                    val dz = hitPose.tz() - camZ

                    val euclideanDistance = sqrt(dx*dx + dy*dy + dz*dz)

                    Log.d("ARCore", "Accurate distance: $euclideanDistance meters")
                    return hashMapOf(
                        "distance" to euclideanDistance,
                        "dx" to dx,
                        "dy" to dy,
                        "dz" to dz,
                    )
                }
            }

        }catch (e: Exception){
            Log.e("traccking","ditance not av")
        }
        return null
    }

    fun calculateDistanceBetweenScreenPoints(
        x1: Float, y1: Float,
        x2: Float, y2: Float
    ): Float? {
        lastFrame?.let {
            val point1 = get3DPointFromScreen(x1, y1, it)
            val point2 = get3DPointFromScreen(x2, y2, it)

            return if (point1 != null && point2 != null) {
                calculateDistanceBetween3DPoints(point1, point2)
            } else {
                null
            }
        }
       return null
    }

    fun calculateDistanceBetween3DPoints(point1: Point3D, point2: Point3D): Float {
        val dx = point2.x - point1.x
        val dy = point2.y - point1.y
        val dz = point2.z - point1.z

        return sqrt(dx.pow(2) + dy.pow(2) + dz.pow(2))
    }


    fun getDetailedDistanceInfo(
        x1: Float, y1: Float,
        x2: Float, y2: Float
    ): HashMap<String, Float>? {
        lastFrame?.let {
            val point1 = get3DPointFromScreen(x1, y1, it)
            val point2 = get3DPointFromScreen(x2, y2, it)

            return if (point1 != null && point2 != null) {
                val dx = point2.x - point1.x
                val dy = point2.y - point1.y
                val dz = point2.z - point1.z
                val distance = sqrt(dx.pow(2) + dy.pow(2) + dz.pow(2))

                hashMapOf(
                    "distance" to distance,
                    "dx" to dx,
                    "dy" to dy,
                    "dz" to dz,
                    "abs_dx" to abs(dx),
                    "abs_dy" to abs(dy),
                    "abs_dz" to abs(dz),
                    "point1_x" to point1.x,
                    "point1_y" to point1.y,
                    "point1_z" to point1.z,
                    "point2_x" to point2.x,
                    "point2_y" to point2.y,
                    "point2_z" to point2.z
                )
            } else {
                null
            }
        }
        return null
    }

    fun get3DPointFromScreen(screenX: Float, screenY: Float, frame: Frame): Point3D? {
        try {
            val hitResults = frame.hitTest(screenX, screenY)

            // Try to find a hit on a plane first for better accuracy
            val hitResult = hitResults.firstOrNull { hit ->
                hit.trackable is Plane && (hit.trackable as Plane).isPoseInPolygon(hit.hitPose)
            } ?: hitResults.firstOrNull() // Fallback to any hit

            hitResult?.let {
                val pose = it.hitPose
                return Point3D(pose.tx(), pose.ty(), pose.tz())
            }
        } catch (e: Exception) {
            // Handle exceptions (e.g., no AR session, invalid frame)
        }
        return null
    }
    fun calculateTwoPointsDistance(
        x1: Float, y1: Float,
        x2: Float, y2: Float
    ):Float?{
        return calculateDistanceBetweenScreenPoints(x1 = x1 , y1 = y1, x2 = x2 , y2 = y2)
    }
}
data class Point3D(val x: Float, val y: Float, val z: Float)
