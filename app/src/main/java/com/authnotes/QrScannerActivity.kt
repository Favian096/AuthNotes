package com.authnotes

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Framework Camera2 preview + ZXing's QR decoder; no CameraX, ML Kit, or Play Services. */
class QrScannerActivity : Activity() {
    private lateinit var preview: TextureView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var active = false
    @Volatile private var opening = false
    @Volatile private var delivered = false
    private var previewSize = Size(640, 480)
    private var sensorRotation = 90
    private var previewSurface: Surface? = null
    private var lastDecode = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = TextureView(this)
        val root = FrameLayout(this).apply { setBackgroundColor(0xff101412.toInt()) }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(QrTargetOverlay(this), FrameLayout.LayoutParams(-1, -1))
        val hint = TextView(this).apply {
            text = "对准 TOTP、HOTP 或迁移二维码\n识别后可确认账户信息"
            textSize = 16f; setTextColor(-1); gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
            setBackgroundColor(0x99000000.toInt())
        }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { transformPreview() }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { closeCamera(); return true }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), PERMISSION)
        }
    }
    override fun onResume() {
        super.onResume()
        active = true
        worker = HandlerThread("authnotes-camera").also { it.start() }
        handler = Handler(worker!!.looper)
        if (preview.isAvailable) openCamera()
    }
    override fun onPause() {
        active = false
        closeCamera()
        worker?.quitSafely(); worker = null; handler = null
        super.onPause()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == PERMISSION) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) { if (preview.isAvailable) openCamera() }
            else { Toast.makeText(this, "未获得相机权限，可使用手动输入密钥", Toast.LENGTH_LONG).show(); finish() }
        }
    }
    @Synchronized private fun closeCamera() {
        session?.close(); session = null
        camera?.close(); camera = null
        imageReader?.close(); imageReader = null
        previewSurface?.release(); previewSurface = null
        opening = false
    }
    private fun fail(message: String) { runOnUiThread { if (!isFinishing) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); finish() } } }

    @Synchronized private fun openCamera() {
        if (!active || opening || camera != null || !preview.isAvailable || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val callbackHandler = handler ?: return
        try {
            val manager = getSystemService(CameraManager::class.java)
            val cameraId = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: run { fail("未找到后置相机"); return }
            val characteristics = manager.getCameraCharacteristics(cameraId)
            sensorRotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("无相机配置")
            val textureSizes = map.getOutputSizes(SurfaceTexture::class.java).toSet()
            val compatible = map.getOutputSizes(ImageFormat.YUV_420_888).filter { it in textureSizes }
            previewSize = compatible.filter { it.width <= 1280 && it.height <= 960 }.maxByOrNull { it.width * it.height }
                ?: compatible.minByOrNull { it.width * it.height } ?: error("不支持相机预览")
            transformPreview()
            val reader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)
            imageReader = reader
            reader.setOnImageAvailableListener({ source ->
                try { source.acquireLatestImage()?.use { decodeFrame(it) } } catch (_: IllegalStateException) { /* Camera closed while callback was queued. */ }
            }, callbackHandler)
            opening = true
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    opening = false
                    if (!active || imageReader !== reader) { device.close(); return }
                    camera = device
                    try {
                        val texture = preview.surfaceTexture ?: run { device.close(); return }
                        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
                        val surface = Surface(texture)
                        previewSurface = surface
                        device.createCaptureSession(listOf(surface, reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                if (!active || camera !== device) { configured.close(); return }
                                session = configured
                                try {
                                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(surface); addTarget(reader.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    }.build()
                                    configured.setRepeatingRequest(request, null, callbackHandler)
                                } catch (_: Exception) { if (active) fail("相机预览启动失败") }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { if (active) fail("无法启动相机预览") }
                        }, callbackHandler)
                    } catch (_: Exception) { if (active) fail("相机预览启动失败") }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (active) fail("相机已断开") }
                override fun onError(device: CameraDevice, error: Int) { device.close(); if (active) fail("无法打开相机，请重试") }
            }, callbackHandler)
        } catch (_: Exception) { opening = false; fail("无法打开相机，请检查权限或相机是否被占用") }
    }

    private fun transformPreview() {
        val width = preview.width.toFloat()
        val height = preview.height.toFloat()
        if (width <= 0 || height <= 0) return
        val displayDegrees = displayDegrees()
        val rawWidth = previewSize.width.toFloat()
        val rawHeight = previewSize.height.toFloat()
        // Camera2's SurfaceTexture already applies the sensor's orientation. Its upright
        // buffer is stretched to the TextureView bounds before this View-space transform.
        // Undo that stretch, center-crop uniformly, then compensate only for display rotation.
        val sensorSwapsAxes = sensorRotation % 180 != 0
        val uprightWidth = if (sensorSwapsAxes) rawHeight else rawWidth
        val uprightHeight = if (sensorSwapsAxes) rawWidth else rawHeight
        val displaySwapsAxes = displayDegrees % 180 != 0
        val displayedWidth = if (displaySwapsAxes) uprightHeight else uprightWidth
        val displayedHeight = if (displaySwapsAxes) uprightWidth else uprightHeight
        val scale = maxOf(width / displayedWidth, height / displayedHeight)
        val centerX = width / 2f
        val centerY = height / 2f
        val matrix = Matrix().apply {
            setScale(uprightWidth * scale / width, uprightHeight * scale / height, centerX, centerY)
            postRotate(-displayDegrees.toFloat(), centerX, centerY)
        }
        preview.setTransform(matrix)
    }

    private fun decodeFrame(image: Image) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!active || delivered || now - lastDecode < 200) return
        lastDecode = now
        val crop = image.cropRect
        // ImageReader exposes raw sensor pixels, unlike the already oriented preview texture.
        val rotation = (sensorRotation - displayDegrees() + 360) % 360
        val orientedWidth = if (rotation % 180 != 0) crop.height() else crop.width()
        val orientedHeight = if (rotation % 180 != 0) crop.width() else crop.height()
        val scale = maxOf(preview.width / orientedWidth.toFloat(), preview.height / orientedHeight.toFloat())
        val frameSize = minOf(preview.width * 0.76f, preview.height * 0.46f, 320f * resources.displayMetrics.density)
        val side = (frameSize / scale).toInt().coerceIn(1, minOf(crop.width(), crop.height()))
        val left = crop.left + (crop.width() - side) / 2
        val top = crop.top + (crop.height() - side) / 2
        val width = side
        val height = side
        val plane = image.planes[0]
        val buffer = plane.buffer
        val start = buffer.position() + top * plane.rowStride + left * plane.pixelStride
        val bytes = ByteArray(width * height)
        for (r in 0 until height) {
            val rowStart = start + r * plane.rowStride
            for (c in 0 until width) bytes[r * width + c] = buffer.get(rowStart + c * plane.pixelStride)
        }
        val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
        val reader = QRCodeReader()
        val hints = mapOf<DecodeHintType, Any>(DecodeHintType.TRY_HARDER to true)
        val value = runCatching { reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text }
            .recoverCatching { reader.reset(); reader.decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text }.getOrNull() ?: return
        delivered = true
        runOnUiThread {
            if (active) { setResult(RESULT_OK, Intent().putExtra(EXTRA_VALUE, value)); finish() }
        }
    }

    private fun displayDegrees() = when (display?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    companion object { const val EXTRA_VALUE = "qr_value"; private const val PERMISSION = 10 }
}

private class QrTargetOverlay(context: android.content.Context) : android.view.View(context) {
    private val density = context.resources.displayMetrics.density
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xb8000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val frame = RectF()
    private val path = Path()

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val size = minOf(width * 0.76f, height * 0.46f, 320f * density)
        val top = (height - size) / 2f
        frame.set((width - size) / 2f, top, (width + size) / 2f, top + size)
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRoundRect(frame, 18f * density, 18f * density, Path.Direction.CCW)
        canvas.drawPath(path, dim)
        canvas.drawRoundRect(frame, 18f * density, 18f * density, border)
    }
}
