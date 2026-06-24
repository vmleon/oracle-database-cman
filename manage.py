#!/usr/bin/env python3
"""Orchestrator CLI for the Oracle CMAN-TDM showcase (foundation slice)."""
import configparser
import json
import os
import secrets
import subprocess
import urllib.request
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


if __name__ == "__main__":
    app()
