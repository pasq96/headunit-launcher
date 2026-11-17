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
- **Limiti noti**: il ridimensionamento di un task già aperto dipende dal firmware; se la finestra non si adatta al volo,
  tocca il pannello per riaprire l'app con le nuove misure.

## Impostazioni

Posizione, spessore e icone della dock, etichette, larghezza del divisore, proporzioni, scambio pannelli, dimensione della griglia
del drawer, colore di accento, navigatore e player predefiniti, modalità player (widget o app embedded), orologio 12/24h,
indicatori di rete, intervallo sensori, soglia allarme temperatura CPU, modalità notte e intensità, avvio automatico del
navigatore e PLAY con ritardi, ripristino al risveglio con attesa.

## Struttura

`ui/` Compose (home, dock, drawer, pannelli) · `embed/` strategie di embedding · `media/` MediaSession e listener ·
`tools/` temperatura, RAM, batteria, GPS, luminosità, overlay notte · `data/` DataStore e app installate.
