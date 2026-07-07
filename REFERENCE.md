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
continuity on the client's behalf). Either way the client keeps the one CMAN endpoint. The smart
client connects with `(SERVER=POOLED)` so it draws warm sessions from CMAN's PRCP pool; the dumb
client stays dedicated (EZConnect), so it builds a fresh backend gateway on failover.

## Configuration primitives

**`cman.ora`** — Traffic Director Mode plus the access rule list. The showcase accepts client
connections from any source, so the demo never breaks when the operator's egress IP changes:

```
cman_proxy =
  (configuration =
    (address = (protocol = tcp)(host = CMAN_HOST)(port = 1521))
    (parameter_list =
      (tdm = true)
      (tdm_threading_mode = dedicated)
      (tdm_prcp_max_call_wait_time = 60)
      (tdm_prcp_max_txn_call_wait_time = 120)
      (service_affinity = off)
      (max_connections = 1024)
      (idle_timeout = 0)
      (inbound_connect_timeout = 60)
      (log_level = user)
      (registration_invited_nodes = 10.0.2.0/24))
    (rule_list =
      (rule = (src = *)(dst = *)(srv = *)(act = accept))
      (rule = (src = CMAN_HOST)(dst = 127.0.0.1)(srv = cmon)(act = accept))))
```

`registration_invited_nodes` is CMAN's valid-node check for service registration: the DB subnet
must be listed or CMAN rejects the registration (TNS-01182).

**`oraaccess.xml`** — two roles: `<events>true</events>` lets CMAN consume FAN, and the
`<session_pool>` turns on PRCP (Proxy Resident Connection Pooling) so CMAN keeps a pool of warm
backend gateway sessions to the service:

```xml
<oraaccess>
  <default_parameters>
    <events>true</events>
  </default_parameters>
  <config_descriptions>
    <config_description>
      <config_alias>tdm_pooled</config_alias>
      <parameters>
        <session_pool>
          <enable>true</enable>
          <min_size>8</min_size>
          <max_size>32</max_size>
          <increment>2</increment>
        </session_pool>
      </parameters>
    </config_description>
  </config_descriptions>
  <connection_configs>
    <connection_config>
      <connection_string>SERVICE_NAME</connection_string>
      <config_alias>tdm_pooled</config_alias>
    </connection_config>
  </connection_configs>
</oraaccess>
```

FAN reaches CMAN over ONS on port 6200, opened CMAN→DB by the `cman_eg_db_6200` / `db_in_cman_6200`
NSG rules; without that path CMAN sees only `service_update` registration churn, not drain events.
`min_size` is the warm-pool floor: CMAN keeps that many backend sessions live even when idle, so a
restored or surviving node is never cold. A client that connects with `(SERVER=POOLED)` checks a
warm session out of this pool per request instead of building a fresh TDM gateway (the ~10 s
warmup); the PRCP knobs `tdm_prcp_max_call_wait_time` / `tdm_prcp_max_txn_call_wait_time` in
`cman.ora` bound how long a client may hold a checked-out session idle before it is reclaimed.

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

## Inspect CMAN on the host

CMAN's own view of registrations and gateways, and its on-disk configuration, are the other half of
the proof. SSH into the CMAN host and ask cmctl as `oracle`:

```bash
CH=/opt/oracle/product/19c/client_1
ssh -i <key> opc@<cman_ip> "sudo su - oracle -c 'export ORACLE_HOME=$CH; $CH/bin/cmctl show services -c cman_proxy'"
```

`show services` lists each registered service and its gateway handlers; `show status` reports the
TDM mode and connection counts. `python manage.py info` prints these commands with the live host
filled in; the full cmctl set is in [Command reference](#command-reference) below.

The configuration files live under `$ORACLE_HOME/network/admin` on the CMAN host — read them to see
what CMAN is actually running:

| File            | What to look for                                                                                                |
| --------------- | --------------------------------------------------------------------------------------------------------------- |
| `cman.ora`      | the `cman_proxy` configuration — `tdm = true`, `max_connections`, the `rule_list`, `registration_invited_nodes` |
| `oraaccess.xml` | `<events>true</events>`, which lets CMAN consume FAN in-band                                                    |

Their contents and meaning are in [Configuration primitives](#configuration-primitives) above.

## Tuning: drains, timeouts, rebalance, TTL

Two families of knobs decide how a drain behaves: **server-side service attributes** (what the
database does during planned maintenance) and **client-side pool settings** (how fast the pool
reacts and re-spreads afterwards). Values below are what this showcase ships; change them with the
"tweak" column.

| Knob                                       | Layer                                   | Now                            | What it does / what the wait is for                                                                                                                                                                                           | Tweak                                                                                                           |
| ------------------------------------------ | --------------------------------------- | ------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `drain_timeout`                            | service (`srvctl`) + `manage.py drain`  | 120 s service / 60 s per drain | Grace period after the drain notification during which sessions relocate **at a request boundary** before `stopoption` ends the rest. The wait is for in-flight work to reach a safe point.                                   | Raise for long-running transactions; sub-second queries never need it.                                          |
| `stopoption`                               | service                                 | `IMMEDIATE`                    | How leftover sessions end when `drain_timeout` expires: `IMMEDIATE` cuts them, `TRANSACTIONAL`/`POST_TRANSACTION` waits for the transaction.                                                                                  | Leave `IMMEDIATE` for a planned demo; use transactional stops for OLTP that must not be cut.                    |
| `replay_init_time`                         | service                                 | 300 s (default)                | How long AC keeps trying to **initiate** replay after a recoverable error before giving up with `ORA-25415`. Not the bottleneck here — errors fired ~9 s in, far under 300 s.                                                 | Raise only if replay legitimately needs minutes to land on the survivor.                                        |
| `rlbgoal SERVICE_TIME`                     | service                                 | on                             | Publishes runtime load advisories over FAN. Removes the wait where the pool has nothing telling it a restored node is idle, so it re-spreads lazily. `SERVICE_TIME` routes to the fastest node.                               | `THROUGHPUT` to balance by work done instead of response time; `NONE` disables (what caused the pinned pool).   |
| `clbgoal SHORT`                            | service                                 | on                             | Connection-time balancing for **new** connections. `SHORT` spreads by session count (right for pools).                                                                                                                        | `LONG` for long-lived, non-pooled connections.                                                                  |
| `maxConnectionReuseTime`                   | UCP client (`SmartWorkload.java`)       | 180 s                          | Retires an aged pooled connection so its replacement re-spreads onto a restored node. Bounds how long a connection can stay pinned when FAN _up_ events rebalance only lazily.                                                | 30–60 s for aggressive re-spread (more reconnects); `0` disables.                                               |
| `oracle.net.CONNECT_TIMEOUT`               | both clients                            | 20 s smart / 20 s dumb         | Bounds the TCP + logon to a backend. Too low aborts the slow first TDM logon and breaks reconnects.                                                                                                                           | Keep ≥ the observed cold-gateway logon (~10–20 s).                                                              |
| statement query timeout                    | both clients                            | 30 s smart / 15 s dumb         | How fast a dead session is detected. Must sit **above** the first-query/TDM warmup (~10–20 s) or healthy slow queries get killed as false errors.                                                                             | Lower to detect failures faster once you know your warmup cost.                                                 |
| `idle_timeout` / `inbound_connect_timeout` | `cman.ora`                              | 0 / 60 s                       | CMAN idle-connection close (0 = never) and how long CMAN waits for a client connect handshake.                                                                                                                                | Set `idle_timeout` > 0 to reap abandoned clients.                                                               |
| `oraaccess <session_pool> min_size`        | `oraaccess.xml` (CMAN)                  | `min 8 / max 32`               | PRCP keeps this many warm backend gateway sessions to the service even when idle, so a restored or surviving node is never cold. A `SERVER=POOLED` client checks one out per request instead of building a ~10 s TDM gateway. | Raise `min_size` for a larger smart pool; delete the `<session_pool>` block to fall back to dedicated gateways. |
| `SERVER=POOLED`                            | smart client URL (`SmartWorkload.java`) | on                             | Routes the pooling-aware smart client to CMAN's PRCP pool. The dumb client stays dedicated (EZConnect).                                                                                                                       | Remove to compare dedicated-gateway cold-start behaviour.                                                       |
| `tdm_prcp_max_call_wait_time` / `_txn_`    | `cman.ora`                              | 60 s / 120 s                   | How long a client may hold a checked-out PRCP session idle (and once a transaction is open) before CMAN reclaims it back to the pool.                                                                                         | Raise for bursty clients that pause mid-session.                                                                |
| `connectionWaitTimeout`                    | UCP client (`SmartWorkload.java`)       | 30 s                           | A borrow waits this long for a free/warm session before failing with `UCP-29`. Absorbs a still-filling PRCP pool right after a drain as brief latency instead of an error.                                                    | Lower to surface borrow failures faster.                                                                        |
| `min` / `max` pool size                    | UCP client (`SmartWorkload.java`)       | 4 / 8                          | `min` below `max` means a drain rebuilds the pool only down to `min`, so fewer cold gateway builds land on the survivor at once.                                                                                              | Set `min = max` to keep every connection warm, at the cost of more rebuilds on a drain.                         |

**Draining both nodes in sequence.** Draining a node pushes the whole smart pool onto the survivor.
CMAN's PRCP pool keeps `min_size` warm backend sessions, so the survivor is not cold: the smart
pool checks out warm sessions, and `connectionWaitTimeout` absorbs any residual pool-fill delay as
brief latency rather than a `UCP-29` borrow error. `restore` brings the drained node back, and
`rlbgoal` + `maxConnectionReuseTime` re-spread the pool across both nodes. Operationally, **wait
until the "Smart pool spread" panel shows both nodes populated before draining the other one.**

## Where the logs are

| Log                         | Location                                                                  | How to read it                                                                                                                                                 |
| --------------------------- | ------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| CMAN status / registrations | live, in-process                                                          | `cmctl show status -c cman_proxy`, `cmctl show services -c cman_proxy`                                                                                         |
| CMAN alert + trace          | ADR diag tree under the client `ORACLE_BASE`                              | `cmctl show parameter log_directory` / `trace_directory` to find the exact path, then read `cman_proxy/{alert,trace}`                                          |
| Database alert log          | `$ORACLE_BASE/diag/rdbms/<db>/<sid>/trace/alert_<sid>.log` on the DB node | `tail -f` as `oracle`                                                                                                                                          |
| Service config / status     | live                                                                      | `srvctl config service -db "$D" -service myapp`, `srvctl status service ...`                                                                                   |
| Ops bootstrap               | `/var/log/cman-bootstrap.log` on the ops host                             | `python manage.py info` prints the tail command; complete when `/var/lib/cman-bootstrap.ok` exists                                                             |
| Workload metrics            | InfluxDB `workload` bucket                                                | the Grafana **CMAN-TDM Resiliency** dashboard, or the InfluxDB UI at `:8086`                                                                                   |
| Client FAN trace            | smart client stdout, `FAN_DEBUG=true ./demo/run-smart.sh`                 | streams `oracle.simplefan` + `oracle.ucp`; during a drain, **silence here means the pool never received the FAN event** — CMAN isn't relaying it to the client |

Each workload error ships its full detail, not just a count: an `err` tag (the code prefix, e.g.
`UCP-29` or `ORA-03113` — the borrow-vs-database answer at a glance) plus an `err_msg` field that
walks to the root cause (a `UCP-29` pool wrapper still shows the underlying `ORA-`/`TNS-` reason).
The dashboard's **Error log** table renders these newest-first: time, client, code, message.

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

## Drain and restore by hand

`manage.py drain` / `restore` wrap `srvctl` on the RAC node. Run the same operations directly to
drive the drain without `manage.py`.

**Over SSH (`srvctl`, server-side draining).** Hop through the ops host to a DB node as `oracle`:

```bash
ssh -i <key> opc@<ops_ip>
DB=$(awk -F= '/dbnode/{print $NF}' ~/hosts.ini)
ssh -i ~/private.key opc@"$DB"
sudo su - oracle
export ORACLE_HOME=$(ls -d /u01/app/oracle/product/*/dbhome_* | head -1)
export PATH=$ORACLE_HOME/bin:$PATH
D=$(srvctl config database | head -1)

srvctl status service -db "$D" -service myapp
# drain off one instance (planned maintenance), leaving the survivor serving:
srvctl stop service  -db "$D" -service myapp -instance dbcman1 -drain_timeout 60 -stopoption immediate -force
# restore on all nodes:
srvctl start service -db "$D" -service myapp
```

**From SQLcl (`DBMS_SERVICE`).** Connected to the _instance you want to drain_ as a privileged user
(e.g. `system`), the same drain is a PL/SQL call — `DBMS_SERVICE` acts on the local instance only:

```sql
exec dbms_service.stop_service('myapp', drain_timeout => 60, stop_option => dbms_service.post_transaction);
-- restore:
exec dbms_service.start_service('myapp');
```

`srvctl` is the path `manage.py drain` uses because it can target a named instance from anywhere on
the cluster; the `DBMS_SERVICE` form is handy when you already have a SQLcl session on the node.

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
