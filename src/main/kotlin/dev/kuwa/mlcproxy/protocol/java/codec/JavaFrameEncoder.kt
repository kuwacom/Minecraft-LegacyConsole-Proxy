package dev.kuwa.mlcproxy.protocol.java.codec

import dev.kuwa.mlcproxy.protocol.common.writeVarInt
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageEncoder

class JavaFrameEncoder : MessageToMessageEncoder<ByteBuf>() {
    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val framed = ctx.alloc().buffer(5 + msg.readableBytes())
        framed.writeVarInt(msg.readableBytes())
        framed.writeBytes(msg, msg.readerIndex(), msg.readableBytes())
        out.add(framed)
    }
}
