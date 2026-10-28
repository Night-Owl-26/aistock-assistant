#!/usr/bin/env bash
# One-command setup of the Follower server on a fresh Ubuntu machine (Oracle Cloud Always Free works).
#
#   curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github.raw" \
#     https://api.github.com/repos/Night-Owl-26/aistock-assistant/contents/server/install.sh | \
#     GITHUB_TOKEN=$GITHUB_TOKEN GEMINI_API_KEY=... SARVAM_API_KEY=... OPENROUTER_API_KEY=... bash
#
# Run it again any time: it updates the code, keeps your passwords and data, and only changes the keys you pass.
# Keys can also be left out of the command; the script then asks for them (input hidden).
set -euo pipefail

DIR="${FOLLOWER_DIR:-/opt/follower}"
REPO="${FOLLOWER_REPO:-https://github.com/Night-Owl-26/aistock-assistant.git}"
ENV_ONLY="${1:-}"   # --env-only: only write server/.env (used by tests)

say() { printf '\n\033[1;32m== %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!! %s\033[0m\n' "$*"; }
SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"

# Sets NAME=VALUE in a .env file, replacing an existing line; values are written exactly as given.
setenv() {
  local file="$1" name="$2" value="$3" tmp
  tmp="$(mktemp)"
  NAME="$name" VALUE="$value" awk 'BEGIN { n = ENVIRON["NAME"]; v = ENVIRON["VALUE"]; done = 0 }
    index($0, n "=") == 1 { if (!done) print n "=" v; done = 1; next } { print }
    END { if (!done) print n "=" v }' "$file" > "$tmp"
  cat "$tmp" > "$file"; rm -f "$tmp"
}
getenv() { grep -E "^$2=" "$1" 2>/dev/null | head -1 | cut -d= -f2- || true; }

ask_key() { # name, description
  local name="$1" what="$2" current value
  current="$(getenv "$ENVF" "$name")"
  value="${!name:-}"
  if [ -z "$value" ] && [ -z "$current" ] && [ -t 0 -o -e /dev/tty ] && [ -z "${NONINTERACTIVE:-}" ]; then
    printf '%s (%s) - paste it and press Enter, or just press Enter to skip: ' "$name" "$what" > /dev/tty
    IFS= read -rs value < /dev/tty || value=""
    echo > /dev/tty
  fi
  value="$(printf '%s' "$value" | tr -d '[:space:]')"
  if [ -n "$value" ]; then setenv "$ENVF" "$name" "$value"; echo "  $name saved"; elif [ -n "$current" ]; then echo "  $name kept"; else echo "  $name not set"; fi
}

if [ "$ENV_ONLY" != "--env-only" ]; then
  say "Opening ports 80 and 443 in this machine's firewall"
  if command -v iptables >/dev/null 2>&1; then
    for p in 80 443; do
      $SUDO iptables -C INPUT -m state --state NEW -p tcp --dport "$p" -j ACCEPT 2>/dev/null || $SUDO iptables -I INPUT 6 -m state --state NEW -p tcp --dport "$p" -j ACCEPT 2>/dev/null || $SUDO iptables -I INPUT -p tcp --dport "$p" -j ACCEPT
    done
    if ! command -v netfilter-persistent >/dev/null 2>&1; then
      $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -q iptables-persistent >/dev/null 2>&1 || true
    fi
    $SUDO netfilter-persistent save >/dev/null 2>&1 || warn "could not save firewall rules (they still work until reboot)"
  fi

  say "Installing Docker and git (if missing)"
  command -v git >/dev/null 2>&1 || { $SUDO apt-get update -q && $SUDO apt-get install -y -q git; }
  command -v docker >/dev/null 2>&1 || curl -fsSL https://get.docker.com | $SUDO sh
  $SUDO docker compose version >/dev/null

  say "Getting the latest code into $DIR"
  if [ -d "$DIR/.git" ]; then
    if [ -n "${GITHUB_TOKEN:-}" ]; then $SUDO git -C "$DIR" pull --ff-only -q "https://x-access-token:${GITHUB_TOKEN}@${REPO#https://}" main
    else $SUDO git -C "$DIR" pull --ff-only -q; fi
  else
    $SUDO mkdir -p "$DIR"
    # A private repository needs a GitHub token with read access: GITHUB_TOKEN=... bash install.sh
    if [ -n "${GITHUB_TOKEN:-}" ]; then
      $SUDO git clone -q "https://x-access-token:${GITHUB_TOKEN}@${REPO#https://}" "$DIR"
      $SUDO git -C "$DIR" remote set-url origin "$REPO"
    else
      $SUDO git clone -q "$REPO" "$DIR"
    fi
  fi
  $SUDO chown -R "$(id -u):$(id -g)" "$DIR"
fi

ENVF="$DIR/server/.env"
say "Writing settings to $ENVF"
[ -f "$ENVF" ] || cp "$DIR/server/.env.example" "$ENVF"
chmod 600 "$ENVF"
rand() { openssl rand -hex 32 2>/dev/null || head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'; }
for n in POSTGRES_PASSWORD JWT_SECRET ADMIN_TOKEN; do
  cur="$(getenv "$ENVF" "$n")"
  case "$cur" in ""|change-me*) setenv "$ENVF" "$n" "$(rand)"; echo "  $n generated";; *) echo "  $n kept";; esac
done
cur_domain="$(getenv "$ENVF" DOMAIN)"
if [ -n "${DOMAIN:-}" ]; then
  setenv "$ENVF" DOMAIN "$DOMAIN"
elif [ -z "$cur_domain" ] || [ "$cur_domain" = "140.238.1.2.sslip.io" ]; then
  ip="$(curl -fsS -4 --max-time 10 https://ifconfig.me 2>/dev/null || curl -fsS -4 --max-time 10 https://api.ipify.org 2>/dev/null || true)"
  [ -n "$ip" ] || { warn "could not find this machine's public IP; set DOMAIN=<ip>.sslip.io and run again"; exit 1; }
  setenv "$ENVF" DOMAIN "$ip.sslip.io"
fi
DOMAIN_NOW="$(getenv "$ENVF" DOMAIN)"
echo "  address: https://$DOMAIN_NOW"

ask_key GEMINI_API_KEY "Google Gemini, for Stocky and live voice"
ask_key SARVAM_API_KEY "Sarvam AI, Tamil speech-to-text"
ask_key OPENROUTER_API_KEY "OpenRouter, optional spare chat model"
ask_key GOOGLE_CLOUD_API_KEY "Google Cloud Speech/Text-to-Speech, optional"
# With a Gemini key, Stocky's chat uses Gemini (best for Tamil and tools); OpenRouter stays as a spare.
if [ -n "${TEXT_PROVIDER:-}" ]; then setenv "$ENVF" TEXT_PROVIDER "$TEXT_PROVIDER"
elif [ -n "$(getenv "$ENVF" GEMINI_API_KEY)" ]; then setenv "$ENVF" TEXT_PROVIDER gemini
fi

[ "$ENV_ONLY" = "--env-only" ] && { say "Settings written (no containers started)"; exit 0; }

cd "$DIR/server"
# Another web server on ports 80/443 (nginx, apache, another app) stops Caddy from starting: say which one.
busy=""
for p in 80 443; do
  who="$($SUDO ss -ltnpH "( sport = :$p )" 2>/dev/null | grep -v docker-proxy | sed -n 's/.*users:(("\([^"]*\)".*/\1/p' | head -1)"
  [ -n "$who" ] && busy="$busy port $p is used by '$who';"
done
if [ -n "$busy" ]; then
  warn "Another program is already using the web ports:$busy"
  warn "Follower needs ports 80 and 443. If that program is not needed, stop it, for example:"
  warn "  sudo systemctl disable --now nginx     (or apache2)"
  warn "then run this script again. If you need it, ask for the 'behind nginx' setup instead."
  exit 1
fi

say "Building and starting (first time takes a few minutes)"
$SUDO docker compose up -d --build

say "Waiting for https://$DOMAIN_NOW to answer"
ok=""
for i in $(seq 1 60); do
  if out="$(curl -fsS --max-time 8 "https://$DOMAIN_NOW/healthz" 2>/dev/null)"; then ok="$out"; break; fi
  sleep 5
done
if [ -n "$ok" ]; then
  echo "  $ok"
  say "Done. In the Follower app: More > Follower account > Server address: https://$DOMAIN_NOW > Create account"
else
  warn "The server did not answer over HTTPS yet. Most often ports 80/443 are still closed in Oracle Cloud:"
  warn "Networking > Virtual cloud networks > your VCN > Security Lists > Default > Add Ingress Rules:"
  warn "  Source 0.0.0.0/0, TCP, destination port 80; and again for 443. Then run this script again."
  warn "Logs: cd $DIR/server && sudo docker compose logs --tail 50"
  exit 1
fi
