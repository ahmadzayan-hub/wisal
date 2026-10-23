'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { complete, catalog } = require('../lib/groq-policy');
const opts = { enabled: true, apiKey: 'fake', model: catalog.models[0], messages: [{ role: 'user', content: 'Fictional test' }] };
const ok = () => ({ ok: true, json: async () => ({ choices: [{ message: { content: 'Hello' } }] }) });
test('portable policy copies remain identical', () => assert.equal(fs.readFileSync(path.join(__dirname, '../lib/groq-policy.js'), 'utf8'), fs.readFileSync(path.join(__dirname, '../../telegram-wife-assistant/groq-policy.js'), 'utf8')));
test('disabled, missing key and retired model never call provider', async () => {
  for (const [patch, code] of [[{ enabled: false }, 'disabled'], [{ apiKey: '' }, 'missing_key'], [{ model: catalog.retired[0] }, 'retired_model']]) {
    await assert.rejects(complete({ ...opts, ...patch }, () => { throw new Error('must not call'); }), { code });
  }
});
test('rate limit honors retry after once', async () => {
  let calls = 0; const sleeps = [];
  const text = await complete(opts, async () => ++calls === 1 ? { ok: false, status: 429, headers: new Headers({ 'retry-after': '2' }), json: async () => ({}) } : ok(), async ms => sleeps.push(ms));
  assert.equal(text, 'Hello'); assert.equal(calls, 2); assert.deepEqual(sleeps, [2000]);
});
test('quota, auth and retired failures are distinct and sanitized', async () => {
  for (const [status, providerCode, code] of [[429, 'insufficient_quota', 'quota'], [401, '', 'authentication'], [400, 'model_decommissioned', 'retired_model']]) {
    let calls = 0;
    await assert.rejects(complete(opts, async () => { calls++; return { ok: false, status, headers: new Headers(), json: async () => ({ error: { code: providerCode, message: 'private prompt' } }) }; }), e => e.code === code && !e.message.includes('private'));
    assert.equal(calls, 1);
  }
});
test('empty successful response is failure', async () => assert.rejects(complete(opts, async () => ({ ok: true, json: async () => ({}) })), { code: 'invalid_response' }));
test('pre-cancelled request cannot call provider', async () => {
  const controller = new AbortController(); controller.abort();
  await assert.rejects(complete({ ...opts, signal: controller.signal }, () => { throw Error('must not call'); }), { code: 'cancelled' });
});
