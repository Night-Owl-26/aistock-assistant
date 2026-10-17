import Fastify from 'fastify'
import rateLimit from '@fastify/rate-limit'
import websocket from '@fastify/websocket'
import { registerLiveRelay } from './liveRelay.js'
import { checkPassword, hashPassword, randomToken, sameSecret, sha256, tokens } from './auth.js'
import { tx } from './db.js'
import { geminiApi, sanitizeRequest } from './gemini.js'
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
export async function buildApp({ cfg, pool, fetchImpl = fetch, logger = false, WebSocketImpl }) {
  const app = Fastify({ logger, bodyLimit: 1024 * 1024, trustProxy: true })
  const jwt = tokens(cfg)
  const gemini = geminiApi(cfg, fetchImpl)

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
    return {
      active, status: s.status, planId: s.plan_id, planName: s.plan_name, priceMinor: s.price_minor, currency: s.currency,
      periodEnd: s.current_period_end, maxStaff: s.max_staff,
      aiRequests: { used: ai, limit: s.ai_requests_month },
      liveSeconds: { used: live, limit: s.live_seconds_month },
    }
  }

  function requireActive(e) {
    if (!e.active) fail(402, 'subscription_inactive', 'Your AIStock plan has ended. Renew it to keep using Stocky AI.')
  }

  // ---------------------------------------------------------------- public

  app.get('/healthz', async () => {
    await pool.query('SELECT 1')
    return { ok: true, gemini: gemini.configured(), textModel: cfg.textModel, liveModel: cfg.liveModel }
  })

  app.get('/v1/plans', async () => ({
    plans: (await pool.query('SELECT id, name, price_minor, currency, period_days, ai_requests_month, live_seconds_month, max_staff FROM plans WHERE active ORDER BY price_minor')).rows,
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
    for (const b of rows) businesses.push({ ...b, entitlement: await entitlement(b.id) })
    return {
      user: { id: u.id, email: u.email, name: u.name }, businesses,
      ai: { configured: gemini.configured(), textModel: cfg.textModel, liveModel: cfg.liveModel, liveWsUrl: cfg.liveWsUrl },
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
    if (!target) fail(404, 'user_not_found', 'Ask this person to create an AIStock account first.')
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
    if (!gemini.configured()) fail(503, 'ai_not_configured', 'The server has no Gemini key yet.')
    const body = sanitizeRequest(req.body?.request)
    if (!body) fail(400, 'bad_request', 'Not a valid conversation request.')
    const usageId = await reserve(req.params.id, async (c, e) => {
      if (e.aiRequests.used >= e.aiRequests.limit) fail(429, 'quota_exceeded', 'This month\'s Stocky AI limit is used up. It resets on your renewal date.')
      return (await c.query("INSERT INTO usage_events (business_id, user_id, kind, quantity, model) VALUES ($1,$2,'ai_request',1,$3) RETURNING id", [req.params.id, user.id, cfg.textModel])).rows[0].id
    })
    let r
    try { r = await gemini.generate(cfg.textModel, body) } catch (err) { await pool.query('DELETE FROM usage_events WHERE id = $1', [usageId]); throw err }
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

  /** Administrator: activate or extend a plan after a payment received outside the app. */
  app.post('/admin/subscriptions', async (req) => {
    const h = req.headers.authorization ?? ''
    if (!cfg.adminToken || !sameSecret(h.replace(/^Bearer /, ''), cfg.adminToken)) fail(401, 'unauthorized', 'Admin token required.')
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
