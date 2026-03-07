package dev.kuwa.mlcproxy.config

import org.tomlj.Toml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.bufferedWriter
import kotlin.io.path.exists

/**
 * `config/config.toml` の読み書きと参照を担当する設定マネージャ
 *
 * よく使う例
 * ```kotlin
 * val config = ConfigManager.current()
 * val proxy = ConfigManager.proxy
 *
 * ConfigManager.update {
 *     it.copy(proxy = it.proxy.copy(listenPort = 25570))
 * }
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
     * proxy 設定のみを簡単に取得するショートカット
     */
    val proxy: ProxyConfig
        get() = current().proxy

    /**
     * protocol 設定のみを簡単に取得するショートカット
     */
    val protocol: ProtocolConfig
        get() = current().protocol

    /**
     * discovery 設定のみを簡単に取得するショートカット
     */
    val discovery: DiscoveryConfig
        get() = current().discovery

    /**
     * 設定を初期化する
     *
     * - ファイルが存在すれば読み込む
     * - 存在しなければデフォルト設定を作成して保存する
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
     *     it.copy(proxy = it.proxy.copy(targetPort = 25566))
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
     *
     * 未初期化の場合は自動で `init()` を実行する
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

        val protocolTable = result.getTable("protocol")
        val protocolDefaults = defaults.protocol
        val protocol = protocolDefaults.copy(
            mlcTransportMode = protocolTable?.getString("mlc_transport_mode") ?: protocolDefaults.mlcTransportMode,
            mlcNetVersion = (protocolTable?.getLong("mlc_net_version")?.toInt()) ?: protocolDefaults.mlcNetVersion,
            mlcGameProtocolVersion = (protocolTable?.getLong("mlc_game_protocol_version")?.toInt())
                ?: protocolDefaults.mlcGameProtocolVersion,
            javaProtocolVersion = (protocolTable?.getLong("java_protocol_version")?.toInt())
                ?: protocolDefaults.javaProtocolVersion,
            javaHandshakeHost = protocolTable?.getString("java_handshake_host")
                ?: proxy.targetHost,
            javaHandshakePort = (protocolTable?.getLong("java_handshake_port")?.toInt())
                ?: proxy.targetPort
        )

        val discoveryTable = result.getTable("discovery")
        val discoveryDefaults = defaults.discovery
        val discovery = discoveryDefaults.copy(
            enabled = discoveryTable?.getBoolean("enabled") ?: discoveryDefaults.enabled,
            port = (discoveryTable?.getLong("port")?.toInt()) ?: discoveryDefaults.port,
            advertiseIntervalMs = discoveryTable?.getLong("advertise_interval_ms")
                ?: discoveryDefaults.advertiseIntervalMs,
            respondToQueries = discoveryTable?.getBoolean("respond_to_queries")
                ?: discoveryDefaults.respondToQueries,
            hostName = discoveryTable?.getString("host_name") ?: discoveryDefaults.hostName,
            maxPlayers = (discoveryTable?.getLong("max_players")?.toInt()) ?: discoveryDefaults.maxPlayers,
            gameHostSettings = discoveryTable?.getLong("game_host_settings") ?: discoveryDefaults.gameHostSettings,
            texturePackParentId = discoveryTable?.getLong("texture_pack_parent_id")
                ?: discoveryDefaults.texturePackParentId,
            subTexturePackId = (discoveryTable?.getLong("sub_texture_pack_id")?.toInt())
                ?: discoveryDefaults.subTexturePackId
        )

        validatePort("proxy.listen_port", proxy.listenPort)
        validatePort("proxy.target_port", proxy.targetPort)
        validatePort("protocol.java_handshake_port", protocol.javaHandshakePort)
        validateMlcTransportMode(protocol.mlcTransportMode)
        validatePort("discovery.port", discovery.port)
        validateDiscovery(discovery)

        return Config(proxy = proxy, protocol = protocol, discovery = discovery)
    }

    private fun encodeToml(config: Config): String {
        val p = config.proxy
        val protocol = config.protocol
        val discovery = config.discovery
        return buildString {
            appendLine("# Minecraft LegacyConsole Proxy configuration")
            appendLine("[proxy]")
            appendLine("""listen_host = "${escapeTomlString(p.listenHost)}"""")
            appendLine("listen_port = ${p.listenPort}")
            appendLine("""target_host = "${escapeTomlString(p.targetHost)}"""")
            appendLine("target_port = ${p.targetPort}")
            appendLine()
            appendLine("[protocol]")
            appendLine("""mlc_transport_mode = "${escapeTomlString(protocol.mlcTransportMode)}"""")
            appendLine("mlc_net_version = ${protocol.mlcNetVersion}")
            appendLine("mlc_game_protocol_version = ${protocol.mlcGameProtocolVersion}")
            appendLine("java_protocol_version = ${protocol.javaProtocolVersion}")
            appendLine("""java_handshake_host = "${escapeTomlString(protocol.javaHandshakeHost)}"""")
            appendLine("java_handshake_port = ${protocol.javaHandshakePort}")
            appendLine()
            appendLine("[discovery]")
            appendLine("enabled = ${discovery.enabled}")
            appendLine("port = ${discovery.port}")
            appendLine("advertise_interval_ms = ${discovery.advertiseIntervalMs}")
            appendLine("respond_to_queries = ${discovery.respondToQueries}")
            appendLine("""host_name = "${escapeTomlString(discovery.hostName)}"""")
            appendLine("max_players = ${discovery.maxPlayers}")
            appendLine("game_host_settings = ${discovery.gameHostSettings}")
            appendLine("texture_pack_parent_id = ${discovery.texturePackParentId}")
            appendLine("sub_texture_pack_id = ${discovery.subTexturePackId}")
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

    private fun validateMlcTransportMode(value: String) {
        val normalized = value.uppercase()
        require(normalized == "LENGTH_PREFIXED_32BE" || normalized == "RAW") {
            "protocol.mlc_transport_mode must be LENGTH_PREFIXED_32BE or RAW, but was $value"
        }
    }

    private fun validateDiscovery(discovery: DiscoveryConfig) {
        require(discovery.advertiseIntervalMs in 100L..60_000L) {
            "discovery.advertise_interval_ms must be between 100 and 60000, but was ${discovery.advertiseIntervalMs}"
        }
        require(discovery.hostName.isNotBlank()) {
            "discovery.host_name must not be blank"
        }
        require(discovery.maxPlayers in 1..255) {
            "discovery.max_players must be between 1 and 255, but was ${discovery.maxPlayers}"
        }
        require(discovery.gameHostSettings in 0L..0xFFFF_FFFFL) {
            "discovery.game_host_settings must be between 0 and 4294967295, but was ${discovery.gameHostSettings}"
        }
        require(discovery.texturePackParentId in 0L..0xFFFF_FFFFL) {
            "discovery.texture_pack_parent_id must be between 0 and 4294967295, but was ${discovery.texturePackParentId}"
        }
        require(discovery.subTexturePackId in 0..255) {
            "discovery.sub_texture_pack_id must be between 0 and 255, but was ${discovery.subTexturePackId}"
        }
    }

    private fun ensureParentDirectory(path: Path) {
        val parent = path.parent ?: return
        if (!Files.exists(parent)) {
            Files.createDirectories(parent)
        }
    }
}
