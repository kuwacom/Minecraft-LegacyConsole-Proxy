package dev.kuwa.mlcproxy.protocol.mlc

object MlcPacketId {
    const val KEEP_ALIVE = 0
    const val LOGIN = 1
    const val PRE_LOGIN = 2
    const val MOVE_PLAYER_POS_ROT = 11
    const val MOVE_PLAYER_ROT = 12
    const val MOVE_PLAYER_POS = 13
    const val CUSTOM_PAYLOAD = 250
    const val DISCONNECT = 255
}
