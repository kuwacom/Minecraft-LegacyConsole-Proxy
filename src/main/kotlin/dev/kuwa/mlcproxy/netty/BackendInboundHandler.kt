package dev.kuwa.mlcproxy.netty

import dev.kuwa.mlcproxy.bridge.JavaToMlcMapper
import dev.kuwa.mlcproxy.protocol.java.JavaPacket
import dev.kuwa.mlcproxy.protocol.mlc.MlcPacketId
import dev.kuwa.mlcproxy.session.ProxySession
import dev.kuwa.mlcproxy.session.SessionManager
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
}
