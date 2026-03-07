package dev.kuwa.mlcproxy.netty

import dev.kuwa.mlcproxy.bridge.JavaToMlcMapper
import dev.kuwa.mlcproxy.protocol.java.codec.JavaFrameDecoder
import dev.kuwa.mlcproxy.protocol.java.codec.JavaFrameEncoder
import dev.kuwa.mlcproxy.protocol.java.codec.JavaPacketDecoder
import dev.kuwa.mlcproxy.protocol.java.codec.JavaPacketEncoder
import dev.kuwa.mlcproxy.session.ProxySession
import dev.kuwa.mlcproxy.session.SessionManager
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel

class BackendChannelInitializer(
    private val session: ProxySession,
    private val sessionManager: SessionManager,
    private val javaToMlcMapper: JavaToMlcMapper
) : ChannelInitializer<SocketChannel>() {
    override fun initChannel(ch: SocketChannel) {
        ch.pipeline()
            .addLast("java-exception", ExceptionLoggingHandler("backend"))
            .addLast("java-frame-decoder", JavaFrameDecoder())
            .addLast("java-packet-decoder", JavaPacketDecoder())
            .addLast("java-frame-encoder", JavaFrameEncoder())
            .addLast("java-packet-encoder", JavaPacketEncoder())
            .addLast("java-inbound", BackendInboundHandler(session, sessionManager, javaToMlcMapper))
    }
}
