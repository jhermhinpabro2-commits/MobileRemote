const http = require('http');
const { WebSocketServer } = require('ws');

const server = http.createServer((req, res) => res.end('RemoteCheck server OK'));
const wss = new WebSocketServer({ server });

const clients = new Map();
const pending = new Set();
const sessions = new Set();

const pairKey = (a, b) => [a, b].sort().join('|');
const send = (ws, obj) => { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); };
const newId = () => {
  let id;
  do { id = String(Math.floor(100000000 + Math.random() * 900000000)); } while (clients.has(id));
  return id;
};

wss.on('connection', (ws) => {
  ws.alive = true;
  ws.on('pong', () => (ws.alive = true));

  ws.on('message', (raw) => {
    let m;
    try { m = JSON.parse(raw); } catch { return; }

    if (m.type === 'register') {
      const id = /^\d{9}$/.test(m.id || '') && !clients.has(m.id) ? m.id : newId();
      ws.id = id;
      clients.set(id, ws);
      return send(ws, { type: 'registered', id });
    }
    if (!ws.id) return;

    const target = clients.get(m.to);
    if (!target || m.to === ws.id) return send(ws, { type: 'error', message: 'Offline o mali ang code' });

    switch (m.type) {
      case 'request':
        pending.add(`${ws.id}>${m.to}`);
        return send(target, { type: 'request', from: ws.id });
      case 'accept':
        if (!pending.delete(`${m.to}>${ws.id}`)) return;
        sessions.add(pairKey(ws.id, m.to));
        return send(target, { type: 'accept', from: ws.id });
      case 'deny':
        if (!pending.delete(`${m.to}>${ws.id}`)) return;
        return send(target, { type: 'deny', from: ws.id });
      case 'end':
        sessions.delete(pairKey(ws.id, m.to));
        return send(target, { type: 'end', from: ws.id });
      case 'signal':
        if (!sessions.has(pairKey(ws.id, m.to))) return;
        return send(target, { type: 'signal', from: ws.id, data: m.data });
    }
  });

  ws.on('close', () => {
    if (ws.id && clients.get(ws.id) === ws) clients.delete(ws.id);
  });
});

setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.alive) return ws.terminate();
    ws.alive = false;
    ws.ping();
  });
}, 30000);

server.listen(process.env.PORT || 8080, () => console.log('RemoteCheck server running'));
