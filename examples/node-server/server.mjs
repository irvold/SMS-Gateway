// Minimal SMS-gateway-server til GL-SMS — ingen afhængigheder, kun Node 18+.
//
//   node server.mjs
//   GATEWAY_KEY=glsms_hemmelig SENDER_KEY=glsend_hemmelig PORT=4000 node server.mjs
//
// Viser kontrakten i docs/API.md i sin enkleste form: en kø i hukommelsen, telefonens tre kald (ping, poll, report)
// og afsenderens kald (læg i kø, se status, træk tilbage). Intet gemmes mellem kørsler, og der er hverken https
// eller timegrænser — brug det som udgangspunkt, ikke som produktion. Mangler: prioritet, kvittering-overgange
// i detaljer, oprydning af gamle beskeder, omskrivning af typografiske tegn og tilladte landekoder (se docs/API.md).
import crypto from 'node:crypto';
import http from 'node:http';

const PORT = Number(process.env.PORT) || 4000;
const random = (prefix) => `${prefix}_${crypto.randomBytes(18).toString('base64url')}`;
const GATEWAY_KEY = process.env.GATEWAY_KEY || random('glsms');
const SENDER_KEY = process.env.SENDER_KEY || random('glsend');

const LEASE_MS = 90_000; // så længe telefonen har til at kvittere, før beskeden gives videre
const MAX_ATTEMPTS = 2;
const MAX_TEXT = 480;

/** @type {Map<string, any>} */
const messages = new Map();
let counter = 0;
const waiting = new Set(); // ventende long-polls, vækkes når der kommer en besked
let gatewaySeen = null;

// "12 34 56 78" → "+4512345678" (otte cifre regnes som danske); null hvis det ikke ligner et nummer.
function normalizePhone(raw) {
  let s = String(raw ?? '').trim().replace(/[\s().-]/g, '');
  if (s.startsWith('00')) s = `+${s.slice(2)}`;
  if (/^\d{8}$/.test(s)) s = `+45${s}`;
  return /^\+[1-9]\d{7,14}$/.test(s) ? s : null;
}

const send = (res, status, body) => {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
};
const bearer = (req) => (req.headers.authorization || '').replace(/^Bearer /, '');
const same = (a, b) => a.length === b.length && crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
const readJson = async (req) => {
  let raw = '';
  for await (const chunk of req) raw += chunk;
  try { return JSON.parse(raw || '{}'); } catch { return null; }
};

// Udløb og beskeder, telefonen hentede men aldrig kvitterede for.
function housekeeping() {
  const now = Date.now();
  for (const m of messages.values()) {
    if ((m.status === 'queued' || m.status === 'sending') && m.expiresAt < now) {
      m.status = 'expired';
    } else if (m.status === 'sending' && m.leasedUntil < now) {
      if (m.attempts < MAX_ATTEMPTS) m.status = 'queued';
      else { m.status = 'failed'; m.error = 'Ingen kvittering fra telefonen'; }
    }
    if (m.kind !== 'free' && !['queued', 'sending'].includes(m.status)) m.text = ''; // koder gemmes ikke
  }
}

function claim(max = 5) {
  housekeeping();
  const now = Date.now();
  const out = [];
  for (const m of messages.values()) {
    if (out.length >= max) break;
    if (m.status !== 'queued') continue;
    m.status = 'sending';
    m.attempts += 1;
    m.leasedUntil = now + LEASE_MS;
    out.push({ id: m.id, to: m.to, text: m.text, kind: m.kind, expiresAt: new Date(m.expiresAt).toISOString() });
  }
  return out;
}

const view = (m) => ({
  id: m.id, to: m.to, kind: m.kind, status: m.status, error: m.error ?? null, attempts: m.attempts,
  createdAt: new Date(m.createdAt).toISOString(), expiresAt: new Date(m.expiresAt).toISOString(),
  sentAt: m.sentAt ? new Date(m.sentAt).toISOString() : null,
  deliveredAt: m.deliveredAt ? new Date(m.deliveredAt).toISOString() : null,
  ...(m.kind === 'free' ? { text: m.text } : {}),
});

// Hvilke tilstande en kvittering må ændre fra (telefonen kan kvittere forsinket).
const REPORT_FROM = {
  sent: ['sending', 'expired', 'failed'],
  delivered: ['sending', 'sent', 'expired', 'failed'],
  failed: ['sending', 'sent', 'expired'],
};

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const path = url.pathname.replace(/\/+$/, '') || '/';
  try {
    // ---- Telefonen (GATEWAY_KEY) ----
    if (path.startsWith('/api/sms-gateway')) {
      if (!same(bearer(req), GATEWAY_KEY)) return send(res, 401, { error: 'Ukendt nøgle' });
      gatewaySeen = Date.now();

      if (req.method === 'GET' && path === '/api/sms-gateway/ping') {
        return send(res, 200, { ok: true, gateway: { id: 'telefon', name: 'Min telefon' } });
      }

      if (req.method === 'GET' && path === '/api/sms-gateway/poll') {
        // Long-poll: svar med det samme, hvis der er beskeder — ellers vent op til `wait` sekunder.
        const wait = Math.min(Math.max(Number(url.searchParams.get('wait')) || 0, 0), 30) * 1000;
        let found = claim();
        if (!found.length && wait) {
          found = await new Promise((resolve) => {
            const done = () => { clearTimeout(timer); waiting.delete(done); resolve(claim()); };
            const timer = setTimeout(done, wait);
            waiting.add(done);
            req.on('close', () => { clearTimeout(timer); waiting.delete(done); });
          });
        }
        if (res.destroyed) return;
        gatewaySeen = Date.now();
        return send(res, 200, { messages: found, serverTime: new Date().toISOString() });
      }

      if (req.method === 'POST' && path === '/api/sms-gateway/report') {
        const body = await readJson(req);
        const reports = Array.isArray(body?.reports) ? body.reports : body?.id ? [body] : null;
        if (!reports || reports.length > 50) return send(res, 400, { error: 'Ugyldig kvittering' });
        let applied = 0;
        for (const r of reports) {
          const m = messages.get(r?.id);
          const from = REPORT_FROM[r?.status];
          if (!m || !from || !from.includes(m.status)) continue; // ukendt id eller samme kvittering to gange: ingen skade
          m.status = r.status;
          m.error = r.status === 'failed' ? String(r.error || 'Fejl på telefonen').slice(0, 300) : null;
          if (r.status === 'sent') m.sentAt = Date.now();
          if (r.status === 'delivered') m.deliveredAt = Date.now();
          applied += 1;
        }
        return send(res, 200, { ok: true, applied });
      }
    }

    // ---- Afsendere (SENDER_KEY) ----
    if (path === '/api/sms' || path.startsWith('/api/sms/')) {
      if (!same(bearer(req), SENDER_KEY)) return send(res, 401, { error: 'Nøglen er forkert' });
      housekeeping();

      if (req.method === 'POST' && path === '/api/sms') {
        const b = await readJson(req);
        const to = normalizePhone(b?.to);
        const text = String(b?.text ?? '').trim();
        if (!to) return send(res, 400, { error: 'Ugyldigt telefonnummer' });
        if (!text) return send(res, 400, { error: 'Teksten er tom' });
        if (text.length > MAX_TEXT) return send(res, 400, { error: `Teksten er for lang (maks. ${MAX_TEXT} tegn)` });
        const ttl = Math.min(Math.max(Number(b?.ttlSeconds) || 600, 30), 7 * 86400);
        const id = `m${String(++counter).padStart(6, '0')}`;
        const m = {
          id, to, text, kind: String(b?.kind || 'free'), status: 'queued', attempts: 0,
          createdAt: Date.now(), expiresAt: Date.now() + ttl * 1000,
        };
        messages.set(id, m);
        for (const wake of [...waiting]) wake(); // væk ventende long-polls
        return send(res, 201, {
          id, to, status: m.status, expiresAt: new Date(m.expiresAt).toISOString(),
          gatewayOnline: !!gatewaySeen && Date.now() - gatewaySeen < 90_000, // så afsenderen kan falde tilbage på mail
        });
      }
      if (req.method === 'GET' && path === '/api/sms') {
        const limit = Math.min(Math.max(Number(url.searchParams.get('limit')) || 50, 1), 200);
        return send(res, 200, { messages: [...messages.values()].reverse().slice(0, limit).map(view) });
      }
      const id = path.startsWith('/api/sms/') ? path.slice('/api/sms/'.length) : null;
      if (id && req.method === 'GET') {
        const m = messages.get(id);
        return m ? send(res, 200, view(m)) : send(res, 404, { error: 'SMS ikke fundet' });
      }
      if (id && req.method === 'DELETE') {
        const m = messages.get(id);
        if (!m || m.status !== 'queued') return send(res, 409, { error: 'Kan kun trækkes tilbage, mens den står i kø' });
        m.status = 'cancelled';
        m.text = '';
        return send(res, 200, { ok: true });
      }
    }

    send(res, 404, { error: 'Ukendt adresse' });
  } catch (err) {
    console.error(err);
    if (!res.headersSent) send(res, 500, { error: 'Serverfejl' });
  }
});

server.listen(PORT, () => {
  console.log(`SMS-gateway-server på http://localhost:${PORT}`);
  console.log(`  Telefonens nøgle (i appen):  ${GATEWAY_KEY}`);
  console.log(`  Afsenderens nøgle (curl):    ${SENDER_KEY}`);
});
