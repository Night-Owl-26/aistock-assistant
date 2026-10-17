// A stand-in for Google's API, for the emulator test: answers every chat with a fixed sentence.
import http from 'node:http'
const port = Number(process.env.PORT ?? 9090)
http.createServer((req, res) => {
  let body = ''
  req.on('data', c => { body += c })
  req.on('end', () => {
    const send = (code, o) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(o)) }
    if (req.url.includes(':generateContent')) return send(200, { candidates: [{ content: { role: 'model', parts: [{ text: 'Vanakkam from the test server!' }] }, finishReason: 'STOP' }], usageMetadata: { promptTokenCount: 10, candidatesTokenCount: 5 } })
    if (req.url.startsWith('/v1beta/models')) return send(200, { models: [{ name: 'models/gemini-flash-latest', supportedGenerationMethods: ['generateContent'] }] })
    if (req.url.endsWith('/auth_tokens')) return send(200, { name: 'auth_tokens/test', expireTime: new Date(Date.now() + 600000).toISOString() })
    send(404, { error: { message: 'not here' } })
  })
}).listen(port, () => console.log('fake gemini on', port))
