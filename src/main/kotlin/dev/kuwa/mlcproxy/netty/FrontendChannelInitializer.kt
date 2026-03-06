package dev.kuwa.mlcproxy.netty

import dev.kuwa.mlcproxy.bootstrap.UpstreamConnector
import dev.kuwa.mlcproxy.bridge.MlcToJavaMapper
import dev.kuwa.mlcproxy.config.Config
import dev.kuwa.mlcproxy.protocol.mlc.codec.MlcPacketDecoder
import dev.kuwa.mlcproxy.protocol.mlc.codec.MlcPacketEncoder
import dev.kuwa.mlcproxy.protocol.mlc.codec.MlcTransportFrameDecoder
import dev.kuwa.mlcproxy.protocol.mlc.codec.MlcTransportFrameEncoder
import dev.kuwa.mlcproxy.protocol.mlc.codec.MlcTransportMode
import dev.kuwa.mlcproxy.session.SessionManager
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel

class FrontendChannelInitializer(
    private val config: Config,
    private val sessionManager: SessionManager,
    private val upstreamConnector: UpstreamConnector,
    private val mlcToJavaMapper: MlcToJavaMapper
) : ChannelInitializer<SocketChannel>() {
    override fun initChannel(ch: SocketChannel) {
        val transportMode = MlcTransportMode.fromConfig(config.protocol.mlcTransportMode)
        ch.pipeline()
            .addLast("mlc-exception", ExceptionLoggingHandler("frontend"))
            .addLast("mlc-frame-decoder", MlcTransportFrameDecoder(transportMode))
            .addLast("mlc-packet-decoder", MlcPacketDecoder())
            .addLast("mlc-packet-encoder", MlcPacketEncoder())
            .addLast("mlc-frame-encoder", MlcTransportFrameEncoder(transportMode))
            .addLast(
                "mlc-inbound",
                FrontendInboundHandler(config, sessionManager, upstreamConnector, mlcToJavaMapper)
            )
    }
}
