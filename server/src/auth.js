import crypto from 'node:crypto'
import bcrypt from 'bcryptjs'
import { SignJWT, jwtVerify } from 'jose'

export const hashPassword = (p) => bcrypt.hash(p, 11)
export const checkPassword = (p, h) => bcrypt.compare(p, h)
export const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex')
export const randomToken = () => crypto.randomBytes(32).toString('base64url')

export function tokens(cfg) {
  const key = new TextEncoder().encode(cfg.jwtSecret)
  return {
    async access(userId) {
      return new SignJWT({}).setProtectedHeader({ alg: 'HS256' }).setSubject(userId)
        .setIssuedAt().setExpirationTime(`${cfg.accessTtlSeconds}s`).setAudience('aistock-app').sign(key)
    },
    async verify(token) {
      const { payload } = await jwtVerify(token, key, { audience: 'aistock-app', algorithms: ['HS256'] })
      return payload.sub
    },
  }
}

/** Constant-time comparison for the admin token. */
export function sameSecret(a, b) {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b))
  return x.length === y.length && x.length > 0 && crypto.timingSafeEqual(x, y)
}
