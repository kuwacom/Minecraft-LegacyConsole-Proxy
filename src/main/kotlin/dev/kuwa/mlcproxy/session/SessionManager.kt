package dev.kuwa.mlcproxy.session

import dev.kuwa.mlcproxy.config.Config
import io.netty.channel.Channel
import io.netty.channel.ChannelId
import java.util.concurrent.ConcurrentHashMap

class SessionManager {
    private val sessionsByFrontend = ConcurrentHashMap<ChannelId, ProxySession>()
    private val sessionsByBackend = ConcurrentHashMap<ChannelId, ProxySession>()

    fun create(frontend: Channel, config: Config): ProxySession {
        val session = ProxySession(frontend, config)
        sessionsByFrontend[frontend.id()] = session
        return session
    }

    fun attachBackend(session: ProxySession, backend: Channel) {
        session.attachBackend(backend)
        sessionsByBackend[backend.id()] = session
    }

    fun getByFrontend(frontend: Channel): ProxySession? = sessionsByFrontend[frontend.id()]

    fun getByBackend(backend: Channel): ProxySession? = sessionsByBackend[backend.id()]

    fun remove(session: ProxySession) {
        sessionsByFrontend.remove(session.frontendChannel.id())
        val backend = session.backendChannel
        if (backend != null) {
            sessionsByBackend.remove(backend.id())
        }
    }

    fun close(session: ProxySession, reason: String) {
        session.close(reason)
        remove(session)
    }

    fun closeByFrontend(frontend: Channel, reason: String) {
        val session = sessionsByFrontend.remove(frontend.id()) ?: return
        val backend = session.backendChannel
        if (backend != null) {
            sessionsByBackend.remove(backend.id())
        }
        session.close(reason)
    }

    fun closeByBackend(backend: Channel, reason: String) {
        val session = sessionsByBackend.remove(backend.id()) ?: return
        sessionsByFrontend.remove(session.frontendChannel.id())
        session.close(reason)
    }

    fun closeAll(reason: String) {
        sessionsByFrontend.values.forEach { it.close(reason) }
        sessionsByFrontend.clear()
        sessionsByBackend.clear()
    }

    fun activeSessionCount(): Int = sessionsByFrontend.size
}
