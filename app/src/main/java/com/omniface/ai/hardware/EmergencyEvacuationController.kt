package com.omniface.ai.hardware

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object EmergencyEvacuationController {

    private val _isEvacuationActive = MutableStateFlow(false)
    val isEvacuationActive: StateFlow<Boolean> = _isEvacuationActive.asStateFlow()

    private val _evacuationReason = MutableStateFlow("")
    val evacuationReason: StateFlow<String> = _evacuationReason.asStateFlow()

    fun triggerEmergencyEvacuation(reason: String = "CAMPUS FIRE / LIFE-SAFETY ALARM", context: Context? = null) {
        _evacuationReason.value = reason
        _isEvacuationActive.value = true
        context?.let { TurnstileRelayController.sendUsbRelayCommand(it, true) }
    }

    fun resetEvacuation(context: Context? = null) {
        _isEvacuationActive.value = false
        _evacuationReason.value = ""
        context?.let { TurnstileRelayController.sendUsbRelayCommand(it, false) }
    }
}
