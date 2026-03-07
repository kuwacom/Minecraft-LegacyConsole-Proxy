package dev.kuwa.mlcproxy.protocol.common

import io.netty.buffer.ByteBuf
import java.nio.charset.StandardCharsets

fun ByteBuf.readVarInt(maxBytes: Int = 5): Int {
    var numRead = 0
    var result = 0
    var read: Int
    do {
        if (!isReadable) {
            throw IllegalStateException("Not enough bytes to read VarInt")
        }
        read = readByte().toInt()
        val value = read and 0x7F
        result = result or (value shl (7 * numRead))

        numRead++
        if (numRead > maxBytes) {
            throw IllegalStateException("VarInt is too big")
        }
    } while ((read and 0x80) != 0)

    return result
}

fun ByteBuf.writeVarInt(value: Int) {
    var v = value
    do {
        var temp = (v and 0x7F)
        v = v ushr 7
        if (v != 0) {
            temp = temp or 0x80
        }
        writeByte(temp)
    } while (v != 0)
}

fun ByteBuf.readJavaString(maxLength: Int = 32767): String {
    val length = readVarInt()
    require(length >= 0) { "String length must be >= 0" }
    require(length <= maxLength * 4) { "String byte length too large: $length" }
    require(readableBytes() >= length) { "Not enough bytes for Java string: need $length, had ${readableBytes()}" }

    val bytes = ByteArray(length)
    readBytes(bytes)
    return String(bytes, StandardCharsets.UTF_8)
}

fun ByteBuf.writeJavaString(value: String, maxLength: Int = 32767) {
    require(value.length <= maxLength) { "String char length too large: ${value.length}" }
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= maxLength * 4) { "String byte length too large: ${bytes.size}" }
    writeVarInt(bytes.size)
    writeBytes(bytes)
}

fun ByteBuf.readDataUtfLike(): String {
    require(readableBytes() >= 2) { "Not enough bytes for UTF-like string length" }
    val byteLength = readUnsignedShort()
    require(readableBytes() >= byteLength) { "Not enough bytes for UTF-like string payload" }
    val bytes = ByteArray(byteLength)
    readBytes(bytes)
    return String(bytes, StandardCharsets.UTF_8)
}

fun ByteBuf.writeDataUtfLike(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= 0xFFFF) { "UTF-like string too large: ${bytes.size}" }
    writeShort(bytes.size)
    writeBytes(bytes)
}

fun ByteBuf.readLegacyUtf16(maxLength: Int = 32767): String {
    require(readableBytes() >= 2) { "Not enough bytes for legacy UTF length" }
    val charLength = readShort().toInt()
    require(charLength >= 0) { "Legacy UTF char length must be >= 0: $charLength" }
    require(charLength <= maxLength) { "Legacy UTF char length too large: $charLength > $maxLength" }
    require(readableBytes() >= charLength * 2) { "Not enough bytes for legacy UTF payload" }

    val builder = StringBuilder(charLength)
    repeat(charLength) {
        builder.append(readChar())
    }
    return builder.toString()
}

fun ByteBuf.writeLegacyUtf16(value: String, maxLength: Int = 32767) {
    require(value.length <= maxLength) { "Legacy UTF char length too large: ${value.length}" }
    writeShort(value.length)
    value.forEach { ch -> writeChar(ch.code) }
}

fun encodeJavaPosition(x: Int, y: Int, z: Int): Long {
    return ((x.toLong() and 0x3FFFFFFL) shl 38) or
        ((y.toLong() and 0xFFFL) shl 26) or
        (z.toLong() and 0x3FFFFFFL)
}

fun decodeJavaPosition(value: Long): Triple<Int, Int, Int> {
    var x = (value shr 38).toInt()
    var y = ((value shr 26) and 0xFFFL).toInt()
    var z = (value shl 38 shr 38).toInt()

    if (x >= 1 shl 25) x -= 1 shl 26
    if (y >= 1 shl 11) y -= 1 shl 12
    if (z >= 1 shl 25) z -= 1 shl 26

    return Triple(x, y, z)
}
