import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { WebSocketServer, WebSocket } from 'ws'
import { createPool } from '../src/db.js'
import { migrate } from '../src/migrate.js'
import { buildApp } from '../src/app.js'
import { loadConfig } from '../src/config.js'

// Voice through the server: a fake Google WebSocket upstream, a real server on a port, a ws client as the phone.
const url = process.env.TEST_DATABASE_URL ?? 'postgres://postgres@localhost:55432/aistock_test?host=/srv/pgtest'
let pool, app, google, base
const seen = { headers: null, setup: null }

before(async () => {
  pool = createPool(url)
  await migrate(pool)
  google = new WebSocketServer({ port: 0 })
  google.on('connection', (ws, req) => {
    seen.headers = req.headers
    ws.on('message', (m) => {
      const o = JSON.parse(m.toString())
      if (o.setup) { seen.setup = o.setup; ws.send(JSON.stringify({ setupComplete: {} })) }
      if (o.realtimeInput) ws.send(JSON.stringify({ serverContent: { outputTranscription: { text: 'Vanakkam' }, turnComplete: true } }))
    })
  })
  const cfg = loadConfig({ DATABASE_URL: url, JWT_SECRET: 'y'.repeat(40), GEMINI_API_KEY: 'server-key', REGISTER_PER_HOUR: '1000',
    GEMINI_LIVE_UPSTREAM_URL: `ws://127.0.0.1:${google.address().port}`, GEMINI_LIVE_MODEL: 'gemini-3.8-live', LIVE_MAX_SECONDS: '600' })
  app = await buildApp({ cfg, pool })
  await app.listen({ port: 0, host: '127.0.0.1' })
  base = `127.0.0.1:${app.server.address().port}`
})
after(async () => { await app?.close(); google?.close(); await pool?.end() })

async function register(email) {
  const r = await fetch(`http://${base}/v1/auth/register`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email, password: 'correct horse 1', businessName: 'Relay Shop' }) })
  return r.json()
}

function open(path, token) {
  return new Promise((resolve) => {
    const ws = new WebSocket(`ws://${base}${path}`, token ? { headers: { authorization: `Bearer ${token}` } } : {})
    const got = []
    ws.on('message', (m) => got.push(JSON.parse(m.toString())))
    ws.on('close', (code, reason) => resolve({ ws, got, code, reason: reason.toString() }))
    ws.on('open', () => resolve({ ws, got }))
  })
}

test('voice goes through the server with the server key and the server model, and minutes are measured here', async () => {
  const a = await register('relay@shop.in')
  const ticket = await (await fetch(`http://${base}/v1/businesses/${a.businessId}/ai/live-session`, { method: 'POST', headers: { authorization: `Bearer ${a.accessToken}` } })).json()
  assert.equal(ticket.mode, 'relay')
  assert.equal(ticket.token, '', 'no Google credential is handed out')
  assert.match(ticket.wsUrl, new RegExp(`^ws://${base}/v1/businesses/${a.businessId}/ai/live$`))
  const { ws, got } = await open(`/v1/businesses/${a.businessId}/ai/live`, a.accessToken)
  ws.send(JSON.stringify({ setup: { model: 'models/some-expensive-model', systemInstruction: { parts: [{ text: 'You are Stocky' }] } } }))
  await new Promise(r => setTimeout(r, 300))
  ws.send(JSON.stringify({ realtimeInput: { audio: { mimeType: 'audio/pcm;rate=16000', data: 'AAAA' } } }))
  await new Promise(r => setTimeout(r, 300))
  assert.equal(seen.headers['x-goog-api-key'], 'server-key')
  assert.equal(seen.setup.model, 'models/gemini-3.8-live', 'the server decides the model')
  assert.ok(got.some(m => m.setupComplete))
  assert.ok(got.some(m => m.serverContent?.outputTranscription?.text === 'Vanakkam'))
  const open1 = (await pool.query('SELECT closed FROM live_sessions WHERE business_id = $1', [a.businessId])).rows
  assert.deepEqual(open1.map(r => r.closed), [false])
  ws.close()
  await new Promise(r => setTimeout(r, 300))
  const row = (await pool.query('SELECT closed, reported_seconds FROM live_sessions WHERE business_id = $1', [a.businessId])).rows[0]
  assert.equal(row.closed, true)
  assert.ok(row.reported_seconds >= 1 && row.reported_seconds <= 3, String(row.reported_seconds))
})

test('voice is refused without a valid sign-in or for another business', async () => {
  const a = await register('relay2@shop.in')
  const b = await register('relay3@shop.in')
  const r1 = await open(`/v1/businesses/${a.businessId}/ai/live`, null)
  assert.equal(r1.code ?? (await new Promise(res => r1.ws.on('close', c => res(c)))), 4401)
  const r2 = await open(`/v1/businesses/${a.businessId}/ai/live`, b.accessToken)
  assert.equal(r2.code ?? (await new Promise(res => r2.ws.on('close', c => res(c)))), 4400)
})
