package com.wifeassistant.data.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.*
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import java.util.Base64

internal interface SignalStoreAccess {
    fun <T> use(operation: (LibsignalIdentity, InMemorySignalProtocolStore) -> T): T
}

internal class MemorySignalAccess(private val identity: LibsignalIdentity,
    private val store: InMemorySignalProtocolStore) : SignalStoreAccess {
    override fun <T> use(operation: (LibsignalIdentity, InMemorySignalProtocolStore) -> T): T =
        synchronized(store) { operation(identity, store) }
}

@Serializable
private data class KyberUse(val kyberId: Int, val signedId: Int, val baseKey: String)

@Serializable
private data class SignalSnapshot(
    val version: Int = 1,
    val identity: String,
    val registrationId: Int,
    val ownerDeviceId: String? = null,
    val sessions: MutableMap<String, String> = mutableMapOf(),
    val peers: MutableMap<String, String> = mutableMapOf(),
    val preKeys: MutableMap<Int, String> = mutableMapOf(),
    val signedKeys: MutableMap<Int, String> = mutableMapOf(),
    val kyberKeys: MutableMap<Int, String> = mutableMapOf(),
    val kyberUses: MutableList<KyberUse> = mutableListOf(),
    var highestPreKeyId: Int = 0,
    var highestSignedId: Int = 0,
    var highestKyberId: Int = 0,
    var lastSignedKeyRotationEpochSec: Long = 0,
    val outbox: MutableMap<String, QueuedSignalMessage> = mutableMapOf(),
    val inbox: MutableMap<String, ReceivedSignalMessage> = mutableMapOf(),
    val receipts: MutableMap<String, String> = mutableMapOf(),
    val pendingAcks: MutableMap<String, String> = mutableMapOf(),
)

private fun ByteArray.b64() = Base64.getEncoder().encodeToString(this)
private fun String.bytes() = Base64.getDecoder().decode(this)
private fun SignalProtocolAddress.storageKey() = "$name:$deviceId"
private fun address(key: String): SignalProtocolAddress {
    val split = key.lastIndexOf(':')
    require(split in 1..200)
    return SignalProtocolAddress(key.substring(0, split), key.substring(split + 1).toInt())
}

/** One encrypted snapshot per successful protocol operation. Callbacks MUST use
 * verified durable encrypted storage; this class provides no encryption itself.
 * Single Android process only. No protection against restoring an older valid
 * snapshot: rollback/backup acceptance remains a release gate.
 */
class SignalStatePersistence(private val read: () -> String?, private val write: (String) -> Unit,
    private val ownerDeviceId: String? = null) : SignalStoreAccess {
    companion object { private val lock = Any() }
    private val json = Json { encodeDefaults = true }
    private var blocked = false
    private var previouslyLoaded = false

    override fun <T> use(operation: (LibsignalIdentity, InMemorySignalProtocolStore) -> T): T = synchronized(lock) {
        check(!blocked) { "Signal storage is blocked after an unverified write." }
        val original: String?
        val snapshot: SignalSnapshot
        val identity: LibsignalIdentity
        val store: SnapshotSignalStore
        try {
            original = read()
            check(original != null || !previouslyLoaded)
            if (original == null) {
                identity = LibsignalKeys.generateIdentity()
                snapshot = SignalSnapshot(identity = identity.keyPair.serialize().b64(),
                    registrationId = identity.registrationId, ownerDeviceId = ownerDeviceId)
            } else {
                require(original.length <= 8 * 1024 * 1024)
                snapshot = json.decodeFromString(SignalSnapshot.serializer(), original)
                require(snapshot.version == 1 && snapshot.registrationId in 1..16380)
                require(snapshot.ownerDeviceId == ownerDeviceId)
                identity = LibsignalIdentity(IdentityKeyPair(snapshot.identity.bytes()), snapshot.registrationId)
            }
            store = SnapshotSignalStore(identity, snapshot)
        } catch (_: Exception) {
            throw IllegalStateException("Signal state could not be loaded. Existing identity was not replaced.")
        }
        // Native mutations affect this operation's working copy only. A protocol
        // failure discards it, including partial pre-key removals or ratchet updates.
        val baseline = json.encodeToString(SignalSnapshot.serializer(), snapshot)
        val result = operation(identity, store)
        val encoded = json.encodeToString(SignalSnapshot.serializer(), snapshot)
        if (original == null || encoded != baseline) {
            // Capacity rejection is not an ambiguous disk write and must not
            // block reads of the last durable state for the rest of the process.
            require(encoded.length <= 8 * 1024 * 1024) { "Signal state capacity reached." }
            blocked = true
            try {
                write(encoded)
                check(read() == encoded)
            } catch (_: Exception) {
                throw IllegalStateException("Signal state could not be committed. Operation result was not released.")
            }
            blocked = false
        }
        previouslyLoaded = true
        result
    }

    internal fun requireOwner(deviceId: String) {
        require(ownerDeviceId == null || ownerDeviceId == deviceId) { "Signal state belongs to another device identity." }
    }

    internal fun nextPublishableBundle(
        deviceId: String,
        nowEpochSec: Long = System.currentTimeMillis() / 1000,
    ): SignalPreKeyBundleDto = use { identity, store ->
        requireOwner(deviceId)
        // Read, allocate, generate and commit under the same persistence lock.
        // Counts or the largest remaining key would reuse IDs after consumption.
        val snapshotStore = store as SnapshotSignalStore
        val (signedId, kyberId, oneTimeId) = snapshotStore.nextPreKeyIds()
        val bundle = LibsignalKeys.generateAndStorePublishableBundle(identity, store, deviceId, signedId, kyberId, oneTimeId)
        // A fresh signed pre-key was just generated and committed above:
        // this is the only place that may legitimately move the rotation
        // clock forward, so the replenishment policy sees an honest age.
        snapshotStore.recordSignedKeyRotation(nowEpochSec)
        bundle
    }

    /** Read-only snapshot of local pre-key state for [PreKeyReplenishmentPolicy].
     * Carries no secret material, only counts and a rotation timestamp.
     */
    internal fun replenishmentState(deviceId: String): PreKeyReplenishmentState = use { _, store ->
        requireOwner(deviceId)
        (store as SnapshotSignalStore).replenishmentState()
    }
}

/** Native record serialization from libsignal 0.86.5, including Kyber replay
 * markers. Sender/group keys are deliberately unsupported by this 1:1 adapter.
 */
private class SnapshotSignalStore(identity: LibsignalIdentity, private val state: SignalSnapshot) :
    InMemorySignalProtocolStore(identity.keyPair, identity.registrationId), SignalDeliveryRecords {
    override val outbox get() = state.outbox
    override val inbox get() = state.inbox
    override val receipts get() = state.receipts
    override val pendingAcks get() = state.pendingAcks

    fun nextPreKeyIds(): Triple<Int, Int, Int> {
        val ids = listOf(state.highestSignedId, state.highestKyberId, state.highestPreKeyId)
        check(ids.all { it in 0 until Int.MAX_VALUE }) { "Pre-key ID space exhausted; no new keys were generated." }
        return Triple(ids[0] + 1, ids[1] + 1, ids[2] + 1)
    }

    /** Records when a signed pre-key was last (re)generated. Never moves the
     * clock backwards: an out-of-order or repeated call cannot make the
     * rotation policy believe a key is younger than one already committed.
     */
    fun recordSignedKeyRotation(nowEpochSec: Long) {
        require(nowEpochSec >= 0) { "Rotation timestamp cannot be negative." }
        if (nowEpochSec > state.lastSignedKeyRotationEpochSec) state.lastSignedKeyRotationEpochSec = nowEpochSec
    }

    fun replenishmentState(): PreKeyReplenishmentState = PreKeyReplenishmentState(
        hasPublishedBundle = state.highestSignedId > 0,
        lastSignedKeyRotationEpochSec = state.lastSignedKeyRotationEpochSec,
        oneTimePreKeyCount = state.preKeys.size,
    )

    init {
        require(state.lastSignedKeyRotationEpochSec >= 0)
        require(state.outbox.size <= 1000 && state.inbox.size <= 1000 && state.receipts.size <= 10_000)
        require(state.pendingAcks.all { (id, message) -> state.receipts[id] == message && state.inbox.containsKey(message) })
        require(state.outbox.all { (id, item) -> id == item.id })
        require(state.inbox.all { (id, item) -> id == item.id })
        state.peers.forEach { (a, r) -> super.saveIdentity(address(a), IdentityKey(r.bytes())) }
        state.sessions.forEach { (a, r) -> super.storeSession(address(a), SessionRecord(r.bytes())) }
        state.preKeys.forEach { (id, r) -> super.storePreKey(id, PreKeyRecord(r.bytes())) }
        state.signedKeys.forEach { (id, r) -> super.storeSignedPreKey(id, SignedPreKeyRecord(r.bytes())) }
        state.kyberKeys.forEach { (id, r) -> super.storeKyberPreKey(id, KyberPreKeyRecord(r.bytes())) }
        state.kyberUses.forEach { super.markKyberPreKeyUsed(it.kyberId, it.signedId, ECPublicKey(it.baseKey.bytes())) }
        require(state.highestPreKeyId >= (state.preKeys.keys.maxOrNull() ?: 0))
        require(state.highestSignedId >= (state.signedKeys.keys.maxOrNull() ?: 0))
        require(state.highestKyberId >= (state.kyberKeys.keys.maxOrNull() ?: 0))
    }

    override fun saveIdentity(a: SignalProtocolAddress, key: IdentityKey): IdentityKeyStore.IdentityChange {
        check(getIdentity(a)?.let { it == key } != false) { "Peer identity changed; explicit re-verification is required." }
        val result = super.saveIdentity(a, key)
        state.peers[a.storageKey()] = key.serialize().b64()
        return result
    }
    override fun isTrustedIdentity(a: SignalProtocolAddress, key: IdentityKey,
        direction: IdentityKeyStore.Direction): Boolean = getIdentity(a)?.let { it == key } == true
    override fun storeSession(a: SignalProtocolAddress, record: SessionRecord) {
        super.storeSession(a, record); state.sessions[a.storageKey()] = record.serialize().b64()
    }
    override fun deleteSession(a: SignalProtocolAddress) {
        super.deleteSession(a); state.sessions.remove(a.storageKey())
    }
    override fun deleteAllSessions(name: String) {
        super.deleteAllSessions(name); state.sessions.keys.removeAll { address(it).name == name }
    }
    override fun storePreKey(id: Int, record: PreKeyRecord) {
        check(id > state.highestPreKeyId) { "Pre-key id must increase." }
        super.storePreKey(id, record); state.preKeys[id] = record.serialize().b64(); state.highestPreKeyId = id
    }
    override fun removePreKey(id: Int) { super.removePreKey(id); state.preKeys.remove(id) }
    override fun storeSignedPreKey(id: Int, record: SignedPreKeyRecord) {
        check(id > state.highestSignedId) { "Signed pre-key id must increase." }
        super.storeSignedPreKey(id, record); state.signedKeys[id] = record.serialize().b64(); state.highestSignedId = id
    }
    override fun removeSignedPreKey(id: Int) { super.removeSignedPreKey(id); state.signedKeys.remove(id) }
    override fun storeKyberPreKey(id: Int, record: KyberPreKeyRecord) {
        check(id > state.highestKyberId) { "Kyber pre-key id must increase." }
        super.storeKyberPreKey(id, record); state.kyberKeys[id] = record.serialize().b64(); state.highestKyberId = id
    }
    override fun markKyberPreKeyUsed(id: Int, signedId: Int, baseKey: ECPublicKey) {
        super.markKyberPreKeyUsed(id, signedId, baseKey)
        state.kyberUses.add(KyberUse(id, signedId, baseKey.serialize().b64()))
    }
    override fun storeSenderKey(a: SignalProtocolAddress, id: java.util.UUID,
        record: org.signal.libsignal.protocol.groups.state.SenderKeyRecord) {
        error("Group sessions are not supported by the persistent 1:1 store.")
    }
}
