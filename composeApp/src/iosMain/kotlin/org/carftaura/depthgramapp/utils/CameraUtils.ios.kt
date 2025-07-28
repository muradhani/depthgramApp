package org.carftaura.depthgramapp.utils

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceDiscoverySession
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionUnspecified
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCATransactionDisableActions
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.sel_registerName


@Composable
actual fun CameraPreview(modifier: Modifier) {
    val cameraPermission = rememberCameraPermissionState()
    val cameraReady = remember { mutableStateOf(false) }
    val cameraErrorMessage = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        cameraPermission.requestPermission()
    }

    // UI
    when {
        cameraPermission.isGranted.value -> {
            androidx.compose.foundation.layout.Box(modifier = modifier.fillMaxSize()) {
                UIKitView(
                    factory = {
                        CameraPreviewView().apply {
                            setupCamera { errorMessage ->
                                cameraErrorMessage.value = errorMessage
                            }
                            cameraReady.value = true
                        }
                    },
                    update = { view ->
                        if (cameraReady.value) {
                            view.layoutCameraPreview()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                cameraErrorMessage.value?.let { error ->
                    if (error.isNotEmpty()) {
                        Text(
                            text = error,
                            modifier = Modifier
                                .fillMaxSize(),
                            color = androidx.compose.ui.graphics.Color.Red
                        )
                    }
                }
            }
        }

        cameraPermission.shouldShowRationale.value -> {
            Text("Camera permission required")
        }

        else -> {
            Text("Requesting camera access...")
        }
    }
}


@Composable
fun rememberCameraPermissionState(): CameraPermissionState {
    val state = remember { CameraPermissionState() }
    DisposableEffect(Unit) {
        onDispose { state.unregisterObserver() }
    }
    return state
}

@OptIn(ExperimentalForeignApi::class)
class CameraPermissionState {
    val isGranted = mutableStateOf(false)
    val shouldShowRationale = mutableStateOf(false)

    private val observer = object : NSObject() {
        @Suppress("UNUSED_PARAMETER")
        @ObjCAction
        fun permissionChanged() {
            isGranted.value = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized
        }
    }
    private val permissionChangedSelector = sel_registerName("permissionChanged")

    init {
        NSNotificationCenter.defaultCenter.addObserver(
            observer,
            selector = permissionChangedSelector,
            null,
            null
        )
        checkCurrentStatus()
    }

    fun unregisterObserver() {
        NSNotificationCenter.defaultCenter.removeObserver(observer)
    }

    fun requestPermission() {
        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
            isGranted.value = granted
            shouldShowRationale.value = !granted
            NSNotificationCenter.defaultCenter.postNotificationName("permissionChanged", null)
        }
    }

    private fun checkCurrentStatus() {
        isGranted.value = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized
    }
}

@OptIn(ExperimentalForeignApi::class)
class CameraPreviewView : UIView(frame =  CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val captureSession = AVCaptureSession().apply {
        sessionPreset = AVCaptureSessionPresetPhoto
    }
    private val previewLayer = AVCaptureVideoPreviewLayer(session = captureSession).apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    }

    init {
        layer.addSublayer(previewLayer)
    }

    fun setupCamera(onFailure: (String) -> Unit) {
        val deviceTypes = listOf(AVCaptureDeviceTypeBuiltInWideAngleCamera)
        val discoverySession = AVCaptureDeviceDiscoverySession.discoverySessionWithDeviceTypes(
            deviceTypes = deviceTypes,
            mediaType = AVMediaTypeVideo,
            position = AVCaptureDevicePositionUnspecified
        )


        if ( discoverySession.devices.firstOrNull() == null) onFailure("camera null")

        val camera = discoverySession.devices.firstOrNull() ?: return

        try {
            val input = AVCaptureDeviceInput.deviceInputWithDevice(camera as AVCaptureDevice, null)
            if (captureSession.canAddInput(input as AVCaptureDeviceInput)) {
                captureSession.addInput(input)
            }

            captureSession.startRunning()
        } catch (e: Exception) {
            println("Camera setup error: ${e.message}")
        }
    }

    fun layoutCameraPreview() {
        CATransaction.begin()
        CATransaction.setValue(true, kCATransactionDisableActions)
        previewLayer.frame = bounds
        CATransaction.commit()
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        layoutCameraPreview()
    }

    fun cleanup() {
        captureSession.stopRunning()
        previewLayer.removeFromSuperlayer()
    }
}