import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { createPool } from '../src/db.js'
import { migrate } from '../src/migrate.js'
import { buildApp } from '../src/app.js'
import { loadConfig } from '../src/config.js'
import { sha256 as sha } from '../src/auth.js'

// Runs against a real PostgreSQL (TEST_DATABASE_URL) and a fake Google.
const url = process.env.TEST_DATABASE_URL ?? 'postgres://postgres@localhost:55432/aistock_test?host=/srv/pgtest'
let pool, app
const sentMail = []
const google = { calls: [], tokenFails: false, altRejected: false, ttsVoiceMissing: false, sttDown: false, geminiDown: false, rzpStatus: 'created', rzpCalls: [] }

function fakeFetch(u, init) {
  google.calls.push({ url: String(u), body: init?.body ? JSON.parse(init.body) : null, headers: init?.headers })
  const json = (status, body) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }))
  if (String(u).endsWith('/auth_tokens')) {
    if (google.tokenFails) return json(403, { error: { message: 'API key not valid' } })
    return json(200, { name: 'auth_tokens/abc123', expireTime: new Date(Date.now() + 600000).toISOString() })
  }
  if (String(u).includes('speech:recognize')) {
    const b = JSON.parse(init.body)
    if (google.sttDown) return json(500, { error: { message: 'backend error' } })
    if (google.altRejected && b.config.alternativeLanguageCodes) return json(400, { error: { message: 'alternative_language_codes not supported for this model' } })
    return json(200, { results: [{ alternatives: [{ transcript: 'Raju-ku rendu XL shirt', confidence: 0.9 }], languageCode: 'ta-in' }, { alternatives: [{ transcript: 'cash', confidence: 0.7 }] }] })
  }
  if (String(u).includes('text:synthesize')) {
    const b = JSON.parse(init.body)
    if (google.ttsVoiceMissing && b.voice.name) return json(400, { error: { message: 'voice does not exist' } })
    return json(200, { audioContent: Buffer.from('RIFF....WAVEfmt ').toString('base64') })
  }
  if (String(u).startsWith('https://rzp.test')) {
    google.rzpCalls.push({ url: String(u), body: init?.body ? JSON.parse(init.body) : null, auth: init?.headers?.authorization })
    if (init?.method === 'POST') return json(200, { id: 'plink_test1', short_url: 'https://rzp.io/i/test1', status: 'created' })
    return json(200, { id: 'plink_test1', status: google.rzpStatus, amount_paid: google.rzpStatus === 'paid' ? 49900 : 0 })
  }
  if (String(u).includes(':generateContent')) {
    if (google.geminiDown) return json(429, { error: { message: 'Resource has been exhausted' } })
    return json(200, { candidates: [{ content: { role: 'model', parts: [{ text: 'Innikku 3 sales.' }] }, finishReason: 'STOP' }], usageMetadata: { promptTokenCount: 120, candidatesTokenCount: 8 } })
  }
  return json(404, { error: { message: 'unknown' } })
}

before(async () => {
  pool = createPool(url)
  await pool.query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;')
  await migrate(pool)
  const cfg = loadConfig({ DATABASE_URL: url, JWT_SECRET: 'x'.repeat(40), GEMINI_API_KEY: 'server-key', ADMIN_TOKEN: 'admin-secret-token', GEMINI_BASE_URL: 'https://google.test', GOOGLE_CLOUD_API_KEY: 'cloud-key', GOOGLE_STT_BASE_URL: 'https://stt.test', GOOGLE_TTS_BASE_URL: 'https://tts.test', REGISTER_PER_HOUR: '1000', LIVE_MODE: 'token', UPI_ID: 'shop@okaxis', UPI_NAME: 'Follower', RAZORPAY_KEY_ID: 'rzp_test_key', RAZORPAY_KEY_SECRET: 'rzp-secret', RAZORPAY_BASE_URL: 'https://rzp.test' })
  app = await buildApp({ cfg, pool, fetchImpl: fakeFetch, mailTransport: { sendMail: async (m) => { sentMail.push(m) } } })
})
after(async () => { await app?.close(); await pool?.end() })

const call = async (method, path, { token, body, headers = {} } = {}) => {
  const r = await app.inject({ method, url: path, headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...headers }, ...(body !== undefined ? (Buffer.isBuffer(body) ? { payload: body } : { payload: body }) : {}) })
  let json = null; try { json = r.json() } catch {}
  return { status: r.statusCode, json, raw: r.rawPayload, headers: r.headers }
}
async function register(email, businessName = 'Kumar Fancy Store') {
  const r = await call('POST', '/v1/auth/register', { body: { email, password: 'correct horse 1', name: 'Kumar', businessName } })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  return r.json
}

test('register, sign in, refresh rotates, me shows trial', async () => {
  const a = await register('owner1@shop.in')
  assert.ok(a.accessToken && a.refreshToken && a.businessId)
  assert.equal((await call('POST', '/v1/auth/register', { body: { email: 'owner1@shop.in', password: 'another pass', businessName: 'X' } })).status, 409)
  assert.equal((await call('POST', '/v1/auth/login', { body: { email: 'owner1@shop.in', password: 'wrong password' } })).status, 401)
  const l = await call('POST', '/v1/auth/login', { body: { email: 'OWNER1@shop.in', password: 'correct horse 1' } })
  assert.equal(l.status, 200)
  const r1 = await call('POST', '/v1/auth/refresh', { body: { refreshToken: l.json.refreshToken } })
  assert.equal(r1.status, 200)
  await pool.query("UPDATE refresh_tokens SET revoked_at = now() - interval '5 minutes' WHERE token_hash = $1", [sha(l.json.refreshToken)])
  assert.equal((await call('POST', '/v1/auth/refresh', { body: { refreshToken: l.json.refreshToken } })).status, 401, 'a used refresh token must not work twice')
  const again = await call('POST', '/v1/auth/login', { body: { email: 'owner1@shop.in', password: 'correct horse 1' } })
  const r1b = await call('POST', '/v1/auth/refresh', { body: { refreshToken: again.json.refreshToken } })
  const me = await call('GET', '/v1/me', { token: r1b.json.accessToken })
  assert.equal(me.status, 200)
  assert.equal(me.json.businesses[0].entitlement.status, 'trial')
  assert.equal(me.json.businesses[0].role, 'owner')
  assert.equal((await call('GET', '/v1/me')).status, 401)
  assert.equal((await call('GET', '/v1/me', { token: 'garbage' })).status, 401)
})

test('one business can never see another business', async () => {
  const a = await register('a@iso.in', 'Shop A')
  const b = await register('b@iso.in', 'Shop B')
  const cfg = { schemaVersion: 1, businessType: 'retail', modules: ['inventory', 'sales'] }
  assert.equal((await call('PUT', `/v1/businesses/${a.businessId}/profile`, { token: a.accessToken, body: { config: cfg } })).status, 200)
  await call('POST', `/v1/businesses/${a.businessId}/backups`, { token: a.accessToken, body: Buffer.from('secret-a'), headers: { 'content-type': 'application/octet-stream' } })
  for (const [m, p] of [['GET', 'profile'], ['GET', 'backups'], ['GET', 'usage'], ['GET', 'members'], ['POST', 'ai/generate'], ['POST', 'ai/live-session']]) {
    const r = await call(m, `/v1/businesses/${a.businessId}/${p}`, { token: b.accessToken, body: m === 'POST' ? {} : undefined })
    assert.equal(r.status, 404, `${m} ${p} must look like it does not exist`)
  }
  assert.equal((await call('PUT', `/v1/businesses/${a.businessId}/profile`, { token: b.accessToken, body: { config: cfg } })).status, 404)
  assert.equal((await call('GET', '/v1/businesses/not-a-uuid/profile', { token: b.accessToken })).status, 404)
})

test('business configuration is validated and versioned', async () => {
  const a = await register('cfg@shop.in')
  const put = (config, baseVersion) => call('PUT', `/v1/businesses/${a.businessId}/profile`, { token: a.accessToken, body: { config, baseVersion } })
  assert.equal((await put({ schemaVersion: 1, businessType: 'retail', modules: ['inventory', 'drop_tables'] })).status, 400)
  assert.equal((await put({ schemaVersion: 1, businessType: 'spaceship', modules: ['inventory'] })).status, 400)
  assert.equal((await put({ schemaVersion: 1, businessType: 'garment_manufacturing', modules: ['production'], customFields: [{ module: 'production', key: 'Bad Key', label: 'x', type: 'text' }] })).status, 400)
  const good = { schemaVersion: 1, businessType: 'garment_manufacturing', modules: ['inventory', 'production', 'tasks'],
    workflows: { production: { stages: ['Cutting', 'Stitching', 'Finishing', 'Quality check', 'Packed'] } },
    customFields: [{ module: 'production', key: 'buyer_po', label: 'Buyer PO', type: 'text' }], terminology: { work_item: 'Production order' } }
  const r1 = await put(good, 0)
  assert.equal(r1.json.version, 1)
  assert.equal((await put(good, 0)).status, 409, 'stale edit must be refused')
  assert.equal((await put(good, 1)).json.version, 2)
  const g = await call('GET', `/v1/businesses/${a.businessId}/profile`, { token: a.accessToken })
  assert.equal(g.json.version, 2)
  assert.deepEqual(g.json.config.workflows.production.stages[0], 'Cutting')
})

test('roles: staff cannot change settings or see backups', async () => {
  const owner = await register('boss@shop.in')
  const staff = await register('ravi@shop.in', 'Ravi own shop')
  assert.equal((await call('POST', `/v1/businesses/${owner.businessId}/members`, { token: owner.accessToken, body: { email: 'ravi@shop.in', role: 'staff' } })).status, 402, 'trial allows 1 person')
  await call('POST', '/admin/subscriptions', { headers: { authorization: 'Bearer admin-secret-token' }, body: { businessId: owner.businessId, planId: 'basic', days: 30 } })
  assert.equal((await call('POST', `/v1/businesses/${owner.businessId}/members`, { token: owner.accessToken, body: { email: 'ravi@shop.in', role: 'staff' } })).status, 200)
  assert.equal((await call('PUT', `/v1/businesses/${owner.businessId}/profile`, { token: staff.accessToken, body: { config: { schemaVersion: 1, businessType: 'retail', modules: ['sales'] } } })).status, 403)
  assert.equal((await call('GET', `/v1/businesses/${owner.businessId}/backups`, { token: staff.accessToken })).status, 403)
  assert.equal((await call('GET', `/v1/businesses/${owner.businessId}/usage`, { token: staff.accessToken })).status, 200)
})

test('AI proxy: key stays on the server, tools only, usage metered, limits enforced', async () => {
  const a = await register('ai@shop.in')
  google.calls.length = 0
  const request = {
    systemInstruction: { parts: [{ text: 'You are Stocky' }] },
    contents: [{ role: 'user', parts: [{ text: 'innikku sales evlo?' }] }],
    tools: [{ functionDeclarations: [{ name: 'get_sales', description: 'x' }] }, { googleSearch: {} }, { codeExecution: {} }],
    generationConfig: { temperature: 0.4, thinkingConfig: { thinkingBudget: 0 }, maxOutputTokens: 999999 },
    cachedContent: 'cachedContents/x',
  }
  const r = await call('POST', `/v1/businesses/${a.businessId}/ai/generate`, { token: a.accessToken, body: { request } })
  assert.equal(r.status, 200)
  assert.equal(r.json.candidates[0].content.parts[0].text, 'Innikku 3 sales.')
  const sent = google.calls.at(-1)
  assert.match(sent.url, /models\/gemini-flash-lite-latest:generateContent$/)
  assert.equal(sent.headers['x-goog-api-key'], 'server-key')
  assert.deepEqual(sent.body.tools, [{ functionDeclarations: [{ name: 'get_sales', description: 'x' }] }], 'search/code tools are stripped')
  assert.equal(sent.body.cachedContent, undefined)
  assert.equal(sent.body.generationConfig.maxOutputTokens, 4096)
  const u = await call('GET', `/v1/businesses/${a.businessId}/usage`, { token: a.accessToken })
  assert.equal(u.json.aiRequests.used, 1)
  // Use up the month.
  await pool.query("INSERT INTO usage_events (business_id, kind, quantity) VALUES ($1,'ai_request',299)", [a.businessId])
  assert.equal((await call('POST', `/v1/businesses/${a.businessId}/ai/generate`, { token: a.accessToken, body: { request } })).json.error, 'quota_exceeded')
  // An expired plan stops AI, not the account.
  await pool.query("UPDATE subscriptions SET current_period_end = now() - interval '1 day' WHERE business_id = $1", [a.businessId])
  assert.equal((await call('POST', `/v1/businesses/${a.businessId}/ai/generate`, { token: a.accessToken, body: { request } })).status, 402)
  assert.equal((await call('GET', '/v1/me', { token: a.accessToken })).status, 200)
  assert.equal((await call('POST', `/v1/businesses/${a.businessId}/ai/generate`, { token: a.accessToken, body: { request: { contents: [] } } })).status, 400)
})

test('live voice: short-lived token, usage capped by the clock, never counted twice', async () => {
  const a = await register('live@shop.in')
  google.calls.length = 0
  const r = await call('POST', `/v1/businesses/${a.businessId}/ai/live-session`, { token: a.accessToken })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.equal(r.json.token, 'auth_tokens/abc123')
  assert.match(r.json.wsUrl, /^wss:\/\//)
  assert.equal(r.json.model, 'gemini-3.8-live')
  const tokReq = google.calls.at(-1).body
  assert.equal(tokReq.uses, 1)
  assert.equal(tokReq.liveConnectConstraints.model, 'models/gemini-3.8-live')
  assert.ok(new Date(tokReq.newSessionExpireTime) - Date.now() < 120_000)
  // The app claims 5000 seconds right away: only the real elapsed time (+5 s) counts.
  const rep = await call('POST', `/v1/businesses/${a.businessId}/ai/live-usage`, { token: a.accessToken, body: { sessionId: r.json.sessionId, seconds: 5000 } })
  assert.ok(rep.json.recordedSeconds <= 6)
  await pool.query("UPDATE live_sessions SET started_at = now() - interval '200 seconds' WHERE id = $1", [r.json.sessionId])
  await call('POST', `/v1/businesses/${a.businessId}/ai/live-usage`, { token: a.accessToken, body: { sessionId: r.json.sessionId, seconds: 90, final: true } })
  await call('POST', `/v1/businesses/${a.businessId}/ai/live-usage`, { token: a.accessToken, body: { sessionId: r.json.sessionId, seconds: 90, final: true } })
  const u = await call('GET', `/v1/businesses/${a.businessId}/usage`, { token: a.accessToken })
  assert.equal(u.json.liveSeconds.used, 200, 'a closed session is charged at least the time it was open')
  // An open session counts at its full length until it is closed.
  const r2 = await call('POST', `/v1/businesses/${a.businessId}/ai/live-session`, { token: a.accessToken })
  const u2 = await call('GET', `/v1/businesses/${a.businessId}/usage`, { token: a.accessToken })
  assert.equal(u2.json.liveSeconds.used, 200 + r2.json.maxSeconds)
  await pool.query("UPDATE live_sessions SET max_seconds = 1700 WHERE id = $1", [r2.json.sessionId])
  assert.equal((await call('POST', `/v1/businesses/${a.businessId}/ai/live-session`, { token: a.accessToken })).json.error, 'quota_exceeded')
})

test('live voice: Google refusing the token is reported, not hidden', async () => {
  const a = await register('livefail@shop.in')
  google.tokenFails = true
  const r = await call('POST', `/v1/businesses/${a.businessId}/ai/live-session`, { token: a.accessToken })
  google.tokenFails = false
  assert.equal(r.status, 502)
  assert.match(r.json.message, /API key not valid/)
})

test('backups round-trip, only 10 kept, owner only', async () => {
  const a = await register('bk@shop.in')
  for (let i = 0; i < 12; i++) {
    const r = await call('POST', `/v1/businesses/${a.businessId}/backups`, { token: a.accessToken, body: Buffer.from(`backup-${i}`), headers: { 'content-type': 'application/octet-stream', 'x-backup-note': `n${i}` } })
    assert.equal(r.status, 200)
  }
  const list = await call('GET', `/v1/businesses/${a.businessId}/backups`, { token: a.accessToken })
  assert.equal(list.json.backups.length, 10)
  const newest = list.json.backups[0]
  const d = await call('GET', `/v1/businesses/${a.businessId}/backups/${newest.id}`, { token: a.accessToken })
  assert.equal(d.raw.toString(), 'backup-11')
})

test('admin activation needs the admin token; account deletion removes owned data', async () => {
  const a = await register('del@shop.in')
  assert.equal((await call('POST', '/admin/subscriptions', { headers: { authorization: 'Bearer nope' }, body: { businessId: a.businessId, planId: 'pro' } })).status, 401)
  const ok = await call('POST', '/admin/subscriptions', { headers: { authorization: 'Bearer admin-secret-token' }, body: { businessId: a.businessId, planId: 'pro', days: 30, reference: 'UPI 123' } })
  assert.equal(ok.status, 200)
  const exp = await call('GET', '/v1/me/export', { token: a.accessToken })
  assert.equal(exp.json.user.email, 'del@shop.in')
  assert.equal((await call('DELETE', '/v1/me', { token: a.accessToken, body: { password: 'wrong' } })).status, 401)
  assert.equal((await call('DELETE', '/v1/me', { token: a.accessToken, body: { password: 'correct horse 1' } })).status, 200)
  assert.equal(Number((await pool.query('SELECT COUNT(*) FROM businesses WHERE id = $1', [a.businessId])).rows[0].count), 0)
  assert.equal((await call('GET', '/v1/me', { token: a.accessToken })).status, 401)
})

test('health and plans are public', async () => {
  assert.equal((await call('GET', '/healthz')).json.ok, true)
  const p = await call('GET', '/v1/plans')
  assert.equal(p.json.plans.find(x => x.id === 'basic').price_minor, 19900)
})

test('parallel requests cannot slip past the monthly limit', async () => {
  const a = await register('race@shop.in')
  await pool.query("INSERT INTO usage_events (business_id, kind, quantity) VALUES ($1,'ai_request',298)", [a.businessId])
  const request = { contents: [{ role: 'user', parts: [{ text: 'hi' }] }] }
  const results = await Promise.all(Array.from({ length: 10 }, () => call('POST', `/v1/businesses/${a.businessId}/ai/generate`, { token: a.accessToken, body: { request } })))
  assert.equal(results.filter(r => r.status === 200).length, 2, results.map(r => r.status).join(','))
})

test('a refresh token works once even in parallel; a stolen old one signs the person out', async () => {
  const a = await register('rot@shop.in')
  const rs = await Promise.all(Array.from({ length: 4 }, () => call('POST', '/v1/auth/refresh', { body: { refreshToken: a.refreshToken } })))
  // One wins; the others are inside the 60-second grace for lost answers and get their own new session.
  assert.ok(rs.every(r => r.status === 200))
  const fresh = rs[0].json.refreshToken
  await pool.query("UPDATE refresh_tokens SET revoked_at = now() - interval '5 minutes' WHERE token_hash = $1", [sha(a.refreshToken)])
  assert.equal((await call('POST', '/v1/auth/refresh', { body: { refreshToken: a.refreshToken } })).status, 401)
  assert.equal((await call('POST', '/v1/auth/refresh', { body: { refreshToken: fresh } })).status, 401, 'reuse of an old token ends every session')
})

test('owners cannot be demoted by mistake; bad admin input is refused', async () => {
  const o = await register('own@shop.in')
  await call('POST', '/admin/subscriptions', { headers: { authorization: 'Bearer admin-secret-token' }, body: { businessId: o.businessId, planId: 'pro', days: 30 } })
  assert.equal((await call('POST', `/v1/businesses/${o.businessId}/members`, { token: o.accessToken, body: { email: 'own@shop.in', role: 'staff' } })).status, 400)
  assert.equal((await call('POST', '/admin/subscriptions', { headers: { authorization: 'Bearer admin-secret-token' }, body: { businessId: 'x', planId: 'pro' } })).status, 400)
  assert.equal((await call('POST', '/admin/subscriptions', { headers: { authorization: 'Bearer admin-secret-token' }, body: { businessId: o.businessId, planId: 'pro', days: -5 } })).status, 400)
})

test('Google Cloud speech: key stays on the server, phrases passed, minutes metered and refunded on failure', async () => {
  const a = await register('speech@shop.in', 'Speech Shop')
  const pcm = Buffer.alloc(32000 * 3) // three seconds of silence
  google.calls.length = 0
  google.altRejected = true
  let r = await call('POST', `/v1/businesses/${a.businessId}/ai/stt`, { token: a.accessToken, body: { audio: pcm.toString('base64'), language: 'ta-IN', phrases: ['Shirt XL', 'Raju', 42, 'Shirt XL'] } })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.equal(r.json.text, 'Raju-ku rendu XL shirt cash')
  assert.equal(r.json.seconds, 3)
  const stt = google.calls.filter(c => c.url.includes('speech:recognize'))
  assert.equal(stt.length, 2, 'retried without the second language')
  assert.equal(stt[0].headers['x-goog-api-key'], 'cloud-key')
  assert.equal(stt[0].body.config.languageCode, 'ta-IN')
  assert.deepEqual(stt[0].body.config.speechContexts[0].phrases, ['Shirt XL', 'Raju'])
  assert.equal(stt[1].body.config.alternativeLanguageCodes, undefined)
  google.altRejected = false

  google.ttsVoiceMissing = true
  r = await call('POST', `/v1/businesses/${a.businessId}/ai/tts`, { token: a.accessToken, body: { text: 'Raju-க்கு 2 XL shirt bill போட்டாச்சு.' } })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.equal(r.json.language, 'ta-IN')
  assert.ok(Buffer.from(r.json.audio, 'base64').toString().startsWith('RIFF'))
  const tts = google.calls.filter(c => c.url.includes('text:synthesize'))
  assert.equal(tts[0].body.voice.name, 'ta-IN-Chirp3-HD-Aoede')
  assert.equal(tts[1].body.voice.name, undefined, 'falls back to the default voice')
  google.ttsVoiceMissing = false
  r = await call('POST', `/v1/businesses/${a.businessId}/ai/tts`, { token: a.accessToken, body: { text: 'Shirt XL stock 10 irukku' } })
  assert.equal(r.json.language, 'en-IN')

  const used = Number((await pool.query("SELECT COALESCE(SUM(quantity),0) AS n FROM usage_events WHERE business_id = $1 AND kind IN ('stt_seconds','tts_seconds')", [a.businessId])).rows[0].n)
  assert.ok(used >= 5)
  const usage = await call('GET', `/v1/businesses/${a.businessId}/usage`, { token: a.accessToken })
  assert.ok(JSON.stringify(usage.json).includes(`"used":${used}`), 'speech counts against the voice minutes')

  // Google failing: Gemini hears instead, and the health check says Google is resting.
  google.sttDown = true
  r = await call('POST', `/v1/businesses/${a.businessId}/ai/stt`, { token: a.accessToken, body: { audio: pcm.toString('base64') } })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.equal(r.json.text, 'Innikku 3 sales.')
  assert.match(JSON.stringify((await call('GET', '/healthz')).json.speechResting), /google/)
  const afterFallback = Number((await pool.query("SELECT COALESCE(SUM(quantity),0) AS n FROM usage_events WHERE business_id = $1 AND kind IN ('stt_seconds','tts_seconds')", [a.businessId])).rows[0].n)
  // Every provider failing gives the time back.
  google.geminiDown = true
  r = await call('POST', `/v1/businesses/${a.businessId}/ai/stt`, { token: a.accessToken, body: { audio: pcm.toString('base64') } })
  assert.equal(r.status, 503, 'Google is resting, so Gemini alone was asked and is rate limited: busy, try again')
  google.sttDown = false; google.geminiDown = false
  const after = Number((await pool.query("SELECT COALESCE(SUM(quantity),0) AS n FROM usage_events WHERE business_id = $1 AND kind IN ('stt_seconds','tts_seconds')", [a.businessId])).rows[0].n)
  assert.equal(after, afterFallback)
  assert.equal(afterFallback, used + 3)

  // Bad input, too long, other business, no minutes left.
  assert.equal((await call('POST', `/v1/businesses/${a.businessId}/ai/stt`, { token: a.accessToken, body: { audio: 'not base64!' } })).status, 400)
  assert.equal((await call('POST', `/v1/businesses/${a.businessId}/ai/tts`, { token: a.accessToken, body: { text: 'x'.repeat(2001) } })).status, 413)
  const b = await register('other-speech@shop.in', 'Other')
  assert.equal((await call('POST', `/v1/businesses/${a.businessId}/ai/stt`, { token: b.accessToken, body: { audio: pcm.toString('base64') } })).status, 404)
  await pool.query("INSERT INTO usage_events (business_id, kind, quantity) VALUES ($1, 'stt_seconds', 1000000)", [a.businessId])
  assert.equal((await call('POST', `/v1/businesses/${a.businessId}/ai/stt`, { token: a.accessToken, body: { audio: pcm.toString('base64') } })).status, 429)
})

test('UPI payment: the owner sends the UTR, the administrator approves, the plan starts; a UTR works once', async () => {
  const a = await register('upi@shop.in', 'UPI Shop')
  const plans = await call('GET', '/v1/plans')
  assert.equal(plans.json.payment.upiId, 'shop@okaxis')
  assert.equal(plans.json.payment.trialDays, 14)
  // Trial ended: Stocky's AI stops until a plan is paid.
  await pool.query("UPDATE subscriptions SET current_period_end = now() - interval '1 day' WHERE business_id = $1", [a.businessId])
  let me = await call('GET', '/v1/me', { token: a.accessToken })
  assert.equal(me.json.businesses[0].entitlement.active, false)

  const path = `/v1/businesses/${a.businessId}/payments`
  assert.equal((await call('POST', path, { token: a.accessToken, body: { planId: 'basic', utr: '12' } })).status, 400, 'too short')
  assert.equal((await call('POST', path, { token: a.accessToken, body: { planId: 'trial', utr: '412345678901' } })).status, 400, 'trial cannot be bought')
  const claim = await call('POST', path, { token: a.accessToken, body: { planId: 'basic', utr: '4123 4567 8901' } })
  assert.equal(claim.status, 200, JSON.stringify(claim.json))
  assert.equal(claim.json.utr, '412345678901')
  assert.equal(claim.json.amountMinor, 19900)
  assert.equal((await call('POST', path, { token: a.accessToken, body: { planId: 'basic', utr: '412345678901' } })).status, 409, 'same UTR twice')
  const other = await register('upi-other@shop.in', 'Other UPI')
  assert.equal((await call('POST', path, { token: other.accessToken, body: { planId: 'basic', utr: '999999999999' } })).status, 404, 'not their business')
  me = await call('GET', '/v1/me', { token: a.accessToken })
  assert.equal(me.json.businesses[0].entitlement.pendingPayments, 1)

  const adminH = { authorization: 'Bearer admin-secret-token' }
  assert.equal((await call('GET', '/admin/payments')).status, 401)
  const list = await call('GET', '/admin/payments', { headers: adminH })
  const mine = list.json.payments.find(p => p.utr === '412345678901')
  assert.equal(mine.email, 'upi@shop.in')
  const ok = await call('POST', `/admin/payments/${mine.id}/approve`, { headers: adminH, body: {} })
  assert.equal(ok.status, 200, JSON.stringify(ok.json))
  assert.equal((await call('POST', `/admin/payments/${mine.id}/approve`, { headers: adminH, body: {} })).status, 409, 'not twice')
  me = await call('GET', '/v1/me', { token: a.accessToken })
  const e = me.json.businesses[0].entitlement
  assert.equal(e.active, true); assert.equal(e.planId, 'basic'); assert.equal(e.status, 'active'); assert.equal(e.pendingPayments, 0)
  const days = (new Date(e.periodEnd) - Date.now()) / 86400000
  assert.ok(days > 29 && days < 31, `about 30 days, got ${days}`)

  // Paying again before the month ends adds a month after the current one.
  const c2 = await call('POST', path, { token: a.accessToken, body: { planId: 'basic', utr: 'T2410290001' } })
  await call('POST', `/admin/payments/${c2.json.id}/approve`, { headers: adminH, body: {} })
  me = await call('GET', '/v1/me', { token: a.accessToken })
  const days2 = (new Date(me.json.businesses[0].entitlement.periodEnd) - Date.now()) / 86400000
  assert.ok(days2 > 59 && days2 < 61, `about 60 days, got ${days2}`)

  const c3 = await call('POST', path, { token: a.accessToken, body: { planId: 'pro', utr: 'FAKE123456' } })
  const rej = await call('POST', `/admin/payments/${c3.json.id}/reject`, { headers: adminH, body: { note: 'not received' } })
  assert.equal(rej.json.status, 'rejected')
  const mineList = await call('GET', path, { token: a.accessToken })
  assert.equal(mineList.json.payments.find(p => p.utr === 'FAKE123456').note, 'not received')
})

test('forgot password: emailed code sets a new password once, signs out other phones; admin can make a code', async () => {
  const a = await register('forgot@shop.in', 'Forgot Shop')
  sentMail.length = 0
  assert.deepEqual((await call('POST', '/v1/auth/forgot', { body: { email: 'nobody@shop.in' } })).json, { ok: true, emailed: true }, 'same answer for unknown emails')
  assert.equal(sentMail.length, 0)
  const r = await call('POST', '/v1/auth/forgot', { body: { email: 'Forgot@Shop.in' } })
  assert.equal(r.status, 200)
  assert.equal(sentMail.length, 1)
  assert.equal(sentMail[0].to, 'forgot@shop.in')
  const code = /\b(\d{6})\b/.exec(sentMail[0].text)[1]
  assert.equal((await call('POST', '/v1/auth/reset', { body: { email: 'forgot@shop.in', code: '000000', password: 'newpassword1' } })).status, 400)
  assert.equal((await call('POST', '/v1/auth/reset', { body: { email: 'forgot@shop.in', code, password: 'short' } })).status, 400)
  assert.equal((await call('POST', '/v1/auth/reset', { body: { email: 'forgot@shop.in', code, password: 'newpassword1' } })).status, 200)
  assert.equal((await call('POST', '/v1/auth/reset', { body: { email: 'forgot@shop.in', code, password: 'another-one1' } })).status, 400, 'a code works once')
  assert.equal((await call('POST', '/v1/auth/login', { body: { email: 'forgot@shop.in', password: 'correct horse 1' } })).status, 401, 'old password no longer works')
  assert.equal((await call('POST', '/v1/auth/login', { body: { email: 'forgot@shop.in', password: 'newpassword1' } })).status, 200)
  assert.equal((await call('POST', '/v1/auth/refresh', { body: { refreshToken: a.refreshToken } })).status, 401, 'other phones must sign in again')

  assert.equal((await call('POST', '/admin/password-code', { body: { email: 'forgot@shop.in' } })).status, 401)
  const byAdmin = await call('POST', '/admin/password-code', { headers: { authorization: 'Bearer admin-secret-token' }, body: { email: 'forgot@shop.in' } })
  assert.match(byAdmin.json.code, /^\d{6}$/)
  assert.equal((await call('POST', '/v1/auth/reset', { body: { email: 'forgot@shop.in', code: byAdmin.json.code, password: 'fromadmin123' } })).status, 200)

  // Five wrong tries and the code is dead even if guessed later.
  const c2 = (await call('POST', '/admin/password-code', { headers: { authorization: 'Bearer admin-secret-token' }, body: { email: 'forgot@shop.in' } })).json.code
  for (let i = 0; i < 5; i++) await call('POST', '/v1/auth/reset', { body: { email: 'forgot@shop.in', code: c2 === '111111' ? '222222' : '111111', password: 'guessing123' } })
  const last = (await call('POST', '/v1/auth/reset', { body: { email: 'forgot@shop.in', code: c2, password: 'guessing123' } })).status
  assert.ok(last === 400 || last === 429, `refused after too many tries, got ${last}`)
})

test('cards and net banking: a Razorpay page per plan; once paid the plan starts, only once; keys stay on the server', async () => {
  const a = await register('cards@shop.in', 'Card Shop')
  assert.equal((await call('GET', '/v1/plans')).json.payment.cards, true)
  const path = `/v1/businesses/${a.businessId}/payments/link`
  assert.equal((await call('POST', path, { token: a.accessToken, body: { planId: 'trial' } })).status, 400)
  const link = await call('POST', path, { token: a.accessToken, body: { planId: 'pro' } })
  assert.equal(link.status, 200, JSON.stringify(link.json))
  assert.equal(link.json.url, 'https://rzp.io/i/test1')
  const made = google.rzpCalls.find(c => c.body)
  assert.equal(made.body.amount, 49900); assert.equal(made.body.currency, 'INR'); assert.equal(made.body.customer.email, 'cards@shop.in')
  assert.equal(made.auth, 'Basic ' + Buffer.from('rzp_test_key:rzp-secret').toString('base64'))
  assert.ok(!JSON.stringify(link.json).includes('rzp-secret'))

  google.rzpStatus = 'created'
  let chk = await call('POST', `/v1/businesses/${a.businessId}/payments/check`, { token: a.accessToken })
  assert.equal(chk.json.paid, 0); assert.equal(chk.json.entitlement.planId, 'trial')
  google.rzpStatus = 'paid'
  // Paid in the browser: the next look by the app (here /v1/me) starts the plan.
  const me = await call('GET', '/v1/me', { token: a.accessToken })
  const e = me.json.businesses[0].entitlement
  assert.equal(e.planId, 'pro'); assert.equal(e.status, 'active')
  chk = await call('POST', `/v1/businesses/${a.businessId}/payments/check`, { token: a.accessToken })
  assert.equal(chk.json.paid, 0, 'not counted twice')
  const days = (new Date(chk.json.entitlement.periodEnd) - Date.now()) / 86400000
  assert.ok(days > 29 && days < 31, `one month, got ${days}`)
  google.rzpStatus = 'created'
})
