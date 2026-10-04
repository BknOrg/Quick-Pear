package com.app.quickpear.protocol

object ProtocolConstants {
    // Magic Bytes: ASCII "SHRE" -> 0x53, 0x48, 0x52, 0x45
    val MAGIC_BYTES = byteArrayOf(0x53.toByte(), 0x48.toByte(), 0x52.toByte(), 0x45.toByte())
    const val HEADER_SIZE = 13

    // Message Types
    const val MSG_HANDSHAKE_REQ: Byte = 0x01
    const val MSG_HANDSHAKE_RESP: Byte = 0x02
    const val MSG_METADATA_REQ: Byte = 0x03
    const val MSG_METADATA_RESP: Byte = 0x04
    const val MSG_CHUNK_DATA: Byte = 0x05
    const val MSG_CHUNK_ACK: Byte = 0x06
    const val MSG_TRANSFER_COMPLETE: Byte = 0x07
    const val MSG_TRANSFER_COMPLETE_ACK: Byte = 0x08
    const val MSG_HANDSHAKE_AUTH: Byte = 0x09
    const val MSG_PAIR_CONFIRM: Byte = 0x0A
    const val MSG_PROBE_ACK: Byte = 0x0B
    const val MSG_TEXT_SHARE: Byte = 0x0C
    const val MSG_TEXT_SHARE_ACK: Byte = 0x0D

    // Identity handshake
    const val PROTOCOL_VERSION = 1
    const val MAX_HANDSHAKE_PAYLOAD = 8192L

    // Metadata Response Codes
    const val METADATA_RESP_ACCEPTED: Byte = 0x00
    const val METADATA_RESP_REJECTED: Byte = 0x01
    const val METADATA_RESP_INSUFFICIENT_STORAGE: Byte = 0x02

    // Chunk Type Marker
    const val CHUNK_MARKER_PAYLOAD: Byte = 0xAA.toByte()
}
