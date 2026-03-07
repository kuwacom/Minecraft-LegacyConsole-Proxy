package dev.kuwa.mlcproxy.bridge

import dev.kuwa.mlcproxy.config.Config
import dev.kuwa.mlcproxy.protocol.common.encodeJavaPosition
import dev.kuwa.mlcproxy.protocol.common.readDataUtfLike
import dev.kuwa.mlcproxy.protocol.common.readLegacyUtf16
import dev.kuwa.mlcproxy.protocol.common.writeJavaString
import dev.kuwa.mlcproxy.protocol.common.writeVarInt
import dev.kuwa.mlcproxy.protocol.java.JavaPacket
import dev.kuwa.mlcproxy.protocol.java.JavaPacketId
import dev.kuwa.mlcproxy.protocol.mlc.MlcPacket
import dev.kuwa.mlcproxy.protocol.mlc.MlcPacketId
import dev.kuwa.mlcproxy.session.SessionState
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.max

class MlcToJavaMapper(
    private val config: Config
) : PacketMapper<MlcPacket, JavaPacket> {
    private val logger = LoggerFactory.getLogger(MlcToJavaMapper::class.java)

    override fun map(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        return when (packet.id) {
            MlcPacketId.PRE_LOGIN -> handlePreLogin(packet, context)
            MlcPacketId.LOGIN -> handleLogin(packet, context)
            MlcPacketId.KEEP_ALIVE -> handleKeepAlive(packet, context)
            MlcPacketId.CHAT -> handleChat(packet, context)
            MlcPacketId.INTERACT -> handleInteract(packet, context)
            MlcPacketId.MOVE_PLAYER -> handleMovePlayerFlags(packet, context)
            MlcPacketId.MOVE_PLAYER_POS_ROT,
            MlcPacketId.MOVE_PLAYER_POS,
            MlcPacketId.MOVE_PLAYER_ROT -> handleMovePlayer(packet, context)
            MlcPacketId.PLAYER_ACTION -> handlePlayerAction(packet, context)
            MlcPacketId.USE_ITEM -> handleUseItem(packet, context)
            MlcPacketId.SET_CARRIED_ITEM -> handleSetCarriedItem(packet, context)
            MlcPacketId.ANIMATE -> handleAnimate(packet, context)
            MlcPacketId.PLAYER_COMMAND -> handlePlayerCommand(packet, context)
            MlcPacketId.PLAYER_INPUT -> handlePlayerInput(packet, context)
            MlcPacketId.PLAYER_ABILITIES -> handlePlayerAbilities(packet, context)
            MlcPacketId.CLIENT_COMMAND -> handleClientCommand(packet, context)
            MlcPacketId.CONTAINER_CLOSE -> handleContainerClose(packet, context)
            MlcPacketId.CONTAINER_CLICK -> handleContainerClick(packet, context)
            MlcPacketId.CONTAINER_ACK -> handleContainerAck(packet, context)
            MlcPacketId.SET_CREATIVE_MODE_SLOT -> handleCreativeModeSlot(packet, context)
            MlcPacketId.CONTAINER_BUTTON_CLICK -> handleContainerButtonClick(packet, context)
            MlcPacketId.SIGN_UPDATE -> handleSignUpdate(packet, context)
            MlcPacketId.CUSTOM_PAYLOAD -> handleCustomPayload(packet, context)
            MlcPacketId.DISCONNECT -> emptyList()
            else -> emptyList()
        }
    }

    private fun handlePreLogin(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (packet.payload.size >= 2) {
            val buf = Unpooled.wrappedBuffer(packet.payload)
            val netVersion = buf.readShort().toInt()
            if (netVersion != config.protocol.mlcNetVersion) {
                if (netVersion == 560) {
                    logger.info(
                        "MLC net version differs from config, continuing with client value expected={} actual={}",
                        config.protocol.mlcNetVersion,
                        netVersion
                    )
                } else {
                    logger.warn(
                        "MLC net version mismatch: expected={} actual={}",
                        config.protocol.mlcNetVersion,
                        netVersion
                    )
                }
            }
        }

        context.preLoginReceived = true
        context.sessionState = SessionState.PRELOGIN
        return emptyList()
    }

    private fun handleLogin(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        context.loginReceived = true
        context.sessionState = SessionState.LOGIN

        if (context.javaLoginStarted) {
            return emptyList()
        }

        val loginInfo = parseMlcLogin(packet.payload)
        if (loginInfo != null && loginInfo.clientVersion != context.protocolConfig.mlcGameProtocolVersion) {
            logger.warn(
                "MLC game protocol version mismatch: expected={} actual={}",
                context.protocolConfig.mlcGameProtocolVersion,
                loginInfo.clientVersion
            )
        }

        val playerName = loginInfo?.playerName ?: parsePlayerName(packet.payload)
        context.playerName = playerName
        context.javaLoginStarted = true
        logger.info(
            "MLC login received player={} clientVersion={}",
            playerName,
            loginInfo?.clientVersion ?: -1
        )

        return listOf(
            buildJavaHandshake(context),
            buildJavaLoginStart(playerName, context)
        )
    }

    private fun handleKeepAlive(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) {
            return emptyList()
        }

        if (packet.payload.size < 4) {
            return emptyList()
        }

        val mlcToken = Unpooled.wrappedBuffer(packet.payload).readInt()
        val payload = Unpooled.buffer()
        return if (isModernPlayProtocol(context)) {
            val javaKeepAlive = context.javaKeepAliveByMlcToken.remove(mlcToken)
            if (javaKeepAlive == null) {
                logger.warn("Dropping unmatched MLC keepalive token={} (no Java challenge)", mlcToken)
                return emptyList()
            }
            payload.writeLong(javaKeepAlive)
            listOf(JavaPacket(JavaPacketId.PlayServerbound.KEEP_ALIVE_MODERN, payload.toByteArray()))
        } else {
            payload.writeVarInt(mlcToken)
            listOf(JavaPacket(JavaPacketId.PlayServerbound.KEEP_ALIVE, payload.toByteArray()))
        }
    }

    private fun handleChat(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (isModernPlayProtocol(context)) {
            // 1.19+ chat packet needs signatures/timestamps. Drop legacy chat to avoid backend decode errors.
            logger.debug("Dropping legacy chat packet for modern Java protocol")
            return emptyList()
        }

        val buf = Unpooled.wrappedBuffer(packet.payload)
        val message = runCatching { parseMlcChatMessage(buf) }.getOrNull() ?: return emptyList()
        if (message.isBlank()) return emptyList()

        val payload = Unpooled.buffer()
        payload.writeJavaString(message.take(256))
        return listOf(JavaPacket(JavaPacketId.PlayServerbound.CHAT_MESSAGE, payload.toByteArray()))
    }

    private fun handleInteract(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (isModernPlayProtocol(context)) return emptyList()

        if (packet.payload.size < 9) return emptyList()
        val buf = Unpooled.wrappedBuffer(packet.payload)
        buf.readInt() // source entity id (unused in Java serverbound)
        val target = buf.readInt()
        val action = buf.readByte().toInt()

        val javaType = if (action == 1) 1 else 0
        val payload = Unpooled.buffer()
        payload.writeVarInt(target)
        payload.writeVarInt(javaType)
        return listOf(JavaPacket(JavaPacketId.PlayServerbound.USE_ENTITY, payload.toByteArray()))
    }

    private fun handleMovePlayerFlags(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (packet.payload.size < 1) return emptyList()

        val flags = Unpooled.wrappedBuffer(packet.payload).readUnsignedByte().toInt()
        val onGround = (flags and 0x01) != 0
        context.playerFlying = (flags and 0x02) != 0

        val payload = Unpooled.buffer()
        return if (isModernPlayProtocol(context)) {
            payload.writeByte(if (onGround) 0x01 else 0x00)
            listOf(JavaPacket(JavaPacketId.PlayServerbound.PLAYER_MODERN, payload.toByteArray()))
        } else {
            payload.writeBoolean(onGround)
            listOf(JavaPacket(JavaPacketId.PlayServerbound.PLAYER, payload.toByteArray()))
        }
    }

    private fun handleMovePlayer(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) {
            return emptyList()
        }

        val buf = Unpooled.wrappedBuffer(packet.payload)
        return when (packet.id) {
            MlcPacketId.MOVE_PLAYER_POS_ROT -> {
                val javaPayload = tryBuildPosLookPayload(buf, context) ?: return emptyList()
                val packetId = if (isModernPlayProtocol(context)) {
                    JavaPacketId.PlayServerbound.PLAYER_POSITION_ROTATION_MODERN
                } else {
                    JavaPacketId.PlayServerbound.PLAYER_POSITION_LOOK
                }
                listOf(JavaPacket(packetId, javaPayload))
            }

            MlcPacketId.MOVE_PLAYER_POS -> {
                val javaPayload = tryBuildPosPayload(buf, context) ?: return emptyList()
                val packetId = if (isModernPlayProtocol(context)) {
                    JavaPacketId.PlayServerbound.PLAYER_POSITION_MODERN
                } else {
                    JavaPacketId.PlayServerbound.PLAYER_POSITION
                }
                listOf(JavaPacket(packetId, javaPayload))
            }

            MlcPacketId.MOVE_PLAYER_ROT -> {
                val javaPayload = tryBuildLookPayload(buf, context) ?: return emptyList()
                val packetId = if (isModernPlayProtocol(context)) {
                    JavaPacketId.PlayServerbound.PLAYER_ROTATION_MODERN
                } else {
                    JavaPacketId.PlayServerbound.PLAYER_LOOK
                }
                listOf(JavaPacket(packetId, javaPayload))
            }

            else -> emptyList()
        }
    }

    private fun handlePlayerAction(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (isModernPlayProtocol(context)) {
            // Modern digging packet has additional sequence fields and changed semantics.
            return emptyList()
        }
        if (packet.payload.size < 11) return emptyList()

        val buf = Unpooled.wrappedBuffer(packet.payload)
        val action = buf.readUnsignedByte().toInt()
        val x = buf.readInt()
        val y = buf.readUnsignedByte().toInt()
        val z = buf.readInt()
        val face = buf.readUnsignedByte().toInt()

        val status = when (action) {
            0 -> 0
            1 -> 1
            2 -> 2
            3 -> 3
            4 -> 4
            5 -> 5
            else -> return emptyList()
        }

        val payload = Unpooled.buffer()
        payload.writeVarInt(status)
        payload.writeLong(encodeJavaPosition(x, y, z))
        payload.writeByte(face)
        return listOf(JavaPacket(JavaPacketId.PlayServerbound.PLAYER_DIGGING, payload.toByteArray()))
    }

    private fun handleUseItem(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (isModernPlayProtocol(context)) {
            // Modern use-item-on has changed shape (hand/sequence/inside-block). Not bridged yet.
            return emptyList()
        }

        val buf = Unpooled.wrappedBuffer(packet.payload)
        if (buf.readableBytes() < 11) return emptyList()

        val x = buf.readInt()
        val y = buf.readUnsignedByte().toInt()
        val z = buf.readInt()
        val faceRaw = buf.readByte().toInt()

        val legacySlot = parseLegacyItemSlot(buf) ?: return emptyList()

        if (buf.readableBytes() < 3) return emptyList()
        val clickX = buf.readUnsignedByte().toInt()
        val clickY = buf.readUnsignedByte().toInt()
        val clickZ = buf.readUnsignedByte().toInt()

        val payload = Unpooled.buffer()
        payload.writeLong(encodeJavaPosition(x, y, z))
        payload.writeVarInt(faceRaw and 0xFF)
        writeJavaSlot(payload, legacySlot)
        payload.writeByte(clickX)
        payload.writeByte(clickY)
        payload.writeByte(clickZ)

        return listOf(JavaPacket(JavaPacketId.PlayServerbound.PLAYER_BLOCK_PLACEMENT, payload.toByteArray()))
    }

    private fun handleSetCarriedItem(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (packet.payload.size < 2) return emptyList()

        val slot = Unpooled.wrappedBuffer(packet.payload).readShort().toInt()
        val payload = Unpooled.buffer()
        payload.writeShort(slot)
        val packetId = if (isModernPlayProtocol(context)) {
            JavaPacketId.PlayServerbound.HELD_ITEM_CHANGE_MODERN
        } else {
            JavaPacketId.PlayServerbound.HELD_ITEM_CHANGE
        }
        return listOf(JavaPacket(packetId, payload.toByteArray()))
    }

    private fun handleAnimate(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (isModernPlayProtocol(context)) return emptyList()

        val action = if (packet.payload.size >= 5) {
            Unpooled.wrappedBuffer(packet.payload).apply {
                readInt() // entity id
                readUnsignedByte().toInt()
            }
        } else {
            1
        }

        if (action != 1) return emptyList()
        return listOf(JavaPacket(JavaPacketId.PlayServerbound.ANIMATION, ByteArray(0)))
    }

    private fun handlePlayerCommand(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (isModernPlayProtocol(context)) return emptyList()
        if (packet.payload.size < 9) return emptyList()

        val buf = Unpooled.wrappedBuffer(packet.payload)
        val entityId = buf.readInt()
        val action = buf.readByte().toInt()
        val data = buf.readInt()

        val mappedAction = when (action) {
            1 -> 0
            2 -> 1
            3 -> 2
            4 -> 3
            5 -> 4
            8 -> 5
            9 -> 6
            else -> return emptyList()
        }

        val payload = Unpooled.buffer()
        payload.writeVarInt(if (entityId != 0) entityId else context.javaEntityId)
        payload.writeVarInt(mappedAction)
        payload.writeVarInt(data.coerceAtLeast(0))
        return listOf(JavaPacket(JavaPacketId.PlayServerbound.ENTITY_ACTION, payload.toByteArray()))
    }

    private fun handlePlayerInput(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (isModernPlayProtocol(context)) return emptyList()
        if (packet.payload.size < 10) return emptyList()

        val buf = Unpooled.wrappedBuffer(packet.payload)
        val sideways = buf.readFloat()
        val forward = buf.readFloat()
        val jumping = buf.readBoolean()
        val sneaking = buf.readBoolean()

        val flags = (if (jumping) 0x01 else 0) or (if (sneaking) 0x02 else 0)
        val payload = Unpooled.buffer()
        payload.writeFloat(sideways)
        payload.writeFloat(forward)
        payload.writeByte(flags)
        return listOf(JavaPacket(JavaPacketId.PlayServerbound.STEER_VEHICLE, payload.toByteArray()))
    }

    private fun handlePlayerAbilities(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (packet.payload.size < 9) return emptyList()

        val buf = Unpooled.wrappedBuffer(packet.payload)
        val flags = buf.readUnsignedByte().toInt()
        val flySpeed = buf.readFloat()
        val walkSpeed = buf.readFloat()

        context.playerFlying = (flags and 0x02) != 0

        val payload = Unpooled.buffer()
        payload.writeByte(flags)
        payload.writeFloat(flySpeed)
        payload.writeFloat(walkSpeed)
        val packetId = if (isModernPlayProtocol(context)) {
            JavaPacketId.PlayServerbound.PLAYER_ABILITIES_MODERN
        } else {
            JavaPacketId.PlayServerbound.PLAYER_ABILITIES
        }
        return listOf(JavaPacket(packetId, payload.toByteArray()))
    }

    private fun handleClientCommand(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (packet.payload.isEmpty()) return emptyList()

        val action = Unpooled.wrappedBuffer(packet.payload).readUnsignedByte().toInt()
        val javaAction = when (action) {
            1 -> 0
            else -> 0
        }

        val payload = Unpooled.buffer()
        payload.writeVarInt(javaAction)
        val packetId = if (isModernPlayProtocol(context)) {
            JavaPacketId.PlayServerbound.CLIENT_STATUS_MODERN
        } else {
            JavaPacketId.PlayServerbound.CLIENT_STATUS
        }
        return listOf(JavaPacket(packetId, payload.toByteArray()))
    }

    private fun handleContainerClose(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (isModernPlayProtocol(context)) return emptyList()
        if (packet.payload.isEmpty()) return emptyList()

        val windowId = Unpooled.wrappedBuffer(packet.payload).readUnsignedByte().toInt()
        val payload = Unpooled.buffer(1)
        payload.writeByte(windowId)
        return listOf(JavaPacket(JavaPacketId.PlayServerbound.CLOSE_WINDOW, payload.toByteArray()))
    }

    private fun handleContainerClick(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (isModernPlayProtocol(context)) return emptyList()
        if (packet.payload.size < 7) return emptyList()

        val buf = Unpooled.wrappedBuffer(packet.payload)
        val windowId = buf.readUnsignedByte().toInt()
        val slot = buf.readShort().toInt()
        val button = buf.readUnsignedByte().toInt()
        val actionNumber = buf.readShort().toInt()
        val mode = buf.readUnsignedByte().toInt()
        val item = parseLegacyItemSlot(buf) ?: return emptyList()

        val payload = Unpooled.buffer()
        payload.writeByte(windowId)
        payload.writeShort(slot)
        payload.writeByte(button)
        payload.writeShort(actionNumber)
        payload.writeByte(mode)
        writeJavaSlot(payload, item)
        return listOf(JavaPacket(JavaPacketId.PlayServerbound.CLICK_WINDOW, payload.toByteArray()))
    }

    private fun handleContainerAck(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (isModernPlayProtocol(context)) return emptyList()
        if (packet.payload.size < 4) return emptyList()

        val buf = Unpooled.wrappedBuffer(packet.payload)
        val windowId = buf.readUnsignedByte().toInt()
        val uid = buf.readShort().toInt()
        val accepted = buf.readUnsignedByte().toInt() != 0

        val payload = Unpooled.buffer(4)
        payload.writeByte(windowId)
        payload.writeShort(uid)
        payload.writeBoolean(accepted)
        return listOf(JavaPacket(JavaPacketId.PlayServerbound.CONFIRM_TRANSACTION, payload.toByteArray()))
    }

    private fun handleCreativeModeSlot(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (isModernPlayProtocol(context)) return emptyList()
        if (packet.payload.size < 2) return emptyList()

        val buf = Unpooled.wrappedBuffer(packet.payload)
        val slot = buf.readShort().toInt()
        val item = parseLegacyItemSlot(buf) ?: return emptyList()

        val payload = Unpooled.buffer()
        payload.writeShort(slot)
        writeJavaSlot(payload, item)
        return listOf(JavaPacket(JavaPacketId.PlayServerbound.CREATIVE_INVENTORY_ACTION, payload.toByteArray()))
    }

    private fun handleContainerButtonClick(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (isModernPlayProtocol(context)) return emptyList()
        if (packet.payload.size < 2) return emptyList()

        val buf = Unpooled.wrappedBuffer(packet.payload)
        val windowId = buf.readUnsignedByte().toInt()
        val button = buf.readUnsignedByte().toInt()

        val payload = Unpooled.buffer(2)
        payload.writeByte(windowId)
        payload.writeByte(button)
        return listOf(JavaPacket(JavaPacketId.PlayServerbound.ENCHANT_ITEM, payload.toByteArray()))
    }

    private fun handleSignUpdate(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (!context.javaLoginSucceeded) return emptyList()
        if (isModernPlayProtocol(context)) return emptyList()
        if (packet.payload.size < 13) return emptyList()

        val buf = Unpooled.wrappedBuffer(packet.payload)
        val x = buf.readInt()
        val y = buf.readShort().toInt()
        val z = buf.readInt()
        if (buf.readableBytes() < 2) return emptyList()
        buf.readBoolean() // verified
        buf.readBoolean() // censored

        val lines = mutableListOf<String>()
        repeat(4) {
            if (!buf.isReadable) return emptyList()
            lines += runCatching { buf.readLegacyUtf16(maxLength = 15) }.getOrNull() ?: ""
        }

        val payload = Unpooled.buffer()
        payload.writeLong(encodeJavaPosition(x, y, z))
        lines.forEach { line ->
            payload.writeJavaString(line, maxLength = 15)
        }
        return listOf(JavaPacket(JavaPacketId.PlayServerbound.UPDATE_SIGN, payload.toByteArray()))
    }

    private fun handleCustomPayload(packet: MlcPacket, context: MappingContext): List<JavaPacket> {
        if (isModernPlayProtocol(context)) {
            // Legacy custom payload mapping is 1.8 shape (channel + short length).
            return emptyList()
        }

        val buf = Unpooled.wrappedBuffer(packet.payload)
        return runCatching {
            val channel = buf.readLegacyUtf16(maxLength = 20)
            if (buf.readableBytes() < 2) {
                return emptyList()
            }
            val dataLength = buf.readUnsignedShort().toInt()
            if (buf.readableBytes() < dataLength) {
                return emptyList()
            }
            val data = ByteArray(dataLength)
            buf.readBytes(data)

            val payload = Unpooled.buffer()
            payload.writeJavaString(channel, maxLength = 20)
            payload.writeShort(data.size)
            payload.writeBytes(data)
            JavaPacket(JavaPacketId.PlayServerbound.PLUGIN_MESSAGE, payload.toByteArray())
        }.map { listOf(it) }.getOrElse {
            logger.debug("Failed to map MLC custom payload: {}", it.message)
            emptyList()
        }
    }

    private fun buildJavaHandshake(context: MappingContext): JavaPacket {
        val payload = Unpooled.buffer()
        payload.writeVarInt(context.protocolConfig.javaProtocolVersion)
        payload.writeJavaString(context.protocolConfig.javaHandshakeHost)
        payload.writeShort(context.protocolConfig.javaHandshakePort)
        payload.writeVarInt(2)
        return JavaPacket(JavaPacketId.Handshake.HANDSHAKE, payload.toByteArray())
    }

    private fun buildJavaLoginStart(playerName: String, context: MappingContext): JavaPacket {
        val payload = Unpooled.buffer()
        val normalizedName = playerName.take(16)
        payload.writeJavaString(normalizedName, maxLength = 16)

        // 1.20.5+ uses Login Start (hello) with required UUID.
        if (context.protocolConfig.javaProtocolVersion >= MODERN_LOGIN_START_UUID_PROTOCOL) {
            val profileId = createOfflineUuid(normalizedName)
            payload.writeLong(profileId.mostSignificantBits)
            payload.writeLong(profileId.leastSignificantBits)
            logger.info(
                "Using modern Java login-start format protocol={} name={} uuid={}",
                context.protocolConfig.javaProtocolVersion,
                normalizedName,
                profileId
            )
        }

        return JavaPacket(JavaPacketId.LoginServerbound.LOGIN_START, payload.toByteArray())
    }

    private fun createOfflineUuid(playerName: String): UUID {
        return UUID.nameUUIDFromBytes("OfflinePlayer:$playerName".toByteArray(StandardCharsets.UTF_8))
    }

    private fun parsePlayerName(payload: ByteArray): String {
        if (payload.isEmpty()) return "Player"

        val buf = Unpooled.wrappedBuffer(payload)
        val parsed = runCatching { buf.readLegacyUtf16(maxLength = 16) }.getOrNull()
        if (!parsed.isNullOrBlank()) {
            return parsed.take(16)
        }

        val utfFallback = runCatching { Unpooled.wrappedBuffer(payload).readDataUtfLike() }.getOrNull()
        if (!utfFallback.isNullOrBlank()) {
            return utfFallback.take(16)
        }

        val fallback = payload
            .map { b -> b.toInt().toChar() }
            .filter { it.isLetterOrDigit() || it == '_' }
            .joinToString("")
            .ifBlank { "Player" }

        return fallback.take(max(3, minOf(fallback.length, 16)))
    }

    private data class ParsedMlcLogin(
        val clientVersion: Int,
        val playerName: String
    )

    private fun parseMlcLogin(payload: ByteArray): ParsedMlcLogin? {
        if (payload.size < 6) return null
        val buf = Unpooled.wrappedBuffer(payload)
        val version = runCatching { buf.readInt() }.getOrNull() ?: return null
        val playerName = runCatching { buf.readLegacyUtf16(maxLength = 16) }.getOrNull() ?: return null
        return ParsedMlcLogin(version, playerName.take(16))
    }

    private fun parseMlcChatMessage(buf: ByteBuf): String {
        if (buf.readableBytes() < 4) return ""

        val messageType = buf.readShort().toInt()
        val packedCounts = buf.readShort().toInt()
        val stringCount = (packedCounts shr 4) and 0xF
        val intCount = packedCounts and 0xF

        val strings = mutableListOf<String>()
        repeat(stringCount) {
            strings += buf.readLegacyUtf16(maxLength = 32767)
        }

        repeat(intCount) {
            if (buf.readableBytes() >= 4) {
                buf.readInt()
            }
        }

        if (messageType == 0 && strings.isNotEmpty()) {
            return strings.first()
        }
        return strings.firstOrNull().orEmpty()
    }

    private fun tryBuildPosLookPayload(buf: ByteBuf, context: MappingContext): ByteArray? {
        if (buf.readableBytes() < 41) return null

        val x = buf.readDouble()
        val y = buf.readDouble()
        val yView = buf.readDouble()
        val z = buf.readDouble()
        val yaw = buf.readFloat()
        val pitch = buf.readFloat()
        val flags = buf.readUnsignedByte().toInt()
        val onGround = (flags and 0x01) != 0
        context.playerFlying = (flags and 0x02) != 0

        context.playerX = x
        context.playerY = y
        context.playerZ = z
        context.playerYaw = yaw
        context.playerPitch = pitch

        return encodePosLook(x, y, yView, z, yaw, pitch, onGround, isModernPlayProtocol(context))
    }

    private fun tryBuildPosPayload(buf: ByteBuf, context: MappingContext): ByteArray? {
        if (buf.readableBytes() < 33) return null

        val x = buf.readDouble()
        val y = buf.readDouble()
        val yView = buf.readDouble()
        val z = buf.readDouble()
        val flags = buf.readUnsignedByte().toInt()
        val onGround = (flags and 0x01) != 0
        context.playerFlying = (flags and 0x02) != 0

        context.playerX = x
        context.playerY = y
        context.playerZ = z

        return encodePos(x, y, yView, z, onGround, isModernPlayProtocol(context))
    }

    private fun tryBuildLookPayload(buf: ByteBuf, context: MappingContext): ByteArray? {
        if (buf.readableBytes() < 9) return null

        val yaw = buf.readFloat()
        val pitch = buf.readFloat()
        val flags = buf.readUnsignedByte().toInt()
        val onGround = (flags and 0x01) != 0
        context.playerFlying = (flags and 0x02) != 0

        context.playerYaw = yaw
        context.playerPitch = pitch

        return encodeLook(yaw, pitch, onGround, isModernPlayProtocol(context))
    }

    private data class LegacyItemSlot(
        val itemId: Int,
        val count: Int,
        val damage: Int
    )

    private fun parseLegacyItemSlot(buf: ByteBuf): LegacyItemSlot? {
        if (buf.readableBytes() < 2) return null
        val itemId = buf.readShort().toInt()
        if (itemId < 0) {
            return LegacyItemSlot(-1, 0, 0)
        }

        if (buf.readableBytes() < 1 + 2 + 2) return null
        val count = buf.readUnsignedByte().toInt()
        val damage = buf.readShort().toInt()
        val nbtLength = buf.readShort().toInt()
        if (nbtLength >= 0) {
            if (buf.readableBytes() < nbtLength) return null
            buf.skipBytes(nbtLength)
        }

        return LegacyItemSlot(itemId, count, damage)
    }

    private fun writeJavaSlot(buf: ByteBuf, slot: LegacyItemSlot) {
        if (slot.itemId < 0) {
            buf.writeShort(-1)
            return
        }

        buf.writeShort(slot.itemId)
        buf.writeByte(slot.count)
        buf.writeShort(slot.damage)
        buf.writeShort(-1)
    }

    private fun encodePosLook(
        x: Double,
        y: Double,
        yView: Double,
        z: Double,
        yaw: Float,
        pitch: Float,
        onGround: Boolean,
        modern: Boolean
    ): ByteArray {
        val out = Unpooled.buffer(33)
        out.writeDouble(x)
        out.writeDouble(y)
        out.writeDouble(z)
        out.writeFloat(yaw)
        out.writeFloat(pitch)
        if (modern) {
            out.writeByte(if (onGround) 0x01 else 0x00)
        } else {
            out.writeBoolean(onGround)
        }
        return out.toByteArray()
    }

    private fun encodePos(
        x: Double,
        y: Double,
        yView: Double,
        z: Double,
        onGround: Boolean,
        modern: Boolean
    ): ByteArray {
        val out = Unpooled.buffer(25)
        out.writeDouble(x)
        out.writeDouble(y)
        out.writeDouble(z)
        if (modern) {
            out.writeByte(if (onGround) 0x01 else 0x00)
        } else {
            out.writeBoolean(onGround)
        }
        return out.toByteArray()
    }

    private fun encodeLook(yaw: Float, pitch: Float, onGround: Boolean, modern: Boolean): ByteArray {
        val out = Unpooled.buffer(9)
        out.writeFloat(yaw)
        out.writeFloat(pitch)
        if (modern) {
            out.writeByte(if (onGround) 0x01 else 0x00)
        } else {
            out.writeBoolean(onGround)
        }
        return out.toByteArray()
    }

    private fun ByteBuf.toByteArray(): ByteArray {
        val bytes = ByteArray(readableBytes())
        getBytes(readerIndex(), bytes)
        return bytes
    }

    companion object {
        private const val MODERN_LOGIN_START_UUID_PROTOCOL = 766 // Minecraft 1.20.5+
        private const val MODERN_PLAY_PROTOCOL = 764 // Minecraft 1.20.2+
    }

    private fun isModernPlayProtocol(context: MappingContext): Boolean {
        return context.protocolConfig.javaProtocolVersion >= MODERN_PLAY_PROTOCOL
    }
}
