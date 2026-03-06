package dev.kuwa.mlcproxy.protocol.common

class PacketRegistry<T> {
    private val handlers = mutableMapOf<Int, T>()

    fun register(packetId: Int, handler: T) {
        handlers[packetId] = handler
    }

    fun resolve(packetId: Int): T? = handlers[packetId]
}
