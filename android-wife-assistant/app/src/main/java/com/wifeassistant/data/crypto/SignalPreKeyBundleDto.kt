package com.wifeassistant.data.crypto

import kotlinx.serialization.Serializable

/** Public wire data only. Signal key/signature verification belongs to libsignal. */
@Serializable
data class SignalPreKeyBundleDto(
    val deviceId: String,
    val registrationId: Int,
    val identityKeyB64: String,
    val signedPreKeyId: Int,
    val signedPreKeyPublicB64: String,
    val signedPreKeySignatureB64: String,
    val kyberPreKeyId: Int,
    val kyberPreKeyPublicB64: String,
    val kyberPreKeySignatureB64: String,
    val oneTimePreKeyId: Int,
    val oneTimePreKeyPublicB64: String,
) {
    fun hasValidWireFields(): Boolean {
        val base64 = Regex("^[A-Za-z0-9+/]+={0,2}$")
        return deviceId.isNotBlank() && !deviceId.contains(':') &&
            listOf(registrationId, signedPreKeyId, kyberPreKeyId, oneTimePreKeyId).all { it > 0 } &&
            listOf(identityKeyB64, signedPreKeyPublicB64, signedPreKeySignatureB64, kyberPreKeyPublicB64,
                kyberPreKeySignatureB64, oneTimePreKeyPublicB64).all { it.length in 1..4096 && base64.matches(it) }
    }
}
