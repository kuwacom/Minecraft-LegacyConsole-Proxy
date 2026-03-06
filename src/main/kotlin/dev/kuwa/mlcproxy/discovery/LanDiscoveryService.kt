package dev.kuwa.mlcproxy.discovery

import dev.kuwa.mlcproxy.config.Config
import dev.kuwa.mlcproxy.session.SessionManager
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class LanDiscoveryService(
    private val config: Config,
    private val sessionManager: SessionManager
) {
    private val logger = LoggerFactory.getLogger(LanDiscoveryService::class.java)

    private val discoveryConfig = config.discovery
    private val group = NioEventLoopGroup(1)
    private var channel: Channel? = null
    private var broadcastTask: ScheduledFuture<*>? = null

    fun start() {
        if (!discoveryConfig.enabled) {
            logger.info("LAN discovery is disabled by config")
            return
        }
        if (channel != null) return

        val bootstrap = Bootstrap()
            .group(group)
            .channel(NioDatagramChannel::class.java)
            .option(ChannelOption.SO_BROADCAST, true)
            .option(ChannelOption.SO_REUSEADDR, true)
            .handler(LanDiscoveryInboundHandler(this))

        channel = bootstrap.bind(discoveryConfig.port).syncUninterruptibly().channel()
        schedulePeriodicBroadcast()

        logger.info(
            "LAN discovery started on UDP {} (interval={}ms, respondToQueries={})",
            discoveryConfig.port,
            discoveryConfig.advertiseIntervalMs,
            discoveryConfig.respondToQueries
        )
    }

    fun stop() {
        broadcastTask?.cancel(false)
        broadcastTask = null

        channel?.close()?.syncUninterruptibly()
        channel = null

        group.shutdownGracefully().syncUninterruptibly()
        logger.info("LAN discovery stopped")
    }

    fun handleDatagram(ctx: ChannelHandlerContext, packet: DatagramPacket) {
        if (!discoveryConfig.respondToQueries) return

        val content = packet.content()
        if (!Win64LanBroadcastCodec.shouldTreatAsDiscoveryQuery(content)) {
            return
        }

        sendAnnouncementTo(packet.sender())
        logger.debug(
            "LAN discovery query handled from {}:{} ({} bytes)",
            packet.sender().address.hostAddress,
            packet.sender().port,
            content.readableBytes()
        )
    }

    private fun schedulePeriodicBroadcast() {
        val activeChannel = channel ?: return
        val eventLoop = activeChannel.eventLoop()
        val target = InetSocketAddress("255.255.255.255", discoveryConfig.port)

        broadcastTask = eventLoop.scheduleAtFixedRate(
            { sendAnnouncementTo(target) },
            0L,
            discoveryConfig.advertiseIntervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun sendAnnouncementTo(target: InetSocketAddress) {
        val activeChannel = channel ?: return
        if (!activeChannel.isActive) return

        val payload = Win64LanBroadcastCodec.encode(snapshotBroadcastPacket())
        val datagram = DatagramPacket(Unpooled.wrappedBuffer(payload), target)
        activeChannel.writeAndFlush(datagram)
    }

    private fun snapshotBroadcastPacket(): Win64LanBroadcastPacket {
        val activeSessions = sessionManager.activeSessionCount()
        val reportedPlayers = (activeSessions + 1).coerceAtLeast(1).coerceAtMost(discoveryConfig.maxPlayers)
        val joinable = reportedPlayers < discoveryConfig.maxPlayers

        return Win64LanBroadcastPacket(
            netVersion = config.protocol.mlcNetVersion,
            gamePort = config.proxy.listenPort,
            hostName = discoveryConfig.hostName,
            playerCount = reportedPlayers,
            maxPlayers = discoveryConfig.maxPlayers,
            gameHostSettings = discoveryConfig.gameHostSettings,
            texturePackParentId = discoveryConfig.texturePackParentId,
            subTexturePackId = discoveryConfig.subTexturePackId,
            isJoinable = joinable
        )
    }
}
