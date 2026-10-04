package com.app.quickpear.protocol

/**
 * Represents the 13-byte standard message header.
 */
data class FrameHeader(
    val messageType: Byte,
    val payloadLength: Long
)
