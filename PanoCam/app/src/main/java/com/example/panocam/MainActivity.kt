package com.example.panocam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.Segmentation
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity(), SensorEventListener {
    private val renderer = PanoRenderer()
    private lateinit var glView: GLSurfaceView
    private lateinit var sensors: SensorManager
    private val busy = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor()
    private val panoFile by lazy { File(filesDir, "pano.jpg") }

    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build())

    private val pickPano = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        exec.execute {
            contentResolver.openInputStream(uri)?.use { i -> panoFile.outputStream().use { o -> i.copyTo(o) } }
            loadPano()
        }
    }
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startCamera() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnLongClickListener { pickPano.launch("image/*"); true } // hidden: long-press to change panorama
        }
        setContentView(glView)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager

        if (panoFile.exists()) exec.execute { loadPano() } else pickPano.launch("image/*")
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun loadPano() {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(panoFile.path, o)
        var s = 1; while (o.outWidth / s > 4096) s *= 2
        val bmp = BitmapFactory.decodeFile(panoFile.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return
        renderer.setPano(bmp)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(exec) { proxy ->
                if (!busy.compareAndSet(false, true)) { proxy.close(); return@setAnalyzer }
                val m = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()); postScale(-1f, 1f) } // upright + selfie mirror
                val raw = proxy.toBitmap()
                val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                proxy.close()
                segmenter.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { r ->
                        val fb = r.buffer.asFloatBuffer()
                        val bytes = ByteArray(r.width * r.height) { (fb.get(it).coerceIn(0f, 1f) * 255f).toInt().toByte() }
                        renderer.submitFrame(bmp, bytes, r.width, r.height)
                    }
                    .addOnCompleteListener { busy.set(false) }
            }
            val provider = future.get()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume(); glView.onResume()
        sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }
    override fun onPause() { super.onPause(); glView.onPause(); sensors.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // row-major device->world  =>  column-major for GL
        renderer.rot = floatArrayOf(r[0], r[3], r[6], r[1], r[4], r[7], r[2], r[5], r[8])
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
