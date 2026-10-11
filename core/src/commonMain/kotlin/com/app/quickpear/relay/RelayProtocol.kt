package com.app.quickpear.relay

object RelayProtocol {
    /** 4-byte magic header "QPRL" (QuickPear Relay Link) */
    const val MAGIC: Int = 0x5150524C

    const val MSG_REGISTER: Byte = 0x01
    const val MSG_REGISTER_ACK: Byte = 0x02
    const val MSG_DATA: Byte = 0x03
    const val MSG_PING: Byte = 0x04
    const val MSG_PONG: Byte = 0x05

    const val STATUS_OK: Byte = 0x00
    const val STATUS_ERROR: Byte = 0x01
    const val STATUS_PEER_CONNECTED: Byte = 0x02

    /** Token length: 16 bytes (128-bit session token) */
    const val TOKEN_LENGTH: Int = 16

    const val DEFAULT_RELAY_PORT: Int = 53320
}
