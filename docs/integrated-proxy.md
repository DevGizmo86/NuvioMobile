# Proxy integrato Android — prima versione

Questo ramo aggiunge un proxy di riproduzione locale a Nuvio Mobile Android.
È il primo nucleo della riscrittura prevista: **non è ancora una replica completa di EasyProxy**.

## Utilizzo

Impostazioni → Riproduzione → Proxy integrato:

- **Disattivato** (predefinito): percorso di riproduzione originale.
- **Automatico — stream HLS**: attiva il proxy per URL `.m3u8` o stream dichiarati HLS.
- **Stream HTTP — HLS e file diretti**: include gli URL HTTP(S) opachi e i file video.

La scelta si applica alla prossima apertura del player. Il server si avvia alla
riproduzione e viene chiuso quando il player esce dalla composizione o cambia sorgente.
Resta disponibile durante il passaggio automatico da ExoPlayer a libmpv.
Non esiste un servizio sempre attivo: non servono Termux o un server esterno.

## Cosa funziona

- Player interni Android ExoPlayer e libmpv.
- Playlist HLS master/media, URL relativi risolti rispetto all'URL finale dopo redirect.
- Riferimenti URI a varianti, audio, sottotitoli HLS, chiavi AES e segmenti iniziali.
  Le chiavi vengono inoltrate; la decifratura HLS resta responsabilità del player.
- Inoltro binario senza ricodifica, GET/HEAD, Range/206 per i file e segmenti.
- Header della sorgente, cookie in memoria per sessione, redirect e timeout.
- Server su `127.0.0.1` con porta casuale e percorsi opachi per le risorse registrate.
- Limiti a connessioni, coda, dimensione manifest e numero di risorse in memoria.
- Chiusura delle socket e annullamento delle richieste alla chiusura della sessione.
- Verifica TLS standard. Credenziali esplicite Authorization/Cookie limitate
  all'origine iniziale; cookie ricevuti dal server limitati al proprio ambito.

## Limiti espliciti

- Nessun extractor di siti, browser/solver, conversione DASH, DRM aggiuntivo o DVR.
- Non converte gli URL di un EasyProxy remoto in richieste locali equivalenti.
  Serve un URL video/manifest già risolto dall'addon o dal plugin.
- DASH riconoscibile dal tipo o dall'estensione `.mpd` viene lasciato diretto.
  Non usare la modalità HTTP per DASH opaco non dichiarato: il manifest non viene riscritto.
- HLS con variabili `EXT-X-DEFINE` non è supportato; le variabili non risolte
  producono un errore esplicito. LL-HLS blocking reload/content steering non collaudati.
- Nessun supporto a casting, player esterni o download tramite questo proxy.
  I sottotitoli esterni continuano sul percorso originale di Nuvio.
- YouTube chunked, file locali e URL loopback già esistenti usano il percorso originale.
- Modalità salvata solo sul dispositivo, non sincronizzata con l'account.
- iOS invariato; integrazione Nuvio TV ancora da sviluppare.
- Non corregge errori del provider, URL scaduti, blocchi IP o dipendenze da browser.

## Verifica

I test JVM `LocalStreamProxyTest` esercitano realmente le connessioni HTTP locali:
playlist, redirect, segmenti, Range, HEAD, errori upstream, cookie, isolamento delle
credenziali, chiusura del server e rifiuto di percorsi sconosciuti/richieste browser.

Per eseguire solo questi test senza Android SDK:

```sh
bash scripts/test-integrated-proxy.sh
```

Il primo avvio scarica compilatore Kotlin e dipendenze da Maven Central nella cache
locale del sistema. Il compilatore isolato è 2.3.10; la compilazione dell'app usa
la versione Kotlin prevista dal progetto. Il test isolato **non verifica Compose,
la build APK o il comportamento su un telefono**.

Build Android completa:

```sh
./gradlew :androidApp:assembleFullDebug -Pnuvio.android.distribution=full -Pnuvio.ios.distribution=appstore
```

Il pacchetto debug usa già un application ID separato dall'app ufficiale.
Per funzioni account/cataloghi potrebbero servire le proprie configurazioni runtime
(`local.properties`): il fork non contiene le credenziali del progetto ufficiale.

Prova su Pixel prima di considerare la funzione stabile:

1. Confrontare lo stesso HLS autorizzato con proxy disattivato e automatico.
2. Provare entrambi i player, seek, cambio variante e traccia audio/sottotitoli.
3. Provare un live per almeno 20 minuti, cambio sorgente e uscita dal player.
4. Verificare Picture-in-Picture, background/ritorno all'app e cambio rete.
5. In modalità HTTP, provare seek su MP4 e un HLS con URL opaco.

Gli extractor specifici di EasyProxy sono il passo successivo e richiedono test
per ciascun provider. La pubblicazione di questo ramo non implica compatibilità
con tutti gli addon che oggi usano EasyProxy.
