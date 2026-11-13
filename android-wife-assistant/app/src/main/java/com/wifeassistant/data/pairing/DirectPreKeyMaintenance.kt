package com.wifeassistant.data.pairing

import com.wifeassistant.data.crypto.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class PreKeyMaintenanceAction { CURRENT, PUBLISHED, RECONCILED }

data class PreKeyMaintenanceResult(
    val action: PreKeyMaintenanceAction,
    val oneTimePreKeyId: Int,
    /** Observation only. Another device may claim the key immediately afterwards. */
    val oneTimePreKeyAvailable: Boolean,
)

/** Explicit, bounded, single-process integration. Nothing schedules or activates
 * this class implicitly. One call publishes OR reconciles at most one journaled
 * bundle. The caller decides when to retry; there is no internal retry loop.
 *
 * Every new bundle rotates the signed, Kyber and one-time triplet, reusing PR #18.
 * A consumed relay OTP can therefore trigger signed rotation earlier than 7 days.
 * Only the relay's supported inventory floor (one available bundle) is used here.
 */
class DirectPreKeyMaintenance(
    private val provider: LibsignalCryptoProvider,
    private val relay: PreKeyPublicationTransport,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    signedKeyRotationIntervalSeconds: Long = PreKeyReplenishmentPolicy.DEFAULT_SIGNED_KEY_ROTATION_INTERVAL_SECONDS,
) {
    private val policy = PreKeyReplenishmentPolicy(signedKeyRotationIntervalSeconds, oneTimePreKeyFloor = 1)

    companion object {
        // A process-wide coroutine lock also covers reopened coordinator instances.
        // No native/persistence monitor is held during HTTP. Mutex.withLock releases
        // on failure/cancellation: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/with-lock.html
        private val maintenanceLock = Mutex()
    }

    suspend fun runOnce(identity: DeviceSigningIdentity): Result<PreKeyMaintenanceResult> = withContext(Dispatchers.IO) {
        maintenanceLock.withLock {
            try {
                Result.success(maintain(identity))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(if (failure is RelayRequestException && failure.refusalReason == "stale timestamp")
                    PreKeyClockException() else failure)
            }
        }
    }

    private suspend fun maintain(identity: DeviceSigningIdentity): PreKeyMaintenanceResult {
        val scope = relay.publicationScope()
        val local = provider.preKeyPublicationState(identity.deviceId)
        listOfNotNull(local.pending, local.accepted).forEach {
            check(it.relayScope == scope && it.publisherKeyB64 == identity.publicKeyB64 && it.bundle.deviceId == identity.deviceId) {
                "Pre-key publication belongs to a different relay or device identity."
            }
        }
        fun now(): Long {
            val value = clock()
            if (value !in 1..9_007_199_254_740_991L || value < local.lastSignedKeyRotationEpochSec) throw PreKeyClockException()
            return value
        }
        val observed = relay.preKeyPublicationStatus(identity, now()).getOrThrow()
        // A forward clock jump after the successful preflight must not poison
        // the durable rotation watermark before the relay rejects publication.
        // Long-paused calls stop and can be invoked again with a fresh status.
        fun checkedNow(): Long {
            val value = now()
            if (kotlin.math.abs(value - observed.serverTimeEpochSec) > 300) throw PreKeyClockException()
            return value
        }
        coroutineContext.ensureActive()
        checkedNow()

        local.pending?.let { pending ->
            if (observed.matches(pending)) {
                if (observed.oneTimePreKeyAvailable && !local.pendingOneTimeKeyPresent) throw PreKeyPublicationConflictException()
                provider.acceptPreKeyPublication(identity.deviceId, pending, observed.serverTimeEpochSec)
                return result(PreKeyMaintenanceAction.RECONCILED, pending, observed)
            }
            // Only a known previous accepted publication (or an empty first
            // relay) permits sending. Unknown higher/lower/different state stops.
            requirePrevious(observed, local.accepted)
            if (!local.pendingOneTimeKeyPresent) throw PreKeyPublicationConflictException()
            return publishAndConfirm(identity, pending, ::checkedNow)
        }

        requirePrevious(observed, local.accepted)
        if (observed.oneTimePreKeyAvailable && !local.acceptedOneTimeKeyPresent) throw PreKeyPublicationConflictException()
        val decision = policy.evaluate(PreKeyReplenishmentState(
            hasPublishedBundle = local.accepted != null,
            lastSignedKeyRotationEpochSec = local.accepted?.generatedAtEpochSec ?: 0,
            oneTimePreKeyCount = if (observed.oneTimePreKeyAvailable) 1 else 0,
        ), checkedNow())
        if (decision.clockAnomaly) throw PreKeyClockException()
        provider.retireSupersededPreKeys(identity.deviceId, observed.serverTimeEpochSec)
        if (!decision.needsReplenishment) return result(PreKeyMaintenanceAction.CURRENT, checkNotNull(local.accepted), observed)

        coroutineContext.ensureActive()
        // Stage private material, ID high-water marks and public intent in ONE
        // verified write. A storage failure prevents the publication request.
        val pending = provider.preparePreKeyPublication(identity.deviceId, checkedNow(), scope,
            identity.publicKeyB64, local.accepted)
        return publishAndConfirm(identity, pending, ::checkedNow)
    }

    private suspend fun publishAndConfirm(identity: DeviceSigningIdentity, pending: PreparedPreKeyPublication,
        now: () -> Long): PreKeyMaintenanceResult {
        coroutineContext.ensureActive()
        val timestamp = now()
        if (timestamp < pending.generatedAtEpochSec) throw PreKeyClockException()
        relay.publishPreKeys(identity, pending.bundle, timestamp).getOrThrow()
        coroutineContext.ensureActive()
        // The existing HTTP receipt checks device/key ID. This second, signed,
        // non-consuming read binds acceptance to the entire public bundle.
        val confirmationTime = now()
        if (confirmationTime < pending.generatedAtEpochSec) throw PreKeyClockException()
        val observed = relay.preKeyPublicationStatus(identity, confirmationTime).getOrThrow()
        if (!observed.matches(pending)) throw PreKeyPublicationConflictException()
        coroutineContext.ensureActive()
        provider.acceptPreKeyPublication(identity.deviceId, pending, observed.serverTimeEpochSec)
        return result(PreKeyMaintenanceAction.PUBLISHED, pending, observed)
    }

    private fun requirePrevious(observed: RelayPreKeyStatus, accepted: PreparedPreKeyPublication?) {
        if (accepted == null) {
            if (observed.bundleDigest != null) throw PreKeyPublicationConflictException()
        } else if (!observed.matches(accepted)) throw PreKeyPublicationConflictException()
    }

    private fun RelayPreKeyStatus.matches(publication: PreparedPreKeyPublication): Boolean =
        bundleDigest == publication.bundle.publicationDigest() && oneTimePreKeyId == publication.bundle.oneTimePreKeyId

    private fun result(action: PreKeyMaintenanceAction, publication: PreparedPreKeyPublication, status: RelayPreKeyStatus) =
        PreKeyMaintenanceResult(action, publication.bundle.oneTimePreKeyId, status.oneTimePreKeyAvailable)
}
