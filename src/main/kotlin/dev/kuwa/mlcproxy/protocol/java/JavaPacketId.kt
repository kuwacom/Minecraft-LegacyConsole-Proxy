package dev.kuwa.mlcproxy.protocol.java

/**
 * Java Edition protocol 47 (1.8.x) を前提にした主要 packet ID
 */
object JavaPacketId {
    object Handshake {
        const val HANDSHAKE = 0x00
    }

    object LoginServerbound {
        const val LOGIN_START = 0x00
    }

    object LoginClientbound {
        const val DISCONNECT = 0x00
        const val LOGIN_SUCCESS = 0x02
    }

    object PlayServerbound {
        const val KEEP_ALIVE = 0x00
        const val PLAYER = 0x03
        const val PLAYER_POSITION = 0x04
        const val PLAYER_LOOK = 0x05
        const val PLAYER_POSITION_LOOK = 0x06
        const val PLUGIN_MESSAGE = 0x17
    }

    object PlayClientbound {
        const val KEEP_ALIVE = 0x00
        const val JOIN_GAME = 0x01
        const val CHAT_MESSAGE = 0x02
        const val DISCONNECT = 0x40
    }
}
