package com.app.quickpear.network

expect object LocalIpResolver {
    fun getLocalIpAddress(): String
    fun getAllLocalIpAddresses(): List<String>
    fun getBroadcastAddresses(): List<String>
}
