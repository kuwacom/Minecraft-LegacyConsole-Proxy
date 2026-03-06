package dev.kuwa.mlcproxy.protocol.java

data class JavaPacket(
    val id: Int,
    val payload: ByteArray
)
