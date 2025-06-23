package com.eudi.verifier

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.eudi.verifier.databinding.ActivityQrScanBinding
import com.eudi.verifier.qr.QRCodeAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QRScanActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityQrScanBinding
    private lateinit var cameraExecutor: ExecutorService
    
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required for QR scanning", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("QRScanActivity", "QRScanActivity started")
        binding = ActivityQrScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        setupUI()
        checkCameraPermissionAndStart()
    }
    
    private fun setupUI() {
        binding.apply {
            btnBack.setOnClickListener {
                finish()
            }
        }
    }
    
    private fun checkCameraPermissionAndStart() {
        android.util.Log.d("QRScanActivity", "Checking camera permission")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            android.util.Log.d("QRScanActivity", "Camera permission granted, starting camera")
            startCamera()
        } else {
            android.util.Log.d("QRScanActivity", "Camera permission not granted, requesting")
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    
    private fun startCamera() {
        android.util.Log.d("QRScanActivity", "Starting camera")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QRCodeAnalyzer { qrData ->
                        runOnUiThread {
                            onQRCodeDetected(qrData)
                        }
                    })
                }
            
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show()
                finish()
            }
            
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun onQRCodeDetected(qrData: String) {
        android.util.Log.d("QRScanActivity", "QR Code detected: $qrData")
        
        try {
            // Parse the wallet engagement QR data
            val gson = com.google.gson.Gson()
            val engagementData = gson.fromJson(qrData, Map::class.java)
            
            android.util.Log.d("QRScanActivity", "Parsed engagement data: $engagementData")
            android.util.Log.d("QRScanActivity", "Type: ${engagementData["type"]}, Version: ${engagementData["version"]} (${engagementData["version"]?.javaClass?.simpleName})")
            
            // More detailed validation logging
            val type = engagementData["type"]
            val version = engagementData["version"]
            
            android.util.Log.d("QRScanActivity", "Validation check - Type: '$type' (${type?.javaClass?.simpleName})")
            android.util.Log.d("QRScanActivity", "Validation check - Version: '$version' (${version?.javaClass?.simpleName})")
            android.util.Log.d("QRScanActivity", "Type equals WalletEngagement: ${type == "WalletEngagement"}")
            android.util.Log.d("QRScanActivity", "Version equals 1.0: ${version == 1.0}")
            android.util.Log.d("QRScanActivity", "Version equals 1: ${version == 1}")
            
            if (type == "WalletEngagement" && (version == 1.0 || version == 1)) {
                android.util.Log.d("QRScanActivity", "✅ Valid wallet engagement detected!")
                Toast.makeText(this, "✅ Valid wallet engagement detected", Toast.LENGTH_LONG).show()
                
                val resultIntent = Intent().apply {
                    putExtra("qr_data", qrData)
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            } else {
                android.util.Log.e("QRScanActivity", "❌ Invalid format - Type: '$type', Version: '$version'")
                Toast.makeText(this, "❌ Invalid QR code format: Type='$type', Version='$version'", Toast.LENGTH_LONG).show()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("QRScanActivity", "❌ Failed to parse QR data", e)
            Toast.makeText(this, "❌ Failed to parse QR data: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}