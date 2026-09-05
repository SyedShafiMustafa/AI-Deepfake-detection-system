// MainActivity.kt
package com.shafi.deepfakedetector

import android.animation.ObjectAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.gson.Gson
import com.shafi.deepfakedetector.databinding.ActivityMainBinding
import com.shafi.deepfakedetector.databinding.DialogSourceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    enum class SourceMode(val title: String, val mimeType: String) {
        PHOTO("Photo", "image/*"),
        VIDEO("Video", "video/*")
    }

    private lateinit var binding: ActivityMainBinding
    private var currentMode = SourceMode.PHOTO
    private var selectedImageUri: Uri? = null
    private var cameraPhotoUri: Uri? = null
    private var cameraVideoUri: Uri? = null
    private val tempCameraFiles = mutableListOf<File>()

    private val gson = Gson()
    private val historyAdapter = HistoryAdapter { entry -> restoreHistoryEntry(entry) }

    private val settingsPrefs by lazy {
        getSharedPreferences("settings", Context.MODE_PRIVATE)
    }
    private val historyPrefs by lazy {
        getSharedPreferences("history", Context.MODE_PRIVATE)
    }

    // ────── Launchers ──────

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            showSelectedMedia(it, if (currentMode == SourceMode.PHOTO) "GALLERY" else "VIDEO GALLERY")
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success && cameraPhotoUri != null) {
            selectedImageUri = cameraPhotoUri
            showSelectedMedia(cameraPhotoUri!!, "CAMERA")
        } else {
            toast("No photo was taken (camera canceled or failed)")
        }
    }

    private val videoCameraLauncher = registerForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { success: Boolean ->
        if (success && cameraVideoUri != null) {
            lifecycleScope.launch {
                val frameUri = extractVideoFrame(cameraVideoUri!!)
                if (frameUri != null) {
                    selectedImageUri = frameUri
                    showSelectedMedia(frameUri, "CAMERA VIDEO")
                } else {
                    toast("Could not read a frame from the video")
                }
            }
        } else {
            toast("No video was recorded (camera canceled or failed)")
        }
    }

    // ────── Lifecycle ──────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvHistory.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvHistory.adapter = historyAdapter

        binding.etServerIp.setText(
            settingsPrefs.getString(KEY_SERVER_IP, DEFAULT_SERVER_IP)
        )

        setupClickListeners()
        selectMode(SourceMode.PHOTO)
        refreshHistoryUi()
    }

    // ────── Click listeners ──────

    private fun setupClickListeners() {
        binding.btnModePhoto.setOnClickListener { selectMode(SourceMode.PHOTO) }
        binding.btnModeVideo.setOnClickListener { selectMode(SourceMode.VIDEO) }
        binding.btnAnalyze.setOnClickListener {
            selectedImageUri?.let { uri -> analyzeMedia(uri) }
        }
        binding.btnRemove.setOnClickListener { clearSelection() }
        binding.btnClearHistory.setOnClickListener { clearHistory() }
    }

    // ────── Mode selection ──────

    private fun selectMode(mode: SourceMode) {
        currentMode = mode
        val photoSelected = mode == SourceMode.PHOTO

        binding.btnModePhoto.isSelected = photoSelected
        binding.btnModeVideo.isSelected = !photoSelected

        val activeColor = color(R.color.colorAccent)
        val idleColor = color(R.color.colorTextSecondary)
        val idleSubColor = color(R.color.colorTextMuted)

        binding.tvModePhotoLabel.setTextColor(if (photoSelected) activeColor else idleColor)
        binding.tvModeVideoLabel.setTextColor(if (photoSelected) idleColor else activeColor)
        binding.tvModePhotoSub.setTextColor(if (photoSelected) idleColor else idleSubColor)
        binding.tvModeVideoSub.setTextColor(if (photoSelected) idleSubColor else idleColor)

        binding.tvPlaceholderTitle.text = "Choose a ${mode.title.lowercase()}"
        if (selectedImageUri == null) {
            binding.layoutPlaceholder.visibility = View.VISIBLE
        }
    }

    // ────── Source bottom sheet (Camera / Gallery) ──────

    private fun showSourceDialog() {
        val dialog = BottomSheetDialog(this)
        val sheet = DialogSourceBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)

        val isPhoto = currentMode == SourceMode.PHOTO
        sheet.tvSheetTitle.text = "Add ${currentMode.title.lowercase()}"

        sheet.tvSheetCameraIcon.text = if (isPhoto) "📷" else "🎥"
        sheet.tvSheetCameraTitle.text = if (isPhoto) "Camera" else "Record"
        sheet.tvSheetCameraDesc.text =
            if (isPhoto) "Capture right now" else "Record a short clip"
        sheet.tvSheetGalleryIcon.text = if (isPhoto) "🖼️" else "📂"
        sheet.tvSheetGalleryTitle.text = "Gallery"
        sheet.tvSheetGalleryDesc.text =
            if (isPhoto) "Pick an image from your device" else "Pick a video from your device"

        sheet.btnSheetCamera.setOnClickListener {
            dialog.dismiss()
            openCamera()
        }
        sheet.btnSheetGallery.setOnClickListener {
            dialog.dismiss()
            galleryLauncher.launch(currentMode.mimeType)
        }
        dialog.show()
    }

    // ────── Camera helpers ──────

    private fun openCamera() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            toast("No camera found on this device", long = true)
            return
        }

        val ext = if (currentMode == SourceMode.PHOTO) "jpg" else "mp4"
        val mediaFile: File? = try {
            createMediaFile(ext)
        } catch (ex: IOException) {
            toast("Could not prepare a file for capture", long = true)
            null
        }
        if (mediaFile == null) return

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", mediaFile)
        try {
            if (currentMode == SourceMode.PHOTO) {
                cameraPhotoUri = uri
                cameraLauncher.launch(uri)
            } else {
                cameraVideoUri = uri
                videoCameraLauncher.launch(uri)
            }
        } catch (ex: Exception) {
            toast("Could not open the camera app", long = true)
        }
    }

    private fun createMediaFile(ext: String): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File = getExternalFilesDir(null)
            ?: throw IOException("External storage not available")
        val file = File(storageDir, "DEEPFAKE_${timeStamp}.$ext")
        // Do NOT pre-create the file: some camera apps (e.g. Samsung) fail to
        // write when the target file already exists as an empty 0-byte file.
        if (file.exists()) {
            file.delete()
        }
        tempCameraFiles.add(file)
        return file
    }

    private suspend fun extractVideoFrame(videoUri: Uri): Uri? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this@MainActivity, videoUri)
            val durationMs =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            // Grab a frame from ~1 second in (or halfway for very short clips)
            val timeUs = min(1_000L, durationMs / 2).coerceAtLeast(0L) * 1000L
            val frame: Bitmap? = retriever
                .getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0)

            frame?.let { bitmap ->
                val dir = File(filesDir, "frames").apply { mkdirs() }
                val file = File(dir, "frame_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ────── UI updates ──────

    private fun showSelectedMedia(uri: Uri, source: String) {
        binding.layoutPlaceholder.visibility = View.GONE
        binding.imagePreview.visibility = View.VISIBLE
        binding.cardResult.visibility = View.GONE
        binding.tvSourceChip.visibility = View.VISIBLE
        binding.tvSourceChip.text = source
        binding.btnRemove.visibility = View.VISIBLE

        Glide.with(this).load(uri).centerCrop().into(binding.imagePreview)
        binding.btnAnalyze.isEnabled = true
    }

    private fun clearSelection() {
        selectedImageUri = null
        binding.layoutPlaceholder.visibility = View.VISIBLE
        binding.imagePreview.visibility = View.GONE
        binding.tvSourceChip.visibility = View.GONE
        binding.btnRemove.visibility = View.GONE
        binding.cardResult.visibility = View.GONE
        binding.btnAnalyze.isEnabled = false
    }

    private fun showLoading(show: Boolean) {
        binding.layoutLoading.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnAnalyze.isEnabled = !show && selectedImageUri != null
        binding.btnModePhoto.isEnabled = !show
        binding.btnModeVideo.isEnabled = !show
    }

    // ────── Analysis ──────

    private fun analyzeMedia(uri: Uri) {
        lifecycleScope.launch {
            showLoading(true)
            binding.cardResult.visibility = View.GONE

            // Remember the last used server IP
            settingsPrefs.edit()
                .putString(KEY_SERVER_IP, binding.etServerIp.text.toString().trim())
                .apply()

            try {
                val result = withContext(Dispatchers.IO) {
                    callFlaskApi(uri)
                }

                showLoading(false)

                if (result != null) {
                    val saved = withContext(Dispatchers.IO) { persistMediaToHistory(uri) }
                    cleanupTempFiles(uri)

                    if (saved != null) {
                        addHistoryEntry(
                            HistoryEntry(
                                filePath = saved.absolutePath,
                                mode = currentMode.name,
                                label = result.label,
                                confidence = result.confidence,
                                rawScore = result.raw_score,
                                message = result.message,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                    displayResult(result)
                } else {
                    toast("Error: Could not connect to server. Check IP address.")
                }
            } catch (e: Exception) {
                showLoading(false)
                toast("Error: ${e.message}")
            }
        }
    }

    private suspend fun callFlaskApi(uri: Uri): PredictionResponse? {
        return try {
            val serverIp = binding.etServerIp.text.toString().trim()
            val baseUrl = "http://$serverIp/"

            val inputStream = contentResolver.openInputStream(uri)
                ?: return null
            val imageBytes = inputStream.readBytes()
            inputStream.close()

            val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
            val imagePart = MultipartBody.Part.createFormData("image", "image.jpg", requestBody)

            val apiService = RetrofitClient.create(baseUrl)
            val response = apiService.predictImage(imagePart)

            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            null
        }
    }

    // ────── History persistence ──────

    private suspend fun persistMediaToHistory(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(filesDir, "history").apply { mkdirs() }
            val file = File(dir, "${System.currentTimeMillis()}.jpg")
            val input = contentResolver.openInputStream(uri)
                ?: return@withContext null
            input.use { ins ->
                FileOutputStream(file).use { out -> ins.copyTo(out) }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    /** Remove camera originals and extracted frames once they are saved to history. */
    private fun cleanupTempFiles(analyzedUri: Uri) {
        tempCameraFiles.forEach { file ->
            runCatching { if (file.exists()) file.delete() }
        }
        tempCameraFiles.clear()

        val path = analyzedUri.path.orEmpty()
        if (path.contains("/frames/")) {
            runCatching { File(path).delete() }
        }
    }

    private fun loadHistory(): List<HistoryEntry> {
        val json = historyPrefs.getString("entries", null) ?: return emptyList()
        return try {
            gson.fromJson(json, Array<HistoryEntry>::class.java)
                .toList()
                .sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(entries: List<HistoryEntry>) {
        historyPrefs.edit().putString("entries", gson.toJson(entries)).apply()
    }

    private fun addHistoryEntry(entry: HistoryEntry) {
        val entries = loadHistory().toMutableList()
        entries.add(0, entry)
        val pruned = entries.take(MAX_HISTORY)
        (entries - pruned.toSet()).forEach { File(it.filePath).delete() }
        saveHistory(pruned)
        refreshHistoryUi()
    }

    private fun clearHistory() {
        loadHistory().forEach { File(it.filePath).delete() }
        historyPrefs.edit().remove("entries").apply()
        refreshHistoryUi()
        toast("History cleared")
    }

    private fun refreshHistoryUi() {
        val entries = loadHistory()
        historyAdapter.submit(entries)
        val hasHistory = entries.isNotEmpty()
        binding.rvHistory.visibility = if (hasHistory) View.VISIBLE else View.GONE
        binding.tvHistoryEmpty.visibility = if (hasHistory) View.GONE else View.VISIBLE
        binding.btnClearHistory.visibility = if (hasHistory) View.VISIBLE else View.GONE
    }

    private fun restoreHistoryEntry(entry: HistoryEntry) {
        val file = File(entry.filePath)
        if (!file.exists()) {
            toast("This history item is no longer available")
            return
        }
        selectedImageUri = Uri.fromFile(file)
        showSelectedMedia(selectedImageUri!!, if (entry.mode == "VIDEO") "VIDEO" else "PHOTO")
        displayResult(
            PredictionResponse(
                label = entry.label,
                confidence = entry.confidence,
                raw_score = entry.rawScore,
                message = entry.message
            )
        )
    }

    // ────── Result display ──────

    private fun displayResult(result: PredictionResponse) {
        binding.cardResult.visibility = View.VISIBLE

        val isReal = result.label == "REAL"
        val verdictColor = color(
            if (isReal) R.color.colorRealGreen else R.color.colorFakeRed
        )

        binding.tvVerdict.text = result.label
        binding.tvVerdict.setTextColor(verdictColor)
        binding.tvVerdictEmoji.text = if (isReal) "✅" else "🚨"

        val conf = result.confidence.coerceIn(0f, 100f)

        // Animated circular gauge
        binding.gaugeConfidence.setColors(
            verdictColor,
            color(if (isReal) R.color.colorRealGreenDeep else R.color.colorFakeRedDeep)
        )
        binding.gaugeConfidence.setProgress(conf)

        // Strength label + verdict-specific probability
        binding.tvStrength.text = strengthLabel(conf)
        binding.tvConfidence.text = String.format(
            Locale.US, "%.1f%% probability this is %s", conf, result.label
        )

        binding.tvRawScore.text = String.format(
            Locale.US, "Model output (raw): %.4f", result.raw_score
        )
        binding.tvMessage.text = result.message

        // Animated confidence bar (verdict-colored)
        binding.progressConfidence.progressDrawable = resources.getDrawable(
            if (isReal) R.drawable.confidence_bar_green else R.drawable.confidence_bar_red,
            theme
        )
        binding.progressConfidence.progress = 0
        ObjectAnimator.ofInt(binding.progressConfidence, "progress", 0, conf.toInt()).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            start()
        }

        binding.cardResult.post {
            binding.cardResult.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, binding.cardResult.width, binding.cardResult.height),
                true
            )
        }
    }

    private fun strengthLabel(conf: Float): String = when {
        conf >= 90f -> "VERY HIGH CONFIDENCE"
        conf >= 75f -> "HIGH CONFIDENCE"
        conf >= 60f -> "MODERATE CONFIDENCE"
        else -> "LOW CONFIDENCE"
    }

    // ────── Misc helpers ──────

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    private fun toast(message: String, long: Boolean = false) {
        Toast.makeText(
            this,
            message,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        private const val KEY_SERVER_IP = "server_ip"
        private const val DEFAULT_SERVER_IP = "10.0.2.2:5000"
        private const val MAX_HISTORY = 30
    }
}