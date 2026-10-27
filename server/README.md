# AIStock server

The backend for AIStock Assistant. It keeps the Gemini key away from phones, holds owner accounts and businesses,
checks plans and usage limits, gives phones short-lived tokens for real-time voice, stores optional backups,
and keeps an audit log. Business records (stock, sales, customers) stay on each phone.

```
Phone ──HTTPS──> Caddy (TLS, ports 80/443) ──> API (Node 22, Fastify) ──> PostgreSQL 16
                                                 └──> Google Gemini (text replies; voice tokens)
Phone ──WSS (short-lived token)──> Google Gemini Live (real-time voice)
```

## What it does

| Endpoint | Purpose |
|---|---|
| `POST /v1/auth/register`, `/login`, `/refresh`, `/logout` | Accounts. Passwords bcrypt-hashed; 1-hour access tokens; refresh tokens work once (rotation). |
| `GET /v1/me`, `GET /v1/me/export`, `DELETE /v1/me` | Account info with plan and usage; download my data; delete account. |
| `GET/PUT /v1/businesses/:id/profile` | Business setup (type, modules, steps, custom fields). Validated and versioned; stale edits refused. |
| `GET/POST /v1/businesses/:id/members` | Add a manager or staff account (owner only, plan limit). |
| `POST /v1/businesses/:id/ai/generate` | Stocky's conversation. Only function tools pass through; tokens are counted. |
| `POST /v1/businesses/:id/ai/live-session` | A one-use Gemini Live token locked to the configured voice model. |
| `POST /v1/businesses/:id/ai/live-usage` | The phone reports voice seconds; capped by the real clock. |
| `GET /v1/businesses/:id/usage` | Plan, AI replies and voice minutes used this period. |
| `POST/GET /v1/businesses/:id/backups` | Backup copies (last 10 kept). |
| `POST /admin/subscriptions` | Activate a plan after a payment. Only reachable from the server itself (Caddy blocks `/admin` from outside). |

Every `/v1/businesses/:id/...` route checks that the signed-in person belongs to that business; another
business's id answers "not found". This is covered by `test/api.test.js`.

## Deploy on Oracle Cloud (Always Free works)

1. **Create the machine.** Compute → Instances → Create. Image: Ubuntu 22.04 or 24.04. Shape: Ampere A1 (ARM) 1 OCPU / 6 GB is plenty.
   Add your SSH key. Note the public IP, e.g. `140.238.1.2`.
2. **Open ports 80 and 443.** Networking → Virtual cloud networks → your VCN → Security Lists → Default → Add ingress rules:
   source `0.0.0.0/0`, TCP, destination ports `80` and `443`.
   Oracle's Ubuntu images also block ports in the machine's own firewall, so on the machine run:
   ```
   sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
   sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
   sudo netfilter-persistent save
   ```
3. **Install Docker.**
   ```
   curl -fsSL https://get.docker.com | sudo sh
   sudo usermod -aG docker $USER && newgrp docker
   ```
4. **Get the code and configure it.**
   ```
   sudo mkdir -p /opt/aistock && sudo chown $USER /opt/aistock
   git clone https://github.com/Night-Owl-26/aistock-assistant.git /opt/aistock
   cd /opt/aistock/server
   cp .env.example .env
   nano .env
   ```
   - `DOMAIN`: your domain, or with no domain `<your-ip>.sslip.io` (e.g. `140.238.1.2.sslip.io`), which points at your IP and gets a real HTTPS certificate.
   - `POSTGRES_PASSWORD`, `JWT_SECRET`, `ADMIN_TOKEN`: long random values — make each with `openssl rand -hex 32`.
   - `GEMINI_API_KEY`: your key from https://aistudio.google.com/apikey. Use a new key, not one that was ever shared in a chat.
   - `SARVAM_API_KEY` (optional, recommended for Tamil voice): from https://dashboard.sarvam.ai → API keys. Sarvam AI then turns the owner's speech into text (model `saaras:v4`, Tamil with English words kept in English). It is billed by Sarvam per minute of audio (new accounts get free credit). Replies are still read by Google Cloud Text-to-Speech when `GOOGLE_CLOUD_API_KEY` is set, otherwise by the phone.
   - `GOOGLE_CLOUD_API_KEY` (optional): a Google Cloud API key for **Cloud Speech-to-Text** and **Cloud Text-to-Speech**. In console.cloud.google.com: pick or create a project with billing, enable both APIs, create an API key under *APIs & Services > Credentials*, and restrict it to those two APIs. Without it the app uses the phone's own speech and Gemini Live. Speech time counts against the plan's voice minutes.
5. **Start it.**
   ```
   docker compose up -d --build
   curl https://$DOMAIN/healthz      # {"ok":true,"gemini":true,...}
   ```
6. **In the app:** More → AIStock account → server address `https://<DOMAIN>` → Create account.
   New accounts get a 14-day trial (change `TRIAL_DAYS`).

## Running it day to day

```
./scripts.sh find owner@email.com                          # business id, plan, status
./scripts.sh activate <businessId> basic 30 "UPI ref 123"  # after payment: plan for 30 days
./scripts.sh usage                                          # AI replies, tokens, voice minutes this month
./scripts.sh backup                                         # database dump to backups/ (keeps 14 days)
./scripts.sh restore backups/aistock-2026-10-17.dump
docker compose logs -f api
git pull && docker compose up -d --build                    # update
```
Add the nightly backup to cron: `crontab -e` → `0 2 * * * cd /opt/aistock/server && ./scripts.sh backup`.
Copy `backups/` off the machine regularly (e.g. to OCI Object Storage).

## Plans and costs

Plans are rows in the `plans` table (price in paise, AI replies per month, voice seconds per month, staff),
seeded as trial (free), basic (₹199) and pro (₹499). Change them with SQL; no app release is needed:
```
docker compose exec db psql -U aistock -d aistock -c "UPDATE plans SET live_seconds_month = 5400 WHERE id = 'basic'"
```
Whether ₹199 covers its cost depends on real use. Measure it before fixing prices:
1. Run `./scripts.sh usage` at month end: tokens in/out per business and voice minutes.
2. Multiply by Google's current prices (https://ai.google.dev/gemini-api/docs/pricing) for the models in `.env`.
   Real-time voice is billed per audio token and costs much more per minute than text.
3. Add the server (Always Free = ₹0, otherwise the instance price), Google Play's fee if you sell through Play,
   GST, payment-gateway fees and refunds.
4. Set `live_seconds_month` so a typical user stays inside the price with margin.

Google Play billing is **not connected yet** (`/v1/billing/google-play/verify` answers 501). Until it is, plans are
activated by hand with `./scripts.sh activate` after a UPI/bank payment.

## Security notes

- The Gemini key is only in `.env` on the server. Phones get one-use voice tokens (valid ≤ ~12 minutes) and
  never the key. Text requests go through the server, which strips search/code tools and caps output length.
- Refresh tokens are stored hashed; access tokens are short-lived JWTs (HS256, `JWT_SECRET`).
- Rate limits: 120 requests/minute per user, 20 sign-ins per 15 minutes per IP, `REGISTER_PER_HOUR` sign-ups per IP.
- Set `ALLOW_REGISTRATION=false` to stop new sign-ups once your users are in.
- Backups uploaded from phones are stored as sent (inside HTTPS, on your server). Anyone with database access can read
  them, so protect the machine and its SSH key.
- Voice minutes reported by a phone are capped by the real elapsed time, and a session that never reports is
  charged for the time its token could run, so a modified app cannot get unlimited free minutes.

## Development

```
npm ci
TEST_DATABASE_URL=postgres://postgres:postgres@localhost:5432/aistock_test npm test
```
The tests run against a real PostgreSQL and a fake Google. GitHub Actions also builds the Docker stack and checks
`/healthz`, registration and that `/admin` is blocked from outside (job `server`).
