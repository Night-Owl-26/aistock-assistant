#!/usr/bin/env bash
# Chooses which service hears the owner first (Server voice in the app), then restarts the API.
#   cd /opt/follower/server && bash use-voice.sh gemini    (Gemini key only; the default)
#   bash use-voice.sh whisper                              (Whisper on this server; turn it on first: bash enable-whisper.sh)
# Replies are always spoken by Gemini, in the voice each owner picks in the app. If the first choice fails (rate limit),
# the other one hears instead and the failing one rests for a while.
set -euo pipefail
cd "$(dirname "$0")"
choice="${1:-gemini}"
case "$choice" in gemini|whisper) ;; *) echo "Use: bash use-voice.sh gemini|whisper"; exit 1;; esac
setenv() {
  local name="$1" value="$2" tmp; tmp="$(mktemp)"
  NAME="$name" VALUE="$value" awk 'BEGIN { n = ENVIRON["NAME"]; v = ENVIRON["VALUE"]; d = 0 }
    index($0, n "=") == 1 { if (!d) print n "=" v; d = 1; next } { print } END { if (!d) print n "=" v }' .env > "$tmp"
  cat "$tmp" > .env; rm -f "$tmp"
}
setenv STT_PROVIDER "$choice"
files="-f docker-compose.yml"
sudo docker compose ps --services 2>/dev/null | grep -qx caddy || files="$files -f docker-compose.nginx.yml"
echo "Server voice hears with: $choice. Rebuilding and restarting the API..."
sudo docker compose $files up -d --build --force-recreate api >/dev/null
sleep 4
curl -s "https://$(grep -E '^DOMAIN=' .env | cut -d= -f2)/healthz"; echo
