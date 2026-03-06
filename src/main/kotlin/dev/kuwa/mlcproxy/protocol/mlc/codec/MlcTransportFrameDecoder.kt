package dev.kuwa.mlcproxy.protocol.mlc.codec

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder

class MlcTransportFrameDecoder(
    private val mode: MlcTransportMode,
    private val maxFrameLength: Int = 2 * 1024 * 1024
) : ByteToMessageDecoder() {

    override fun decode(ctx: ChannelHandlerContext, `in`: ByteBuf, out: MutableList<Any>) {
        when (mode) {
            MlcTransportMode.LENGTH_PREFIXED_32BE -> decodeLengthPrefixed(`in`, out)
            MlcTransportMode.RAW -> {
                if (!`in`.isReadable) {
                    return
                }
                out.add(`in`.readRetainedSlice(`in`.readableBytes()))
            }
        }
    }

    private fun decodeLengthPrefixed(`in`: ByteBuf, out: MutableList<Any>) {
        if (`in`.readableBytes() < 4) {
            return
        }

        `in`.markReaderIndex()
        val frameLength = `in`.readInt()
        if (frameLength <= 0 || frameLength > maxFrameLength) {
            throw IllegalStateException("Invalid MLC frame length: $frameLength")
        }
        if (`in`.readableBytes() < frameLength) {
            `in`.resetReaderIndex()
            return
        }

        out.add(`in`.readRetainedSlice(frameLength))
    }
}
