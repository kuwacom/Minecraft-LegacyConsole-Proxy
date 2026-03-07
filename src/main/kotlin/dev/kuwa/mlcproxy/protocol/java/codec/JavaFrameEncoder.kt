package dev.kuwa.mlcproxy.protocol.java.codec

import dev.kuwa.mlcproxy.protocol.common.writeVarInt
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageEncoder
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class JavaFrameEncoder : MessageToMessageEncoder<ByteBuf>() {
    @Volatile
    private var compressionThreshold: Int = -1

    fun setCompressionThreshold(threshold: Int) {
        compressionThreshold = threshold
    }

    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        if (compressionThreshold >= 0) {
            encodeCompressed(ctx, msg, out)
            return
        }

        val framed = ctx.alloc().buffer(5 + msg.readableBytes())
        framed.writeVarInt(msg.readableBytes())
        framed.writeBytes(msg, msg.readerIndex(), msg.readableBytes())
        out.add(framed)
    }

    private fun encodeCompressed(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val readable = msg.readableBytes()
        if (readable < compressionThreshold) {
            val packetLength = varIntSize(0) + readable
            val framed = ctx.alloc().buffer(5 + packetLength)
            framed.writeVarInt(packetLength)
            framed.writeVarInt(0)
            framed.writeBytes(msg, msg.readerIndex(), readable)
            out.add(framed)
            return
        }

        val input = ByteArray(readable)
        msg.getBytes(msg.readerIndex(), input)
        val compressed = deflate(input)
        val packetLength = varIntSize(readable) + compressed.size
        val framed = ctx.alloc().buffer(5 + packetLength)
        framed.writeVarInt(packetLength)
        framed.writeVarInt(readable)
        framed.writeBytes(compressed)
        out.add(framed)
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val chunk = ByteArray(4096)
            val stream = ByteArrayOutputStream(input.size)
            var stallCount = 0
            while (!deflater.finished()) {
                val length = deflater.deflate(chunk)
                if (length > 0) {
                    stream.write(chunk, 0, length)
                    stallCount = 0
                    continue
                }
                stallCount++
                if (stallCount > 8) {
                    throw IllegalStateException("Deflater stalled while encoding Java frame")
                }
            }
            stream.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun varIntSize(value: Int): Int {
        var v = value
        var count = 1
        while ((v and -0x80) != 0) {
            count++
            v = v ushr 7
        }
        return count
    }
}
