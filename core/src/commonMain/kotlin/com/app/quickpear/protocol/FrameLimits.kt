package com.app.quickpear.protocol

object FrameLimits {
    fun validateWire(header: FrameHeader) {
        val max = if (header.messageType == ProtocolConstants.MSG_SECURE) ProtocolConstants.MAX_FRAME_PAYLOAD + 37 else limit(header.messageType)
        require(header.payloadLength in 0..max) { "Invalid frame size" }
    }
    fun validate(header: FrameHeader, secure: Boolean) {
        validateWire(header)
        if (secure) require(header.messageType == ProtocolConstants.MSG_SECURE) { "Encrypted frame required" }
        else require(header.messageType in setOf(ProtocolConstants.MSG_HANDSHAKE_REQ, ProtocolConstants.MSG_HANDSHAKE_RESP, ProtocolConstants.MSG_HANDSHAKE_AUTH, ProtocolConstants.MSG_HANDSHAKE_COMMIT, ProtocolConstants.MSG_HANDSHAKE_REVEAL)) { "Protocol v2 handshake required" }
    }
    fun validatePlain(header: FrameHeader) {
        require(header.payloadLength in 0..limit(header.messageType)) { "Invalid payload size" }
        val exact = when (header.messageType) {
            ProtocolConstants.MSG_METADATA_RESP, ProtocolConstants.MSG_CHUNK_ACK -> 9L
            ProtocolConstants.MSG_TRANSFER_COMPLETE -> 36L
            ProtocolConstants.MSG_TRANSFER_COMPLETE_ACK -> 5L
            else -> null
        }
        require(exact == null || exact == header.payloadLength) { "Invalid fixed payload size" }
    }
    private fun limit(type: Byte): Long = when (type) {
        ProtocolConstants.MSG_CANCEL -> 0L
        ProtocolConstants.MSG_HANDSHAKE_REQ, ProtocolConstants.MSG_HANDSHAKE_RESP, ProtocolConstants.MSG_HANDSHAKE_AUTH, ProtocolConstants.MSG_HANDSHAKE_COMMIT, ProtocolConstants.MSG_HANDSHAKE_REVEAL -> ProtocolConstants.MAX_HANDSHAKE_PAYLOAD
        ProtocolConstants.MSG_METADATA_REQ -> ProtocolConstants.MAX_METADATA_REQ_PAYLOAD
        ProtocolConstants.MSG_CHUNK_DATA -> ProtocolConstants.MAX_FRAME_PAYLOAD
        ProtocolConstants.MSG_CONTROL -> 8192L
        ProtocolConstants.MSG_TEXT_SHARE -> 65536L
        ProtocolConstants.MSG_METADATA_RESP, ProtocolConstants.MSG_CHUNK_ACK -> 9L
        ProtocolConstants.MSG_TRANSFER_COMPLETE -> 36L
        ProtocolConstants.MSG_TRANSFER_COMPLETE_ACK -> 5L
        ProtocolConstants.MSG_PAIR_CONFIRM, ProtocolConstants.MSG_PROBE_ACK, ProtocolConstants.MSG_TEXT_SHARE_ACK -> 1L
        else -> throw IllegalArgumentException("Unknown frame type")
    }
}
