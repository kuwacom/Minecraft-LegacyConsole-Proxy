package dev.kuwa.mlcproxy.protocol.mlc.codec

enum class MlcTransportMode {
    LENGTH_PREFIXED_32BE,
    RAW;

    companion object {
        fun fromConfig(value: String): MlcTransportMode {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown MLC transport mode: $value")
        }
    }
}
