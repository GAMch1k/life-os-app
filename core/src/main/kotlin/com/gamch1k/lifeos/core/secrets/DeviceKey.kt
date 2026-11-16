package com.gamch1k.lifeos.core.secrets

/**
 * A device key (life-os `docs/api/devices.md`): `lo_dev_` and 64 letters,
 * the credential a paired phone, or its watch, sends on every call as
 * `Authorization: Bearer <key>`. Kept only encrypted ([TokenStore]); its
 * toString never shows it, and it is never logged, put in a URL, or sent
 * anywhere but the server that issued it.
 */
class DeviceKey private constructor(val value: String) {
    val bearer: String get() = "Bearer $value"

    override fun equals(other: Any?) = other is DeviceKey && other.value == value
    override fun hashCode() = value.hashCode()
    override fun toString() = "DeviceKey(…)"

    companion object {
        const val PREFIX = "lo_dev_"

        // The contract says 64 letters; a little room either way, never anything a header can't carry.
        private val FORMAT = Regex("^lo_dev_[A-Za-z0-9]{32,256}$")

        fun parse(value: String?): DeviceKey? = value?.takeIf(FORMAT::matches)?.let(::DeviceKey)
    }
}
