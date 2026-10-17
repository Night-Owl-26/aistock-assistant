// The business configuration is data the app and Stocky propose; it is validated here before it is stored,
// so a model or a bad client can never create arbitrary structures.
export const MODULES = ['inventory', 'sales', 'purchases', 'expenses', 'customers', 'suppliers', 'orders', 'production',
  'service_tickets', 'appointments', 'projects', 'tasks', 'followups', 'staff']
export const BUSINESS_TYPES = ['retail', 'garment_manufacturing', 'wholesale', 'grocery', 'restaurant', 'service_repair',
  'salon', 'construction', 'agency', 'custom']
const FIELD_TYPES = ['text', 'number', 'date', 'choice', 'boolean']
const KEY = /^[a-z][a-z0-9_]{0,39}$/

function str(v, max) { return typeof v === 'string' && v.trim().length > 0 && v.length <= max }

/** @returns {{ ok: true, config: object } | { ok: false, errors: string[] }} */
export function validateConfig(input) {
  const errors = []
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return { ok: false, errors: ['config must be an object'] }
  const c = input
  if (c.schemaVersion !== 1) errors.push('schemaVersion must be 1')
  if (!BUSINESS_TYPES.includes(c.businessType)) errors.push('unknown businessType')
  if (!Array.isArray(c.modules) || c.modules.length === 0 || c.modules.length > MODULES.length) errors.push('modules must be a non-empty list')
  else for (const m of c.modules) if (!MODULES.includes(m)) errors.push(`unknown module ${m}`)
  if (c.terminology !== undefined) {
    if (typeof c.terminology !== 'object' || Array.isArray(c.terminology)) errors.push('terminology must be an object')
    else {
      const entries = Object.entries(c.terminology)
      if (entries.length > 60) errors.push('too many terminology entries')
      for (const [k, v] of entries) if (!KEY.test(k) || !str(v, 40)) errors.push(`bad terminology ${k}`)
    }
  }
  if (c.units !== undefined && (!Array.isArray(c.units) || c.units.length > 40 || !c.units.every(u => str(u, 20)))) errors.push('bad units')
  if (c.workflows !== undefined) {
    if (typeof c.workflows !== 'object' || Array.isArray(c.workflows)) errors.push('workflows must be an object')
    else for (const [m, w] of Object.entries(c.workflows)) {
      if (!MODULES.includes(m)) { errors.push(`workflow for unknown module ${m}`); continue }
      for (const list of ['statuses', 'stages']) {
        const l = w?.[list]
        if (l === undefined) continue
        if (!Array.isArray(l) || l.length > 30 || !l.every(s => str(s, 40))) errors.push(`bad ${list} for ${m}`)
        else if (new Set(l.map(s => s.toLowerCase())).size !== l.length) errors.push(`duplicate ${list} for ${m}`)
      }
    }
  }
  if (c.customFields !== undefined) {
    if (!Array.isArray(c.customFields) || c.customFields.length > 80) errors.push('bad customFields')
    else for (const f of c.customFields) {
      if (!MODULES.includes(f?.module) || !KEY.test(f?.key ?? '') || !str(f?.label, 40) || !FIELD_TYPES.includes(f?.type)) { errors.push(`bad custom field ${f?.key}`); continue }
      if (f.type === 'choice' && (!Array.isArray(f.options) || f.options.length === 0 || f.options.length > 30 || !f.options.every(o => str(o, 40)))) errors.push(`bad options for ${f.key}`)
    }
  }
  if (c.language !== undefined && !['en', 'ta', 'tanglish'].includes(c.language)) errors.push('bad language')
  if (JSON.stringify(c).length > 32_000) errors.push('config too large')
  return errors.length ? { ok: false, errors } : { ok: true, config: c }
}
