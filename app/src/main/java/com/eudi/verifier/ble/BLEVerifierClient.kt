package com.eudi.verifier.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.eudi.verifier.models.BLEEngagementData
import com.eudi.verifier.models.PresentationDefinition
import com.eudi.verifier.models.VerificationRequest
import com.eudi.verifier.models.InputDescriptor
import com.eudi.verifier.models.Constraints
import com.eudi.verifier.models.Field
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

@SuppressLint("MissingPermission")
class BLEVerifierClient(private val context: Context) {
    
    companion object {
        private const val TAG = "BLEVerifierClient"
        private const val CONNECTION_TIMEOUT_MS = 30000L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 10000L
        private const val WRITE_TIMEOUT_MS = 5000L
        private const val DEFAULT_MTU = 23
        private const val HEADER_SIZE = 3 // ATT header overhead
        val WALLET_SERVICE_UUID: UUID = UUID.fromString("12345678-1234-5678-9abc-123456789abc")
        val WALLET_CHARACTERISTIC_UUID: UUID = UUID.fromString("87654321-4321-8765-cba9-876543210def")
    }
    
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    private var connectionTimeoutRunnable: Runnable? = null
    private var serviceDiscoveryTimeoutRunnable: Runnable? = null
    private var writeTimeoutRunnable: Runnable? = null
    private var currentMtu = DEFAULT_MTU
    private val chunkBuffer = mutableMapOf<Int, ByteArray>()
    private var expectedTotalChunks = 0
    private var receivedChunks = 0
    
    private val _clientState = MutableStateFlow<BLEClientState>(BLEClientState.Idle)
    val clientState: StateFlow<BLEClientState> = _clientState
    
    private val _receivedVPToken = MutableStateFlow<String?>(null)
    val receivedVPToken: StateFlow<String?> = _receivedVPToken
    
    private val gson = Gson()
    
    sealed class BLEClientState {
        object Idle : BLEClientState()
        object Scanning : BLEClientState()
        object Connecting : BLEClientState()
        object Connected : BLEClientState()
        object ServicesReady : BLEClientState()
        object RequestSent : BLEClientState()
        data class Error(val message: String) : BLEClientState()
    }
    
    fun connectToWallet(engagementData: BLEEngagementData) {
        try {
            bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            
            if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
                _clientState.value = BLEClientState.Error("Bluetooth not available or disabled")
                return
            }
            
            _clientState.value = BLEClientState.Connecting
            
            // Set connection timeout
            connectionTimeoutRunnable = Runnable {
                Log.e(TAG, "Connection timeout")
                _clientState.value = BLEClientState.Error("Connection timeout")
                disconnect()
            }
            handler.postDelayed(connectionTimeoutRunnable!!, CONNECTION_TIMEOUT_MS)
            
            // Connect directly to the device
            val device = bluetoothAdapter?.getRemoteDevice(engagementData.deviceMac)
            bluetoothGatt = device?.connectGatt(context, false, gattCallback)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to wallet", e)
            _clientState.value = BLEClientState.Error("Connection failed: ${e.message}")
        }
    }
    
    fun scanForWallets() {
        try {
            bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            scanner = bluetoothAdapter?.bluetoothLeScanner
            
            if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
                _clientState.value = BLEClientState.Error("Bluetooth not available or disabled")
                return
            }
            
            _clientState.value = BLEClientState.Scanning
            
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            
            scanner?.startScan(null, settings, scanCallback)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan", e)
            _clientState.value = BLEClientState.Error("Scan failed: ${e.message}")
        }
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val serviceUuids = result.scanRecord?.serviceUuids
            
            // Check if this is a wallet device
            if (serviceUuids?.any { it.uuid == WALLET_SERVICE_UUID } == true) {
                Log.d(TAG, "Found wallet device: ${device.address}")
                scanner?.stopScan(this)
                
                _clientState.value = BLEClientState.Connecting
                bluetoothGatt = device.connectGatt(context, false, gattCallback)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _clientState.value = BLEClientState.Error("Scan failed: $errorCode")
        }
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to wallet")
                    clearTimeouts()
                    _clientState.value = BLEClientState.Connected
                    
                    // Set service discovery timeout
                    serviceDiscoveryTimeoutRunnable = Runnable {
                        Log.e(TAG, "Service discovery timeout")
                        _clientState.value = BLEClientState.Error("Service discovery timeout")
                        disconnect()
                    }
                    handler.postDelayed(serviceDiscoveryTimeoutRunnable!!, SERVICE_DISCOVERY_TIMEOUT_MS)
                    
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from wallet")
                    clearTimeouts()
                    _clientState.value = BLEClientState.Idle
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            clearTimeouts()
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(WALLET_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(WALLET_CHARACTERISTIC_UUID)
                
                if (characteristic != null) {
                    // Enable notifications
                    gatt.setCharacteristicNotification(characteristic, true)
                    
                    val descriptor = characteristic.getDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    )
                    descriptor?.let {
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        val success = gatt.writeDescriptor(it)
                        if (success) {
                            Log.d(TAG, "Descriptor write initiated, waiting for completion")
                        } else {
                            Log.e(TAG, "Failed to initiate descriptor write")
                            _clientState.value = BLEClientState.Error("Failed to enable notifications")
                        }
                    } ?: run {
                        Log.e(TAG, "CCCD descriptor not found")
                        _clientState.value = BLEClientState.Error("Notification descriptor not available")
                    }
                } else {
                    Log.e(TAG, "Required service or characteristic not found")
                    _clientState.value = BLEClientState.Error("Wallet service not available")
                }
            } else {
                Log.e(TAG, "Service discovery failed: $status")
                _clientState.value = BLEClientState.Error("Service discovery failed")
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            Log.d(TAG, "Characteristic changed: char=${characteristic.uuid}, value size=${characteristic.value?.size ?: 0}")
            
            if (characteristic.uuid == WALLET_CHARACTERISTIC_UUID) {
                val data = characteristic.value
                if (data != null) {
                    processReceivedData(data)
                } else {
                    Log.w(TAG, "Received notification with null data")
                }
            } else {
                Log.w(TAG, "Notification from unknown characteristic: ${characteristic.uuid}")
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            Log.d(TAG, "Characteristic changed (new API): char=${characteristic.uuid}, value size=${value.size}")
            
            if (characteristic.uuid == WALLET_CHARACTERISTIC_UUID) {
                if (value.isNotEmpty()) {
                    processReceivedData(value)
                } else {
                    Log.w(TAG, "Received notification with empty data via new API")
                }
            } else {
                Log.w(TAG, "Notification from unknown characteristic via new API: ${characteristic.uuid}")
            }
        }
        
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.d(TAG, "Characteristic write callback: char=${characteristic.uuid}, status=$status")
            clearTimeouts()
            
            if (characteristic.uuid == WALLET_CHARACTERISTIC_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Verification request sent successfully")
                    _clientState.value = BLEClientState.RequestSent
                } else {
                    Log.e(TAG, "Failed to send verification request: status=$status")
                    val errorMessage = when (status) {
                        BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "Write not permitted"
                        BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "Invalid attribute length"
                        BluetoothGatt.GATT_CONNECTION_CONGESTED -> "Connection congested"
                        BluetoothGatt.GATT_FAILURE -> "GATT failure"
                        else -> "Unknown error: $status"
                    }
                    _clientState.value = BLEClientState.Error("Failed to send request: $errorMessage")
                }
            } else {
                Log.w(TAG, "Write callback for unknown characteristic: ${characteristic.uuid}")
            }
        }
        
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "Descriptor write callback: descriptor=${descriptor.uuid}, status=$status")
            
            if (descriptor.uuid == UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Notifications enabled successfully, requesting larger MTU")
                    // Request larger MTU before sending verification request
                    val success = gatt.requestMtu(512)
                    if (!success) {
                        Log.w(TAG, "Failed to request MTU, using default")
                        _clientState.value = BLEClientState.ServicesReady
                    }
                } else {
                    Log.e(TAG, "Failed to enable notifications: status=$status")
                    _clientState.value = BLEClientState.Error("Failed to enable notifications")
                }
            } else {
                Log.w(TAG, "Descriptor write callback for unknown descriptor: ${descriptor.uuid}")
            }
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed: mtu=$mtu, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
                Log.d(TAG, "MTU negotiated successfully: $mtu bytes")
            } else {
                Log.w(TAG, "MTU negotiation failed, using default: $currentMtu bytes")
            }
            _clientState.value = BLEClientState.ServicesReady
        }
    }
    
    private fun processReceivedData(data: ByteArray) {
        if (data.size >= 2) {
            // Check if this is a chunked response (has chunk header)
            val chunkIndex = data[0].toInt()
            val totalChunks = data[1].toInt()
            val chunkData = data.sliceArray(2 until data.size)
            
            if (totalChunks > 1) {
                // This is a chunked response
                Log.d(TAG, "Received chunk $chunkIndex/$totalChunks (${chunkData.size} bytes)")
                
                if (expectedTotalChunks == 0) {
                    expectedTotalChunks = totalChunks
                    chunkBuffer.clear()
                    receivedChunks = 0
                }
                
                if (totalChunks == expectedTotalChunks) {
                    chunkBuffer[chunkIndex] = chunkData
                    receivedChunks++
                    
                    if (receivedChunks == expectedTotalChunks) {
                        // All chunks received, reassemble
                        val completeData = reassembleChunks()
                        if (completeData != null) {
                            val vpToken = String(completeData, Charsets.UTF_8)
                            Log.d(TAG, "Reassembled VP Token (${completeData.size} bytes): $vpToken")
                            _receivedVPToken.value = vpToken
                        }
                        // Reset for next message
                        expectedTotalChunks = 0
                        chunkBuffer.clear()
                        receivedChunks = 0
                    }
                } else {
                    Log.w(TAG, "Chunk total mismatch: expected $expectedTotalChunks, got $totalChunks")
                }
            } else {
                // Single chunk or non-chunked response
                val vpToken = String(chunkData, Charsets.UTF_8)
                Log.d(TAG, "Received single VP Token (${chunkData.size} bytes): $vpToken")
                _receivedVPToken.value = vpToken
            }
        } else {
            // Legacy non-chunked response
            val vpToken = String(data, Charsets.UTF_8)
            Log.d(TAG, "Received legacy VP Token (${data.size} bytes): $vpToken")
            _receivedVPToken.value = vpToken
        }
    }
    
    private fun reassembleChunks(): ByteArray? {
        return try {
            val totalSize = chunkBuffer.values.sumOf { it.size }
            val result = ByteArray(totalSize)
            var offset = 0
            
            for (i in 0 until expectedTotalChunks) {
                val chunk = chunkBuffer[i]
                if (chunk == null) {
                    Log.e(TAG, "Missing chunk $i")
                    return null
                }
                System.arraycopy(chunk, 0, result, offset, chunk.size)
                offset += chunk.size
            }
            
            Log.d(TAG, "Successfully reassembled ${result.size} bytes from $expectedTotalChunks chunks")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reassemble chunks", e)
            null
        }
    }
    
    fun sendVerificationRequest(presentationDefinition: PresentationDefinition) {
        Log.d(TAG, "Attempting to send verification request")
        
        val gatt = bluetoothGatt
        if (gatt == null) {
            Log.e(TAG, "BluetoothGatt is null, cannot send request")
            _clientState.value = BLEClientState.Error("Connection lost")
            return
        }
        
        val service = gatt.getService(WALLET_SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "Wallet service not found")
            _clientState.value = BLEClientState.Error("Wallet service not available")
            return
        }
        
        val characteristic = service.getCharacteristic(WALLET_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.e(TAG, "Wallet characteristic not found")
            _clientState.value = BLEClientState.Error("Wallet characteristic not available")
            return
        }
        
        try {
            val request = VerificationRequest(
                verifierDid = "did:web:verifier.example.com",
                verifierName = "EUDI Demo Verifier",
                presentationDefinition = presentationDefinition
            )
            
            val requestJson = gson.toJson(request)
            val requestBytes = requestJson.toByteArray(Charsets.UTF_8)
            val maxPayloadSize = currentMtu - HEADER_SIZE
            
            Log.d(TAG, "Sending verification request (${requestBytes.size} bytes, MTU: $currentMtu, Max payload: $maxPayloadSize): $requestJson")
            
            if (requestBytes.size > maxPayloadSize) {
                Log.w(TAG, "Request size (${requestBytes.size}) exceeds MTU limit ($maxPayloadSize), truncating")
                // Create a minimal request that fits within MTU
                val shortRequest = VerificationRequest(
                    verifierDid = "did:web:v.com",
                    verifierName = "EUDI Verifier",
                    presentationDefinition = PresentationDefinition(
                        name = "ID Check",
                        purpose = "Verify ID",
                        input_descriptors = listOf(
                            InputDescriptor(
                                id = "id",
                                name = "ID",
                                purpose = "ID",
                                constraints = Constraints(
                                    fields = listOf(
                                        Field(path = listOf("$.credentialSubject.givenName"))
                                    )
                                )
                            )
                        )
                    )
                )
                val shortJson = gson.toJson(shortRequest)
                val shortBytes = shortJson.toByteArray(Charsets.UTF_8)
                
                if (shortBytes.size > maxPayloadSize) {
                    Log.e(TAG, "Even shortened request (${shortBytes.size} bytes) exceeds MTU limit ($maxPayloadSize)")
                    _clientState.value = BLEClientState.Error("Request too large for BLE MTU")
                    return
                }
                
                Log.d(TAG, "Using shortened request (${shortBytes.size} bytes): $shortJson")
                characteristic.value = shortBytes
            } else {
                characteristic.value = requestBytes
            }
            
            // Set write timeout
            writeTimeoutRunnable = Runnable {
                Log.e(TAG, "Write operation timeout")
                _clientState.value = BLEClientState.Error("Write timeout")
            }
            handler.postDelayed(writeTimeoutRunnable!!, WRITE_TIMEOUT_MS)
            
            val success = gatt.writeCharacteristic(characteristic)
            
            if (success) {
                Log.d(TAG, "Write characteristic initiated successfully")
            } else {
                Log.e(TAG, "Failed to initiate characteristic write")
                clearTimeouts()
                _clientState.value = BLEClientState.Error("Failed to send request")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while sending verification request", e)
            _clientState.value = BLEClientState.Error("Failed to send request: ${e.message}")
        }
    }
    
    private fun clearTimeouts() {
        connectionTimeoutRunnable?.let { handler.removeCallbacks(it) }
        serviceDiscoveryTimeoutRunnable?.let { handler.removeCallbacks(it) }
        writeTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectionTimeoutRunnable = null
        serviceDiscoveryTimeoutRunnable = null
        writeTimeoutRunnable = null
    }
    
    fun disconnect() {
        clearTimeouts()
        scanner?.stopScan(scanCallback)
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _clientState.value = BLEClientState.Idle
    }
}