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
    textModel: get('GEMINI_TEXT_MODEL', 'gemini-flash-lite-latest'),
    liveModel: get('GEMINI_LIVE_MODEL', 'gemini-3.8-live'),
    liveWsUrl: get('GEMINI_LIVE_WS_URL', 'wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained'),
    liveMode: get('LIVE_MODE', 'relay'),
    liveUpstreamUrl: get('GEMINI_LIVE_UPSTREAM_URL', 'wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent'),
    liveMaxSeconds: Number(get('LIVE_MAX_SECONDS', 600)),
    // Which service hears the owner first: gemini (default) or whisper. Replies are always spoken by Gemini.
    sttProvider: get('STT_PROVIDER', 'gemini'),
    ttsProvider: 'gemini',
    // Gemini for speech, with the same Gemini key (no extra service): hearing uses the text model,
    // speaking a Gemini TTS model (found automatically when empty) in the prebuilt voice the owner picks in the app
    // (GEMINI_TTS_VOICE is used until they pick one).
    // Self-hosted Whisper (server/whisper, started with COMPOSE_PROFILES=whisper): http://whisper:9000
    whisperUrl: get('WHISPER_URL', ''),
    geminiSttModel: get('GEMINI_STT_MODEL', ''),
    geminiTtsModel: get('GEMINI_TTS_MODEL', ''),
    geminiTtsVoice: get('GEMINI_TTS_VOICE', 'Kore'),
    // OpenRouter for Stocky's typed conversation (any model there, including free ones). When its key is set it is
    // used instead of Gemini for text (TEXT_PROVIDER=gemini keeps Gemini). Real-time voice always needs Gemini.
    openRouterKey: get('OPENROUTER_API_KEY', ''),
    openRouterBase: get('OPENROUTER_BASE_URL', 'https://openrouter.ai/api/v1'),
    openRouterModel: get('OPENROUTER_MODEL', 'openrouter/free'),
    textProvider: get('TEXT_PROVIDER', 'auto'),
    trialDays: Number(get('TRIAL_DAYS', 14)),
    // Where owners pay after the trial (shown in the app, never a secret). Empty: the app says to contact you.
    upiId: get('UPI_ID', ''),
    upiName: get('UPI_NAME', 'Thodar'),
    supportPhone: get('SUPPORT_PHONE', ''),
    // Razorpay (cards, net banking, UPI) for paying after the trial: Dashboard > Account & Settings > API Keys.
    razorpayKeyId: get('RAZORPAY_KEY_ID', ''),
    razorpayKeySecret: get('RAZORPAY_KEY_SECRET', ''),
    razorpayBase: get('RAZORPAY_BASE_URL', 'https://api.razorpay.com'),
    // Email for "Forgot password" codes (optional). Gmail: smtp.gmail.com, 465, your address, an app password.
    smtpHost: get('SMTP_HOST', ''),
    smtpPort: Number(get('SMTP_PORT', 465)),
    smtpUser: get('SMTP_USER', ''),
    smtpPass: get('SMTP_PASS', ''),
    smtpFrom: get('SMTP_FROM', ''),
    accessTtlSeconds: Number(get('ACCESS_TTL_SECONDS', 3600)),
    refreshTtlDays: Number(get('REFRESH_TTL_DAYS', 60)),
    maxBackupBytes: Number(get('MAX_BACKUP_BYTES', 20 * 1024 * 1024)),
    registerPerHour: Number(get('REGISTER_PER_HOUR', 10)),
    allowRegistration: get('ALLOW_REGISTRATION', 'true') !== 'false',
  }
  if (cfg.jwtSecret.length < 32) throw new Error('JWT_SECRET must be at least 32 characters')
  return cfg
}
