import pg from 'pg'

export function createPool(databaseUrl) {
  return new pg.Pool({ connectionString: databaseUrl, max: 10 })
}

/** Runs fn inside one transaction; rolls back on any error. */
export async function tx(pool, fn) {
  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    const out = await fn(client)
    await client.query('COMMIT')
    return out
  } catch (e) {
    await client.query('ROLLBACK').catch(() => {})
    throw e
  } finally {
    client.release()
  }
}
