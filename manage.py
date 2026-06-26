#!/usr/bin/env python3
"""Orchestrator CLI for the Oracle CMAN-TDM showcase (foundation slice)."""
import configparser
import json
import os
import secrets
import subprocess
import urllib.request
import zipfile
from pathlib import Path

import typer
from dotenv import dotenv_values

app = typer.Typer(no_args_is_help=True, invoke_without_command=True)


@app.callback()
def _main():
    """Orchestrator CLI for the Oracle CMAN-TDM showcase."""
TF_DIR = Path("infra/terraform")
ENV_FILE = Path(".env")
TFVARS_FILE = TF_DIR / "terraform.tfvars"

# maps terraform output keys -> config keys
_TF_MAP = {
    "cman_public_ip": "CMAN_HOST",
    "ops_public_ip": "OPS_HOST",
    "db_service_name": "DB_SERVICE",
    "db_name": "DB_NAME",
}


def _read_env() -> dict:
    return {k: v for k, v in dotenv_values(".env").items() if v is not None}


def _read_tf_output() -> dict:
    try:
        out = subprocess.run(
            ["terraform", "output", "-json"], cwd=TF_DIR,
            capture_output=True, text=True, check=True,
        ).stdout
        return json.loads(out or "{}")
    except (subprocess.CalledProcessError, FileNotFoundError):
        return {}


def load_config(cli_overrides: dict) -> dict:
    cfg = {}
    tf = _read_tf_output()
    for tf_key, cfg_key in _TF_MAP.items():
        if tf_key in tf:
            cfg[cfg_key] = tf[tf_key]["value"]
    cfg.update(_read_env())
    cfg.update({k: v for k, v in cli_overrides.items() if v is not None})
    return cfg


_SECRET_FLAGS = {"--password", "--admin-password"}


def _sh(cmd, **kw):
    safe = ["***" if i > 0 and cmd[i - 1] in _SECRET_FLAGS else a for i, a in enumerate(cmd)]
    typer.echo("$ " + " ".join(safe))
    return subprocess.run(cmd, check=True, **kw)


def _generate_password(length=20):
    """Oracle-compliant: starts with a letter, 2+ specials, 2+ digits."""
    letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    digits = "0123456789"
    specials = "#_-"
    pw = [secrets.choice(letters), secrets.choice(specials), secrets.choice(specials),
          secrets.choice(digits), secrets.choice(digits)]
    alphabet = letters + digits + specials
    pw += [secrets.choice(alphabet) for _ in range(length - 5)]
    tail = pw[1:]
    secrets.SystemRandom().shuffle(tail)
    pw[1:] = tail
    return "".join(pw)


# --- setup helpers ---------------------------------------------------------

def _read_oci_config():
    path = Path.home() / ".oci" / "config"
    if not path.exists():
        typer.echo(f"OCI config not found at {path}. Run 'oci setup config' first.")
        raise typer.Exit(1)
    parser = configparser.ConfigParser()
    parser.read(path)
    profiles = list(parser.sections())
    if parser.defaults():
        profiles.insert(0, "DEFAULT")
    return profiles, parser


def _sdk_config(parser, profile):
    p = parser[profile]
    return {
        "user": p.get("user"),
        "key_file": p.get("key_file"),
        "fingerprint": p.get("fingerprint"),
        "tenancy": p.get("tenancy"),
        "region": p.get("region", "us-phoenix-1"),
    }


def _list_regions(sdk_config):
    import oci

    try:
        client = oci.identity.IdentityClient(sdk_config)
        tenancy_id = sdk_config["tenancy"]
        home = client.get_tenancy(tenancy_id).data.home_region_key
        subs = client.list_region_subscriptions(tenancy_id).data
        regions = [{"name": s.region_name, "is_home": s.region_key == home} for s in subs]
        regions.sort(key=lambda x: (not x["is_home"], x["name"]))
        return regions
    except Exception as e:
        typer.echo(f"Warning: could not fetch regions: {e}")
        return None


def _list_compartments(sdk_config):
    import oci

    try:
        client = oci.identity.IdentityClient(sdk_config)
        tenancy_id = sdk_config["tenancy"]
        tenancy = client.get_compartment(tenancy_id).data
        comps = [{"name": f"{tenancy.name} (root)", "id": tenancy_id}]
        resp = oci.pagination.list_call_get_all_results(
            client.list_compartments,
            compartment_id=tenancy_id,
            compartment_id_in_subtree=True,
            access_level="ACCESSIBLE",
        )
        for c in resp.data:
            if c.lifecycle_state == "ACTIVE":
                comps.append({"name": c.name, "id": c.id})
        return comps
    except Exception as e:
        typer.echo(f"Warning: could not fetch compartments: {e}")
        return None


def _detect_public_ip():
    for url in ("https://api.ipify.org", "https://checkip.amazonaws.com"):
        try:
            return urllib.request.urlopen(url, timeout=5).read().decode().strip()
        except Exception:
            continue
    return None


def _write_env(values):
    ENV_FILE.write_text("".join(f'{k}="{v}"\n' for k, v in values.items()))


def _write_tfvars(values):
    lines = []
    for k, v in values.items():
        if isinstance(v, bool):
            lines.append(f"{k} = {str(v).lower()}")
        else:
            lines.append(f'{k} = "{v}"')
    TFVARS_FILE.write_text("\n".join(lines) + "\n")


def _validate_client_zip(path):
    """Check the path is a usable Oracle 19c Administrator client zip."""
    p = Path(path).expanduser()
    if not p.is_file():
        return False, "file not found"
    try:
        with zipfile.ZipFile(p) as z:
            names = z.namelist()
    except zipfile.BadZipFile:
        return False, "not a valid zip file"
    if "client/runInstaller" not in names:
        return False, "not the Administrator client zip (missing client/runInstaller)"
    return True, ""


# --- commands --------------------------------------------------------------

@app.command()
def setup():
    """Interactive OCI configuration. Writes .env and terraform.tfvars — no manual editing."""
    from InquirerPy import inquirer
    from rich.console import Console
    from rich.panel import Panel

    console = Console()
    console.print("[bold]Oracle CMAN-TDM — Setup[/bold]\n")

    console.print("Checking tools:")
    for tool in ["oci", "terraform", "ansible", "sql", "ssh"]:
        ok = subprocess.run(["which", tool], capture_output=True).returncode == 0
        console.print(f"  {'[green]OK[/green]     ' if ok else '[red]MISSING[/red]'} {tool}")

    profiles, parser = _read_oci_config()
    profile = inquirer.select(
        message="OCI profile:", choices=profiles, default=profiles[0]
    ).execute()
    sdk_config = _sdk_config(parser, profile)

    console.print("\nFetching subscribed regions...")
    regions = _list_regions(sdk_config)
    if regions:
        choices = [f"{r['name']} (home)" if r["is_home"] else r["name"] for r in regions]
        region = inquirer.select(
            message="Region:", choices=choices, default=choices[0]
        ).execute().replace(" (home)", "")
    else:
        region = inquirer.text(message="Region:", default=sdk_config["region"]).execute()
    sdk_config["region"] = region

    console.print("\nFetching compartments...")
    comps = _list_compartments(sdk_config)
    if comps:
        comp_map = {c["name"]: c["id"] for c in comps}
        selected = inquirer.fuzzy(
            message="Compartment (type to search):", choices=list(comp_map)
        ).execute()
        compartment_ocid = comp_map[selected]
    else:
        compartment_ocid = inquirer.text(message="Compartment OCID:").execute()

    detected = _detect_public_ip()
    if detected:
        console.print(f"\nDetected your public IP: [bold]{detected}[/bold]")
    client_cidr = inquirer.text(
        message="Client CIDR (your egress IP — locks CMAN ingress):",
        default=f"{detected}/32" if detected else "0.0.0.0/0",
    ).execute()

    ssh_dir = Path.home() / ".ssh"
    keys = (
        sorted(
            f.name for f in ssh_dir.iterdir()
            if f.is_file() and not f.suffix and f.with_suffix(".pub").exists()
        )
        if ssh_dir.is_dir() else []
    )
    if keys:
        ssh_private = str(ssh_dir / inquirer.fuzzy(message="SSH private key:", choices=keys).execute())
    else:
        ssh_private = inquirer.text(message="SSH private key path:").execute()
    ssh_public_path = ssh_private + ".pub"
    if Path(ssh_public_path).exists():
        ssh_public_key = Path(ssh_public_path).read_text().strip()
    else:
        ssh_public_key = inquirer.text(message="SSH public key (paste content):").execute()

    db_name = inquirer.text(
        message="Database name (used for resource naming):", default="dbcman"
    ).execute()

    default_client_zip = str(Path.home() / "Downloads" / "LINUX.X64_193000_client.zip")
    while True:
        client_zip = inquirer.text(
            message="Oracle 19c client Administrator zip path:",
            default=default_client_zip,
        ).execute()
        ok, why = _validate_client_zip(client_zip)
        if ok:
            client_zip = str(Path(client_zip).expanduser().resolve())
            break
        console.print(f"[red]Cannot use this zip: {why}[/red]")

    db_password = _generate_password()
    tdm_password = _generate_password()
    appuser_password = _generate_password()

    console.print(Panel(
        f"Profile:       {profile}\n"
        f"Region:        {region}\n"
        f"Compartment:   {compartment_ocid}\n"
        f"Client CIDR:   {client_cidr}\n"
        f"SSH key:       {ssh_private}\n"
        f"Database name: {db_name}\n"
        f"Client zip:    {client_zip}\n"
        f"DB password:   (generated — stored in .env and terraform.tfvars)",
        title="Configuration Summary",
    ))
    if not inquirer.confirm(message="Save configuration?", default=True).execute():
        console.print("[yellow]Setup cancelled.[/yellow]")
        raise typer.Exit(0)

    _write_env({
        "OCI_PROFILE": profile,
        "OCI_REGION": region,
        "COMPARTMENT_OCID": compartment_ocid,
        "CLIENT_CIDR": client_cidr,
        "SSH_PRIVATE_KEY_PATH": ssh_private,
        "SSH_PUBLIC_KEY_PATH": ssh_public_path,
        "DB_NAME": db_name,
        "DB_USER": "system",
        "DB_PASSWORD": db_password,
        "APPUSER_PASSWORD": appuser_password,
    })
    _write_tfvars({
        "oci_profile": profile,
        "region": region,
        "tenancy_ocid": sdk_config["tenancy"],
        "user_ocid": sdk_config["user"],
        "fingerprint": sdk_config["fingerprint"],
        "private_key_path": sdk_config["key_file"],
        "compartment_ocid": compartment_ocid,
        "client_cidr": client_cidr,
        "ssh_public_key": ssh_public_key,
        "ssh_private_key_path": ssh_private,
        "db_admin_password": db_password,
        "tdm_password": tdm_password,
        "appuser_password": appuser_password,
        "db_name": db_name,
        "cman_client_source_path": client_zip,
    })

    console.print(f"\n[green]Wrote {ENV_FILE} and {TFVARS_FILE}[/green]")
    console.print("\nNext step: [bold]python manage.py tf apply[/bold]")


@app.command()
def tf(action: str):
    """plan | apply | destroy (reads infra/terraform/terraform.tfvars)."""
    env = {**os.environ, "GODEBUG": "netdns=cgo"}
    if action in ("plan", "apply"):
        _sh(["terraform", "init", "-input=false"], cwd=TF_DIR, env=env)
    args = ["terraform", action]
    if action in ("apply", "destroy"):
        args.append("-auto-approve")
    _sh(args, cwd=TF_DIR, env=env)


@app.command()
def info():
    """Show endpoints and copy-paste connect/SSH commands."""
    from rich.console import Console
    from rich.panel import Panel
    console = Console()
    cfg = load_config({})
    tf = _read_tf_output()
    cman_ip = tf.get("cman_public_ip", {}).get("value")
    ops_ip = tf.get("ops_public_ip", {}).get("value")
    if not cman_ip:
        console.print("[yellow]No Terraform outputs — run 'python manage.py tf apply' first.[/yellow]")
        raise typer.Exit(1)
    key = cfg.get("SSH_PRIVATE_KEY_PATH", "~/.ssh/id_rsa")
    svc = cfg.get("DB_SERVICE", "health")
    console.print(Panel(
        f"Region:       {cfg.get('OCI_REGION','?')}\n"
        f"CMAN endpoint: {cman_ip}:1521/{svc}\n"
        f"Ops host:      {ops_ip}\n"
        f"DB user:       {cfg.get('DB_USER','system')}",
        title="Deployment",
    ))
    console.print("\n[bold]Tail the ops bootstrap log[/bold]")
    console.print(f"ssh -i {key} opc@{ops_ip} 'sudo tail -n 80 /var/log/cman-bootstrap.log'")
    console.print("\n[bold]Health through CMAN (local SQLcl)[/bold]")
    console.print("python manage.py sql   # one-time: save the 'cman' connection locally")
    console.print("python manage.py health")


@app.command()
def sql():
    """Create the 'cman' SQLcl saved connection on this laptop (TERM=dumb)."""
    cfg = load_config({})
    host = cfg.get("CMAN_HOST")
    if not host:
        typer.echo("No CMAN host yet — run 'tf apply' first.")
        raise typer.Exit(1)
    user = cfg.get("DB_USER", "system")
    pwd = cfg["DB_PASSWORD"]
    svc = cfg.get("DB_SERVICE", "health")
    script = f"conn -save cman -replace -savepwd {user}/{pwd}@{host}:1521/{svc}\nEXIT;\n"
    env = {**os.environ, "TERM": "dumb"}
    subprocess.run(["sql", "/nolog"], input=script, text=True, env=env, check=True)
    typer.echo("Saved connection 'cman'. Use: TERM=dumb sql -name cman")


@app.command()
def health():
    """Run a query through the CMAN endpoint via the saved 'cman' connection."""
    env = {**os.environ, "TERM": "dumb"}
    rc = subprocess.run(
        ["sql", "-name", "cman", "-S"],
        input="select instance_name from v$instance;\nexit;\n",
        text=True, env=env, capture_output=True,
    )
    typer.echo(rc.stdout.strip())
    raise typer.Exit(0 if (rc.returncode == 0 and rc.stdout.strip()) else 1)


GENERATED_DIR = TF_DIR / "generated"


@app.command()
def clean(destroy: bool = False):
    """Remove local build artefacts (infra/terraform/generated/); with --destroy also tear down all provisioned infrastructure."""
    for f in GENERATED_DIR.iterdir():
        if f.name != ".gitkeep":
            if f.is_file():
                f.unlink()
            elif f.is_dir():
                import shutil
                shutil.rmtree(f)
    typer.echo(f"Cleared {GENERATED_DIR}")
    if destroy:
        _sh(["terraform", "destroy", "-auto-approve"], cwd=TF_DIR)


if __name__ == "__main__":
    app()
