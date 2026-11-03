# PackLauncher

Launcher Minecraft minimale per giocare con gli amici su un server con modpack.
L'amico apre `launcher.jar`, inserisce il nome e preme **Gioca**: il launcher
scarica Minecraft, installa NeoForge, sincronizza il modpack ed entra direttamente nel server.

- Un unico jar, nessuna dipendenza esterna (gira con Java 17+; su Java più vecchi mostra un messaggio chiaro).
- **Java per il gioco installato in automatico**: il launcher scarica il runtime ufficiale Mojang
  giusto per la versione di Minecraft (Java 8 / 17 / 21) in `runtime/`.
- **Più versioni di Minecraft e loader**: ogni modpack dichiara la sua versione.
  Loader supportati: `neoforge`, `forge`, `fabric`, `quilt`, `vanilla`.
  Supporta sia il formato moderno (1.13+) sia quello vecchio (argomenti in stringa, native da estrarre).
- Più modpack sullo stesso server (`index.json`), con librerie, asset e runtime Java condivisi.
- Aggiornamenti automatici: a ogni avvio il pack viene confrontato con il server tramite SHA-1.
- Le mod aggiunte a mano dall'utente e le sue opzioni (`options.txt`) non vengono toccate.

> Nota: il launcher usa account **offline** (solo nickname). Il client di Minecraft è
> software a pagamento e usarlo senza licenza viola l'EULA di Mojang.

## Compilazione

```bash
gradle jar
```

Il risultato è `launcher/build/libs/launcher.jar` (da dare agli amici) e
`pack-builder/build/libs/pack-builder.jar` (per il server).

L'indirizzo del server è in `launcher/src/main/resources/launcher.properties`
(`index.url`). Per riusare il launcher con un altro server cambia quel valore e ricompila.

## Lato server (Linux)

### 1. Preparare il modpack

1. Installa il modpack (es. *All the Mods 10 Aeronautics*) con l'app CurseForge sul tuo PC.
2. Copia l'intera cartella dell'istanza sul server, ad esempio in `~/packs/atm10a/`.
   Conviene tenerla in un repo git, così hai lo storico delle versioni.
3. Aggiungi `pack.properties` nella cartella (vedi `server-example/pack.properties`).
   Versione di Minecraft, tipo di loader e versione del loader vengono lette da
   `minecraftinstance.json` (CurseForge). Altrimenti impostale a mano:
   `minecraft=1.20.1`, `loader=forge`, `loaderVersion=47.4.28`.

### 2. Pubblicare

```bash
java -jar pack-builder.jar ~/packs/atm10a /srv/mcfiles/packs/atm10a --index /srv/mcfiles/packs/index.json
```

Il comando genera:

```
/srv/mcfiles/packs/index.json              elenco dei modpack
/srv/mcfiles/packs/atm10a/pack.json        manifest (scritto per ultimo, in modo atomico)
/srv/mcfiles/packs/atm10a/objects/ab/ab…   file, con nome uguale all'hash
```

Per aggiornare il pack basta modificare la cartella e rilanciare lo stesso comando.
`--prune` cancella gli oggetti non più usati (usalo quando nessuno sta scaricando).

Esclusi di default: `logs/`, `saves/`, `screenshots/`, `crash-reports/`, `*.log`, ecc.
Scritti solo se mancanti ("once"): `options.txt`, `servers.dat`, `optionsof.txt`, `optionsshaders.txt`.

### 3. Servire i file

Va bene qualsiasi web server statico. C'è un esempio in `server-example/nginx.conf`.
Consigliato HTTPS con `certbot --nginx`.

### 4. Server Minecraft

Nel `server.properties`:

```properties
online-mode=false
enforce-secure-profile=false
white-list=true
```

Con `online-mode=false` chiunque può entrare con qualunque nome, anche il tuo. Usa la
whitelist (`/whitelist add NomeAmico`) e una mod di login con password per NeoForge 1.21.1.

## Lato amico

1. Serve un Java 17+ qualsiasi solo per aprire il launcher (https://adoptium.net).
   Il Java usato dal gioco lo scarica il launcher da solo.
2. Apri `launcher.jar` con un doppio clic, inserisci il nome e premi **Gioca**.

Il primo avvio scarica circa 1–2 GB. I dati stanno in `%APPDATA%\.lollata-launcher\`.
In caso di crash il launcher propone di aprire il log
(`instances/<pack>/.packlauncher/game-output.log`).

## Test eseguiti

Avvio reale del gioco (Linux, display virtuale) con mod vere scaricate da Modrinth:

| Pack di test | Minecraft | Loader | Java (auto) | Esito |
|---|---|---|---|---|
| ATM10 Aeronautics (struttura) + AppleSkin, JEI | 1.21.1 | NeoForge 21.1.265 | 21 | avviato, mod caricate |
| AppleSkin, JEI | 1.20.1 | Forge 47.4.28 | 17 (scaricato) | avviato, mod caricate |
| AppleSkin, Fabric API | 1.21.1 | Fabric 0.20.1 | 21 | avviato, 57 mod caricate |
| — | 1.20.1 | Quilt 0.30.2 | 17 (scaricato) | avviato |
| — | 1.12.2 | Forge 14.23.5.2864 | 8 (scaricato) | avviato e connesso al server |
| — | 1.16.5 | vanilla | 8 (scaricato) | avviato |

Non testati qui: Windows/macOS reali, il pack ATM10A completo (~450 mod), un server Minecraft vero.

## Pacchetto Windows senza installazioni

```bash
scripts/build-windows-bundle.sh
```

Crea `build/LollataLauncher-Windows.zip` (~47 MB) con Java 21 incluso e `Avvia LollataLauncher.bat`.
Gli amici estraggono lo zip e fanno doppio clic sul `.bat`: non serve installare Java.

## Debug da terminale

```bash
java -jar launcher.jar --cli --name Steve [--pack atm10a] [--memory 8192] [--dry-run]
```

Override utili: `-Dindex.url=…` (server alternativo), `-Dlauncher.home=…` (cartella dati).

## Struttura

```
common/        JSON e SHA-1 (condivisi)
launcher/      Config, Downloader, GameInstaller (vanilla + NeoForge), PackSync,
               GameLauncher (riga di comando), LaunchFlow, LauncherUI (Swing), Main
pack-builder/  genera pack.json + objects/ da una cartella di istanza
server-example/ nginx, pack.properties, publish.sh
```
