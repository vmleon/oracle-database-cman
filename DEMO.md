# Demo

## Health walkthrough

This walkthrough confirms that the laptop reaches the database exclusively through the CMAN endpoint — never by addressing Real Application Clusters (RAC) nodes directly.

**Prerequisite:** Run `python manage.py sql` once to save the `cman` SQLcl named connection (laptop requires SQLcl: `brew install --cask sqlcl`).

### Run the health check

```bash
python manage.py health
```

`manage.py health` automates the full check: it connects to the saved `cman` SQLcl named connection, runs `select instance_name from v$instance;` through CMAN, and prints the result.

### What it does manually

If you want to run the query by hand:

```bash
TERM=dumb sql -name cman
```

Then at the SQL prompt:

```sql
select instance_name from v$instance;
```

The result is the name of the RAC node that served the connection, for example `dbcman1` or `dbcman2`. That name comes from inside the private subnet — the laptop never communicated with it directly. The only address the laptop used was the CMAN endpoint (printed by `python manage.py info`); CMAN resolved the Single Client Access Name (SCAN) redirect to a node VIP server-side and forwarded the Oracle Net session into the private subnet.

### What this proves

A dumb TCP relay forwards bytes to a fixed destination. CMAN parses the Oracle Net handshake, applies the `RULE_LIST` (accept/reject by source IP and service name), follows the SCAN redirect itself, and forwards the session to a live RAC node. The client configured one address and received a response from behind the proxy without any knowledge of the RAC topology.
