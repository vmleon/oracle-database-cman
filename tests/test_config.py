import string

import manage


def test_precedence_cli_over_env_over_tf(monkeypatch):
    monkeypatch.setattr(manage, "_read_env", lambda: {"CMAN_HOST": "from-env"})
    monkeypatch.setattr(manage, "_read_tf_output",
                        lambda: {"cman_public_ip": {"value": "from-tf"}})
    cfg = manage.load_config({"CMAN_HOST": "from-cli"})
    assert cfg["CMAN_HOST"] == "from-cli"


def test_env_falls_back_to_tf(monkeypatch):
    monkeypatch.setattr(manage, "_read_env", lambda: {})
    monkeypatch.setattr(manage, "_read_tf_output",
                        lambda: {"cman_public_ip": {"value": "1.2.3.4"}})
    cfg = manage.load_config({})
    assert cfg["CMAN_HOST"] == "1.2.3.4"


def test_generated_password_is_oracle_compliant():
    pw = manage._generate_password()
    assert len(pw) == 20
    assert pw[0] in string.ascii_letters
    assert sum(c.isdigit() for c in pw) >= 2
    assert sum(c in "#_-" for c in pw) >= 2
