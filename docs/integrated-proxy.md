# Proxy integrato Android e plugin di risoluzione

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
Nel player compare il badge **Proxy attivo** solo dopo la creazione della sessione
locale e l'instradamento della sorgente attraverso di essa. Il badge non appare per
riproduzioni dirette o sorgenti escluse, anche se il proxy è abilitato nelle impostazioni.
Indica il percorso utilizzato, non garantisce che il provider risponda correttamente.
Non esiste un servizio sempre attivo: non servono Termux o un server esterno.

## Test senza addon

Nella stessa sezione toccare **Prova stream HLS**. Si apre Big Buck Bunny, lo stream
pubblico di Mux, direttamente nel player interno (anche se è configurato un player
esterno). Non occorre incollare URL o installare un addon.

Provare prima con **Disattivato**, uscire dal player e ripetere con **Automatico —
stream HLS**: nella seconda prova deve comparire **Proxy attivo**. Il pulsante non
modifica la modalità scelta. Il video utilizza internet e non viene identificato
come un film della libreria per il salvataggio della cronologia.

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

- Risoluzione dei provider tramite plugin separati; nessun browser/solver, conversione DASH, DRM aggiuntivo o DVR.
- Non converte gli URL di un EasyProxy remoto in richieste locali equivalenti.
  Sono intercettati solo gli URL marcati per il resolver locale (vedi sotto).
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

## Plugin di risoluzione (Android full)

L’app riconosce esclusivamente il marcatore `https://nuvio-resolver.invalid`:
non effettua richieste di rete a questo dominio. Invoca i plugin abilitati del
profilo con `supportedTypes: ["resolver"]`, passando l’URL completo come primo
argomento di `getStreams(requestUrl, "resolver")`. Non esegue ricerche TMDB e non
attende la ripresa delle ricerche fonti in background. Timeout complessivo: 45 s.
I normali URL e i proxy remoti mantengono il percorso originale.

Il primo risultato HTTP(S) valido `{url, headers?, type?}` viene riprodotto dal
proxy locale. I risultati ricorsivi, loopback, DASH o con credenziali URL incorporate
vengono rifiutati. Gli header del risultato vengono applicati solo a monte dal proxy;
le credenziali dell’API devono essere gestite dal plugin, senza passarle al player.
Il proxy deve essere abilitato anche per le fonti marcate; in caso contrario viene
mostrato un errore, senza tentare di contattare il marcatore.

I plugin sono pubblicati separatamente sul ramo `resolver-plugins` del fork,
non incorporati nell’APK. Installazione e configurazione TVvoo:
[README dei plugin](https://github.com/DevGizmo86/NuvioMobile/blob/resolver-plugins/README.md).

Il pulsante **Prova plugin di risoluzione** usa un plugin diagnostico separato per
aprire Big Buck Bunny attraverso lo stesso collegamento. Permette di distinguere
problemi del collegamento da quelli del servizio del provider. Il vecchio pulsante
**Prova stream HLS** continua a testare il proxy senza coinvolgere plugin.

Le credenziali vengono richieste all’apertura della fonte. Se una risorsa a monte
risponde 401, 403 o 410, il proxy segnala la scadenza al collegamento resolver:
Nuvio richiede nuove credenziali, crea una nuova sessione locale e aggiorna la
sorgente del player. Le segnalazioni simultanee vengono accorpate e limitate a un
tentativo ogni 10 secondi. Il rinnovo riporta un canale live al margine in diretta.
Timeout, rallentamenti, 404 e chiusure di connessione non attivano il rinnovo:
non dimostrano che una credenziale sia scaduta e possono dipendere dalla fonte.
Non supporta questi URL in casting, player esterni o download, né in build Play Store
senza runtime plugin. La compatibilità live con ogni provider va provata sul dispositivo.

I test isolati includono selezione dei resolver, validazione dei risultati,
cancellazione e header Origin applicati upstream. Il test HLS di base è già stato
confermato sul dispositivo dall’utente; il nuovo percorso plugin richiede una nuova prova.
