package dev.kuwa.mlcproxy.config

/**
 * プロトコル変換に必要な設定
 */
data class ProtocolConfig(
    val mlcTransportMode: String,
    val mlcNetVersion: Int,
    val mlcGameProtocolVersion: Int,
    val javaProtocolVersion: Int,
    val javaHandshakeHost: String,
    val javaHandshakePort: Int
) {
    companion object {
        fun default(): ProtocolConfig {
            return ProtocolConfig(
                mlcTransportMode = "LENGTH_PREFIXED_32BE",
                mlcNetVersion = 560,
                mlcGameProtocolVersion = 78,
                javaProtocolVersion = 773,
                javaHandshakeHost = "127.0.0.1",
                javaHandshakePort = 25565
            )
        }
    }
}
