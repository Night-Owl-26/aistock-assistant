// Sends the few emails the server needs (password reset codes) through the owner's own SMTP account,
// for example Gmail with an app password. Without SMTP settings nothing is sent and the administrator reads codes instead.
import nodemailer from 'nodemailer'

export function mailer(cfg, transport = null) {
  const configured = () => !!(cfg.smtpHost && cfg.smtpUser && cfg.smtpPass)
  let t = transport
  const get = () => t ?? (t = nodemailer.createTransport({
    host: cfg.smtpHost, port: cfg.smtpPort, secure: cfg.smtpPort === 465,
    auth: { user: cfg.smtpUser, pass: cfg.smtpPass },
  }))
  return {
    configured: () => transport != null || configured(),
    async send(to, subject, text) {
      await get().sendMail({ from: cfg.smtpFrom || cfg.smtpUser, to, subject, text })
    },
  }
}
