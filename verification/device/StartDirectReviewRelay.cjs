const { createHash } = require('node:crypto');
const { createServer } = require('node:https');
const { readFileSync, readdirSync } = require('node:fs');
const path = require('node:path');

const relay = path.resolve(__dirname, '../../wisal-direct-relay');
const { Pool } = require(path.join(relay, 'node_modules/pg'));
const { version } = require(path.join(relay, 'package.json'));
const database = new URL(process.env.DATABASE_URL || '');
const port = Number(process.env.WISAL_REVIEW_PORT || 8443);
if (database.protocol !== 'postgresql:' || database.hostname !== '127.0.0.1' ||
    database.pathname !== '/wisal_acceptance_test' || database.search || database.hash ||
    !Number.isInteger(port) || port < 1024 || port > 65535) {
  throw new Error('Use a dedicated loopback PostgreSQL database named wisal_acceptance_test and a valid HTTPS port.');
}

const routes = ['devices', 'prekeys', 'prekeys-status', 'prekeys-claim', 'envelopes', 'inbox', 'ack', 'revoke'];
const handlers = new Map(routes.map(name => ['/api/' + name, require(path.join(relay, 'api', name + '.js'))]));
const faultRoutes = new Set(['/api/prekeys', '/api/envelopes', '/api/ack']);
const faultPath = process.env.WISAL_REVIEW_FAULT_FILE;
const appliedFaults = new Set();

function faultFor(route) {
  if (!faultPath || !faultRoutes.has(route)) return null;
  try {
    const fault = JSON.parse(readFileSync(faultPath, 'utf8'));
    return typeof fault.id === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(fault.id) && fault.route === route &&
      ['before', 'after'].includes(fault.at) && !appliedFaults.has(fault.id) ? fault : null;
  } catch (error) {
    if (error.code === 'ENOENT' || error instanceof SyntaxError) return null;
    throw error;
  }
}

function drop(fault, req) {
  appliedFaults.add(fault.id);
  console.log(JSON.stringify({ event: 'review_response_dropped', route: fault.route, point: fault.at, id: fault.id }));
  req.socket.destroy();
}

function sha256(data) { return createHash('sha256').update(data).digest('hex'); }

function sourceDigest() {
  const sourceFiles = ['package.json', 'package-lock.json'];
  for (const dir of ['api', 'lib']) {
    for (const name of readdirSync(path.join(relay, dir)).filter(file => file.endsWith('.js')).sort()) {
      sourceFiles.push(`${dir}/${name}`);
    }
  }
  const sources = sourceFiles.map(file => [file, sha256(readFileSync(path.join(relay, file)))]);
  sources.push(['StartDirectReviewRelay.cjs', sha256(readFileSync(__filename))]);
  return sha256(JSON.stringify(sources));
}

async function start() {
  const store = require(path.join(relay, 'lib/sharedStore'));
  await store.getDevice('__review_schema_probe__');
  const pool = new Pool({ connectionString: database.href });
  let descriptor;
  try {
    const versionResult = await pool.query('SHOW server_version');
    const columns = await pool.query(`SELECT table_name, column_name, data_type, is_nullable, column_default
      FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ANY($1)
      ORDER BY table_name, ordinal_position`, [['relay_devices', 'relay_envelopes', 'relay_prekey_bundles']]);
    descriptor = { relayVersion: version, sourceSha256: sourceDigest(),
      postgres: versionResult.rows[0].server_version, schemaSha256: sha256(JSON.stringify(columns.rows)) };
  } finally { await pool.end(); }

  const server = createServer({ key: readFileSync(process.env.WISAL_REVIEW_TLS_KEY),
    cert: readFileSync(process.env.WISAL_REVIEW_TLS_CERT), minVersion: 'TLSv1.2' }, async (req, res) => {
    const url = new URL(req.url, 'https://localhost');
    res.setHeader('Cache-Control', 'no-store');
    res.status = code => { res.statusCode = code; return res; };
    res.json = value => {
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify(value));
    };
    if (url.pathname === '/__wisal_review' && req.method === 'GET') return res.json(descriptor);
    const handler = handlers.get(url.pathname);
    if (!handler) return res.status(404).json({ ok: false });
    try {
      let size = 0;
      const chunks = [];
      for await (const chunk of req) {
        size += chunk.length;
        if (size > 256 * 1024) return res.status(413).json({ ok: false });
        chunks.push(chunk);
      }
      req.body = Buffer.concat(chunks).toString('utf8');
      req.query = Object.fromEntries(url.searchParams);
      const before = faultFor(url.pathname);
      if (before?.at === 'before') return drop(before, req);
      const json = res.json;
      res.json = value => {
        const after = faultFor(url.pathname);
        if (after?.at === 'after' && value.ok === true) return drop(after, req);
        json(value);
      };
      await handler(req, res);
    } catch {
      if (!res.headersSent && !res.destroyed) res.status(500).json({ ok: false });
    }
  });
  server.requestTimeout = 15_000;
  server.listen(port, '127.0.0.1', () => console.log(JSON.stringify({ event: 'review_relay_ready', port, ...descriptor })));
}

start().catch(() => {
  console.error('Review relay could not start. Check local PostgreSQL and TLS inputs.');
  process.exitCode = 1;
});
