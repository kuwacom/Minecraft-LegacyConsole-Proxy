package dev.kuwa.mlcproxy

import dev.kuwa.mlcproxy.bootstrap.ProxyServer
import dev.kuwa.mlcproxy.config.ConfigManager
import org.slf4j.LoggerFactory

/**
 * アプリケーション起動エントリポイント
 *
 * `ConfigManager` を初期化して proxy 設定を読み込み
 * 現在は起動確認ログを出力する
 */
object MLCProxy {
    private val logger = LoggerFactory.getLogger(MLCProxy::class.java)

    /**
     * アプリケーションを起動する
     *
     * `config/config.toml` がなければデフォルト値で生成される
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val appConfig = ConfigManager.init()
        val config = appConfig.proxy
        val protocol = appConfig.protocol
        val discovery = appConfig.discovery

        logger.info(
            "Starting Minecraft proxy: {}:{} -> {}:{}",
            config.listenHost,
            config.listenPort,
            config.targetHost,
            config.targetPort
        )
        logger.info(
            "Protocol config mlcTransport={} mlcNet={} mlcGame={} javaProtocol={} javaHandshake={}:{}",
            protocol.mlcTransportMode,
            protocol.mlcNetVersion,
            protocol.mlcGameProtocolVersion,
            protocol.javaProtocolVersion,
            protocol.javaHandshakeHost,
            protocol.javaHandshakePort
        )
        logger.info(
            "Discovery config enabled={} udpPort={} interval={}ms respondToQueries={} hostName={}",
            discovery.enabled,
            discovery.port,
            discovery.advertiseIntervalMs,
            discovery.respondToQueries,
            discovery.hostName
        )

        val proxyServer = ProxyServer(appConfig)
        val channel = proxyServer.start()

        Runtime.getRuntime().addShutdownHook(
            Thread {
                proxyServer.stop()
            }
        )

        channel.closeFuture().syncUninterruptibly()
    }
}
