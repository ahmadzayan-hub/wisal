package com.wifeassistant.data.crypto

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Public material only, committed in the SAME encrypted snapshot as the private
 * keys and monotonic counters. This is an intent, not evidence of relay acceptance.
 */
@Serializable
data class PreparedPreKeyPublication(
    val bundle: SignalPreKeyBundleDto,
    val generatedAtEpochSec: Long,
    val relayScope: String,
    val publisherKeyB64: String,
)

data class PreKeyPublicationState(
    val pending: PreparedPreKeyPublication?,
    val accepted: PreparedPreKeyPublication?,
    val lastSignedKeyRotationEpochSec: Long,
    val pendingOneTimeKeyPresent: Boolean,
    val acceptedOneTimeKeyPresent: Boolean,
)

/** Versioned content identifier, not a replacement for Signal or TLS authentication.
 * Exact contract: wisal-direct-relay/lib/prekeys.js. Only validated public fields
 * enter the digest, in the same order as the existing signed publication proof.
 */
fun SignalPreKeyBundleDto.publicationDigest(): String {
    require(hasValidWireFields())
    val canonical = listOf("wisal-prekey-bundle-v1", deviceId, registrationId, identityKeyB64,
        signedPreKeyId, signedPreKeyPublicB64, signedPreKeySignatureB64,
        kyberPreKeyId, kyberPreKeyPublicB64, kyberPreKeySignatureB64,
        oneTimePreKeyId, oneTimePreKeyPublicB64).joinToString(":")
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
