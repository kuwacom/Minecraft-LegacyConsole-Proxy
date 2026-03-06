package dev.kuwa.mlcproxy.discovery

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.nio.charset.StandardCharsets

/**
 * MinecraftConsoles Windows64 の `Win64LANBroadcast` 互換 codec
 */
object Win64LanBroadcastCodec {
    const val MAGIC: Int = 0x4D434C4E
    const val PACKET_SIZE: Int = 84
    private const val HOST_NAME_WCHARS: Int = 32

    fun encode(packet: Win64LanBroadcastPacket): ByteArray {
        val out = Unpooled.buffer(PACKET_SIZE)
        out.writeIntLE(MAGIC)
        out.writeShortLE(packet.netVersion and 0xFFFF)
        out.writeShortLE(packet.gamePort and 0xFFFF)
        out.writeBytes(encodeFixedWchar(packet.hostName, HOST_NAME_WCHARS))
        out.writeByte(packet.playerCount and 0xFF)
        out.writeByte(packet.maxPlayers and 0xFF)
        out.writeIntLE(packet.gameHostSettings.toInt())
        out.writeIntLE(packet.texturePackParentId.toInt())
        out.writeByte(packet.subTexturePackId and 0xFF)
        out.writeByte(if (packet.isJoinable) 1 else 0)

        val bytes = ByteArray(out.readableBytes())
        out.getBytes(out.readerIndex(), bytes)
        return bytes
    }

    fun decodeOrNull(buf: ByteBuf): Win64LanBroadcastPacket? {
        if (buf.readableBytes() < PACKET_SIZE) return null
        val idx = buf.readerIndex()
        if (buf.getIntLE(idx) != MAGIC) return null

        val netVersion = buf.getUnsignedShortLE(idx + 4)
        val gamePort = buf.getUnsignedShortLE(idx + 6)
        val hostNameBytes = ByteArray(HOST_NAME_WCHARS * 2)
        buf.getBytes(idx + 8, hostNameBytes)
        val playerCount = buf.getUnsignedByte(idx + 72).toInt()
        val maxPlayers = buf.getUnsignedByte(idx + 73).toInt()
        val gameHostSettings = buf.getUnsignedIntLE(idx + 74)
        val texturePackParentId = buf.getUnsignedIntLE(idx + 78)
        val subTexturePackId = buf.getUnsignedByte(idx + 82).toInt()
        val isJoinable = buf.getUnsignedByte(idx + 83).toInt() != 0

        return Win64LanBroadcastPacket(
            netVersion = netVersion,
            gamePort = gamePort,
            hostName = decodeFixedWchar(hostNameBytes),
            playerCount = playerCount,
            maxPlayers = maxPlayers,
            gameHostSettings = gameHostSettings,
            texturePackParentId = texturePackParentId,
            subTexturePackId = subTexturePackId,
            isJoinable = isJoinable
        )
    }

    fun isBroadcastAnnouncement(buf: ByteBuf): Boolean {
        val idx = buf.readerIndex()
        if (buf.readableBytes() < 4) return false
        return buf.getIntLE(idx) == MAGIC
    }

    /**
     * 探索パケットは実装差分が大きいため、
     * 有効な announce 以外は「検索問い合わせ」として扱う。
     */
    fun shouldTreatAsDiscoveryQuery(buf: ByteBuf): Boolean {
        if (!buf.isReadable) return true
        if (decodeOrNull(buf) != null) return false

        if (buf.readableBytes() == 4) {
            val idx = buf.readerIndex()
            val le = buf.getIntLE(idx)
            val be = buf.getInt(idx)
            if (le == MAGIC || be == MAGIC) return true

            val four = ByteArray(4)
            buf.getBytes(idx, four)
            val token = String(four, StandardCharsets.US_ASCII)
            if (token == "MCLN" || token == "NLCM") return true
        }

        return true
    }

    private fun encodeFixedWchar(value: String, fixedChars: Int): ByteArray {
        val out = ByteArray(fixedChars * 2)
        val truncated = value.take(fixedChars - 1)
        val raw = truncated.toByteArray(StandardCharsets.UTF_16LE)
        System.arraycopy(raw, 0, out, 0, minOf(raw.size, out.size - 2))
        return out
    }

    private fun decodeFixedWchar(bytes: ByteArray): String {
        val full = String(bytes, StandardCharsets.UTF_16LE)
        val nullIndex = full.indexOf('\u0000')
        return if (nullIndex >= 0) full.substring(0, nullIndex) else full
    }
}
