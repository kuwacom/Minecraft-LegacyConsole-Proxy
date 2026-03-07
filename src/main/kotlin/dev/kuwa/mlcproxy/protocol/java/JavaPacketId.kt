package dev.kuwa.mlcproxy.protocol.java

/**
 * Java Edition protocol 47 (1.8.x) を前提にした主要 packet ID
 */
object JavaPacketId {
    object Handshake {
        const val HANDSHAKE = 0x00
    }

    object LoginServerbound {
        const val LOGIN_START = 0x00
        const val LOGIN_ACKNOWLEDGED = 0x03
    }

    object LoginClientbound {
        const val DISCONNECT = 0x00
        const val LOGIN_SUCCESS = 0x02
        const val SET_COMPRESSION = 0x03
    }

    object PlayServerbound {
        // Legacy 1.8.x
        const val KEEP_ALIVE = 0x00
        const val CHAT_MESSAGE = 0x01
        const val USE_ENTITY = 0x02
        const val PLAYER = 0x03
        const val PLAYER_POSITION = 0x04
        const val PLAYER_LOOK = 0x05
        const val PLAYER_POSITION_LOOK = 0x06
        const val PLAYER_DIGGING = 0x07
        const val PLAYER_BLOCK_PLACEMENT = 0x08
        const val HELD_ITEM_CHANGE = 0x09
        const val ANIMATION = 0x0A
        const val ENTITY_ACTION = 0x0B
        const val STEER_VEHICLE = 0x0C
        const val CLOSE_WINDOW = 0x0D
        const val CLICK_WINDOW = 0x0E
        const val CONFIRM_TRANSACTION = 0x0F
        const val CREATIVE_INVENTORY_ACTION = 0x10
        const val ENCHANT_ITEM = 0x11
        const val UPDATE_SIGN = 0x12
        const val PLAYER_ABILITIES = 0x13
        const val TAB_COMPLETE = 0x14
        const val CLIENT_STATUS = 0x16
        const val PLUGIN_MESSAGE = 0x17

        // Modern 1.20.2+ / 1.21.x (subset used by proxy)
        const val ACCEPT_TELEPORTATION_MODERN = 0x00
        const val CHAT_MESSAGE_MODERN = 0x08
        const val CHUNK_BATCH_RECEIVED_MODERN = 0x0A
        const val CLIENT_STATUS_MODERN = 0x0B
        const val CLIENT_INFORMATION_MODERN = 0x0D
        const val PLUGIN_MESSAGE_MODERN = 0x15
        const val KEEP_ALIVE_MODERN = 0x1B
        const val PLAYER_POSITION_MODERN = 0x1D
        const val PLAYER_POSITION_ROTATION_MODERN = 0x1E
        const val PLAYER_ROTATION_MODERN = 0x1F
        const val PLAYER_MODERN = 0x20
        const val PLAYER_ABILITIES_MODERN = 0x27
        const val PLAYER_ACTION_MODERN = 0x28
        const val HELD_ITEM_CHANGE_MODERN = 0x34
        const val USE_ITEM_ON_MODERN = 0x3F
    }

    object PlayClientbound {
        // Legacy 1.8.x
        const val KEEP_ALIVE = 0x00
        const val JOIN_GAME = 0x01
        const val CHAT_MESSAGE = 0x02
        const val TIME_UPDATE = 0x03
        const val SPAWN_POSITION = 0x05
        const val UPDATE_HEALTH = 0x06
        const val RESPAWN = 0x07
        const val PLAYER_POSITION_LOOK = 0x08
        const val HELD_ITEM_CHANGE = 0x09
        const val SPAWN_PLAYER = 0x0C
        const val SPAWN_OBJECT = 0x0E
        const val SPAWN_MOB = 0x0F
        const val ENTITY_VELOCITY = 0x12
        const val DESTROY_ENTITIES = 0x13
        const val ENTITY = 0x14
        const val ENTITY_REL_MOVE = 0x15
        const val ENTITY_LOOK = 0x16
        const val ENTITY_LOOK_MOVE = 0x17
        const val ENTITY_TELEPORT = 0x18
        const val ENTITY_HEAD_LOOK = 0x19
        const val ENTITY_METADATA = 0x1C
        const val ENTITY_EFFECT = 0x1D
        const val REMOVE_ENTITY_EFFECT = 0x1E
        const val SET_EXPERIENCE = 0x1F
        const val UPDATE_ATTRIBUTES = 0x20
        const val BLOCK_CHANGE = 0x23
        const val BLOCK_ACTION = 0x24
        const val BLOCK_BREAK_ANIMATION = 0x25
        const val EFFECT = 0x28
        const val GAME_STATE = 0x2B
        const val SPAWN_GLOBAL_ENTITY = 0x2C
        const val OPEN_WINDOW = 0x2D
        const val CLOSE_WINDOW = 0x2E
        const val SET_SLOT = 0x2F
        const val WINDOW_ITEMS = 0x30
        const val WINDOW_PROPERTY = 0x31
        const val CONFIRM_TRANSACTION = 0x32
        const val UPDATE_SIGN = 0x33
        const val UPDATE_BLOCK_ENTITY = 0x35
        const val OPEN_SIGN_EDITOR = 0x36
        const val PLAYER_LIST_ITEM = 0x38
        const val PLAYER_ABILITIES = 0x39
        const val SCOREBOARD_OBJECTIVE = 0x3B
        const val UPDATE_SCORE = 0x3C
        const val DISPLAY_SCOREBOARD = 0x3D
        const val TEAMS = 0x3E
        const val PLUGIN_MESSAGE = 0x3F
        const val DISCONNECT = 0x40

        // Modern 1.20.2+ / 1.21.x (subset used by proxy)
        const val BUNDLE_DELIMITER_MODERN = 0x00
        const val ADD_ENTITY_MODERN = 0x01
        const val CHUNK_BATCH_FINISHED_MODERN = 0x0B
        const val CHUNK_BATCH_START_MODERN = 0x0C
        const val PLUGIN_MESSAGE_MODERN = 0x18
        const val DISCONNECT_MODERN = 0x20
        const val FORGET_LEVEL_CHUNK_MODERN = 0x25
        const val FORGET_LEVEL_CHUNK_MODERN_ALT = 0x21
        const val GAME_EVENT_MODERN = 0x26
        const val KEEP_ALIVE_MODERN = 0x2B
        const val LEVEL_CHUNK_WITH_LIGHT_MODERN = 0x2C
        const val LEVEL_CHUNK_WITH_LIGHT_MODERN_ALT = 0x27
        const val LOGIN_MODERN = 0x30
        const val MOVE_ENTITY_POS_MODERN = 0x33
        const val MOVE_ENTITY_POS_ROT_MODERN = 0x34
        const val MOVE_ENTITY_ROT_MODERN = 0x36
        const val PLAYER_ABILITIES_MODERN = 0x3E
        const val PLAYER_POSITION_MODERN = 0x46
        const val PLAYER_ROTATION_MODERN = 0x47
        const val REMOVE_ENTITIES_MODERN = 0x4B
        const val RESPAWN_MODERN = 0x50
        const val ROTATE_HEAD_MODERN = 0x51
        const val SET_DEFAULT_SPAWN_POSITION_MODERN = 0x5F
        const val SET_ENTITY_MOTION_MODERN = 0x63
        const val SET_HEALTH_MODERN = 0x66
        const val SET_HELD_SLOT_MODERN = 0x67
        const val SET_TIME_MODERN = 0x6F
        const val SYSTEM_CHAT_MODERN = 0x77
        const val TELEPORT_ENTITY_MODERN = 0x7B
        const val TRANSFER_MODERN = 0x7F
    }

    /**
     * Java 1.20.2+ configuration state packet IDs
     */
    object ConfigurationClientbound {
        const val COOKIE_REQUEST = 0x00
        const val CUSTOM_PAYLOAD = 0x01
        const val DISCONNECT = 0x02
        const val FINISH_CONFIGURATION = 0x03
        const val KEEP_ALIVE = 0x04
        const val PING = 0x05
        const val RESET_CHAT = 0x06
        const val REGISTRY_DATA = 0x07
        const val REMOVE_RESOURCE_PACK = 0x08
        const val ADD_RESOURCE_PACK = 0x09
        const val STORE_COOKIE = 0x0A
        const val TRANSFER = 0x0B
        const val ENABLED_FEATURES = 0x0C
        const val UPDATE_TAGS = 0x0D
        const val SELECT_KNOWN_PACKS = 0x0E
        const val CUSTOM_REPORT_DETAILS = 0x0F
        const val SERVER_LINKS = 0x10
        const val CLEAR_DIALOG = 0x11
        const val SHOW_DIALOG = 0x12
        const val CODE_OF_CONDUCT = 0x13
    }

    object ConfigurationServerbound {
        const val CLIENT_INFORMATION = 0x00
        const val COOKIE_RESPONSE = 0x01
        const val CUSTOM_PAYLOAD = 0x02
        const val FINISH_CONFIGURATION = 0x03
        const val KEEP_ALIVE = 0x04
        const val PONG = 0x05
        const val RESOURCE_PACK_RESPONSE = 0x06
        const val SELECT_KNOWN_PACKS = 0x07
        const val ACCEPT_CODE_OF_CONDUCT = 0x09
    }
}
