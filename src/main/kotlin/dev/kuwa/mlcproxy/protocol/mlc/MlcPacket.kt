package dev.kuwa.mlcproxy.protocol.mlc

data class MlcPacket(
    val id: Int,
    val payload: ByteArray
)
