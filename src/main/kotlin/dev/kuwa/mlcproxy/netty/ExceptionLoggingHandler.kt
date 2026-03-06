package dev.kuwa.mlcproxy.netty

import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import org.slf4j.LoggerFactory

class ExceptionLoggingHandler(
    private val name: String
) : ChannelDuplexHandler() {
    private val logger = LoggerFactory.getLogger(ExceptionLoggingHandler::class.java)

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("[$name] channel exception remote={}", ctx.channel().remoteAddress(), cause)
        ctx.fireExceptionCaught(cause)
    }
}
