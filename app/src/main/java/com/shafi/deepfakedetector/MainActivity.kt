// MainActivity.kt
package com.shafi.deepfakedetector

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.shafi.deepfakedetector.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedImageUri: Uri? = null
    private var cameraImageUri: Uri? = null

    // ────── Register launchers for gallery and camera ──────

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            showSelectedImage(it)
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success && cameraImageUri != null) {
            selectedImageUri = cameraImageUri
            showSelectedImage(cameraImageUri!!)
        } else {
            Toast.makeText(
                this,
                "No photo was taken (camera canceled or failed)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ────── Lifecycle ──────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
    }


    private fun setupClickListeners() {

        binding.btnGallery.setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        // TakePicture delegates to the system camera app, so the CAMERA
        // permission is NOT required here. Requesting it only adds a failure
        // path (denied -> camera button appears dead), so we launch directly.
        binding.btnCamera.setOnClickListener {
            openCamera()
        }

        binding.btnAnalyze.setOnClickListener {
            selectedImageUri?.let { uri ->
                analyzeImage(uri)
            }
        }
    }

    // ────── Camera helper ──────

    private fun openCamera() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(
                this,
                "No camera found on this device",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val photoFile: File? = try {
            createImageFile()
        } catch (ex: IOException) {
            Toast.makeText(
                this,
                "Could not prepare a file for the photo",
                Toast.LENGTH_LONG
            ).show()
            null
        }

        photoFile?.also {
            cameraImageUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                it
            )
            try {
                cameraLauncher.launch(cameraImageUri)
            } catch (ex: Exception) {
                Toast.makeText(
                    this,
                    "Could not open the camera app",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File = getExternalFilesDir(null)
            ?: throw IOException("External storage not available")
        val file = File(storageDir, "DEEPFAKE_${timeStamp}.jpg")
        // Do NOT pre-create the file: some camera apps (e.g. Samsung) fail to
        // write when the target file already exists as an empty 0-byte file.
        if (file.exists()) {
            file.delete()
        }
        return file
    }

    // ────── UI updates ──────

    private fun showSelectedImage(uri: Uri) {
        binding.layoutPlaceholder.visibility = View.GONE
        binding.imagePreview.visibility = View.VISIBLE
        binding.cardResult.visibility = View.GONE

        Glide.with(this).load(uri).centerCrop().into(binding.imagePreview)

        binding.btnAnalyze.isEnabled = true
    }

    private fun showLoading(show: Boolean) {
        binding.layoutLoading.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnAnalyze.isEnabled = !show
        binding.btnGallery.isEnabled = !show
        binding.btnCamera.isEnabled = !show
    }

    // ────── API call ──────

    private fun analyzeImage(uri: Uri) {
        lifecycleScope.launch {
            showLoading(true)
            binding.cardResult.visibility = View.GONE

            try {
                val result = withContext(Dispatchers.IO) {
                    callFlaskApi(uri)
                }

                showLoading(false)

                if (result != null) {
                    displayResult(result)
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Error: Could not connect to server. Check IP address.",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                showLoading(false)
                Toast.makeText(
                    this@MainActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun callFlaskApi(uri: Uri): PredictionResponse? {
        return try {
            val serverIp = binding.etServerIp.text.toString().trim()
            val baseUrl = "http://$serverIp/"

            // Read the image bytes from URI
            val inputStream = contentResolver.openInputStream(uri)
                ?: return null
            val imageBytes = inputStream.readBytes()
            inputStream.close()

            // Build the multipart request body
            val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
            val imagePart = MultipartBody.Part.createFormData("image", "image.jpg", requestBody)

            // Make the API call
            val apiService = RetrofitClient.create(baseUrl)
            val response = apiService.predictImage(imagePart)

            if (response.isSuccessful) {
                response.body()
            } else {
                null
            }

        } catch (e: Exception) {
            null
        }
    }

    // ────── Display result ──────

    private fun displayResult(result: PredictionResponse) {
        binding.cardResult.visibility = View.VISIBLE

        val isReal = result.label == "REAL"

        // Set verdict text and color
        binding.tvVerdict.text = result.label
        binding.tvVerdict.setTextColor(
            ContextCompat.getColor(
                this,
                if (isReal) R.color.colorRealGreen else R.color.colorFakeRed
            )
        )

        // Set emoji
        binding.tvVerdictEmoji.text = if (isReal) "✅" else "🚨"

        // Set confidence
        binding.tvConfidence.text = "${result.confidence}% confidence"

        // Update progress bar
        binding.progressConfidence.progress = result.confidence.toInt()

        // Raw score
        binding.tvRawScore.text = "Raw model score: ${result.raw_score}"

        // Full message
        binding.tvMessage.text = result.message

        // Scroll to result
        binding.cardResult.post {
            binding.cardResult.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, binding.cardResult.width, binding.cardResult.height),
                true
            )
        }
    }
}