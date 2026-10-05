"""The ``examples:`` every ``pravaha <command> --help`` ends with: real invocations, one to three.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Keyed by the command's path (``"streams describe"``). Each line is a whole command a person could
paste; ``test_cli_wiring`` parses every one with the real parser, so an example naming a flag the
command does not have fails the build rather than a reader. A ``#`` starts a comment.
"""

from __future__ import annotations

from typing import Optional

EXAMPLES: "dict[str, tuple[str, ...]]" = {
    # ------------------------------------------------------------------ Flight
    "query": (
        'pravaha query --sql "SELECT user_id, spend FROM hourly_spend WHERE user_id = ?" --params u1',
        "pravaha query --sql-file report.sql --tsv > report.tsv",
        "pravaha query --sql 'SHOW CONTINUOUS QUERIES' --json",
    ),
    "register": (
        "pravaha register --name spend_by_hour --sql-file spend.sql --keys 0,2 --retain P7D",
    ),
    "queries": ("pravaha queries", "pravaha queries --verbose", "pravaha queries --json"),
    "pause": ("pravaha pause --name spend_by_hour",),
    "resume": ("pravaha resume --name spend_by_hour",),
    "drop": (
        "pravaha drop --name spend_by_hour        # says what it would do, changes nothing",
        "pravaha drop --name spend_by_hour --yes",
    ),
    "replace": (
        "pravaha replace --name spend_by_hour --sql-file spend_v2.sql --keys 0,2 --wait",
        "pravaha replace --name spend_by_hour --sql-file spend_v2.sql --backfill none "
        "--cutover auto",
    ),
    "replacements": ("pravaha replacements", "pravaha replacements --name spend_by_hour"),
    "cutover": ("pravaha cutover --name spend_by_hour",),
    "rollback": ("pravaha rollback --name spend_by_hour",),
    "abandon": ("pravaha abandon --name spend_by_hour --yes",),
    "finish": ("pravaha finish --name spend_by_hour --yes",),
    "throttle": ("pravaha throttle --name spend_by_hour --rate 5000",),
    "pause-backfill": ("pravaha pause-backfill --name spend_by_hour",),
    "resume-backfill": ("pravaha resume-backfill --name spend_by_hour",),
    "subscribe": (
        "pravaha subscribe --view big_txn --filter merchant=TRAVELCO --snapshot",
        "pravaha subscribe --view spend_by_hour --answer --limit 100 --json",
        "pravaha subscribe --view spend_by_hour --reconnect --reconnect-timeout 600",
    ),
    "dlq": ("pravaha dlq list --name orders_by_hour", "pravaha dlq count --name orders_by_hour"),
    "dlq list": ("pravaha dlq list --name orders_by_hour --limit 20",),
    "dlq show": ("pravaha dlq show --name orders_by_hour --id 42",),
    "dlq replay": ("pravaha dlq replay --name orders_by_hour --id 42,43",),
    "dlq count": ("pravaha dlq count --name orders_by_hour",),
    "debug": (
        "pravaha debug fork --name spend_by_hour",
        "pravaha debug step --session dbg-1 --step commit",
    ),
    "debug fork": ("pravaha debug fork --name spend_by_hour --checkpoint 7",),
    "debug checkpoints": ("pravaha debug checkpoints --name spend_by_hour",),
    "debug step": (
        "pravaha debug step --session dbg-1 --step rows:10",
        "pravaha debug step --session dbg-1 --step watermark:1700000000000000000",
    ),
    "debug state": ("pravaha debug state --session dbg-1",),
    "debug inspect": ("pravaha debug inspect --session dbg-1 --operator 3 --limit 50",),
    "debug view": ("pravaha debug view --session dbg-1",),
    "debug fixture": (
        "pravaha debug fixture --session dbg-1 --name LateRefundDoubleCounts --out src/test/java",
    ),
    "debug sessions": ("pravaha debug sessions",),
    "debug end": ("pravaha debug end --session dbg-1",),
    # ------------------------------------------------------------------ HTTP: the node
    "status": ("pravaha status", "pravaha status --json --http https://node-1:18080"),
    "health": ("pravaha health", "pravaha health --json || echo 'not serving'"),
    "version": ("pravaha version", "pravaha version --client"),
    "metrics": ("pravaha metrics --grep pravaha_query_rows_in",),
    "plugins": ("pravaha plugins",),
    "sinks": ("pravaha sinks --json",),
    "doctor": (
        "pravaha doctor",
        "pravaha doctor --local --timeout 5",
        "pravaha doctor --json --http https://node-1:18080 --url grpc+tls://node-1:19090",
    ),
    "completion": (
        "source <(pravaha completion bash)                 # bash, this shell",
        "pravaha completion zsh > ~/.zfunc/_pravaha        # zsh, with fpath+=~/.zfunc",
        "pravaha completion fish > ~/.config/fish/completions/pravaha.fish",
    ),
    "streams": ("pravaha streams", "pravaha streams describe trades"),
    "streams list": ("pravaha streams list --json",),
    "streams describe": ("pravaha streams describe trades",),
    "streams declare": (
        "pravaha streams declare trades --schema 'id:BIGINT,merchant:VARCHAR,amount:DECIMAL(18,2),"
        "ts:TIMESTAMP' --event-time ts --out-of-orderness PT10S",
    ),
    "views": ("pravaha views", "pravaha views describe spend_by_hour"),
    "views list": ("pravaha views list --json",),
    "views describe": ("pravaha views describe spend_by_hour",),
    "describe": ("pravaha describe spend_by_hour", "pravaha describe --name spend_by_hour --json"),
    "plan": ("pravaha plan spend_by_hour",),
    "validate": ("pravaha validate --sql-file spend.sql",),
    "explain": (
        "pravaha explain --sql-file spend.sql",
        "pravaha explain --sql-file spend.sql --level logical --graph --json",
    ),
    "run": ("pravaha-engine run --help        # this command moved to the offline Java tool",),
    "lanes": ("pravaha lanes", "pravaha lanes rebalance status"),
    "lanes list": ("pravaha lanes list --json",),
    "lanes rebalance": (
        "pravaha lanes rebalance                # what it would move, changing nothing",
        "pravaha lanes rebalance --yes",
        "pravaha lanes rebalance status",
    ),
    "audit": (
        "pravaha audit --decision deny --limit 20",
        "pravaha audit --principal ann --since 2026-10-01T00:00:00Z --json",
    ),
    "tenants": ("pravaha tenants",),
    "permissions": ("pravaha permissions",),
    # ------------------------------------------------------------------ identity
    "login": (
        "pravaha login --user ann --save       # asks for the password without echo",
        "printf %s \"$PW\" | pravaha login --user ann --password-stdin --save",
    ),
    "logout": ("pravaha logout",),
    "whoami": ("pravaha whoami", "pravaha whoami --json"),
    "password": (
        "pravaha password                      # asks for both without echo",
        "pravaha password --reset-token rst_abc123",
    ),
    "user": ("pravaha user list", "pravaha user create ann --roles analyst"),
    "user list": ("pravaha user list --json",),
    "user create": (
        "pravaha user create ann --roles analyst,operator --email ann@example.com",
        "pravaha user create etl-bot --roles writer --service --password-stdin",
    ),
    "user disable": ("pravaha user disable ann --yes",),
    "user enable": ("pravaha user enable ann",),
    "user roles": ("pravaha user roles ann --roles analyst",),
    "user reset": ("pravaha user reset ann",),
    "user attrs": ("pravaha user attrs ann", "pravaha user attrs ann region=EU --unset desk"),
    "key": ("pravaha key list", "pravaha key create ci --roles reader --days 30"),
    "key list": ("pravaha key list --all",),
    "key create": (
        "pravaha key create ci --roles reader --days 30",
        "pravaha key create loader --roles writer --for etl-bot",
    ),
    "key rotate": ("pravaha key rotate k_7f3a",),
    "key revoke": ("pravaha key revoke k_7f3a --yes",),
    "key report": ("pravaha key report --json",),
    "session": ("pravaha session list", "pravaha session end s_42"),
    "session list": ("pravaha session list --all",),
    "session end": ("pravaha session end s_42",),
    # ------------------------------------------------------------------ the assistant
    "ask": (
        'pravaha ask "orders per customer per minute" --name orders_per_minute',
        'pravaha ask "orders per customer per minute" --register --yes --url grpc://localhost:19090',
    ),
    "explain-sql": (
        "pravaha explain-sql --sql-file spend.sql",
        "pravaha explain-sql --query spend_by_hour --show-plan",
    ),
    "why": (
        'pravaha why PRV-2050 --sql "SELECT customer, COUNT(*) FROM orders GROUP BY customer"',
        "pravaha why PRV-4023 --no-check",
    ),
    "assist": ("pravaha assist models", "pravaha assist check"),
    "assist models": ("pravaha assist models --json",),
    "assist providers": ("pravaha assist providers",),
    "assist check": ("pravaha assist check --model local-llama",),
    "assist use": ("pravaha assist use explain local-llama,claude --yes",),
    "assist enable": ("pravaha assist enable local-llama --yes",),
    "assist disable": ("pravaha assist disable claude --yes",),
    "assist eval": (
        "pravaha assist eval --limit 5",
        "pravaha assist eval --case adtech-click-attribution --run --url grpc://localhost:19090",
    ),
    # ------------------------------------------------------------------ the catalogue
    "catalog": ("pravaha catalog ls", "pravaha catalog show sales.revenue"),
    "catalog ls": ("pravaha catalog ls --namespace acme.sales --kind VIEW",),
    "catalog search": ("pravaha catalog search revenue",),
    "catalog namespaces": ("pravaha catalog namespaces",),
    "catalog show": ("pravaha catalog show acme.sales.revenue",),
    "catalog create-namespace": (
        "pravaha catalog create-namespace sales --comment 'Revenue views' --if-not-exists",
    ),
    "catalog comment": ("pravaha catalog comment sales.revenue 'Revenue by hour, in USD'",),
    "catalog tag": (
        "pravaha catalog tag sales.revenue pii=false domain=finance",
        "pravaha catalog tag sales.revenue --unset domain",
    ),
    "catalog move": ("pravaha catalog move revenue --namespace sales",),
    "catalog owner": ("pravaha catalog owner sales.revenue --role finance --yes",),
    "grant": (
        "pravaha grant SELECT,SUBSCRIBE sales.revenue --role analyst",
        "pravaha grant ALL sales --user ann",
    ),
    "revoke": ("pravaha revoke SELECT sales.revenue --role analyst --yes",),
    "grants": ("pravaha grants --on sales.revenue", "pravaha grants --user ann"),
    "access": ("pravaha access why ann sales.revenue",),
    "access why": ("pravaha access why ann sales.revenue",),
    # ------------------------------------------------------------------ policies
    "policy": ("pravaha policy ls", "pravaha policy show eu_only"),
    "policy ls": ("pravaha policy ls --on payments",),
    "policy show": ("pravaha policy show eu_only",),
    "policy create-filter": (
        "pravaha policy create-filter eu_only --as \"region = session_attribute('region')\" "
        "--except-role auditor",
    ),
    "policy create-mask": (
        "pravaha policy create-mask card_last4 --column card --as \"'XXXX-' || RIGHT(card, 4)\"",
    ),
    "policy bind": ("pravaha policy bind eu_only --on payments", "pravaha policy bind eu_only --tag pii"),
    "policy unbind": ("pravaha policy unbind eu_only --on payments --yes",),
    "policy drop": ("pravaha policy drop eu_only --yes",),
    # ------------------------------------------------------------------ alerts
    "alerts": ("pravaha alerts", "pravaha alerts show low_stock"),
    "alerts ls": ("pravaha alerts ls --json",),
    "alerts channels": ("pravaha alerts channels",),
    "alerts show": ("pravaha alerts show low_stock",),
    "alerts pause": ("pravaha alerts pause low_stock",),
    "alerts resume": ("pravaha alerts resume low_stock",),
    "alerts snooze": ("pravaha alerts snooze low_stock 2h",),
    "alerts ack": (
        "pravaha alerts ack low_stock",
        "pravaha alerts ack low_stock --key 'sku=sku-100, warehouse=LDN'",
    ),
    "alert": (
        "pravaha alert create low_stock --on stock_levels --notify ops-slack --where 'qty < 10'",
        "pravaha alert drop low_stock --yes",
    ),
    "alert create": (
        "pravaha alert create low_stock --on stock_levels --notify ops-slack,pager "
        "--where 'qty < 10' --severity critical --fire-after 5m",
        "pravaha alert create low_stock --on stock_levels --notify ops-slack --print-sql",
    ),
    "alert drop": ("pravaha alert drop low_stock --if-exists --yes",),
}


def epilog(path: str) -> Optional[str]:
    """The ``examples:`` block for a command path, or ``None`` when it has none."""
    lines = EXAMPLES.get(path)
    if not lines:
        return None
    return "examples:\n" + "\n".join("  " + line for line in lines)
