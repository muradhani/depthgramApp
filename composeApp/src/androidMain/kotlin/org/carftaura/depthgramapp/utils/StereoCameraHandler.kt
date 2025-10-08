package org.carftaura.depthgramapp.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.util.Size
import java.util.concurrent.Executors
import kotlin.math.abs

class StereoCameraHandler(
    private val context: Context,
    private val cameraHandler: Handler,
    private val onFramesReady: (
        leftImage: Image, 
        rightImage: Image, 
        leftIntrinsics: FloatArray?, 
        rightIntrinsics: FloatArray?
    ) -> Unit
) {

    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var leftImageReader: ImageReader? = null
    private var rightImageReader: ImageReader? = null
    private val imageProcessorHandler by lazy { Handler(cameraHandler.looper) }

    private var leftImage: Image? = null
    private var rightImage: Image? = null
    private val frameSync = Object()

    private var leftCameraIntrinsics: FloatArray? = null
    private var rightCameraIntrinsics: FloatArray? = null


    @SuppressLint("MissingPermission")
    fun openStereoCamera() {
        val logicalCameraId = findLogicalCameraId()
        if (logicalCameraId == null) {
            Log.e("StereoCameraHandler", "No logical multi-camera found.")
            return
        }

        try {
            cameraManager.openCamera(logicalCameraId, stateCallback, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e("StereoCameraHandler", "Failed to open camera", e)
        }
    }


    private fun findLogicalCameraId(): String? {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            if (capabilities != null && capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
                if (characteristics.physicalCameraIds.size >= 2) {
                    Log.d(
                        "StereoCameraHandler",
                        "Found logical camera with id $cameraId and physical cameras ${characteristics.physicalCameraIds.joinToString()}"
                    )
                    return cameraId
                }
            }
        }
        return null
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCaptureSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            Log.e("StereoCameraHandler", "Camera device error: $error")
        }
    }

    @SuppressLint("NewApi")
    private fun createCaptureSession() {
        val cameraDevice = cameraDevice ?: return
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraDevice.id)
            val physicalCameraIds = characteristics.physicalCameraIds
            if (physicalCameraIds.size < 2) {
                Log.e("StereoCameraHandler", "Logical camera does not have at least 2 physical cameras")
                return
            }

            // For simplicity, let's take the first two physical cameras
            val leftCameraId = physicalCameraIds.elementAt(0)
            val rightCameraId = physicalCameraIds.elementAt(1)

            val leftCharacteristics = cameraManager.getCameraCharacteristics(leftCameraId)
            leftCameraIntrinsics = leftCharacteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)

            val rightCharacteristics = cameraManager.getCameraCharacteristics(rightCameraId)
            rightCameraIntrinsics = rightCharacteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)


            val leftStreamMap = leftCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // A very simple selection of size. A real app should check for common sizes.
            val outputSize = leftStreamMap?.getOutputSizes(ImageFormat.YUV_420_888)?.firstOrNull() ?: Size(640, 480)

            leftImageReader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener(leftImageListener, imageProcessorHandler)
            }
            rightImageReader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener(rightImageListener, imageProcessorHandler)
            }


            val leftOutputConfig = OutputConfiguration(leftImageReader!!.surface)
            leftOutputConfig.setPhysicalCameraId(leftCameraId)

            val rightOutputConfig = OutputConfiguration(rightImageReader!!.surface)
            rightOutputConfig.setPhysicalCameraId(rightCameraId)

            val sessionExecutor = Executors.newSingleThreadExecutor()

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(leftOutputConfig, rightOutputConfig),
                sessionExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startStreaming()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("StereoCameraHandler", "Failed to configure capture session")
                    }
                }
            )
            cameraDevice.createCaptureSession(sessionConfig)

        } catch (e: CameraAccessException) {
            Log.e("StereoCameraHandler", "Failed to create capture session", e)
        }
    }

    private fun startStreaming() {
        val captureSession = captureSession ?: return
        val cameraDevice = cameraDevice ?: return

        try {
            val builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(leftImageReader!!.surface)
            builder.addTarget(rightImageReader!!.surface)
            captureSession.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e("StereoCameraHandler", "Failed to start streaming", e)
        }
    }

    private val leftImageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        synchronized(frameSync) {
            if (rightImage != null && rightImage!!.timestamp < image.timestamp) {
                // Stale right image, drop it
                rightImage?.close()
                rightImage = null
            }
            if (leftImage != null) {
                // If a left image is already pending, close it before assigning the new one
                leftImage?.close()
            }
            leftImage = image
            checkAndProcessFrames()
        }
    }

    private val rightImageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        synchronized(frameSync) {
            if (leftImage != null && leftImage!!.timestamp < image.timestamp) {
                // Stale left image, drop it
                leftImage?.close()
                leftImage = null
            }
            if (rightImage != null) {
                // If a right image is already pending, close it before assigning the new one
                rightImage?.close()
            }
            rightImage = image
            checkAndProcessFrames()
        }
    }

    private fun checkAndProcessFrames() {
        if (leftImage != null && rightImage != null) {
            // A simple sync mechanism. A more robust solution would check timestamps
            // and handle cases where one stream is faster than the other.
            if (abs(leftImage!!.timestamp - rightImage!!.timestamp) < 30_000_000) { // 30ms tolerance
                onFramesReady(leftImage!!, rightImage!!, leftCameraIntrinsics, rightCameraIntrinsics)
                leftImage = null
                rightImage = null
            }
        }
    }


    fun close() {
        captureSession?.stopRepeating()
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        leftImageReader?.close()
        leftImageReader = null
        rightImageReader?.close()
        rightImageReader = null
        leftImage?.close()
        leftImage = null
        rightImage?.close()
        rightImage = null
    }
}
