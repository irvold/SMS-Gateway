// Røgtest af SMS-gateway-API'et (backend/src/routes/sms.js) — spiller telefonens og afsenderens rolle.
//
//   node server_smoke_test.mjs <server> <telefon-nøgle glsms_…> <sms-api-nøgle> [--slow]
//   fx: node server_smoke_test.mjs http://localhost:4100 glsms_abc smstestkey
//
// Kør kun mod en testserver: testen lægger SMS'er i køen (og henter dem selv igen, så ingen rigtig
// telefon bør være forbundet). --slow tester også, at en udløbet besked ikke sendes (tager ~35 sek.).
const [base, gwKey, apiKey] = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const slow = process.argv.includes('--slow');
if (!base || !gwKey || !apiKey) {
  console.error('Brug: node server_smoke_test.mjs <server> <telefon-nøgle> <sms-api-nøgle> [--slow]');
  process.exit(2);
}

let failed = 0;
const check = (ok, label, extra = '') => {
  console.log(`${ok ? 'OK  ' : 'FEJL'} ${label}${!ok && extra ? ` — ${extra}` : ''}`);
  if (!ok) failed += 1;
};

const call = async (path, { method = 'GET', key, body } = {}) => {
  const res = await fetch(base + path, {
    method,
    headers: { ...(key ? { Authorization: `Bearer ${key}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  let json = null;
  try { json = await res.json(); } catch { /* ingen krop */ }
  return { status: res.status, json };
};
const phone = (path, opts = {}) => call(path, { ...opts, key: gwKey });
const sender = (path, opts = {}) => call(path, { ...opts, key: apiKey });
// Hvert kørsel bruger sine egne numre, så timegrænsen fra en tidligere kørsel ikke driller.
const run = String(Math.floor(Math.random() * 9000) + 1000);
const num = (n) => `+45${String(n).padStart(2, '0')}${run}${String(Math.floor(Math.random() * 90) + 10)}`.slice(0, 11);

// Tøm evt. gamle beskeder, så testen starter rent.
while ((await phone('/api/sms-gateway/poll?wait=0')).json?.messages?.length) { /* tømt */ }

// ---- Adgang ----
check((await phone('/api/sms-gateway/ping')).json?.ok === true, 'ping med rigtig nøgle');
check((await call('/api/sms-gateway/ping', { key: 'forkert' })).status === 401, 'forkert telefon-nøgle afvises (401)');
check((await call('/api/sms', { method: 'POST', body: { to: '12345678', text: 'hej' } })).status === 401, 'send uden nøgle afvises (401)');
check((await call('/api/sms', { method: 'POST', key: 'forkert', body: { to: '12345678', text: 'hej' } })).status === 401, 'send med forkert nøgle afvises (401)');
check((await call('/api/sms', { key: gwKey })).status === 401, 'telefonens nøgle kan ikke bruges som afsender-nøgle');

// ---- Validering ----
check((await sender('/api/sms', { method: 'POST', body: { to: '+4670123456', text: 'x' } })).status === 400, 'udenlandsk nummer afvises');
check((await sender('/api/sms', { method: 'POST', body: { to: '123', text: 'x' } })).status === 400, 'for kort nummer afvises');
check((await sender('/api/sms', { method: 'POST', body: { to: '12345678', text: '   ' } })).status === 400, 'tom tekst afvises');
check((await sender('/api/sms', { method: 'POST', body: { to: '12345678', text: 'x'.repeat(481) } })).status === 400, 'for lang tekst afvises');

// ---- Typografiske tegn skrives om til GSM-alfabetet (ellers koster SMS'en 70 tegn i stedet for 160) ----
const typo = await sender('/api/sms', { method: 'POST', body: { to: num(17), text: 'A — B ’c’ “d” … e', kind: 'free' } });
check((await sender(`/api/sms/${typo.json?.id}`)).json?.text === 'A - B \'c\' "d" ... e', 'tankestreg, krøllede citationstegn og … skrives om');
await sender(`/api/sms/${typo.json?.id}`, { method: 'DELETE' });

// ---- Forløb: kode → hent → kvittér ----
const codeTo = num(11);
const code = await sender('/api/sms', { method: 'POST', body: { to: codeTo.slice(3), text: 'Gøngelandet: Din bekræftelseskode er 123456. Æ Ø Å', kind: 'verification', ref: 'evt-1' } });
check(code.status === 201 && code.json?.to === codeTo, 'kode lagt i kø; nummer normaliseret til E.164', JSON.stringify(code.json));
check(code.json?.gatewayOnline === true, 'svaret siger, at en telefon er online');

const free = await sender('/api/sms', { method: 'POST', body: { to: num(12), text: 'Fri besked med æøå', kind: 'free' } });
check(free.status === 201, 'fri besked lagt i kø');

const polled = await phone('/api/sms-gateway/poll?wait=2&battery=87&v=test');
const got = polled.json?.messages ?? [];
check(got.length === 2, 'telefonen henter begge beskeder', JSON.stringify(polled.json));
check(got[0]?.id === code.json?.id, 'koden kommer før den frie besked (prioritet)');
check(got[0]?.text.includes('Æ Ø Å') && got[1]?.text === 'Fri besked med æøå', 'tekst med æøå er intakt');
check((await sender(`/api/sms/${code.json.id}`)).json?.status === 'sending', 'status er "sending" efter hentning');
check((await phone('/api/sms-gateway/poll?wait=0')).json?.messages?.length === 0, 'samme besked udleveres ikke to gange');

const gws = (await sender('/api/sms/gateways')).json?.gateways ?? [];
check(gws.some((g) => g.online && g.battery === 87 && g.appVersion === 'test'), 'telefon ses som online med batteri og version');

check((await phone('/api/sms-gateway/report', { method: 'POST', body: { id: code.json.id, status: 'sent' } })).json?.applied === 1, 'kvittering "sent" modtaget');
const afterSent = (await sender(`/api/sms/${code.json.id}`)).json;
check(afterSent?.status === 'sent' && afterSent?.sentAt && afterSent?.text === undefined, 'status "sent"; koden vises ikke');
check((await phone('/api/sms-gateway/report', { method: 'POST', body: { id: code.json.id, status: 'sent' } })).json?.applied === 0, 'samme kvittering to gange gør ingenting');
check((await phone('/api/sms-gateway/report', { method: 'POST', body: { reports: [{ id: code.json.id, status: 'delivered' }, { id: free.json.id, status: 'failed', error: 'Ingen dækning' }] } })).json?.applied === 2, 'samlet kvittering: delivered + failed');
check((await sender(`/api/sms/${code.json.id}`)).json?.status === 'delivered', 'status "delivered"');
const failedFree = (await sender(`/api/sms/${free.json.id}`)).json;
check(failedFree?.status === 'failed' && failedFree?.error === 'Ingen dækning', 'status "failed" med fejltekst');
check((await phone('/api/sms-gateway/report', { method: 'POST', body: { id: 'findes-ikke', status: 'sent' } })).json?.applied === 0, 'kvittering for ukendt id ignoreres');

// ---- Long-poll vækkes af ny besked ----
const t0 = Date.now();
const waiting = phone('/api/sms-gateway/poll?wait=15');
setTimeout(() => sender('/api/sms', { method: 'POST', body: { to: num(13), text: 'Vækker long-poll', kind: 'free' } }), 1200);
const woke = await waiting;
const took = Date.now() - t0;
check(woke.json?.messages?.length === 1 && took < 4000, `long-poll vækkes med det samme (${took} ms)`);
await phone('/api/sms-gateway/report', { method: 'POST', body: { id: woke.json.messages[0].id, status: 'sent' } });

// ---- Træk tilbage ----
const cancelMe = await sender('/api/sms', { method: 'POST', body: { to: num(14), text: 'Skal ikke sendes', kind: 'free' } });
check((await sender(`/api/sms/${cancelMe.json.id}`, { method: 'DELETE' })).json?.ok === true, 'besked i kø kan trækkes tilbage');
check((await phone('/api/sms-gateway/poll?wait=0')).json?.messages?.length === 0, 'tilbagetrukket besked udleveres ikke');
check((await sender(`/api/sms/${cancelMe.json.id}`, { method: 'DELETE' })).status === 409, 'allerede tilbagetrukket kan ikke trækkes igen');

// ---- Timegrænse pr. nummer ----
const spam = num(15);
let last;
for (let i = 0; i < 7; i += 1) last = await sender('/api/sms', { method: 'POST', body: { to: spam, text: `spam ${i}`, kind: 'free' } });
check(last.status === 429, 'syvende SMS til samme nummer på en time afvises (429)', `status ${last.status}`);
for (;;) { // poll udleverer højst 5 ad gangen — ryd det hele op, så resten af testen starter rent
  const rest = (await phone('/api/sms-gateway/poll?wait=0')).json?.messages ?? [];
  if (!rest.length) break;
  for (const m of rest) await phone('/api/sms-gateway/report', { method: 'POST', body: { id: m.id, status: 'failed', error: 'oprydning' } });
}

// ---- Liste ----
const list = (await sender('/api/sms?limit=5')).json?.messages ?? [];
check(list.length > 0 && list.every((m) => m.to && m.status), 'liste over seneste SMS');

// ---- Udløb ----
if (slow) {
  const exp = await sender('/api/sms', { method: 'POST', body: { to: num(16), text: 'Udløber', kind: 'free', ttlSeconds: 30 } });
  console.log('...venter 33 sek. på, at beskeden udløber');
  await new Promise((r) => setTimeout(r, 33000));
  check((await phone('/api/sms-gateway/poll?wait=0')).json?.messages?.length === 0, 'udløbet besked udleveres ikke');
  check((await sender(`/api/sms/${exp.json.id}`)).json?.status === 'expired', 'status "expired"');
}

console.log(failed ? `\n${failed} FEJL` : '\nAlt OK');
process.exit(failed ? 1 : 0);
