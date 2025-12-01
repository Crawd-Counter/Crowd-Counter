package com.objectcounter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.objectcounter.databinding.ActivityMainBinding
import com.objectcounter.ml.ObjectDetector
import com.objectcounter.ml.Detection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var objectDetector: ObjectDetector? = null
    private var isDetecting = false
    private var isPhotoMode = false
    
    // Stabilization
    private val countHistory = mutableListOf<Int>()
    private val historySize = 10
    private var stableCount = 0
    private var lastDetections: List<Detection> = emptyList()
    
    // Photo mode
    private var photoImageView: ImageView? = null
    private var selectedBitmap: Bitmap? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
    }
    
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processSelectedImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupUI()
        checkCameraPermission()
        initDetector()
    }


    private fun setupUI() {
        binding.btnPhoto.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        
        binding.btnDetect.setOnClickListener {
            if (isPhotoMode) {
                // Already showing photo result, do nothing
                return@setOnClickListener
            }
            isDetecting = !isDetecting
            binding.btnDetect.text = if (isDetecting) "Stop" else "Detect"
            binding.btnDetect.setBackgroundColor(
                ContextCompat.getColor(this, if (isDetecting) R.color.red else R.color.green)
            )
            if (!isDetecting) {
                binding.overlayView.clearDetections()
                binding.tvCounts.text = "Tap Detect to count"
            } else {
                countHistory.clear()
                stableCount = 0
                binding.tvCounts.text = "Counting..."
            }
        }

        binding.btnReset.setOnClickListener {
            resetToCamera()
        }
    }
    
    private fun resetToCamera() {
        isDetecting = false
        isPhotoMode = false
        countHistory.clear()
        stableCount = 0
        selectedBitmap = null
        
        // Remove photo ImageView if exists
        photoImageView?.let { 
            binding.cameraContainer.removeView(it)
            photoImageView = null
        }
        
        // Show camera preview
        binding.previewView.visibility = View.VISIBLE
        binding.btnDetect.isEnabled = true
        binding.btnDetect.text = "Detect"
        binding.btnDetect.setBackgroundColor(ContextCompat.getColor(this, R.color.green))
        binding.overlayView.clearDetections()
        binding.tvCounts.text = "Tap Detect to count"
    }
    
    private fun processSelectedImage(uri: Uri) {
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            
            // Convert to ARGB_8888 if needed
            val argbBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                bitmap
            }
            
            selectedBitmap = argbBitmap
            showPhotoMode(argbBitmap)
            
        } catch (e: Exception) {
            Log.e("ObjectCounter", "Failed to load image", e)
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showPhotoMode(bitmap: Bitmap) {
        isPhotoMode = true
        isDetecting = false
        
        // Hide camera preview
        binding.previewView.visibility = View.GONE
        
        // Create ImageView for photo
        if (photoImageView == null) {
            photoImageView = ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            binding.cameraContainer.addView(photoImageView, 0)
        }
        photoImageView?.setImageBitmap(bitmap)
        
        // Detect objects in photo
        binding.tvCounts.text = "Processing..."
        binding.btnDetect.isEnabled = false
        
        // Wait for layout then set photo bounds and detect
        binding.overlayView.post {
            binding.overlayView.setPhotoBounds(bitmap.width, bitmap.height)
            
            cameraExecutor.execute {
                try {
                    val detections = objectDetector?.detectBitmap(bitmap) ?: emptyList()
                    runOnUiThread {
                        binding.tvCounts.text = "Count: ${detections.size}"
                        binding.overlayView.setDetections(detections)
                    }
                } catch (e: Exception) {
                    Log.e("ObjectCounter", "Detection failed", e)
                    runOnUiThread {
                        binding.tvCounts.text = "Detection failed"
                    }
                }
            }
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build().also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (e: Exception) {
                Log.e("ObjectCounter", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        if (!isDetecting || isPhotoMode || objectDetector == null) {
            imageProxy.close()
            return
        }
        try {
            val detections = objectDetector?.detect(imageProxy) ?: emptyList()
            val currentCount = detections.size
            
            countHistory.add(currentCount)
            if (countHistory.size > historySize) countHistory.removeAt(0)
            
            val newStableCount = if (countHistory.size >= 5) {
                countHistory.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: currentCount
            } else currentCount
            
            if (newStableCount != stableCount || countHistory.size >= historySize) {
                stableCount = newStableCount
                lastDetections = detections
            }
            
            runOnUiThread {
                binding.tvCounts.text = "Count: $stableCount"
                binding.overlayView.setDetections(lastDetections)
            }
        } catch (e: Exception) {
            Log.e("ObjectCounter", "Detection failed", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun initDetector() {
        binding.tvStatus.text = "Initializing..."
        binding.tvStatus.visibility = View.VISIBLE
        cameraExecutor.execute {
            try {
                objectDetector = ObjectDetector()
                runOnUiThread {
                    binding.tvStatus.visibility = View.GONE
                    binding.btnDetect.isEnabled = true
                    binding.tvCounts.text = "Tap Detect to count"
                }
            } catch (e: Exception) {
                Log.e("ObjectCounter", "Init failed", e)
                runOnUiThread {
                    binding.tvStatus.text = "Init failed"
                    Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        objectDetector?.close()
    }
}
