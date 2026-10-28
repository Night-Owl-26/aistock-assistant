// OpenRouter (https://openrouter.ai) as the model behind Stocky's typed conversation.
// The app speaks Gemini's request format to this server; here it is translated to OpenRouter's
// OpenAI-style chat format and the answer translated back, so the app and its tools need no change.

const lowerTypes = (schema) => {
  if (Array.isArray(schema)) return schema.map(lowerTypes)
  if (schema === null || typeof schema !== 'object') return schema
  const out = {}
  for (const [k, v] of Object.entries(schema)) out[k] = k === 'type' && typeof v === 'string' ? v.toLowerCase() : lowerTypes(v)
  return out
}

/** Gemini generateContent request (already sanitised) -> OpenAI chat/completions request. */
export function toChat(req, model) {
  const messages = []
  const system = (req.systemInstruction?.parts ?? []).map(p => p?.text ?? '').join('\n').trim()
  if (system) messages.push({ role: 'system', content: system })
  let seq = 0
  const waiting = new Map() // function name -> ids of calls not answered yet, in order
  for (const c of req.contents ?? []) {
    const parts = Array.isArray(c?.parts) ? c.parts : []
    if (c.role === 'model') {
      const text = parts.filter(p => typeof p?.text === 'string').map(p => p.text).join('')
      const calls = parts.filter(p => p?.functionCall?.name).map(p => {
        const id = String(p.functionCall.id || `call_${++seq}`)
        if (!waiting.has(p.functionCall.name)) waiting.set(p.functionCall.name, [])
        waiting.get(p.functionCall.name).push(id)
        return { id, type: 'function', function: { name: p.functionCall.name, arguments: JSON.stringify(p.functionCall.args ?? {}) } }
      })
      const m = { role: 'assistant', content: text || (calls.length ? null : '') }
      if (calls.length) m.tool_calls = calls
      messages.push(m)
    } else {
      for (const p of parts) {
        if (p?.functionResponse?.name) {
          const name = p.functionResponse.name
          const id = String(p.functionResponse.id || waiting.get(name)?.shift() || `call_${++seq}`)
          const r = p.functionResponse.response
          const content = typeof r?.result === 'string' ? r.result : JSON.stringify(r ?? {})
          messages.push({ role: 'tool', tool_call_id: id, content })
        }
      }
      const text = parts.filter(p => typeof p?.text === 'string').map(p => p.text).join('\n')
      if (text) messages.push({ role: 'user', content: text })
    }
  }
  const out = { model, messages }
  const decls = (req.tools ?? []).flatMap(t => t?.functionDeclarations ?? [])
  if (decls.length) {
    out.tools = decls.map(d => ({ type: 'function', function: { name: d.name, description: d.description ?? '', parameters: lowerTypes(d.parameters ?? { type: 'object', properties: {} }) } }))
    out.tool_choice = 'auto'
  }
  const g = req.generationConfig ?? {}
  if (typeof g.temperature === 'number') out.temperature = g.temperature
  out.max_tokens = Math.min(Number(g.maxOutputTokens) || 2048, 4096)
  return out
}

/** OpenAI chat/completions answer -> Gemini generateContent answer (what the app reads). */
export function fromChat(body) {
  const msg = body?.choices?.[0]?.message
  if (!msg) return null
  const parts = []
  if (typeof msg.content === 'string' && msg.content.trim()) parts.push({ text: msg.content })
  for (const c of msg.tool_calls ?? []) {
    if (!c?.function?.name) continue
    let args = {}
    try { args = c.function.arguments ? JSON.parse(c.function.arguments) : {} } catch { args = {} }
    parts.push({ functionCall: { id: c.id, name: c.function.name, args } })
  }
  if (!parts.length) parts.push({ text: '' })
  const u = body.usage ?? {}
  return {
    candidates: [{ content: { role: 'model', parts }, finishReason: 'STOP' }],
    usageMetadata: { promptTokenCount: u.prompt_tokens ?? 0, candidatesTokenCount: u.completion_tokens ?? 0 },
    modelVersion: body.model,
  }
}

export function openRouterApi(cfg, fetchImpl = fetch) {
  return {
    configured: () => cfg.openRouterKey.length > 0,
    /** Same contract as geminiApi.generate: returns { status, body } with a Gemini-shaped body. */
    async generate(model, request) {
      const r = await fetchImpl(`${cfg.openRouterBase}/chat/completions`, {
        method: 'POST', signal: AbortSignal.timeout(90_000),
        headers: { 'content-type': 'application/json', authorization: `Bearer ${cfg.openRouterKey}`, 'x-title': 'Follower', 'http-referer': 'https://github.com/Night-Owl-26/aistock-assistant' },
        body: JSON.stringify(toChat(request, model)),
      })
      const text = await r.text()
      let body; try { body = JSON.parse(text) } catch { body = { error: { message: text.slice(0, 300) } } }
      if (!r.ok) return { status: r.status, body: { error: { message: body?.error?.message || `OpenRouter answered ${r.status}` } } }
      // OpenRouter can answer 200 with an error inside (for example when the chosen free model is busy).
      if (body?.error) return { status: Number(body.error.code) >= 400 ? Number(body.error.code) : 502, body: { error: { message: body.error.message || 'OpenRouter error' } } }
      const g = fromChat(body)
      if (!g) return { status: 502, body: { error: { message: 'OpenRouter returned no answer' } } }
      return { status: 200, body: g }
    },
  }
}
