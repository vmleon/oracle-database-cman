# Demo

The runbook for showing CMAN working under load. It assumes the stack is deployed and verified
([DEPLOY.md](DEPLOY.md)) — the laptop already reaches the database through CMAN. For the internals
behind each command — log locations, the cmctl / SQLcl / `srvctl` reference, the by-hand drain, and
the configuration primitives — see [REFERENCE.md](REFERENCE.md).

`python manage.py info` prints the live endpoints and the exact demo commands for the current
deployment, so you can copy-paste rather than transcribe.

## Bring up the observability stack

Two Java clients run the same steady workload through CMAN-TDM and ship metrics to InfluxDB; Grafana
plots response time, the serving node, errors, and the smart pool's distribution across nodes:

- **Dumb client** (`run-dumb.sh`) — plain JDBC, one connection, no pool, no Application
  Continuity. `client=dumb`, single-threaded by default.
- **Smart client** (`run-smart.sh`) — a UCP pool (8 connections) with Application Continuity replay
  and FAN enabled. `client=smart`.

Start InfluxDB + Grafana from the `demo` folder:

```bash
cd demo
```

```bash
podman compose up -d
```

## Start the workload

Each client holds its terminal while it runs, so open a **new terminal in the `demo` folder** for
each one.

New terminal in `demo`, dumb client:

```bash
./run-dumb.sh
```

Another new terminal in `demo`, smart client:

```bash
./run-smart.sh
```

## Open the dashboard

Open Grafana and the **CMAN-TDM Resiliency** dashboard (anonymous, no login):

http://localhost:3000

Let both clients report for a moment — you should see steady latency and both `dumb` and `smart`
traffic before driving any drain.

## Drain and restore under load

Drive the drains one at a time from the repository root (another terminal), watching the dashboard
react before moving on. Each `drain` writes a `cman_event` annotation (a red line on every time
panel) and stops `myapp` on that instance with a grace period (`--timeout`, default 60 s); `restore`
restarts `myapp` on all nodes.

**Drain A — push the clients off `dbcman1`:**

```bash
python manage.py drain --instance dbcman1
```

Watch **Serving RAC node — dumb client** move to `dbcman2` with `status=ok`, **Total errors** stay
at 0, and **Smart pool spread across nodes** shift its share onto the survivor. Wait until the spread
panel shows `dbcman2` carrying the pool before continuing.

**Restore A — both nodes serving again:**

```bash
python manage.py restore
```

Wait until **Smart pool spread** shows **both** nodes populated again — the pool re-spreads
gradually — before draining the other node.

**Drain B — push the clients off `dbcman2`:**

```bash
python manage.py drain --instance dbcman2
```

The dumb client moves back to `dbcman1`. RAC never fails a live session back on its own, so this
second drain is what returns it to the origin — visible on **Serving RAC node — dumb client**.
Errors stay at 0.

**Restore B — both nodes serving again:**

```bash
python manage.py restore
```

Omit `--instance` to drain whichever node currently serves the dumb client (read from InfluxDB).

## What the charts teach

- **Blocked, but no error (the dumb client).** On a planned drain, CMAN-TDM holds the in-flight
  request, re-establishes the backend on the survivor, and completes the _same_ read query there —
  so the dumb client sees **one slow query, `status=ok`, zero errors**: a latency event, not an
  outage. It shows on **SQL round-trip latency per client** as a brief spike; single-threaded, that
  one blocked call also stops telemetry, leaving a short **gap** — the client being busy, not
  downtime. Raise `THREADS` on the dumb client to turn the gap into "one thread stalls while others
  keep reporting".
- **Smart client.** Application Continuity replays the in-flight query on the survivor, so the smart
  client rides the same drain with a small blip. **Smart pool spread across nodes** shows its 8
  pooled connections serving from both nodes at once and shifting their share onto the survivor — the
  single-connection view a dumb client can't show.
- **Zero errors.** **Total errors** stays at 0 throughout: every drain is absorbed, by TDM for the
  dumb client and by AC replay for the smart one. A drain is never an outage here.
- **No failback for the dumb client.** RAC never migrates a live session back; the dumb connection
  stays on whichever node it last moved to — visible on **Serving RAC node — dumb client** as it
  moves to the survivor and stays. Draining the _other_ node (step B above) is what forces it back,
  so the connection visibly returns to the origin.

The continuity mechanics behind these charts — FAN / FCF / ONS, where each runs, and why dumb and
smart diverge on the same drain — are in [CONTINUITY.md](CONTINUITY.md). The observability stack
details (credentials, metric schema, teardown) are in [demo/README.md](demo/README.md).
