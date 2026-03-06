package dev.kuwa.mlcproxy

import dev.kuwa.mlcproxy.config.Config
import dev.kuwa.mlcproxy.config.ConfigManager
import dev.kuwa.mlcproxy.config.ProxyConfig
import org.slf4j.LoggerFactory

object MLCProxy {
    private val logger = LoggerFactory.getLogger(MLCProxy::class.java)
    @JvmStatic
    fun main(args: Array<String>) {
        val config: Config = ConfigManager.init()
        val proxy: ProxyConfig = config.proxy

        logger.info("MLCProxy started")
        logger.info("listen={}:{}", proxy.listenHost, proxy.listenPort)
        logger.info("target={}:{}", proxy.targetHost, proxy.targetPort)
    }
}
