package dev.kuwa.mlcproxy.config

/**
 * アプリケーション設定のルート
 *
 * 現在は `proxy` 設定を保持
 *
 * 例
 * ```kotlin
 * val config = Config.default()
 * println(config.proxy.targetHost)
 * ```
 */
data class Config(
    val proxy: ProxyConfig = ProxyConfig.default(),
    val protocol: ProtocolConfig = ProtocolConfig.default(),
    val discovery: DiscoveryConfig = DiscoveryConfig.default()
) {
    companion object {
        /**
         * デフォルト設定を返す
         */
        fun default(): Config = Config()
    }
}
