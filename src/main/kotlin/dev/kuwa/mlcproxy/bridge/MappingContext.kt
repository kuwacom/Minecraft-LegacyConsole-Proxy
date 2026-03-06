package dev.kuwa.mlcproxy.bridge

import dev.kuwa.mlcproxy.config.ProtocolConfig
import dev.kuwa.mlcproxy.session.SessionState

class MappingContext(
    val protocolConfig: ProtocolConfig
) {
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
    var playerName: String = "Player"
}
