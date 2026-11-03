// Speech for Stocky: Gemini (hearing and speaking) and self-hosted Whisper (hearing), with the server's keys only.
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

/** 16 kHz mono 16-bit PCM wrapped in a WAV header. */
export function wavFromPcm(pcm, rate = 16000) {
  const h = Buffer.alloc(44)
  h.write('RIFF', 0); h.writeUInt32LE(36 + pcm.length, 4); h.write('WAVE', 8)
  h.write('fmt ', 12); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22)
  h.writeUInt32LE(rate, 24); h.writeUInt32LE(rate * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34)
  h.write('data', 36); h.writeUInt32LE(pcm.length, 40)
  return Buffer.concat([h, pcm])
}

/** Gemini's prebuilt voices (the same names work for spoken replies and live voice). The owner picks one in the app. */
export const GEMINI_VOICES = ['Zephyr', 'Puck', 'Charon', 'Kore', 'Fenrir', 'Leda', 'Orus', 'Aoede', 'Callirrhoe', 'Autonoe', 'Enceladus', 'Iapetus',
  'Umbriel', 'Algieba', 'Despina', 'Erinome', 'Algenib', 'Rasalgethi', 'Laomedeia', 'Achernar', 'Alnilam', 'Schedar', 'Gacrux', 'Pulcherrima',
  'Achird', 'Zubenelgenubi', 'Vindemiatrix', 'Sadachbia', 'Sadaltager', 'Sulafat']

export function speechApi(cfg, fetchImpl = fetch) {
  const has = { whisper: () => !!cfg.whisperUrl, gemini: () => !!cfg.geminiKey }
  // Only Gemini (with the Gemini key) and Whisper (free, unlimited, on this server) are used. Gemini hears first unless
  // STT_PROVIDER=whisper; if it fails (rate limit) Whisper hears instead and Gemini rests for a while. Replies are
  // always spoken by Gemini, in the voice the owner picked in the app.
  const down = new Map() // provider -> { until, reason }
  const isDown = (p) => { const d = down.get(p); if (d && d.until > Date.now()) return true; if (d) down.delete(p); return false }
  // Hearing and speaking rest separately: Gemini's voice limit running out must not stop Gemini from hearing.
  const chain = (kind, wanted, order) => {
    const usable = order.filter(p => has[p]())
    const first = usable.includes(wanted) ? [wanted, ...usable.filter(p => p !== wanted)] : usable
    const up = first.filter(p => !isDown(`${kind}:${p}`))
    return up.length ? up : first // all resting: try them anyway
  }
  const STT_ORDER = ['gemini', 'whisper']
  const TTS_ORDER = ['gemini']
  /**
   * Out of credit: rest 6 hours (a balance does not come back by itself). Wrong key or rate limited: 30 minutes.
   * Server error or timeout: 2 minutes.
   */
  const rest = (p, r) => {
    const st = Number(r.status) || 0
    const credit = st === 402 || /credit|balance|quota exceeded|insufficient/i.test(String(r.error ?? ''))
    const ms = credit && st !== 429 ? 6 * 60 * 60_000 : [401, 402, 403, 429].includes(st) ? 30 * 60_000 : (st >= 500 || st === 0 || st === 408) ? 2 * 60_000 : 0
    if (ms) down.set(p, { until: Date.now() + ms, reason: `${st || 'no answer'}: ${String(r.error ?? '').slice(0, 120)}` })
  }
  const attempt = async (fn) => {
    try { return await fn() } catch (err) { return { status: err?.name === 'TimeoutError' || err?.name === 'AbortError' ? 408 : 0, error: err?.message || String(err) } }
  }
  const sttProvider = () => chain('stt', cfg.sttProvider, STT_ORDER)[0] ?? null

  /** Whisper on this server's own CPU: free, unlimited, nothing leaves the server. */
  async function recognizeWhisper({ audio, language, phrases }) {
    const prompt = phrases.slice(0, 40).join(', ')
    const r = await fetchImpl(`${cfg.whisperUrl.replace(/\/$/, '')}/transcribe`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, signal: AbortSignal.timeout(90_000),
      body: JSON.stringify({ audio, language, prompt }),
    })
    const b = await parse(r)
    if (!r.ok) return { status: r.status, error: b?.error?.message || b?.error || `speech-to-text failed (${r.status})` }
    return { status: 200, text: String(b.text ?? '').replace(/\s+/g, ' ').trim(), language, confidence: null }
  }

  const gHeaders = () => ({ 'content-type': 'application/json', 'x-goog-api-key': cfg.geminiKey })
  const geminiText = (b) => (b?.candidates?.[0]?.content?.parts ?? []).filter(p => typeof p.text === 'string' && !p.thought).map(p => p.text).join('').trim()

  /** Gemini hears the recording and writes it down: Tamil in Tamil script, English words in English. */
  async function recognizeGemini({ audio, language, phrases }) {
    const model = cfg.geminiSttModel || cfg.textModel
    const wav = wavFromPcm(Buffer.from(audio, 'base64')).toString('base64')
    const names = phrases.slice(0, 80).join(', ')
    const prompt = `Transcribe this short recording of a shop owner in Tamil Nadu talking to their stock and billing app. ` +
      `They speak ${language === 'ta-IN' ? 'Tamil, often mixed with English' : 'English, Tamil or Tanglish'}. ` +
      `Write Tamil words in Tamil script and English words (product names, sizes, units, stock, bill, cash, credit) in English letters. ` +
      `Write numbers as digits. ${names ? `Names they may say: ${names}. ` : ''}` +
      `Output only what was said, nothing else. If nothing was said, output nothing.`
    const r = await fetchImpl(`${cfg.geminiBase}/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
      method: 'POST', headers: gHeaders(), signal: AbortSignal.timeout(20_000),
      body: JSON.stringify({ contents: [{ role: 'user', parts: [{ inlineData: { mimeType: 'audio/wav', data: wav } }, { text: prompt }] }], generationConfig: { temperature: 0, maxOutputTokens: 400 } }),
    })
    const b = await parse(r)
    if (!r.ok) return { status: r.status, error: b?.error?.message || `speech-to-text failed (${r.status})` }
    const text = geminiText(b).replace(/^["'“]|["'”]$/g, '').replace(/\s+/g, ' ').trim()
    return { status: 200, text, language, confidence: null }
  }

  let ttsModelsFound = null
  // A TTS model that is out of its limit (429) or not open to this key rests; the next TTS model speaks meanwhile.
  const modelDown = new Map()
  let lastTtsModel = null
  // The same seed and a low temperature keep Gemini's voice the same from one reply to the next (without them it can
  // change accent or tone each time).
  let steady = { temperature: 0.2, seed: 7 }
  /**
   * The Gemini TTS models to try, in order: the configured one, then every "tts" model this key may call (flash and
   * lite first, newest first). The one that answered last is tried first.
   */
  async function geminiTtsModels() {
    if (!ttsModelsFound) {
      let names = []
      try {
        const r = await fetchImpl(`${cfg.geminiBase}/v1beta/models?pageSize=200`, { headers: gHeaders(), signal: AbortSignal.timeout(15_000) })
        const b = await parse(r)
        names = (b?.models ?? []).filter(m => (m.supportedGenerationMethods ?? []).includes('generateContent')).map(m => String(m.name).replace(/^models\//, '')).filter(n => /tts/i.test(n))
      } catch { /* the configured model may still work */ }
      const ver = (n) => Number(/(\d+(?:\.\d+)?)/.exec(n)?.[1] ?? 0)
      names.sort((a, b) => (/flash/i.test(b) - /flash/i.test(a)) || ver(b) - ver(a) || (/lite/i.test(b) - /lite/i.test(a)) || (/preview/i.test(a) - /preview/i.test(b)))
      ttsModelsFound = [...new Set([cfg.geminiTtsModel, ...names].filter(Boolean))]
      if (!names.length && !cfg.geminiTtsModel) ttsModelsFound = null // look again next time
    }
    const list = ttsModelsFound ?? []
    const up = list.filter(m => { const d = modelDown.get(m); return !d || d.until <= Date.now() })
    const ordered = (up.length ? up : list)
    return lastTtsModel && ordered.includes(lastTtsModel) ? [lastTtsModel, ...ordered.filter(m => m !== lastTtsModel)] : ordered
  }

  /** Gemini speaks the reply in a natural voice; returns a WAV (its audio comes as raw 16-bit PCM). */
  async function synthesizeGemini(args) {
    const models = await geminiTtsModels()
    if (!models.length) return { status: 503, error: 'This Gemini key has no text-to-speech model.' }
    let last = null
    for (const model of models) {
      const r = await attempt(() => synthesizeWith(model, args))
      if (r.status === 200) { lastTtsModel = model; modelDown.delete(model); return { ...r, model } }
      const st = Number(r.status) || 0
      if ([403, 404, 429].includes(st)) modelDown.set(model, { until: Date.now() + (st === 429 ? 30 : 360) * 60_000, reason: `${st}: ${String(r.error ?? '').slice(0, 120)}` })
      last = { ...r, model }
      if (![400, 403, 404, 429].includes(st)) break // a timeout or server error: another model would wait as long
    }
    return last
  }

  async function synthesizeWith(model, { text, language, voice }) {
    const voiceName = GEMINI_VOICES.includes(voice) ? voice : (GEMINI_VOICES.includes(cfg.geminiTtsVoice) ? cfg.geminiTtsVoice : 'Kore')
    // Only the reply itself: a style note in front of it ("Read this aloud ...") was sometimes spoken out loud too.
    const r = await fetchImpl(`${cfg.geminiBase}/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
      method: 'POST', headers: gHeaders(), signal: AbortSignal.timeout(20_000),
      body: JSON.stringify({
        contents: [{ role: 'user', parts: [{ text: text.slice(0, 2000) }] }],
        generationConfig: { responseModalities: ['AUDIO'], ...steady, speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName } } } },
      }),
    })
    const b = await parse(r)
    // A model that does not take the steadiness settings says 400: ask again without them, and stop sending them.
    if (r.status === 400 && Object.keys(steady).length) { steady = {}; return synthesizeWith(model, { text, language, voice }) }
    if (!r.ok) {
      if (r.status === 404) ttsModelsFound = null // a model went away: look again next time
      return { status: r.status, error: b?.error?.message || `text-to-speech failed (${r.status})` }
    }
    const part = (b?.candidates?.[0]?.content?.parts ?? []).find(p => p?.inlineData?.data)
    if (!part) return { status: 502, error: 'Gemini returned no audio' }
    const mime = String(part.inlineData.mimeType || '')
    const pcm = Buffer.from(part.inlineData.data, 'base64')
    if (pcm.subarray(0, 4).toString() === 'RIFF') return { status: 200, audio: part.inlineData.data }
    const rate = Number(/rate=(\d+)/.exec(mime)?.[1]) || 24000
    return { status: 200, audio: wavFromPcm(pcm, rate).toString('base64') }
  }

  const ttsProvider = () => ttsChain()[0] ?? null

  const hear = { whisper: recognizeWhisper, gemini: recognizeGemini }
  const speak = { gemini: synthesizeGemini }

  /**
   * The voice that answered last keeps answering while it works, so replies do not switch between voices (for
   * example back to a provider each time its rest ends while it still fails).
   */
  let lastVoice = null
  const ttsChain = () => {
    const c = chain('tts', cfg.ttsProvider, TTS_ORDER)
    return lastVoice && c.includes(lastVoice) && !isDown(`tts:${lastVoice}`) ? [lastVoice, ...c.filter(p => p !== lastVoice)] : c
  }

  /** Tries each provider in turn until one answers; a failing one rests for a while. */
  async function firstThatWorks(kind, order, impl, args, onOk = () => {}) {
    let first = null
    for (const p of order) {
      const r = await attempt(() => impl[p](args))
      if (r.status === 200) { down.delete(`${kind}:${p}`); onOk(p); return { ...r, provider: p } }
      rest(`${kind}:${p}`, r)
      first ??= { ...r, provider: p } // the preferred provider's error says the most ("not enough credits", ...)
    }
    return first ?? { status: 503, error: 'no speech service is available' }
  }

  return {
    ttsProvider,
    sttProvider,
    sttModel: () => ({ whisper: 'whisper', gemini: cfg.geminiSttModel || cfg.textModel })[sttProvider()] ?? '',
    sttConfigured: () => sttProvider() !== null,
    ttsConfigured: () => ttsProvider() !== null,
    configured: () => sttProvider() !== null || ttsProvider() !== null,
    /** Providers resting after a failure, with the reason (shown on /healthz so the owner sees "gemini: 429 ..."). */
    resting: () => Object.fromEntries([
      ...[...down.entries()].filter(([p]) => isDown(p)).map(([p, d]) => [p, d.reason]),
      ...[...modelDown.entries()].filter(([, d]) => d.until > Date.now()).map(([m, d]) => [`tts-model:${m}`, d.reason]),
    ]),

    /**
     * 16 kHz mono 16-bit PCM (base64) -> text. Product and customer names are passed as phrase hints so they are
     * recognised as the shop writes them. Returns { status, text, language, confidence, error, provider }.
     */
    recognize: (args) => firstThatWorks('stt', chain('stt', cfg.sttProvider, STT_ORDER), hear, args),

    /** Text -> spoken audio (WAV, 16-bit PCM). Returns { status, audio, error, provider }. */
    synthesize: (args) => firstThatWorks('tts', ttsChain(), speak, args, (p) => { lastVoice = p }),
  }
}
