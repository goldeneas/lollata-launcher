#!/usr/bin/env bash
# Installa serve.py come servizio systemd, così parte da solo all'avvio.
#
# Uso (da root):
#   sudo ./install.sh [--dir /srv/mcfiles] [--port 8080] [--bind 0.0.0.0]
#                     [--user www-data] [--prefix /opt/lollata] [--name mcfiles]
#   sudo ./install.sh --uninstall [--name mcfiles] [--prefix /opt/lollata]
#
# Rilanciarlo con opzioni diverse aggiorna l'installazione esistente
# (copia di nuovo serve.py, riscrive l'unità e riavvia il servizio).
# I file del modpack nella cartella servita non vengono mai toccati.
set -euo pipefail

DIR=/srv/mcfiles
PORT=8080
BIND=0.0.0.0
SVC_USER=www-data
PREFIX=/opt/lollata
NAME=mcfiles
UNINSTALL=0

usage() { sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
die() { echo "Errore: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --dir)       DIR="${2:?--dir vuole un valore}"; shift 2 ;;
    --port)      PORT="${2:?--port vuole un valore}"; shift 2 ;;
    --bind)      BIND="${2:?--bind vuole un valore}"; shift 2 ;;
    --user)      SVC_USER="${2:?--user vuole un valore}"; shift 2 ;;
    --prefix)    PREFIX="${2:?--prefix vuole un valore}"; shift 2 ;;
    --name)      NAME="${2:?--name vuole un valore}"; shift 2 ;;
    --uninstall) UNINSTALL=1; shift ;;
    -h|--help)   usage 0 ;;
    *)           echo "Opzione sconosciuta: $1" >&2; usage 1 ;;
  esac
done

[ "$(id -u)" -eq 0 ] || die "va eseguito da root (sudo $0 ...)"
command -v systemctl >/dev/null || die "systemd non trovato (manca systemctl)"
case "$NAME" in *[!A-Za-z0-9_.-]*|"") die "--name non valido: $NAME" ;; esac

UNIT="/etc/systemd/system/$NAME.service"

if [ "$UNINSTALL" -eq 1 ]; then
  systemctl disable --now "$NAME" 2>/dev/null || true
  rm -f "$UNIT" "$PREFIX/serve.py"
  rmdir "$PREFIX" 2>/dev/null || true
  systemctl daemon-reload
  echo "Servizio $NAME rimosso. La cartella dei file del modpack non è stata toccata."
  exit 0
fi

case "$PORT" in *[!0-9]*|"") die "--port deve essere un numero: $PORT" ;; esac
[ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || die "--port fuori intervallo: $PORT"
case "$DIR" in /*) ;; *) die "--dir deve essere un percorso assoluto: $DIR" ;; esac
case "$DIR$PREFIX" in *[[:space:]]*) die "i percorsi non possono contenere spazi" ;; esac
DIR="${DIR%/}"

PYTHON="$(command -v python3 || true)"
[ -n "$PYTHON" ] || die "python3 non trovato: installalo (es. apt install python3)"

SRC="$(cd "$(dirname "$0")" && pwd)/serve.py"
[ -f "$SRC" ] || die "serve.py non trovato accanto a install.sh ($SRC)"

# utente del servizio: se non esiste lo creo come utente di sistema senza login
if ! id "$SVC_USER" >/dev/null 2>&1; then
  echo "Creo l'utente di sistema $SVC_USER"
  useradd --system --no-create-home --home-dir /nonexistent \
          --shell /usr/sbin/nologin "$SVC_USER" 2>/dev/null \
    || useradd -r -M -d /nonexistent -s /sbin/nologin "$SVC_USER"
fi

# cartella dei file: la creo se manca (la riempie publish.sh)
if [ ! -d "$DIR" ]; then
  echo "Creo $DIR/packs"
  install -d -m 755 "$DIR/packs"
fi

install -d -m 755 "$PREFIX"
install -m 755 "$SRC" "$PREFIX/serve.py"

# le porte sotto 1024 richiedono un permesso in più per un utente non root
# con la cartella sotto /home o /root serve almeno la lettura della home
PROTECT_HOME=true
case "$DIR" in /home/*|/root/*|/run/user/*) PROTECT_HOME=read-only ;; esac

CAPS=""
if [ "$PORT" -lt 1024 ]; then
  CAPS="AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE"
fi

cat > "$UNIT" <<EOF
# Generato da server-example/install.sh: rilancia lo script per cambiarlo.
# Log: journalctl -u $NAME -f
[Unit]
Description=Lollata Launcher - file del modpack (HTTP)
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=$PYTHON $PREFIX/serve.py --dir $DIR --port $PORT --bind $BIND
User=$SVC_USER
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=$PROTECT_HOME
ReadOnlyPaths=$DIR
$CAPS

[Install]
WantedBy=multi-user.target
EOF
chmod 644 "$UNIT"

systemctl daemon-reload
systemctl enable "$NAME" >/dev/null
systemctl restart "$NAME"

sleep 1
if systemctl is-active --quiet "$NAME"; then
  echo "Fatto: $NAME è attivo e partirà a ogni avvio."
  echo "  Serve $DIR su http://$BIND:$PORT/"
  echo "  Stato: systemctl status $NAME    Log: journalctl -u $NAME -f"
else
  echo "Il servizio $NAME non è partito. Ultime righe del log:" >&2
  journalctl -u "$NAME" -n 20 --no-pager >&2 || true
  exit 1
fi
