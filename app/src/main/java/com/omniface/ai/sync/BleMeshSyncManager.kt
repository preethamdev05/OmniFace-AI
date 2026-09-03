package com.omniface.ai.sync

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BleMeshState(
    val isAdvertising: Boolean = false,
    val isScanning: Boolean = false,
    val connectedPeerCount: Int = 0,
    val lastSyncedPayloadHash: String? = null,
    val lastError: String? = null
)

object BleMeshSyncManager {

    private val _meshState = MutableStateFlow(BleMeshState())
    val meshState: StateFlow<BleMeshState> = _meshState.asStateFlow()

    /**
     * Toggles BLE Mesh state with honest capability detection.
     * Real GATT advertising/scanning requires BLUETOOTH_CONNECT + BLUETOOTH_SCAN
     * permissions and Android 12+ APIs. Until the full mesh stack is implemented,
     * this surfaces an explicit "Not yet implemented" state instead of faking peer counts.
     */
    fun toggleBleMeshSync(context: Context) {
        val bluetoothManager = try {
            context.applicationContext.getSystemService(BluetoothManager::class.java)
        } catch (e: Exception) {
            Log.w("BleMesh", "BluetoothManager unavailable: ${e.message}")
            _meshState.update { it.copy(lastError = "Bluetooth service unavailable") }
            return
        }
        val adapter = bluetoothManager?.adapter

        if (adapter == null || !adapter.isEnabled) {
            _meshState.update { it.copy(lastError = "Bluetooth is disabled — enable Bluetooth to use mesh sync") }
            Log.w("BleMesh", "Bluetooth disabled, mesh not started")
            return
        }

        val currentlyActive = _meshState.value.isAdvertising || _meshState.value.isScanning
        if (currentlyActive) {
            _meshState.update {
                it.copy(
                    isAdvertising = false,
                    isScanning = false,
                    connectedPeerCount = 0,
                    lastError = null
                )
            }
        } else {
            // Full GATT mesh not yet implemented — surface honest state
            _meshState.update {
                it.copy(
                    isAdvertising = false,
                    isScanning = false,
                    connectedPeerCount = 0,
                    lastError = "BLE mesh sync is not yet fully implemented — infrastructure ready, GATT transport pending"
                )
            }
            Log.i("BleMesh", "BLE mesh requested but GATT transport not yet implemented")
        }
    }

    fun clearError() {
        _meshState.update { it.copy(lastError = null) }
    }
}
