package com.eudi.verifier

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.eudi.verifier.ble.BLEVerifierClient
import com.eudi.verifier.databinding.ActivityMainBinding
import com.eudi.verifier.models.Constraints
import com.eudi.verifier.models.Field
import com.eudi.verifier.models.InputDescriptor
import com.eudi.verifier.models.PresentationDefinition
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var bleClient: BLEVerifierClient
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startVerification()
        } else {
            Toast.makeText(this, "Permissions required for verification", Toast.LENGTH_SHORT).show()
        }
    }
    
    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val qrData = result.data?.getStringExtra("qr_data")
            if (qrData != null) {
                handleQRScanResult(qrData)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("MainActivity", "MainActivity onCreate started")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        bleClient = BLEVerifierClient(this)
        
        setupUI()
        observeBLEClient()
        android.util.Log.d("MainActivity", "MainActivity onCreate completed")
    }
    
    private fun setupUI() {
        android.util.Log.d("MainActivity", "Setting up UI")
        binding.apply {
            btnStartVerification.setOnClickListener {
                android.util.Log.d("MainActivity", "Start Verification button clicked")
                checkPermissionsAndStart()
            }
            
            btnScanQr.setOnClickListener {
                android.util.Log.d("MainActivity", "QR Scan button clicked")
                val intent = Intent(this@MainActivity, QRScanActivity::class.java)
                qrScanLauncher.launch(intent)
            }
            
            btnViewHistory.setOnClickListener {
                android.util.Log.d("MainActivity", "View History button clicked")
                Toast.makeText(this@MainActivity, "Verification history coming soon", Toast.LENGTH_SHORT).show()
            }
            
            updateUI()
        }
        android.util.Log.d("MainActivity", "UI setup completed")
    }
    
    private fun updateUI() {
        binding.apply {
            tvStatus.text = when (bleClient.clientState.value) {
                is BLEVerifierClient.BLEClientState.Idle -> "Ready to verify"
                is BLEVerifierClient.BLEClientState.Scanning -> "Scanning for wallets..."
                is BLEVerifierClient.BLEClientState.Connecting -> "Connecting to wallet..."
                is BLEVerifierClient.BLEClientState.Connected -> "Connected - discovering services..."
                is BLEVerifierClient.BLEClientState.ServicesReady -> "Services ready - sending request..."
                is BLEVerifierClient.BLEClientState.RequestSent -> "Waiting for user response..."
                is BLEVerifierClient.BLEClientState.Error -> "Error: ${(bleClient.clientState.value as BLEVerifierClient.BLEClientState.Error).message}"
            }
            
            btnStartVerification.isEnabled = bleClient.clientState.value is BLEVerifierClient.BLEClientState.Idle
        }
    }
    
    private fun checkPermissionsAndStart() {
        val requiredPermissions = mutableListOf<String>().apply {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }
        
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            startVerification()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
    
    private fun startVerification() {
        // Start scanning for wallets
        bleClient.scanForWallets()
    }
    
    private fun observeBLEClient() {
        lifecycleScope.launch {
            bleClient.clientState.collect { state ->
                runOnUiThread { 
                    updateUI()
                    
                    if (state is BLEVerifierClient.BLEClientState.ServicesReady) {
                        // Automatically send verification request when services are ready
                        sendVerificationRequest()
                    }
                }
            }
        }
        
        lifecycleScope.launch {
            bleClient.receivedVPToken.collect { vpToken ->
                if (vpToken != null) {
                    runOnUiThread {
                        val intent = Intent(this@MainActivity, VerificationResultActivity::class.java)
                        intent.putExtra("vp_token", vpToken)
                        startActivity(intent)
                        bleClient.disconnect()
                    }
                }
            }
        }
    }
    
    private fun handleQRScanResult(qrData: String) {
        try {
            val gson = com.google.gson.Gson()
            val engagementData = gson.fromJson(qrData, Map::class.java)
            
            // Extract BLE connection info and connect to wallet
            val serviceUuid = engagementData["serviceUuid"] as? String
            val characteristicUuid = engagementData["characteristicUuid"] as? String
            val deviceMac = engagementData["deviceMac"] as? String
            
            if (serviceUuid != null && characteristicUuid != null && deviceMac != null) {
                val bleEngagement = com.eudi.verifier.models.BLEEngagementData(
                    deviceMac = deviceMac,
                    serviceUuid = serviceUuid,
                    characteristicUuid = characteristicUuid
                )
                
                Toast.makeText(this, "Connecting to wallet...", Toast.LENGTH_SHORT).show()
                bleClient.connectToWallet(bleEngagement)
            } else {
                Toast.makeText(this, "Invalid engagement data", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to parse QR data: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun sendVerificationRequest() {
        val presentationDefinition = PresentationDefinition(
            name = "Identity Verification",
            purpose = "Verify your identity for access control",
            input_descriptors = listOf(
                InputDescriptor(
                    id = "eudi_credential",
                    name = "EUDI Identity Credential",
                    purpose = "Verify identity attributes",
                    constraints = Constraints(
                        fields = listOf(
                            Field(path = listOf("$.credentialSubject.givenName")),
                            Field(path = listOf("$.credentialSubject.familyName")),
                            Field(path = listOf("$.credentialSubject.dateOfBirth")),
                            Field(path = listOf("$.credentialSubject.address"))
                        )
                    )
                )
            )
        )
        
        bleClient.sendVerificationRequest(presentationDefinition)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        bleClient.disconnect()
    }
}