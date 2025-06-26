package com.example.noiseapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.sqrt

class CameraActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService

    private var audioRecord: AudioRecord? = null
    private var isAudioRecording = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var minDb = 120
    private var maxDb = 0
    private val dbReadings = mutableListOf<Int>()
    private var currentDb = 0

    private lateinit var cameraDbTextView: TextView
    private lateinit var cameraMinDbTextView: TextView
    private lateinit var cameraAvgDbTextView: TextView
    private lateinit var cameraMaxDbTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        cameraDbTextView = findViewById(R.id.camera_db_textview)
        cameraMinDbTextView = findViewById(R.id.camera_min_db_textview)
        cameraAvgDbTextView = findViewById(R.id.camera_avg_db_textview)
        cameraMaxDbTextView = findViewById(R.id.camera_max_db_textview)

        if (allPermissionsGranted()) {
            startCamera()
            startAudioRecording()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        findViewById<Button>(R.id.image_capture_button).setOnClickListener { takePhotoWithOverlay() }
        findViewById<Button>(R.id.video_capture_button).setOnClickListener { captureVideo() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhotoWithOverlay() {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()

                    val annotatedBitmap = addTextOverlayToBitmap(bitmap)
                    saveImage(annotatedBitmap)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }
            }
        )
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val planeProxy = image.planes[0]
        val buffer: ByteBuffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun addTextOverlayToBitmap(originalBitmap: Bitmap): Bitmap {
        val mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 60f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(5f, 2f, 2f, Color.BLACK)
        }

        val statsPaint = Paint().apply {
            color = Color.WHITE
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }

        canvas.drawText("$currentDb dB", 50f, 100f, textPaint)

        val xPos = (canvas.width - 50).toFloat()
        canvas.drawText("Max: $maxDb dB", xPos, 100f, statsPaint)
        canvas.drawText("Avg: ${dbReadings.average().toInt()} dB", xPos, 150f, statsPaint)
        canvas.drawText("Min: $minDb dB", xPos, 200f, statsPaint)

        return mutableBitmap
    }

    private fun saveImage(bitmap: Bitmap) {
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NoiseApp-Photos")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        try {
            uri?.let {
                val outputStream = contentResolver.openOutputStream(it)
                outputStream?.let { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                    stream.close()
                    Toast.makeText(this, "Photo with stats saved!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image with overlay", e)
            Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return
        val videoCaptureButton = findViewById<Button>(R.id.video_capture_button)

        val curRecording = recording
        if (curRecording != null) {
            curRecording.stop()
            recording = null
            return
        }

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NoiseApp-Videos")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(contentValues).build()

        recording = videoCapture.output.prepareRecording(this, mediaStoreOutputOptions).start(ContextCompat.getMainExecutor(this)) { recordEvent ->
            when(recordEvent) {
                is VideoRecordEvent.Start -> {
                    videoCaptureButton.text = "Stop Video"
                }
                is VideoRecordEvent.Finalize -> {
                    if (!recordEvent.hasError()) {
                        val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    } else {
                        recording?.close()
                        recording = null
                        Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
                    }
                    videoCaptureButton.text = "Start Video"
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
            videoCapture = VideoCapture.withOutput(recorder)
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, videoCapture)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecording() {
        if (isAudioRecording) return
        minDb = 120
        maxDb = 0
        dbReadings.clear()
        updateStatsUI()
        isAudioRecording = true
        try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
            audioRecord?.startRecording()
            scope.launch { processAudio() }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord initialization failed", e)
        }
    }

    private suspend fun processAudio() {
        val buffer = ShortArray(1024)
        while (isAudioRecording) {
            val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (readSize > 0) {
                val rms = sqrt((0 until readSize).sumOf { buffer[it].toDouble() * buffer[it].toDouble() } / readSize)
                if (rms > 0) {
                    val db = (20 * log10(rms / 32767.0) + 94).toInt()
                    currentDb = db

                    if (db < minDb) minDb = db
                    if (db > maxDb) maxDb = db
                    dbReadings.add(db)

                    withContext(Dispatchers.Main) {
                        updateOverlayUI(currentDb, minDb, dbReadings.average().toInt(), maxDb)
                    }
                }
            }
            delay(200)
        }
    }

    private fun updateOverlayUI(currentDb: Int, min: Int, avg: Int, max: Int) {
        val safeDb = currentDb.coerceIn(0, 120)
        cameraDbTextView.text = "$safeDb dB"
        cameraMinDbTextView.text = "Min: $min"
        cameraAvgDbTextView.text = "Avg: $avg"
        cameraMaxDbTextView.text = "Max: $max"
    }

    private fun updateStatsUI() {
        cameraMinDbTextView.text = "Min: --"
        cameraAvgDbTextView.text = "Avg: --"
        cameraMaxDbTextView.text = "Max: --"
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
                startAudioRecording()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        isAudioRecording = false
        audioRecord?.release()
        scope.cancel()
    }

    companion object {
        private const val TAG = "CameraActivity"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}
