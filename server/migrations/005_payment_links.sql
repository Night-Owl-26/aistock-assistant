-- Razorpay payment links (cards, net banking, UPI). The plan starts when Razorpay says the link was paid.
CREATE TABLE IF NOT EXISTS payment_links (
  id           bigserial PRIMARY KEY,
  business_id  uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  user_id      uuid REFERENCES users(id) ON DELETE SET NULL,
  plan_id      text NOT NULL REFERENCES plans(id),
  amount_minor integer NOT NULL,
  provider_id  text NOT NULL UNIQUE,
  short_url    text NOT NULL,
  status       text NOT NULL DEFAULT 'created' CHECK (status IN ('created','paid','expired','cancelled')),
  created_at   timestamptz NOT NULL DEFAULT now(),
  paid_at      timestamptz
);
CREATE INDEX IF NOT EXISTS payment_links_business ON payment_links (business_id, created_at);
