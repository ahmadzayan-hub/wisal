// الإنتاج من غير DATABASE_URL لازم يرفض (503) بدل ما يشتغل بصمت على تخزين
// في الذاكرة بيضيع مع كل cold start. البيئة بتتظبط قبل تحميل أي handler.
process.env.NODE_ENV = 'production';
delete process.env.DATABASE_URL;
delete process.env.VERCEL_ENV;

const assert = require('assert');
const { generateKeyPairSync, createSign } = require('crypto');
const { selectStore } = require('../lib/selectStore');
const { StorageNotConfiguredError } = require('../lib/storageError');

function fakeRes() {
  const res = { statusCode: 0, body: null };
  res.status = (code) => { res.statusCode = code; return res; };
  res.json = (body) => { res.body = body; return res; };
  return res;
}

(async () => {
  // ---- اختيار التخزين حسب البيئة ----
  await assert.rejects(selectStore({ NODE_ENV: 'production' }).getDevice('x'), StorageNotConfiguredError);
  await assert.rejects(selectStore({ VERCEL_ENV: 'production' }).putEnvelope({}), StorageNotConfiguredError);
  assert.strictEqual(await selectStore({}).getDevice('x'), null); // تطوير محلي: في الذاكرة

  // ---- كل handler بيرجّع 503 من غير ما يكشف تفاصيل ----
  // التسجيل بيتحقق من التوقيع قبل التخزين، فمحتاج توقيع صحيح عشان يوصل للتخزين.
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const signer = createSign('SHA256'); signer.update('wisal-direct-register:a'); signer.end();
  const cases = [
    ['devices', 'POST', { body: {
      deviceId: 'a',
      publicKeyB64: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
      signatureB64: signer.sign(privateKey).toString('base64'),
    } }],
    ['envelopes', 'POST', { body: { senderDeviceId: 'a', recipientDeviceId: 'b', ciphertextB64: 'c', backend: 'SIGNAL', expiresAtEpochSec: 9e9, signatureB64: 'd' } }],
    ['inbox', 'GET', { query: { deviceId: 'a', timestamp: '1', signatureB64: 'c' } }],
    ['ack', 'POST', { body: { deviceId: 'a', envelopeId: 'b', signatureB64: 'c' } }],
    ['prekeys-claim', 'POST', { body: { requesterDeviceId: 'a', targetDeviceId: 'b', timestamp: 1, signatureB64: 'c' } }],
    ['prekeys-status', 'POST', { body: { deviceId: 'a', timestamp: 1, requestNonce: '00112233445566778899aabbccddeeff', signatureB64: 'c' } }],
    ['revoke', 'POST', { body: { deviceId: 'a', timestamp: 1, signatureB64: 'c' } }],
  ];
  for (const [name, method, req] of cases) {
    // eslint-disable-next-line global-require, import/no-dynamic-require
    const handler = require(`../api/${name}`);
    const res = fakeRes();
    await handler({ method, ...req }, res);
    assert.strictEqual(res.statusCode, 503, `${name} must fail closed, got ${res.statusCode}`);
    assert.deepStrictEqual(res.body, { ok: false, error: 'relay storage unavailable' });
  }

  console.log('fail-closed.test.js: all assertions passed ✅');
})().catch((err) => { console.error(err); process.exit(1); });
