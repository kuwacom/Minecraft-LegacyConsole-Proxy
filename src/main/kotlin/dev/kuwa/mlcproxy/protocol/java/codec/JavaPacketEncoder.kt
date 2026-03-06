package dev.kuwa.mlcproxy.protocol.java.codec

import dev.kuwa.mlcproxy.protocol.common.writeVarInt
import dev.kuwa.mlcproxy.protocol.java.JavaPacket
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageEncoder

class JavaPacketEncoder : MessageToMessageEncoder<JavaPacket>() {
    override fun encode(ctx: ChannelHandlerContext, msg: JavaPacket, out: MutableList<Any>) {
        val buf = ctx.alloc().buffer(5 + msg.payload.size)
        buf.writeVarInt(msg.id)
        buf.writeBytes(msg.payload)
        out.add(buf)
    }
}
