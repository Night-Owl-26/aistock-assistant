import Fastify from 'fastify'
import rateLimit from '@fastify/rate-limit'
import websocket from '@fastify/websocket'
import { registerLiveRelay } from './liveRelay.js'
import { checkPassword, hashPassword, randomToken, sameSecret, sha256, tokens } from './auth.js'
import { tx } from './db.js'
import { geminiApi, sanitizeRequest } from './gemini.js'
import { openRouterApi } from './openrouter.js'
import { SPEECH_LANGS, speechApi, speechLanguage, ttsSeconds } from './speech.js'
import { mailer } from './mail.js'
import { randomInt } from 'node:crypto'
import { validateConfig } from './businessConfig.js'

const EMAIL = /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,24}$/

class HttpError extends Error {
  constructor(status, code, message) { super(message ?? code); this.status = status; this.code = code }
}
const fail = (status, code, message) => { throw new HttpError(status, code, message) }

/**
 * Builds the API. Every business route goes through member(): the caller must belong to that business,
 * and the role must be allowed. Data of one business is never readable with another business's id.
 */
export async function buildApp({ cfg, pool, fetchImpl = fetch, logger = false, WebSocketImpl, mailTransport = null }) {
  const app = Fastify({ logger, bodyLimit: 1024 * 1024, trustProxy: true })
  const jwt = tokens(cfg)
  const gemini = geminiApi(cfg, fetchImpl)
  const speech = speechApi(cfg, fetchImpl)
  const openRouter = openRouterApi(cfg, fetchImpl)
  // Which service answers Stocky's typed conversation.
  const textProvider = cfg.textProvider === 'gemini' ? 'gemini'
    : cfg.textProvider === 'openrouter' ? 'openrouter'
    : (openRouter.configured() ? 'openrouter' : 'gemini')
  const textApi = textProvider === 'openrouter' ? openRouter : gemini
  const textModel = textProvider === 'openrouter' ? cfg.openRouterModel : cfg.textModel

  // Signed-in requests are limited per person (many shops can share one mobile-network address); others per address.
  await app.register(rateLimit, {
    max: 120, timeWindow: '1 minute',
    keyGenerator: async (req) => {
      const h = req.headers.authorization ?? ''
      if (h.startsWith('Bearer ')) { try { return 'u:' + await jwt.verify(h.slice(7)) } catch { /* fall through */ } }
      return 'ip:' + req.ip
    },
  })

  await app.register(websocket, { options: { maxPayload: 1024 * 1024 } })

  app.addContentTypeParser('application/octet-stream', { parseAs: 'buffer', bodyLimit: cfg.maxBackupBytes }, (req, body, done) => done(null, body))

  app.setErrorHandler((err, req, reply) => {
    if (err instanceof HttpError) return reply.code(err.status).send({ error: err.code, message: err.message })
    if (err.statusCode && err.statusCode < 500) return reply.code(err.statusCode).send({ error: 'bad_request', message: err.message })
    req.log?.error?.(err)
    return reply.code(500).send({ error: 'server_error', message: 'Something went wrong on the server.' })
  })

  const audit = (businessId, userId, action, detail, ip) =>
    pool.query('INSERT INTO audit_log (business_id, user_id, action, detail, ip) VALUES ($1,$2,$3,$4,$5)', [businessId, userId, action, detail ?? {}, ip ?? null]).catch(() => {})

  // ---------------------------------------------------------------- auth helpers

  async function authUser(req) {
    const h = req.headers.authorization ?? ''
    if (!h.startsWith('Bearer ')) fail(401, 'unauthorized', 'Sign in first.')
    let userId
    try { userId = await jwt.verify(h.slice(7)) } catch { fail(401, 'unauthorized', 'Your session has expired. Sign in again.') }
    const u = (await pool.query('SELECT id, email, name, disabled FROM users WHERE id = $1', [userId])).rows[0]
    if (!u || u.disabled) fail(401, 'unauthorized', 'Account not found.')
    req.userId = u.id
    return u
  }

  const ROLE_RANK = { staff: 1, manager: 2, owner: 3 }
  async function member(req, businessId, minRole = 'staff') {
    const u = await authUser(req)
    if (!/^[0-9a-f-]{36}$/i.test(String(businessId))) fail(404, 'not_found', 'Business not found.')
    const m = (await pool.query(
      'SELECT m.role FROM memberships m JOIN businesses b ON b.id = m.business_id WHERE m.user_id = $1 AND m.business_id = $2 AND b.deleted_at IS NULL',
      [u.id, businessId])).rows[0]
    // Not a member looks exactly like "does not exist", so ids of other businesses reveal nothing.
    if (!m) fail(404, 'not_found', 'Business not found.')
    if (ROLE_RANK[m.role] < ROLE_RANK[minRole]) fail(403, 'forbidden', 'Your role cannot do this.')
    return { user: u, role: m.role }
  }

  async function issueSession(userId) {
    const refresh = randomToken()
    await pool.query('INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES ($1,$2, now() + make_interval(days => $3))',
      [userId, sha256(refresh), cfg.refreshTtlDays])
    return { accessToken: await jwt.access(userId), refreshToken: refresh, expiresIn: cfg.accessTtlSeconds }
  }

  // ---------------------------------------------------------------- entitlements and usage

  async function entitlement(businessId, db = pool) {
    const s = (await db.query(
      `SELECT s.*, p.name AS plan_name, p.price_minor, p.currency, p.ai_requests_month, p.live_seconds_month, p.max_staff
         FROM subscriptions s JOIN plans p ON p.id = s.plan_id WHERE s.business_id = $1`, [businessId])).rows[0]
    if (!s) return { active: false, reason: 'no_subscription' }
    const now = new Date()
    const active = ['trial', 'active'].includes(s.status) && new Date(s.current_period_end) > now
    const from = s.current_period_start
    const ai = Number((await db.query(
      "SELECT COALESCE(SUM(quantity),0) AS n FROM usage_events WHERE business_id = $1 AND kind = 'ai_request' AND created_at >= $2", [businessId, from])).rows[0].n)
    // Voice time: what the app reported, or, for a session that never reported, the time it could have run.
    const live = Number((await db.query(
      // An open session counts at its full allowance until it is closed; a closed one at what was reported,
      // but never less than the time it was actually open.
      `SELECT COALESCE(SUM(CASE WHEN closed AND closed_at IS NOT NULL
                                THEN LEAST(max_seconds, GREATEST(reported_seconds, EXTRACT(EPOCH FROM (closed_at - started_at))::int))
                                WHEN expires_at <= now() THEN GREATEST(reported_seconds, LEAST(max_seconds, EXTRACT(EPOCH FROM (expires_at - started_at))::int))
                                ELSE max_seconds END),0) AS n
         FROM live_sessions WHERE business_id = $1 AND expires_at > $2`, [businessId, from])).rows[0].n)
      // Google Cloud speech (recognised seconds and spoken seconds) shares the same voice minutes.
      + Number((await db.query(
        "SELECT COALESCE(SUM(quantity),0) AS n FROM usage_events WHERE business_id = $1 AND kind IN ('stt_seconds','tts_seconds') AND created_at >= $2", [businessId, from])).rows[0].n)
    return {
      active, status: s.status, planId: s.plan_id, planName: s.plan_name, priceMinor: s.price_minor, currency: s.currency,
      periodEnd: s.current_period_end, maxStaff: s.max_staff,
      pendingPayments: Number((await db.query("SELECT COUNT(*) AS n FROM payment_claims WHERE business_id = $1 AND status = 'pending'", [businessId])).rows[0].n),
      aiRequests: { used: ai, limit: s.ai_requests_month },
      liveSeconds: { used: live, limit: s.live_seconds_month },
    }
  }

  function requireActive(e) {
    if (!e.active) fail(402, 'subscription_inactive', 'Your Follower plan has ended. Renew it to keep using Stocky AI.')
  }

  // ---------------------------------------------------------------- public

  app.get('/healthz', async () => {
    await pool.query('SELECT 1')
    return { ok: true, gemini: gemini.configured(), textProvider, textReady: textApi.configured(), speech: speech.sttConfigured(), speechToText: speech.sttProvider(), textToSpeech: speech.ttsProvider(), speechResting: speech.resting(), textModel, liveModel: cfg.liveModel }
  })

  app.get('/v1/plans', async () => ({
    plans: (await pool.query('SELECT id, name, price_minor, currency, period_days, ai_requests_month, live_seconds_month, max_staff FROM plans WHERE active ORDER BY price_minor')).rows,
    payment: { upiId: cfg.upiId, payeeName: cfg.upiName, supportPhone: cfg.supportPhone, trialDays: cfg.trialDays, cards: rzpReady() },
  }))

  app.post('/v1/auth/register', { config: { rateLimit: { max: cfg.registerPerHour, timeWindow: '1 hour' } } }, async (req) => {
    if (!cfg.allowRegistration) fail(403, 'registration_closed', 'New accounts are not open on this server.')
    const { email, password, name = '', businessName } = req.body ?? {}
    const em = String(email ?? '').trim().toLowerCase()
    if (!EMAIL.test(em)) fail(400, 'bad_email', 'Enter a valid email address.')
    if (typeof password !== 'string' || password.length < 8 || password.length > 200) fail(400, 'weak_password', 'Use a password of at least 8 characters.')
    const bn = String(businessName ?? '').trim()
    if (bn.length < 1 || bn.length > 80) fail(400, 'bad_business_name', 'Enter the business name.')
    const hash = await hashPassword(password)
    const out = await tx(pool, async (c) => {
      const exists = (await c.query('SELECT 1 FROM users WHERE email = $1', [em])).rowCount
      if (exists) fail(409, 'email_taken', 'An account with this email already exists. Sign in instead.')
      const u = (await c.query('INSERT INTO users (email, password_hash, name) VALUES ($1,$2,$3) RETURNING id', [em, hash, String(name).slice(0, 80)])).rows[0]
      const b = (await c.query('INSERT INTO businesses (name) VALUES ($1) RETURNING id', [bn])).rows[0]
      await c.query("INSERT INTO memberships (user_id, business_id, role) VALUES ($1,$2,'owner')", [u.id, b.id])
      await c.query("INSERT INTO subscriptions (business_id, plan_id, status, current_period_end, source) VALUES ($1,'trial','trial', now() + make_interval(days => $2), 'trial')", [b.id, cfg.trialDays])
      return { userId: u.id, businessId: b.id }
    })
    audit(out.businessId, out.userId, 'register', {}, req.ip)
    return { ...(await issueSession(out.userId)), businessId: out.businessId }
  })

  app.post('/v1/auth/login', { config: { rateLimit: { max: 20, timeWindow: '15 minutes' } } }, async (req) => {
    const em = String(req.body?.email ?? '').trim().toLowerCase()
    const u = (await pool.query('SELECT id, password_hash, disabled FROM users WHERE email = $1', [em])).rows[0]
    const ok = u && !u.disabled && await checkPassword(String(req.body?.password ?? ''), u.password_hash)
    if (!ok) { audit(null, u?.id ?? null, 'login_failed', { email: em.slice(0, 3) + '…' }, req.ip); fail(401, 'bad_credentials', 'Email or password is wrong.') }
    audit(null, u.id, 'login', {}, req.ip)
    return issueSession(u.id)
  })

  // ---------------------------------------------------------------- forgot password

  const mail = mailer(cfg, mailTransport)

  /** A new 6-digit code for this person, valid 15 minutes; earlier unused codes stop working. */
  async function newResetCode(userId) {
    const code = String(randomInt(0, 1_000_000)).padStart(6, '0')
    await pool.query('UPDATE password_resets SET used_at = now() WHERE user_id = $1 AND used_at IS NULL', [userId])
    await pool.query("INSERT INTO password_resets (user_id, code_hash, expires_at) VALUES ($1,$2, now() + interval '15 minutes')", [userId, sha256(code)])
    return code
  }

  /** Sends a reset code by email. The answer is the same whether or not the email has an account. */
  app.post('/v1/auth/forgot', { config: { rateLimit: { max: 5, timeWindow: '1 hour' } } }, async (req) => {
    const em = String(req.body?.email ?? '').trim().toLowerCase()
    if (!EMAIL.test(em)) fail(400, 'bad_email', 'Enter a valid email address.')
    const u = (await pool.query('SELECT id FROM users WHERE email = $1 AND NOT disabled', [em])).rows[0]
    if (u && mail.configured()) {
      const code = await newResetCode(u.id)
      try {
        await mail.send(em, 'Your Follower password reset code', `Your code is ${code}\n\nType it in the Follower app within 15 minutes to choose a new password. If you did not ask for this, ignore this email.`)
      } catch (err) { req.log?.warn?.({ err: String(err) }, 'reset email failed') }
      audit(null, u.id, 'password_reset_requested', {}, req.ip)
    }
    return { ok: true, emailed: mail.configured() }
  })

  /** Sets a new password with a valid code; every signed-in phone of that person has to sign in again. */
  app.post('/v1/auth/reset', { config: { rateLimit: { max: 10, timeWindow: '15 minutes' } } }, async (req) => {
    const em = String(req.body?.email ?? '').trim().toLowerCase()
    const code = String(req.body?.code ?? '').replace(/\s+/g, '')
    const password = req.body?.password
    if (typeof password !== 'string' || password.length < 8 || password.length > 200) fail(400, 'weak_password', 'Use a password of at least 8 characters.')
    const u = (await pool.query('SELECT id FROM users WHERE email = $1 AND NOT disabled', [em])).rows[0]
    const r = u && (await pool.query('SELECT * FROM password_resets WHERE user_id = $1 AND used_at IS NULL AND expires_at > now() ORDER BY created_at DESC LIMIT 1', [u.id])).rows[0]
    if (!r || r.attempts >= 5) fail(400, 'bad_code', 'The code is wrong or has expired. Ask for a new one.')
    if (!/^\d{6}$/.test(code) || !sameSecret(sha256(code), r.code_hash)) {
      await pool.query('UPDATE password_resets SET attempts = attempts + 1 WHERE id = $1', [r.id])
      fail(400, 'bad_code', 'The code is wrong or has expired. Ask for a new one.')
    }
    const hash = await hashPassword(password)
    await pool.query('UPDATE password_resets SET used_at = now() WHERE id = $1', [r.id])
    await pool.query('UPDATE users SET password_hash = $2 WHERE id = $1', [u.id, hash])
    // Back-dated, so the 60-second grace for parallel refreshes does not apply.
    await pool.query("UPDATE refresh_tokens SET revoked_at = now() - interval '1 hour' WHERE user_id = $1 AND revoked_at IS NULL", [u.id])
    audit(null, u.id, 'password_reset', {}, req.ip)
    return { ok: true }
  })

  app.post('/v1/auth/refresh', async (req) => {
    const hash = sha256(String(req.body?.refreshToken ?? ''))
    // Each refresh token works once, claimed in one statement so two parallel refreshes cannot both win.
    const row = (await pool.query(
      'UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = $1 AND revoked_at IS NULL AND expires_at > now() RETURNING user_id', [hash])).rows[0]
    if (row) return issueSession(row.user_id)
    const used = (await pool.query('SELECT user_id, revoked_at > now() - interval \'60 seconds\' AS recent FROM refresh_tokens WHERE token_hash = $1 AND expires_at > now()', [hash])).rows[0]
    // Used a moment ago: the answer was probably lost on a bad network, so the phone may try once more.
    if (used?.recent) return issueSession(used.user_id)
    // An old used token coming back means it was copied: sign that person out everywhere.
    // (Dated back an hour so the short grace above cannot bring them back.)
    if (used) { await pool.query("UPDATE refresh_tokens SET revoked_at = now() - interval '1 hour' WHERE user_id = $1 AND (revoked_at IS NULL OR revoked_at > now() - interval '60 seconds')", [used.user_id]); audit(null, used.user_id, 'refresh_reuse', {}, req.ip) }
    fail(401, 'unauthorized', 'Sign in again.')
  })

  app.post('/v1/auth/logout', async (req) => {
    const t = String(req.body?.refreshToken ?? '')
    await pool.query('UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = $1', [sha256(t)])
    return { ok: true }
  })

  // ---------------------------------------------------------------- account

  app.get('/v1/me', async (req) => {
    const u = await authUser(req)
    const rows = (await pool.query(
      `SELECT b.id, b.name, m.role FROM memberships m JOIN businesses b ON b.id = m.business_id
        WHERE m.user_id = $1 AND b.deleted_at IS NULL ORDER BY b.created_at`, [u.id])).rows
    const businesses = []
    for (const b of rows) {
      // A card or net-banking payment finished in the browser starts the plan here, the next time the app looks.
      if (b.role === 'owner') try { await checkLinks(b.id) } catch { }
      businesses.push({ ...b, entitlement: await entitlement(b.id) })
    }
    return {
      user: { id: u.id, email: u.email, name: u.name }, businesses,
      ai: { configured: textApi.configured(), textProvider, live: gemini.configured(), speech: speech.sttConfigured(), speechToText: speech.sttProvider(), textToSpeech: speech.ttsProvider(), textModel, liveModel: cfg.liveModel, liveWsUrl: cfg.liveWsUrl },
    }
  })

  /** Everything the server holds about this person, for "download my data". */
  app.get('/v1/me/export', async (req) => {
    const u = await authUser(req)
    const businesses = (await pool.query('SELECT b.id, b.name, b.created_at, m.role FROM memberships m JOIN businesses b ON b.id = m.business_id WHERE m.user_id = $1', [u.id])).rows
    const ids = businesses.filter(b => b.role === 'owner').map(b => b.id)
    const profiles = ids.length ? (await pool.query('SELECT business_id, version, config, created_at FROM business_profiles WHERE business_id = ANY($1)', [ids])).rows : []
    const usage = ids.length ? (await pool.query('SELECT business_id, kind, quantity, created_at FROM usage_events WHERE business_id = ANY($1) ORDER BY created_at', [ids])).rows : []
    return { user: { email: u.email, name: u.name }, businesses, profiles, usage }
  })

  /** Deletes the account. Businesses where this person is the only owner are deleted with all their data. */
  app.delete('/v1/me', async (req) => {
    const u = await authUser(req)
    if (!(await checkPassword(String(req.body?.password ?? ''), (await pool.query('SELECT password_hash FROM users WHERE id=$1', [u.id])).rows[0].password_hash)))
      fail(401, 'bad_credentials', 'Password is wrong.')
    await tx(pool, async (c) => {
      const owned = (await c.query(
        `SELECT business_id FROM memberships m WHERE user_id = $1 AND role = 'owner'
           AND NOT EXISTS (SELECT 1 FROM memberships o WHERE o.business_id = m.business_id AND o.role = 'owner' AND o.user_id <> $1)`, [u.id])).rows
      for (const o of owned) await c.query('DELETE FROM businesses WHERE id = $1', [o.business_id])
      await c.query('DELETE FROM users WHERE id = $1', [u.id])
    })
    audit(null, null, 'account_deleted', {}, req.ip)
    return { ok: true }
  })

  // ---------------------------------------------------------------- business profile

  app.get('/v1/businesses/:id/profile', async (req) => {
    await member(req, req.params.id)
    const p = (await pool.query('SELECT version, config, created_at FROM business_profiles WHERE business_id = $1 ORDER BY version DESC LIMIT 1', [req.params.id])).rows[0]
    return p ?? { version: 0, config: null }
  })

  app.put('/v1/businesses/:id/profile', async (req) => {
    const { user } = await member(req, req.params.id, 'owner')
    const v = validateConfig(req.body?.config)
    if (!v.ok) fail(400, 'invalid_config', v.errors.join('; '))
    const expected = Number(req.body?.baseVersion ?? -1)
    const out = await tx(pool, async (c) => {
      await c.query('SELECT id FROM businesses WHERE id = $1 FOR UPDATE', [req.params.id])
      const cur = Number((await c.query('SELECT COALESCE(MAX(version),0) AS v FROM business_profiles WHERE business_id = $1', [req.params.id])).rows[0].v)
      // Two phones editing at once: the second must reload instead of silently overwriting.
      if (expected >= 0 && expected !== cur) fail(409, 'version_conflict', 'The business settings changed on another device. Reload and try again.')
      await c.query('INSERT INTO business_profiles (business_id, version, config, created_by) VALUES ($1,$2,$3,$4)', [req.params.id, cur + 1, v.config, user.id])
      return cur + 1
    })
    audit(req.params.id, user.id, 'profile_saved', { version: out, type: v.config.businessType }, req.ip)
    return { version: out }
  })

  // ---------------------------------------------------------------- staff

  app.get('/v1/businesses/:id/members', async (req) => {
    await member(req, req.params.id, 'manager')
    return { members: (await pool.query('SELECT u.email, u.name, m.role FROM memberships m JOIN users u ON u.id = m.user_id WHERE m.business_id = $1 ORDER BY m.created_at', [req.params.id])).rows }
  })

  app.post('/v1/businesses/:id/members', async (req) => {
    const { user } = await member(req, req.params.id, 'owner')
    const role = req.body?.role
    if (!['manager', 'staff'].includes(role)) fail(400, 'bad_role', 'Role must be manager or staff.')
    const em = String(req.body?.email ?? '').trim().toLowerCase()
    const target = (await pool.query('SELECT id FROM users WHERE email = $1', [em])).rows[0]
    if (!target) fail(404, 'user_not_found', 'Ask this person to create a Follower account first.')
    if (target.id === user.id) fail(400, 'bad_request', 'You are already the owner.')
    const existing = (await pool.query('SELECT role FROM memberships WHERE user_id = $1 AND business_id = $2', [target.id, req.params.id])).rows[0]
    if (existing?.role === 'owner') fail(400, 'bad_request', 'An owner cannot be changed to another role here.')
    const e = await entitlement(req.params.id)
    const count = Number((await pool.query('SELECT COUNT(*) AS n FROM memberships WHERE business_id = $1', [req.params.id])).rows[0].n)
    if (!existing && count >= (e.maxStaff ?? 1)) fail(402, 'staff_limit', `Your plan allows ${e.maxStaff} people.`)
    await pool.query('INSERT INTO memberships (user_id, business_id, role) VALUES ($1,$2,$3) ON CONFLICT (user_id, business_id) DO UPDATE SET role = EXCLUDED.role', [target.id, req.params.id, role])
    audit(req.params.id, user.id, 'member_added', { role }, req.ip)
    return { ok: true }
  })

  // ---------------------------------------------------------------- AI: text conversation proxy

  /** Checks the plan and books one use in the same transaction, so parallel requests cannot all slip under the limit. */
  async function reserve(businessId, check) {
    return tx(pool, async (c) => {
      await c.query('SELECT 1 FROM subscriptions WHERE business_id = $1 FOR UPDATE', [businessId])
      const e = await entitlement(businessId, c)
      requireActive(e)
      return check(c, e)
    })
  }

  app.post('/v1/businesses/:id/ai/generate', { config: { rateLimit: { max: 40, timeWindow: '1 minute' } } }, async (req, reply) => {
    const { user } = await member(req, req.params.id)
    if (!textApi.configured()) fail(503, 'ai_not_configured', textProvider === 'openrouter' ? 'The server has no OpenRouter key yet.' : 'The server has no Gemini key yet.')
    const body = sanitizeRequest(req.body?.request)
    if (!body) fail(400, 'bad_request', 'Not a valid conversation request.')
    const usageId = await reserve(req.params.id, async (c, e) => {
      if (e.aiRequests.used >= e.aiRequests.limit) fail(429, 'quota_exceeded', 'This month\'s Stocky AI limit is used up. It resets on your renewal date.')
      return (await c.query("INSERT INTO usage_events (business_id, user_id, kind, quantity, model) VALUES ($1,$2,'ai_request',1,$3) RETURNING id", [req.params.id, user.id, textModel])).rows[0].id
    })
    let r
    try { r = await textApi.generate(textModel, body) } catch (err) { await pool.query('DELETE FROM usage_events WHERE id = $1', [usageId]); throw err }
    if (r.status >= 200 && r.status < 300) {
      const m = r.body?.usageMetadata ?? {}
      await pool.query('UPDATE usage_events SET input_tokens = $2, output_tokens = $3 WHERE id = $1',
        [usageId, m.promptTokenCount ?? 0, (m.candidatesTokenCount ?? 0) + (m.thoughtsTokenCount ?? 0)])
    } else {
      // Google refused: the use is given back.
      await pool.query('DELETE FROM usage_events WHERE id = $1', [usageId])
    }
    // Google's answer (or its error) goes back as it is, so the app can read function calls and explain errors.
    return reply.code(r.status).send(r.body)
  })

  // ---------------------------------------------------------------- AI: speech (Sarvam AI or Google Cloud), turn by turn

  /** Books [seconds] of voice time under the plan lock; refused when the month's minutes would be exceeded. */
  async function reserveSpeech(businessId, userId, kind, seconds, model) {
    return reserve(businessId, async (c, e) => {
      if (e.liveSeconds.limit - e.liveSeconds.used < seconds) fail(429, 'quota_exceeded', 'This month\'s voice minutes are used up. Typing to Stocky still works.')
      return (await c.query('INSERT INTO usage_events (business_id, user_id, kind, quantity, model) VALUES ($1,$2,$3,$4,$5) RETURNING id', [businessId, userId, kind, seconds, model])).rows[0].id
    })
  }

  const B64 = /^[A-Za-z0-9+/]+={0,2}$/
  const MAX_STT_BYTES = 16000 * 2 * 60 // one minute of 16 kHz 16-bit mono

  app.post('/v1/businesses/:id/ai/stt', { bodyLimit: 3 * 1024 * 1024, config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req) => {
    const { user } = await member(req, req.params.id)
    if (!speech.sttConfigured()) fail(503, 'speech_not_configured', 'The server has no speech-to-text key yet (Sarvam AI or Google Cloud).')
    const { audio, language, phrases } = req.body ?? {}
    if (typeof audio !== 'string' || audio.length < 8 || audio.length % 4 !== 0 || !B64.test(audio)) fail(400, 'bad_request', 'No audio was received.')
    const bytes = audio.length / 4 * 3
    if (bytes > MAX_STT_BYTES) fail(413, 'too_long', 'Please speak for less than a minute at a time.')
    const lang = SPEECH_LANGS.includes(language) ? language : 'ta-IN'
    const hints = Array.isArray(phrases) ? [...new Set(phrases.filter(p => typeof p === 'string').map(p => p.trim().slice(0, 100)).filter(Boolean))].slice(0, 500) : []
    const seconds = Math.max(1, Math.ceil(bytes / 32000))
    const usageId = await reserveSpeech(req.params.id, user.id, 'stt_seconds', seconds, speech.sttModel())
    let r
    try { r = await speech.recognize({ audio, language: lang, phrases: hints }) } catch (err) { await pool.query('DELETE FROM usage_events WHERE id = $1', [usageId]); throw err }
    if (r.status !== 200) {
      await pool.query('DELETE FROM usage_events WHERE id = $1', [usageId])
      fail(r.status === 429 ? 503 : 502, 'speech_failed', r.error)
    }
    return { text: r.text, language: r.language, confidence: r.confidence, seconds }
  })

  app.post('/v1/businesses/:id/ai/tts', { config: { rateLimit: { max: 40, timeWindow: '1 minute' } } }, async (req) => {
    const { user } = await member(req, req.params.id)
    if (!speech.ttsConfigured()) fail(503, 'speech_not_configured', 'The server has no text-to-speech key yet (Sarvam AI or Google Cloud).')
    const text = typeof req.body?.text === 'string' ? req.body.text.trim() : ''
    if (!text) fail(400, 'bad_request', 'Nothing to say.')
    if ([...text].length > 2000) fail(413, 'too_long', 'The reply is too long to read out.')
    const lang = speechLanguage(text, req.body?.language)
    const usageId = await reserveSpeech(req.params.id, user.id, 'tts_seconds', ttsSeconds(text), ({ sarvam: cfg.sarvamTtsModel, gemini: cfg.geminiTtsModel || 'gemini-tts' })[speech.ttsProvider()] ?? (lang === 'ta-IN' ? cfg.ttsVoiceTa : cfg.ttsVoiceEn))
    let r
    try { r = await speech.synthesize({ text, language: lang }) } catch (err) { await pool.query('DELETE FROM usage_events WHERE id = $1', [usageId]); throw err }
    if (r.status !== 200) {
      await pool.query('DELETE FROM usage_events WHERE id = $1', [usageId])
      fail(r.status === 429 ? 503 : 502, 'speech_failed', r.error)
    }
    return { audio: r.audio, encoding: 'wav', sampleRate: 24000, language: lang }
  })

  // ---------------------------------------------------------------- AI: real-time voice

  /** One voice session row, counted at its full length while open; checked under the plan lock. */
  async function reserveLive(businessId, userId) {
    if (!gemini.configured()) fail(503, 'ai_not_configured', 'The server has no Gemini key yet.')
    return reserve(businessId, async (c, e) => {
      const left = e.liveSeconds.limit - e.liveSeconds.used
      if (left < 30) fail(429, 'quota_exceeded', 'This month\'s voice conversation minutes are used up. Typing to Stocky still works.')
      const open = Number((await c.query("SELECT COUNT(*) AS n FROM live_sessions WHERE business_id = $1 AND NOT closed AND expires_at > now()", [businessId])).rows[0].n)
      if (open >= 3) fail(429, 'too_many_sessions', 'Too many voice conversations are open at once. Close one and try again.')
      const seconds = Math.min(cfg.liveMaxSeconds, left)
      const row = (await c.query(
        'INSERT INTO live_sessions (business_id, user_id, model, max_seconds, expires_at) VALUES ($1,$2,$3,$4, now() + make_interval(secs => $5)) RETURNING id',
        [businessId, userId, cfg.liveModel, seconds, seconds + 120])).rows[0]
      return { id: row.id, seconds }
    })
  }

  /**
   * How to start a voice conversation. Default ("relay"): through this server's WebSocket, which measures minutes itself.
   * Optional ("token", LIVE_MODE=token): a one-use Google token so audio goes straight to Google (less server traffic,
   * but minutes are then reported by the phone).
   */
  app.post('/v1/businesses/:id/ai/live-session', { config: { rateLimit: { max: 12, timeWindow: '10 minutes' } } }, async (req) => {
    const { user } = await member(req, req.params.id)
    if (!gemini.configured()) fail(503, 'ai_not_configured', 'The server has no Gemini key yet.')
    if (cfg.liveMode === 'relay') {
      const e = await entitlement(req.params.id)
      requireActive(e)
      const left = e.liveSeconds.limit - e.liveSeconds.used
      if (left < 30) fail(429, 'quota_exceeded', 'This month\'s voice conversation minutes are used up. Typing to Stocky still works.')
      const scheme = req.protocol === 'https' ? 'wss' : 'ws'
      return { mode: 'relay', sessionId: '', token: '', wsUrl: `${scheme}://${req.host}/v1/businesses/${req.params.id}/ai/live`, model: cfg.liveModel, maxSeconds: Math.min(cfg.liveMaxSeconds, left) }
    }
    const s = await reserveLive(req.params.id, user.id)
    let tok
    try { tok = await gemini.liveToken(cfg.liveModel, s.seconds) } catch (err) {
      await pool.query('DELETE FROM live_sessions WHERE id = $1', [s.id])
      fail(502, 'ai_token_failed', `Google did not give a voice session: ${err.message}`)
    }
    audit(req.params.id, user.id, 'live_session', { seconds: s.seconds }, req.ip)
    return { mode: 'token', sessionId: s.id, token: tok.token, wsUrl: cfg.liveWsUrl, model: cfg.liveModel, maxSeconds: s.seconds, expiresAt: tok.expiresAt }
  })

  registerLiveRelay(app, { cfg, pool, member, reserveLive, audit, ...(WebSocketImpl ? { WebSocketImpl } : {}) })

  /** The app reports how long the voice session really ran. Repeated reports only ever raise the number, capped by the clock. */
  app.post('/v1/businesses/:id/ai/live-usage', async (req) => {
    await member(req, req.params.id)
    const sid = String(req.body?.sessionId ?? '')
    if (!/^[0-9a-f-]{36}$/i.test(sid)) fail(400, 'bad_request', 'sessionId missing.')
    const seconds = Math.max(0, Math.floor(Number(req.body?.seconds) || 0))
    const r = await pool.query(
      `UPDATE live_sessions SET
          reported_seconds = GREATEST(reported_seconds, LEAST($3::int, max_seconds, EXTRACT(EPOCH FROM (now() - started_at))::int + 5)),
          closed = closed OR $4::boolean,
          closed_at = CASE WHEN $4::boolean AND closed_at IS NULL THEN now() ELSE closed_at END
        WHERE id = $1 AND business_id = $2 RETURNING reported_seconds`, [sid, req.params.id, seconds, req.body?.final === true])
    if (!r.rowCount) fail(404, 'not_found', 'Session not found.')
    return { recordedSeconds: r.rows[0].reported_seconds }
  })

  app.get('/v1/businesses/:id/usage', async (req) => {
    await member(req, req.params.id)
    return entitlement(req.params.id)
  })

  // ---------------------------------------------------------------- backups (the app's backup file, kept on the owner's own server)

  app.post('/v1/businesses/:id/backups', async (req) => {
    const { user } = await member(req, req.params.id, 'owner')
    const data = req.body
    if (!Buffer.isBuffer(data) || data.length === 0) fail(400, 'bad_request', 'Send the backup file as application/octet-stream.')
    const note = String(req.headers['x-backup-note'] ?? '').slice(0, 120)
    const sum = (await import('node:crypto')).createHash('sha256').update(data).digest('hex')
    const row = await tx(pool, async (c) => {
      const r = (await c.query('INSERT INTO backups (business_id, user_id, size_bytes, sha256, data, note) VALUES ($1,$2,$3,$4,$5,$6) RETURNING id, created_at',
        [req.params.id, user.id, data.length, sum, data, note])).rows[0]
      // Keep the 10 newest.
      await c.query('DELETE FROM backups WHERE business_id = $1 AND id NOT IN (SELECT id FROM backups WHERE business_id = $1 ORDER BY created_at DESC LIMIT 10)', [req.params.id])
      return r
    })
    audit(req.params.id, user.id, 'backup_uploaded', { size: data.length }, req.ip)
    return { id: row.id, createdAt: row.created_at, sha256: sum }
  })

  app.get('/v1/businesses/:id/backups', async (req) => {
    await member(req, req.params.id, 'owner')
    return { backups: (await pool.query('SELECT id, size_bytes, sha256, note, created_at FROM backups WHERE business_id = $1 ORDER BY created_at DESC', [req.params.id])).rows }
  })

  app.get('/v1/businesses/:id/backups/:backupId', async (req, reply) => {
    await member(req, req.params.id, 'owner')
    if (!/^[0-9a-f-]{36}$/i.test(req.params.backupId)) fail(404, 'not_found', 'Backup not found.')
    const b = (await pool.query('SELECT data, sha256 FROM backups WHERE id = $1 AND business_id = $2', [req.params.backupId, req.params.id])).rows[0]
    if (!b) fail(404, 'not_found', 'Backup not found.')
    return reply.header('content-type', 'application/octet-stream').header('x-sha256', b.sha256).send(b.data)
  })

  // ---------------------------------------------------------------- billing

  app.post('/v1/billing/google-play/verify', async () => {
    fail(501, 'not_configured', 'Google Play billing is not connected on this server yet. Plans are activated by the administrator for now.')
  })

  // ---------------------------------------------------------------- UPI payments (claimed in the app, approved by the administrator)

  const UTR = /^[A-Za-z0-9]{6,30}$/
  const claimRow = (r) => ({ id: Number(r.id), planId: r.plan_id, amountMinor: r.amount_minor, utr: r.utr, status: r.status, note: r.note, createdAt: r.created_at, decidedAt: r.decided_at })

  /** The owner paid by UPI and sends the transaction reference; the plan starts when the administrator approves it. */
  app.post('/v1/businesses/:id/payments', { config: { rateLimit: { max: 10, timeWindow: '1 hour' } } }, async (req) => {
    const { user } = await member(req, req.params.id, 'owner')
    const planId = String(req.body?.planId ?? '')
    const utr = String(req.body?.utr ?? '').replace(/\s+/g, '')
    if (!UTR.test(utr)) fail(400, 'bad_utr', 'Enter the UPI transaction ID (UTR / reference number) shown in your UPI app: 6 to 30 letters or digits.')
    const plan = (await pool.query('SELECT id, price_minor FROM plans WHERE id = $1 AND active AND price_minor > 0', [planId])).rows[0]
    if (!plan) fail(400, 'bad_plan', 'Choose a plan.')
    const pending = Number((await pool.query("SELECT COUNT(*) AS n FROM payment_claims WHERE business_id = $1 AND status = 'pending'", [req.params.id])).rows[0].n)
    if (pending >= 3) fail(429, 'too_many_pending', 'You already sent payments that are waiting to be checked.')
    const r = await pool.query(
      `INSERT INTO payment_claims (business_id, user_id, plan_id, amount_minor, utr) VALUES ($1,$2,$3,$4,$5)
       ON CONFLICT (utr) DO NOTHING RETURNING *`, [req.params.id, user.id, plan.id, plan.price_minor, utr])
    if (!r.rowCount) fail(409, 'utr_used', 'This UPI transaction ID was already sent.')
    audit(req.params.id, user.id, 'payment_claimed', { planId: plan.id, utr }, req.ip)
    return claimRow(r.rows[0])
  })

  app.get('/v1/businesses/:id/payments', async (req) => {
    await member(req, req.params.id, 'owner')
    return { payments: (await pool.query('SELECT * FROM payment_claims WHERE business_id = $1 ORDER BY created_at DESC LIMIT 20', [req.params.id])).rows.map(claimRow) }
  })

  /** Starts the paid plan now, or adds its period after a paid period that is still running. Inside a transaction. */
  async function activatePlan(c, businessId, planId, reference) {
    const days = Number((await c.query('SELECT period_days FROM plans WHERE id = $1', [planId])).rows[0]?.period_days) || 30
    const r = await c.query(
      `UPDATE subscriptions SET plan_id = $2, status = 'active', source = 'manual', external_ref = $4, updated_at = now(),
          current_period_start = CASE WHEN status = 'active' AND current_period_end > now() THEN current_period_start ELSE now() END,
          current_period_end = GREATEST(CASE WHEN status = 'active' THEN current_period_end ELSE now() END, now()) + make_interval(days => $3)
        WHERE business_id = $1 RETURNING current_period_end`, [businessId, planId, days, reference])
    if (!r.rowCount) fail(404, 'not_found', 'Business not found.')
    return r.rows[0].current_period_end
  }

  // ---------------------------------------------------------------- Razorpay payment links

  const rzpReady = () => !!(cfg.razorpayKeyId && cfg.razorpayKeySecret)
  const rzp = async (method, path, body) => {
    const r = await fetchImpl(`${cfg.razorpayBase}/v1${path}`, {
      method, signal: AbortSignal.timeout(20_000),
      headers: { 'content-type': 'application/json', authorization: 'Basic ' + Buffer.from(`${cfg.razorpayKeyId}:${cfg.razorpayKeySecret}`).toString('base64') },
      body: body ? JSON.stringify(body) : undefined,
    })
    const text = await r.text()
    let json; try { json = JSON.parse(text) } catch { json = {} }
    if (!r.ok) fail(502, 'payment_provider', json?.error?.description || `Razorpay answered ${r.status}.`)
    return json
  }

  /** A Razorpay page for this plan (cards, net banking, UPI, wallets); the app opens it in the browser. */
  app.post('/v1/businesses/:id/payments/link', { config: { rateLimit: { max: 10, timeWindow: '1 hour' } } }, async (req) => {
    const { user } = await member(req, req.params.id, 'owner')
    if (!rzpReady()) fail(503, 'not_configured', 'Card payments are not set up on this server yet.')
    const plan = (await pool.query('SELECT id, name, price_minor FROM plans WHERE id = $1 AND active AND price_minor > 0', [String(req.body?.planId ?? '')])).rows[0]
    if (!plan) fail(400, 'bad_plan', 'Choose a plan.')
    const ref = `F${Date.now().toString(36)}${randomInt(0, 1e6).toString(36)}`.slice(0, 40)
    const link = await rzp('POST', '/payment_links', {
      amount: plan.price_minor, currency: 'INR', accept_partial: false, reference_id: ref,
      description: `Follower ${plan.name} plan`, customer: { email: user.email },
      notify: { sms: false, email: false }, reminder_enable: false,
      notes: { business_id: req.params.id, plan_id: plan.id },
    })
    if (!link?.id || !link?.short_url) fail(502, 'payment_provider', 'Razorpay did not return a payment page.')
    await pool.query('INSERT INTO payment_links (business_id, user_id, plan_id, amount_minor, provider_id, short_url) VALUES ($1,$2,$3,$4,$5,$6)',
      [req.params.id, user.id, plan.id, plan.price_minor, link.id, link.short_url])
    audit(req.params.id, user.id, 'payment_link_created', { planId: plan.id, link: link.id }, req.ip)
    return { url: link.short_url, id: link.id }
  })

  /** Asks Razorpay about this business's open payment pages; a paid one starts the plan (once). */
  async function checkLinks(businessId) {
    if (!rzpReady()) return 0
    const open = (await pool.query("SELECT * FROM payment_links WHERE business_id = $1 AND status = 'created' AND created_at > now() - interval '7 days' ORDER BY created_at DESC LIMIT 5", [businessId])).rows
    let paid = 0
    for (const l of open) {
      let remote
      try { remote = await rzp('GET', `/payment_links/${encodeURIComponent(l.provider_id)}`) } catch { continue }
      const st = String(remote?.status ?? '')
      if (st === 'paid' && Number(remote.amount_paid ?? 0) >= l.amount_minor) {
        await tx(pool, async (c) => {
          const claimed = await c.query("UPDATE payment_links SET status = 'paid', paid_at = now() WHERE id = $1 AND status = 'created' RETURNING id", [l.id])
          if (claimed.rowCount) await activatePlan(c, businessId, l.plan_id, `Razorpay ${l.provider_id}`)
        })
        audit(businessId, l.user_id, 'payment_link_paid', { link: l.provider_id }, null)
        paid++
      } else if (st === 'expired' || st === 'cancelled') {
        await pool.query('UPDATE payment_links SET status = $2 WHERE id = $1', [l.id, st])
      }
    }
    return paid
  }

  app.post('/v1/businesses/:id/payments/check', { config: { rateLimit: { max: 30, timeWindow: '10 minutes' } } }, async (req) => {
    await member(req, req.params.id, 'owner')
    const paid = await checkLinks(req.params.id)
    return { paid, entitlement: await entitlement(req.params.id) }
  })

  const admin = (req) => {
    const h = req.headers.authorization ?? ''
    if (!cfg.adminToken || !sameSecret(h.replace(/^Bearer /, ''), cfg.adminToken)) fail(401, 'unauthorized', 'Admin token required.')
  }

  /** Administrator: a reset code for an owner who has no email access (read it out to them on the phone). */
  app.post('/admin/password-code', async (req) => {
    admin(req)
    const em = String(req.body?.email ?? '').trim().toLowerCase()
    const u = (await pool.query('SELECT id FROM users WHERE email = $1', [em])).rows[0]
    if (!u) fail(404, 'not_found', 'No account with this email.')
    const code = await newResetCode(u.id)
    audit(null, u.id, 'password_code_by_admin', {}, req.ip)
    return { email: em, code, validMinutes: 15 }
  })

  /** Administrator: payments waiting to be checked, with who sent them. */
  app.get('/admin/payments', async (req) => {
    admin(req)
    const status = ['pending', 'approved', 'rejected'].includes(req.query?.status) ? req.query.status : 'pending'
    const rows = (await pool.query(
      `SELECT c.*, b.name AS business_name, u.email FROM payment_claims c JOIN businesses b ON b.id = c.business_id
         LEFT JOIN users u ON u.id = c.user_id WHERE c.status = $1 ORDER BY c.created_at LIMIT 200`, [status])).rows
    return { payments: rows.map(r => ({ ...claimRow(r), businessId: r.business_id, business: r.business_name, email: r.email })) }
  })

  /**
   * Administrator: the money arrived (approve: the plan starts now, or continues after the paid period that is still
   * running, for the plan's period) or it did not (reject).
   */
  app.post('/admin/payments/:claimId/:decision', async (req) => {
    admin(req)
    const decision = req.params.decision
    if (!['approve', 'reject'].includes(decision) || !/^\d+$/.test(req.params.claimId)) fail(404, 'not_found', 'Unknown payment or action.')
    const c = await pool.connect()
    try {
      await c.query('BEGIN')
      const claim = (await c.query("SELECT * FROM payment_claims WHERE id = $1 FOR UPDATE", [req.params.claimId])).rows[0]
      if (!claim) fail(404, 'not_found', 'Unknown payment.')
      if (claim.status !== 'pending') fail(409, 'already_decided', `This payment was already ${claim.status}.`)
      const note = String(req.body?.note ?? '').slice(0, 200)
      await c.query('UPDATE payment_claims SET status = $2, note = $3, decided_at = now() WHERE id = $1', [claim.id, decision === 'approve' ? 'approved' : 'rejected', note])
      let periodEnd = null
      if (decision === 'approve') periodEnd = await activatePlan(c, claim.business_id, claim.plan_id, `UPI ${claim.utr}`)
      await c.query('COMMIT')
      audit(claim.business_id, null, decision === 'approve' ? 'payment_approved' : 'payment_rejected', { claimId: Number(claim.id), utr: claim.utr }, req.ip)
      return { ok: true, status: decision === 'approve' ? 'approved' : 'rejected', periodEnd }
    } catch (err) {
      await c.query('ROLLBACK').catch(() => {})
      throw err
    } finally { c.release() }
  })

  /** Administrator: activate or extend a plan after a payment received outside the app. */
  app.post('/admin/subscriptions', async (req) => {
    admin(req)
    const { businessId, planId, days = 30, reference = '' } = req.body ?? {}
    if (!/^[0-9a-f-]{36}$/i.test(String(businessId)) || !Number.isInteger(Number(days)) || Number(days) < 1 || Number(days) > 3660) fail(400, 'bad_request', 'businessId must be an id and days 1-3660.')
    const plan = (await pool.query('SELECT id FROM plans WHERE id = $1', [planId])).rows[0]
    if (!plan) fail(400, 'bad_plan', 'Unknown plan.')
    const r = await pool.query(
      `UPDATE subscriptions SET plan_id = $2, status = 'active', source = 'manual', external_ref = $4, updated_at = now(),
          current_period_start = now(), current_period_end = now() + make_interval(days => $3)
        WHERE business_id = $1 RETURNING current_period_end`, [businessId, planId, Number(days), String(reference).slice(0, 120)])
    if (!r.rowCount) fail(404, 'not_found', 'Business not found.')
    audit(businessId, null, 'subscription_set', { planId, days }, req.ip)
    return { ok: true, periodEnd: r.rows[0].current_period_end }
  })

  return app
}
