package dev.kuwa.mlcproxy.netty

import dev.kuwa.mlcproxy.bootstrap.UpstreamConnector
import dev.kuwa.mlcproxy.bridge.MlcToJavaMapper
import dev.kuwa.mlcproxy.config.Config
import dev.kuwa.mlcproxy.protocol.common.readLegacyUtf16
import dev.kuwa.mlcproxy.protocol.common.writeLegacyUtf16
import dev.kuwa.mlcproxy.protocol.mlc.MlcPacket
import dev.kuwa.mlcproxy.protocol.mlc.MlcPacketId
import dev.kuwa.mlcproxy.session.SessionManager
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets

class FrontendInboundHandler(
    private val config: Config,
    private val sessionManager: SessionManager,
    private val upstreamConnector: UpstreamConnector,
    private val mapper: MlcToJavaMapper
) : SimpleChannelInboundHandler<MlcPacket>() {
    private val logger = LoggerFactory.getLogger(FrontendInboundHandler::class.java)

    override fun channelActive(ctx: ChannelHandlerContext) {
        val session = sessionManager.create(ctx.channel(), config)
        logger.info("Frontend connected sessionId={} remote={}", session.id, ctx.channel().remoteAddress())

        if (config.protocol.mlcTransportMode.equals("LENGTH_PREFIXED_32BE", ignoreCase = true)) {
            sendWin64SmallIdAssignment(ctx, session.id)
        }

        upstreamConnector.connect(session).addListener { future ->
            if (!future.isSuccess) {
                logger.error("Failed to connect upstream sessionId={}", session.id, future.cause())
                sessionManager.close(session, "failed to connect upstream")
            }
        }
        ctx.fireChannelActive()
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: MlcPacket) {
        val session = sessionManager.getByFrontend(ctx.channel()) ?: return
        try {
            if (
                msg.id == MlcPacketId.PRE_LOGIN ||
                msg.id == MlcPacketId.LOGIN ||
                msg.id == MlcPacketId.DISCONNECT
            ) {
                logger.info("MLC inbound sessionId={} packetId={}", session.id, msg.id)
            }

            if (msg.id == MlcPacketId.PRE_LOGIN) {
                // MLC handshake expects a server-side PreLogin response before Login(1).
                val parsedPreLogin = parsePreLoginRequest(msg.payload)
                if (parsedPreLogin?.loginKey?.isNotBlank() == true) {
                    session.mappingContext.playerName = parsedPreLogin.loginKey.take(16)
                }
                val response = buildPreLoginResponse(parsedPreLogin)
                session.writeToFrontend(response)

                logger.info(
                    "Sent MLC PRE_LOGIN response sessionId={} reqNet={} respNet={} ugcVersion={} hostSettings={} texturePackId={}",
                    session.id,
                    parsedPreLogin?.netVersion ?: -1,
                    parseNetVersion(response.payload),
                    parsedPreLogin?.ugcPlayersVersion ?: 0,
                    config.discovery.gameHostSettings,
                    config.discovery.texturePackParentId
                )
            }

            val translatedPackets = mapper.map(msg, session.mappingContext)
            if (msg.id == MlcPacketId.LOGIN && translatedPackets.isNotEmpty()) {
                logger.info(
                    "Forwarding Java login sequence sessionId={} packetIds={}",
                    session.id,
                    translatedPackets.joinToString(",") { it.id.toString() }
                )
            }
            translatedPackets.forEach(session::writeToBackend)

            if (msg.id == MlcPacketId.DISCONNECT) {
                sessionManager.close(session, "mlc requested disconnect")
            }
        } catch (e: Exception) {
            logger.error("Failed to translate MLC packet sessionId={} packetId={}", session.id, msg.id, e)
            sessionManager.close(session, "mlc translation error")
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        sessionManager.closeByFrontend(ctx.channel(), "frontend inactive")
        ctx.fireChannelInactive()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("Frontend exception remote={}", ctx.channel().remoteAddress(), cause)
        sessionManager.closeByFrontend(ctx.channel(), "frontend exception")
    }

    private fun sendWin64SmallIdAssignment(ctx: ChannelHandlerContext, sessionId: String) {
        // Win64 transport expects a raw 1-byte smallId immediately after TCP connect.
        val raw = Unpooled.buffer(1)
        raw.writeByte(1)
        val frameEncoderCtx = ctx.pipeline().context("mlc-frame-encoder")
        val future = if (frameEncoderCtx != null) {
            frameEncoderCtx.writeAndFlush(raw)
        } else {
            ctx.channel().writeAndFlush(raw)
        }
        future.addListener { result ->
            if (result.isSuccess) {
                logger.info("Sent Win64 smallId assignment sessionId={} smallId=1", sessionId)
            } else {
                logger.error("Failed Win64 smallId assignment sessionId={}", sessionId, result.cause())
                ctx.channel().close()
            }
        }
    }

    private data class ParsedPreLoginRequest(
        val netVersion: Int,
        val loginKey: String,
        val ugcPlayersVersion: Int
    )

    private fun parsePreLoginRequest(payload: ByteArray): ParsedPreLoginRequest? {
        if (payload.size < 2) return null

        return try {
            val buf = Unpooled.wrappedBuffer(payload)
            val netVersion = buf.readUnsignedShort()
            val loginKey = buf.readLegacyUtf16(maxLength = 32)

            if (buf.readableBytes() >= 1) {
                buf.readUnsignedByte() // friendsOnlyBits
            }

            val ugcPlayersVersion = if (buf.readableBytes() >= 4) buf.readInt() else 0
            ParsedPreLoginRequest(netVersion, loginKey, ugcPlayersVersion)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildPreLoginResponse(request: ParsedPreLoginRequest?): MlcPacket {
        val payload = Unpooled.buffer()
        val netVersion = (request?.netVersion ?: config.protocol.mlcNetVersion).coerceIn(0, 0xFFFF)

        payload.writeShort(netVersion)
        payload.writeLegacyUtf16("-", maxLength = 32)
        payload.writeByte(0) // friendsOnlyBits
        payload.writeInt(request?.ugcPlayersVersion ?: 0)
        payload.writeByte(0) // player count
        payload.writeBytes(buildUniqueSaveName())
        payload.writeInt(config.discovery.gameHostSettings.toInt())
        payload.writeByte(0) // host index
        payload.writeInt(config.discovery.texturePackParentId.toInt())

        return MlcPacket(MlcPacketId.PRE_LOGIN, payload.toByteArray())
    }

    private fun buildUniqueSaveName(): ByteArray {
        val out = ByteArray(14)
        val seed = "MLCPROXYSAVE1".toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(seed, 0, out, 0, minOf(seed.size, out.size - 1))
        return out
    }

    private fun parseNetVersion(payload: ByteArray): Int {
        if (payload.size < 2) return -1
        return Unpooled.wrappedBuffer(payload).readUnsignedShort()
    }

    private fun ByteBuf.toByteArray(): ByteArray {
        val out = ByteArray(readableBytes())
        getBytes(readerIndex(), out)
        return out
    }
}
