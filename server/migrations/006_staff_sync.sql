-- Staff sync: the shared record of a business's data, so the owner's and staff phones see the same stock and bills.
ALTER TABLE businesses ADD COLUMN IF NOT EXISTS staff_money boolean NOT NULL DEFAULT false;
-- A short tag per staff or manager (S1, M1 ...) put into the bill numbers made on their phone, so two phones never
-- give the same number. The owner has none.
ALTER TABLE memberships ADD COLUMN IF NOT EXISTS code text;

CREATE SEQUENCE IF NOT EXISTS sync_seq;
CREATE TABLE IF NOT EXISTS sync_rows (
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  tbl         text NOT NULL,
  row_id      text NOT NULL,
  data        jsonb,
  deleted     boolean NOT NULL DEFAULT false,
  seq         bigint NOT NULL,
  user_id     uuid REFERENCES users(id) ON DELETE SET NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_id, tbl, row_id)
);
CREATE INDEX IF NOT EXISTS sync_rows_business_seq ON sync_rows (business_id, seq);
