package dev.kuwa.mlcproxy.protocol.java.codec

import dev.kuwa.mlcproxy.protocol.common.readVarInt
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

class JavaFrameDecoder(
    private val maxFrameLength: Int = 2 * 1024 * 1024
) : ByteToMessageDecoder() {
    @Volatile
    private var compressionThreshold: Int = -1

    fun setCompressionThreshold(threshold: Int) {
        compressionThreshold = threshold
    }

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

        if (compressionThreshold < 0) {
            out.add(`in`.readRetainedSlice(length))
            return
        }

        val frame = `in`.readBytes(length)
        try {
            val uncompressedLength = frame.readVarInt()
            if (uncompressedLength == 0) {
                out.add(frame.readRetainedSlice(frame.readableBytes()))
                return
            }

            if (uncompressedLength < 0 || uncompressedLength > maxFrameLength) {
                throw IllegalStateException("Invalid Java decompressed length: $uncompressedLength")
            }

            val compressed = ByteArray(frame.readableBytes())
            frame.readBytes(compressed)
            val uncompressed = inflateExact(compressed, uncompressedLength)
            out.add(Unpooled.wrappedBuffer(uncompressed))
        } finally {
            frame.release()
        }
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

    private fun inflateExact(compressed: ByteArray, expectedLength: Int): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val out = ByteArray(expectedLength)
            var offset = 0
            while (!inflater.finished() && offset < expectedLength) {
                val read = inflater.inflate(out, offset, expectedLength - offset)
                if (read <= 0) break
                offset += read
            }

            if (offset != expectedLength || !inflater.finished()) {
                throw IllegalStateException("Invalid Java compressed payload: expected=$expectedLength actual=$offset")
            }
            out
        } catch (e: DataFormatException) {
            throw IllegalStateException("Failed to inflate Java packet payload", e)
        } finally {
            inflater.end()
        }
    }
}
