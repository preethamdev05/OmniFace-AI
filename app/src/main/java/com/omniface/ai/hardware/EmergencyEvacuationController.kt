package com.omniface.ai.hardware

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object EmergencyEvacuationController {

    private val _isEvacuationActive = MutableStateFlow(false)
    val isEvacuationActive: StateFlow<Boolean> = _isEvacuationActive.asStateFlow()

    private val _evacuationReason = MutableStateFlow("")
    val evacuationReason: StateFlow<String> = _evacuationReason.asStateFlow()

    fun triggerEmergencyEvacuation(reason: String = "CAMPUS FIRE / LIFE-SAFETY ALARM") {
        _evacuationReason.value = reason
        _isEvacuationActive.value = true
    }

    fun resetEvacuation() {
        _isEvacuationActive.value = false
        _evacuationReason.value = ""
    }
}
