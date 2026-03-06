package dev.kuwa.mlcproxy.session

import dev.kuwa.mlcproxy.bridge.MappingContext
import dev.kuwa.mlcproxy.config.Config
import dev.kuwa.mlcproxy.protocol.java.JavaPacket
import io.netty.channel.Channel
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

class ProxySession(
    val frontendChannel: Channel,
    config: Config
) {
    private val logger = LoggerFactory.getLogger(ProxySession::class.java)

    val id: String = UUID.randomUUID().toString()
    val state = AtomicReference(SessionState.CONNECTED)
    val mappingContext = MappingContext(config.protocol)

    @Volatile
    var backendChannel: Channel? = null
        private set

    private val pendingToBackend = ConcurrentLinkedQueue<JavaPacket>()

    fun attachBackend(channel: Channel) {
        backendChannel = channel
        flushPendingToBackend()
    }

    fun writeToBackend(packet: JavaPacket) {
        val backend = backendChannel
        if (backend == null || !backend.isActive) {
            pendingToBackend.offer(packet)
            return
        }
        backend.writeAndFlush(packet)
    }

    fun writeToFrontend(msg: Any) {
        if (frontendChannel.isActive) {
            frontendChannel.writeAndFlush(msg)
        }
    }

    fun close(reason: String) {
        if (state.getAndSet(SessionState.CLOSED) == SessionState.CLOSED) {
            return
        }

        logger.info("Closing session id={} reason={}", id, reason)

        if (frontendChannel.isActive) {
            frontendChannel.close()
        }
        val backend = backendChannel
        if (backend != null && backend.isActive) {
            backend.close()
        }
        pendingToBackend.clear()
    }

    private fun flushPendingToBackend() {
        val backend = backendChannel ?: return
        if (!backend.isActive) return

        while (true) {
            val packet = pendingToBackend.poll() ?: break
            backend.write(packet)
        }
        backend.flush()
    }
}
