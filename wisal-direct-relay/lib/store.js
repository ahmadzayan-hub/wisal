// تخزين قابل للاستبدال (interface) — النسخة الافتراضية في الذاكرة **للتطوير
// والاختبار فقط**. حد إنتاج موثّق: Vercel serverless بيعيد تشغيل الـ instance،
// فالتخزين في الذاكرة بيضيع بين الطلبات في الإنتاج الحقيقي. قبل أي إطلاق عام
// لازم storage حقيقي (Postgres مثلاً) يطبّق نفس الواجهة دي — موثّق كـ blocker
// في docs/decisions/ADR-002-e2ee-protocol.md وdocs/implementation-roadmap.md.
//
// كل الحقول المخزّنة في envelope: توجيه + ciphertext معتم بس — مفيش حقل
// plaintext في السكيمة أصلًا (نفس تصميم EncryptedEnvelope في العميل).

function createInMemoryStore() {
  const devices = new Map();   // deviceId -> { deviceId, publicKeyB64, registeredAtEpochSec }
  const envelopes = new Map(); // id -> envelope
  const preKeyBundles = new Map(); // deviceId -> published bundle (public keys only)
  const highestOneTimePreKeyId = new Map(); // deviceId -> highest one-time pre-key id ever published

  return {
    // ---- الأجهزة ----
    getDevice(deviceId) {
      return devices.get(deviceId) || null;
    },
    // إدخال لو مش موجود بس، وبيرجّع السجل المخزّن فعلًا — الطبقة الأعلى
    // بتقارن المفتاح، فتسجيلين متزامنين بمفتاحين مختلفين ما يكتبوش فوق بعض.
    putDevice(device) {
      if (!devices.has(device.deviceId)) devices.set(device.deviceId, device);
      return devices.get(device.deviceId);
    },

    // ---- المغلفات ----
    putEnvelope(envelope) {
      envelopes.set(envelope.id, envelope);
    },
    getEnvelope(id) {
      return envelopes.get(id) || null;
    },
    deleteEnvelope(id) {
      envelopes.delete(id);
    },
    listEnvelopesFor(recipientDeviceId) {
      return Array.from(envelopes.values()).filter((e) => e.recipientDeviceId === recipientDeviceId);
    },
    // فحص شفافية: كل المغلفات المخزّنة — يستخدمه اختبار تسرّب plaintext.
    allEnvelopes() {
      return Array.from(envelopes.values());
    },
    // ---- حزم المفاتيح المسبقة (pre-key bundles) ----
    // مفاتيح عامة بس — مفيش مفتاح خاص ولا محتوى. المفتاح الفردي (one-time)
    // بيتسلّم مرة واحدة وبعدين بيتمسح؛ ده لازم يبقى ذرّي (transaction) في أي
    // تخزين إنتاج حقيقي، عشان طلبين متزامنين ما ياخدوش نفس المفتاح.
    // بيرجّع false لو معرّف المفتاح الفردي مش أعلى من أي معرّف سابق — الشرط
    // جوه التخزين نفسه عشان نشرين متزامنين ما يعدّوش الفحص الاتنين.
    putPreKeyBundle(deviceId, bundle) {
      const prev = highestOneTimePreKeyId.get(deviceId) || 0;
      if (bundle.oneTimePreKeyId <= prev) return false;
      preKeyBundles.set(deviceId, bundle);
      highestOneTimePreKeyId.set(deviceId, bundle.oneTimePreKeyId);
      return true;
    },
    getPreKeyBundle(deviceId) {
      return preKeyBundles.get(deviceId) || null;
    },
    // بيرجّع الحزمة كاملة ويشيل منها المفتاح الفردي في نفس الخطوة. لو المفتاح
    // الفردي اتسلّم قبل كده بيرجّع null — الحزمة نفسها بتفضل لحد ما الجهاز
    // ينشر حزمة جديدة.
    claimPreKeyBundle(deviceId) {
      const bundle = preKeyBundles.get(deviceId);
      if (!bundle || bundle.oneTimePreKeyClaimed) return null;
      preKeyBundles.set(deviceId, { ...bundle, oneTimePreKeyPublicB64: null, oneTimePreKeyClaimed: true });
      return bundle;
    },

    sweepExpired(nowEpochSec) {
      let removed = 0;
      for (const [id, e] of envelopes) {
        if (e.expiresAtEpochSec <= nowEpochSec) {
          envelopes.delete(id);
          removed++;
        }
      }
      return removed;
    },
  };
}

module.exports = { createInMemoryStore };
