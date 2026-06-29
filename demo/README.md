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

The dashboard reads empty until the workload runs. Tear the stack down with
`podman compose down` (add `-v` to also drop the InfluxDB volume).

## Run the demo

With the OCI stack deployed and the observability stack up:

```bash
./demo/run-workload.sh      # dumb client -> CMAN-TDM; ~60s connect, then ~80ms/tick
```

Leave it running and watch Grafana fill: latency around 80ms, a `Requests per RAC node` bar series
on whichever node the session landed on. Then, in another terminal, drain that node:

```bash
python manage.py drain      # stop health on the serving node, drain grace period, Grafana annotation
python manage.py restore    # restart health on both nodes when you're done
```

`drain` reads the serving node from InfluxDB (override with `--instance dbcman2`, grace with
`--timeout 90`) and writes a `cman_event` marker so the dashboard draws a line at the drain. Watch
whether the dumb client's latency stays flat as CMAN-TDM carries the session to the other node, or
spikes into errors and reconnects — the answer is the showcase.

## Metric schema

```
cman_workload,inst=<instance>,host=<node>,status=ok|error   latency_ms=<float>
cman_event,kind=drain                                       value=1
```

`inst`/`host` come from `SYS_CONTEXT('USERENV', …)` — no DB privileges needed. `cman_event` points
mark drains so Grafana draws an annotation line at each one.
