package com.app.quickpear.network

import com.app.quickpear.domain.ConnectionType
import com.app.quickpear.domain.PeerDevice

interface P2pLink {
    val connectionType: ConnectionType
    val localIp: String
    val remoteIp: String
    val port: Int
    suspend fun release()
}

data class LocalHotspotCredentials(
    val ssid: String,
    val passphrase: String,
    val hostIp: String = "192.168.43.1",
    val port: Int = 8888
)

interface P2pLinkNegotiator {
    fun isSupported(): Boolean
    suspend fun establishHostLink(): P2pLink
    suspend fun connectClientLink(target: PeerDevice): P2pLink

    fun isHotspotSupported(): Boolean = false
    suspend fun startLocalHotspot(): LocalHotspotCredentials? = null
    suspend fun stopLocalHotspot() {}
    suspend fun connectToHotspot(ssid: String, pass: String, timeoutMillis: Long = 6000L): Boolean = false
    suspend fun disconnectHotspot() {}
}

object NoOpP2pLinkNegotiator : P2pLinkNegotiator {
    override fun isSupported(): Boolean = false
    override suspend fun establishHostLink(): Nothing = throw UnsupportedOperationException("P2P Link not supported on this platform")
    override suspend fun connectClientLink(target: PeerDevice): Nothing = throw UnsupportedOperationException("P2P Link not supported on this platform")
}
