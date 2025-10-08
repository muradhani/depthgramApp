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
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

class StereoCameraHandler(
    private val context: Context,
    private val cameraHandler: Handler,
    private val onFramesReady: (
        leftImage: Image,
        rightImage: Image,
        leftIntrinsics: FloatArray?,
        rightIntrinsics: FloatArray?
    ) -> Unit,
    private val onStatusUpdate: (String) -> Unit
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

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @SuppressLint("MissingPermission")
    fun openStereoCamera() {
        scope.launch(Dispatchers.IO) {
            try {
                onStatusUpdate("Searching for logical camera...")
                val logicalCameraId = findLogicalCameraId() ?: throw IllegalStateException("No logical multi-camera found on this device.")

                onStatusUpdate("Opening camera $logicalCameraId...")
                cameraDevice = openCameraDevice(logicalCameraId)

                onStatusUpdate("Creating capture session...")
                captureSession = createCaptureSession(cameraDevice!!)

                onStatusUpdate("Starting stream...")
                startStreaming()
                onStatusUpdate("Streaming started.")
            } catch (e: Exception) {
                val errorMessage = e.message ?: "An unknown error occurred."
                Log.e("StereoCameraHandler", "Failed to open stereo camera: $errorMessage", e)
                onStatusUpdate("Error: $errorMessage")
            }
        }
    }

    private suspend fun openCameraDevice(cameraId: String): CameraDevice = suspendCancellableCoroutine { continuation ->
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (continuation.isActive) continuation.resume(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(CameraAccessException(error, "Camera device error: $error"))
                    }
                    camera.close()
                }
            }, cameraHandler)

            continuation.invokeOnCancellation { 
                cameraDevice?.close()
            }
        } catch (e: CameraAccessException) {
            continuation.resumeWithException(e)
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

    @SuppressLint("NewApi")
    private suspend fun createCaptureSession(device: CameraDevice): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        val characteristics = cameraManager.getCameraCharacteristics(device.id)
        val physicalCameraIds = characteristics.physicalCameraIds
        if (physicalCameraIds.size < 2) {
            throw IllegalStateException("Logical camera does not have at least 2 physical cameras")
        }

        val leftCameraId = physicalCameraIds.elementAt(0)
        val rightCameraId = physicalCameraIds.elementAt(1)

        val leftCharacteristics = cameraManager.getCameraCharacteristics(leftCameraId)
        leftCameraIntrinsics = leftCharacteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)

        val rightCharacteristics = cameraManager.getCameraCharacteristics(rightCameraId)
        rightCameraIntrinsics = rightCharacteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)

        val leftStreamMap = leftCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSize = leftStreamMap?.getOutputSizes(ImageFormat.YUV_420_888)?.firstOrNull() ?: Size(640, 480)

        leftImageReader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener(leftImageListener, imageProcessorHandler)
        }
        rightImageReader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener(rightImageListener, imageProcessorHandler)
        }

        val leftOutputConfig = OutputConfiguration(leftImageReader!!.surface).apply { setPhysicalCameraId(leftCameraId) }
        val rightOutputConfig = OutputConfiguration(rightImageReader!!.surface).apply { setPhysicalCameraId(rightCameraId) }

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(leftOutputConfig, rightOutputConfig),
            Executors.newSingleThreadExecutor(),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (continuation.isActive) continuation.resume(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Failed to configure capture session"))
                    }
                }
            }
        )

        try {
            device.createCaptureSession(sessionConfig)
        } catch (e: CameraAccessException) {
            continuation.resumeWithException(e)
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
                rightImage?.close()
                rightImage = null
            }
            if (leftImage != null) {
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
                leftImage?.close()
                leftImage = null
            }
            if (rightImage != null) {
                rightImage?.close()
            }
            rightImage = image
            checkAndProcessFrames()
        }
    }

    private fun checkAndProcessFrames() {
        if (leftImage != null && rightImage != null) {
            if (abs(leftImage!!.timestamp - rightImage!!.timestamp) < 30_000_000) { // 30ms tolerance
                onFramesReady(leftImage!!, rightImage!!, leftCameraIntrinsics, rightCameraIntrinsics)
                leftImage = null
                rightImage = null
            }
        }
    }


    fun close() {
        scope.cancel()
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
