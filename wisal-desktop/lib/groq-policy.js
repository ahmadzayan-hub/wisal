'use strict';
// Kept byte-identical in the standalone Telegram surface; tested in test/groq-policy.test.js.
const catalog = Object.freeze({
  reviewed: '2026-10-23', source: 'https://console.groq.com/docs/models',
  limits: 'https://console.groq.com/docs/rate-limits',
  models: ['openai/gpt-oss-20b', 'openai/gpt-oss-120b'],
  retired: ['llama-3.3-70b-versatile', 'llama-3.1-8b-instant'],
});
class GroqError extends Error { constructor(code) { super(code); this.code = code; } }
function publicMessage(error) {
  return ({ disabled: 'Groq غير مفعّل. راجع خطة حسابك وحدودها قبل التفعيل.',
    missing_key: 'مفتاح Groq غير مسجّل.', retired_model: 'النموذج متوقف. اختر بديلاً بنفسك في الإعدادات.',
    model: 'النموذج غير متاح. راجع إعدادات النموذج.', authentication: 'مفتاح Groq غير صالح.',
    permission: 'الحساب لا يسمح بهذا النموذج.', quota: 'انتهت حصة الحساب.', rate_limit: 'تم بلوغ حد الطلبات. حاول لاحقاً.',
    too_long: 'السياق طويل جداً. اختصره ثم أعد المحاولة.', timeout: 'انتهت مهلة الاتصال.',
    invalid_response: 'لم يرجع Groq نصاً صالحاً.', service: 'خدمة Groq غير متاحة مؤقتاً.',
    cancelled: 'تم إلغاء الطلب.', network: 'تعذّر الاتصال بالشبكة.' })[error.code] || 'تعذّر إكمال طلب Groq.';
}
async function complete(options, fetchImpl = globalThis.fetch, sleep = ms => new Promise(resolve => setTimeout(resolve, ms))) {
  const { enabled, apiKey, model, messages, signal } = options;
  if (!enabled) throw new GroqError('disabled');
  if (!apiKey) throw new GroqError('missing_key');
  if (catalog.retired.includes(model)) throw new GroqError('retired_model');
  if (!catalog.models.includes(model)) throw new GroqError('model');
  if (messages.reduce((n, m) => n + String(m.content).length, 0) > 16000) throw new GroqError('too_long');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 45000);
  const cancel = () => controller.abort();
  signal?.addEventListener('abort', cancel, { once: true });
  if (signal?.aborted) controller.abort();
  const body = JSON.stringify({ model, messages, temperature: options.temperature ?? 0.8,
    max_completion_tokens: 1024, reasoning_effort: 'low' });
  try {
    for (let attempt = 0; attempt < 2; attempt++) {
      if (controller.signal.aborted) throw new GroqError(signal?.aborted ? 'cancelled' : 'timeout');
      let response;
      try {
        response = await fetchImpl('https://api.groq.com/openai/v1/chat/completions', {
          method: 'POST', headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
          body, signal: controller.signal,
        });
      } catch (_) { throw new GroqError(controller.signal.aborted ? (signal?.aborted ? 'cancelled' : 'timeout') : 'network'); }
      const data = await response.json().catch(() => null);
      if (response.ok) {
        const content = data?.choices?.[0]?.message?.content;
        if (typeof content !== 'string' || !content.trim()) throw new GroqError('invalid_response');
        return content.trim();
      }
      const providerCode = data?.error?.code;
      const reason = providerCode === 'model_decommissioned' ? 'retired_model'
        : ['insufficient_quota', 'quota_exceeded', 'billing_hard_limit_reached'].includes(providerCode) ? 'quota'
        : response.status === 401 ? 'authentication' : response.status === 403 ? 'permission'
        : [400, 404].includes(response.status) ? 'model' : response.status === 429 ? 'rate_limit'
        : response.status >= 500 ? 'service' : 'request';
      const retryHeader = response.headers?.get('retry-after');
      const retryMs = retryHeader == null ? 1000 : Number(retryHeader) * 1000;
      if (attempt === 0 && ['rate_limit', 'service'].includes(reason) && Number.isFinite(retryMs) && retryMs >= 0 && retryMs <= 10000) {
        await sleep(retryMs); continue;
      }
      throw new GroqError(reason);
    }
    throw new GroqError('service');
  } finally { clearTimeout(timer); signal?.removeEventListener('abort', cancel); }
}
module.exports = { catalog, GroqError, complete, publicMessage };
