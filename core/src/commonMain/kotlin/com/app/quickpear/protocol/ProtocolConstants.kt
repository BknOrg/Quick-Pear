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
    const val PROTOCOL_VERSION = 2
    const val MAX_HANDSHAKE_PAYLOAD = 8192L
    const val MAX_METADATA_REQ_PAYLOAD = 1_048_576L // 1 MB
    const val MAX_CHUNK_DATA_PAYLOAD = 4_194_304L // 4 MB (default chunk is 2 MB)

    const val MSG_SECURE: Byte = 0x20
    const val MSG_HANDSHAKE_COMMIT: Byte = 0x23
    const val MSG_HANDSHAKE_REVEAL: Byte = 0x24
    const val MSG_CANCEL: Byte = 0x22
    const val MSG_CONTROL: Byte = 0x21
    const val IO_TIMEOUT_MS = 30_000L
    const val IDLE_TIMEOUT_MS = 600_000L
    const val MAX_FILES = 1000
    const val MAX_FRAME_PAYLOAD = MAX_CHUNK_DATA_PAYLOAD + 21L

    // Metadata Response Codes
    const val METADATA_RESP_ACCEPTED: Byte = 0x00
    const val METADATA_RESP_REJECTED: Byte = 0x01
    const val METADATA_RESP_INSUFFICIENT_STORAGE: Byte = 0x02

    // Chunk Type Marker
    const val CHUNK_MARKER_PAYLOAD: Byte = 0xAA.toByte()
}
