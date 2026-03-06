package dev.kuwa.mlcproxy.netty

import dev.kuwa.mlcproxy.bootstrap.UpstreamConnector
import dev.kuwa.mlcproxy.bridge.MlcToJavaMapper
import dev.kuwa.mlcproxy.config.Config
import dev.kuwa.mlcproxy.protocol.mlc.MlcPacket
import dev.kuwa.mlcproxy.protocol.mlc.MlcPacketId
import dev.kuwa.mlcproxy.session.SessionManager
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.slf4j.LoggerFactory

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
            val translatedPackets = mapper.map(msg, session.mappingContext)
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
}
