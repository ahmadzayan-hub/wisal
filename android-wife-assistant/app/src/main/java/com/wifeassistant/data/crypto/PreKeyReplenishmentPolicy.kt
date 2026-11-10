package com.wifeassistant.data.crypto

/** Local signal about this device's currently stored pre-key material. Carries
 * only counts and a timestamp, never secret material. Produced by
 * [SignalStatePersistence.replenishmentState]; a caller with no durable
 * Signal storage has nothing to evaluate.
 */
data class PreKeyReplenishmentState(
    val hasPublishedBundle: Boolean,
    val lastSignedKeyRotationEpochSec: Long,
    val oneTimePreKeyCount: Int,
)

/** What [PreKeyReplenishmentPolicy] decided is due right now. Neither flag
 * implies the other: a signed pre-key can be due for rotation while the
 * one-time pre-key floor is still satisfied, and vice versa.
 */
data class PreKeyReplenishmentDecision(
    val needsSignedKeyRotation: Boolean,
    val needsOneTimePreKeyReplenishment: Boolean,
) {
    val needsReplenishment: Boolean get() = needsSignedKeyRotation || needsOneTimePreKeyReplenishment
}

/**
 * Pure, deterministic policy deciding whether this device's signed pre-key
 * and/or one-time pre-key are due for replenishment, given its local state
 * and a caller-supplied "now". It holds no state of its own, starts no
 * scheduler/thread/WorkManager and performs no I/O: a caller (app start,
 * after sending/receiving a message, its own periodic trigger, …) invokes
 * [evaluate] to ask "is replenishment needed right now" and decides what to
 * do with the answer — generating a new bundle with
 * [LibsignalCryptoProvider.nextPublishableBundle] and publishing it remain
 * the caller's responsibility.
 *
 * `nextPublishableBundle` already allocates a fresh signed/Kyber/one-time
 * triplet in a single call, so this policy only decides *when* making that
 * call is worth it; it never allocates ids or talks to the relay itself.
 */
class PreKeyReplenishmentPolicy(
    private val signedKeyRotationIntervalSeconds: Long = DEFAULT_SIGNED_KEY_ROTATION_INTERVAL_SECONDS,
    private val oneTimePreKeyFloor: Int = DEFAULT_ONE_TIME_PREKEY_FLOOR,
) {
    init {
        require(signedKeyRotationIntervalSeconds > 0) { "Rotation interval must be positive." }
        require(oneTimePreKeyFloor >= 0) { "One-time pre-key floor cannot be negative." }
    }

    /** Never published before: both a signed pre-key and a one-time pre-key
     * are due, regardless of [nowEpochSec] or [PreKeyReplenishmentState.oneTimePreKeyCount].
     * Otherwise a signed pre-key is due once its age reaches the rotation
     * interval, and a one-time pre-key is due once the local count drops
     * below the floor.
     */
    fun evaluate(state: PreKeyReplenishmentState, nowEpochSec: Long): PreKeyReplenishmentDecision {
        require(nowEpochSec >= 0) { "nowEpochSec cannot be negative." }
        if (!state.hasPublishedBundle) {
            return PreKeyReplenishmentDecision(needsSignedKeyRotation = true, needsOneTimePreKeyReplenishment = true)
        }
        val signedKeyAgeSeconds = nowEpochSec - state.lastSignedKeyRotationEpochSec
        val needsSignedKeyRotation = signedKeyAgeSeconds >= signedKeyRotationIntervalSeconds
        val needsOneTimePreKeyReplenishment = state.oneTimePreKeyCount < oneTimePreKeyFloor
        return PreKeyReplenishmentDecision(needsSignedKeyRotation, needsOneTimePreKeyReplenishment)
    }

    companion object {
        /** Signal's own clients rotate their signed pre-key roughly weekly;
         * this keeps the same order of magnitude pending a dedicated review.
         */
        const val DEFAULT_SIGNED_KEY_ROTATION_INTERVAL_SECONDS: Long = 7L * 24 * 60 * 60
        const val DEFAULT_ONE_TIME_PREKEY_FLOOR: Int = 1
    }
}
