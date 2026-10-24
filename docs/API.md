# API

GL-SMS taler med en server over HTTP(S) og JSON. Telefonen forbinder altid **ud** til serveren (long-polling), så den kan sidde bag mobilnettet
uden åbne porte. Serveren har en kø af SMS'er; telefonen henter dem, sender dem fra sit SIM-kort og kvitterer tilbage.

```
afsender ──POST /api/sms──►  server (kø)  ◄──GET /api/sms-gateway/poll── telefon ──► SMS fra SIM-kortet
                                 ▲                                          │
                                 └──────POST /api/sms-gateway/report────────┘   sent / delivered / failed
```

Et fungerende eksempel på en server er [`examples/node-server/server.mjs`](../examples/node-server/server.mjs) (Node, ingen afhængigheder),
og [`test-server/`](../test-server) er en mere komplet Rust-udgave med vindue. Begge følger kontrakten herunder.

## Generelt

- Alle svar er JSON (`Content-Type: application/json; charset=utf-8`). Fejl har formen `{"error": "tekst til mennesker"}` og en HTTP-status.
- Alle kald kræver `Authorization: Bearer <nøgle>`. Der er to slags nøgler:
  - **telefon-nøgle** (appen kalder `/api/sms-gateway/…`). Vælg en lang, tilfældig streng; i vores servere starter den med `glsms_`.
    Hver telefon bør have sin egen, så den kan tilbagekaldes.
  - **afsender-nøgle** (dem, der vil have sendt SMS'er, kalder `/api/sms…`). En telefon-nøgle må **ikke** kunne bruges som afsender-nøgle.
- Appen kræver `https://`, undtagen til lokale adresser (10.x, 172.16–31.x, 192.168.x, Tailscale 100.64–127.x, `localhost`, `*.local`, `*.lan`,
  `*.ts.net` og enkeltstående navne som `min-pc`), hvor `http://` er tilladt. Nøglen sendes i hvert kald, så brug kun `http://` på et net, du stoler på.
- Tidspunkter er ISO 8601 i UTC, fx `2026-10-24T14:55:36.123Z`.
- Telefonnumre er i E.164 (`+4512345678`). Serveren bør også acceptere `12 34 56 78` / `0045 12345678` og normalisere dem (otte cifre uden
  landekode = danske).

## Telefonens kald

### `GET /api/sms-gateway/ping`

Appen prøver nøglen her, før den gemmer en server. Valgfri query: `v` (appens versionsnummer).

```json
{ "ok": true, "gateway": { "id": "abc123", "name": "Pixel 7 Pro" } }
```

`401` hvis nøglen er ukendt (appen viser «Serveren kender ikke nøglen»).

### `GET /api/sms-gateway/poll?wait=25&battery=87&v=1.1.0`

**Long-poll.** Er der beskeder i køen, svares med det samme. Ellers holdes forbindelsen åben, til der kommer en besked — eller til `wait` sekunder er
gået (højst 30; appen bruger 25) — og svaret er en tom liste. Appen kalder igen lige efter. Den allerførste forespørgsel efter start har `wait=0`,
så appen straks kan vise «Forbundet». `battery` (0–100) og `v` er valgfrie og er blot til visning hos serveren.

```json
{
  "messages": [
    { "id": "m000001", "to": "+4512345678", "text": "Din kode er 123456", "kind": "verification", "expiresAt": "2026-10-24T15:10:36.000Z" }
  ],
  "serverTime": "2026-10-24T14:55:36.123Z"
}
```

Serveren bør:
- udlevere højst ca. 5 beskeder ad gangen, og **koder (alt andet end `free`) før frie beskeder**,
- markere de udleverede som `sending` med en **lease** (fx 90 sekunder) — de må ikke udleveres igen, før leasen er udløbet,
- aldrig udlevere en besked, hvis `expiresAt` er passeret (en gammel engangskode er værdiløs),
- vække ventende long-polls, når en ny besked lægges i kø (fx med en event/condition) i stedet for at vente på næste tick,
- opdatere «sidst set» for telefonen ved hvert kald (bruges til `gatewayOnline`, se nedenfor).

### `POST /api/sms-gateway/report`

Telefonen kvitterer. Enten én kvittering eller en liste (højst 50):

```json
{ "reports": [
  { "id": "m000001", "status": "sent" },
  { "id": "m000002", "status": "failed", "error": "Ingen dækning" }
] }
```

`status` er `sent` (afleveret til mobilnettet), `delivered` (modtagerens telefon har den — kun hvis operatøren sender leveringsrapporter) eller
`failed` (med `error`, højst 300 tegn). Svar: `{ "ok": true, "applied": 2 }` — `applied` er antallet af kvitteringer, der ændrede noget.

Kvitteringer kan komme **forsinket og flere gange** (fx hvis telefonen var uden net, eller svaret gik tabt), så de skal være idempotente:

| Kvittering | Må ændre fra |
|---|---|
| `sent` | `sending`, `expired`, `failed` |
| `delivered` | `sending`, `sent`, `expired`, `failed` |
| `failed` | `sending`, `sent`, `expired` |

Alt andet (ukendt id, samme kvittering igen) ignoreres uden fejl. Kun den telefon, der hentede beskeden, må kvittere for den.

## Afsenderens kald

### `POST /api/sms` — læg en SMS i kø

```json
{ "to": "12 34 56 78", "text": "Din kode er 123456", "kind": "verification", "ref": "bruger-42", "ttlSeconds": 900 }
```

| Felt | |
|---|---|
| `to` | påkrævet — modtagerens nummer |
| `text` | påkrævet — højst 480 tegn (tre SMS-dele). Typografiske tegn (— ’ “ ” …) bør skrives om til almindelige tegn, så SMS'en holder sig til GSM-alfabetet: 160 tegn pr. SMS i stedet for 70 |
| `kind` | valgfri, standard `free`. En etiket: `verification`, `event-edit`, `admin-login` eller `free` i vores servere. Alt andet end `free` regnes for en **kode**: den udleveres først, og teksten slettes, så snart beskeden er sendt/udløbet/fejlet |
| `ref` | valgfri fri reference (højst 100 tegn), vises i loggen |
| `ttlSeconds` | valgfri, 30–604800. Efter udløb sendes beskeden ikke længere. Standard for koder 10–15 minutter, for frie beskeder 24 timer |

Svar `201`:

```json
{ "id": "m000001", "to": "+4512345678", "status": "queued", "expiresAt": "2026-10-24T15:10:36.000Z", "gatewayOnline": true }
```

`gatewayOnline` fortæller, om en telefon har ringet ind inden for de sidste ~90 sekunder, så afsenderen kan vælge at falde tilbage på mail,
hvis ingen telefon er online. Fejl: `400` (ugyldigt nummer, tom/for lang tekst, ukendt type), `429` (for mange SMS — se nedenfor), `401`.

### `GET /api/sms/:id` — status

```json
{ "id": "m000001", "to": "+4512345678", "kind": "verification", "ref": null, "status": "delivered", "error": null, "attempts": 1,
  "createdAt": "…", "expiresAt": "…", "sentAt": "…", "deliveredAt": "…" }
```

`text` er med for `free`-beskeder, men udelades for koder.

Status-forløbet: `queued` → `sending` (telefonen har hentet den) → `sent` → `delivered`. Eller `failed`, `expired` (nåede ikke at blive sendt),
`cancelled` (trukket tilbage).

### `GET /api/sms?limit=50&status=failed&to=+4512345678` — seneste SMS'er

`{ "messages": [ … ] }`, nyeste først (`limit` 1–200).

### `DELETE /api/sms/:id` — træk tilbage

Kun mens beskeden står i kø: `{ "ok": true }`. Ellers `409`.

### `GET /api/sms/gateways` — telefonerne (valgfri)

```json
{ "gateways": [ { "id": "abc123", "name": "Pixel 7 Pro", "online": true, "lastSeenAt": "…", "battery": 87, "appVersion": "1.1.0" } ] }
```

## Pålidelighed og sikkerhed — det serveren bør sørge for

- **Lease og genudlevering.** Hentede, men ikke kvitterede beskeder (telefonen mistede strøm/net) gives videre igen, når leasen er udløbet — højst
  to forsøg i alt, derefter `failed` («Ingen kvittering fra telefonen»). Appen husker, hvad den allerede har sendt, og sender aldrig det samme id
  to gange, så en genudlevering giver ikke dobbelte SMS'er.
- **Id'er er pr. server.** Appen er gateway for flere servere samtidig og holder id'erne adskilt, så to servere godt kan bruge de samme id'er.
- **Timegrænser.** SMS koster penge og er et klassisk misbrugsmål. Begræns pr. modtager og i alt pr. time (vi bruger 6 og 120) og returnér `429`.
  Appen har desuden sin egen fælles timegrænse som sidste sikkerhedsnet.
- **Tilladte lande.** Vi tillader kun `+45` som standard. Udvid bevidst.
- **Koder i klartekst.** Slet teksten fra køen, så snart en kode er færdig. Ryd gamle poster (telefonnumre er persondata) efter fx 30 dage.
- **Nøgler.** Gem kun en hash af telefon-nøglen, og lad den kunne skiftes uden at røre afsendernes nøgler.
- **https.** Kør serveren bag https, hvis den kan nås fra internettet.

## Hvad appen gør med en besked

1. Henter den (`poll`), og skriver den i sin egen log (kun maskeret nummer — `+45 ••••5678` — aldrig teksten) **før** den sendes.
2. Sender den med `SmsManager` fra det valgte SIM-kort (standard: telefonens standard-SIM til SMS; kan vælges pr. server).
3. Kvitterer `sent` (alle SMS-dele afsendt), og siden `delivered` (leveringsrapport) eller `failed`.
4. Er serveren utilgængelig, gemmes kvitteringerne og sendes, når forbindelsen er tilbage.

Er tekstens tegn uden for GSM-alfabetet og længere end én SMS, kræver Android tilladelsen *Telefonstatus* for at kunne dele teksten op.
