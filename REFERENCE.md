# Reference

The deeper explanation behind the showcase: how CMAN-TDM works, what each kind of client gets from
it, where the logs are, and the cmctl / SQLcl / `srvctl` commands worth knowing. The runbook is in
[DEMO.md](DEMO.md); the value framing and the layer-ownership table are in [README.md](README.md).

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
the handshake. CMAN consumes FAN in-band when `oraaccess.xml` sets `<events>true</events>`; for the
database to _publish_ FAN to CMAN over ONS, the DB→CMAN ONS port (6200) must also be open.

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

**`health` service (`srvctl`)** — Transparent Application Continuity attributes, preferred on both
instances:

```bash
srvctl add service -db "$D" -service health \
  -preferred dbcman1,dbcman2 \
  -failovertype AUTO -failover_restore AUTO \
  -commit_outcome TRUE -notification TRUE \
  -drain_timeout 120 -stopoption IMMEDIATE
```

`FAILOVER_TYPE=AUTO` enables TAC (it is not on by default); `drain_timeout` bounds the grace period
that starts when the drain notification is sent.

## Where the logs are

| Log                         | Location                                                                  | How to read it                                                                                                        |
| --------------------------- | ------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| CMAN status / registrations | live, in-process                                                          | `cmctl show status -c cman_proxy`, `cmctl show services -c cman_proxy`                                                |
| CMAN alert + trace          | ADR diag tree under the client `ORACLE_BASE`                              | `cmctl show parameter log_directory` / `trace_directory` to find the exact path, then read `cman_proxy/{alert,trace}` |
| Database alert log          | `$ORACLE_BASE/diag/rdbms/<db>/<sid>/trace/alert_<sid>.log` on the DB node | `tail -f` as `oracle`                                                                                                 |
| Service config / status     | live                                                                      | `srvctl config service -db "$D" -service health`, `srvctl status service ...`                                         |
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
srvctl config service  -db "$D" -service health       # AC/drain attributes
srvctl status service  -db "$D" -service health        # which instances serve health
lsnrctl status                                         # local listener + registered services
```

To force a DB instance to re-register with CMAN after a reload, run `alter system register;` on
that instance as a privileged user instead of waiting out the registration interval.

## Oracle documentation

- Oracle Net Services Administrator's Guide — _Configuring Oracle Connection Manager_ and _Traffic
  Director Mode_.
- Oracle Net Services Reference — _Oracle Connection Manager Parameters_ (`tdm`, `rule_list`,
  `max_connections`, `registration_invited_nodes`, PRCP tuning).
- CMAN-TDM whitepaper — _Oracle Database Connection Proxy for Scalable Applications_.
- Oracle RAC Administration — _Ensuring Application Continuity_ (`FAILOVER_TYPE`/`FAILOVER_RESTORE`,
  FAN, draining).
- Base Database Service — editions and feature gating (RAC and Active Data Guard require Extreme
  Performance).
- OCI Terraform provider — `oci_database_db_system` (`node_count`, `database_edition`).
