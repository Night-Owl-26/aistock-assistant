#!/usr/bin/env bash
# Helper commands for the server. Run from the server/ folder.
set -euo pipefail
case "${1:-}" in
  backup)   # nightly database dump: add to crontab ->  0 2 * * * cd /opt/aistock/server && ./scripts.sh backup
    mkdir -p backups
    docker compose exec -T db pg_dump -U aistock -Fc aistock > "backups/aistock-$(date +%F).dump"
    find backups -name 'aistock-*.dump' -mtime +14 -delete
    echo "saved backups/aistock-$(date +%F).dump" ;;
  restore)  # ./scripts.sh restore backups/aistock-2026-10-17.dump
    docker compose exec -T db pg_restore -U aistock -d aistock --clean --if-exists < "$2" ;;
  activate) # ./scripts.sh activate <businessId> basic 30 "UPI ref 123"
    docker compose exec -T api wget -qO- --header "Authorization: Bearer $(grep ^ADMIN_TOKEN .env | cut -d= -f2-)" \
      --header 'Content-Type: application/json' \
      --post-data "{\"businessId\":\"$2\",\"planId\":\"$3\",\"days\":${4:-30},\"reference\":\"${5:-}\"}" http://127.0.0.1:8080/admin/subscriptions; echo ;;
  find)     # ./scripts.sh find owner@email.com   -> shows the business id
    docker compose exec -T db psql -U aistock -d aistock -c "SELECT b.id, b.name, s.plan_id, s.status, s.current_period_end FROM users u JOIN memberships m ON m.user_id=u.id JOIN businesses b ON b.id=m.business_id LEFT JOIN subscriptions s ON s.business_id=b.id WHERE u.email=lower('$2')" ;;
  usage)    # cost check: AI requests, tokens and voice minutes per business this month
    docker compose exec -T db psql -U aistock -d aistock -c "SELECT b.name, SUM(CASE WHEN kind='ai_request' THEN quantity END) AS ai_requests, SUM(input_tokens) AS in_tokens, SUM(output_tokens) AS out_tokens FROM usage_events e JOIN businesses b ON b.id=e.business_id WHERE e.created_at >= date_trunc('month', now()) GROUP BY b.name" \
      -c "SELECT b.name, ROUND(SUM(GREATEST(reported_seconds,0))/60.0,1) AS voice_minutes, COUNT(*) AS sessions FROM live_sessions l JOIN businesses b ON b.id=l.business_id WHERE l.started_at >= date_trunc('month', now()) GROUP BY b.name" ;;
  *) echo "usage: ./scripts.sh backup|restore <file>|activate <businessId> <plan> [days] [ref]|find <email>|usage" ;;
esac
