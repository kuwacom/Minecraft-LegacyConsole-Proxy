package dev.kuwa.mlcproxy.bridge

import dev.kuwa.mlcproxy.config.ProtocolConfig
import dev.kuwa.mlcproxy.session.SessionState
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

class MappingContext(
    val protocolConfig: ProtocolConfig
) {
    data class EntityTracker(
        var x32: Int = 0,
        var y32: Int = 0,
        var z32: Int = 0,
        var yawByte: Int = 0,
        var pitchByte: Int = 0,
        var headYawByte: Int = 0
    )

    @Volatile
    var sessionState: SessionState = SessionState.CONNECTED

    @Volatile
    var preLoginReceived: Boolean = false

    @Volatile
    var loginReceived: Boolean = false

    @Volatile
    var javaLoginStarted: Boolean = false

    @Volatile
    var javaLoginSucceeded: Boolean = false

    @Volatile
    var javaConfigurationPhase: Boolean = false

    @Volatile
    var javaLoginAcknowledged: Boolean = false

    @Volatile
    var mlcLoginSent: Boolean = false

    @Volatile
    var playerName: String = "Player"

    @Volatile
    var javaEntityId: Int = 0

    @Volatile
    var javaDimension: Int = 0

    @Volatile
    var javaDifficulty: Int = 1

    @Volatile
    var javaGameMode: Int = 0

    @Volatile
    var javaMaxPlayers: Int = 8

    @Volatile
    var javaLevelType: String = "default"

    @Volatile
    var playerX: Double = 0.0

    @Volatile
    var playerY: Double = 64.0

    @Volatile
    var playerZ: Double = 0.0

    @Volatile
    var playerYaw: Float = 0.0f

    @Volatile
    var playerPitch: Float = 0.0f

    @Volatile
    var playerFlying: Boolean = false

    val entityTrackers: ConcurrentHashMap<Int, EntityTracker> = ConcurrentHashMap()
    val playerNamesByUuid: ConcurrentHashMap<String, String> = ConcurrentHashMap()
    val javaKeepAliveByMlcToken: ConcurrentHashMap<Int, Long> = ConcurrentHashMap()
    val nextKeepAliveToken: AtomicInteger = AtomicInteger(1)
    val sentMlcRegionChunks: ConcurrentHashMap<Long, Boolean> = ConcurrentHashMap()
    val sentMlcRegionChunkCount: AtomicInteger = AtomicInteger(0)
    val visibleChunkKeys: ConcurrentHashMap<Long, Boolean> = ConcurrentHashMap()
}
