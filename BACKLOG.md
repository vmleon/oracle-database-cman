# Backlog

Pending work to grow the deployed foundation slice into the full showcase described in
[cman-showcase-design.md](cman-showcase-design.md). The foundation slice — single 2-node RAC,
one CMAN-TDM instance with an IP-allow rule, a non-AC `health` service, and a verified
laptop→CMAN→RAC query — is in place; everything below is not yet built.

## Enabling infrastructure

- **Second RAC DB system (DB2).** A second `oci_database_db_system` (2-node RAC, Extreme
  Performance) in the private subnet. Required for service routing and for the Data Guard
  upgrade scenario.
- **ONS proxy on the CMAN host.** For the scenario where the client app subscribes to RAC FAN
  events through CMAN. Not needed for CMAN-driven draining.
- **Spring Boot + UCP client app (`app/`).** The workload driver used by the draining and
  upgrade demos; connects only to the CMAN endpoint.
- **`docs/scenarios.md`.** Variations documented but not deployed: editions, on-prem,
  multi-cloud, GoldenGate.

## `manage.py` verbs

- **`build` / `run`.** Build and run the Spring Boot client.
- **`demo firewall | route | socks | tls | pool | drain | upgrade`.** Drive each use-case end to
  end and assert the expected outcome.

## Use-cases

Each is an independent demo against the deployed stack, plus the configuration it needs.

1. **Access-control firewall.** Extend `cman.ora` `rule_list` to accept/reject by source IP
   **and** service name with a default-deny `reject` rule. Today the rule list has a single
   IP-allow rule with `srv = *`.
2. **Service / multi-tenant routing.** Route service `SALES` to cluster 1 and `HR` to cluster 2.
   Needs DB2.
3. **SOCKS5 → CMAN handoff.** A SOCKS5 dumb relay in front of CMAN: SOCKS5 carries bytes, CMAN
   adds Oracle Net awareness.
4. **TCP ↔ TCPS protocol translation.** Client connects plain TCP; CMAN holds the wallet and
   originates mutual TLS to the database. Adds a TCPS listener (`:1523`).
5. **Connection multiplexing (PRCP).** Funnel many client connections onto fewer database
   sessions via Proxy Resident Connection Pooling on the CMAN tier.
6. **Planned-maintenance draining (AC/TAC).** AC-enabled services
   (`-failovertype AUTO -failover_restore AUTO -commit_outcome TRUE -notification TRUE
-drain_timeout 120`), `oraaccess.xml` with `<events>true</events>`, and the
   `srvctl stop service` / `relocate` workflow so CMAN drains in-flight work and continues it on
   a surviving instance. Today the `health` service is plain (no AC attributes), and no
   `oraaccess.xml` is deployed.
7. **RAC SCAN redirect, resolved server-side.** A formal demo and assertion. The mechanism is
   already exercised by the `health` path (CMAN follows the SCAN redirect to a node VIP itself).
8. **Transparent platform migration and upgrade.** Pair the two clusters as Active Data Guard
   and run a switchover behind CMAN while a client workload keeps running. Needs DB2.

## Items to confirm during implementation

- Smallest credible VM shapes, minimum OCPUs, and minimum storage for the RAC systems; concrete
  running cost.
- Exact NSG/security-list rules: CMAN→SCAN/VIP/listener on 1521 (and 1522 for TCPS), ONS ports.
- Precise `srvctl` service attributes and the `stop service` / `relocate` workflow that yield a
  clean end-to-end draining demo.
- Whether the two systems run different base versions for the upgrade scenario, or use Data Guard
  rolling upgrade (`DBMS_ROLLING`) between matched systems.
