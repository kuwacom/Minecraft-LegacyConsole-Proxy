package dev.kuwa.mlcproxy.config

import org.tomlj.Toml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.bufferedWriter
import kotlin.io.path.exists

/**
 * `config.toml` の読み書きと参照を担当する設定マネージャ
 *
 * 現在は proxy 設定を扱う
 *
 * 例
 * ```kotlin
 * val config = ConfigManager.init()
 * println(config.proxy.targetHost)
 * ```
 */
object ConfigManager {
    private val lock = Any()
    private val defaultPath: Path = Paths.get("./", "config.toml")

    private var configPath: Path = defaultPath

    @Volatile
    lateinit var config: Config
        private set

    /**
     * proxy 設定を直接取得するショートカット
     *
     * 例
     * ```kotlin
     * val proxy = ConfigManager.proxy
     * ```
     */
    val proxy: ProxyConfig
        get() = current().proxy

    /**
     * 設定を初期化する
     *
     * - ファイルが存在すれば読み込む
     * - 存在しなければデフォルト設定を作成して保存する
     *
     * 例
     * ```kotlin
     * val config = ConfigManager.init()
     * ```
     */
    fun init(path: Path = defaultPath): Config {
        synchronized(lock) {
            configPath = path
            ensureParentDirectory(configPath)

            config = if (configPath.exists()) {
                read(configPath)
            } else {
                Config.default()
            }

            save()
            return config
        }
    }

    /**
     * 設定ファイルを再読込する
     *
     * 例
     * ```kotlin
     * val latest = ConfigManager.reload()
     * ```
     */
    fun reload(): Config {
        synchronized(lock) {
            if (!::config.isInitialized) {
                return init(configPath)
            }
            config = read(configPath)
            return config
        }
    }

    /**
     * メモリ上の設定を `config.toml` に保存する
     *
     * 例
     * ```kotlin
     * ConfigManager.save()
     * ```
     */
    fun save() {
        synchronized(lock) {
            if (!::config.isInitialized) {
                config = Config.default()
            }
            ensureParentDirectory(configPath)
            configPath.bufferedWriter().use { writer ->
                writer.write(encodeToml(config))
            }
        }
    }

    /**
     * 設定を更新して即保存する
     *
     * 例
     * ```kotlin
     * ConfigManager.update {
     *     it.copy(proxy = it.proxy.copy(listenPort = 25570))
     * }
     * ```
     */
    fun update(transform: (Config) -> Config): Config {
        synchronized(lock) {
            config = transform(current())
            save()
            return config
        }
    }

    /**
     * 現在の設定を取得する
     * 未初期化の場合は自動で `init()` を実行する
     *
     * 例
     * ```kotlin
     * val current = ConfigManager.current()
     * ```
     */
    fun current(): Config {
        if (::config.isInitialized) {
            return config
        }

        synchronized(lock) {
            if (!::config.isInitialized) {
                init(configPath)
            }
            return config
        }
    }

    private fun read(path: Path): Config {
        val result = Toml.parse(path)
        if (result.hasErrors()) {
            val details = result.errors().joinToString("; ") { it.toString() }
            throw IllegalStateException("Failed to parse ${path.toAbsolutePath()}: $details")
        }

        val defaults = Config.default()
        val proxyTable = result.getTable("proxy")

        val proxy = defaults.proxy.copy(
            listenHost = proxyTable?.getString("listen_host") ?: defaults.proxy.listenHost,
            listenPort = (proxyTable?.getLong("listen_port")?.toInt()) ?: defaults.proxy.listenPort,
            targetHost = proxyTable?.getString("target_host") ?: defaults.proxy.targetHost,
            targetPort = (proxyTable?.getLong("target_port")?.toInt()) ?: defaults.proxy.targetPort
        )

        validatePort("proxy.listen_port", proxy.listenPort)
        validatePort("proxy.target_port", proxy.targetPort)

        return Config(proxy = proxy)
    }

    private fun encodeToml(config: Config): String {
        val p = config.proxy
        return buildString {
            appendLine("# Minecraft LegacyConsole Proxy configuration")
            appendLine("[proxy]")
            appendLine("""listen_host = "${escapeTomlString(p.listenHost)}"""")
            appendLine("listen_port = ${p.listenPort}")
            appendLine("""target_host = "${escapeTomlString(p.targetHost)}"""")
            appendLine("target_port = ${p.targetPort}")
        }
    }

    private fun escapeTomlString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    private fun validatePort(name: String, value: Int) {
        require(value in 1..65535) {
            "$name must be between 1 and 65535, but was $value"
        }
    }

    private fun ensureParentDirectory(path: Path) {
        val parent = path.parent ?: return
        if (!Files.exists(parent)) {
            Files.createDirectories(parent)
        }
    }
}
