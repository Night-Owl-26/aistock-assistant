import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createPool } from './db.js'

const dir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'migrations')

/** Applies every migrations/NNN_*.sql not yet applied, each in its own transaction. */
export async function migrate(pool) {
  await pool.query('CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())')
  const done = new Set((await pool.query('SELECT name FROM schema_migrations')).rows.map(r => r.name))
  const files = fs.readdirSync(dir).filter(f => /^\d+_.*\.sql$/.test(f)).sort()
  const applied = []
  for (const f of files) {
    if (done.has(f)) continue
    const client = await pool.connect()
    try {
      await client.query('BEGIN')
      await client.query(fs.readFileSync(path.join(dir, f), 'utf8'))
      await client.query('INSERT INTO schema_migrations(name) VALUES ($1)', [f])
      await client.query('COMMIT')
      applied.push(f)
    } catch (e) {
      await client.query('ROLLBACK').catch(() => {})
      throw new Error(`Migration ${f} failed: ${e.message}`)
    } finally {
      client.release()
    }
  }
  return applied
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const pool = createPool(process.env.DATABASE_URL)
  migrate(pool).then(a => { console.log('applied:', a.length ? a.join(', ') : 'nothing new'); return pool.end() })
    .catch(e => { console.error(e.message); process.exit(1) })
}
