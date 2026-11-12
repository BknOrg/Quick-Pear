package com.app.quickpear.signaling

import com.app.quickpear.domain.*
import kotlinx.coroutines.flow.*

/** Receives only data from an authenticated and encrypted PROBE session. */
internal class CloudPresenceRegistry {
    private val peers = MutableStateFlow<Map<String, PeerDevice>>(emptyMap())
    private val state = MutableStateFlow<List<PeerDevice>>(emptyList())
    val devices: StateFlow<List<PeerDevice>> = state.asStateFlow()
    @Synchronized fun seen(id: String, name: String, data: CloudControl) {
        require(data.action == "PRESENCE" && data.port in 1..65535 && data.ip.length <= 64)
        peers.value = peers.value + (id to PeerDevice(id, name, data.ip, data.port, data.deviceType,
            ConnectionType.CLOUD_P2P, lastSeen = System.currentTimeMillis()))
        state.value = peers.value.values.toList()
    }
    @Synchronized fun expire() {
        peers.value = peers.value.filterValues { System.currentTimeMillis() - it.lastSeen < 60_000 }
        state.value = peers.value.values.toList()
    }
    @Synchronized fun clear() { peers.value = emptyMap(); state.value = emptyList() }
}
