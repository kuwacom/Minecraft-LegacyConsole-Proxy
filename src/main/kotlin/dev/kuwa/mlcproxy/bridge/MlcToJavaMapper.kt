package dev.kuwa.mlcproxy.bridge

import dev.kuwa.mlcproxy.config.Config
import dev.kuwa.mlcproxy.protocol.common.readDataUtfLike
import dev.kuwa.mlcproxy.protocol.common.writeJavaString
import dev.kuwa.mlcproxy.protocol.common.writeVarInt
import dev.kuwa.mlcproxy.protocol.java.JavaPacket
import dev.kuwa.mlcproxy.protocol.java.JavaPacketId
import dev.kuwa.mlcproxy.protocol.mlc.MlcPacket
import dev.kuwa.mlcproxy.protocol.mlc.MlcPacketId
import dev.kuwa.mlcproxy.session.SessionState
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.slf4j.LoggerFactory
import kotlin.math.max

class MlcToJavaMapper(
    private val config: Config
) : PacketMapper<MlcPacket, JavaPacket> {
    private val logger = LoggerFactory.getLogger(MlcToJavaMapper::class.java)

    override fun map(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        return when (packet.id) {
            MlcPacketId.PRE_LOGIN -> handlePreLogin(packet, context)
            MlcPacketId.LOGIN -> handleLogin(packet, context)
            MlcPacketId.KEEP_ALIVE -> handleKeepAlive(packet, context)
            MlcPacketId.MOVE_PLAYER_POS_ROT,
            MlcPacketId.MOVE_PLAYER_POS,
            MlcPacketId.MOVE_PLAYER_ROT -> handleMovePlayer(packet, context)
            MlcPacketId.CUSTOM_PAYLOAD -> handleCustomPayload(packet)
            MlcPacketId.DISCONNECT -> emptyList()
            else -> emptyList()
        }
    }

    private fun handlePreLogin(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (packet.payload.size >= 4) {
            val buf = Unpooled.wrappedBuffer(packet.payload)
            val netVersion = buf.readInt()
            if (netVersion != config.protocol.mlcNetVersion) {
                logger.warn(
                    "MLC net version mismatch: expected={} actual={}",
                    config.protocol.mlcNetVersion,
                    netVersion
                )
            }
        }

        context.preLoginReceived = true
        context.sessionState = SessionState.PRELOGIN
        return emptyList()
    }

    private fun handleLogin(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        context.loginReceived = true
        context.sessionState = SessionState.LOGIN

        if (context.javaLoginStarted) {
            return emptyList()
        }

        val playerName = parsePlayerName(packet.payload)
        context.playerName = playerName
        context.javaLoginStarted = true

        return listOf(
            buildJavaHandshake(context),
            buildJavaLoginStart(playerName)
        )
    }

    private fun handleKeepAlive(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) {
            return emptyList()
        }
        return listOf(JavaPacket(JavaPacketId.PlayServerbound.KEEP_ALIVE, packet.payload))
    }

    private fun handleMovePlayer(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) {
            return emptyList()
        }

        val buf = Unpooled.wrappedBuffer(packet.payload)
        return when (packet.id) {
            MlcPacketId.MOVE_PLAYER_POS_ROT -> {
                val javaPayload = tryBuildPosLookPayload(buf) ?: return emptyList()
                listOf(JavaPacket(JavaPacketId.PlayServerbound.PLAYER_POSITION_LOOK, javaPayload))
            }

            MlcPacketId.MOVE_PLAYER_POS -> {
                val javaPayload = tryBuildPosPayload(buf) ?: return emptyList()
                listOf(JavaPacket(JavaPacketId.PlayServerbound.PLAYER_POSITION, javaPayload))
            }

            MlcPacketId.MOVE_PLAYER_ROT -> {
                val javaPayload = tryBuildLookPayload(buf) ?: return emptyList()
                listOf(JavaPacket(JavaPacketId.PlayServerbound.PLAYER_LOOK, javaPayload))
            }

            else -> emptyList()
        }
    }

    private fun handleCustomPayload(packet: MlcPacket): List<JavaPacket> {
        val buf = Unpooled.wrappedBuffer(packet.payload)
        return runCatching {
            val channel = buf.readDataUtfLike()
            if (buf.readableBytes() < 2) {
                return emptyList()
            }
            val dataLength = buf.readUnsignedShort()
            if (buf.readableBytes() < dataLength) {
                return emptyList()
            }
            val data = ByteArray(dataLength)
            buf.readBytes(data)

            val payload = Unpooled.buffer()
            payload.writeJavaString(channel, maxLength = 20)
            payload.writeShort(data.size)
            payload.writeBytes(data)
            JavaPacket(JavaPacketId.PlayServerbound.PLUGIN_MESSAGE, payload.toByteArray())
        }.map { listOf(it) }.getOrElse {
            logger.debug("Failed to map MLC custom payload: {}", it.message)
            emptyList()
        }
    }

    private fun buildJavaHandshake(context: MappingContext): JavaPacket {
        val payload = Unpooled.buffer()
        payload.writeVarInt(context.protocolConfig.javaProtocolVersion)
        payload.writeJavaString(context.protocolConfig.javaHandshakeHost)
        payload.writeShort(context.protocolConfig.javaHandshakePort)
        payload.writeVarInt(2)
        return JavaPacket(JavaPacketId.Handshake.HANDSHAKE, payload.toByteArray())
    }

    private fun buildJavaLoginStart(playerName: String): JavaPacket {
        val payload = Unpooled.buffer()
        payload.writeJavaString(playerName, maxLength = 16)
        return JavaPacket(JavaPacketId.LoginServerbound.LOGIN_START, payload.toByteArray())
    }

    private fun parsePlayerName(payload: ByteArray): String {
        if (payload.isEmpty()) return "Player"

        val buf = Unpooled.wrappedBuffer(payload)
        val parsed = runCatching { buf.readDataUtfLike() }.getOrNull()
        if (!parsed.isNullOrBlank()) {
            return parsed.take(16)
        }

        val fallback = payload
            .map { b -> b.toInt().toChar() }
            .filter { it.isLetterOrDigit() || it == '_' }
            .joinToString("")
            .ifBlank { "Player" }

        return fallback.take(max(3, minOf(fallback.length, 16)))
    }

    private fun tryBuildPosLookPayload(buf: io.netty.buffer.ByteBuf): ByteArray? {
        return when {
            buf.readableBytes() >= 33 -> {
                val x = buf.readDouble()
                val y = buf.readDouble()
                val z = buf.readDouble()
                val yaw = buf.readFloat()
                val pitch = buf.readFloat()
                val onGround = buf.readBoolean()
                encodePosLook(x, y, z, yaw, pitch, onGround)
            }

            buf.readableBytes() >= 21 -> {
                val x = buf.readFloat().toDouble()
                val y = buf.readFloat().toDouble()
                val z = buf.readFloat().toDouble()
                val yaw = buf.readFloat()
                val pitch = buf.readFloat()
                val onGround = buf.readBoolean()
                encodePosLook(x, y, z, yaw, pitch, onGround)
            }

            else -> null
        }
    }

    private fun tryBuildPosPayload(buf: io.netty.buffer.ByteBuf): ByteArray? {
        return when {
            buf.readableBytes() >= 25 -> {
                val x = buf.readDouble()
                val y = buf.readDouble()
                val z = buf.readDouble()
                val onGround = buf.readBoolean()
                encodePos(x, y, z, onGround)
            }

            buf.readableBytes() >= 13 -> {
                val x = buf.readFloat().toDouble()
                val y = buf.readFloat().toDouble()
                val z = buf.readFloat().toDouble()
                val onGround = buf.readBoolean()
                encodePos(x, y, z, onGround)
            }

            else -> null
        }
    }

    private fun tryBuildLookPayload(buf: io.netty.buffer.ByteBuf): ByteArray? {
        return if (buf.readableBytes() >= 9) {
            val yaw = buf.readFloat()
            val pitch = buf.readFloat()
            val onGround = buf.readBoolean()
            encodeLook(yaw, pitch, onGround)
        } else {
            null
        }
    }

    private fun encodePosLook(
        x: Double,
        y: Double,
        z: Double,
        yaw: Float,
        pitch: Float,
        onGround: Boolean
    ): ByteArray {
        val out = Unpooled.buffer(33)
        out.writeDouble(x)
        out.writeDouble(y)
        out.writeDouble(z)
        out.writeFloat(yaw)
        out.writeFloat(pitch)
        out.writeBoolean(onGround)
        return out.toByteArray()
    }

    private fun encodePos(x: Double, y: Double, z: Double, onGround: Boolean): ByteArray {
        val out = Unpooled.buffer(25)
        out.writeDouble(x)
        out.writeDouble(y)
        out.writeDouble(z)
        out.writeBoolean(onGround)
        return out.toByteArray()
    }

    private fun encodeLook(yaw: Float, pitch: Float, onGround: Boolean): ByteArray {
        val out = Unpooled.buffer(9)
        out.writeFloat(yaw)
        out.writeFloat(pitch)
        out.writeBoolean(onGround)
        return out.toByteArray()
    }

    private fun ByteBuf.toByteArray(): ByteArray {
        val bytes = ByteArray(readableBytes())
        getBytes(readerIndex(), bytes)
        return bytes
    }
}
