package com.example.noiseapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.location.Geocoder
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import com.example.noiseapp.R
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private val RECORD_AUDIO_PERMISSION_CODE = 123
    private val LOCATION_PERMISSION_CODE_FOR_REPORT = 124
    private val STORAGE_PERMISSION_CODE = 125
    private val HIGH_NOISE_THRESHOLD = 50

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // UI Elements
    private lateinit var noiseLevelGauge: ProgressBar
    private lateinit var decibelTextView: TextView
    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var startPauseButton: FloatingActionButton
    private lateinit var minDbTextView: TextView
    private lateinit var avgDbTextView: TextView
    private lateinit var maxDbTextView: TextView
    private lateinit var askAiButton: Button
    private lateinit var reportButton: Button
    private lateinit var noiseChart: LineChart

    // State and Stats variables
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var minDb = 120
    private var maxDb = 0
    private val dbReadings = mutableListOf<Int>()
    private var recordingStartTime: Long = 0L
    private var totalElapsedTime: Long = 0L
    private var recordingLocation: String = "Not available"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = Firebase.auth
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize UI components
        noiseLevelGauge = findViewById(R.id.noiseLevelGauge)
        decibelTextView = findViewById(R.id.decibelTextView)
        bottomNavigationView = findViewById(R.id.bottom_navigation)
        startPauseButton = findViewById(R.id.startPauseButton)
        minDbTextView = findViewById(R.id.minDbTextView)
        avgDbTextView = findViewById(R.id.avgDbTextView)
        maxDbTextView = findViewById(R.id.maxDbTextView)
        askAiButton = findViewById(R.id.askAiButton)
        reportButton = findViewById(R.id.reportButton)
        noiseChart = findViewById(R.id.noiseChart)

        setupChart()
        setupBottomNavigation()
        setupControlButtons()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
        }
    }

    private fun setupChart() {
        noiseChart.description.isEnabled = false
        noiseChart.setTouchEnabled(true)
        noiseChart.setPinchZoom(true)
        val data = LineData()
        data.setValueTextColor(android.graphics.Color.WHITE)
        noiseChart.data = data
    }

    private fun addChartEntry(dbValue: Int) {
        val data = noiseChart.data
        if (data != null) {
            var set = data.getDataSetByIndex(0)
            if (set == null) {
                set = createChartDataSet()
                data.addDataSet(set)
            }
            data.addEntry(Entry(set.entryCount.toFloat(), dbValue.toFloat()), 0)
            data.notifyDataChanged()
            noiseChart.notifyDataSetChanged()
            noiseChart.setVisibleXRangeMaximum(60f)
            noiseChart.moveViewToX(data.entryCount.toFloat())
        }
    }

    private fun createChartDataSet(): LineDataSet {
        val set = LineDataSet(null, "Real-time dB")
        set.setDrawValues(false)
        set.setDrawCircles(false)
        set.mode = LineDataSet.Mode.CUBIC_BEZIER
        set.color = android.graphics.Color.CYAN
        set.lineWidth = 2f
        return set
    }

    private fun setupControlButtons() {
        startPauseButton.setOnClickListener {
            if (isRecording) {
                pauseRecording()
            } else {
                startRecording()
            }
        }
        reportButton.setOnClickListener {
            showReportDialog()
        }
        askAiButton.setOnClickListener {
            if(askAiButton.isEnabled){
                getAiAdvice(maxDb)
            } else {
                Toast.makeText(this, "AI advice is available for high noise levels.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBottomNavigation() {
        if (auth.currentUser == null) {
            bottomNavigationView.inflateMenu(R.menu.bottom_nav_menu_guest)
        } else {
            bottomNavigationView.inflateMenu(R.menu.bottom_nav_menu_logged_in)
        }

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_noise_detector -> true
                R.id.nav_nearby_clinic -> {
                    startActivity(Intent(this, MapActivity::class.java))
                    true
                }
                R.id.nav_music -> {
                    startActivity(Intent(this, MusicActivity::class.java))
                    true
                }
                R.id.nav_camera -> {
                    startActivity(Intent(this, CameraActivity::class.java))
                    true
                }
                R.id.nav_logout -> {
                    signOut()
                    true
                }
                R.id.nav_login -> {
                    navigateToLogin()
                    true
                }
                else -> false
            }
        }
    }

    private fun showReportDialog() {
        if (dbReadings.isEmpty()) {
            Toast.makeText(this, "No data recorded yet. Press Play to start.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_report, null)
        val reportMinDb = dialogView.findViewById<TextView>(R.id.report_min_db)
        val reportMaxDb = dialogView.findViewById<TextView>(R.id.report_max_db)
        val reportAvgDb = dialogView.findViewById<TextView>(R.id.report_avg_db)
        val reportDuration = dialogView.findViewById<TextView>(R.id.report_duration)
        val reportLocation = dialogView.findViewById<TextView>(R.id.report_location)
        val exportPdfButton = dialogView.findViewById<Button>(R.id.exportPdfButton)

        val currentDuration = if(isRecording) System.currentTimeMillis() - recordingStartTime else 0
        val finalDuration = totalElapsedTime + currentDuration
        val minutes = TimeUnit.MILLISECONDS.toMinutes(finalDuration)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(finalDuration) % 60
        val durationString = String.format("%02d min, %02d sec", minutes, seconds)

        val minDbText = "$minDb dB"
        val maxDbText = "$maxDb dB"
        val avgDbText = "${dbReadings.average().toInt()} dB"

        reportMinDb.text = minDbText
        reportMaxDb.text = maxDbText
        reportAvgDb.text = avgDbText
        reportDuration.text = durationString
        reportLocation.text = recordingLocation

        val reportString = """
            Noise Report
            -------------------
            Min dB: $minDbText
            Max dB: $maxDbText
            Avg dB: $avgDbText
            Duration: $durationString
            Location: $recordingLocation
        """.trimIndent()

        exportPdfButton.setOnClickListener {
            createAndSavePdf(reportString)
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun createAndSavePdf(reportText: String) {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint()

        var yPosition = 40f
        for (line in reportText.split("\n")) {
            canvas.drawText(line, 20f, yPosition, paint)
            yPosition += paint.descent() - paint.ascent()
        }

        pdfDocument.finishPage(page)

        val fileName = "NoiseReport_${System.currentTimeMillis()}.pdf"
        try {
            val fileOutputStream: FileOutputStream
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                fileOutputStream = resolver.openOutputStream(uri!!) as FileOutputStream
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                fileOutputStream = FileOutputStream(file)
            }

            pdfDocument.writeTo(fileOutputStream)
            Toast.makeText(this, "PDF saved to Downloads folder", Toast.LENGTH_LONG).show()

        } catch(e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error saving PDF: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            pdfDocument.close()
        }
    }

    private fun signOut() {
        pauseRecording()
        auth.signOut()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        val googleSignInClient = GoogleSignIn.getClient(this, gso)
        googleSignInClient.signOut().addOnCompleteListener {
            navigateToLogin()
        }
    }

    private fun navigateToLogin() {
        pauseRecording()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            RECORD_AUDIO_PERMISSION_CODE -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
                }
            }
            LOCATION_PERMISSION_CODE_FOR_REPORT -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    fetchLocationAndStartRecording()
                } else {
                    Toast.makeText(this, "Location permission denied.", Toast.LENGTH_SHORT).show()
                    startRecordingWithoutLocation()
                }
            }
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_CODE_FOR_REPORT)
        } else {
            fetchLocationAndStartRecording()
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocationAndStartRecording() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                try {
                    val geocoder = Geocoder(this, Locale.getDefault())
                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    if (addresses != null && addresses.isNotEmpty()) {
                        recordingLocation = addresses[0].getAddressLine(0) ?: "Address not found"
                    }
                } catch (e: IOException) {
                    recordingLocation = "Location service error"
                }
            } else {
                recordingLocation = "Location not available"
            }
            startRecordingWithoutLocation()
        }
    }

    private fun startRecordingWithoutLocation() {
        minDb = 120
        maxDb = 0
        dbReadings.clear()
        noiseChart.data.clearValues()
        noiseChart.invalidate()
        totalElapsedTime = 0L
        updateStatsUI()

        recordingStartTime = System.currentTimeMillis()
        isRecording = true
        startPauseButton.setImageResource(R.drawable.ic_pause)

        try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                isRecording = false
                startPauseButton.setImageResource(R.drawable.ic_play)
                return
            }

            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
            audioRecord?.startRecording()
            scope.launch { processAudio() }

        } catch (e: Exception) {
            Log.e("AudioRecord", "Error initializing recording", e)
            isRecording = false
            startPauseButton.setImageResource(R.drawable.ic_play)
        }
    }

    private fun pauseRecording() {
        if (!isRecording) return

        totalElapsedTime += System.currentTimeMillis() - recordingStartTime
        isRecording = false
        startPauseButton.setImageResource(R.drawable.ic_play)
    }

    private suspend fun processAudio() {
        val buffer = ShortArray(1024)
        while (isRecording) {
            val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (readSize > 0) {
                val rms = sqrt((0 until readSize).sumOf { buffer[it].toDouble() * buffer[it].toDouble() } / readSize)
                if (rms > 0) {
                    val db = (20 * log10(rms / 32767.0) + 94).toInt()

                    if (db < minDb) minDb = db
                    if (db > maxDb) maxDb = db
                    dbReadings.add(db)

                    withContext(Dispatchers.Main) {
                        updateNoiseUI(db)
                        updateStatsUI(minDb, dbReadings.average().toInt(), maxDb)
                        addChartEntry(db)
                    }
                }
            }
            delay(1000)
        }
    }

    private fun updateNoiseUI(dbValue: Int) {
        val safeDbValue = dbValue.coerceIn(0, 120)
        decibelTextView.text = "$safeDbValue dB"
        noiseLevelGauge.progress = safeDbValue

        askAiButton.isEnabled = safeDbValue > HIGH_NOISE_THRESHOLD
    }

    private fun updateStatsUI(min: Int? = null, avg: Int? = null, max: Int? = null) {
        minDbTextView.text = min?.toString() ?: "--"
        avgDbTextView.text = avg?.toString() ?: "--"
        maxDbTextView.text = max?.toString() ?: "--"
    }

    private fun getAiAdvice(currentDb: Int) {
        val loadingDialog = AlertDialog.Builder(this)
            .setView(R.layout.loading_dialog)
            .setCancelable(false)
            .create()
        loadingDialog.show()

        scope.launch {
            val prompt = "The current environmental noise level is $currentDb decibels. What are the potential health risks of this noise level, and what are three simple, actionable steps someone can take to protect their hearing?"
            try {

                val apiKey = "AIzaSyA0vTqzlmWrXRzZ1IaUiIeXwsrci4j1AXg"

                val chatHistory = mutableListOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt))))
                val payload = mapOf("contents" to chatHistory)
                val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
                val url = URL(apiUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(JSONObject(payload).toString())
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                val jsonResponse = JSONObject(response)
                val text = jsonResponse.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("AI Health Advice")
                        .setMessage(text)
                        .setPositiveButton("OK", null)
                        .show()
                }

            } catch (e: Exception) {
                Log.e("AI_API", "Error fetching AI advice", e)
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Failed to load AI advice. Please check your connection or API key.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        pauseRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        audioRecord?.release()
        audioRecord = null
        scope.cancel()
    }
}
