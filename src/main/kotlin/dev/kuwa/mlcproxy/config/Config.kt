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
    val proxy: ProxyConfig = ProxyConfig.default()
) {
    companion object {
        /**
         * デフォルト設定を返す
         *
         * 例
         * ```kotlin
         * val config = Config.default()
         * ```
         */
        fun default(): Config = Config()
    }
}
