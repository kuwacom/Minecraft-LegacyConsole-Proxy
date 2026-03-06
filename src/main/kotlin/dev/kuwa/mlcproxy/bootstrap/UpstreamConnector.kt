package dev.kuwa.mlcproxy.bootstrap

import dev.kuwa.mlcproxy.bridge.JavaToMlcMapper
import dev.kuwa.mlcproxy.config.Config
import dev.kuwa.mlcproxy.netty.BackendChannelInitializer
import dev.kuwa.mlcproxy.session.ProxySession
import dev.kuwa.mlcproxy.session.SessionManager
import io.netty.channel.ChannelFuture
import org.slf4j.LoggerFactory

class UpstreamConnector(
    private val config: Config,
    private val sessionManager: SessionManager,
    private val javaToMlcMapper: JavaToMlcMapper
) {
    private val logger = LoggerFactory.getLogger(UpstreamConnector::class.java)

    fun connect(session: ProxySession): ChannelFuture {
        val frontend = session.frontendChannel
        val bootstrap = ChannelFactory.client(
            frontend.eventLoop(),
            BackendChannelInitializer(session, sessionManager, javaToMlcMapper)
        )

        return bootstrap.connect(config.proxy.targetHost, config.proxy.targetPort).addListener { future ->
            if (future.isSuccess) {
                val backend = (future as ChannelFuture).channel()
                sessionManager.attachBackend(session, backend)
                logger.info(
                    "Backend connected sessionId={} local={} remote={}",
                    session.id,
                    backend.localAddress(),
                    backend.remoteAddress()
                )
            }
        }
    }
}
