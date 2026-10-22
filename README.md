# BoothDediche
Due app in un solo progetto Android Studio / GitHub Actions:
- `camera`  -> telefono: registra un "take" temporaneo, lo serve in streaming al tablet (HTTP /take),
               salva in Movies/Booth/<nome>_001.mp4 solo se il tablet dice SAVE. WebSocket :8080 + mDNS.
- `tablet`  -> tablet: preview in mirror, REC, impostazioni remote (qualita, durata, ottica/zoom, torcia,
               esposizione, nome file), anteprima in basso a destra con Rifai / Salva / Condividi.

Novità 1.6
- Cornice (PNG trasparente) su foto e video: il tablet la mostra sopra l'anteprima e la invia al telefono (POST /frame);
  il telefono la applica alle foto (Canvas, ritaglio 16:9) e ai video (Media3 Transformer, qualche secondo dopo lo stop).
  Inclusa "Gaia +30" (assets/frame_gaia30.png); da Impostazioni > CORNICE si può scegliere un'altra immagine dalla galleria.
- Flash nelle foto: usa il flash/doppio LED del telefono (FLASH_MODE_ON); se l'obiettivo grandangolare non ha il flash,
  accende la torcia ~0,5 s prima dello scatto. Tasti rapidi ⚡ (flash) e 🖼 (cornice) sul tablet.

Protocollo: tablet->telefono START|PHOTO|STOP|SAVE|DISCARD|INFO?|SET:{json}  (+ HTTP POST /frame = PNG cornice)
            telefono->tablet STATE:..(anche PROCESSING:<%>)|EVENT:..|INFO:{json} + frame JPEG

## Test fotocamere (modulo `camtest`)
App a parte "Test Camere": mostra se il tablet dichiara il supporto alle fotocamere concorrenti
(`getConcurrentCameraIds`) e prova ad aprire davvero anteriore + posteriore insieme.
Serve a decidere come progettare la dashcam. Non fa parte del booth.

## Novità 1.7 — dashcam, controllo remoto, log, aggiornamenti OTA

**Dashcam (sul tablet)**: servizio in primo piano che registra video+audio a segmenti (5 min di default) e
cancella i più vecchi (conserva 120 min di default, e lascia sempre almeno 2 GB liberi).
Fotocamera frontale + posteriore insieme se Android dichiara la combinazione (`getConcurrentCameraIds`);
altrimenti solo la frontale. Se la posteriore non si apre insieme alla frontale, ripiega da sola sulla frontale.
Audio solo dalla frontale. File in `Android/data/it.mdo.booth.tablet/files/dashcam/`.

**App "Booth Controllo"** (modulo `control`, sul telefono di Marco): stato di salute (verde/giallo/rosso),
contatori di oggi, log eventi con link al video del momento, elenco video, impostazioni dashcam.
Trova il tablet via mDNS `_boothdash._tcp.` (o IP a mano). Il tablet espone HTTP sulla porta 8090 e
richiede il codice a 6 cifre mostrato in Impostazioni → DASHCAM.

**Log eventi** (`events.jsonl` sul tablet): REC_START, FOTO, DEDICA_PRONTA/SALVATA/SCARTATA/ELIMINATA,
CONDIVISA, QR, PHONE_ON/OFF, BATT_BASSA, SPAZIO_BASSO, DASH_*.

**OTA**: ogni app controlla l'ultima Release del repo (tag `v<versionCode>`) e installa il proprio APK
(`booth-camera.apk`, `booth-tablet.apk`, `booth-control.apk`). Versione unica in `gradle.properties`
(`appVersionCode`, `appVersionName`): per rilasciare, aumenta `appVersionCode` e fai commit.
Il workflow `.github/workflows/release-apk.yml` compila e pubblica la release.

**Repo privato**: le app leggono la release con un token GitHub di sola lettura (fine-grained, solo questo repo,
permesso Contents: Read-only), salvato come segreto `UPDATE_TOKEN` del repo e incluso negli APK in fase di build.
Il token scade (max 1 anno): va rinnovato e il segreto aggiornato.

## Novità 1.8 — diretta e controllo remoto
- Tab **Diretta** in Booth Controllo: fotocamera frontale del tablet in tempo reale (~640 px, pochi fotogrammi al secondo).
  Il tablet prepara i fotogrammi solo mentre l'app di controllo li chiede (`GET /snap`). Se il tablet non supporta registrazione + diretta insieme, la diretta si disattiva e la dashcam continua a registrare.
- Tab **Impost.** di Booth Controllo = copia del pannello Impostazioni del tablet (telefono camera, cornice, dashcam, tablet, aggiornamenti), via `POST /settings`.

## Novità 1.9 — sicurezza con l'accelerometro
- **Tablet**: rileva cadute (assenza di peso + urto), urti forti e spostamenti/inclinazioni (sensibilità Alta/Media/Bassa, Impostazioni → DASHCAM).
  Ogni evento va nel log e protegge per 24 h i video in corso e quelli appena chiusi (file `.keep`, non vengono sovrascritti).
  Con il kiosk attivo, caduta o spostamento scattano anche una foto antifurto con la frontale (`allarme_*.jpg`, 3 giorni).
- **Telefono camera**: avvisa il tablet se viene spostato o cade (`EVENT:MOTION:MOVED|FALL`); il tablet mostra un avviso e lo scrive nel log.
- **Controllo**: nuovi eventi nel log, foto antifurto apribile, 🔒 sui video protetti, avviso giallo nello Stato per 15 minuti.

## Novità 1.10 — schermo nero sul telefono camera
Dopo 60 s senza tocchi (o col tasto "Schermo nero") il telefono camera mostra solo un puntino di funzionamento su schermo nero a luminosità minima
(AMOLED: pixel neri spenti). Verde = tablet collegato, arancione = tablet non collegato. Il puntino cambia posizione ogni minuto.
Doppio tocco (o Indietro) per uscire. La camera e i sensori continuano a funzionare perché l'app resta aperta.

## Novità 1.11 — telecomando Bluetooth a un tasto
Sul **tablet**: uno scatto selfie Bluetooth (un solo tasto) si abbina dalle impostazioni Bluetooth di Android; poi in Impostazioni → TELECOMANDO BLUETOOTH
si preme **Associa** e il tasto. I tasti dell'altro hardware (volume del tablet, tastiere) restano ignorati.
Gesti: singola / doppia / tripla / quadrupla / lunga (700 ms; molti scatti mandano un impulso istantaneo e la lunga non si rileva).
- Pronto: singola = REC, doppia = FOTO, tripla = flash foto, lunga o quadrupla = torcia
- Conto alla rovescia: qualsiasi pressione annulla; registrazione: qualsiasi pressione = STOP
- Anteprima non salvata: singola = Salva, doppia = Rifai; salvata: singola = Nuova, lunga/tripla/quadrupla = QR
- QR a schermo: qualsiasi pressione lo chiude
Il pannello mostra gli ultimi tasti e gesti ricevuti, utile per provare il telecomando.
Sul tablet, accanto a QR compare (solo se il telecomando è associato) l'icona del telecomando: anello verde = collegato, rosso e sbiadita = non collegato.
Toccandola si mostra/nasconde la legenda dei gesti (cambia tra pronto e anteprima). Icona: `assets/remote_icon.png`.

## Novità 1.12 — tab Dediche in Booth Controllo
Nuova tab **Dediche**: griglia di miniature di video e foto salvati sul telefono camera (ultimi 50, quelli confermati con Salva).
Si toccano quelle da scegliere (✓), 👁 apre l'anteprima, **Scarica in Galleria** le salva sul telefono di Controllo in Movies/Booth e Pictures/Booth.
Chip: Aggiorna, Nuove (non ancora scaricate), Tutte, Nessuna, IP (se la camera non viene trovata da sola).
Il telefono di Controllo parla direttamente col telefono camera (stessa Wi-Fi, mDNS `_boothcam._tcp.`, porta 8080): serve reinstallare solo Booth Controllo.


## Novità 1.13 — bordo colorato attorno all'anteprima (tablet)
Impostazioni → **BORDO ANTEPRIMA**: *Pieno schermo* (come prima), *Sottile* (cornice di 24 dp) o *Largo* (predefinito):
l'anteprima si rimpicciolisce e i tasti (impostazioni, REC, foto, storico, controlli ottica) stanno nel bordo, non sopra la cornice.
Il colore del bordo si sceglie tra sette preset (Nero, Bianco, Oro, Rosa, Salvia, Blu notte, Bordeaux) o con un codice #RRGGBB.
La cornice PNG resta agganciata all'immagine. Serve aggiornare solo l'app Booth (tablet).

## Novità 1.14 — interfaccia tablet ridisegnata
Con il bordo *Largo* tutto sta fuori dall'immagine: messaggio d'attesa nella fascia in alto, REC con anello bianco e FOTO bianco in basso,
tasti a destra più grandi con scritta (TORCIA, FLASH, CORNICE, AUTO/FISSO, giallo = attivo), storico a sinistra.
Batteria e spazio più grandi (rosso quando stanno finendo). Nuovo preset colore **Fucsia**. Solo app Booth (tablet).

## Novità 1.15 — interfaccia identica al mockup
Font Poppins incluso nell'app, icone disegnate (batteria, scheda, torcia, flash, cornice, lucchetto, fotocamera, ingranaggio),
miniature dello storico 16:9 con bordo bianco e titolo ULTIMI, video con angoli arrotondati e ombra, tasti scuri viola semitrasparenti.
Il bordo Fucsia è il colore predefinito per le nuove installazioni. Solo app Booth (tablet).

## Novità 1.16 — legenda del telecomando
La legenda dei gesti sta a sinistra della colonna di tasti e non li copre più; testo completo.

## Novità 1.17 — notifica di aggiornamento
Notifica quando esce una nuova versione (tablet, camera, controllo); controllo automatico ogni 6 ore.

## Novità 1.18 — aggiornamenti pronti, novità nell'app, scheda telecomando
- Quando esce una versione nuova l'app scarica da sola l'APK in background e manda la notifica "Aggiornamento pronto": un tocco e parte l'installazione.
  Se il download fallisce resta la notifica "Aggiornamento disponibile" (Installa dalle impostazioni).
- Novità di ogni versione (`Changelog.kt` in ogni app): finestra dopo l'aggiornamento e voce **Novità** nelle impostazioni (tablet e controllo);
  sul telefono camera si tocca la scritta della versione.
- Tablet: scheda del telecomando ridisegnata (stato collegato, gesti a sinistra e azione a destra in pillola gialla).
- Le Activity sono `singleTop` per ricevere il tocco sulla notifica.
