import { test } from 'node:test'
import assert from 'node:assert/strict'
import { speechApi, wavFromPcm } from '../src/speech.js'
import { loadConfig } from '../src/config.js'

const base = { DATABASE_URL: 'postgres://x', JWT_SECRET: 'x'.repeat(40) }

function fake(reply) {
  const calls = []
  const f = async (url, init) => {
    calls.push({ url: String(url), init })
    return new Response(JSON.stringify(reply(String(url), init)), { status: reply.status ?? 200, headers: { 'content-type': 'application/json' } })
  }
  f.calls = calls
  return f
}

test('WAV header is valid 16 kHz mono 16-bit', () => {
  const w = wavFromPcm(Buffer.alloc(10))
  assert.equal(w.length, 54)
  assert.equal(w.toString('ascii', 8, 12), 'WAVE')
  assert.equal(w.readUInt16LE(22), 1)
  assert.equal(w.readUInt16LE(34), 16)
  assert.equal(w.readUInt32LE(40), 10)
})

import { toChat, fromChat, openRouterApi } from '../src/openrouter.js'

test('OpenRouter: Gemini-format conversation with tools is translated both ways', async () => {
  const req = {
    systemInstruction: { parts: [{ text: 'You are Stocky.' }] },
    contents: [
      { role: 'user', parts: [{ text: 'Shirt XL stock evlo?' }] },
      { role: 'model', parts: [{ functionCall: { name: 'find_products', args: { query: 'shirt xl' } } }] },
      { role: 'user', parts: [{ functionResponse: { name: 'find_products', response: { result: 'Shirt XL: 10 pieces' } } }] },
    ],
    tools: [{ functionDeclarations: [{ name: 'find_products', description: 'Look up', parameters: { type: 'OBJECT', properties: { query: { type: 'STRING', description: 'q' } } } }] }],
    generationConfig: { temperature: 0.3, maxOutputTokens: 9999, thinkingConfig: { thinkingBudget: 0 } },
  }
  const c = toChat(req, 'openrouter/free')
  assert.equal(c.model, 'openrouter/free')
  assert.deepEqual(c.messages[0], { role: 'system', content: 'You are Stocky.' })
  assert.deepEqual(c.messages[1], { role: 'user', content: 'Shirt XL stock evlo?' })
  assert.equal(c.messages[2].role, 'assistant')
  const id = c.messages[2].tool_calls[0].id
  assert.equal(c.messages[2].tool_calls[0].function.arguments, '{"query":"shirt xl"}')
  assert.deepEqual(c.messages[3], { role: 'tool', tool_call_id: id, content: 'Shirt XL: 10 pieces' })
  assert.equal(c.tools[0].function.parameters.type, 'object')
  assert.equal(c.tools[0].function.parameters.properties.query.type, 'string')
  assert.equal(c.max_tokens, 4096)
  assert.equal(c.thinkingConfig, undefined)

  const g = fromChat({ model: 'x', choices: [{ message: { content: null, tool_calls: [{ id: 'c9', type: 'function', function: { name: 'propose_sale', arguments: '{"product":"shirt","size":"XL","quantity":2}' } }] } }], usage: { prompt_tokens: 50, completion_tokens: 7 } })
  assert.deepEqual(g.candidates[0].content.parts[0], { functionCall: { id: 'c9', name: 'propose_sale', args: { product: 'shirt', size: 'XL', quantity: 2 } } })
  assert.equal(g.usageMetadata.promptTokenCount, 50)
  assert.equal(fromChat({ choices: [{ message: { content: 'Shirt XL 10 irukku.' } }] }).candidates[0].content.parts[0].text, 'Shirt XL 10 irukku.')
})

test('OpenRouter: key in the header only, errors passed on', async () => {
  const cfg = loadConfig({ ...base, OPENROUTER_API_KEY: 'or-key', OPENROUTER_BASE_URL: 'https://or.test/api/v1' })
  const seen = []
  const ok = async (url, init) => { seen.push({ url, init }); return new Response(JSON.stringify({ choices: [{ message: { content: 'Vanakkam' } }] }), { status: 200 }) }
  const r = await openRouterApi(cfg, ok).generate('openrouter/free', { contents: [{ role: 'user', parts: [{ text: 'hi' }] }] })
  assert.equal(r.status, 200)
  assert.equal(r.body.candidates[0].content.parts[0].text, 'Vanakkam')
  assert.equal(seen[0].url, 'https://or.test/api/v1/chat/completions')
  assert.equal(seen[0].init.headers.authorization, 'Bearer or-key')
  assert.ok(!seen[0].init.body.includes('or-key'))
  const bad = async () => new Response(JSON.stringify({ error: { message: 'No auth credentials found', code: 401 } }), { status: 401 })
  const e = await openRouterApi(cfg, bad).generate('m', { contents: [] })
  assert.equal(e.status, 401)
  assert.equal(e.body.error.message, 'No auth credentials found')
  const busy = async () => new Response(JSON.stringify({ error: { message: 'Provider returned error', code: 429 } }), { status: 200 })
  assert.equal((await openRouterApi(cfg, busy).generate('m', { contents: [] })).status, 429)
})

test('Gemini hears and speaks with the Gemini key, in the voice the owner picked', async () => {
  const cfg = loadConfig({ ...base, GEMINI_API_KEY: 'gk', GEMINI_BASE_URL: 'https://gem.test', GEMINI_TEXT_MODEL: 'gemini-flash-lite-latest' })
  const calls = []
  const pcmOut = Buffer.alloc(4800, 2)
  const f = async (url, init) => {
    calls.push({ url: String(url), body: init?.body ? JSON.parse(init.body) : null, key: init?.headers?.['x-goog-api-key'] })
    const json = (o) => new Response(JSON.stringify(o), { status: 200 })
    if (String(url).includes('/models?')) return json({ models: [
      { name: 'models/gemini-flash-lite-latest', supportedGenerationMethods: ['generateContent'] },
      { name: 'models/gemini-pro-tts', supportedGenerationMethods: ['generateContent'] },
      { name: 'models/gemini-flash-tts', supportedGenerationMethods: ['generateContent'] },
    ] })
    if (String(url).includes('gemini-flash-tts:generateContent')) return json({ candidates: [{ content: { parts: [{ inlineData: { mimeType: 'audio/L16;codec=pcm;rate=24000', data: pcmOut.toString('base64') } }] } }] })
    return json({ candidates: [{ content: { parts: [{ text: 'Raju-க்கு 2 XL shirt cash\n' }] } }] })
  }
  const api = speechApi(cfg, f)
  assert.equal(api.sttProvider(), 'gemini')
  assert.equal(api.ttsProvider(), 'gemini')
  const heard = await api.recognize({ audio: Buffer.alloc(3200).toString('base64'), language: 'ta-IN', phrases: ['Shirt XL', 'Raju'] })
  assert.equal(heard.status, 200)
  assert.equal(heard.text, 'Raju-க்கு 2 XL shirt cash')
  assert.match(calls[0].url, /gemini-flash-lite-latest:generateContent$/)
  assert.equal(calls[0].key, 'gk')
  assert.equal(calls[0].body.contents[0].parts[0].inlineData.mimeType, 'audio/wav')
  assert.match(calls[0].body.contents[0].parts[1].text, /Shirt XL, Raju/)

  const spoken = await api.synthesize({ text: 'Shirt XL இப்போ 10 பீஸ் இருக்கு.', language: 'ta-IN' })
  assert.equal(spoken.status, 200)
  const wav = Buffer.from(spoken.audio, 'base64')
  assert.equal(wav.toString('ascii', 0, 4), 'RIFF')
  assert.equal(wav.readUInt32LE(24), 24000)
  assert.equal(wav.length, 44 + pcmOut.length)
  const ttsCall = calls.find(c => c.url.includes('gemini-flash-tts'))
  assert.deepEqual(ttsCall.body.generationConfig.responseModalities, ['AUDIO'])
  assert.equal(ttsCall.body.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName, 'Kore')
  // The voice the owner picked is used; a name Gemini does not have falls back to the default.
  await api.synthesize({ text: 'Hello', language: 'en-IN', voice: 'Puck' })
  assert.equal(calls.filter(c => c.url.includes('gemini-flash-tts')).at(-1).body.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName, 'Puck')
  await api.synthesize({ text: 'Hello', language: 'en-IN', voice: 'NotAVoice' })
  assert.equal(calls.filter(c => c.url.includes('gemini-flash-tts')).at(-1).body.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName, 'Kore')
})

test('Whisper on the server hears when it is running; replies are still spoken by Gemini', async () => {
  const cfg = loadConfig({ ...base, WHISPER_URL: 'http://whisper:9000/', SARVAM_API_KEY: 's', GEMINI_API_KEY: 'g', STT_PROVIDER: 'whisper' })
  const calls = []
  const f = async (url, init) => { calls.push({ url: String(url), body: JSON.parse(init.body) }); return new Response(JSON.stringify({ text: ' சரி  2 XL shirt ', took: 1.2 }), { status: 200 }) }
  const api = speechApi(cfg, f)
  assert.equal(api.sttProvider(), 'whisper')
  assert.equal(api.ttsProvider(), 'gemini', 'Whisper does not speak: Gemini does')
  // A Sarvam key left in an old .env is ignored: only Gemini and Whisper are used.
  assert.equal(speechApi(loadConfig({ ...base, GEMINI_API_KEY: 'g', SARVAM_API_KEY: 's', GOOGLE_CLOUD_API_KEY: 'c' })).sttProvider(), 'gemini')
  assert.equal(speechApi(loadConfig({ ...base, SARVAM_API_KEY: 's' })).ttsConfigured(), false)
  const r = await api.recognize({ audio: 'AAAA', language: 'ta-IN', phrases: ['Shirt XL', 'Raju'] })
  assert.equal(r.text, 'சரி 2 XL shirt')
  assert.equal(calls[0].url, 'http://whisper:9000/transcribe')
  assert.deepEqual(calls[0].body, { audio: 'AAAA', language: 'ta-IN', prompt: 'Shirt XL, Raju' })
  const down = async () => new Response(JSON.stringify({ error: 'audio too long' }), { status: 400 })
  assert.equal((await speechApi(cfg, down).recognize({ audio: 'AAAA', language: 'ta-IN', phrases: [] })).error, 'audio too long', 'the chosen provider\'s own error is reported')
  // Without WHISPER_URL, asking for whisper gives nothing rather than a broken provider.
  assert.equal(speechApi(loadConfig({ ...base, STT_PROVIDER: 'whisper' })).sttProvider(), null)
})

test('Gemini voice is kept steady, and a model that refuses the steadiness settings still speaks', async () => {
  const cfg = loadConfig({ ...base, GEMINI_API_KEY: 'gk', GEMINI_BASE_URL: 'https://gem.test', GEMINI_TTS_MODEL: 'tts-model', TTS_PROVIDER: 'gemini' })
  const bodies = []
  const f = async (url, init) => {
    const b = JSON.parse(init.body); bodies.push(b)
    if (b.generationConfig.seed !== undefined && bodies.length === 1) return new Response(JSON.stringify({ error: { message: 'Unknown field seed' } }), { status: 400 })
    return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ inlineData: { mimeType: 'audio/L16;rate=24000', data: Buffer.alloc(8).toString('base64') } }] } }] }), { status: 200 })
  }
  const api = speechApi(cfg, f)
  const r = await api.synthesize({ text: 'வணக்கம்', language: 'ta-IN' })
  assert.equal(r.status, 200)
  assert.equal(bodies[0].generationConfig.seed, 7)
  assert.equal(bodies[0].generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName, 'Kore')
  assert.equal(bodies[1].generationConfig.seed, undefined, 'asked again without the settings')
  await api.synthesize({ text: 'Hello', language: 'en-IN' })
  assert.equal(bodies.length, 3, 'not sent again after the model refused them')
})


test('Gemini rate limited: Whisper hears instead, and Gemini rests instead of slowing every request', async () => {
  const cfg = loadConfig({ ...base, WHISPER_URL: 'http://whisper:9000', GEMINI_API_KEY: 'g', GEMINI_BASE_URL: 'https://gemini.test' })
  const calls = []
  const f = async (url) => {
    url = String(url); calls.push(url)
    if (url.startsWith('https://gemini.test')) return new Response(JSON.stringify({ error: { message: 'Resource has been exhausted' } }), { status: 429 })
    return new Response(JSON.stringify({ text: 'Shirt XL evlo irukku' }), { status: 200 })
  }
  const api = speechApi(cfg, f)
  assert.equal(api.sttProvider(), 'gemini')
  let r = await api.recognize({ audio: 'AAAA', language: 'ta-IN', phrases: [] })
  assert.equal(r.provider, 'whisper')
  assert.equal(r.text, 'Shirt XL evlo irukku')
  assert.match(api.resting()['stt:gemini'], /429/)
  calls.length = 0
  r = await api.recognize({ audio: 'AAAA', language: 'ta-IN', phrases: [] })
  assert.ok(calls.every(u => !u.startsWith('https://gemini.test')), 'no time lost on Gemini while it rests')
})

test('a Gemini voice model out of its limit rests and the next one speaks; hearing is not stopped by it', async () => {
  const cfg = loadConfig({ ...base, GEMINI_API_KEY: 'g', GEMINI_BASE_URL: 'https://gem.test' })
  const calls = []
  const f = async (url) => {
    url = String(url); calls.push(url)
    const json = (status, o) => new Response(JSON.stringify(o), { status })
    if (url.includes('/models?')) return json(200, { models: [
      { name: 'models/gemini-flash-tts', supportedGenerationMethods: ['generateContent'] },
      { name: 'models/gemini-pro-tts', supportedGenerationMethods: ['generateContent'] },
    ] })
    if (url.includes('gemini-flash-tts:')) return json(429, { error: { message: 'Quota exceeded for requests per day' } })
    if (url.includes('gemini-pro-tts:')) return json(200, { candidates: [{ content: { parts: [{ inlineData: { mimeType: 'audio/L16;rate=24000', data: Buffer.alloc(48).toString('base64') } }] } }] })
    return json(200, { candidates: [{ content: { parts: [{ text: 'Shirt XL evlo' }] } }] })
  }
  const api = speechApi(cfg, f)
  let r = await api.synthesize({ text: 'Hello', language: 'en-IN' })
  assert.equal(r.status, 200)
  assert.equal(r.model, 'gemini-pro-tts')
  assert.match(api.resting()['tts-model:gemini-flash-tts'], /429/)
  calls.length = 0
  r = await api.synthesize({ text: 'Again', language: 'en-IN' })
  assert.equal(r.model, 'gemini-pro-tts')
  assert.ok(calls.every(u => !u.includes('gemini-flash-tts:')), 'the resting model is not asked again')
  // Hearing still uses Gemini.
  assert.equal(api.sttProvider(), 'gemini')
  assert.equal((await api.recognize({ audio: 'AAAA', language: 'ta-IN', phrases: [] })).provider, 'gemini')
})

test('a Tamil reply in English letters is written in Tamil script before it is spoken', async () => {
  const cfg = loadConfig({ ...base, GEMINI_API_KEY: 'g', GEMINI_BASE_URL: 'https://gem.test', GEMINI_TTS_MODEL: 'tts-model', GEMINI_TEXT_MODEL: 'text-model' })
  const spoken = []
  let rewrites = 0
  const f = async (url, init) => {
    const b = JSON.parse(init.body)
    if (String(url).includes('text-model')) { rewrites++; return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: 'Shirt XL stock 10 இருக்கு.' }] } }] }), { status: 200 }) }
    spoken.push(b.contents[0].parts[0].text)
    return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ inlineData: { mimeType: 'audio/L16;rate=24000', data: Buffer.alloc(8).toString('base64') } }] } }] }), { status: 200 })
  }
  const api = speechApi(cfg, f)
  assert.equal((await api.synthesize({ text: 'Shirt XL stock 10 irukku.', language: 'ta-IN' })).status, 200)
  assert.equal(spoken.at(-1), 'Shirt XL stock 10 இருக்கு.')
  await api.synthesize({ text: 'Shirt XL stock 10 irukku.', language: 'ta-IN' })
  assert.equal(rewrites, 1, 'the same reply is not rewritten twice')
  // English replies and text already in Tamil script are spoken as they are.
  await api.synthesize({ text: 'Shirt XL has 10 pieces.', language: 'en-IN' })
  assert.equal(spoken.at(-1), 'Shirt XL has 10 pieces.')
  await api.synthesize({ text: 'சரி, 10 இருக்கு', language: 'ta-IN' })
  assert.equal(rewrites, 1)
})
