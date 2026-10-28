#!/usr/bin/env bash
# Chooses which service hears the owner and speaks Stocky's replies (Server voice in the app), then restarts the API.
#   cd /opt/follower/server && bash use-voice.sh gemini    (Gemini key only: no Sarvam needed)
#   bash use-voice.sh sarvam                               (Sarvam first, when it has credit)
#   bash use-voice.sh auto                                 (Sarvam > Google Cloud > Gemini, whichever keys are set)
# Whatever is chosen, if it fails (no credit, rate limit) the next one answers and the failing one rests for 30 minutes.
set -euo pipefail
cd "$(dirname "$0")"
choice="${1:-gemini}"
case "$choice" in gemini|sarvam|google|auto) ;; *) echo "Use: bash use-voice.sh gemini|sarvam|google|auto"; exit 1;; esac
setenv() {
  local name="$1" value="$2" tmp; tmp="$(mktemp)"
  NAME="$name" VALUE="$value" awk 'BEGIN { n = ENVIRON["NAME"]; v = ENVIRON["VALUE"]; d = 0 }
    index($0, n "=") == 1 { if (!d) print n "=" v; d = 1; next } { print } END { if (!d) print n "=" v }' .env > "$tmp"
  cat "$tmp" > .env; rm -f "$tmp"
}
setenv STT_PROVIDER "$choice"; setenv TTS_PROVIDER "$choice"
files="-f docker-compose.yml"
sudo docker compose ps --services 2>/dev/null | grep -qx caddy || files="$files -f docker-compose.nginx.yml"
echo "Server voice: $choice. Rebuilding and restarting the API..."
sudo docker compose $files up -d --build --force-recreate api >/dev/null
sleep 4
curl -s "https://$(grep -E '^DOMAIN=' .env | cut -d= -f2)/healthz"; echo
