package dev.kuwa.mlcproxy.protocol.mlc.codec

import dev.kuwa.mlcproxy.protocol.mlc.MlcPacket
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageDecoder

class MlcPacketDecoder : MessageToMessageDecoder<ByteBuf>() {
    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        if (!msg.isReadable) {
            return
        }

        val packetId = msg.readUnsignedByte().toInt()
        val payload = ByteArray(msg.readableBytes())
        msg.readBytes(payload)

        out.add(MlcPacket(packetId, payload))
    }
}
