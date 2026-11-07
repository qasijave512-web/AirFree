// AirFree relay
// The phone keeps one WebSocket open to this server. When you open
//   https://<this-server>/d/<device-id>/
// in a browser, every request is forwarded to the phone, which answers it
// and sends the reply back. The PIN login still happens on the phone.

const http = require('http');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 3000;

// deviceId -> { ws, pending: Map<number, ServerResponse>, nextId, alive }
const phones = new Map();

function frame(id, chunk) {
  const head = Buffer.alloc(4);
  head.writeUInt32BE(id, 0);
  return Buffer.concat([head, chunk]);
}

function clientIp(req) {
  const xff = req.headers['x-forwarded-for'];
  if (xff) {
    const parts = xff.split(',').map((s) => s.trim());
    return parts[parts.length - 1];
  }
  return req.socket.remoteAddress || '';
}

function text(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end(body);
}

const server = http.createServer((req, res) => {
  if (req.url === '/' || req.url === '/health') {
    return text(res, 200, 'AirFree relay is running.\n');
  }

  const m = req.url.match(/^\/d\/([0-9a-f]{16,64})(\/.*)?$/);
  if (!m) return text(res, 404, 'Not found');

  const deviceId = m[1];
  const rest = m[2];
  if (!rest) {
    res.writeHead(301, { Location: '/d/' + deviceId + '/' });
    return res.end();
  }

  const phone = phones.get(deviceId);
  if (!phone || phone.ws.readyState !== 1) {
    return text(
      res,
      503,
      'Your phone is not connected.\nOpen AirFree on the phone, make sure the server is ON and the relay address is saved.'
    );
  }

  const id = phone.nextId++;
  phone.pending.set(id, res);

  const headers = {};
  if (req.headers['content-type']) headers['content-type'] = req.headers['content-type'];
  if (req.headers['authorization']) headers['authorization'] = req.headers['authorization'];

  const hasBody =
    parseInt(req.headers['content-length'] || '0', 10) > 0 || !!req.headers['transfer-encoding'];

  phone.ws.send(
    JSON.stringify({ t: 'req', id, method: req.method, path: rest, headers, hasBody, ip: clientIp(req) })
  );

  if (hasBody) {
    req.on('data', (chunk) => {
      req.pause();
      phone.ws.send(frame(id, chunk), { binary: true }, () => req.resume());
    });
    req.on('end', () => {
      if (phone.ws.readyState === 1) phone.ws.send(JSON.stringify({ t: 'end', id }));
    });
  }

  res.on('close', () => {
    if (phone.pending.has(id)) {
      phone.pending.delete(id);
      try {
        phone.ws.send(JSON.stringify({ t: 'abort', id }));
      } catch (e) {}
    }
  });
});

const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const u = new URL(req.url, 'http://x');
  const id = u.searchParams.get('id') || '';
  if (u.pathname !== '/tunnel' || !/^[0-9a-f]{16,64}$/.test(id)) {
    return socket.destroy();
  }
  wss.handleUpgrade(req, socket, head, (ws) => onPhone(id, ws));
});

function onPhone(id, ws) {
  const old = phones.get(id);
  if (old) {
    try {
      old.ws.terminate();
    } catch (e) {}
  }
  const phone = { ws, pending: new Map(), nextId: 1, alive: true };
  phones.set(id, phone);

  ws.on('pong', () => {
    phone.alive = true;
  });

  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      if (data.length < 4) return;
      const res = phone.pending.get(data.readUInt32BE(0));
      if (!res) return;
      const ok = res.write(data.subarray(4));
      if (!ok && ws._socket) {
        ws._socket.pause();
        res.once('drain', () => ws._socket && ws._socket.resume());
      }
      return;
    }
    let msg;
    try {
      msg = JSON.parse(data.toString());
    } catch (e) {
      return;
    }
    const res = phone.pending.get(msg.id);
    if (!res) return;
    if (msg.t === 'res') {
      res.writeHead(msg.status, msg.headers || {});
    } else if (msg.t === 'end') {
      phone.pending.delete(msg.id);
      res.end();
    } else if (msg.t === 'err') {
      phone.pending.delete(msg.id);
      if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'text/plain' });
      res.end(msg.msg || 'Phone error');
    }
  });

  ws.on('close', () => {
    for (const res of phone.pending.values()) {
      try {
        if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'text/plain' });
        res.end('Phone disconnected');
      } catch (e) {}
    }
    phone.pending.clear();
    if (phones.get(id) === phone) phones.delete(id);
  });

  ws.on('error', () => {});
}

// Drop phones that stopped answering pings.
setInterval(() => {
  for (const p of phones.values()) {
    if (!p.alive) {
      p.ws.terminate();
      continue;
    }
    p.alive = false;
    try {
      p.ws.ping();
    } catch (e) {}
  }
}, 30000);

server.listen(PORT, () => console.log('AirFree relay listening on ' + PORT));
