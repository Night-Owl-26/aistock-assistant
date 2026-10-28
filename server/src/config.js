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
    // Google Cloud Speech-to-Text / Text-to-Speech (an API key from a Google Cloud project with both APIs enabled).
    speechKey: get('GOOGLE_CLOUD_API_KEY', ''),
    sttBase: get('GOOGLE_STT_BASE_URL', 'https://speech.googleapis.com'),
    ttsBase: get('GOOGLE_TTS_BASE_URL', 'https://texttospeech.googleapis.com'),
    sttModel: get('GOOGLE_STT_MODEL', 'latest_short'),
    ttsVoiceTa: get('GOOGLE_TTS_VOICE_TA', 'ta-IN-Chirp3-HD-Aoede'),
    ttsVoiceEn: get('GOOGLE_TTS_VOICE_EN', 'en-IN-Chirp3-HD-Aoede'),
    ttsRate: Math.max(0.25, Math.min(2, Number(get('GOOGLE_TTS_RATE', 1)))),
    // Sarvam AI speech-to-text (Indian languages). When its key is set it hears the owner instead of Google
    // (STT_PROVIDER=google keeps Google even then).
    sarvamKey: get('SARVAM_API_KEY', ''),
    sarvamBase: get('SARVAM_BASE_URL', 'https://api.sarvam.ai'),
    sarvamModel: get('SARVAM_STT_MODEL', 'saaras:v4'),
    sarvamMode: get('SARVAM_STT_MODE', 'codemix'),
    sttProvider: get('STT_PROVIDER', 'auto'),
    // Sarvam AI text-to-speech (Bulbul): natural Indian voices. Used for spoken replies when the Sarvam key is set
    // (TTS_PROVIDER=google keeps Google Cloud).
    sarvamTtsModel: get('SARVAM_TTS_MODEL', 'bulbul:v4-flash'),
    sarvamSpeakerTa: get('SARVAM_TTS_SPEAKER_TA', 'gokul_ta_narration'),
    sarvamSpeakerEn: get('SARVAM_TTS_SPEAKER_EN', ''),
    sarvamTtsPace: Math.max(0.5, Math.min(2, Number(get('SARVAM_TTS_PACE', 1.05)))),
    ttsProvider: get('TTS_PROVIDER', 'auto'),
    // OpenRouter for Stocky's typed conversation (any model there, including free ones). When its key is set it is
    // used instead of Gemini for text (TEXT_PROVIDER=gemini keeps Gemini). Real-time voice always needs Gemini.
    openRouterKey: get('OPENROUTER_API_KEY', ''),
    openRouterBase: get('OPENROUTER_BASE_URL', 'https://openrouter.ai/api/v1'),
    openRouterModel: get('OPENROUTER_MODEL', 'openrouter/free'),
    textProvider: get('TEXT_PROVIDER', 'auto'),
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
