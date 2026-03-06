package dev.kuwa.mlcproxy.protocol.mlc.codec

import dev.kuwa.mlcproxy.protocol.mlc.MlcPacket
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageEncoder

class MlcPacketEncoder : MessageToMessageEncoder<MlcPacket>() {
    override fun encode(ctx: ChannelHandlerContext, msg: MlcPacket, out: MutableList<Any>) {
        val buf = ctx.alloc().buffer(1 + msg.payload.size)
        buf.writeByte(msg.id)
        buf.writeBytes(msg.payload)
        out.add(buf)
    }
}
