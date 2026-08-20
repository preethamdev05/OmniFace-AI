package com.omniface.ai.hardware

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThermalState(val maxFps: Int, val description: String) {
    NOMINAL(30, "Nominal Temperature (<38°C) - Full GPU/NPU 30 FPS"),
    WARM(20, "Elevated Temperature (38-42°C) - Throttled 20 FPS"),
    CRITICAL(10, "Critical Thermal Level (>42°C) - CPU XNNPACK 10 FPS")
}

object ThermalGovernor {

    private val _thermalState = MutableStateFlow(ThermalState.NOMINAL)
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    private val _currentTemperature = MutableStateFlow(32.0f)
    val currentTemperature: StateFlow<Float> = _currentTemperature.asStateFlow()

    fun updateTemperature(context: Context) {
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320) ?: 320
            val tempCelsius = tempTenths / 10.0f
            _currentTemperature.value = tempCelsius

            val isSystemThrottled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val thermalStatus = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
            } else {
                false
            }

            _thermalState.value = when {
                tempCelsius > 42.0f || isSystemThrottled -> ThermalState.CRITICAL
                tempCelsius > 38.0f -> ThermalState.WARM
                else -> ThermalState.NOMINAL
            }
        } catch (e: Exception) {
            _currentTemperature.value = 32.0f
            _thermalState.value = ThermalState.NOMINAL
        }
    }
}
