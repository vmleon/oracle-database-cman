# Deploy

Provision the showcase stack on OCI and verify the laptop reaches the database only through CMAN.
Every command is run from the repository root.

## Prerequisites

Install the Python dependencies and the external tools on PATH:

```bash
pip install -e ".[dev]"
brew install --cask sqlcl
# also required on PATH: oci, terraform, ansible
```

`setup` checks for `oci`, `terraform`, `ansible`, `sql`, and `ssh` and reports any that are
missing.

### Oracle 19c client installer

CMAN ships inside the Oracle Client. The CMAN host installs it with a **Custom** install that
selects the Oracle Connection Manager component (`oracle.network.cman`) — the Administrator install
type omits it. The installer is license-gated, so it cannot be fetched automatically: download it
once and note the path. `setup` uploads the file to Object Storage and the CMAN host pulls it
during provisioning.

1. Open the [Oracle Database 19c for Linux x86-64 download page](https://www.oracle.com/database/technologies/oracle19c-linux-downloads.html).
2. Sign in with an Oracle account and accept the license agreement.
3. Under **Client (64-bit)**, download `LINUX.X64_193000_client.zip` (1,134,912,540 bytes) — not
   _Client Home_ (image-based, no `runInstaller`), not the 32-bit client, and not the database or
   Grid homes.
4. Optionally verify:

   ```bash
   shasum -a 256 ~/Downloads/LINUX.X64_193000_client.zip
   # expect: ee33637947d760413790d16bd11fc273a6e8c0ee3193e215a85af4c9dd1a5834
   ```

Leave the file at `~/Downloads/LINUX.X64_193000_client.zip` to accept the `setup` default, or move
it anywhere and give `setup` that path. `setup` rejects a zip that does not contain
`client/runInstaller`.

## Configure

```bash
python manage.py setup
```

Interactive: selects OCI profile, region, compartment, SSH key, and client CIDR; asks for the path
to the Oracle 19c client zip and validates it; generates the database, proxy-user, and app-user
passwords; writes `.env` and `infra/terraform/terraform.tfvars`. Nothing is hand-edited after this.

## Provision the stack

```bash
python manage.py tf apply
```

Stands up the Virtual Cloud Network (VCN), subnets, Network Security Groups (NSGs), the CMAN VM,
the ops/bastion VM, and the 2-node RAC DB system. Uploads the client zip and the Ansible roles to
the `cman-poc-artifacts` Object Storage bucket and creates the pre-authenticated requests the ops
host bootstrap reads. The ops host then **self-provisions via cloud-init**: it installs Ansible,
pulls the roles, configures CMAN-TDM on the CMAN host, and creates the `myapp` service on the
database over SSH — no SSH push from the operator.

Bootstrap is complete when the sentinel file `/var/lib/cman-bootstrap.ok` exists on the ops host.
`python manage.py info` prints the exact command to tail the bootstrap log:

```bash
ssh -i <key> opc@<ops_ip> 'sudo tail -f /var/log/cman-bootstrap.log'
```

## Verify connectivity

```bash
python manage.py sql     # one-time: save the 'cman' SQLcl named connection locally
python manage.py health  # run a query through the CMAN endpoint
```

`health` connects to the saved `cman` named connection, runs `select instance_name from v$instance`
through CMAN, and prints the RAC instance name that served it — for example `dbcman1` or `dbcman2`.
That name comes from inside the private subnet, yet the only address the laptop used is the CMAN
endpoint: proof the end-to-end path works and the laptop reaches a node without addressing it
directly. To run the query by hand:

```bash
TERM=dumb sql -name cman
```

```sql
select instance_name from v$instance;
```

When SQLcl connects you see `connected via Oracle Connection Manager in Traffic Director mode` — the
unambiguous sign the session goes through CMAN-TDM, not a direct hop. CMAN parsed the Oracle Net
handshake, applied the `RULE_LIST`, followed the SCAN redirect to a node VIP itself, and forwarded
the session.

With connectivity verified, the resiliency runbook — drain nodes under load and watch the clients
ride it out in Grafana — is in [DEMO.md](DEMO.md).

## What gets deployed

| Resource                     | Where          | Purpose                                                                                                              |
| ---------------------------- | -------------- | -------------------------------------------------------------------------------------------------------------------- |
| CMAN proxy VM                | Public subnet  | The only address the client knows. Runs CMAN-TDM on :1521. NSG allows ingress from the client CIDR on :1521 and :22. |
| Ops / bastion VM             | Public subnet  | Self-provisions via cloud-init, runs the Ansible `cman` and `db` roles, and is the SSH hop to the private DB nodes.  |
| 2-node RAC DB system         | Private subnet | Extreme Performance, SCAN + node VIPs on :1521, the `myapp` service. Unreachable from the client network.            |
| Object Storage bucket + PARs | Regional       | Carries the client zip and Ansible roles to the ops host bootstrap.                                                  |

The client authenticates through CMAN-TDM with proxy authentication: the `db` role creates an
`appuser` (client identity) and a `tdm` proxy user, and the `cman` role holds the `tdm` credential
in an auto-login wallet keyed to the full service name. `appuser` connects through CMAN; `tdm`
bootstraps the outbound gateway pool. See [REFERENCE.md](REFERENCE.md) for the configuration
primitives.

## Teardown

Teardown has two independent parts: the local observability stack and the OCI infrastructure.

```bash
cd demo && podman compose down && cd ..   # stop the local InfluxDB + Grafana (add -v to drop the metrics volume)
```

```bash
python manage.py clean --destroy          # destroy all OCI infrastructure (Terraform destroy)
```

`clean --destroy` only tears down what Terraform provisioned on OCI; the InfluxDB + Grafana
containers run locally and are managed by podman, so stop them with `podman compose down` separately.
`clean` without `--destroy` only clears local build artefacts under `infra/terraform/generated/`. Run
the teardown between sessions to avoid idle costs.
