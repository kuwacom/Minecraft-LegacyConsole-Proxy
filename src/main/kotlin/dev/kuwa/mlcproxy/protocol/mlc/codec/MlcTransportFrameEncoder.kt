package dev.kuwa.mlcproxy.protocol.mlc.codec

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageEncoder

class MlcTransportFrameEncoder(
    private val mode: MlcTransportMode
) : MessageToMessageEncoder<ByteBuf>() {

    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        when (mode) {
            MlcTransportMode.LENGTH_PREFIXED_32BE -> {
                val framed = ctx.alloc().buffer(4 + msg.readableBytes())
                framed.writeInt(msg.readableBytes())
                framed.writeBytes(msg, msg.readerIndex(), msg.readableBytes())
                out.add(framed)
            }

            MlcTransportMode.RAW -> out.add(msg.retain())
        }
    }
}
