# CMAN-TDM resiliency stack

The observability stack and the two Java clients behind the resiliency demo. The runbook — when to
start the workload and how to read the two-jump flow — is in [../DEMO.md](../DEMO.md); this file
covers the stack itself.

```
Gradle Java clients ──JDBC thin──> CMAN-TDM endpoint ──> RAC
        │ line protocol (java.net.http)
        ▼
   InfluxDB 2.7 ◄── Flux ── Grafana ◄── browser (localhost:3000)
```

Both clients poll `SYS_CONTEXT('USERENV', 'INSTANCE_NAME'/'SERVER_HOST')` (no DB privileges needed)
and ship line-protocol metrics to InfluxDB, tagged `client=dumb` or `client=smart`. The launcher
picks one via the `CLIENT` env var; both take `THREADS`.

- **Dumb** (`run-workload.sh`, `CLIENT=dumb`) — plain JDBC, one connection per thread, no pool, no
  Application Continuity. `THREADS` defaults to 1.
- **Smart** (`run-smart.sh`, `CLIENT=smart`) — a UCP pool sized to `THREADS` (default 8) using the
  Application Continuity replay connection factory, with Fast Connection Failover (FAN) enabled. If
  FAN can't be reached it falls back to AC-only and says so.

`run-workload.sh` / `run-smart.sh` read the endpoint from Terraform outputs and the app password
from `.env`.

## Observability stack

```bash
cd demo
podman compose up -d
```

- **Grafana** — http://localhost:3000 (anonymous, no login). Dashboard: **CMAN-TDM Resiliency**.
- **InfluxDB** — http://localhost:8086 (`admin` / `cmanpoc-admin`), org `cman`, bucket `workload`,
  token `cman-poc-token`. Local POC credentials, fine to keep in the compose file.

The dashboard reads empty until the workload runs. Tear down with `podman compose down` (add `-v`
to also drop the InfluxDB volume).

## Metric schema

```
cman_workload,client=dumb|smart,inst=<instance>,host=<node>,status=ok|error   latency_ms=<float>
cman_workload,client=dumb|smart,inst=<instance>,host=<node>,status=ok          recovery_ms=<int>
cman_event,kind=drain|restore,inst=<instance>                                  value=1
```

`latency_ms` is the per-tick round trip; `max(latency_ms)` drives the **Max stall** stat (the real
time a client was blocked, even when no error fired). `recovery_ms` is written only after a query
errors and a later one succeeds — the error-based outage behind the **Reconnect outage** stat; a
TDM-absorbed drain produces none. The `client` tag splits every panel into dumb vs smart, and the
**Smart pool distribution** panel counts `client=smart` points per node to show the UCP pool
draining and rebalancing. `cman_event` points mark drains and restores as annotation lines.
