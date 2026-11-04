#!/usr/bin/env bash
# Crea i pacchetti "pronti all'uso" con Java incluso: gli amici non installano niente.
#   windows -> <Nome>-Windows.zip     (doppio clic su "Avvia <Nome>.bat")
#   linux   -> <Nome>-Linux.tar.gz    (./avvia.sh, oppure ./crea-collegamento.sh per il menu)
# Uso: scripts/build-bundle.sh <windows|linux|all> [cartella-output]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${1:-all}"
OUT="$(mkdir -p "${2:-$ROOT/build}" && cd "${2:-$ROOT/build}" && pwd)"
prop() { grep "^$1=" "$ROOT/launcher/src/main/resources/launcher.properties" | cut -d= -f2; }
NAME="$(prop launcher.name)"
JRE_API="https://api.adoptium.net/v3/binary/latest/21/ga"

(cd "$ROOT" && gradle jar -q)

# Cartella di lavoro con <Nome>/java (Temurin JRE 21 x64) e <Nome>/launcher.jar.
# Uso: work="$(prepare <windows|linux>)"
prepare() {
  local os="$1" work; work="$(mktemp -d)"
  echo "$os: scarico Temurin JRE 21 x64..." >&2
  curl -fsSL -o "$work/jre.archive" "$JRE_API/$os/x64/jre/hotspot/normal/eclipse?project=jdk"
  mkdir -p "$work/$NAME" "$work/jre"
  if [ "$os" = windows ]; then unzip -q "$work/jre.archive" -d "$work/jre"; else tar -xzf "$work/jre.archive" -C "$work/jre"; fi
  mv "$work"/jre/*/ "$work/$NAME/java"
  cp "$ROOT/launcher/build/libs/launcher.jar" "$work/$NAME/launcher.jar"
  echo "$work"
}

build_windows() {
  local work; work="$(prepare windows)"
  # "start" + javaw: la finestra del prompt si chiude subito.
  printf '@echo off\r\ncd /d "%%~dp0"\r\nstart "" "%%~dp0java\\bin\\javaw.exe" -jar "%%~dp0launcher.jar"\r\n' \
    > "$work/$NAME/Avvia $NAME.bat"
  printf 'Estrai questa cartella dove vuoi e fai doppio clic su "Avvia %s.bat".\r\nMinecraft, il modpack e il Java per il gioco vengono scaricati in automatico.\r\n' "$NAME" \
    > "$work/$NAME/LEGGIMI.txt"
  rm -f "$OUT/$NAME-Windows.zip"
  (cd "$work" && zip -qr "$OUT/$NAME-Windows.zip" "$NAME")
  rm -rf "$work"
  echo "Creato $OUT/$NAME-Windows.zip ($(du -h "$OUT/$NAME-Windows.zip" | cut -f1))"
}

build_linux() {
  local work; work="$(prepare linux)"
  cp "$ROOT/launcher/src/main/resources/icon.png" "$work/$NAME/icon.png"

  cat > "$work/$NAME/avvia.sh" <<'SH'
#!/bin/sh
# Avvia il launcher con il Java incluso nel pacchetto.
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/java/bin/java" -jar "$DIR/launcher.jar" "$@"
SH

  sed "s/@NAME@/$NAME/g" > "$work/$NAME/crea-collegamento.sh" <<'SH'
#!/bin/sh
# Aggiunge "@NAME@" al menu delle applicazioni (e al desktop, se esiste).
DIR="$(cd "$(dirname "$0")" && pwd)"
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$APPS"
FILE="$APPS/@NAME@.desktop"
cat > "$FILE" <<DESKTOP
[Desktop Entry]
Type=Application
Name=@NAME@
Comment=Minecraft con il modpack del server
Exec="$DIR/avvia.sh"
Icon=$DIR/icon.png
Terminal=false
Categories=Game;
DESKTOP
chmod +x "$FILE"
DESK="$(xdg-user-dir DESKTOP 2>/dev/null || echo "$HOME/Desktop")"
if [ -d "$DESK" ]; then
  cp "$FILE" "$DESK/"
  chmod +x "$DESK/@NAME@.desktop"
  # GNOME: segna il collegamento come affidabile
  gio set "$DESK/@NAME@.desktop" metadata::trusted true 2>/dev/null || true
fi
echo "Collegamento creato: trovi @NAME@ nel menu delle applicazioni."
SH

  cat > "$work/$NAME/LEGGIMI.txt" <<TXT
Estrai l'archivio dove vuoi (es. tar -xzf $NAME-Linux.tar.gz), poi:

  ./avvia.sh                 avvia il launcher
  ./crea-collegamento.sh     (facoltativo) aggiunge $NAME al menu delle applicazioni

Minecraft, il modpack e il Java per il gioco vengono scaricati in automatico.
I dati stanno in ~/.$(prop launcher.dir)/
TXT
  chmod +x "$work/$NAME/avvia.sh" "$work/$NAME/crea-collegamento.sh"
  rm -f "$OUT/$NAME-Linux.tar.gz"
  (cd "$work" && tar -czf "$OUT/$NAME-Linux.tar.gz" "$NAME")
  rm -rf "$work"
  echo "Creato $OUT/$NAME-Linux.tar.gz ($(du -h "$OUT/$NAME-Linux.tar.gz" | cut -f1))"
}

case "$TARGET" in
  windows) build_windows ;;
  linux) build_linux ;;
  all) build_windows; build_linux ;;
  *) echo "Uso: $0 <windows|linux|all> [cartella-output]"; exit 1 ;;
esac
