#!/usr/bin/env bash
# Run the dumb Java client against the live CMAN endpoint, shipping metrics to the local
# InfluxDB. Reads the endpoint from Terraform outputs and the app password from .env.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
TF="$ROOT/infra/terraform"

export CMAN_HOST="$(terraform -chdir="$TF" output -raw cman_public_ip)"
export DB_SERVICE="$(terraform -chdir="$TF" output -raw db_service_name)"
export APPUSER_PASSWORD="$(grep '^APPUSER_PASSWORD=' "$ROOT/.env" | cut -d= -f2- | tr -d '"')"
export INFLUX_URL="${INFLUX_URL:-http://localhost:8086}"
export INFLUX_TOKEN="${INFLUX_TOKEN:-cman-poc-token}"
export INFLUX_ORG="${INFLUX_ORG:-cman}"
export INFLUX_BUCKET="${INFLUX_BUCKET:-workload}"
export INTERVAL_MS="${INTERVAL_MS:-1000}"
export CLIENT=dumb
export THREADS="${THREADS:-1}"

echo "Dumb client THREADS=$THREADS CMAN_HOST=$CMAN_HOST DB_SERVICE=$DB_SERVICE"
cd "$HERE/workload"
# installDist + direct launcher streams stdout in real time (gradle run buffers it).
./gradlew installDist -q --console=plain
exec ./build/install/workload/bin/workload
