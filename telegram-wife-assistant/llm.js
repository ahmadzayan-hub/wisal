// Hosted generation is opt-in. No paid or cross-provider fallback.
const config = require('./config');
const policy = require('./groq-policy');
async function complete(messages, opts = {}) {
  try {
    return await policy.complete({ enabled: process.env.GROQ_ENABLED === 'true',
      apiKey: process.env.GROQ_API_KEY, model: process.env.GROQ_MODEL || config.groqModel,
      messages, temperature: opts.temperature, signal: opts.signal });
  } catch (error) {
    const safe = new Error(policy.publicMessage(error)); safe.code = error.code; throw safe;
  }
}
module.exports = { complete };
