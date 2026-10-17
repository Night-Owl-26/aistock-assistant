// The only place that talks to Google with the server's key.
export function geminiApi(cfg, fetchImpl = fetch) {
  const headers = () => ({ 'content-type': 'application/json', 'x-goog-api-key': cfg.geminiKey })
  return {
    configured: () => cfg.geminiKey.length > 0,

    /** Forwards a generateContent request. Returns { status, body(json|text) }. */
    async generate(model, request) {
      const r = await fetchImpl(`${cfg.geminiBase}/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
        method: 'POST', headers: headers(), body: JSON.stringify(request), signal: AbortSignal.timeout(75_000),
      })
      const text = await r.text()
      let body; try { body = JSON.parse(text) } catch { body = { error: { message: text.slice(0, 300) } } }
      return { status: r.status, body }
    },

    /** Short-lived token that can only open a Live session with the given model. */
    async liveToken(model, seconds) {
      const now = Date.now()
      const r = await fetchImpl(`${cfg.geminiBase}/v1beta/auth_tokens`, {
        method: 'POST', headers: headers(), signal: AbortSignal.timeout(20_000),
        body: JSON.stringify({
          uses: 1,
          expireTime: new Date(now + Math.min(seconds + 120, 30 * 60) * 1000).toISOString(),
          newSessionExpireTime: new Date(now + 90 * 1000).toISOString(),
          liveConnectConstraints: { model: `models/${model}`, config: { responseModalities: ['AUDIO'], sessionResumption: {} } },
        }),
      })
      const text = await r.text()
      let body; try { body = JSON.parse(text) } catch { body = { error: { message: text.slice(0, 300) } } }
      if (!r.ok || !body.name) {
        const err = new Error(body?.error?.message || `token request failed (${r.status})`)
        err.status = r.status
        throw err
      }
      return { token: body.name, expiresAt: body.expireTime }
    },
  }
}

/** Only function declarations may be sent through the proxy (no paid search/code tools, no cached content). */
export function sanitizeRequest(req) {
  if (typeof req !== 'object' || req === null || !Array.isArray(req.contents) || req.contents.length === 0) return null
  if (req.contents.length > 80) return null
  const out = { contents: req.contents }
  if (req.systemInstruction) out.systemInstruction = req.systemInstruction
  if (Array.isArray(req.tools)) {
    const decls = req.tools.flatMap(t => Array.isArray(t?.functionDeclarations) ? t.functionDeclarations : [])
    if (decls.length > 64) return null
    if (decls.length) out.tools = [{ functionDeclarations: decls }]
  }
  if (req.generationConfig && typeof req.generationConfig === 'object') {
    const g = req.generationConfig, c = {}
    if (typeof g.temperature === 'number') c.temperature = Math.max(0, Math.min(2, g.temperature))
    if (g.thinkingConfig && typeof g.thinkingConfig === 'object') {
      const t = {}
      if (typeof g.thinkingConfig.thinkingBudget === 'number') t.thinkingBudget = Math.max(0, Math.min(2048, g.thinkingConfig.thinkingBudget))
      if (['minimal', 'low', 'medium', 'high'].includes(g.thinkingConfig.thinkingLevel)) t.thinkingLevel = g.thinkingConfig.thinkingLevel
      c.thinkingConfig = t
    }
    c.maxOutputTokens = Math.min(Number(g.maxOutputTokens) || 2048, 4096)
    out.generationConfig = c
  } else out.generationConfig = { maxOutputTokens: 2048 }
  return out
}
