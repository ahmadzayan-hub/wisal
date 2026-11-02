// لوجر بحارس صريح: عمره ما يسجّل غير الحقول المسموحة (معرّفات، أوقات، أعداد،
// أكواد أخطاء) — أي محاولة تسجيل حقل غير مسموح (زي ciphertextB64 أو أي محتوى)
// بترمي استثناء بدل ما تسجّل بصمت. ده تنفيذ فعلي لقاعدة المنتج: "السيرفر ما
// يسجّلش رسائل نصية ولا مفاتيح فك تشفير المرفقات ولا محتوى إشعار كامل."

const ALLOWED_FIELDS = new Set([
  'event', 'deviceId', 'senderDeviceId', 'recipientDeviceId', 'targetDeviceId', 'envelopeId',
  'count', 'reason', 'atEpochSec', 'backend',
]);

function safeLog(event, fields = {}) {
  for (const key of Object.keys(fields)) {
    if (!ALLOWED_FIELDS.has(key)) {
      throw new Error(`safeLog: field "${key}" is not in the logging allowlist (possible content leak)`);
    }
  }
  const line = JSON.stringify({ event, ...fields });
  // eslint-disable-next-line no-console
  console.log(line);
  return line;
}

module.exports = { safeLog, ALLOWED_FIELDS };
