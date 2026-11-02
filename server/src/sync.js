// Staff sync rules: which tables travel between the phones of one business, and what each role may change.
// Every phone keeps its own database and works offline; changes go up with /sync/push and come down with /sync/pull.

/** The business tables that are shared. Anything else (settings, chats, Excel links, reminders) stays on its phone. */
export const SYNC_TABLES = [
  'categories', 'products', 'customers', 'suppliers', 'sales', 'sale_items', 'purchases', 'purchase_items',
  'expenses', 'payments', 'inventory_transactions', 'staff', 'work_items', 'work_events', 'followups',
  'contact_log', 'product_batches', 'business_aliases',
]

// Staff may add these (never change or delete what exists): selling, stock in and out, customers, notes.
const STAFF_ADD = new Set(['sales', 'sale_items', 'inventory_transactions', 'customers', 'contact_log', 'products', 'categories', 'business_aliases', 'product_batches'])
// Staff may also update these: their work items, steps and follow-ups.
const STAFF_EDIT = new Set(['work_items', 'work_events', 'followups'])
// Only when the owner allows money entries for staff.
const STAFF_MONEY = new Set(['purchases', 'purchase_items', 'payments', 'expenses', 'suppliers'])

const ID = /^[A-Za-z0-9_-]{1,64}$/
export const MAX_CHANGES = 500
export const MAX_ROW_BYTES = 20_000

/**
 * Whether [role] may write this change. [exists] says whether the row is already on the server.
 * @returns null when allowed, else a short reason
 */
export function allowed({ role, staffMoney }, change, exists) {
  if (!SYNC_TABLES.includes(change.t)) return 'unknown_table'
  if (role === 'owner' || role === 'manager') return null
  if (change.deleted) return 'staff_cannot_delete'
  if (STAFF_EDIT.has(change.t)) return null
  if (STAFF_ADD.has(change.t) || (staffMoney && STAFF_MONEY.has(change.t))) return exists ? 'staff_cannot_change' : null
  return STAFF_MONEY.has(change.t) ? 'staff_money_off' : 'not_allowed'
}

/** Checks the shape of one change from a phone; returns the cleaned change or null. */
export function clean(c) {
  if (!c || typeof c !== 'object') return null
  const t = String(c.t ?? ''); const id = String(c.id ?? '')
  if (!SYNC_TABLES.includes(t) || !ID.test(id)) return null
  if (c.deleted === true) return { t, id, deleted: true, data: null }
  if (!c.data || typeof c.data !== 'object' || Array.isArray(c.data)) return null
  if (String(c.data.id ?? id) !== id) return null
  if (Buffer.byteLength(JSON.stringify(c.data)) > MAX_ROW_BYTES) return null
  return { t, id, deleted: false, data: c.data }
}
