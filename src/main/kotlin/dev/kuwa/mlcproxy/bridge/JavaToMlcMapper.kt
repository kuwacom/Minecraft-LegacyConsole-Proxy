package dev.kuwa.mlcproxy.bridge

import dev.kuwa.mlcproxy.protocol.common.decodeJavaPosition
import dev.kuwa.mlcproxy.protocol.common.readJavaString
import dev.kuwa.mlcproxy.protocol.common.readVarInt
import dev.kuwa.mlcproxy.protocol.common.writeLegacyUtf16
import dev.kuwa.mlcproxy.protocol.java.JavaPacket
import dev.kuwa.mlcproxy.protocol.java.JavaPacketId
import dev.kuwa.mlcproxy.protocol.mlc.MlcPacket
import dev.kuwa.mlcproxy.protocol.mlc.MlcPacketId
import dev.kuwa.mlcproxy.session.SessionState
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.floor
import java.util.zip.Deflater

class JavaToMlcMapper : PacketMapper<JavaPacket, MlcPacket> {
    private val logger = LoggerFactory.getLogger(JavaToMlcMapper::class.java)

    override fun map(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        if (
            context.javaConfigurationPhase &&
            !context.javaLoginSucceeded &&
            packet.id == JavaPacketId.LoginClientbound.LOGIN_SUCCESS
        ) {
            // Defensive guard: ignore duplicated/late login-success while already in configuration.
            return emptyList()
        }
        if (context.javaConfigurationPhase && !context.javaLoginSucceeded) {
            return mapConfigurationPhase(packet, context)
        }
        if (!context.javaLoginSucceeded) {
            return mapLoginPhase(packet, context)
        }
        return mapPlayPhase(packet, context)
    }

    private fun mapLoginPhase(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        return when (packet.id) {
            JavaPacketId.LoginClientbound.DISCONNECT -> {
                val rawReason = parseJavaDisconnectReason(packet.payload)
                val displayReason = rawReason.ifBlank { "<empty>" }
                logger.warn("Java login disconnect before success reason={}", displayReason)
                listOf(buildMlcDisconnect(reason = mapJavaDisconnectToMlcReason(displayReason)))
            }

            JavaPacketId.LoginClientbound.LOGIN_SUCCESS -> {
                if (context.protocolConfig.javaProtocolVersion >= MODERN_CONFIGURATION_PROTOCOL) {
                    context.javaConfigurationPhase = true
                    context.javaLoginSucceeded = false
                    logger.info("Java login success received (entering configuration phase)")
                } else {
                    context.javaLoginSucceeded = true
                    logger.info("Java login success received")
                }
                emptyList()
            }

            0x01 -> {
                logger.error("Java encryption request received. online-mode is not supported by this proxy")
                listOf(buildMlcDisconnect(reason = 8))
            }

            0x03 -> {
                logger.info("Java compression negotiation received")
                emptyList()
            }

            else -> {
                logger.debug(
                    "Ignoring Java login-phase packet id={} payloadSize={}",
                    packet.id,
                    packet.payload.size
                )
                emptyList()
            }
        }
    }

    private fun mapConfigurationPhase(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        return when (packet.id) {
            JavaPacketId.ConfigurationClientbound.DISCONNECT -> {
                val rawReason = parseJavaDisconnectReason(packet.payload)
                val displayReason = rawReason.ifBlank { "<empty>" }
                logger.warn("Java configuration disconnect reason={}", displayReason)
                listOf(buildMlcDisconnect(reason = mapJavaDisconnectToMlcReason(displayReason)))
            }

            else -> emptyList()
        }
    }

    private fun mapPlayPhase(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        if (isModernPlayProtocol(context)) {
            return mapPlayPhaseModern(packet, context)
        }

        return mapPlayPhaseLegacy(packet, context)
    }

    private fun mapPlayPhaseLegacy(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        return when (packet.id) {
            JavaPacketId.PlayClientbound.KEEP_ALIVE -> handleKeepAlive(packet, context)

            JavaPacketId.PlayClientbound.JOIN_GAME -> handleJoinGame(packet, context)

            JavaPacketId.PlayClientbound.CHAT_MESSAGE -> handleChatMessage(packet, context)

            JavaPacketId.PlayClientbound.TIME_UPDATE -> handleTimeUpdate(packet)

            JavaPacketId.PlayClientbound.SPAWN_POSITION -> handleSpawnPosition(packet)

            JavaPacketId.PlayClientbound.UPDATE_HEALTH -> handleUpdateHealth(packet)

            JavaPacketId.PlayClientbound.RESPAWN -> handleRespawn(packet, context)

            JavaPacketId.PlayClientbound.PLAYER_POSITION_LOOK -> handlePlayerPositionLook(packet, context)

            JavaPacketId.PlayClientbound.HELD_ITEM_CHANGE -> handleHeldItemChange(packet)

            JavaPacketId.PlayClientbound.PLAYER_ABILITIES -> handlePlayerAbilities(packet, context)

            JavaPacketId.PlayClientbound.PLUGIN_MESSAGE -> handlePluginMessage(packet)

            JavaPacketId.PlayClientbound.DISCONNECT -> {
                val rawReason = parseJavaDisconnectReason(packet.payload)
                val displayReason = rawReason.ifBlank { "<empty>" }
                logger.warn("Java play disconnect reason={}", displayReason)
                listOf(buildMlcDisconnect(reason = mapJavaDisconnectToMlcReason(displayReason)))
            }

            else -> emptyList()
        }
    }

    private fun mapPlayPhaseModern(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        return when (packet.id) {
            JavaPacketId.PlayClientbound.BUNDLE_DELIMITER_MODERN -> emptyList()
            JavaPacketId.PlayClientbound.KEEP_ALIVE_MODERN -> handleKeepAlive(packet, context)
            JavaPacketId.PlayClientbound.LOGIN_MODERN -> handleJoinGame(packet, context)
            JavaPacketId.PlayClientbound.SYSTEM_CHAT_MODERN -> handleChatMessage(packet, context)
            JavaPacketId.PlayClientbound.SET_TIME_MODERN -> handleTimeUpdate(packet)
            JavaPacketId.PlayClientbound.SET_DEFAULT_SPAWN_POSITION_MODERN -> handleSpawnPosition(packet)
            JavaPacketId.PlayClientbound.SET_HEALTH_MODERN -> handleUpdateHealth(packet)
            JavaPacketId.PlayClientbound.RESPAWN_MODERN -> handleRespawn(packet, context)
            JavaPacketId.PlayClientbound.PLAYER_POSITION_MODERN,
            JavaPacketId.PlayClientbound.PLAYER_ROTATION_MODERN -> handlePlayerPositionLook(packet, context)
            JavaPacketId.PlayClientbound.SET_HELD_SLOT_MODERN -> handleHeldItemChange(packet)
            JavaPacketId.PlayClientbound.PLAYER_ABILITIES_MODERN -> handlePlayerAbilities(packet, context)
            JavaPacketId.PlayClientbound.PLUGIN_MESSAGE_MODERN -> handlePluginMessage(packet)
            JavaPacketId.PlayClientbound.LEVEL_CHUNK_WITH_LIGHT_MODERN,
            JavaPacketId.PlayClientbound.LEVEL_CHUNK_WITH_LIGHT_MODERN_ALT -> handleModernChunkData(packet, context)
            JavaPacketId.PlayClientbound.FORGET_LEVEL_CHUNK_MODERN,
            JavaPacketId.PlayClientbound.FORGET_LEVEL_CHUNK_MODERN_ALT -> handleModernForgetChunk(packet, context)
            JavaPacketId.PlayClientbound.DISCONNECT_MODERN -> {
                val rawReason = parseJavaDisconnectReason(packet.payload)
                val displayReason = rawReason.ifBlank { "<empty>" }
                logger.warn("Java play disconnect reason={}", displayReason)
                listOf(buildMlcDisconnect(reason = mapJavaDisconnectToMlcReason(displayReason)))
            }

            else -> {
                if (packet.payload.size > 1024) {
                    logger.debug("Ignoring unknown modern play packet id={} payloadSize={}", packet.id, packet.payload.size)
                }
                emptyList()
            }
        }
    }

    private fun handleKeepAlive(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        val buf = Unpooled.wrappedBuffer(packet.payload)
        val keepAliveToken = if (isModernPlayProtocol(context)) {
            val javaKeepAliveId = if (buf.readableBytes() >= 8) buf.readLong() else 0L
            val token = allocateKeepAliveToken(context)
            context.javaKeepAliveByMlcToken[token] = javaKeepAliveId
            logger.debug("Mapped Java keepalive challenge={} -> mlcToken={}", javaKeepAliveId, token)
            token
        } else {
            runCatching { buf.readVarInt() }.getOrDefault(0)
        }

        val out = Unpooled.buffer(4)
        out.writeInt(keepAliveToken)
        return listOf(MlcPacket(MlcPacketId.KEEP_ALIVE, out.toByteArray()))
    }

    private fun allocateKeepAliveToken(context: MappingContext): Int {
        while (true) {
            val candidate = context.nextKeepAliveToken.getAndIncrement()
            if (candidate == 0) {
                continue
            }
            if (!context.javaKeepAliveByMlcToken.containsKey(candidate)) {
                return candidate
            }
        }
    }

    private fun handleJoinGame(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        if (isModernPlayProtocol(context)) {
            return handleModernJoinGame(packet, context)
        }

        val buf = Unpooled.wrappedBuffer(packet.payload)
        if (buf.readableBytes() < 8) return emptyList()

        val entityId = buf.readInt()
        val gameMode = buf.readUnsignedByte().toInt()
        val dimension = buf.readByte().toInt()
        val difficulty = buf.readUnsignedByte().toInt()
        val maxPlayers = buf.readUnsignedByte().toInt()
        val levelType = runCatching { buf.readJavaString(maxLength = 16) }.getOrDefault("default")

        context.javaEntityId = entityId
        context.javaGameMode = gameMode and 0x7
        context.javaDimension = dimension
        context.javaDifficulty = difficulty
        context.javaMaxPlayers = if (maxPlayers <= 0) 8 else maxPlayers
        context.javaLevelType = if (levelType.isBlank()) "default" else levelType
        context.sessionState = SessionState.PLAY

        if (context.mlcLoginSent) {
            return emptyList()
        }

        context.mlcLoginSent = true
        logger.info(
            "Java join game: entityId={} dim={} gm={} diff={} max={} levelType={}",
            entityId,
            dimension,
            context.javaGameMode,
            difficulty,
            context.javaMaxPlayers,
            context.javaLevelType
        )
        return listOf(buildMlcLogin(context))
    }

    private fun handleModernJoinGame(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        val buf = Unpooled.wrappedBuffer(packet.payload)
        if (buf.readableBytes() < 4) return emptyList()

        val entityId = buf.readInt()
        if (buf.isReadable) runCatching { buf.readBoolean() } // hardcore

        val dimensionCount = runCatching { buf.readVarInt() }.getOrDefault(0).coerceIn(0, 512)
        repeat(dimensionCount) {
            if (!buf.isReadable) return@repeat
            runCatching { buf.readJavaString(maxLength = 32767) }
        }

        val maxPlayers = runCatching { buf.readVarInt() }.getOrDefault(8)
        runCatching { buf.readVarInt() } // view distance
        runCatching { buf.readVarInt() } // simulation distance
        if (buf.isReadable) runCatching { buf.readBoolean() } // reduced debug info
        if (buf.isReadable) runCatching { buf.readBoolean() } // show death screen
        if (buf.isReadable) runCatching { buf.readBoolean() } // limited crafting

        runCatching { buf.readVarInt() } // dimension type
        val dimensionName = runCatching { buf.readJavaString(maxLength = 32767) }.getOrDefault("minecraft:overworld")
        if (buf.readableBytes() >= 8) runCatching { buf.readLong() } // hashed seed
        val gameMode = if (buf.isReadable) {
            runCatching { buf.readUnsignedByte().toInt() }.getOrDefault(0)
        } else {
            0
        }
        if (buf.isReadable) runCatching { buf.readByte() } // previous gamemode
        if (buf.isReadable) runCatching { buf.readBoolean() } // debug world
        if (buf.isReadable) runCatching { buf.readBoolean() } // flat world

        if (buf.isReadable) {
            val hasDeathLocation = runCatching { buf.readBoolean() }.getOrDefault(false)
            if (hasDeathLocation) {
                runCatching { buf.readJavaString(maxLength = 32767) }
                if (buf.readableBytes() >= 8) runCatching { buf.readLong() } // death position
            }
        }

        runCatching { buf.readVarInt() } // portal cooldown
        runCatching { buf.readVarInt() } // sea level
        if (buf.isReadable) runCatching { buf.readBoolean() } // enforce secure chat

        context.javaEntityId = entityId
        context.javaGameMode = gameMode and 0x7
        context.javaDimension = mapModernDimension(dimensionName)
        context.javaDifficulty = 1
        context.javaMaxPlayers = maxPlayers.coerceIn(1, 255)
        context.javaLevelType = "default"
        context.sessionState = SessionState.PLAY

        if (context.mlcLoginSent) {
            return emptyList()
        }

        context.mlcLoginSent = true
        logger.info(
            "Java modern login: entityId={} dimName={} dim={} gm={} max={}",
            entityId,
            dimensionName,
            context.javaDimension,
            context.javaGameMode,
            context.javaMaxPlayers
        )
        return listOf(buildMlcLogin(context))
    }

    private fun handleChatMessage(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        val buf = Unpooled.wrappedBuffer(packet.payload)
        val rawMessage = if (isModernPlayProtocol(context)) {
            // modern System Chat carries a component + overlay flag.
            runCatching { buf.readJavaString() }.getOrDefault("")
        } else {
            runCatching { buf.readJavaString() }.getOrDefault("")
        }
        val plainMessage = normalizeChat(rawMessage)
        if (plainMessage.isBlank()) return emptyList()

        val out = Unpooled.buffer()
        out.writeShort(0)
        out.writeShort((1 shl 4) or 0)
        out.writeLegacyUtf16(plainMessage.take(256))
        return listOf(MlcPacket(MlcPacketId.CHAT, out.toByteArray()))
    }

    private fun handleTimeUpdate(packet: JavaPacket): List<MlcPacket> {
        val buf = Unpooled.wrappedBuffer(packet.payload)
        if (buf.readableBytes() < 16) return emptyList()

        val worldAge = buf.readLong()
        val dayTime = buf.readLong()

        val out = Unpooled.buffer(16)
        out.writeLong(worldAge)
        out.writeLong(dayTime)
        return listOf(MlcPacket(MlcPacketId.SET_TIME, out.toByteArray()))
    }

    private fun handleSpawnPosition(packet: JavaPacket): List<MlcPacket> {
        val buf = Unpooled.wrappedBuffer(packet.payload)
        if (buf.readableBytes() < 8) return emptyList()

        val (x, y, z) = decodeJavaPosition(buf.readLong())

        val out = Unpooled.buffer(12)
        out.writeInt(x)
        out.writeInt(y)
        out.writeInt(z)
        return listOf(MlcPacket(MlcPacketId.SET_SPAWN_POSITION, out.toByteArray()))
    }

    private fun handleUpdateHealth(packet: JavaPacket): List<MlcPacket> {
        val buf = Unpooled.wrappedBuffer(packet.payload)
        if (buf.readableBytes() < 5) return emptyList()

        val health = buf.readFloat()
        val food = runCatching { buf.readVarInt() }.getOrDefault(20)
        val saturation = if (buf.readableBytes() >= 4) buf.readFloat() else 5.0f

        val out = Unpooled.buffer(11)
        out.writeFloat(health)
        out.writeShort(food.coerceIn(0, 65535))
        out.writeFloat(saturation)
        out.writeByte(0)
        return listOf(MlcPacket(MlcPacketId.SET_HEALTH, out.toByteArray()))
    }

    private fun handleRespawn(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        if (isModernPlayProtocol(context)) {
            return handleModernRespawn(packet, context)
        }

        val buf = Unpooled.wrappedBuffer(packet.payload)
        if (buf.readableBytes() < 7) return emptyList()

        val dimension = buf.readInt()
        val difficulty = buf.readUnsignedByte().toInt()
        val gameMode = buf.readUnsignedByte().toInt()
        val levelType = runCatching { buf.readJavaString(maxLength = 16) }.getOrDefault(context.javaLevelType)

        context.javaDimension = dimension
        context.javaDifficulty = difficulty
        context.javaGameMode = gameMode and 0x7
        context.javaLevelType = if (levelType.isBlank()) "default" else levelType

        return listOf(buildMlcRespawn(context))
    }

    private fun handleModernRespawn(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        val buf = Unpooled.wrappedBuffer(packet.payload)
        if (!buf.isReadable) return emptyList()

        runCatching { buf.readVarInt() } // dimension type
        val dimensionName = runCatching { buf.readJavaString(maxLength = 32767) }.getOrDefault("minecraft:overworld")
        if (buf.readableBytes() >= 8) {
            buf.readLong() // hashed seed
        }
        val gameMode = if (buf.isReadable) buf.readUnsignedByte().toInt() else context.javaGameMode
        if (buf.isReadable) {
            buf.readByte() // previous gamemode
        }
        if (buf.isReadable) buf.readBoolean() // debug world
        if (buf.isReadable) buf.readBoolean() // flat world
        if (buf.isReadable) {
            val hasDeathLocation = buf.readBoolean()
            if (hasDeathLocation) {
                runCatching { buf.readJavaString(maxLength = 32767) }
                if (buf.readableBytes() >= 8) {
                    buf.readLong()
                }
            }
        }

        context.javaDimension = mapModernDimension(dimensionName)
        context.javaGameMode = gameMode and 0x7
        context.javaLevelType = "default"
        return listOf(buildMlcRespawn(context))
    }

    private fun handlePlayerPositionLook(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        if (isModernPlayProtocol(context)) {
            return handleModernPlayerSync(packet, context)
        }

        val buf = Unpooled.wrappedBuffer(packet.payload)
        if (buf.readableBytes() < 33) return emptyList()

        var x = buf.readDouble()
        var y = buf.readDouble()
        var z = buf.readDouble()
        var yaw = buf.readFloat()
        var pitch = buf.readFloat()
        val flags = buf.readUnsignedByte().toInt()

        if ((flags and 0x01) != 0) x += context.playerX
        if ((flags and 0x02) != 0) y += context.playerY
        if ((flags and 0x04) != 0) z += context.playerZ
        if ((flags and 0x08) != 0) yaw += context.playerYaw
        if ((flags and 0x10) != 0) pitch += context.playerPitch

        context.playerX = x
        context.playerY = y
        context.playerZ = z
        context.playerYaw = yaw
        context.playerPitch = pitch

        val onGroundAndFlying = 0x01 or if (context.playerFlying) 0x02 else 0
        val out = Unpooled.buffer(41)
        out.writeDouble(x)
        out.writeDouble(y)
        out.writeDouble(y + 1.62)
        out.writeDouble(z)
        out.writeFloat(yaw)
        out.writeFloat(pitch)
        out.writeByte(onGroundAndFlying)
        return listOf(MlcPacket(MlcPacketId.MOVE_PLAYER_POS_ROT, out.toByteArray()))
    }

    private fun handleModernPlayerSync(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        return when (packet.id) {
            JavaPacketId.PlayClientbound.PLAYER_POSITION_MODERN -> handleModernPlayerPosition(packet, context)
            JavaPacketId.PlayClientbound.PLAYER_ROTATION_MODERN -> handleModernPlayerRotation(packet, context)
            else -> emptyList()
        }
    }

    private fun handleModernPlayerPosition(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        val buf = Unpooled.wrappedBuffer(packet.payload)
        if (buf.readableBytes() < 24) return emptyList()

        val deltaX = buf.readDouble()
        val deltaY = buf.readDouble()
        val deltaZ = buf.readDouble()
        runCatching { buf.readVarInt() } // teleport id (acked in BackendInboundHandler)
        if (buf.isReadable) buf.readBoolean() // dismount vehicle
        val relative = runCatching { buf.readVarInt() }.getOrDefault(0)

        val x = if ((relative and 0x01) != 0) context.playerX + deltaX else deltaX
        val y = if ((relative and 0x02) != 0) context.playerY + deltaY else deltaY
        val z = if ((relative and 0x04) != 0) context.playerZ + deltaZ else deltaZ

        context.playerX = x
        context.playerY = y
        context.playerZ = z

        val out = Unpooled.buffer(41)
        out.writeDouble(x)
        out.writeDouble(y)
        out.writeDouble(y + 1.62)
        out.writeDouble(z)
        out.writeFloat(context.playerYaw)
        out.writeFloat(context.playerPitch)
        out.writeByte(0x01 or if (context.playerFlying) 0x02 else 0)
        return listOf(MlcPacket(MlcPacketId.MOVE_PLAYER_POS_ROT, out.toByteArray()))
    }

    private fun handleModernPlayerRotation(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        val buf = Unpooled.wrappedBuffer(packet.payload)
        if (buf.readableBytes() < 8) return emptyList()

        val yawValue = buf.readFloat()
        val pitchValue = buf.readFloat()
        runCatching { buf.readVarInt() } // teleport id (acked in BackendInboundHandler)
        val relative = runCatching { buf.readVarInt() }.getOrDefault(0)

        val yaw = if ((relative and 0x08) != 0) context.playerYaw + yawValue else yawValue
        val pitch = if ((relative and 0x10) != 0) context.playerPitch + pitchValue else pitchValue

        context.playerYaw = yaw
        context.playerPitch = pitch

        val out = Unpooled.buffer(9)
        out.writeFloat(yaw)
        out.writeFloat(pitch)
        out.writeByte(0x01 or if (context.playerFlying) 0x02 else 0)
        return listOf(MlcPacket(MlcPacketId.MOVE_PLAYER_ROT, out.toByteArray()))
    }

    private fun handleModernChunkData(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        val buf = Unpooled.wrappedBuffer(packet.payload)
        if (buf.readableBytes() < 8) return emptyList()
        val chunkX = buf.readInt()
        val chunkZ = buf.readInt()
        val chunkKey = packChunkKey(chunkX, chunkZ)
        val centerChunkX = floor(context.playerX / 16.0).toInt()
        val centerChunkZ = floor(context.playerZ / 16.0).toInt()
        val nearPlayer =
            abs(chunkX - centerChunkX) <= MODERN_CHUNK_DATA_RADIUS &&
                abs(chunkZ - centerChunkZ) <= MODERN_CHUNK_DATA_RADIUS
        if (!nearPlayer) {
            return emptyList()
        }

        val firstVisibilityForChunk = context.visibleChunkKeys.putIfAbsent(chunkKey, true) == null
        val firstRegionForChunk = context.sentMlcRegionChunks.putIfAbsent(chunkKey, true) == null
        val budgetRemaining = context.sentMlcRegionChunkCount.get() < MAX_REGION_CHUNKS_PER_SESSION

        // Guardrail: legacy client is unstable under heavy full-region bursts.
        if (!firstVisibilityForChunk && (!firstRegionForChunk || !budgetRemaining)) {
            logger.debug(
                "Skipping chunk payload chunkX={} chunkZ={} firstVisibilityForChunk={} firstRegionForChunk={} budgetRemaining={}",
                chunkX,
                chunkZ,
                firstVisibilityForChunk,
                firstRegionForChunk,
                budgetRemaining
            )
            return emptyList()
        }

        val packets = mutableListOf<MlcPacket>()
        if (firstVisibilityForChunk) {
            packets += buildChunkVisibilityPacket(chunkX, chunkZ, visible = true)
        }
        if (firstRegionForChunk && budgetRemaining) {
            val regionPacket = buildEmptyChunkPacket(chunkX, chunkZ, context.javaDimension)
            packets += regionPacket
            val regionCount = context.sentMlcRegionChunkCount.incrementAndGet()
            logger.info(
                "Sent MLC region chunk sessionChunkCount={} chunkX={} chunkZ={}",
                regionCount,
                chunkX,
                chunkZ
            )
        }
        return packets
    }

    private fun handleModernForgetChunk(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        val buf = Unpooled.wrappedBuffer(packet.payload)
        if (buf.readableBytes() < 8) return emptyList()
        val chunkX = buf.readInt()
        val chunkZ = buf.readInt()
        val chunkKey = packChunkKey(chunkX, chunkZ)
        val wasVisible = context.visibleChunkKeys.remove(chunkKey) != null
        if (!wasVisible) {
            return emptyList()
        }
        return listOf(buildChunkVisibilityPacket(chunkX, chunkZ, visible = false))
    }

    private fun handleHeldItemChange(packet: JavaPacket): List<MlcPacket> {
        val buf = Unpooled.wrappedBuffer(packet.payload)
        if (!buf.isReadable) return emptyList()

        val slot = when {
            packet.id == JavaPacketId.PlayClientbound.SET_HELD_SLOT_MODERN -> {
                runCatching { buf.readVarInt() }.getOrElse { buf.readUnsignedByte().toInt() }
            }
            buf.readableBytes() >= 2 -> buf.readShort().toInt()
            else -> buf.readUnsignedByte().toInt()
        }

        val out = Unpooled.buffer(2)
        out.writeShort(slot)
        return listOf(MlcPacket(MlcPacketId.SET_CARRIED_ITEM, out.toByteArray()))
    }

    private fun handlePlayerAbilities(packet: JavaPacket, context: MappingContext): List<MlcPacket> {
        val buf = Unpooled.wrappedBuffer(packet.payload)
        if (buf.readableBytes() < 9) return emptyList()

        val flags = buf.readUnsignedByte().toInt()
        val flySpeed = buf.readFloat()
        val walkSpeed = buf.readFloat()

        context.playerFlying = (flags and 0x02) != 0

        val out = Unpooled.buffer(9)
        out.writeByte(flags)
        out.writeFloat(flySpeed)
        out.writeFloat(walkSpeed)
        return listOf(MlcPacket(MlcPacketId.PLAYER_ABILITIES, out.toByteArray()))
    }

    private fun handlePluginMessage(packet: JavaPacket): List<MlcPacket> {
        val buf = Unpooled.wrappedBuffer(packet.payload)
        val channel = runCatching { buf.readJavaString(maxLength = 20) }.getOrNull() ?: return emptyList()

        val data = ByteArray(buf.readableBytes())
        buf.readBytes(data)

        val out = Unpooled.buffer()
        out.writeLegacyUtf16(channel, maxLength = 20)
        out.writeShort(data.size)
        out.writeBytes(data)
        return listOf(MlcPacket(MlcPacketId.CUSTOM_PAYLOAD, out.toByteArray()))
    }

    private fun buildMlcLogin(context: MappingContext): MlcPacket {
        val out = Unpooled.buffer()
        // In server->client Login(1), first int is treated as local player entity id on client side.
        out.writeInt(context.javaEntityId)
        out.writeLegacyUtf16("", maxLength = 16)
        out.writeLegacyUtf16(context.javaLevelType.take(16), maxLength = 16)
        out.writeLong(0L)
        out.writeInt(context.javaGameMode)
        out.writeByte(context.javaDimension)
        out.writeByte(0) // 256 casted to byte in original implementation
        out.writeByte(context.javaMaxPlayers)
        out.writeLong(0L)
        out.writeLong(0L)
        out.writeBoolean(false)
        out.writeInt(0)
        out.writeByte(context.javaDifficulty)
        out.writeInt(0)
        out.writeByte(0)
        out.writeInt(0)
        out.writeInt(0)
        out.writeBoolean(false)
        out.writeBoolean(false)
        out.writeInt(0)
        out.writeShort(320)
        out.writeByte(8)
        return MlcPacket(MlcPacketId.LOGIN, out.toByteArray())
    }

    private fun buildMlcRespawn(context: MappingContext): MlcPacket {
        val out = Unpooled.buffer()
        out.writeByte(context.javaDimension)
        out.writeByte(context.javaGameMode)
        out.writeShort(128)
        out.writeLegacyUtf16(context.javaLevelType.take(16), maxLength = 16)
        out.writeLong(0L)
        out.writeByte(context.javaDifficulty)
        out.writeBoolean(false)
        out.writeShort(context.javaEntityId)
        out.writeShort(320)
        out.writeByte(8)
        return MlcPacket(MlcPacketId.RESPAWN, out.toByteArray())
    }

    private fun buildChunkVisibilityPacket(chunkX: Int, chunkZ: Int, visible: Boolean): MlcPacket {
        val out = Unpooled.buffer(9)
        out.writeInt(chunkX)
        out.writeInt(chunkZ)
        out.writeByte(if (visible) 1 else 0)
        return MlcPacket(MlcPacketId.CHUNK_VISIBILITY, out.toByteArray())
    }

    private fun buildEmptyChunkPacket(chunkX: Int, chunkZ: Int, javaDimension: Int): MlcPacket {
        val out = Unpooled.buffer(18)
        out.writeByte(0x01) // full chunk
        out.writeInt(chunkX shl 4)
        out.writeShort(0)
        out.writeInt(chunkZ shl 4)
        out.writeByte(15) // xs - 1
        out.writeByte(LEGACY_CHUNK_HEIGHT - 1) // ys - 1
        out.writeByte(15) // zs - 1
        val levelIdx = when (javaDimension) {
            -1 -> 1
            1 -> 2
            else -> 0
        }
        out.writeInt(levelIdx shl 30) // compressed size 0 + levelIdx
        return MlcPacket(MlcPacketId.BLOCK_REGION_UPDATE, out.toByteArray())
    }

    private fun buildTerrainApproxChunkPacket(
        chunkX: Int,
        chunkZ: Int,
        javaDimension: Int,
        surfaceHeights: IntArray
    ): MlcPacket {
        val blockCount = 16 * LEGACY_CHUNK_HEIGHT * 16
        val blocks = ByteArray(blockCount)
        val metadata = ByteArray(blockCount / 2)
        val blockLight = ByteArray(blockCount / 2)
        val skyLight = ByteArray(blockCount / 2) { 0xFF.toByte() }
        val biomes = ByteArray(16 * 16) { 1 } // plains

        for (z in 0 until 16) {
            for (x in 0 until 16) {
                val columnTop = surfaceHeights[z * 16 + x].coerceIn(0, LEGACY_CHUNK_HEIGHT - 1)
                for (y in 0..columnTop) {
                    val blockId = when {
                        y == 0 -> 7 // bedrock
                        y == columnTop -> 2 // grass
                        y >= columnTop - 3 -> 3 // dirt
                        else -> 1 // stone
                    }
                    blocks[legacyBlockIndex(x, y, z)] = blockId.toByte()
                }
            }
        }

        val raw = ByteArray(blocks.size + metadata.size + blockLight.size + skyLight.size + biomes.size)
        var cursor = 0
        System.arraycopy(blocks, 0, raw, cursor, blocks.size)
        cursor += blocks.size
        System.arraycopy(metadata, 0, raw, cursor, metadata.size)
        cursor += metadata.size
        System.arraycopy(blockLight, 0, raw, cursor, blockLight.size)
        cursor += blockLight.size
        System.arraycopy(skyLight, 0, raw, cursor, skyLight.size)
        cursor += skyLight.size
        System.arraycopy(biomes, 0, raw, cursor, biomes.size)

        val compressed = deflate(raw)
        val levelIdx = when (javaDimension) {
            -1 -> 1
            1 -> 2
            else -> 0
        }
        val payloadSize = compressed.size and 0x3FFF_FFFF
        val out = Unpooled.buffer(18 + compressed.size)
        out.writeByte(0x01) // full chunk
        out.writeInt(chunkX shl 4)
        out.writeShort(0)
        out.writeInt(chunkZ shl 4)
        out.writeByte(15) // xs - 1
        out.writeByte(LEGACY_CHUNK_HEIGHT - 1) // ys - 1
        out.writeByte(15) // zs - 1
        out.writeInt((levelIdx shl 30) or payloadSize)
        out.writeBytes(compressed)
        return MlcPacket(MlcPacketId.BLOCK_REGION_UPDATE, out.toByteArray())
    }

    private fun legacyBlockIndex(x: Int, y: Int, z: Int): Int {
        return (y shl 8) or (z shl 4) or x
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val stream = ByteArrayOutputStream(input.size / 2)
            val chunk = ByteArray(4096)
            var stallCount = 0
            while (!deflater.finished()) {
                val len = deflater.deflate(chunk)
                if (len > 0) {
                    stream.write(chunk, 0, len)
                    stallCount = 0
                    continue
                }
                stallCount++
                if (stallCount > 8) {
                    throw IllegalStateException("Deflater stalled while encoding MLC region payload")
                }
            }
            stream.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun decodeModernChunkSurfaceHeights(buf: ByteBuf): IntArray? {
        val heightmap = readHeightmapLongArrayFromNbt(buf) ?: return null
        if (heightmap.isEmpty()) return null
        val heights = IntArray(256)
        for (idx in 0 until 256) {
            val value = unpackFixedBits(heightmap, idx, HEIGHTMAP_BITS)
            val worldY = (value + MODERN_MIN_Y - 1).coerceIn(MODERN_MIN_Y, MODERN_MAX_Y)
            heights[idx] = worldY
        }
        return heights
    }

    private fun readHeightmapLongArrayFromNbt(buf: ByteBuf): LongArray? {
        if (!buf.isReadable) return null
        val rootType = buf.readUnsignedByte().toInt()
        if (rootType != NBT_TAG_COMPOUND) return null

        val rootNameLen = if (buf.readableBytes() >= 2) buf.readUnsignedShort() else return null
        if (buf.readableBytes() < rootNameLen) return null
        if (rootNameLen > 0) buf.skipBytes(rootNameLen)

        var fallback: LongArray? = null
        while (buf.isReadable) {
            val tagType = buf.readUnsignedByte().toInt()
            if (tagType == NBT_TAG_END) {
                return fallback
            }

            val nameLen = if (buf.readableBytes() >= 2) buf.readUnsignedShort() else return fallback
            if (buf.readableBytes() < nameLen) return fallback
            val nameBytes = ByteArray(nameLen)
            buf.readBytes(nameBytes)
            val tagName = String(nameBytes, Charsets.UTF_8)

            if (tagType == NBT_TAG_LONG_ARRAY) {
                val arr = readNbtLongArray(buf) ?: return fallback
                if (tagName == "WORLD_SURFACE" || tagName == "MOTION_BLOCKING_NO_LEAVES" || tagName == "MOTION_BLOCKING") {
                    return arr
                }
                if (fallback == null) {
                    fallback = arr
                }
            } else {
                if (!skipNbtTagPayload(tagType, buf)) {
                    return fallback
                }
            }
        }

        return fallback
    }

    private fun readNbtLongArray(buf: ByteBuf): LongArray? {
        if (buf.readableBytes() < 4) return null
        val length = buf.readInt()
        if (length < 0 || length > 4096) return null
        if (buf.readableBytes() < length * 8) return null
        val out = LongArray(length)
        for (i in 0 until length) {
            out[i] = buf.readLong()
        }
        return out
    }

    private fun skipNbtTagPayload(tagType: Int, buf: ByteBuf): Boolean {
        return when (tagType) {
            1 -> skipBytesSafe(buf, 1)
            2 -> skipBytesSafe(buf, 2)
            3 -> skipBytesSafe(buf, 4)
            4 -> skipBytesSafe(buf, 8)
            5 -> skipBytesSafe(buf, 4)
            6 -> skipBytesSafe(buf, 8)
            7 -> {
                if (buf.readableBytes() < 4) return false
                val len = buf.readInt()
                if (len < 0) return false
                skipBytesSafe(buf, len)
            }
            8 -> {
                if (buf.readableBytes() < 2) return false
                val len = buf.readUnsignedShort()
                skipBytesSafe(buf, len)
            }
            9 -> {
                if (buf.readableBytes() < 5) return false
                val elemType = buf.readUnsignedByte().toInt()
                val len = buf.readInt()
                if (len < 0) return false
                repeat(len) {
                    if (!skipNbtTagPayload(elemType, buf)) return false
                }
                true
            }
            10 -> {
                while (buf.isReadable) {
                    val childType = buf.readUnsignedByte().toInt()
                    if (childType == NBT_TAG_END) {
                        return true
                    }
                    if (buf.readableBytes() < 2) return false
                    val nameLen = buf.readUnsignedShort()
                    if (!skipBytesSafe(buf, nameLen)) return false
                    if (!skipNbtTagPayload(childType, buf)) return false
                }
                false
            }
            11 -> {
                if (buf.readableBytes() < 4) return false
                val len = buf.readInt()
                if (len < 0) return false
                skipBytesSafe(buf, len * 4)
            }
            12 -> {
                if (buf.readableBytes() < 4) return false
                val len = buf.readInt()
                if (len < 0) return false
                skipBytesSafe(buf, len * 8)
            }
            else -> false
        }
    }

    private fun skipBytesSafe(buf: ByteBuf, len: Int): Boolean {
        if (len < 0 || buf.readableBytes() < len) return false
        if (len > 0) buf.skipBytes(len)
        return true
    }

    private fun unpackFixedBits(values: LongArray, index: Int, bits: Int): Int {
        val bitIndex = index * bits
        val longIndex = bitIndex ushr 6
        val offset = bitIndex and 63
        val mask = (1L shl bits) - 1L
        var value = (values.getOrElse(longIndex) { 0L } ushr offset) and mask
        val spill = offset + bits - 64
        if (spill > 0) {
            val extra = values.getOrElse(longIndex + 1) { 0L } and ((1L shl spill) - 1L)
            value = value or (extra shl (bits - spill))
        }
        return value.toInt()
    }

    private fun packChunkKey(chunkX: Int, chunkZ: Int): Long {
        return (chunkX.toLong() shl 32) xor (chunkZ.toLong() and 0xFFFF_FFFFL)
    }

    private fun buildMlcDisconnect(reason: Int): MlcPacket {
        val out = Unpooled.buffer(4)
        out.writeInt(reason)
        return MlcPacket(MlcPacketId.DISCONNECT, out.toByteArray())
    }

    private fun mapModernDimension(dimensionName: String): Int {
        return when {
            "the_nether" in dimensionName -> -1
            "the_end" in dimensionName -> 1
            else -> 0
        }
    }

    private fun parseJavaDisconnectReason(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val json = runCatching { Unpooled.wrappedBuffer(payload).readJavaString() }.getOrNull()
        if (json.isNullOrBlank()) {
            return "binary(${payload.size}):${payload.toHex(maxBytes = 96)}"
        }
        return extractDisconnectText(json)
    }

    private fun extractDisconnectText(json: String): String {
        val textMatch = TEXT_FIELD_REGEX.find(json)?.groupValues?.getOrNull(1)
        if (!textMatch.isNullOrBlank()) {
            return unescapeJsonString(textMatch)
        }

        val translateKey = TRANSLATE_FIELD_REGEX.find(json)?.groupValues?.getOrNull(1)
        if (!translateKey.isNullOrBlank()) {
            val argText = WITH_ARRAY_REGEX.find(json)?.groupValues?.getOrNull(1).orEmpty()
            val compactArgs = argText.replace("\"", "").replace("\\s+".toRegex(), " ").trim()
            return if (compactArgs.isBlank()) translateKey else "$translateKey ($compactArgs)"
        }

        return json
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun unescapeJsonString(value: String): String {
        return value
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\n", " ")
            .replace("\\r", " ")
            .replace("\\t", " ")
            .trim()
    }

    private fun mapJavaDisconnectToMlcReason(reason: String): Int {
        val normalized = reason.lowercase()
        return when {
            "outdated server" in normalized -> 13 // eDisconnect_OutdatedServer
            "outdated client" in normalized -> 14 // eDisconnect_OutdatedClient
            "outdated_server" in normalized -> 13
            "outdated_client" in normalized -> 14
            "server full" in normalized -> 12 // eDisconnect_ServerFull
            "banned" in normalized -> 25 // eDisconnect_Banned
            else -> 8 // eDisconnect_Kicked
        }
    }

    private fun normalizeChat(raw: String): String {
        return raw
            .replace("\\n", " ")
            .replace("{", "")
            .replace("}", "")
            .replace("\"", "")
            .ifBlank { "" }
    }

    private fun ByteArray.toHex(maxBytes: Int): String {
        val len = minOf(size, maxBytes)
        val body = (0 until len).joinToString("") { idx -> "%02x".format(this[idx]) }
        return if (size > len) "$body..." else body
    }

    private fun ByteBuf.toByteArray(): ByteArray {
        val bytes = ByteArray(readableBytes())
        getBytes(readerIndex(), bytes)
        return bytes
    }

    private fun isModernPlayProtocol(context: MappingContext): Boolean {
        return context.protocolConfig.javaProtocolVersion >= MODERN_PLAY_PROTOCOL
    }

    companion object {
        private val TEXT_FIELD_REGEX = Regex("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        private val TRANSLATE_FIELD_REGEX = Regex("\"translate\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        private val WITH_ARRAY_REGEX = Regex("\"with\"\\s*:\\s*\\[(.*?)]")
        private const val MODERN_CONFIGURATION_PROTOCOL = 764 // 1.20.2+
        private const val MODERN_PLAY_PROTOCOL = 764 // 1.20.2+
        private const val MODERN_MIN_Y = -64
        private const val MODERN_MAX_Y = 319
        private const val HEIGHTMAP_BITS = 9
        private const val MODERN_CHUNK_DATA_RADIUS = 1
        private const val MAX_REGION_CHUNKS_PER_SESSION = 4
        private const val LEGACY_CHUNK_HEIGHT = 128
        private const val NBT_TAG_END = 0
        private const val NBT_TAG_COMPOUND = 10
        private const val NBT_TAG_LONG_ARRAY = 12
    }
}
