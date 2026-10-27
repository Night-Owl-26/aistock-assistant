// Google Cloud Speech-to-Text and Text-to-Speech, called with the server's key only.
// The app sends recorded audio or reply text here; the key never reaches the phone.

export const SPEECH_LANGS = ['ta-IN', 'en-IN']

const parse = async (r) => {
  const text = await r.text()
  try { return JSON.parse(text) } catch { return { error: { message: text.slice(0, 300) } } }
}

/** Tamil script anywhere in the text means a Tamil voice; otherwise Indian English (which also reads Tanglish). */
export function speechLanguage(text, asked) {
  if (SPEECH_LANGS.includes(asked)) return asked
  return /[஀-௿]/.test(text) ? 'ta-IN' : 'en-IN'
}

/** Rough spoken length of a reply, used to meter Text-to-Speech against the voice minutes. */
export function ttsSeconds(text) {
  return Math.max(1, Math.ceil([...text].length / 14))
}

/** 16 kHz mono 16-bit PCM wrapped in a WAV header, the format Sarvam reads best. */
export function wavFromPcm(pcm, rate = 16000) {
  const h = Buffer.alloc(44)
  h.write('RIFF', 0); h.writeUInt32LE(36 + pcm.length, 4); h.write('WAVE', 8)
  h.write('fmt ', 12); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22)
  h.writeUInt32LE(rate, 24); h.writeUInt32LE(rate * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34)
  h.write('data', 36); h.writeUInt32LE(pcm.length, 40)
  return Buffer.concat([h, pcm])
}

export function speechApi(cfg, fetchImpl = fetch) {
  const headers = () => ({ 'content-type': 'application/json', 'x-goog-api-key': cfg.speechKey })
  const sttProvider = () => {
    if (cfg.sttProvider === 'google') return cfg.speechKey ? 'google' : null
    if (cfg.sttProvider === 'sarvam') return cfg.sarvamKey ? 'sarvam' : null
    return cfg.sarvamKey ? 'sarvam' : cfg.speechKey ? 'google' : null
  }

  /** Sarvam AI speech-to-text: one short clip (under about 30 seconds) per request. */
  async function recognizeSarvam({ audio, language, phrases }) {
    const form = new FormData()
    form.append('file', new Blob([wavFromPcm(Buffer.from(audio, 'base64'))], { type: 'audio/wav' }), 'speech.wav')
    form.append('model', cfg.sarvamModel)
    form.append('language_code', language)
    if (cfg.sarvamMode) form.append('mode', cfg.sarvamMode)
    // Product and customer names help it spell them the way the shop does (at most 50, 64 characters each).
    const terms = phrases.filter(p => [...p].length <= 64).slice(0, 50)
    if (terms.length && cfg.sarvamModel.startsWith('saaras:v4')) form.append('keyterms', JSON.stringify(terms))
    const r = await fetchImpl(`${cfg.sarvamBase}/speech-to-text`, {
      method: 'POST', headers: { 'api-subscription-key': cfg.sarvamKey }, body: form, signal: AbortSignal.timeout(60_000),
    })
    const body = await parse(r)
    if (!r.ok) return { status: r.status, error: body?.error?.message || body?.message || `speech-to-text failed (${r.status})` }
    return { status: 200, text: String(body.transcript ?? '').replace(/\s+/g, ' ').trim(), language: body.language_code || language, confidence: null }
  }

  return {
    sttProvider,
    sttModel: () => sttProvider() === 'sarvam' ? cfg.sarvamModel : cfg.sttModel,
    sttConfigured: () => sttProvider() !== null,
    ttsConfigured: () => cfg.speechKey.length > 0,
    configured: () => sttProvider() !== null || cfg.speechKey.length > 0,

    /**
     * 16 kHz mono 16-bit PCM (base64) -> text. Product and customer names are passed as phrase hints so they are
     * recognised as the shop writes them. Returns { status, text, language, confidence, error }.
     */
    async recognize({ audio, language, phrases }) {
      if (sttProvider() === 'sarvam') return recognizeSarvam({ audio, language, phrases })
      const other = SPEECH_LANGS.find(l => l !== language)
      const config = {
        encoding: 'LINEAR16', sampleRateHertz: 16000, audioChannelCount: 1, languageCode: language,
        model: cfg.sttModel, maxAlternatives: 1, profanityFilter: false,
        ...(phrases.length ? { speechContexts: [{ phrases, boost: 8 }] } : {}),
      }
      const send = (withAlt) => fetchImpl(`${cfg.sttBase}/v1p1beta1/speech:recognize`, {
        method: 'POST', headers: headers(), signal: AbortSignal.timeout(60_000),
        body: JSON.stringify({ config: withAlt ? { ...config, alternativeLanguageCodes: [other] } : config, audio: { content: audio } }),
      })
      let r = await send(true)
      let body = await parse(r)
      // Some models do not accept a second language: ask again with one.
      if (r.status === 400) { r = await send(false); body = await parse(r) }
      if (!r.ok) return { status: r.status, error: body?.error?.message || `speech-to-text failed (${r.status})` }
      const results = Array.isArray(body.results) ? body.results : []
      const text = results.map(x => x?.alternatives?.[0]?.transcript ?? '').join(' ').replace(/\s+/g, ' ').trim()
      const conf = results.map(x => x?.alternatives?.[0]?.confidence).filter(n => typeof n === 'number')
      return {
        status: 200, text,
        language: (results.find(x => x.languageCode)?.languageCode || language),
        confidence: conf.length ? conf.reduce((a, b) => a + b, 0) / conf.length : null,
      }
    },

    /** Text -> spoken audio (WAV, 16-bit PCM, 24 kHz). Falls back to Google's default voice for the language. */
    async synthesize({ text, language }) {
      const name = language === 'ta-IN' ? cfg.ttsVoiceTa : cfg.ttsVoiceEn
      const send = (voice) => fetchImpl(`${cfg.ttsBase}/v1/text:synthesize`, {
        method: 'POST', headers: headers(), signal: AbortSignal.timeout(30_000),
        body: JSON.stringify({ input: { text }, voice, audioConfig: { audioEncoding: 'LINEAR16', sampleRateHertz: 24000, speakingRate: cfg.ttsRate } }),
      })
      let r = await send(name ? { languageCode: language, name } : { languageCode: language })
      let body = await parse(r)
      if (!r.ok && name) { r = await send({ languageCode: language }); body = await parse(r) }
      if (!r.ok || typeof body.audioContent !== 'string') return { status: r.ok ? 502 : r.status, error: body?.error?.message || `text-to-speech failed (${r.status})` }
      return { status: 200, audio: body.audioContent }
    },
  }
}
