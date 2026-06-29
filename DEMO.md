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

## Resiliency: drain a node under load

A dumb Java client (plain JDBC, no connection pool, no Application Continuity) runs a steady
workload through CMAN-TDM and ships metrics to InfluxDB; Grafana plots response time, errors, and
which RAC node served each query. Draining a node mid-run shows what CMAN-TDM does for a client
that has no continuity logic of its own.

Bring up the observability stack and start the workload:

```bash
cd demo && podman compose up -d   # InfluxDB + Grafana at http://localhost:3000
./demo/run-workload.sh            # dumb client -> CMAN-TDM; metrics -> InfluxDB
```

Open Grafana (anonymous, no login) and watch the **CMAN-TDM Resiliency** dashboard fill: steady
latency and a serving-node timeline on whichever instance the session landed on. Then drain that
node:

```bash
python manage.py drain      # stop health on the serving node, with a drain grace period
python manage.py restore    # restart health on all nodes when done
```

`drain` reads the serving node from InfluxDB (override with `--instance dbcman2`, grace period with
`--timeout 90`), writes a `cman_event` annotation so the dashboard draws a line at the drain, then
stops `health` on that instance with a `drain_timeout`. It first ensures `health` is up on every
node, so stopping one always leaves a survivor to route to.

**What the dashboard shows.** CMAN reroutes _new_ connections to the surviving node immediately —
it learns the topology from service registration, no client change. The dumb client's _existing_
session, having no continuity logic, sees its in-flight query interrupted and reconnects through
CMAN to the survivor; the gap shows as a latency spike and a `recovery_ms` cutover stat. That gap
is the motivation for a continuity-aware client (UCP / Application Continuity), which CMAN-TDM
carries transparently — the stable endpoint never changes either way.

The observability stack details (credentials, metric schema, teardown) are in
[demo/README.md](demo/README.md).

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
