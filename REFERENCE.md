# Reference

The deeper explanation behind the showcase: how CMAN-TDM works, what each kind of client gets from
it, where the logs are, and the cmctl / SQLcl / `srvctl` commands worth knowing. The runbook is in
[DEMO.md](DEMO.md); the value framing and the layer-ownership table are in [README.md](README.md). The
FAN / FCF / ONS continuity story — where each piece runs and how a drain reaches each client — is in
[CONTINUITY.md](CONTINUITY.md).

## CMAN and Traffic Director Mode

CMAN is an Oracle Net listener-proxy. It accepts a client's Oracle Net connection, reads the
service name and source address out of the TNS handshake, applies the `RULE_LIST`, and then opens
its own connection onward to the database — following the SCAN redirect to a node VIP server-side,
so the client never learns the topology.

**Traffic Director Mode (`tdm = true`)** adds a pool of persistent outbound _gateway_ connections
to the database and multiplexes many inbound client sessions over them. This is what lets CMAN
drain and re-route work and, for a continuity-aware client, carry a session across a backend move.
TDM connects to the database with **proxy authentication**: the gateway pool authenticates as a
proxy user (`tdm`) whose credential CMAN holds in an auto-login wallet, and the client's real user
(`appuser`) is granted `CONNECT THROUGH tdm`. The first query on a fresh gateway pays a one-time
warmup while the pool establishes; steady-state queries do not.

`max_connections`, `idle_timeout`, and `inbound_connect_timeout` in `cman.ora` bound the pool and
the handshake. CMAN consumes FAN in-band when `oraaccess.xml` sets `<events>true</events>`. To
receive those events CMAN subscribes to the database's Oracle Notification Service (ONS): the NSGs
open CMAN→DB on port 6200 (`cman_eg_db_6200` / `db_in_cman_6200`), the continuity counterpart to the
1521 service-registration path.

## What each client gets

| Client                                                              | Through CMAN it gets                                                         | On a node drain                                                                                                                  |
| ------------------------------------------------------------------- | ---------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| **Dumb client** (plain JDBC, no pool, no AC — the demo workload)    | Stable endpoint, access control, topology hiding, server-side SCAN redirect. | New connections route to the survivor immediately; the existing in-flight session is interrupted and reconnects (a visible gap). |
| **Pooled / continuity-aware client** (UCP + Application Continuity) | All of the above, plus in-flight work recorded and replayed.                 | The session drains at a request boundary and continues on the survivor with no error.                                            |

The dumb client is deliberate: it isolates what the _proxy tier_ contributes from what the
_driver_ contributes. The stable endpoint, firewalling, and topology-hiding are CMAN's regardless
of client; transparent in-flight continuity needs a continuity-aware driver (or CMAN performing
continuity on the client's behalf). Either way the client keeps the one CMAN endpoint.

## Configuration primitives

**`cman.ora`** — Traffic Director Mode plus the IP-allow rule (the rule list is default-deny; the
showcase ships a single accept rule for the client CIDR):

```
cman_proxy =
  (configuration =
    (address = (protocol = tcp)(host = CMAN_HOST)(port = 1521))
    (parameter_list =
      (tdm = true)
      (tdm_threading_mode = dedicated)
      (max_connections = 1024)
      (idle_timeout = 0)
      (inbound_connect_timeout = 60)
      (log_level = user)
      (registration_invited_nodes = 10.0.2.0/24))
    (rule_list =
      (rule = (src = CLIENT_CIDR)(dst = *)(srv = *)(act = accept))
      (rule = (src = CMAN_HOST)(dst = 127.0.0.1)(srv = cmon)(act = accept))
      (rule = (src = *)(dst = *)(srv = *)(act = reject))))
```

`registration_invited_nodes` is CMAN's valid-node check for service registration: the DB subnet
must be listed or CMAN rejects the registration (TNS-01182).

**`oraaccess.xml`** — lets CMAN consume FAN in-band, required for draining/continuity:

```xml
<oraaccess>
  <default_parameters>
    <events>true</events>
  </default_parameters>
</oraaccess>
```

FAN reaches CMAN over ONS on port 6200, opened CMAN→DB by the `cman_eg_db_6200` / `db_in_cman_6200`
NSG rules; without that path CMAN sees only `service_update` registration churn, not drain events.

**`myapp` service (`srvctl`)** — Transparent Application Continuity attributes, preferred on both
instances:

```bash
srvctl add service -db "$D" -service myapp \
  -preferred dbcman1,dbcman2 \
  -failovertype AUTO -failover_restore AUTO \
  -commit_outcome TRUE -notification TRUE \
  -drain_timeout 120 -stopoption IMMEDIATE \
  -rlbgoal SERVICE_TIME -clbgoal SHORT
```

`FAILOVER_TYPE=AUTO` enables TAC (it is not on by default); `drain_timeout` bounds the grace period
that starts when the drain notification is sent. `rlbgoal SERVICE_TIME` makes the service publish
runtime load advisories over FAN so a continuity-aware pool rebalances back onto a restored node
instead of staying pinned to the survivor; `clbgoal SHORT` spreads new connections by session count.

## Tuning: drains, timeouts, rebalance, TTL

Two families of knobs decide how a drain behaves: **server-side service attributes** (what the
database does during planned maintenance) and **client-side pool settings** (how fast the pool
reacts and re-spreads afterwards). Values below are what this showcase ships; change them with the
"tweak" column.

| Knob                                       | Layer                                  | Now                            | What it does / what the wait is for                                                                                                                                                             | Tweak                                                                                                         |
| ------------------------------------------ | -------------------------------------- | ------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `drain_timeout`                            | service (`srvctl`) + `manage.py drain` | 120 s service / 60 s per drain | Grace period after the drain notification during which sessions relocate **at a request boundary** before `stopoption` ends the rest. The wait is for in-flight work to reach a safe point.     | Raise for long-running transactions; sub-second queries never need it.                                        |
| `stopoption`                               | service                                | `IMMEDIATE`                    | How leftover sessions end when `drain_timeout` expires: `IMMEDIATE` cuts them, `TRANSACTIONAL`/`POST_TRANSACTION` waits for the transaction.                                                    | Leave `IMMEDIATE` for a planned demo; use transactional stops for OLTP that must not be cut.                  |
| `replay_init_time`                         | service                                | 300 s (default)                | How long AC keeps trying to **initiate** replay after a recoverable error before giving up with `ORA-25415`. Not the bottleneck here — errors fired ~9 s in, far under 300 s.                   | Raise only if replay legitimately needs minutes to land on the survivor.                                      |
| `rlbgoal SERVICE_TIME`                     | service                                | on                             | Publishes runtime load advisories over FAN. Removes the wait where the pool has nothing telling it a restored node is idle, so it re-spreads lazily. `SERVICE_TIME` routes to the fastest node. | `THROUGHPUT` to balance by work done instead of response time; `NONE` disables (what caused the pinned pool). |
| `clbgoal SHORT`                            | service                                | on                             | Connection-time balancing for **new** connections. `SHORT` spreads by session count (right for pools).                                                                                          | `LONG` for long-lived, non-pooled connections.                                                                |
| `maxConnectionReuseTime`                   | UCP client (`SmartWorkload.java`)      | 180 s                          | Retires an aged pooled connection so its replacement re-spreads onto a restored node. Bounds how long a connection can stay pinned when FAN _up_ events rebalance only lazily.                  | 30–60 s for aggressive re-spread (more reconnects); `0` disables.                                             |
| `oracle.net.CONNECT_TIMEOUT`               | both clients                           | 20 s smart / 20 s dumb         | Bounds the TCP + logon to a backend. Too low aborts the slow first TDM logon and breaks reconnects.                                                                                             | Keep ≥ the observed cold-gateway logon (~10–20 s).                                                            |
| statement query timeout                    | both clients                           | 30 s smart / 15 s dumb         | How fast a dead session is detected. Must sit **above** the first-query/TDM warmup (~10–20 s) or healthy slow queries get killed as false errors.                                               | Lower to detect failures faster once you know your warmup cost.                                               |
| `idle_timeout` / `inbound_connect_timeout` | `cman.ora`                             | 0 / 60 s                       | CMAN idle-connection close (0 = never) and how long CMAN waits for a client connect handshake.                                                                                                  | Set `idle_timeout` > 0 to reap abandoned clients.                                                             |
| `tdm_threading_mode` (PRCP)                | `cman.ora`                             | `dedicated`                    | Dedicated mode does **not** keep warm backend connections. CMAN cannot pre-warm a restored node, so re-spread is entirely the client pool's job (the two rows above).                           | Enabling PRCP pooling adds proxy-resident warm backends, at added CMAN config complexity.                     |

**Draining both nodes in sequence.** A single drain is absorbed with zero errors. Draining one
node pushes the whole smart pool onto the survivor; `restore` brings the service back but the pool
re-spreads only gradually. Draining the _second_ node before the pool has spread back means a
near-total failover onto a node that was just restored and is still cold — which is what produces
the handful of `ORA-25415`/`ORA-3114` on the smart client. `rlbgoal` + `maxConnectionReuseTime`
shorten that re-spread window; operationally, **wait until the "Smart pool spread" panel shows both
nodes populated before draining the other one.**

## Where the logs are

| Log                         | Location                                                                  | How to read it                                                                                                        |
| --------------------------- | ------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| CMAN status / registrations | live, in-process                                                          | `cmctl show status -c cman_proxy`, `cmctl show services -c cman_proxy`                                                |
| CMAN alert + trace          | ADR diag tree under the client `ORACLE_BASE`                              | `cmctl show parameter log_directory` / `trace_directory` to find the exact path, then read `cman_proxy/{alert,trace}` |
| Database alert log          | `$ORACLE_BASE/diag/rdbms/<db>/<sid>/trace/alert_<sid>.log` on the DB node | `tail -f` as `oracle`                                                                                                 |
| Service config / status     | live                                                                      | `srvctl config service -db "$D" -service myapp`, `srvctl status service ...`                                          |
| Ops bootstrap               | `/var/log/cman-bootstrap.log` on the ops host                             | `python manage.py info` prints the tail command; complete when `/var/lib/cman-bootstrap.ok` exists                    |
| Workload metrics            | InfluxDB `workload` bucket                                                | the Grafana **CMAN-TDM Resiliency** dashboard, or the InfluxDB UI at `:8086`                                          |

Raise CMAN logging for a connection trace by setting a higher `log_level`/`trace_level` in
`cman.ora`; a CMAN trace shows whether the database is publishing FAN (`event`/`drain`/`down`
lines) or only `service_update` registration churn.

## Command reference

**cmctl (on the CMAN host, as `oracle`).** Set the home first:

```bash
export ORACLE_HOME=/opt/oracle/product/19c/client_1
export PATH=$ORACLE_HOME/bin:$PATH
cmctl show status   -c cman_proxy   # TDM mode, uptime, connection counts
cmctl show services -c cman_proxy   # registered services + gateway handlers
cmctl show parameter -c cman_proxy  # effective cman.ora parameters
cmctl reload        -c cman_proxy   # re-read cman.ora (a full shutdown/startup drops registrations ~60s)
```

**SQLcl (from the laptop, through CMAN).** The saved connection routes through the endpoint:

```bash
TERM=dumb sql -name cman          # macOS SQLcl needs TERM=dumb
```

```sql
select instance_name, host_name from v$instance;          -- which node served me
select sys_context('USERENV','SERVER_HOST') from dual;     -- same, no privileges needed
show user
```

**DB / RAC (on a DB node, as `oracle`).** Manage the service and the cluster:

```bash
export ORACLE_HOME=$(ls -d /u01/app/oracle/product/*/dbhome_* | head -1)
export PATH=$ORACLE_HOME/bin:$PATH
D=$(srvctl config database | head -1)
srvctl status database -db "$D"                       # instances up/down
srvctl config service  -db "$D" -service myapp       # AC/drain attributes
srvctl status service  -db "$D" -service myapp        # which instances serve myapp
lsnrctl status                                         # local listener + registered services
```

To force a DB instance to re-register with CMAN after a reload, run `alter system register;` on
that instance as a privileged user instead of waiting out the registration interval.

## Oracle documentation

- Oracle Net Services Administrator's Guide — [_Configuring and Administering Oracle Connection Manager_](https://docs.oracle.com/en/database/oracle/oracle-database/26/netag/configuring-oracle-connection-manager.html)
  (the primary reference: Traffic Director Mode, `rule_list`, proxy authentication, PRCP).
- Oracle Net Services Reference — _Oracle Connection Manager Parameters_ (`tdm`, `rule_list`,
  `max_connections`, `registration_invited_nodes`, PRCP tuning).
- CMAN-TDM whitepaper — _Oracle Database Connection Proxy for Scalable Applications_.
- Oracle RAC Administration — _Ensuring Application Continuity_ (`FAILOVER_TYPE`/`FAILOVER_RESTORE`,
  FAN, draining).
- Base Database Service — editions and feature gating (RAC and Active Data Guard require Extreme
  Performance).
- OCI Terraform provider — `oci_database_db_system` (`node_count`, `database_edition`).
