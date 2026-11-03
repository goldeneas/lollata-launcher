#!/usr/bin/env bash
# Crea LollataLauncher-Windows.zip: launcher.jar + Java 21 (Temurin JRE) + "Avvia.bat".
# Gli amici estraggono lo zip e fanno doppio clic su Avvia.bat: nessuna installazione.
# Uso: scripts/build-windows-bundle.sh [cartella-output]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/build}"
NAME="$(grep '^launcher.name=' "$ROOT/launcher/src/main/resources/launcher.properties" | cut -d= -f2)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

[ -f "$ROOT/launcher/build/libs/launcher.jar" ] || (cd "$ROOT" && gradle jar -q)

echo "Scarico Temurin JRE 21 per Windows x64..."
curl -fsSL -o "$WORK/jre.zip" \
  "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk"
mkdir -p "$WORK/$NAME"
unzip -q "$WORK/jre.zip" -d "$WORK/jre"
mv "$WORK"/jre/*/ "$WORK/$NAME/java"
cp "$ROOT/launcher/build/libs/launcher.jar" "$WORK/$NAME/launcher.jar"

# "start" + javaw: la finestra nera del prompt si chiude subito.
printf '@echo off\r\ncd /d "%%~dp0"\r\nstart "" "%%~dp0java\\bin\\javaw.exe" -jar "%%~dp0launcher.jar"\r\n' \
  > "$WORK/$NAME/Avvia $NAME.bat"
printf 'Estrai questa cartella dove vuoi e fai doppio clic su "Avvia %s.bat".\r\nMinecraft, il modpack e il Java per il gioco vengono scaricati in automatico.\r\n' "$NAME" \
  > "$WORK/$NAME/LEGGIMI.txt"

mkdir -p "$OUT"
rm -f "$OUT/$NAME-Windows.zip"
(cd "$WORK" && zip -qr "$OUT/$NAME-Windows.zip" "$NAME")
echo "Creato $OUT/$NAME-Windows.zip ($(du -h "$OUT/$NAME-Windows.zip" | cut -f1))"
