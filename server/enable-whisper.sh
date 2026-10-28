#!/usr/bin/env bash
# Turns on Whisper speech-to-text on this server (OpenAI's Whisper models via faster-whisper, CPU only).
#   cd /opt/follower/server && bash enable-whisper.sh            (WHISPER_MODEL=small|medium|large-v3 to choose)
#   bash enable-whisper.sh off                                     (back to Sarvam / Gemini)
set -euo pipefail
cd "$(dirname "$0")"
setenv() {
  local name="$1" value="$2" tmp; tmp="$(mktemp)"
  NAME="$name" VALUE="$value" awk 'BEGIN { n = ENVIRON["NAME"]; v = ENVIRON["VALUE"]; d = 0 }
    index($0, n "=") == 1 { if (!d) print n "=" v; d = 1; next } { print } END { if (!d) print n "=" v }' .env > "$tmp"
  cat "$tmp" > .env; rm -f "$tmp"
}
files="-f docker-compose.yml"
sudo docker compose ps --services 2>/dev/null | grep -qx caddy || files="$files -f docker-compose.nginx.yml"

if [ "${1:-}" = off ]; then
  setenv STT_PROVIDER auto; setenv WHISPER_URL ""; setenv COMPOSE_PROFILES ""
  sudo docker compose $files --profile whisper stop whisper >/dev/null 2>&1 || true
  sudo docker compose $files up -d api >/dev/null
  echo "Whisper turned off."; exit 0
fi

cores="$(nproc)"; mem_gb="$(awk '/MemTotal/ {printf "%d", $2/1024/1024}' /proc/meminfo)"
model="${WHISPER_MODEL:-}"
if [ -z "$model" ]; then model=$([ "$cores" -ge 4 ] && [ "$mem_gb" -ge 4 ] && echo medium || echo small); fi
echo "This server: $cores CPU cores, ${mem_gb} GB memory -> Whisper model '$model'"
[ "$cores" -lt 2 ] && echo "!! Only $cores core: Whisper will be slow (10+ seconds per request). Gemini is the better choice here."

setenv WHISPER_MODEL "$model"; setenv WHISPER_URL "http://whisper:9000"; setenv STT_PROVIDER whisper; setenv COMPOSE_PROFILES whisper
echo "Building Whisper and downloading the '$model' model (first time: several minutes, about 0.5-3 GB)..."
sudo docker compose $files --profile whisper up -d --build whisper
for i in $(seq 1 180); do
  if sudo docker compose $files exec -T whisper python -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:9000/health', timeout=4).read().decode())" 2>/dev/null; then ready=1; break; fi
  sleep 10
done
[ -n "${ready:-}" ] || { echo "!! Whisper did not start. Logs: sudo docker compose logs whisper --tail 50"; exit 1; }
echo "Speed test (3 seconds of audio):"
sudo docker compose $files exec -T whisper python -c "
import base64, json, urllib.request, math, struct
pcm = b''.join(struct.pack('<h', int(3000*math.sin(i/8))) for i in range(48000))
req = urllib.request.Request('http://127.0.0.1:9000/transcribe', json.dumps({'audio': base64.b64encode(pcm).decode(), 'language': 'ta-IN', 'vad': False}).encode(), {'content-type': 'application/json'})
print('  took', json.load(urllib.request.urlopen(req, timeout=120))['took'], 'seconds')"
sudo docker compose $files up -d api >/dev/null
sleep 4
curl -s "https://$(grep -E '^DOMAIN=' .env | cut -d= -f2)/healthz"; echo
echo "Done: Server voice now hears with Whisper. Replies are still spoken by Sarvam/Gemini."
