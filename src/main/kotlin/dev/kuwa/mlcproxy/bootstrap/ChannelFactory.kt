package dev.kuwa.mlcproxy.bootstrap

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel

object ChannelFactory {
    fun server(
        bossGroup: EventLoopGroup,
        workerGroup: EventLoopGroup,
        childInitializer: ChannelInitializer<SocketChannel>
    ): ServerBootstrap {
        return ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(childInitializer)
            .childOption(ChannelOption.AUTO_READ, true)
    }

    fun client(
        eventLoopGroup: EventLoopGroup,
        initializer: ChannelInitializer<SocketChannel>
    ): Bootstrap {
        return Bootstrap()
            .group(eventLoopGroup)
            .channel(NioSocketChannel::class.java)
            .handler(initializer)
            .option(ChannelOption.AUTO_READ, true)
    }
}
