#!/usr/bin/env bash
# Pubblica i modpack.
#   ./publish.sh                      tutte le istanze Prism in ~/packs (una cartella per server)
#   ./publish.sh <cartella-istanza>   una sola istanza (l'id viene da pack.properties o dal nome della cartella)
# Variabili: PACKS (default ~/packs), WWW (default /srv/mcfiles/packs)
set -euo pipefail
PACKS="${PACKS:-$HOME/packs}"
WWW="${WWW:-/srv/mcfiles/packs}"
JAR="$(dirname "$0")/pack-builder.jar"
if [ $# -eq 0 ]; then
  java -jar "$JAR" --all "$PACKS" "$WWW"
else
  SRC="$1"
  ID="$(grep -E '^id=' "$SRC/pack.properties" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '[:space:]' || true)"
  [ -n "$ID" ] || ID="$(basename "$SRC" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9_.-]+/-/g')"
  java -jar "$JAR" "$SRC" "$WWW/$ID" --index "$WWW/index.json"
fi
echo "Pubblicato. I client si aggiornano al prossimo avvio."
