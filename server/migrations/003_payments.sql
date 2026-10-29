-- UPI payments the owner says they made. The administrator checks the bank/UPI app and approves (which starts or
-- extends the plan) or rejects. A UPI transaction reference (UTR) can only be claimed once.
CREATE TABLE IF NOT EXISTS payment_claims (
  id           bigserial PRIMARY KEY,
  business_id  uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  user_id      uuid REFERENCES users(id) ON DELETE SET NULL,
  plan_id      text NOT NULL REFERENCES plans(id),
  amount_minor integer NOT NULL,
  utr          text NOT NULL UNIQUE,
  status       text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected')),
  note         text NOT NULL DEFAULT '',
  created_at   timestamptz NOT NULL DEFAULT now(),
  decided_at   timestamptz
);
CREATE INDEX IF NOT EXISTS payment_claims_business ON payment_claims (business_id, created_at);
CREATE INDEX IF NOT EXISTS payment_claims_pending ON payment_claims (status) WHERE status = 'pending';
