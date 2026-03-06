package dev.kuwa.mlcproxy.bridge

interface PacketMapper<I, O> {
    fun map(packet: I, context: MappingContext): List<O>
}
