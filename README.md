# HeadUnit Launcher

Launcher Android nativo (Kotlin + Compose, nessuna dipendenza oltre AndroidX/Coroutines/DataStore) per autoradio aftermarket
(iMotor `YMT08-3326`, Rockchip PX30/RK3326). Home a due pannelli ridimensionabili (navigazione + media), dock configurabile,
drawer app e utility "Car Tools". Niente Google Play Services, niente polling quando lo schermo è spento o il launcher non è visibile.

`minSdk 26`, `targetSdk 33`, ABI `armeabi-v7a` + `arm64-v8a` (nessun codice nativo proprio).

## Installazione

L'APK si scarica dall'ultima build (release `latest`) o dagli artifact di GitHub Actions.
Senza i secret `KEYSTORE_*` la CI firma con una keystore di test generata a ogni run: per aggiornare in place servono gli stessi secret.

```sh
adb install -r headunit-launcher-*.apk
adb shell cmd package set-home-activity com.pasq96.headunit/.MainActivity
```

## Comandi adb (senza root)

Embedding delle app (finestre freeform, senza di esse mappa e player si aprono a tutto schermo):

```sh
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
adb shell settings put global hidden_api_policy 1        # facoltativo: permette setLaunchWindowingMode via reflection
adb reboot                                                # il freeform si applica al riavvio
```

Permessi:

```sh
adb shell appops set com.pasq96.headunit SYSTEM_ALERT_WINDOW allow     # overlay notte + pannelli sopra le app embedded
adb shell appops set com.pasq96.headunit WRITE_SETTINGS allow          # luminosità
adb shell appops set com.pasq96.headunit GET_USAGE_STATS allow         # accesso utilizzo app
adb shell pm grant com.pasq96.headunit android.permission.ACCESS_FINE_LOCATION   # velocità GPS
adb shell cmd notification allow_listener com.pasq96.headunit/com.pasq96.headunit.media.MediaListener   # copertina/titolo
```

Tutti i permessi si possono dare anche dalla sezione **Permessi e stato** delle impostazioni.

## Uscita di emergenza (safe mode)

In **Impostazioni** (e nel pannello Car Tools) ci sono due pulsanti rossi sempre visibili: **Impostazioni Android**
(`ACTION_SETTINGS`) e **App predefinite** (`ACTION_HOME_SETTINGS`), per tornare al launcher stock in due tap.
Da adb:

```sh
adb shell pm disable-user --user 0 com.pasq96.headunit     # il sistema torna al launcher stock
adb shell pm enable com.pasq96.headunit                    # per riattivarlo
adb shell settings put global enable_freeform_support 0    # per annullare il freeform
```

## Comportamento

- **Comandi al volante**: i tasti media (next/prev/play-pause/play/pause/stop) vanno alla `MediaSession` attiva e, se non c'è,
  non vengono consumati. Volume, Back e Home non sono mai intercettati: li gestisce il sistema / `com.imotor`.
  Back nella home non fa nulla; con un pannello aperto lo chiude.
- **Audio**: il launcher non chiede mai l'audio focus e non riproduce suoni (nemmeno i click). La CI fallisce se compaiono
  `requestAudioFocus`, `AudioFocusRequest`, `SoundPool`, `MediaPlayer`, `ToneGenerator` o `playSoundEffect` nel sorgente.
- **ACC ON/OFF**: al `SCREEN_ON` dopo la sospensione attende il ritardo impostato, riapplica overlay notte e media,
  e rilancia navigatore e player nelle loro finestre (rianima anche le app ospitate morte durante il sonno).
  Opzionalmente rimanda `KEYCODE_MEDIA_PLAY`.
- **Embedding**: tre strategie in cascata: freeform con `setLaunchBounds`, split-screen di sistema se il launcher è già in multi-window,
  altrimenti avvio a schermo intero. `ActivityView`/`TaskView` non si usano perché richiedono permessi di firma di sistema.
  Con il freeform le finestre ospitate stanno sopra il launcher: drawer, impostazioni e Car Tools si aprono quindi in una finestra
  overlay (serve `SYSTEM_ALERT_WINDOW`), altrimenti sarebbero coperti.
- **Dock e pannelli in overlay**: `OverlayService` (servizio in foreground, nessun suono) ospita la dock e, quando servono, drawer,
  impostazioni e Car Tools come finestre overlay sopra le app freeform: toccare la mappa non nasconde più la dock. Parte da solo
  se ci sono il permesso overlay e il freeform; la dock overlay si disattiva dalle impostazioni. L'activity tiene lo spazio della dock vuoto,
  così la geometria dei pannelli non cambia. Le finestre overlay non prendono mai il focus: i tasti del volante restano all'app in primo piano.
- **Radio FM nativa**: terza modalità del player (Impostazioni > Player). Display frequenza, PS e RT scorrevole, barra di sintonia
  trascinabile, seek, passo ±0.1 MHz, FM1/FM2/AM, AF/TA, 8 preset locali (tocca = richiama, tieni premuto = salva o cancella) e
  "Radio stock" per aprire l'app originale. Con il pannello attivo e il launcher in primo piano, NEXT/PREV del volante scorrono i preset
  (o fanno seek, a scelta). **Le action `com.imotor.*` sono segnaposto non verificati**: dopo aver ispezionato l'APK della radio
  si correggono solo in `radio/ImotorRadioContract.kt` (action, extra, unità della frequenza).
- **Limiti noti**: il ridimensionamento di un task già aperto dipende dal firmware; se la finestra non si adatta al volo,
  tocca il pannello per riaprire l'app con le nuove misure.

## Impostazioni

**Aspetto**: tema Scuro (predefinito), Chiaro o Auto; lingua Italiano (predefinita) o Inglese, cambio immediato; colore di accento.
Tutti i testi stanno in `res/values` (IT) e `res/values-en` (EN).

**LED e modalità notte**: con tema Auto e/o oscuramento Auto, LED dei tasti accesi = notte (tema scuro, overlay di oscuramento),
spenti = giorno. Il segnale è letto da broadcast, impostazioni di sistema o sysfs (sorgente selezionabile, inversione, stato rilevato
mostrato in Impostazioni). Se lo stato è sconosciuto resta scuro. I nomi dei segnali sono segnaposto in
`system/IlluminationContract.kt`: vanno verificati sull'unità (logcat, `settings list system`, `/sys/class/leds`) e corretti solo lì.

**Layout**: posizione, spessore e icone della dock, etichette, divisore, proporzioni, scambio pannelli.

**Drawer**: verticale o orizzontale, colonne/righe (auto o fisse), dimensione icone, etichette.

**App**: navigatore e player predefiniti scelti da un selettore che mostra solo le app che dichiarano navigazione (geo:, intent
maps) o musica (MediaBrowser, categoria music); "Mostra tutte" per l'elenco completo. Stessa scelta dal menu di pressione lunga.

**ADB interno**: Impostazioni > ADB interno > "Concedi tutti i permessi" concede da solo (senza PC) impostazioni protette, posizione, sovrapposizione, modifica impostazioni, accesso uso app, notifiche (listener player), finestre libere, esclusione batteria e opzioni sviluppatore. Il launcher parla con l'adbd dell'unità su 127.0.0.1:5555 (protocollo ADB classico con chiave RSA salvata in app): servono Debug USB e ADB su rete attivi e, al primo uso, "Consenti sempre" nella finestra di debug. Il debug wireless con pairing di Android 11+ non è supportato. Lo stesso canale rende operative cancella dati e forza chiusura nel menu app (la cancellazione cache richiede root, altrimenti apre la schermata di sistema dell app). Usa il permesso INTERNET solo per il loopback.

**Opzioni sviluppatore**: prima cella del drawer (disattivabile), le apre in un tap. Per attivarle in automatico se nascoste: `adb shell pm grant com.pasq96.headunit android.permission.WRITE_SECURE_SETTINGS`.

**Menu app (pressione lunga)**: info, pin dock, imposta navigatore/player, poi le azioni distruttive in rosso dalla più lieve alla più severa: cancella cache, forza chiusura, cancella dati, disinstalla (conferma per le ultime tre; disinstalla nascosto per le app di sistema). Senza root o adb uid, cache e dati aprono le info app di Android e la chiusura termina solo i processi in background. Ogni etichetta ha la propria icona.

Inoltre: modalità player (widget, app embedded, radio), radio (passo FM/AM, tasti volante), orologio 12/24h, indicatori di rete,
sensori, soglia temperatura, avvio automatico, ripristino al risveglio, stato dei permessi.

## Struttura

`ui/` Compose (home, dock, drawer, pannelli) · `embed/` strategie di embedding · `media/` MediaSession e listener ·
`system/` LED tasti e tema auto · `radio/` radio FM nativa · `tools/` temperatura, RAM, batteria, GPS, luminosità, overlay notte · `data/` DataStore e app installate.

**Icona e splash**: icona adattiva a quadrante (con versione monocromatica per le icone a tema) e splash di avvio con dissolvenza, trattenuto al massimo 1,5 s finché la lista app è pronta.

**Radio stock (com.imotor.fmam)**: il pannello legge la frequenza dal log di sistema (permesso READ_LOGS, concesso da ADB interno) e comanda stazione successiva/precedente e cambio banda tramite il servizio della radio. Sintonia diretta, preset, AF/TA e mute non sono esposti dall app, quindi in questa modalità sono nascosti.

**Anteprime**: nei riquadri navigazione/player compare l ultima schermata dell app (catturata ogni ~10 s solo mentre l app e in primo piano, via ADB interno); un tocco apre l app a schermo intero. Le finestre freeform incorporate sono un opzione sperimentale, disattivata di default. Il pannello musica e scorrevole: musica (sessione multimediale attiva) e radio.

**Finestre incorporate (freeform)**: con l opzione attiva la mappa gira nel riquadro come finestra libera; una finestra trasparente del launcher la copre, nasconde la barra del titolo e trasforma il riquadro in anteprima (un tocco apre l app a schermo intero). Serve il permesso di sovrapposizione.
