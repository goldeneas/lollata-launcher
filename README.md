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
- **Più server, ognuno con il suo modpack** (`index.json`): versione di Minecraft, loader, indirizzo
  e tipo di accesso (premium / non-premium) per ciascuno. L'utente sceglie il server dall'elenco;
  ogni pack ha la sua istanza e si aggiorna da solo, mentre librerie, asset e runtime Java sono condivisi.
  Nickname e RAM vengono ricordati per server.
- Aggiornamenti automatici: a ogni avvio il pack viene confrontato con il server tramite SHA-1.
- Le mod aggiunte a mano dall'utente e le sue opzioni (`options.txt`) non vengono toccate.

> Nota: il launcher usa account **offline** (solo nickname). Il client di Minecraft è
> software a pagamento e usarlo senza licenza viola l'EULA di Mojang.

## Download

I jar vengono compilati da GitHub Actions (workflow `.github/workflows/build.yml`):

- **Release `latest`**: a ogni push su `main` la Release
  [latest](https://github.com/goldeneas/lollata-launcher/releases/tag/latest) viene ricreata con
  `launcher.jar` e `pack-builder.jar` dell'ultimo commit.
- **Versioni numerate**: pubblicando un tag (`git tag v1.0.0 && git push origin v1.0.0`) si crea una Release
  con i due jar e i pacchetti `LollataLauncher-Windows.zip` / `LollataLauncher-Linux.tar.gz` con Java incluso.
- **Build di una pull request**: scheda
  [Actions](https://github.com/goldeneas/lollata-launcher/actions/workflows/build.yml) → apri l'esecuzione
  → sezione *Artifacts* → `launcher` o `pack-builder` (file .zip che contiene il jar).

Sul server Linux, con la [GitHub CLI](https://cli.github.com) autenticata (il repository è privato,
quindi i link di download diretti non funzionano senza login):

```bash
gh release download latest --repo goldeneas/lollata-launcher --pattern pack-builder.jar --clobber
```

## Compilazione

Serve Java 17+ e [Gradle](https://gradle.org/install/) (testato con 8.14):

```bash
gradle jar
```

Il risultato è `launcher/build/libs/launcher.jar` (da dare agli amici) e
`pack-builder/build/libs/pack-builder.jar` (per il server).

L'indirizzo del server è in `launcher/src/main/resources/launcher.properties`
(`index.url`). Per riusare il launcher con un altro server cambia quel valore e ricompila.

## Lato server (Linux)

### 1. Preparare il modpack

Il builder supporta solo istanze di **Prism Launcher**.

1. Installa il modpack (es. *All the Mods 10 Aeronautics*) in Prism Launcher sul tuo PC
   (Aggiungi istanza → CurseForge / Modrinth). Usa il pack client, non il serverpack:
   solo così vengono incluse anche le mod client-side.
2. Copia l'intera cartella dell'istanza sul server, ad esempio in `~/packs/atm10a/`.
   È la cartella che contiene `instance.cfg`, `mmc-pack.json` e `.minecraft/`
   (in Prism: tasto destro sull'istanza → Cartella).
   Conviene tenerla in un repo git, così hai lo storico delle versioni.
3. Aggiungi `pack.properties` accanto a `instance.cfg` (vedi `server-example/pack.properties`).
   Versione di Minecraft, tipo di loader e versione del loader vengono lette da
   `mmc-pack.json`, il nome dell'istanza da `instance.cfg`. Se serve puoi forzarli:
   `minecraft=1.20.1`, `loader=forge`, `loaderVersion=47.4.28`.

Vengono pubblicati solo i file dentro `.minecraft/` (o `minecraft/` nelle istanze vecchie).

### 2. Pubblicare

Ogni server Minecraft ha la sua istanza Prism. Mettile tutte in una cartella, una per server:

```
~/packs/atm10a/     (instance.cfg, mmc-pack.json, .minecraft/, pack.properties)
~/packs/svago/
```

e pubblicale tutte insieme:

```bash
java -jar pack-builder.jar --all ~/packs /srv/mcfiles/packs
# oppure: server-example/publish.sh   (stesso comando, con PACKS e WWW modificabili)
```

Il comando genera:

```
/srv/mcfiles/packs/index.json              elenco dei server (riscritto con esattamente le istanze trovate)
/srv/mcfiles/packs/atm10a/pack.json        manifest (scritto per ultimo, in modo atomico)
/srv/mcfiles/packs/atm10a/objects/ab/ab…   file, con nome uguale all'hash
/srv/mcfiles/packs/svago/...
```

L'id di ogni server è `id` in `pack.properties` oppure il nome della cartella. Se togli un'istanza
da `~/packs`, al prossimo `--all` sparisce dall'elenco (i suoi file sul web server restano).

Per pubblicare una sola istanza senza toccare le altre:

```bash
java -jar pack-builder.jar ~/packs/atm10a /srv/mcfiles/packs/atm10a --index /srv/mcfiles/packs/index.json
```

Ogni voce di `index.json` contiene nome, indirizzo, versione di Minecraft, loader e `authMode`,
così il launcher mostra l'elenco senza scaricare ogni manifest:

```json
{ "formatVersion": 2,
  "packs": [ { "id": "atm10a", "name": "All the Mods 10 Aeronautics", "url": "atm10a/pack.json",
               "version": "0.7.1-20261104-1000", "minecraft": "1.21.1",
               "loader": { "type": "neoforge", "version": "21.1.228" },
               "server": "mc.lollata.xyz:25565", "authMode": "offline" } ] }
```

`authMode` è `premium` (serve un account Microsoft), `offline` (basta un nickname) o assente
(sconosciuto). Si imposta con `authMode=` in `pack.properties`; con `auto` (default) viene chiesto
al server Minecraft indicato in `server`. I launcher già distribuiti leggono ancora l'indice
(usano solo `id`, `name` e `url`).

Per aggiornare un pack basta modificare la sua cartella e rilanciare lo stesso comando.
`--prune` cancella gli oggetti non più usati (usalo quando nessuno sta scaricando).

Esclusi di default: `logs/`, `saves/`, `screenshots/`, `crash-reports/`, `*.log`, ecc.
Scritti solo se mancanti ("once"): `options.txt`, `servers.dat`, `optionsof.txt`, `optionsshaders.txt`.

### 3. Servire i file

Il modo più semplice è lo script incluso (serve solo Python 3, già presente su quasi tutte le distro):

```bash
server-example/serve.py --dir /srv/mcfiles --port 8080
```

Serve `/srv/mcfiles` in HTTP semplice, senza elenco delle cartelle, con
`Cache-Control: no-cache` sui `.json` e cache lunga sugli `objects/` (immutabili).
Opzioni anche da variabili d'ambiente: `MCFILES_DIR`, `MCFILES_PORT`, `MCFILES_BIND`.

**HTTPS con Nginx Proxy Manager**: crea un Proxy Host `mcfiles.lollata.xyz` →
scheme `http`, forward hostname/IP della macchina con lo script, porta `8080`,
e nella scheda SSL richiedi il certificato Let's Encrypt con "Force SSL".
Se NPM e lo script stanno sulla stessa macchina puoi usare `--bind 127.0.0.1`
(con NPM in Docker usa invece l'IP del host, es. `172.17.0.1`, o lascia `0.0.0.0`
e chiudi la porta 8080 dal firewall verso l'esterno).

**Avvio automatico con systemd**: dalla cartella `server-example/` lancia

```bash
sudo ./install.sh                          # /srv/mcfiles sulla porta 8080
sudo ./install.sh --dir /srv/mcfiles --port 8080 --bind 127.0.0.1
```

Lo script copia `serve.py` in `/opt/lollata/`, crea `/srv/mcfiles/packs` se manca,
scrive `/etc/systemd/system/mcfiles.service`, lo abilita all'avvio e lo avvia.
Altre opzioni: `--user` (default `www-data`, creato se non esiste), `--prefix`
(default `/opt/lollata`), `--name` (nome del servizio, default `mcfiles`).
Rilanciarlo con opzioni diverse aggiorna l'installazione; `sudo ./install.sh --uninstall`
toglie il servizio senza toccare i file del modpack.
Stato e log: `systemctl status mcfiles`, `journalctl -u mcfiles -f`.

Se preferisci farlo a mano c'è l'unità di esempio `server-example/mcfiles.service`
(copia `serve.py` in `/opt/lollata/` o modifica il percorso in `ExecStart`).

In alternativa va bene qualsiasi web server statico, ad esempio nginx con
`server-example/nginx.conf` e `certbot --nginx`.

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
2. Apri `launcher.jar` con un doppio clic, scegli il server (se ce n'è più di uno), inserisci il nome e premi **Gioca**.
   Sotto l'elenco vedi versione di Minecraft, loader, indirizzo e se serve un account premium.
   Nome e RAM vengono ricordati separatamente per ogni server.

Il primo avvio scarica circa 1–2 GB. I dati stanno in `%APPDATA%\.lollata-launcher\` (Windows) o `~/.lollata-launcher/` (Linux).
In caso di crash il launcher propone di aprire il log
(`instances/<pack>/.packlauncher/game-output.log`).

## Test automatici

```bash
gradle test
```

Girano anche in GitHub Actions a ogni push e pull request. Coprono: lettura di `index.json`
(formato nuovo e vecchio, id pericolosi scartati) e di `pack.json` con `authMode`; impostazioni
per server (nickname, RAM, ripiego sulle impostazioni vecchie); installazione e aggiornamento di due
server da un web server HTTP locale (mod aggiornate e tolte, mod dell'utente e `options.txt` intatti,
pack separati); pack-builder per una istanza e con `--all` (manifest, indice, id duplicati o non validi).

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

## Pacchetti pronti (Java incluso, nessuna installazione)

```bash
scripts/build-bundle.sh all        # oppure: windows | linux
```

Crea in `build/`:

- `LollataLauncher-Windows.zip` (~47 MB): estrai e doppio clic su `Avvia LollataLauncher.bat`.
- `LollataLauncher-Linux.tar.gz` (~50 MB, x86_64): estrai ed esegui `./avvia.sh`.
  `./crea-collegamento.sh` aggiunge il launcher al menu delle applicazioni.

Il Java incluso (Temurin 21) serve ad aprire il launcher, e viene riusato per i modpack 1.20.5+.
Per le versioni più vecchie il launcher scarica da solo Java 8 o 17.

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
pack-builder/  genera pack.json + objects/ da un'istanza Prism Launcher
server-example/ serve.py (web server), install.sh (servizio systemd), mcfiles.service, nginx, pack.properties, publish.sh
```
