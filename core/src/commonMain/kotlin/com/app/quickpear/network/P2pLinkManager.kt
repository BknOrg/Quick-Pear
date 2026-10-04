package com.app.quickpear.network

import com.app.quickpear.domain.ConnectionType
import com.app.quickpear.domain.PeerDevice

class P2pLinkManager {

    /**
     * Resolves appropriate IP and port for target peer based on connection type.
     */
    fun resolveTargetAddress(peer: PeerDevice): Pair<String, Int> {
        return when (peer.connectionType) {
            ConnectionType.WIFI_DIRECT -> {
                // Wi-Fi Direct Group Owner default IP address
                val p2pIp = if (peer.ipAddress.isEmpty()) "192.168.49.1" else peer.ipAddress
                Pair(p2pIp, peer.port)
            }
            ConnectionType.LOCAL_HOTSPOT -> {
                val hotspotIp = if (peer.ipAddress.isEmpty()) "192.168.43.1" else peer.ipAddress
                Pair(hotspotIp, peer.port)
            }
            else -> Pair(peer.ipAddress, peer.port)
        }
    }
}
