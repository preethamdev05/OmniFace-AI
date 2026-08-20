package com.omniface.ai.hardware

import android.content.Context
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import androidx.compose.runtime.Immutable

@Immutable
data class KioskNode(
    val id: String,
    val name: String,
    val ipAddress: String,
    val batteryPct: Int,
    val activeFps: Int,
    val isOnline: Boolean,
    val lastHeartbeat: Long = System.currentTimeMillis()
)

object FleetTopologyManager {

    private val _kioskNodes = MutableStateFlow<List<KioskNode>>(emptyList())
    val kioskNodes: StateFlow<List<KioskNode>> = _kioskNodes.asStateFlow()

    fun initializeLocalNode(context: Context, activeFps: Int = 30) {
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100

        val localNode = KioskNode(
            id = "kiosk_local_master",
            name = "Local Kiosk Node (Active Terminal)",
            ipAddress = ip,
            batteryPct = battery,
            activeFps = activeFps,
            isOnline = true,
            lastHeartbeat = System.currentTimeMillis()
        )

        _kioskNodes.value = listOf(localNode)
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun registerNode(node: KioskNode) {
        val current = _kioskNodes.value.toMutableList()
        val index = current.indexOfFirst { it.id == node.id }
        if (index >= 0) {
            current[index] = node
        } else {
            current.add(node)
        }
        _kioskNodes.value = current
    }

    fun updateNodeHeartbeat(id: String, fps: Int, batteryPct: Int) {
        _kioskNodes.value = _kioskNodes.value.map { node ->
            if (node.id == id) {
                node.copy(
                    activeFps = fps,
                    batteryPct = batteryPct,
                    isOnline = true,
                    lastHeartbeat = System.currentTimeMillis()
                )
            } else node
        }
    }
}
