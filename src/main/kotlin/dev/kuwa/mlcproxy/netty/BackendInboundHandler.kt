package dev.kuwa.mlcproxy.netty

import dev.kuwa.mlcproxy.bridge.JavaToMlcMapper
import dev.kuwa.mlcproxy.protocol.common.readJavaString
import dev.kuwa.mlcproxy.protocol.common.readVarInt
import dev.kuwa.mlcproxy.protocol.common.writeJavaString
import dev.kuwa.mlcproxy.protocol.common.writeVarInt
import dev.kuwa.mlcproxy.protocol.java.JavaPacket
import dev.kuwa.mlcproxy.protocol.java.JavaPacketId
import dev.kuwa.mlcproxy.protocol.java.codec.JavaFrameDecoder
import dev.kuwa.mlcproxy.protocol.java.codec.JavaFrameEncoder
import dev.kuwa.mlcproxy.protocol.mlc.MlcPacketId
import dev.kuwa.mlcproxy.session.ProxySession
import dev.kuwa.mlcproxy.session.SessionManager
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.slf4j.LoggerFactory

class BackendInboundHandler(
    private val session: ProxySession,
    private val sessionManager: SessionManager,
    private val mapper: JavaToMlcMapper
) : SimpleChannelInboundHandler<JavaPacket>() {
    private val logger = LoggerFactory.getLogger(BackendInboundHandler::class.java)

    override fun channelRead0(ctx: ChannelHandlerContext, msg: JavaPacket) {
        try {
            val context = session.mappingContext
            val shouldLog = if (!context.javaLoginSucceeded) {
                msg.id == 0x00 || msg.id == 0x01 || msg.id == 0x02 || msg.id == 0x03
            } else {
                msg.id == JavaPacketId.PlayClientbound.LOGIN_MODERN ||
                    msg.id == JavaPacketId.PlayClientbound.KEEP_ALIVE_MODERN ||
                    msg.id == JavaPacketId.PlayClientbound.CHUNK_BATCH_FINISHED_MODERN ||
                    msg.id == JavaPacketId.PlayClientbound.CHUNK_BATCH_START_MODERN ||
                    msg.id == JavaPacketId.PlayClientbound.LEVEL_CHUNK_WITH_LIGHT_MODERN ||
                    msg.id == JavaPacketId.PlayClientbound.LEVEL_CHUNK_WITH_LIGHT_MODERN_ALT ||
                    msg.id == JavaPacketId.PlayClientbound.PLAYER_POSITION_MODERN ||
                    msg.id == JavaPacketId.PlayClientbound.PLAYER_ROTATION_MODERN ||
                    msg.id == JavaPacketId.PlayClientbound.DISCONNECT_MODERN
            }

            if (shouldLog) {
                logger.info(
                    "Java inbound sessionId={} packetId={} javaLoginSucceeded={}",
                    session.id,
                    msg.id,
                    context.javaLoginSucceeded
                )
            }
            if (context.javaLoginSucceeded) {
                logModernChunkObservability(msg)
            }

            if (
                !context.javaLoginSucceeded &&
                !context.javaConfigurationPhase &&
                msg.id == JavaPacketId.LoginClientbound.SET_COMPRESSION
            ) {
                applyCompressionThreshold(ctx, msg.payload)
            }

            if (handleModernLoginAndConfiguration(ctx, msg)) {
                return
            }

            handleModernPlayAutoResponses(ctx, msg)

            val translatedPackets = mapper.map(msg, session.mappingContext)
            translatedPackets.forEach(session::writeToFrontend)

            if (translatedPackets.any { it.id == MlcPacketId.DISCONNECT }) {
                sessionManager.close(session, "java requested disconnect")
            }
        } catch (e: Exception) {
            logger.error("Failed to translate Java packet sessionId={} packetId={}", session.id, msg.id, e)
            sessionManager.close(session, "java translation error")
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        sessionManager.closeByBackend(ctx.channel(), "backend inactive")
        ctx.fireChannelInactive()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("Backend exception remote={}", ctx.channel().remoteAddress(), cause)
        sessionManager.closeByBackend(ctx.channel(), "backend exception")
    }

    private fun applyCompressionThreshold(ctx: ChannelHandlerContext, payload: ByteArray) {
        val threshold = runCatching {
            Unpooled.wrappedBuffer(payload).readVarInt()
        }.getOrElse {
            throw IllegalStateException("Invalid Java compression payload", it)
        }

        val frameDecoder = ctx.pipeline().get("java-frame-decoder")
        val frameEncoder = ctx.pipeline().get("java-frame-encoder")

        if (frameDecoder is JavaFrameDecoder) {
            frameDecoder.setCompressionThreshold(threshold)
        } else {
            logger.warn("java-frame-decoder is missing or unexpected type: {}", frameDecoder?.javaClass?.name)
        }

        if (frameEncoder is JavaFrameEncoder) {
            frameEncoder.setCompressionThreshold(threshold)
        } else {
            logger.warn("java-frame-encoder is missing or unexpected type: {}", frameEncoder?.javaClass?.name)
        }

        logger.info("Enabled Java compression sessionId={} threshold={}", session.id, threshold)
    }

    private fun handleModernLoginAndConfiguration(ctx: ChannelHandlerContext, msg: JavaPacket): Boolean {
        val context = session.mappingContext
        if (context.protocolConfig.javaProtocolVersion < MODERN_CONFIGURATION_PROTOCOL) {
            return false
        }

        if (!context.javaLoginAcknowledged && msg.id == JavaPacketId.LoginClientbound.LOGIN_SUCCESS) {
            context.javaLoginAcknowledged = true
            context.javaConfigurationPhase = true
            ctx.channel().writeAndFlush(JavaPacket(JavaPacketId.LoginServerbound.LOGIN_ACKNOWLEDGED, ByteArray(0)))
            logger.info("Sent Java login acknowledged sessionId={}", session.id)
            sendDefaultClientInformationConfiguration(ctx)
            // This LOGIN_SUCCESS packet must not be re-mapped after switching to configuration phase.
            return true
        }

        if (!context.javaConfigurationPhase || context.javaLoginSucceeded) {
            return false
        }

        logger.info(
            "Java configuration packet sessionId={} packetId={} payloadSize={}",
            session.id,
            msg.id,
            msg.payload.size
        )

        when (msg.id) {
            JavaPacketId.ConfigurationClientbound.COOKIE_REQUEST -> {
                val key = runCatching { Unpooled.wrappedBuffer(msg.payload).readJavaString(maxLength = 32767) }
                    .getOrDefault("minecraft:empty")
                val out = Unpooled.buffer()
                out.writeJavaString(key)
                out.writeBoolean(false) // no cookie payload
                ctx.channel().writeAndFlush(
                    JavaPacket(JavaPacketId.ConfigurationServerbound.COOKIE_RESPONSE, out.toByteArray())
                )
                logger.info("Sent Java configuration cookie-response sessionId={} key={}", session.id, key)
                return true
            }

            JavaPacketId.ConfigurationClientbound.SELECT_KNOWN_PACKS -> {
                // Echo the server offered packs as "known packs" to continue configuration flow.
                ctx.channel().writeAndFlush(
                    JavaPacket(JavaPacketId.ConfigurationServerbound.SELECT_KNOWN_PACKS, msg.payload)
                )
                logger.info("Sent Java configuration known-packs response sessionId={}", session.id)
                return true
            }

            JavaPacketId.ConfigurationClientbound.KEEP_ALIVE -> {
                ctx.channel().writeAndFlush(JavaPacket(JavaPacketId.ConfigurationServerbound.KEEP_ALIVE, msg.payload))
                logger.debug("Sent Java configuration keep-alive response sessionId={}", session.id)
                return true
            }

            JavaPacketId.ConfigurationClientbound.PING -> {
                ctx.channel().writeAndFlush(JavaPacket(JavaPacketId.ConfigurationServerbound.PONG, msg.payload))
                logger.debug("Sent Java configuration pong response sessionId={}", session.id)
                return true
            }

            JavaPacketId.ConfigurationClientbound.CODE_OF_CONDUCT -> {
                ctx.channel().writeAndFlush(
                    JavaPacket(JavaPacketId.ConfigurationServerbound.ACCEPT_CODE_OF_CONDUCT, ByteArray(0))
                )
                logger.info("Sent Java configuration code-of-conduct acceptance sessionId={}", session.id)
                return true
            }

            JavaPacketId.ConfigurationClientbound.ADD_RESOURCE_PACK -> {
                // payload starts with UUID (16 bytes). Reply with ACCEPTED to continue handshake.
                if (msg.payload.size >= 16) {
                    val out = Unpooled.buffer(20)
                    out.writeBytes(msg.payload, 0, 16)
                    out.writeVarInt(3) // accepted
                    ctx.channel().writeAndFlush(
                        JavaPacket(JavaPacketId.ConfigurationServerbound.RESOURCE_PACK_RESPONSE, out.toByteArray())
                    )
                    logger.info("Sent Java configuration resource-pack response sessionId={}", session.id)
                }
                return true
            }

            JavaPacketId.ConfigurationClientbound.REGISTRY_DATA,
            JavaPacketId.ConfigurationClientbound.REMOVE_RESOURCE_PACK,
            JavaPacketId.ConfigurationClientbound.STORE_COOKIE,
            JavaPacketId.ConfigurationClientbound.TRANSFER,
            JavaPacketId.ConfigurationClientbound.ENABLED_FEATURES,
            JavaPacketId.ConfigurationClientbound.UPDATE_TAGS,
            JavaPacketId.ConfigurationClientbound.RESET_CHAT,
            JavaPacketId.ConfigurationClientbound.CUSTOM_REPORT_DETAILS,
            JavaPacketId.ConfigurationClientbound.SERVER_LINKS,
            JavaPacketId.ConfigurationClientbound.CLEAR_DIALOG,
            JavaPacketId.ConfigurationClientbound.SHOW_DIALOG,
            JavaPacketId.ConfigurationClientbound.CUSTOM_PAYLOAD -> {
                // informational/config payload packets that do not require a mandatory client response
                return true
            }

            JavaPacketId.ConfigurationClientbound.FINISH_CONFIGURATION -> {
                ctx.channel().writeAndFlush(
                    JavaPacket(JavaPacketId.ConfigurationServerbound.FINISH_CONFIGURATION, ByteArray(0))
                )
                context.javaConfigurationPhase = false
                context.javaLoginSucceeded = true
                sendChunkBatchReceived(ctx, "play-start")
                logger.info("Configuration finished; entering play phase sessionId={}", session.id)
                return true
            }
        }

        return false
    }

    private fun handleModernPlayAutoResponses(ctx: ChannelHandlerContext, msg: JavaPacket) {
        val context = session.mappingContext
        if (
            context.protocolConfig.javaProtocolVersion < MODERN_CONFIGURATION_PROTOCOL ||
            context.javaConfigurationPhase ||
            !context.javaLoginSucceeded
        ) {
            return
        }

        if (msg.id == JavaPacketId.PlayClientbound.CHUNK_BATCH_FINISHED_MODERN) {
            val batchSize = runCatching { Unpooled.wrappedBuffer(msg.payload).readVarInt() }.getOrDefault(-1)
            logger.info("Java chunk batch finished sessionId={} batchSize={}", session.id, batchSize)
            sendChunkBatchReceived(ctx, "batch-finished")
            return
        }

        val teleportId = when (msg.id) {
            JavaPacketId.PlayClientbound.PLAYER_POSITION_MODERN -> extractTeleportIdFromPlayerPosition(msg.payload)
            JavaPacketId.PlayClientbound.PLAYER_ROTATION_MODERN -> extractTeleportIdFromPlayerRotation(msg.payload)
            else -> null
        } ?: return

        val out = Unpooled.buffer()
        out.writeVarInt(teleportId)
        ctx.channel().writeAndFlush(
            JavaPacket(JavaPacketId.PlayServerbound.ACCEPT_TELEPORTATION_MODERN, out.toByteArray())
        )
        logger.debug("Sent Java teleport acknowledgement sessionId={} teleportId={}", session.id, teleportId)
    }

    private fun sendChunkBatchReceived(ctx: ChannelHandlerContext, reason: String) {
        val out = Unpooled.buffer(4)
        out.writeFloat(DEFAULT_CHUNKS_PER_TICK)
        ctx.channel().writeAndFlush(
            JavaPacket(JavaPacketId.PlayServerbound.CHUNK_BATCH_RECEIVED_MODERN, out.toByteArray())
        )
        logger.info(
            "Sent Java chunk-batch-received sessionId={} chunksPerTick={} reason={}",
            session.id,
            DEFAULT_CHUNKS_PER_TICK,
            reason
        )
    }

    private fun logModernChunkObservability(msg: JavaPacket) {
        val isChunkPacket =
            msg.id == JavaPacketId.PlayClientbound.LEVEL_CHUNK_WITH_LIGHT_MODERN ||
                msg.id == JavaPacketId.PlayClientbound.LEVEL_CHUNK_WITH_LIGHT_MODERN_ALT
        if (!isChunkPacket) {
            return
        }

        val buf = Unpooled.wrappedBuffer(msg.payload)
        if (buf.readableBytes() < 8) {
            logger.warn(
                "Java modern chunk packet had short payload sessionId={} packetId={} payloadSize={}",
                session.id,
                msg.id,
                msg.payload.size
            )
            return
        }

        val chunkX = buf.readInt()
        val chunkZ = buf.readInt()
        logger.info(
            "Java modern chunk received sessionId={} packetId={} chunkX={} chunkZ={} payloadSize={}",
            session.id,
            msg.id,
            chunkX,
            chunkZ,
            msg.payload.size
        )
    }

    private fun extractTeleportIdFromPlayerPosition(payload: ByteArray): Int? {
        val buf = Unpooled.wrappedBuffer(payload)
        if (buf.readableBytes() < 24) return null
        buf.readDouble()
        buf.readDouble()
        buf.readDouble()
        return runCatching { buf.readVarInt() }.getOrNull()
    }

    private fun extractTeleportIdFromPlayerRotation(payload: ByteArray): Int? {
        val buf = Unpooled.wrappedBuffer(payload)
        if (buf.readableBytes() < 8) return null
        buf.readFloat()
        buf.readFloat()
        return runCatching { buf.readVarInt() }.getOrNull()
    }

    private fun sendDefaultClientInformationConfiguration(ctx: ChannelHandlerContext) {
        val payload = Unpooled.buffer()
        payload.writeJavaString("en_us", maxLength = 16)
        payload.writeByte(8) // view distance
        payload.writeVarInt(0) // chat mode: enabled
        payload.writeBoolean(true) // chat colors
        payload.writeByte(0x7F) // displayed skin parts (all)
        payload.writeVarInt(1) // main hand: right
        payload.writeBoolean(false) // text filtering
        payload.writeBoolean(true) // allow server listing
        payload.writeVarInt(0) // particles: all

        ctx.channel().writeAndFlush(
            JavaPacket(JavaPacketId.ConfigurationServerbound.CLIENT_INFORMATION, payload.toByteArray())
        )
        logger.info("Sent Java configuration client-information sessionId={}", session.id)
    }

    companion object {
        private const val MODERN_CONFIGURATION_PROTOCOL = 764 // 1.20.2+
        private const val DEFAULT_CHUNKS_PER_TICK = 20.0f
    }

    private fun io.netty.buffer.ByteBuf.toByteArray(): ByteArray {
        val out = ByteArray(readableBytes())
        getBytes(readerIndex(), out)
        return out
    }
}
