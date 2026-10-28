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

test('Sarvam AI hears the owner when its key is set; key, model, language and names are sent', async () => {
  const cfg = loadConfig({ ...base, SARVAM_API_KEY: 'sarvam-key', SARVAM_BASE_URL: 'https://sarvam.test', GOOGLE_CLOUD_API_KEY: 'g' })
  const f = fake(() => ({ request_id: 'r1', transcript: 'Raju-க்கு  2 XL shirt  cash', language_code: 'ta-IN' }))
  const api = speechApi(cfg, f)
  assert.equal(api.sttProvider(), 'sarvam')
  assert.equal(api.sttModel(), 'saaras:v4')
  const pcm = Buffer.alloc(32000, 1)
  const r = await api.recognize({ audio: pcm.toString('base64'), language: 'ta-IN', phrases: ['Shirt XL', 'x'.repeat(65), 'Raju'] })
  assert.equal(r.status, 200)
  assert.equal(r.text, 'Raju-க்கு 2 XL shirt cash')
  assert.equal(r.language, 'ta-IN')
  const c = f.calls[0]
  assert.equal(c.url, 'https://sarvam.test/speech-to-text')
  assert.equal(c.init.headers['api-subscription-key'], 'sarvam-key')
  const form = c.init.body
  assert.equal(form.get('model'), 'saaras:v4')
  assert.equal(form.get('language_code'), 'ta-IN')
  assert.equal(form.get('mode'), 'codemix')
  assert.deepEqual(JSON.parse(form.get('keyterms')), ['Shirt XL', 'Raju'])
  const file = Buffer.from(await form.get('file').arrayBuffer())
  assert.equal(file.subarray(0, 4).toString(), 'RIFF')
  assert.equal(file.readUInt32LE(24), 16000)
  assert.equal(file.length, 44 + pcm.length)
})

test('Sarvam errors are reported, and STT_PROVIDER=google keeps Google', async () => {
  const cfg = loadConfig({ ...base, SARVAM_API_KEY: 'k', SARVAM_BASE_URL: 'https://sarvam.test' })
  const bad = async () => new Response(JSON.stringify({ error: { message: 'Invalid API key' } }), { status: 403 })
  const r = await speechApi(cfg, bad).recognize({ audio: 'AAAA', language: 'ta-IN', phrases: [] })
  assert.equal(r.status, 403)
  assert.equal(r.error, 'Invalid API key')
  assert.equal(speechApi(cfg).ttsProvider(), 'sarvam', 'a Sarvam key also reads replies aloud')

  const g = loadConfig({ ...base, SARVAM_API_KEY: 'k', GOOGLE_CLOUD_API_KEY: 'g', STT_PROVIDER: 'google' })
  assert.equal(speechApi(g).sttProvider(), 'google')
  assert.equal(speechApi(loadConfig(base)).sttConfigured(), false)
})

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

test('Sarvam reads replies aloud in a Tamil voice, falling back to the default voice', async () => {
  const cfg = loadConfig({ ...base, SARVAM_API_KEY: 'sk', SARVAM_BASE_URL: 'https://sarvam.test', GOOGLE_CLOUD_API_KEY: 'g' })
  const calls = []
  const f = async (url, init) => {
    const body = JSON.parse(init.body)
    calls.push({ url, body, key: init.headers['api-subscription-key'] })
    if (body.speaker === 'gokul_ta_narration') return new Response(JSON.stringify({ error: { message: 'speaker not found' } }), { status: 400 })
    return new Response(JSON.stringify({ request_id: 'x', audios: [Buffer.from('RIFFdata').toString('base64')] }), { status: 200 })
  }
  const api = speechApi(cfg, f)
  assert.equal(api.ttsProvider(), 'sarvam')
  const r = await api.synthesize({ text: 'Shirt XL இப்போ 10 பீஸ் இருக்கு.', language: 'ta-IN' })
  assert.equal(r.status, 200)
  assert.equal(Buffer.from(r.audio, 'base64').toString(), 'RIFFdata')
  assert.equal(calls[0].url, 'https://sarvam.test/text-to-speech')
  assert.equal(calls[0].key, 'sk')
  assert.equal(calls[0].body.language_code, 'ta-IN')
  assert.equal(calls[0].body.model, 'bulbul:v4-flash')
  assert.equal(calls[0].body.output_audio_codec, 'wav')
  assert.equal(calls[1].body.speaker, undefined)
  const denied = async () => new Response(JSON.stringify({ error: { message: 'Invalid API key' } }), { status: 403 })
  const d = await speechApi(cfg, denied).synthesize({ text: 'x', language: 'ta-IN' })
  assert.equal(d.status, 403)
  const g = loadConfig({ ...base, SARVAM_API_KEY: 'sk', GOOGLE_CLOUD_API_KEY: 'g', TTS_PROVIDER: 'google' })
  assert.equal(speechApi(g).ttsProvider(), 'google')
})

test('Without Sarvam or Google Cloud, Gemini hears and speaks with the Gemini key', async () => {
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
  // Sarvam still wins when its key is set; STT_PROVIDER=gemini keeps Gemini even then.
  assert.equal(speechApi(loadConfig({ ...base, GEMINI_API_KEY: 'g', SARVAM_API_KEY: 's' })).sttProvider(), 'sarvam')
  assert.equal(speechApi(loadConfig({ ...base, GEMINI_API_KEY: 'g', SARVAM_API_KEY: 's', STT_PROVIDER: 'gemini', TTS_PROVIDER: 'gemini' })).ttsProvider(), 'gemini')
})
