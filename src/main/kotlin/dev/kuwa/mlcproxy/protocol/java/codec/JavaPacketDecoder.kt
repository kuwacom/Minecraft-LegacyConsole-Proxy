package dev.kuwa.mlcproxy.protocol.java.codec

import dev.kuwa.mlcproxy.protocol.common.readVarInt
import dev.kuwa.mlcproxy.protocol.java.JavaPacket
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageDecoder

class JavaPacketDecoder : MessageToMessageDecoder<ByteBuf>() {
    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        if (!msg.isReadable) {
            return
        }

        val packetId = msg.readVarInt()
        val payload = ByteArray(msg.readableBytes())
        msg.readBytes(payload)
        out.add(JavaPacket(packetId, payload))
    }
}
