package com.app.quickpear.network

import kotlinx.coroutines.flow.StateFlow

/** MQTT boundary; tests supply an in-process broker rather than a public service. */
interface MessageTransport {
    val isConnected: StateFlow<Boolean>
    fun start()
    fun stop()
    suspend fun subscribe(topic: String, onMessage: (String, ByteArray) -> Unit)
    suspend fun unsubscribe(topic: String)
    suspend fun publish(topic: String, message: ByteArray)
}
