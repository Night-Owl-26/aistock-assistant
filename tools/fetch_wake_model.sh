#!/usr/bin/env bash
# Downloads the small offline speech model used to hear "Hey Stocky" without the phone's beeping recogniser,
# and puts it where the app expects it (app/src/main/assets/model-wake). Run once before building.
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=app/src/main/assets/model-wake
if [ -f "$DEST/uuid" ]; then echo "wake model already present"; exit 0; fi
TMP=$(mktemp -d)
for NAME in vosk-model-small-en-in-0.4 vosk-model-small-en-us-0.15; do
  if curl -fsSL --retry 3 -o "$TMP/m.zip" "https://alphacephei.com/vosk/models/$NAME.zip"; then
    unzip -q "$TMP/m.zip" -d "$TMP/x" && break
  fi
done
SRC=$(find "$TMP/x" -mindepth 1 -maxdepth 1 -type d | head -1)
[ -n "$SRC" ] || { echo "could not download the wake model"; exit 1; }
rm -rf "$DEST"; mkdir -p "$DEST"
cp -r "$SRC"/. "$DEST"/
# StorageService.unpack copies the model again only when this id changes.
basename "$SRC" > "$DEST/uuid"
du -sh "$DEST"; ls "$DEST"
