#!/usr/bin/env bash
# Pubblica una nuova versione del modpack.
# Uso: ./publish.sh <cartella-istanza-prism> <id-pack>
set -euo pipefail
SRC="$1"; ID="$2"
WWW=/srv/mcfiles/packs
java -jar "$(dirname "$0")/pack-builder.jar" "$SRC" "$WWW/$ID" --index "$WWW/index.json"
echo "Pubblicato. I client si aggiornano al prossimo avvio."
