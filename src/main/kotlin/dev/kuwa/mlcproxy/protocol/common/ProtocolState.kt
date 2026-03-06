package dev.kuwa.mlcproxy.protocol.common

enum class ProtocolState {
    HANDSHAKE,
    LOGIN,
    PLAY,
    CLOSED
}
