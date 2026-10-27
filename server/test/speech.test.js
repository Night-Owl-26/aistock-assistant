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
  assert.equal(speechApi(cfg).ttsConfigured(), false, 'Sarvam key alone gives speech-to-text only')

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
