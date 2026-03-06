package dev.kuwa.mlcproxy.discovery

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.DatagramPacket
import org.slf4j.LoggerFactory

class LanDiscoveryInboundHandler(
    private val service: LanDiscoveryService
) : SimpleChannelInboundHandler<DatagramPacket>() {
    private val logger = LoggerFactory.getLogger(LanDiscoveryInboundHandler::class.java)

    override fun channelRead0(ctx: ChannelHandlerContext, msg: DatagramPacket) {
        try {
            service.handleDatagram(ctx, msg)
        } catch (e: Exception) {
            logger.warn("Failed to handle LAN discovery datagram from {}", msg.sender(), e)
        }
    }
}
