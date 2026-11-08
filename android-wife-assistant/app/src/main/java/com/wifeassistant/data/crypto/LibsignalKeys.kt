package com.wifeassistant.data.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import java.util.Base64
import kotlin.random.Random

// توليد مادة بروتوكول Signal الحقيقية (libsignal 0.86.5، PQXDH + Double
// Ratchet) والتحويل من/إلى صيغة نقل بسيطة (JSON base64) عشان تتبعت عبر
// wisal-direct-relay في شريحة لاحقة.
//
// كل بنية وترتيب هنا اتحقق منه من الـ bytecode الفعلي المنشور على Maven
// Central (org.signal:libsignal-client:0.86.5) — مش من الذاكرة — ومن اختبار
// تنفيذي كامل لدورة تشفير/فك تشفير ثنائية الاتجاه حقيقية قبل كتابة الكود ده.
// راجع docs/decisions/ADR-002-e2ee-protocol.md.

private fun ByteArray.toB64(): String = Base64.getEncoder().encodeToString(this)
private fun String.fromB64(): ByteArray = Base64.getDecoder().decode(this)

data class LibsignalIdentity(val keyPair: IdentityKeyPair, val registrationId: Int)

// حزمة المفاتيح المسبقة اللي جهاز بينشرها عشان أي حد يقدر يأسس جلسة معاه
// (X3DH/PQXDH). المفتاح الفردي (one-time) هنا إلزامي دايمًا في النسخة دي —
// مسار "من غير one-time prekey" في libsignal ما اتفحصش هنا، فمش مدعوم.
object LibsignalKeys {
    // معرّف تسجيل عشوائي — النطاق زي ما بروتوكول Signal بيتوقعه (14-bit تقريبًا).
    fun generateIdentity(): LibsignalIdentity =
        LibsignalIdentity(IdentityKeyPair.generate(), Random.nextInt(1, 16381))

    // بيولّد ويخزّن مفتاح موقّع + مفتاح Kyber موقّع + مفتاح فردي واحد في
    // الـ store، ويرجّع DTO قابل للنشر يمثّلهم.
    fun generateAndStorePublishableBundle(
        identity: LibsignalIdentity,
        store: InMemorySignalProtocolStore,
        deviceId: String,
        signedPreKeyId: Int,
        kyberPreKeyId: Int,
        oneTimePreKeyId: Int,
    ): SignalPreKeyBundleDto {
        val signedKp = ECKeyPair.generate()
        val signedSig = identity.keyPair.privateKey.calculateSignature(signedKp.publicKey.serialize())
        store.storeSignedPreKey(signedPreKeyId, SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), signedKp, signedSig))

        val kyberKp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSig = identity.keyPair.privateKey.calculateSignature(kyberKp.publicKey.serialize())
        store.storeKyberPreKey(kyberPreKeyId, KyberPreKeyRecord(kyberPreKeyId, System.currentTimeMillis(), kyberKp, kyberSig))

        val oneTimeKp = ECKeyPair.generate()
        store.storePreKey(oneTimePreKeyId, PreKeyRecord(oneTimePreKeyId, oneTimeKp))

        return SignalPreKeyBundleDto(
            deviceId = deviceId,
            registrationId = identity.registrationId,
            identityKeyB64 = identity.keyPair.publicKey.serialize().toB64(),
            signedPreKeyId = signedPreKeyId,
            signedPreKeyPublicB64 = signedKp.publicKey.serialize().toB64(),
            signedPreKeySignatureB64 = signedSig.toB64(),
            kyberPreKeyId = kyberPreKeyId,
            kyberPreKeyPublicB64 = kyberKp.publicKey.serialize().toB64(),
            kyberPreKeySignatureB64 = kyberSig.toB64(),
            oneTimePreKeyId = oneTimePreKeyId,
            oneTimePreKeyPublicB64 = oneTimeKp.publicKey.serialize().toB64(),
        )
    }

    // إعادة بناء PreKeyBundle حقيقي من DTO — الترتيب اتحقق منه بـ javap على
    // الـ .class الفعلي المنشور (مش من قراءة كود Rust المصدري، اللي رتّب
    // الحقول بترتيب مختلف عن Java).
    fun toPreKeyBundle(dto: SignalPreKeyBundleDto): PreKeyBundle = PreKeyBundle(
        dto.registrationId,
        LIBSIGNAL_DEVICE_INDEX,
        dto.oneTimePreKeyId,
        ECPublicKey(dto.oneTimePreKeyPublicB64.fromB64()),
        dto.signedPreKeyId,
        ECPublicKey(dto.signedPreKeyPublicB64.fromB64()),
        dto.signedPreKeySignatureB64.fromB64(),
        IdentityKey(dto.identityKeyB64.fromB64()),
        dto.kyberPreKeyId,
        KEMPublicKey(dto.kyberPreKeyPublicB64.fromB64()),
        dto.kyberPreKeySignatureB64.fromB64(),
    )
}
