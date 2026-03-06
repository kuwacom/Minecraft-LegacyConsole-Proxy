package dev.kuwa.mlcproxy.bridge

import dev.kuwa.mlcproxy.protocol.common.readJavaString
import dev.kuwa.mlcproxy.protocol.common.writeDataUtfLike
import dev.kuwa.mlcproxy.protocol.java.JavaPacket
import dev.kuwa.mlcproxy.protocol.java.JavaPacketId
import dev.kuwa.mlcproxy.protocol.mlc.MlcPacket
import dev.kuwa.mlcproxy.protocol.mlc.MlcPacketId
import dev.kuwa.mlcproxy.session.SessionState
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.slf4j.LoggerFactory

class JavaToMlcMapper : PacketMapper<JavaPacket, MlcPacket> {
    private val logger = LoggerFactory.getLogger(JavaToMlcMapper::class.java)

    override fun map(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        if (!context.javaLoginSucceeded) {
            return mapLoginPhase(packet, context)
        }
        return mapPlayPhase(packet, context)
    }

    private fun mapLoginPhase(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        return when (packet.id) {
            JavaPacketId.LoginClientbound.DISCONNECT -> {
                listOf(buildMlcDisconnect(fromJavaDisconnectReason(packet.payload)))
            }

            JavaPacketId.LoginClientbound.LOGIN_SUCCESS -> {
                context.javaLoginSucceeded = true
                context.sessionState = SessionState.PLAY
                listOf(buildLegacyLoginAccepted(context.playerName))
            }

            else -> emptyList()
        }
    }

    private fun mapPlayPhase(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        return when (packet.id) {
            JavaPacketId.PlayClientbound.KEEP_ALIVE -> {
                listOf(MlcPacket(MlcPacketId.KEEP_ALIVE, packet.payload))
            }

            JavaPacketId.PlayClientbound.DISCONNECT -> {
                listOf(buildMlcDisconnect(fromJavaDisconnectReason(packet.payload)))
            }

            JavaPacketId.PlayClientbound.JOIN_GAME -> {
                if (context.sessionState != SessionState.PLAY) {
                    context.sessionState = SessionState.PLAY
                }
                emptyList()
            }

            else -> emptyList()
        }
    }

    private fun buildLegacyLoginAccepted(playerName: String): MlcPacket {
        val out = Unpooled.buffer()
        out.writeDataUtfLike(playerName)
        return MlcPacket(MlcPacketId.LOGIN, out.toByteArray())
    }

    private fun buildMlcDisconnect(reason: String): MlcPacket {
        val out = Unpooled.buffer()
        out.writeDataUtfLike(reason)
        return MlcPacket(MlcPacketId.DISCONNECT, out.toByteArray())
    }

    private fun fromJavaDisconnectReason(payload: ByteArray): String {
        val buf = Unpooled.wrappedBuffer(payload)
        return runCatching {
            val raw = buf.readJavaString()
            raw.replace("\\\\n", " ")
                .replace("{", "")
                .replace("}", "")
                .replace("\"", "")
        }.getOrElse {
            logger.debug("Failed to parse Java disconnect payload: {}", it.message)
            "Disconnected by Java server"
        }
    }

    private fun ByteBuf.toByteArray(): ByteArray {
        val bytes = ByteArray(readableBytes())
        getBytes(readerIndex(), bytes)
        return bytes
    }
}
