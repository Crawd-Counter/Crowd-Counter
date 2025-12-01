package com.objectcounter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
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

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
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
        binding.btnDetect.setOnClickListener {
            isDetecting = !isDetecting
            binding.btnDetect.text = if (isDetecting) "Stop" else "Detect"
            binding.btnDetect.setBackgroundColor(
                ContextCompat.getColor(this, if (isDetecting) R.color.red else R.color.green)
            )
            if (!isDetecting) {
                binding.overlayView.clearDetections()
                binding.tvCounts.text = "Tap Detect to count"
            }
        }

        binding.btnReset.setOnClickListener {
            isDetecting = false
            binding.btnDetect.text = "Detect"
            binding.btnDetect.setBackgroundColor(ContextCompat.getColor(this, R.color.green))
            binding.overlayView.clearDetections()
            binding.tvCounts.text = "Tap Detect to count"
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
        if (!isDetecting || objectDetector == null) {
            imageProxy.close()
            return
        }
        try {
            val detections = objectDetector?.detect(imageProxy) ?: emptyList()
            runOnUiThread {
                binding.tvCounts.text = "Count: ${detections.size}"
                binding.overlayView.setDetections(detections)
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
