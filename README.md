# GL-SMS — SMS-gateway til Android

En Android-telefon bliver til en SMS-gateway: **din server bestemmer hvad der sendes og til hvem**, og telefonen sender det fra sit eget SIM-kort.
Tænkt til engangskoder og korte beskeder som supplement til mail — uden at skulle betale en SMS-udbyder pr. besked og uden at åbne porte til telefonen.

```
afsender ──POST /api/sms──►  din server (kø)  ◄──long-poll── GL-SMS på telefonen ──► SMS fra SIM-kortet
                                   ▲                                  │
                                   └──────────── kvittering: sent / delivered / failed
```

- Telefonen forbinder altid **ud** til serveren (long-polling), så den kan sidde bag mobilnettet. En kode når frem inden for et sekund eller to.
- **Flere servere på én gang** — hver med sin egen forbindelse, sin egen aktiv/slået fra og eventuelt sit eget SIM-kort.
- **Valg af SIM-kort** ved dual-SIM (fælles valg, eller pr. server).
- **Låste felter**: alt skal åbnes med «Ret» og bekræftes med «OK», så intet rettes ved et uheld.
- **Timegrænse** i appen som sidste sikkerhedsnet, så en fejl på en server ikke kan sende i ring.
- Starter selv efter genstart af telefonen; kvitterer sikkert, selv hvis telefonen har været uden net (og sender aldrig den samme SMS to gange).

> Afsenderen er SIM-kortets eget nummer — ikke et navn. Et afsendernavn (fx «MitFirma») kræver en SMS-udbyder.
> Android-apps, der sender SMS uden at være standard-SMS-app, kan ikke ligge på Google Play, så appen **sideloades**.

## Kom i gang

1. Hent **[`releases/GL-SMS-1.1.0.apk`](releases/GL-SMS-1.1.0.apk)** og installér den på telefonen (Android 8+). Tillad installation fra denne kilde, hvis Android spørger.
2. Få en server at tale med — se [Server](#server) herunder.
3. Åbn appen → **Tilføj server**: skriv adresse (fx `https://sms.eksempel.dk`) og nøgle → **Gem og test**.
4. Giv tilladelserne under *Tilladelser*: *Send SMS*, *Notifikation*, og **undtag fra batterioptimering** (ellers kan Android lægge forbindelsen i dvale).
   *Telefonstatus* skal bruges til SIM-valg og til lange SMS'er med emoji og andre særlige tegn.
5. Tryk **Start gateway**. Telefonen bør sidde i en lader; *Hold telefonen vågen* er slået til som standard.
6. *Test SIM-kortet* sender en SMS direkte fra telefonen uden om serveren.

> Vises der ingen tilladelsesdialog for SMS? Android 13+ spærrer SMS-tilladelsen for apps, der ikke kommer fra Play:
> Indstillinger → Apps → GL-SMS → ⋮ → **Tillad begrænsede indstillinger**, og prøv igen.

`http://` accepteres kun til servere på lokalnettet (10.x, 172.16–31.x, 192.168.x, Tailscale, `localhost`, `*.local`, enkeltstående navne);
til internettet kræves `https://`, så nøglen aldrig sendes i klartekst over nettet.

## Server

Appen taler med en server over et lille JSON-API: telefonen **henter** SMS'er (`poll`) og **kvitterer** (`report`); afsendere **lægger** SMS'er **i kø** (`POST /api/sms`).
Hele kontrakten står i **[docs/API.md](docs/API.md)**.

| | |
|---|---|
| [`examples/node-server/server.mjs`](examples/node-server) | Det mindste eksempel: Node 18+, ingen afhængigheder, kø i hukommelsen. `node server.mjs` skriver to nøgler ud. |
| [`test-server/`](test-server) | Rust-app med vindue (egui) til din pc: viser telefonen, køen og et kaldlog, kan sende SMS'er fra vinduet og tager de samme regler som en rigtig server (koder først, udløb, timegrænser, kvitteringer, long-poll). Bygges med `cargo build --release`. |
| [`tools/server_smoke_test.mjs`](tools/server_smoke_test.mjs) | Røgtest af en servers API: `node tools/server_smoke_test.mjs <server> <telefon-nøgle> <afsender-nøgle>`. Test-serveren består den. |

Prøv det hurtigt:

```bash
node examples/node-server/server.mjs
# → Telefonens nøgle (i appen): glsms_…   Afsenderens nøgle (curl): glsend_…

curl -X POST http://localhost:4000/api/sms \
  -H "Authorization: Bearer glsend_…" -H "Content-Type: application/json" \
  -d '{"to":"12 34 56 78","text":"Hej fra min server","kind":"free"}'
```

I appen: adresse `http://<pc'ens-ip>:4000` (samme Wi-Fi) og telefonens nøgle. Åbn adressen i telefonens browser først — kan du se serveren dér, kan appen også.

## Bygning

```
cd android
./gradlew assembleRelease      # Windows: .\gradlew.bat assembleRelease
```

Kræver JDK 17 (Android Studios JBR duer). Release-udgaven er signeret med debug-nøglen — nok til sideloading på egne telefoner.
`-PapiBase=http://10.0.2.2:4000` forudfylder en serveradresse (fx til test i emulatoren).

Kotlin + Jetpack Compose, `minSdk 26`, ingen eksterne biblioteker ud over AndroidX/Compose og coroutines.

## Version 1.1.0

- Flere servere på én gang (med migrering af en eksisterende enkelt-server-opsætning).
- Valg af SIM-kort, fælles eller pr. server.
- Alle tekstfelter og SIM-valg er låst, til man trykker «Ret».
- `http://` til lokalnettet; ingen indbygget server — adressen skrives i appen.
- Én long-poll pr. server; en server, der er nede, stopper ikke de andre.

## Hvad der ikke er afprøvet

Appen og serverne er afprøvet på Android-emulatorer (SMS mellem to emulatorer, genstart, netværk væk og tilbage) og på rigtige telefoner med test-serveren.
Leveringskvittering, dual-SIM og strømbesparelse afhænger af telefon og operatør — tjek dem på din egen telefon.
