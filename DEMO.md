# Demo

The runbook for showing CMAN working. It assumes the stack is deployed and verified
([DEPLOY.md](DEPLOY.md)). For the internals behind each command — log locations, the cmctl /
SQLcl / `srvctl` reference, and the configuration primitives — see [REFERENCE.md](REFERENCE.md).

## Command map

| Command                                             | What it does                                                              |
| --------------------------------------------------- | ------------------------------------------------------------------------- |
| `python manage.py info`                             | Print endpoints and ready-to-paste connect / SSH / cmctl / demo commands. |
| `python manage.py sql`                              | Save the `cman` SQLcl named connection on this laptop (one-time).         |
| `python manage.py health`                           | Run `select instance_name from v$instance` through the CMAN endpoint.     |
| `python manage.py drain [--instance N --timeout S]` | Drain the `health` service off the serving RAC node; mark it in Grafana.  |
| `python manage.py restore`                          | Restart `health` on all RAC nodes after a drain.                          |
| `python manage.py jumps`                            | Choreograph the two-jump flow A→B→A (drain/restore/drain/restore).        |

Run `python manage.py info` first — it prints the live endpoints and the exact SSH, cmctl, and
demo commands for the current deployment, so you can copy-paste rather than transcribe.

## Health: the laptop reaches the database only through CMAN

```bash
python manage.py sql     # one-time, saves the 'cman' connection
python manage.py health
```

`health` connects to the saved `cman` named connection, runs the query through CMAN, and prints
the result — for example `dbcman1` or `dbcman2`. To run it by hand:

```bash
TERM=dumb sql -name cman
```

```sql
select instance_name from v$instance;
```

The result is the RAC node that served the connection. That name comes from inside the private
subnet; the only address the laptop used is the CMAN endpoint. CMAN parsed the Oracle Net
handshake, applied the `RULE_LIST` (accept/reject by source IP and service name), followed the
SCAN redirect to a node VIP itself, and forwarded the session — none of which a dumb TCP relay can
do. When SQLcl connects you see `connected via Oracle Connection Manager in Traffic Director mode`,
the unambiguous proof the session is going through CMAN-TDM, not a direct hop.

## Inspect CMAN on the host

CMAN's own view of registrations and gateways is the other half of the proof. SSH into the CMAN
host and ask cmctl:

```bash
CH=/opt/oracle/product/19c/client_1
ssh -i <key> opc@<cman_ip> "sudo su - oracle -c 'export ORACLE_HOME=$CH; $CH/bin/cmctl show services -c cman_proxy'"
```

`show services` lists each registered service and its gateway handlers; `show status` reports the
TDM mode and connection counts. `python manage.py info` prints these commands with the live host
filled in. The cmctl reference is in [REFERENCE.md](REFERENCE.md).

## Resiliency: drain nodes under load, dumb vs smart

Two Java clients run the same steady workload through CMAN-TDM and ship metrics to InfluxDB; Grafana
plots response time, the serving node, errors, and the smart pool's distribution across nodes:

- **Dumb client** (`run-workload.sh`) — plain JDBC, one connection, no pool, no Application
  Continuity. `client=dumb`, single-threaded by default.
- **Smart client** (`run-smart.sh`) — a UCP pool (8 connections) with Application Continuity replay
  and FAN enabled. `client=smart`.

Bring up the stack and start both clients (each in its own terminal):

```bash
cd demo && podman compose up -d   # InfluxDB + Grafana at http://localhost:3000
./demo/run-workload.sh            # dumb client  -> CMAN-TDM; metrics -> InfluxDB
./demo/run-smart.sh               # smart client -> CMAN-TDM; metrics -> InfluxDB
```

Open Grafana (anonymous, no login), the **CMAN-TDM Resiliency** dashboard, then run the two-jump
flow:

```bash
python manage.py jumps      # A -> B -> back to A, with watch pauses and Grafana annotations
```

`jumps` runs `restore`, drains node A, restores A, drains node B, restores B — each step writing a
`cman_event` annotation. It uses explicit node names (`--node-a dbcman1 --node-b dbcman2`) and pauses
(`--settle`, `--restore-wait`) so the A→B→A shape is easy to watch. The single-step `drain` /
`restore` commands still exist for driving it by hand; `drain` reads the dumb client's current node
from InfluxDB (override with `--instance dbcman2`, grace with `--timeout 90`).

### What the charts teach

- **Blocked, but no error (the dumb client).** On a planned drain, CMAN-TDM holds the in-flight
  request, re-establishes the backend on the survivor, and completes the _same_ read query there —
  so the dumb client sees **one slow query (~20 s), `status=ok`, zero errors**: a latency event, not
  an outage. Single-threaded, that one call blocks all telemetry, so the latency panel shows a
  **blank gap** — which is the client being busy, not downtime. Raise `THREADS` on the dumb client
  to turn the blank gap into "one thread stalls while others keep reporting".
- **"Reconnect outage" vs "Max stall".** _Reconnect outage (error-based)_ is `recovery_ms`, written
  only after a query actually errors and a later one succeeds — for a TDM-absorbed drain it reads
  `no gap` because there was no error. _Max stall (ms)_ is `max(latency_ms)` and shows the real
  ~20 s the dumb client was blocked. Read them together.
- **Smart client.** Application Continuity replays the in-flight query on the survivor, so the smart
  client rides the same drain with a small blip instead of a 20 s stall. The **Smart pool
  distribution by node** panel shows its 8 pooled connections draining off the node and rebalancing
  across the cluster — the binary single-connection view a dumb client can't show.
- **No failback for the dumb client.** RAC never migrates a live session back; the dumb connection
  stays on whichever node it last moved to. The smart pool rebalances on FAN/up events. The
  two-jump flow uses an explicit drain of the _other_ node to force the trip home, so both clients
  visibly return to the origin.

The continuity mechanics behind these charts — FAN / FCF / ONS, where each runs, and why dumb and
smart diverge on the same drain — are in [CONTINUITY.md](CONTINUITY.md). The observability stack
details (credentials, metric schema, teardown) are in [demo/README.md](demo/README.md).

## Drain and restore by hand

`drain` / `restore` wrap `srvctl` on the RAC node. Run the same operations directly when you want
to drive the demo without `manage.py`.

**Over SSH (`srvctl`, server-side draining).** Hop through the ops host to a DB node as `oracle`:

```bash
ssh -i <key> opc@<ops_ip>
DB=$(awk -F= '/dbnode/{print $NF}' ~/hosts.ini)
ssh -i ~/private.key opc@"$DB"
sudo su - oracle
export ORACLE_HOME=$(ls -d /u01/app/oracle/product/*/dbhome_* | head -1)
export PATH=$ORACLE_HOME/bin:$PATH
D=$(srvctl config database | head -1)

srvctl status service -db "$D" -service health
# drain off one instance (planned maintenance), leaving the survivor serving:
srvctl stop service  -db "$D" -service health -instance dbcman1 -drain_timeout 60 -stopoption immediate -force
# restore on all nodes:
srvctl start service -db "$D" -service health
```

**From SQLcl (`DBMS_SERVICE`).** Connected to the _instance you want to drain_ as a privileged user
(e.g. `system`), the same drain is a PL/SQL call — `DBMS_SERVICE` acts on the local instance only:

```sql
exec dbms_service.stop_service('health', drain_timeout => 60, stop_option => dbms_service.post_transaction);
-- restore:
exec dbms_service.start_service('health');
```

`srvctl` is the path `manage.py drain` uses because it can target a named instance from anywhere on
the cluster; the `DBMS_SERVICE` form is handy when you already have a SQLcl session on the node.
