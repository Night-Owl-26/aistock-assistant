-- When a voice session was closed, so its charge can be bounded by the clock even if the phone under-reports.
ALTER TABLE live_sessions ADD COLUMN IF NOT EXISTS closed_at timestamptz;
UPDATE live_sessions SET closed_at = started_at + make_interval(secs => reported_seconds) WHERE closed AND closed_at IS NULL;
CREATE INDEX IF NOT EXISTS live_sessions_business_started ON live_sessions (business_id, started_at);
CREATE INDEX IF NOT EXISTS refresh_tokens_user ON refresh_tokens (user_id);
