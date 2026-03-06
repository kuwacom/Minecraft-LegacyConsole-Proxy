package dev.kuwa.mlcproxy.bootstrap

import dev.kuwa.mlcproxy.bridge.JavaToMlcMapper
import dev.kuwa.mlcproxy.bridge.MlcToJavaMapper
import dev.kuwa.mlcproxy.config.Config
import dev.kuwa.mlcproxy.discovery.LanDiscoveryService
import dev.kuwa.mlcproxy.netty.FrontendChannelInitializer
import dev.kuwa.mlcproxy.session.SessionManager
import io.netty.channel.Channel
import io.netty.channel.nio.NioEventLoopGroup
import org.slf4j.LoggerFactory

class ProxyServer(
    private val config: Config
) {
    private val logger = LoggerFactory.getLogger(ProxyServer::class.java)
    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()
    private val sessionManager = SessionManager()

    private var serverChannel: Channel? = null
    private var lanDiscoveryService: LanDiscoveryService? = null

    fun start(): Channel {
        val mlcToJavaMapper = MlcToJavaMapper(config)
        val javaToMlcMapper = JavaToMlcMapper()
        val upstreamConnector = UpstreamConnector(config, sessionManager, javaToMlcMapper)

        val serverBootstrap = ChannelFactory.server(
            bossGroup,
            workerGroup,
            FrontendChannelInitializer(
                config = config,
                sessionManager = sessionManager,
                upstreamConnector = upstreamConnector,
                mlcToJavaMapper = mlcToJavaMapper
            )
        )

        val channel = serverBootstrap.bind(config.proxy.listenHost, config.proxy.listenPort).sync().channel()
        serverChannel = channel
        lanDiscoveryService = LanDiscoveryService(config, sessionManager).also { it.start() }

        logger.info(
            "Proxy started {}:{} -> {}:{}",
            config.proxy.listenHost,
            config.proxy.listenPort,
            config.proxy.targetHost,
            config.proxy.targetPort
        )
        return channel
    }

    fun stop() {
        lanDiscoveryService?.stop()
        lanDiscoveryService = null
        sessionManager.closeAll("proxy shutdown")
        serverChannel?.close()?.syncUninterruptibly()
        workerGroup.shutdownGracefully().syncUninterruptibly()
        bossGroup.shutdownGracefully().syncUninterruptibly()
        logger.info("Proxy stopped")
    }
}
