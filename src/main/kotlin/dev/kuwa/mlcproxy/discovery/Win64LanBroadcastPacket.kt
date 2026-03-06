package dev.kuwa.mlcproxy.discovery

data class Win64LanBroadcastPacket(
    val netVersion: Int,
    val gamePort: Int,
    val hostName: String,
    val playerCount: Int,
    val maxPlayers: Int,
    val gameHostSettings: Long,
    val texturePackParentId: Long,
    val subTexturePackId: Int,
    val isJoinable: Boolean
)
