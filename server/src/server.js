import { loadConfig } from './config.js'
import { createPool } from './db.js'
import { migrate } from './migrate.js'
import { buildApp } from './app.js'

const cfg = loadConfig()
const pool = createPool(cfg.databaseUrl)
const applied = await migrate(pool)
if (applied.length) console.log('database migrations applied:', applied.join(', '))
const app = await buildApp({ cfg, pool, logger: { level: process.env.LOG_LEVEL ?? 'info', redact: ['req.headers.authorization', 'req.headers["x-goog-api-key"]'] } })
await app.listen({ host: '0.0.0.0', port: cfg.port })
const stop = async () => { await app.close(); await pool.end(); process.exit(0) }
process.on('SIGTERM', stop); process.on('SIGINT', stop)
