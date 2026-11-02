// اختيار التخزين حسب البيئة — منفصل عن sharedStore عشان يتختبر من غير ما
// يفتح اتصال حقيقي.
const { createInMemoryStore } = require('./store');
const { StorageNotConfiguredError } = require('./storageError');

function isProduction(env) {
  return env.VERCEL_ENV === 'production' || env.NODE_ENV === 'production';
}

function createRefusingStore() {
  const refuse = async () => { throw new StorageNotConfiguredError(); };
  return new Proxy({}, { get: () => refuse });
}

function selectStore(env = process.env) {
  if (env.DATABASE_URL) {
    // eslint-disable-next-line global-require
    const { Pool } = require('pg');
    // eslint-disable-next-line global-require
    const { createPgStore } = require('./pgStore');
    return createPgStore(new Pool({ connectionString: env.DATABASE_URL, max: 3 }));
  }
  if (isProduction(env)) return createRefusingStore();
  return createInMemoryStore();
}

module.exports = { selectStore, isProduction };
