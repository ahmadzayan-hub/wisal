package com.wifeassistant.data.pairing

data class PairingState(
    val peers: List<PairedPeer> = emptyList(),
    val acceptedInvitationIds: Set<String> = emptySet(),
)

/**
 * One instance per application process. A peer and its replay guard must share
 * one synchronous commit. An ambiguous write blocks this instance until restart:
 * SharedPreferences may expose uncommitted values in memory after commit fails.
 * This records local acceptance only; it does not verify a person's identity.
 */
class PairingAcceptanceStore(
    private val read: () -> PairingState,
    private val commit: (PairingState) -> Boolean,
) {
    private var blocked = false

    @Synchronized
    fun snapshot(): PairingState {
        check(!blocked) { "Pairing storage is unavailable; restart without clearing app data." }
        return read().also { state ->
            check(state.peers.map { it.deviceId }.distinct().size == state.peers.size) {
                "Pairing storage contains conflicting peers."
            }
            check(state.peers.all { it.invitationId in state.acceptedInvitationIds }) {
                "Pairing storage is incomplete."
            }
        }.let { it.copy(peers = it.peers.toList(), acceptedInvitationIds = it.acceptedInvitationIds.toSet()) }
    }

    @Synchronized
    fun accept(invitation: PairingInvitation, token: String, nowEpochSec: Long): PairingResult {
        val decision = Pairing.accept(invitation, token, nowEpochSec)
        if (decision !is PairingResult.Ok) return decision
        val before = snapshot()
        if (invitation.id in before.acceptedInvitationIds) return PairingResult.Refused("already accepted")
        val existing = before.peers.find { it.deviceId == invitation.inviterDeviceId }
        if (existing != null) return PairingResult.Refused(
            if (existing.publicKeyB64 == invitation.inviterPublicKeyB64) "already paired" else "identity changed"
        )
        val peer = PairedPeer(invitation.inviterDeviceId, invitation.inviterPublicKeyB64,
            invitation.inviterName, invitation.id, nowEpochSec)
        val after = PairingState(before.peers + peer, before.acceptedInvitationIds + invitation.id)
        try {
            check(commit(after)) { "Pairing commit failed." }
            check(read() == after) { "Pairing commit could not be verified." }
        } catch (error: Exception) {
            blocked = true
            throw IllegalStateException("Pairing was not confirmed saved. Keep app data intact and restart.", error)
        }
        return decision
    }
}
