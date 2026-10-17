// All settings come from the environment (see .env.example). Nothing secret is built into the app.
function need(name, fallback) {
  const v = process.env[name] ?? fallback
  if (v === undefined || v === '') throw new Error(`Missing environment variable ${name}`)
  return v
}

export function loadConfig(env = process.env) {
  const get = (n, f) => (env[n] ?? f)
  const cfg = {
    port: Number(get('PORT', 8080)),
    databaseUrl: get('DATABASE_URL') ?? need('DATABASE_URL'),
    jwtSecret: get('JWT_SECRET') ?? need('JWT_SECRET'),
    adminToken: get('ADMIN_TOKEN', ''),
    geminiKey: get('GEMINI_API_KEY', ''),
    geminiBase: get('GEMINI_BASE_URL', 'https://generativelanguage.googleapis.com'),
    // Text conversation model (Stocky's tool-calling chat) and the real-time voice model. Change here, not in the app.
    textModel: get('GEMINI_TEXT_MODEL', 'gemini-flash-latest'),
    liveModel: get('GEMINI_LIVE_MODEL', 'gemini-3.8-live'),
    liveWsUrl: get('GEMINI_LIVE_WS_URL', 'wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained'),
    liveMaxSeconds: Number(get('LIVE_MAX_SECONDS', 600)),
    trialDays: Number(get('TRIAL_DAYS', 14)),
    accessTtlSeconds: Number(get('ACCESS_TTL_SECONDS', 3600)),
    refreshTtlDays: Number(get('REFRESH_TTL_DAYS', 60)),
    maxBackupBytes: Number(get('MAX_BACKUP_BYTES', 20 * 1024 * 1024)),
    registerPerHour: Number(get('REGISTER_PER_HOUR', 10)),
    allowRegistration: get('ALLOW_REGISTRATION', 'true') !== 'false',
  }
  if (cfg.jwtSecret.length < 32) throw new Error('JWT_SECRET must be at least 32 characters')
  return cfg
}
