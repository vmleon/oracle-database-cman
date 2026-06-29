# CMAN-TDM resiliency demo

A dumb Java client (plain JDBC, no UCP/Application Continuity) runs a steady workload through the
CMAN-TDM endpoint and ships metrics to InfluxDB; Grafana plots the response time, errors, and which
RAC node served each query. Drain a node mid-run and watch what CMAN-TDM does for a client that has
no continuity logic of its own.

```
Gradle Java client ──JDBC thin──> CMAN-TDM endpoint ──> RAC
        │ line protocol (java.net.http)
        ▼
   InfluxDB 2.7 ◄── Flux ── Grafana ◄── browser (localhost:3000)
```

## Observability stack

```bash
cd demo
podman compose up -d
```

- **Grafana** — http://localhost:3000 (anonymous, no login). Dashboard: **CMAN-TDM Resiliency**.
- **InfluxDB** — http://localhost:8086 (user `admin` / `cmanpoc-admin`), org `cman`, bucket `workload`,
  token `cman-poc-token`. Local POC credentials, fine to keep in the compose file.

The dashboard reads empty until the workload runs (next piece). Tear the stack down with
`podman compose down` (add `-v` to also drop the InfluxDB volume).

## Metric schema

```
cman_workload,inst=<instance>,host=<node>,status=ok|error   latency_ms=<float>
cman_event,kind=drain                                       value=1
```

`inst`/`host` come from `SYS_CONTEXT('USERENV', …)` — no DB privileges needed. `cman_event` points
mark drains so Grafana draws an annotation line at each one.
