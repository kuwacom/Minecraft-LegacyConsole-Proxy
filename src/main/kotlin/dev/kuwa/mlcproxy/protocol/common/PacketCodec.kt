package dev.kuwa.mlcproxy.protocol.common

interface PacketCodec<I, O> {
    fun encode(input: I): O
    fun decode(input: O): I
}
