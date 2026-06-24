# Deploy

## Prerequisites

Install Python dependencies and external tools:

```bash
pip install -e ".[dev]"
brew install --cask sqlcl
# also required on PATH: oci, terraform, ansible
```

## Provisioning steps

### 1. Configure

```bash
python manage.py setup
```

Interactive: selects OCI profile, region, compartment, SSH key, and client CIDR; generates a DB password; writes `.env` and `infra/terraform/terraform.tfvars`. Nothing is edited by hand after this point.

### 2. Stage the CMAN binaries

The `cman-poc-artifacts` Object Storage bucket is created by `tf apply` (step 3). Upload the Oracle Database 19c Client (Administrator) zip after the bucket exists:

```bash
oci os object put \
  --bucket-name cman-poc-artifacts \
  --name client.zip \
  --file <path-to>/LINUX.X64_193000_client.zip
```

On first run the ordering is: run `tf apply` → upload → the ops host bootstrap cloud-init pulls `client.zip` from the bucket during self-provisioning. If the upload completes before `tf apply` finishes, the ops host picks it up automatically.

### 3. Provision infrastructure

```bash
python manage.py tf apply
```

Stands up the VCN, subnets, NSGs, the CMAN VM, the ops/bastion VM, and the RAC DB system. The ops host self-provisions via cloud-init: installs Ansible, pulls the roles, and configures CMAN-TDM on the CMAN host and creates services on the database — no SSH push from the operator.

### 4. Confirm bootstrap completion

```bash
python manage.py info
```

This prints the ops host IP and the exact SSH command to tail the bootstrap log. Tail until the sentinel file appears:

```bash
ssh -i <key> opc@<ops_ip> 'sudo tail -f /var/log/cman-bootstrap.log'
# bootstrap is complete when /var/lib/cman-bootstrap.ok exists
```

### 5. Verify end-to-end connectivity

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
