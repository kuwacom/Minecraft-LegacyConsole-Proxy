package dev.kuwa.mlcproxy.protocol.java.codec

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder

class JavaFrameDecoder(
    private val maxFrameLength: Int = 2 * 1024 * 1024
) : ByteToMessageDecoder() {
    override fun decode(ctx: ChannelHandlerContext, `in`: ByteBuf, out: MutableList<Any>) {
        if (!`in`.isReadable) {
            return
        }

        `in`.markReaderIndex()
        val length = tryReadVarInt(`in`) ?: run {
            `in`.resetReaderIndex()
            return
        }

        if (length <= 0 || length > maxFrameLength) {
            throw IllegalStateException("Invalid Java frame length: $length")
        }

        if (`in`.readableBytes() < length) {
            `in`.resetReaderIndex()
            return
        }

        out.add(`in`.readRetainedSlice(length))
    }

    private fun tryReadVarInt(buf: ByteBuf): Int? {
        var numRead = 0
        var result = 0
        while (numRead < 5) {
            if (!buf.isReadable) {
                return null
            }

            val read = buf.readByte().toInt()
            val value = read and 0x7F
            result = result or (value shl (7 * numRead))
            numRead++

            if ((read and 0x80) == 0) {
                return result
            }
        }

        throw IllegalStateException("Java frame length VarInt is too big")
    }
}
