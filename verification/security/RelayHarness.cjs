// Loopback-only synthetic integration fixture. Uses the real API handlers.
// Never deploy this fixture or expose it outside the test process.
delete process.env.DATABASE_URL;
process.env.NODE_ENV = 'test';
process.env.VERCEL_ENV = 'development';
const http = require('node:http');
const path = require('node:path');
const relay = path.resolve(__dirname, '../../wisal-direct-relay');
const handlers = Object.fromEntries(['devices', 'prekeys', 'prekeys-claim', 'envelopes', 'inbox', 'ack', 'revoke']
  .map(name => ['/api/' + name, require(path.join(relay, 'api', name + '.js'))]));
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  const handler = handlers[url.pathname];
  if (!handler) { res.writeHead(404); res.end(); return; }
  let body = '';
  for await (const chunk of req) {
    body += chunk;
    if (body.length > 2 * 1024 * 1024) { res.writeHead(413); res.end(); return; }
  }
  req.body = body; req.query = Object.fromEntries(url.searchParams);
  res.status = code => { res.statusCode = code; return res; };
  res.json = value => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(value)); };
  try { await handler(req, res); } catch { res.status(500).json({ ok: false }); }
});
server.listen(0, '127.0.0.1', () => console.log('READY ' + server.address().port));
