#!/usr/bin/env bash
# Finds a chat model that answers quickly with your keys and switches the server to it.
#   cd /opt/follower/server && bash pick-model.sh            (PREFER=gemini-3.8-flash bash pick-model.sh tries that one first)
# Tries several Gemini models (each must answer "Say OK" within 20 seconds); if none does, tries OpenRouter.
set -uo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a

setenv() {
  local name="$1" value="$2" tmp; tmp="$(mktemp)"
  NAME="$name" VALUE="$value" awk 'BEGIN { n = ENVIRON["NAME"]; v = ENVIRON["VALUE"]; d = 0 }
    index($0, n "=") == 1 { if (!d) print n "=" v; d = 1; next } { print } END { if (!d) print n "=" v }' .env > "$tmp"
  cat "$tmp" > .env; rm -f "$tmp"
}

BODY='{"contents":[{"role":"user","parts":[{"text":"Say OK"}]}],"generationConfig":{"maxOutputTokens":20}}'
chosen=""; provider=""
if [ -n "${GEMINI_API_KEY:-}" ]; then
  # Models this key can use, newest names first; the usual fast ones are tried before anything else.
  listed="$(curl -4 -s --max-time 20 -H "x-goog-api-key: $GEMINI_API_KEY" 'https://generativelanguage.googleapis.com/v1beta/models?pageSize=200' \
    | grep -o '"name": *"models/[^"]*"' | sed 's/.*models\///; s/"//' | grep -E 'flash' | grep -viE 'image|tts|live|audio|embedding|preview-tts|exp' || true)"
  candidates="$(printf '%s\n' ${PREFER:-} gemini-flash-lite-latest gemini-3.5-flash-lite gemini-3.8-flash gemini-flash-latest $listed | awk 'NF && !seen[$0]++')"
  echo "Testing Gemini models (20 seconds each at most):"
  for m in $candidates; do
    out="$(curl -4 -s -o /tmp/pick.json --max-time 20 -w '%{http_code} %{time_total}' -H "x-goog-api-key: $GEMINI_API_KEY" -H 'content-type: application/json' \
      -d "$BODY" "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")"
    code="${out%% *}"; secs="${out##* }"
    if [ "$code" = 200 ]; then echo "  $m: answered in ${secs}s"; chosen="$m"; provider=gemini; break
    else echo "  $m: no answer (HTTP $code after ${secs}s) $(grep -o '"message": *"[^"]*"' /tmp/pick.json 2>/dev/null | head -1)"; fi
  done
fi

if [ -z "$chosen" ] && [ -n "${OPENROUTER_API_KEY:-}" ]; then
  m="${OPENROUTER_MODEL:-openrouter/free}"
  echo "Testing OpenRouter ($m):"
  out="$(curl -4 -s -o /tmp/pick.json --max-time 40 -w '%{http_code} %{time_total}' -H "authorization: Bearer $OPENROUTER_API_KEY" -H 'content-type: application/json' \
    -d "{\"model\":\"$m\",\"messages\":[{\"role\":\"user\",\"content\":\"Say OK\"}],\"max_tokens\":20}" https://openrouter.ai/api/v1/chat/completions)"
  code="${out%% *}"; secs="${out##* }"
  if [ "$code" = 200 ] && ! grep -q '"error"' /tmp/pick.json; then echo "  answered in ${secs}s"; chosen="$m"; provider=openrouter
  else echo "  no answer (HTTP $code after ${secs}s) $(grep -o '"message": *"[^"]*"' /tmp/pick.json | head -1)"; fi
fi

rm -f /tmp/pick.json
if [ -z "$chosen" ]; then
  echo; echo "No model answered in time. Check the keys (Google AI Studio / OpenRouter) and try again later."; exit 1
fi
if [ "$provider" = gemini ]; then setenv GEMINI_TEXT_MODEL "$chosen"; setenv TEXT_PROVIDER gemini
else setenv OPENROUTER_MODEL "$chosen"; setenv TEXT_PROVIDER openrouter; fi
echo; echo "Switching Stocky to $provider / $chosen and restarting the API..."
files="-f docker-compose.yml"; [ -f docker-compose.nginx.yml ] && sudo docker compose ps --services 2>/dev/null | grep -qx caddy || files="$files -f docker-compose.nginx.yml"
sudo docker compose $files up -d api >/dev/null && sleep 3
curl -s "https://$(grep -E '^DOMAIN=' .env | cut -d= -f2)/healthz"; echo
