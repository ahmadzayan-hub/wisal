package com.wifeassistant.data.pairing

import com.wifeassistant.data.crypto.DeviceSigningIdentity
import com.wifeassistant.data.crypto.SignalPreKeyBundleDto

/** A point-in-time observation over the configured authenticated HTTPS channel.
 * It is NOT a signed server attestation, availability reservation, or E2EE proof.
 */
data class RelayPreKeyStatus(
    val bundleDigest: String?,
    val oneTimePreKeyId: Int?,
    val oneTimePreKeyAvailable: Boolean,
    val publishedAtEpochSec: Long?,
    val serverTimeEpochSec: Long,
)

interface PreKeyPublicationTransport {
    /** Canonical endpoint identity. Maintenance never moves a journal between relays. */
    fun publicationScope(): String
    suspend fun publishPreKeys(identity: DeviceSigningIdentity, bundle: SignalPreKeyBundleDto, nowEpochSec: Long): Result<Unit>
    suspend fun preKeyPublicationStatus(identity: DeviceSigningIdentity, nowEpochSec: Long): Result<RelayPreKeyStatus>
}

class RelayRequestException(val httpCode: Int, val refusalReason: String?) :
    IllegalStateException(refusalReason ?: "HTTP $httpCode")

class PreKeyClockException : IllegalStateException("Pre-key maintenance requires a consistent device clock. Any pending publication is retained for verification.")
class PreKeyPublicationConflictException : IllegalStateException("Relay publication does not match the durable local journal; review is required.")
