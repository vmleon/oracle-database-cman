# CMAN-TDM resiliency stack

The observability stack and dumb Java client behind the resiliency demo. The runbook — when to
start the workload and how to read the drain — is in [../DEMO.md](../DEMO.md); this file covers the
stack itself.

```
Gradle Java client ──JDBC thin──> CMAN-TDM endpoint ──> RAC
        │ line protocol (java.net.http)
        ▼
   InfluxDB 2.7 ◄── Flux ── Grafana ◄── browser (localhost:3000)
```

The client is plain JDBC — no UCP, no Application Continuity. It polls
`SYS_CONTEXT('USERENV', 'INSTANCE_NAME'/'SERVER_HOST')` (no DB privileges needed) and ships
line-protocol metrics to InfluxDB. `run-workload.sh` reads the endpoint from Terraform outputs and
the app password from `.env`.

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
cman_workload,inst=<instance>,host=<node>,status=ok|error   latency_ms=<float>
cman_workload,inst=<instance>,host=<node>,status=ok         recovery_ms=<int>
cman_event,kind=drain|restore,inst=<instance>               value=1
```

`recovery_ms` is written once per cutover — the client-visible outage between the failing query and
the first query that succeeds again — and drives the "Cutover gap" stat. `cman_event` points mark
drains and restores so Grafana draws an annotation line at each one.
