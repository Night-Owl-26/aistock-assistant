import WebSocket from 'ws'

/**
 * Real-time voice through this server. The phone talks to us; we talk to Google with the server's key.
 * Because every audio frame passes here, voice minutes are measured by the server itself (a modified app cannot
 * under-report), the model is always the one configured here, and no Google credential ever reaches a phone.
 */
export function registerLiveRelay(app, { cfg, pool, member, reserveLive, audit, WebSocketImpl = WebSocket }) {
  app.get('/v1/businesses/:id/ai/live', { websocket: true }, async (socket, req) => {
    const closeWith = (code, reason) => { try { socket.close(code, reason.slice(0, 120)) } catch { /* already closed */ } }
    // Messages that arrive while we are still checking the account are kept and sent once Google is connected.
    const early = []
    const keep = (data) => early.push(data)
    socket.on('message', keep)
    let session
    try {
      const { user } = await member(req, req.params.id)
      session = await reserveLive(req.params.id, user.id)
    } catch (e) {
      return closeWith(e.status === 401 ? 4401 : e.status === 402 ? 4402 : e.status === 429 ? 4429 : 4400, e.message || 'refused')
    }
    const started = Date.now()
    let finished = false
    const upstream = new WebSocketImpl(cfg.liveUpstreamUrl, { headers: { 'x-goog-api-key': cfg.geminiKey } })

    const finish = async (code, reason) => {
      if (finished) return
      finished = true
      clearTimeout(timer)
      const seconds = Math.min(session.seconds, Math.ceil((Date.now() - started) / 1000))
      try { upstream.close() } catch { }
      closeWith(code, reason)
      await pool.query('UPDATE live_sessions SET reported_seconds = $2, closed = true, closed_at = now() WHERE id = $1', [session.id, seconds]).catch(() => {})
      audit(req.params.id, null, 'live_ended', { seconds, reason: String(reason).slice(0, 60) })
    }
    const timer = setTimeout(() => finish(4000, 'time'), session.seconds * 1000)

    /** The phone chooses instructions and tools; the server chooses the model. */
    const rewrite = (data) => {
      const text = data.toString()
      if (text.length > 512 * 1024) return null
      if (!text.includes('"setup"')) return text
      try {
        const o = JSON.parse(text)
        if (o.setup) { o.setup.model = `models/${cfg.liveModel}`; return JSON.stringify(o) }
      } catch { return null }
      return text
    }
    const forward = (data) => {
      const out = rewrite(data)
      if (out == null) return finish(4400, 'bad message')
      if (upstream.readyState === WebSocketImpl.OPEN) upstream.send(out)
    }

    upstream.on('open', () => {
      socket.off('message', keep)
      for (const m of early) forward(m)
      socket.on('message', forward)
    })
    upstream.on('message', (data) => { if (socket.readyState === 1) socket.send(data.toString()) })
    upstream.on('close', (code, reason) => finish(code >= 4000 && code < 5000 ? code : 1000, reason?.toString() || 'ended'))
    upstream.on('error', (e) => finish(4502, `voice service: ${e.message}`))
    socket.on('close', () => finish(1000, 'client closed'))
    socket.on('error', () => finish(1000, 'client error'))
  })
}
