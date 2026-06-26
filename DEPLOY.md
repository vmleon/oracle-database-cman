# Deploy

## Prerequisites

Install Python dependencies and external tools:

```bash
pip install -e ".[dev]"
brew install --cask sqlcl
# also required on PATH: oci, terraform, ansible
```

### Download the Oracle 19c client installer

CMAN ships inside the Oracle Client; the CMAN host installs it with a **Custom** install that selects the Oracle Connection Manager component (`oracle.network.cman`), which the Administrator install type omits. This file is license-gated, so it cannot be fetched automatically — download it once and note the path. `setup` asks for that path, uploads the file to Object Storage, and the CMAN host pulls it during provisioning.

1. Open the [Oracle Database 19c for Linux x86-64 download page](https://www.oracle.com/database/technologies/oracle19c-linux-downloads.html).
2. Sign in with an Oracle account and accept the license agreement.
3. Under **Client (64-bit)**, download `LINUX.X64_193000_client.zip` (1,134,912,540 bytes).
   - Pick **Client (64-bit)**, not _Client Home_ (image-based, no `runInstaller`), not the 32-bit client, and not the database or Grid homes.
4. Optionally verify the download:

   ```bash
   shasum -a 256 ~/Downloads/LINUX.X64_193000_client.zip
   # expect: ee33637947d760413790d16bd11fc273a6e8c0ee3193e215a85af4c9dd1a5834
   ```

Leave the file at `~/Downloads/LINUX.X64_193000_client.zip` to accept the `setup` default, or move it anywhere and give `setup` that path. `setup` rejects the file if it is not the Administrator client zip (it checks for `client/runInstaller` inside).

## Provisioning steps

### 1. Configure

```bash
python manage.py setup
```

Interactive: selects Oracle Cloud Infrastructure (OCI) profile, region, compartment, SSH key, and client CIDR; asks for the local path to the Oracle 19c client Administrator zip (`LINUX.X64_193000_client.zip`, downloaded from Oracle's Database 19c download page) and validates it; generates a DB password; writes `.env` and `infra/terraform/terraform.tfvars`. Nothing is edited by hand after this point.

### 2. Provision infrastructure

```bash
python manage.py tf apply
```

Stands up the Virtual Cloud Network (VCN), subnets, Network Security Groups (NSGs), the CMAN VM, the ops/bastion VM, and the Real Application Clusters (RAC) DB system. Uploads the client zip and the Ansible roles to the `cman-poc-artifacts` Object Storage bucket and creates the pre-authenticated requests the ops host bootstrap reads. The ops host self-provisions via cloud-init: installs Ansible, pulls the roles, and configures CMAN-TDM on the CMAN host and creates services on the database — no SSH push from the operator.

### 3. Confirm bootstrap completion

```bash
python manage.py info
```

This prints the ops host IP and the exact SSH command to tail the bootstrap log. Tail until the sentinel file appears:

```bash
ssh -i <key> opc@<ops_ip> 'sudo tail -f /var/log/cman-bootstrap.log'
# bootstrap is complete when /var/lib/cman-bootstrap.ok exists
```

### 4. Verify end-to-end connectivity

```bash
python manage.py sql     # saves the 'cman' SQLcl named connection locally
python manage.py health  # runs a query through the CMAN endpoint
```

`health` prints the RAC instance name returned by `select instance_name from v$instance` — routed through CMAN.

## Teardown

```bash
python manage.py clean --destroy
```

Destroys all OCI infrastructure (Terraform `destroy`). Run between sessions to avoid idle costs.

## Next

Validate end-to-end → [DEMO.md](DEMO.md): prove the laptop reaches the database only through CMAN.
