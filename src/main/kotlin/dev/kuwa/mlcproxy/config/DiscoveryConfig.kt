package dev.kuwa.mlcproxy.config

/**
 * MLC LAN discovery (UDP 25566) の設定
 */
data class DiscoveryConfig(
    val enabled: Boolean,
    val port: Int,
    val advertiseIntervalMs: Long,
    val respondToQueries: Boolean,
    val hostName: String,
    val maxPlayers: Int,
    val gameHostSettings: Long,
    val texturePackParentId: Long,
    val subTexturePackId: Int
) {
    companion object {
        fun default(): DiscoveryConfig {
            return DiscoveryConfig(
                enabled = true,
                port = 25566,
                advertiseIntervalMs = 1000L,
                respondToQueries = true,
                hostName = "MLC Proxy",
                maxPlayers = 8,
                gameHostSettings = 0L,
                texturePackParentId = 0L,
                subTexturePackId = 0
            )
        }
    }
}
