-- AIStock backend schema v1. Every business-owned row carries business_id; every route checks membership.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email         text NOT NULL UNIQUE,
  password_hash text NOT NULL,
  name          text NOT NULL DEFAULT '',
  created_at    timestamptz NOT NULL DEFAULT now(),
  disabled      boolean NOT NULL DEFAULT false
);

CREATE TABLE businesses (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name        text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz
);

-- owner: everything; manager: day-to-day + reports; staff: own tasks and stock entries (enforced in the app's tools too)
CREATE TABLE memberships (
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  role        text NOT NULL CHECK (role IN ('owner','manager','staff')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, business_id)
);

-- Versioned business configuration (type, modules, terminology, custom fields). Validated by the server before saving.
CREATE TABLE business_profiles (
  business_id uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  version     integer NOT NULL,
  config      jsonb NOT NULL,
  created_by  uuid REFERENCES users(id),
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_id, version)
);

CREATE TABLE refresh_tokens (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash  text NOT NULL UNIQUE,
  expires_at  timestamptz NOT NULL,
  revoked_at  timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now()
);

-- Plans are data, not code: price, currency and limits can change without a release.
CREATE TABLE plans (
  id                   text PRIMARY KEY,
  name                 text NOT NULL,
  price_minor          integer NOT NULL,      -- paise
  currency             text NOT NULL DEFAULT 'INR',
  period_days          integer NOT NULL DEFAULT 30,
  ai_requests_month    integer NOT NULL,      -- text conversation requests
  live_seconds_month   integer NOT NULL,      -- real-time voice seconds
  max_staff            integer NOT NULL DEFAULT 1,
  active               boolean NOT NULL DEFAULT true
);

CREATE TABLE subscriptions (
  business_id   uuid PRIMARY KEY REFERENCES businesses(id) ON DELETE CASCADE,
  plan_id       text NOT NULL REFERENCES plans(id),
  status        text NOT NULL CHECK (status IN ('trial','active','past_due','cancelled','expired')),
  current_period_start timestamptz NOT NULL DEFAULT now(),
  current_period_end   timestamptz NOT NULL,
  source        text NOT NULL DEFAULT 'trial',   -- trial | manual | play
  external_ref  text,
  updated_at    timestamptz NOT NULL DEFAULT now()
);

-- Every metered use. kind: ai_request | live_session | live_seconds
CREATE TABLE usage_events (
  id           bigserial PRIMARY KEY,
  business_id  uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  user_id      uuid REFERENCES users(id) ON DELETE SET NULL,
  kind         text NOT NULL,
  quantity     integer NOT NULL,
  input_tokens  integer NOT NULL DEFAULT 0,
  output_tokens integer NOT NULL DEFAULT 0,
  model        text,
  ref          text,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX usage_events_business_time ON usage_events (business_id, created_at);
-- A retried usage report must not be counted twice.
CREATE UNIQUE INDEX usage_events_ref ON usage_events (business_id, kind, ref) WHERE ref IS NOT NULL;

CREATE TABLE live_sessions (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  business_id  uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  model        text NOT NULL,
  max_seconds  integer NOT NULL,
  started_at   timestamptz NOT NULL DEFAULT now(),
  expires_at   timestamptz NOT NULL,
  reported_seconds integer NOT NULL DEFAULT 0,
  closed       boolean NOT NULL DEFAULT false
);

-- Encrypted backups uploaded by the app (opaque to the server).
CREATE TABLE backups (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  business_id  uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
  user_id      uuid REFERENCES users(id) ON DELETE SET NULL,
  size_bytes   integer NOT NULL,
  sha256       text NOT NULL,
  data         bytea NOT NULL,
  note         text NOT NULL DEFAULT '',
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX backups_business ON backups (business_id, created_at DESC);

CREATE TABLE audit_log (
  id           bigserial PRIMARY KEY,
  business_id  uuid,
  user_id      uuid,
  action       text NOT NULL,
  detail       jsonb NOT NULL DEFAULT '{}'::jsonb,
  ip           text,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_business_time ON audit_log (business_id, created_at);

INSERT INTO plans (id, name, price_minor, ai_requests_month, live_seconds_month, max_staff) VALUES
  ('trial',  'Free trial (14 days)', 0,     300,   1800, 1),
  ('basic',  'Basic',               19900, 1500,  3600, 2),
  ('pro',    'Pro',                 49900, 5000, 14400, 10);
