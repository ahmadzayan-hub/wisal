package com.wifeassistant.data.crypto

import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

// جهاز واحد = رقم "device" ثابت داخل عنونة Signal (SignalProtocolAddress).
// مفيش دعم أجهزة متعددة لنفس الشخص لسه (موثّق في الخارطة كمرحلة لاحقة) —
// معرّف جهازنا الحقيقي (UUID) هو الـ "name"، والرقم ده بس placeholder ثابت.
const val LIBSIGNAL_DEVICE_INDEX = 1

// Real Signal backend. Explicit memory constructor supports JVM/development tests.
// AndroidSignalProvider supplies a verified encrypted snapshot boundary. Neither
// path may claim E2EE until device, rollback and independent review gates pass.
class LibsignalCryptoProvider private constructor(private val access: SignalStoreAccess) : PreKeyEstablishingProvider {
    constructor(identity: LibsignalIdentity,
        store: InMemorySignalProtocolStore = InMemorySignalProtocolStore(identity.keyPair, identity.registrationId)) :
        this(MemorySignalAccess(identity, store))
    constructor(persistence: SignalStatePersistence) : this(persistence as SignalStoreAccess)
    override val backend = CryptoBackend.SIGNAL
    override val mayClaimE2ee = false

    private val json = Json { ignoreUnknownKeys = true }

    private fun addressOf(recipientDeviceId: String): SignalProtocolAddress {
        require(recipientDeviceId.isNotBlank() && recipientDeviceId.length <= 200 && !recipientDeviceId.contains(':'))
        return SignalProtocolAddress(recipientDeviceId, LIBSIGNAL_DEVICE_INDEX)
    }

    // الحزمة اللي الجهاز ده هينشرها على الـ relay (شريحة لاحقة) عشان أي حد
    // يقدر يأسس جلسة معاه. بتخزّن المفاتيح المولّدة في الـ store المحلي —
    // لازم تتنادى قبل أي establishSession جاي من طرف تاني.
    fun publishableBundle(
        myDeviceId: String,
        signedPreKeyId: Int = 1,
        kyberPreKeyId: Int = 1,
        oneTimePreKeyId: Int = 1,
    ): SignalPreKeyBundleDto = access.use { identity, store ->
        addressOf(myDeviceId)
        (access as? SignalStatePersistence)?.requireOwner(myDeviceId)
        LibsignalKeys.generateAndStorePublishableBundle(identity, store, myDeviceId, signedPreKeyId, kyberPreKeyId, oneTimePreKeyId)
    }

    override fun hasSession(recipientDeviceId: String): Boolean = access.use { _, store -> store.containsSession(addressOf(recipientDeviceId)) }

    /** Pin from a previously verified P-256 pairing. Never silently replace a pin. */
    fun trustPeer(trusted: PublicIdentity, bundle: SignalPreKeyBundleDto, bindingSignature: String) {
        SignalPeerBinding.verify(trusted, bundle, bindingSignature)
        access.use { _, store ->
            store.saveIdentity(addressOf(trusted.deviceId),
                org.signal.libsignal.protocol.IdentityKey(java.util.Base64.getDecoder().decode(bundle.identityKeyB64)))
        }
    }

    override fun establishSession(recipientDeviceId: String, preKeyBundleBytes: ByteArray) {
        val dto = json.decodeFromString(SignalPreKeyBundleDto.serializer(), String(preKeyBundleBytes, Charsets.UTF_8))
        require(dto.deviceId == recipientDeviceId) { "bundle deviceId mismatch: expected $recipientDeviceId, got ${dto.deviceId}" }
        val bundle = LibsignalKeys.toPreKeyBundle(dto)
        access.use { _, store -> SessionBuilder(store, addressOf(recipientDeviceId)).process(bundle) }
    }

    // مفيش تحقق مسبق هنا إن الجلسة متأسسة — SessionCipher نفسه بيتبنى بأمان
    // من غير جلسة موجودة، لأن أول رسالة PreKey واردة من الطرف التاني هي
    // اللي بتأسس الجلسة كأثر جانبي لفك تشفيرها. المكتبة نفسها بترفض encrypt()
    // أو decrypt() لرسالة Whisper عادية من غير جلسة (NoSessionException) —
    // مفيش داعي نعيد تنفيذ الحارس ده يدويًا فوق ضمانة المكتبة الأصلية.
    override fun session(recipientDeviceId: String): CryptoSession {
        val address = addressOf(recipientDeviceId)
        return object : CryptoSession {
            // نبصم بايت واحد بنوع الرسالة (PreKey أو Whisper العادية) قبل
            // الـ bytes المسلسَلة الحقيقية — ده وصف نقل بسيط (metadata) مش
            // تشفير مخصّص؛ بروتوكول Signal نفسه بيحمل نفس المعلومة دي في نقل
            // Signal الحقيقي كحقل منفصل جنب المحتوى.
            override fun encrypt(plaintext: ByteArray): ByteArray = access.use { _, store ->
                SignalWireCodec.encrypt(SessionCipher(store, address), plaintext)
            }

            override fun decrypt(ciphertext: ByteArray): ByteArray = access.use { _, store ->
                SignalWireCodec.decrypt(SessionCipher(store, address), ciphertext)
            }
        }
    }
}
