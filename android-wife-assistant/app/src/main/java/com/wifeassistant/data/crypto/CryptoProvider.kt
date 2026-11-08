package com.wifeassistant.data.crypto

import kotlinx.serialization.Serializable

// طبقة تجريد التشفير لـ Wisal Direct (ADR-002: قرار المالك — دعم الحالتين).
//
// العميل بيتكتب ضد الواجهة دي بس؛ الباك-إند الفعلي بيتحدد كـ build variant:
// - SIGNAL   → libsignal (AGPL-3.0، نسخة مفتوحة المصدر)
// - VODOZEMAC → vodozemac (Apache-2.0، النسخة الافتراضية)
// - DEMO_ONLY → نقل تطوير غير آمن — ممنوع في release نهائيًا (الحارس تحت)
//
// قاعدة صارمة: مفيش أي ادعاء تشفير في الواجهة أو الموقع قبل ما باك-إند حقيقي
// يتكامل ويعدي بوابات ADR-002 (اختبارات جهازين + فحص plaintext + مراجعة خارجية).

enum class CryptoBackend { SIGNAL, VODOZEMAC, DEMO_ONLY }

// المغلف المتبادل مع الـ relay: البيانات الوصفية للتوجيه فقط + ciphertext معتم.
// السيرفر عمره ما يشوف غير ده — التصميم واحد مهما كان الباك-إند.
@Serializable
data class EncryptedEnvelope(
    val senderDeviceId: String,
    val recipientDeviceId: String,
    val ciphertextB64: String,   // معتم للسيرفر دائمًا
    val backend: String,          // اسم البروتوكول المنتج للمغلف
    val expiresAtEpochSec: Long,  // بعده الـ relay يمسح المغلف
)

// جلسة تشفير مع جهاز واحد. التأسيس (pre-keys/KeyPackages) خاص بكل باك-إند
// وبيتم قبل إنشاء الجلسة — الواجهة دي بتغطي التبادل بعد التأسيس.
interface CryptoSession {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

interface CryptoProvider {
    val backend: CryptoBackend

    // هل الباك-إند ده مسموح يوصف كمشفّر طرف-لطرف في الواجهة؟
    // false للـ DEMO_ONLY دائمًا، وfalse للباقي لحد ما بوابات ADR-002 تعدي.
    val mayClaimE2ee: Boolean

    fun session(recipientDeviceId: String): CryptoSession
}

// باك-إندات بتحتاج مادة تأسيس من الطرف التاني (زي PreKeyBundle بتاع Signal:
// X3DH/PQXDH) قبل أول جلسة مع جهاز معيّن — session() على الباك-إندات دي
// بترمي لو التأسيس لسه ما حصلش. DEMO_ONLY مش محتاجها.
interface PreKeyEstablishingProvider : CryptoProvider {
    fun hasSession(recipientDeviceId: String): Boolean

    // preKeyBundleBytes: تسلسل JSON من SignalPreKeyBundleDto (أو ما يعادلها
    // في باك-إند تاني) — جاي من نقطة استعلام على الـ relay في شريحة لاحقة.
    fun establishSession(recipientDeviceId: String, preKeyBundleBytes: ByteArray)
}

object CryptoProviderFactory {
    // isDebugBuild بيتمرر من BuildConfig.DEBUG عند نقطة الاستدعاء —
    // كده الحارس قابل للاختبار على الـ JVM من غير Robolectric.
    fun create(backend: CryptoBackend, isDebugBuild: Boolean,
        signalPersistence: SignalStatePersistence? = null): CryptoProvider = when (backend) {
        CryptoBackend.DEMO_ONLY -> {
            check(isDebugBuild) {
                "DEMO_ONLY transport is forbidden in release builds (ADR-002)"
            }
            DemoOnlyCrypto()
        }
        // Release callers must supply durable encrypted persistence. Debug-only
        // memory construction remains available for existing isolated tests.
        CryptoBackend.SIGNAL -> if (signalPersistence != null) LibsignalCryptoProvider(signalPersistence) else {
            check(isDebugBuild) { "Release Signal requires verified encrypted persistent storage." }
            LibsignalCryptoProvider(LibsignalKeys.generateIdentity())
        }
        CryptoBackend.VODOZEMAC -> throw NotImplementedError(
            "vodozemac backend lands in Phase 3 (default build variant) — see ADR-002"
        )
    }
}

// نقل تطوير فقط: بيوسم البيانات بوضوح إنها غير مشفرة ولا يدّعي غير كده.
// موجود عشان تطوير الـ relay والواجهات يمشي قبل تكامل البروتوكول الحقيقي.
internal class DemoOnlyCrypto : CryptoProvider {
    override val backend = CryptoBackend.DEMO_ONLY
    override val mayClaimE2ee = false

    override fun session(recipientDeviceId: String): CryptoSession = object : CryptoSession {
        private val marker = "DEMO_ONLY_NOT_ENCRYPTED:".toByteArray()
        override fun encrypt(plaintext: ByteArray): ByteArray = marker + plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray {
            require(ciphertext.size >= marker.size) { "not a DEMO_ONLY payload" }
            return ciphertext.copyOfRange(marker.size, ciphertext.size)
        }
    }
}
