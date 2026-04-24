package com.example.video.show.glass.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.core.content.ContextCompat

/**
 * Camera2 API video capture.
 * References x3 CameraActivity: uses ImageReader to capture YUV, avoiding MediaCodec Surface incompatibility.
 */
class CameraCapture(private val context: Context) {

    companion object {
        private const val TAG = "CameraCapture"
        private val FALLBACK_SIZE = Size(640, 480)

        /**
         * When the glasses camera mounting orientation does not match the image,
         * rotate NV12 by 90° before encoding.
         * Uses **clockwise 90°** (differs from counter-clockwise 90° by 180°);
         * if the image is still wrong, set to false and try the other direction.
         * When true, encoding and streaming dimensions are (original height × original width).
         */
        const val COMPENSATE_GLASSES_CAMERA_ROTATION_CW90 = true
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val backgroundThread = HandlerThread("CameraBackground").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)

    private var outputWidth = 640
    private var outputHeight = 480

    var onFrameAvailable: ((ByteArray, Long) -> Unit)? = null

    fun getOptimalSize(): Pair<Int, Int> {
        return try {
            val cameraId = cameraManager.cameraIdList[0]
            val size = chooseOptimalSize(cameraId)
            Pair(size.width, size.height)
        } catch (e: Exception) {
            Log.e(TAG, "getOptimalSize failed", e)
            Pair(FALLBACK_SIZE.width, FALLBACK_SIZE.height)
        }
    }

    /**
     * Get all resolutions supported by the camera (sorted by width and height in descending order).
     */
    fun getSupportedSizes(): List<Pair<Int, Int>> {
        return try {
            val cameraId = cameraManager.cameraIdList[0]
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
                ?: map?.getOutputSizes(SurfaceTexture::class.java)
            if (!sizes.isNullOrEmpty()) {
                sizes.map { Pair(it.width, it.height) }
                    .sortedWith(compareBy({ -it.first }, { -it.second }))
            } else {
                listOf(Pair(FALLBACK_SIZE.width, FALLBACK_SIZE.height))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSupportedSizes failed", e)
            listOf(Pair(FALLBACK_SIZE.width, FALLBACK_SIZE.height))
        }
    }

    /**
     * Get the list of supported frame rates (deduplicated and sorted).
     * Handles the bug on older Android versions that returns 1000x values.
     */
    fun getSupportedFrameRates(): List<Int> {
        return try {
            val cameraId = cameraManager.cameraIdList[0]
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            if (!ranges.isNullOrEmpty()) {
                val rates = mutableSetOf<Int>()
                fun normalize(v: Int) = if (v > 1000) v / 1000 else v
                ranges.forEach { range ->
                    rates.add(normalize(range.lower))
                    rates.add(normalize(range.upper))
                }
                rates.sorted()
            } else {
                listOf(15, 24, 25, 30, 60)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSupportedFrameRates failed", e)
            listOf(15, 24, 25, 30, 60)
        }
    }

    private fun chooseOptimalSize(cameraId: String): Size {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map: StreamConfigurationMap? = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
                ?: map?.getOutputSizes(SurfaceTexture::class.java)
            if (!sizes.isNullOrEmpty()) {
                val targetSizes = listOf(
                    Size(1920, 1080),
                    Size(1280, 720),
                    Size(960, 540),
                    Size(640, 480),
                    Size(320, 240)
                )
                targetSizes.firstOrNull { target ->
                    sizes.any { it.width == target.width && it.height == target.height }
                }?.let { return it }
                Size(sizes[0].width, sizes[0].height)
            } else {
                FALLBACK_SIZE
            }
        } catch (e: Exception) {
            Log.e(TAG, "chooseOptimalSize failed", e)
            FALLBACK_SIZE
        }
    }

    /**
     * @param size specified resolution; uses optimal size when null
     * @param frameRate specified frame rate; defaults to 30 when null
     */
    fun start(size: Pair<Int, Int>? = null, frameRate: Int? = null, callback: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted")
            callback(false)
            return
        }
        try {
            val cameraId = cameraManager.cameraIdList[0]
            val chosenSize = size?.let { Size(it.first, it.second) } ?: chooseOptimalSize(cameraId)
            outputWidth = chosenSize.width
            outputHeight = chosenSize.height
            targetFrameRate = frameRate ?: 30
            Log.d(TAG, "Using camera size: ${outputWidth}x${outputHeight}, fps=$targetFrameRate")

            imageReader = ImageReader.newInstance(outputWidth, outputHeight, ImageFormat.YUV_420_888, 4).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    var nv12 = yuv420ToNv12(image)
                    if (COMPENSATE_GLASSES_CAMERA_ROTATION_CW90) {
                        nv12 = rotateNv12Cw90(nv12, image.width, image.height)
                    }
                    val ptsUs = System.nanoTime() / 1000
                    onFrameAvailable?.invoke(nv12, ptsUs)
                    image.close()
                }, backgroundHandler)
            }

            val handler = Handler(Looper.getMainLooper())
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera) { success -> callback(success) }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    callback(false)
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            callback(false)
        }
    }

    /**
     * YUV_420_888 → tightly packed NV12 (contiguous Y + interleaved UV), total length = width×height×3/2.
     * Y must be copied row-by-row according to rowStride; using [ByteBuffer.remaining] for a bulk copy
     * would include stride padding in the Y region, making the total bytes exceed the encoder input buffer
     * (allocated as width×height×1.5), causing [BufferOverflowException].
     */
    private fun yuv420ToNv12(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val ySize = width * height
        val uvSize = width * height / 2
        val nv12 = ByteArray(ySize + uvSize)

        var pos = 0
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            var col = 0
            while (col < width) {
                nv12[pos++] = yBuffer.get(rowStart + col * yPixelStride)
                col++
            }
        }

        val uvWidth = width / 2
        val uvHeight = height / 2
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                nv12[pos++] = uBuffer.get(row * uRowStride + col * uPixelStride)
                nv12[pos++] = vBuffer.get(row * vRowStride + col * vPixelStride)
            }
        }
        return nv12
    }

    /**
     * Rotate NV12 **clockwise** by 90°. Input is w×h, output logical size is h×w, buffer length remains unchanged (w×h×3/2).
     */
    private fun rotateNv12Cw90(nv12: ByteArray, w: Int, h: Int): ByteArray {
        val ySize = w * h
        val uvSize = w * h / 2
        require(nv12.size == ySize + uvSize) { "nv12 size ${nv12.size} != ${ySize + uvSize}" }
        val out = ByteArray(nv12.size)

        for (y in 0 until h) {
            for (x in 0 until w) {
                out[x * h + (h - 1 - y)] = nv12[y * w + x]
            }
        }

        val halfW = w / 2
        val halfH = h / 2
        for (cy in 0 until halfH) {
            for (cx in 0 until halfW) {
                val srcBase = ySize + cy * halfW * 2 + cx * 2
                val nx = halfH - 1 - cy
                val ny = cx
                val dstBase = ySize + ny * halfH * 2 + nx * 2
                out[dstBase] = nv12[srcBase]
                out[dstBase + 1] = nv12[srcBase + 1]
            }
        }
        return out
    }

    private var targetFrameRate: Int = 30

    private fun createCaptureSession(camera: CameraDevice, callback: (Boolean) -> Unit) {
        val surface = imageReader?.surface ?: run {
            callback(false)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        try {
            val outputConfig = OutputConfiguration(surface)
            camera.createCaptureSessionByOutputConfigurations(listOf(outputConfig), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFrameRate, targetFrameRate))
                        }
                        session.setRepeatingRequest(requestBuilder.build(), null, null)
                        callback(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start preview", e)
                        callback(false)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session failed")
                    callback(false)
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "createCaptureSessionByOutputConfigurations failed", e)
            try {
                camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFrameRate, targetFrameRate))
                            }
                            session.setRepeatingRequest(requestBuilder.build(), null, null)
                            callback(true)
                        } catch (e2: Exception) {
                            Log.e(TAG, "Failed to start preview", e2)
                            callback(false)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session failed")
                        callback(false)
                    }
                }, handler)
            } catch (e2: Exception) {
                Log.e(TAG, "Create capture session failed", e2)
                callback(false)
            }
        }
    }

    fun stop() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
        }
        Log.d(TAG, "Camera stopped")
    }

    fun getWidth() = outputWidth
    fun getHeight() = outputHeight
}
