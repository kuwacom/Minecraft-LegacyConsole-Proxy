package dev.kuwa.mlcproxy.config

/**
 * Proxy 接続設定
 *
 * - `listen*` は待受先
 * - `target*` は転送先サーバー
 *
 * 例
 * ```kotlin
 * val proxy = ProxyConfig.default()
 * println("${proxy.listenHost}:${proxy.listenPort}")
 * ```
 */
data class ProxyConfig(
    val listenHost: String,
    val listenPort: Int,
    val targetHost: String,
    val targetPort: Int
) {
    companion object {
        /**
         * Proxy 用のデフォルト値を返す
         *
         * 例
         * ```kotlin
         * val proxy = ProxyConfig.default()
         * ```
         */
        fun default(): ProxyConfig {
            return ProxyConfig(
                listenHost = "0.0.0.0",
                listenPort = 25566,
                targetHost = "127.0.0.1",
                targetPort = 25565
            )
        }
    }
}
