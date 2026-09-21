# Nuvio Local Resolvers

Plugin JavaScript installabili separatamente per il fork Android
[DevGizmo86/NuvioMobile](https://github.com/DevGizmo86/NuvioMobile/tree/feature/integrated-proxy).
Questo ramo contiene solo i plugin: **non vengono inclusi nell’APK**.
Richiede la nuova build con collegamento ai resolver; l’app ufficiale e le vecchie
build del proxy non implementano questo collegamento.

## Installazione

1. Installare la build Android full del ramo `feature/integrated-proxy`.
2. Nuvio → Impostazioni → Plugin → aggiungere questo repository:

   `https://raw.githubusercontent.com/DevGizmo86/NuvioMobile/resolver-plugins/manifest.json`

3. Abilitare i plugin nel profilo in uso, compresi **Vavoo — risoluzione locale**
   e **Test resolver — Big Buck Bunny**.
4. Impostazioni → Riproduzione → Proxy integrato → **Automatico**.
5. Toccare **Prova plugin di risoluzione**: deve partire Big Buck Bunny con il badge
   **Proxy attivo**. Questo verifica il collegamento ai plugin, non il servizio Vavoo.

## TVvoo

Nella configurazione dell’addon:

- Tipo proxy: **EasyProxy** (compatibile anche il formato Mediaflow con host Vavoo).
- Proxy URL: `https://nuvio-resolver.invalid`
- Password proxy: `nuvio` (se richiesta dal form; è un segnaposto, non una credenziale).
- Salvare la configurazione e aggiornare/reinstallare l’addon in Nuvio.
- Aprire un canale e scegliere la fonte **Proxy**, usando il player interno.

L’indirizzo `.invalid` è un marcatore intercettato da Nuvio. Non è un sito da aprire
nel browser, un servizio LAN o un URL utilizzabile in Stremio, casting o altri player.
TVvoo incorpora il marcatore nell’URL della fonte senza contattarlo. Eventuali test
di raggiungibilità del form possono quindi segnalarlo come non raggiungibile.
Le fonti Clean o i vecchi URL di proxy remoti non vengono intercettati.

## Credenziali temporanee

Il plugin richiede una nuova firma dal dispositivo all’apertura di ogni fonte,
poi risolve l’URL tramite il provider. La firma rimane in memoria, non viene
salvata, stampata nei log o modificata. Non si inviano IP inventati. La firma
dell’API non viene passata agli host dei segmenti video.

Un 401/403 durante la risoluzione comporta un solo nuovo tentativo con una firma
appena ottenuta. Durante la riproduzione, la build Nuvio con rinnovo automatico
intercetta 401, 403 e 410 dal server video, richiama questo plugin e sostituisce la
sessione locale. Il player riparte automaticamente; un canale live torna al margine
in diretta. Rallentamenti, timeout, 404 e chiusure senza uno di questi codici non
attivano il rinnovo, perché non provano che la credenziale sia scaduta.

Il plugin non include solver browser, worker remoti o fallback che aggirino il
rifiuto del provider. La compatibilità con il servizio reale richiede una prova
sul dispositivo: i test automatici usano risposte simulate.

## Sviluppo e verifica

`getStreams(requestUrl, "resolver")` riceve l’URL completo dell’addon e restituisce
una lista di risultati `{url, headers?, type?}`. Restituire `[]` per richieste non
gestite. Sono accettati stream HTTP(S) HLS e file diretti; DASH non è supportato.
Usare `type: "hls"` solo se il risultato è effettivamente una playlist HLS.
Il motore Nuvio applica un timeout complessivo di 45 secondi.

Eseguire: `node --test tests/*.test.cjs`.

Il formato TVvoo e il protocollo di risoluzione sono stati verificati nel sorgente
pubblico [TVvoo](https://github.com/qwertyuiop8899/tvvoo), in `src/proxy/build.ts`
e `src/addon.ts`. Questa è un’implementazione indipendente del plugin; endpoint
e protocollo del provider possono cambiare.
