package com.app.quickpear.network

import java.net.Inet4Address
import java.net.NetworkInterface

actual object LocalIpResolver {
    actual fun getLocalIpAddress(): String {
        val all = getAllLocalIpAddresses()
        return all.firstOrNull() ?: "127.0.0.1"
    }

    actual fun getAllLocalIpAddresses(): List<String> {
        val priorityList = mutableListOf<String>()
        val fallbackList = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp || iface.isVirtual) continue
                val name = (iface.name + " " + (iface.displayName ?: "")).lowercase()
                val isLowPriority = name.contains("bluetooth") || name.contains("dummy") ||
                        name.contains("rmnet") || name.contains("tun") ||
                        name.contains("p2p")

                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && !addr.isLinkLocalAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("169.254.") || host.startsWith("127.")) continue
                        if (isLowPriority) {
                            if (!fallbackList.contains(host)) fallbackList.add(host)
                        } else {
                            if (!priorityList.contains(host)) priorityList.add(host)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        val result = mutableListOf<String>()
        result.addAll(priorityList)
        for (item in fallbackList) {
            if (!result.contains(item)) result.add(item)
        }
        return result
    }

    actual fun getBroadcastAddresses(): List<String> {
        val list = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return list
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (ia in iface.interfaceAddresses) {
                    val broadcast = ia.broadcast
                    if (broadcast != null && broadcast is Inet4Address) {
                        val host = broadcast.hostAddress
                        if (host != null && host.isNotEmpty() && !host.startsWith("169.254.") && !list.contains(host)) {
                            list.add(host)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }
}
