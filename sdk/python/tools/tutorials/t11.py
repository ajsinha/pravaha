"""Tutorial 11 -- Governance (docs/tutorials/11-governance.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

A node with the catalogue on: card payments from two regions, a view moved into a namespace with an
owner, a description and tags; a second user, ann, in the analyst role with a region attribute;
grants (dry-run first) and why she may read; a row filter on her region and a mask on the card number,
so she and the administrator ask the same question and get different answers; a revocation and her
refusal; the audit trail of all of it.
"""

from tutorial_captures import Cli, Node, Open, Sh, Snap, Until

DOC = "11-governance.md"

NODE = Node(config="t11/application.yaml", files=(
    ("t11/payments.csv", "data/incoming/payments.csv"),
    ("t11/payments_view.sql", "payments_view.sql"),
))

V = "sales.payments_view"
READ = ("query", "--sql", "SELECT * FROM payments_view")
PASSWORD = "Tutorial-ann-2026"
RIGHT_MASK = "'XXXX-XXXX-XXXX-' || RIGHT(card_number, 4)"
MASK = "'XXXX-XXXX-XXXX-' || SUBSTRING(card_number, 16, 4)"


def as_ann(capture, *argv: str) -> Sh:
    """``pravaha <argv>`` signed in as ann: her session token in place of the administrator's."""
    words = " ".join(_quoted(w) for w in argv)
    return Sh(capture, f"PRAVAHA_TOKEN=$(cat ann.token) pravaha {words}", show=f"PRAVAHA_TOKEN=$ANN pravaha {words}")


def _quoted(word: str) -> str:
    import re
    if re.fullmatch(r"[A-Za-z0-9_./:=,@+%-]+", word):
        return word
    return '"' + word.replace("\\", "\\\\").replace('"', '\\"') + '"' if "'" in word else "'" + word + "'"


STEPS = (
    Cli("t11-register", ("register", "--name", "payments_view", "--sql-file", "payments_view.sql", "--keys", "0")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM payments_view"), r"^5\s*$"),
    Cli("t11-catalog", ("catalog", "ls")),
    Cli("t11-namespace", ("catalog", "create-namespace", "sales", "--comment", "Order-to-cash")),
    Cli("t11-move", ("catalog", "move", "payments_view", "--namespace", "sales")),
    Cli("t11-comment", ("catalog", "comment", V, "Every card payment, both regions")),
    Cli("t11-tag", ("catalog", "tag", V, "pii", "domain=payments")),
    # "you may:" lists the privileges in no fixed order (ACCESSORDER-1); the tutorial reads it from access why.
    Cli("t11-show", ("catalog", "show", V), omit=(r"^(created|updated|version)\s", r"^you may:")),
    Sh("t11-user", f"printf '%s\\n' '{PASSWORD}' | pravaha user create ann --roles analyst --password-stdin"),
    Cli("t11-attrs", ("user", "attrs", "ann", "region=EU")),
    Sh("t11-login", f"printf '%s\\n' '{PASSWORD}' | pravaha login --user ann --password-stdin 2>&1 > ann.token",
       show="ANN=$(pravaha login --user ann)"),
    as_ann("t11-whoami", "whoami"),
    as_ann("t11-ann-refused", *READ),
    Cli("t11-grant-dry-run", ("grant", "SELECT", V, "--role", "analyst", "--dry-run")),
    Cli("t11-grant", ("grant", "SELECT", V, "--role", "analyst")),
    Cli("t11-grant-use", ("grant", "USE", "sales", "--role", "analyst")),
    Cli("t11-grants", ("grants", "--role", "analyst")),
    # The rows come in no fixed order (ACCESSORDER-1): sorted under their two header lines here.
    Sh("t11-why", f"pravaha access why ann {V} | {{ IFS= read -r a; echo \"$a\"; IFS= read -r b; echo \"$b\"; sort; }}",
       show=f"pravaha access why ann {V}"),
    as_ann("t11-ann-reads", *READ),
    Cli("t11-filter", ("policy", "create-filter", "sales.region_scope", "--as", "region = session_attribute('region')",
                       "--except-role", "admin", "--comment", "Each analyst sees their own region")),
    Cli("t11-bind-dry-run", ("policy", "bind", "sales.region_scope", "--on", V, "--dry-run")),
    Cli("t11-bind", ("policy", "bind", "sales.region_scope", "--on", V)),
    Cli("t11-mask-right", ("policy", "create-mask", "sales.card_last4", "--column", "card_number", "--as", RIGHT_MASK,
                           "--except-role", "admin")),
    Cli("t11-bind-mask-right", ("policy", "bind", "sales.card_last4", "--tag", "pii")),
    as_ann("t11-ann-mask-refused", *READ),
    Cli("t11-unbind", ("policy", "unbind", "sales.card_last4", "--tag", "pii", "--yes")),
    Cli("t11-drop-mask", ("policy", "drop", "sales.card_last4", "--yes")),
    Cli("t11-mask", ("policy", "create-mask", "sales.card_last4", "--column", "card_number", "--as", MASK,
                     "--except-role", "admin")),
    Cli("t11-bind-mask", ("policy", "bind", "sales.card_last4", "--tag", "pii")),
    Cli("t11-policies", ("policy", "ls")),
    as_ann("t11-ann-narrowed", *READ),
    Cli("t11-admin-reads", READ),
    as_ann("t11-ann-compare-refused", "query", "--sql", "SELECT * FROM payments_view WHERE card_number = '4111-1111-1111-5678'"),
    as_ann("t11-ann-us", "query", "--sql", "SELECT payment_id, region FROM payments_view WHERE region = 'US'"),
    Open("/catalog/objects/public.sales.payments_view"),
    Snap("01-object"),
    Open("/admin/grants?object=public.sales.payments_view"),
    Snap("02-grants"),
    Open("/admin/policies"),
    Snap("03-policies"),
    Cli("t11-revoke-dry-run", ("revoke", "SELECT", V, "--role", "analyst", "--dry-run")),
    Cli("t11-revoke", ("revoke", "SELECT", V, "--role", "analyst", "--yes")),
    as_ann("t11-ann-revoked", *READ),
    # The sequence numbers count every decision the node made, the administrator's included.
    Cli("t11-audit", ("audit", "--principal", "ann", "--decision", "deny", "--limit", "5"), subs=(
        (r"^\d+\s+(?=\d{4}-\d\d-\d\dT)", "N         "),
        (r"^\d+ retained of ", "N retained of "))),
    Open("/admin/audit?principal=ann"),
    Snap("04-audit"),
    Open("/admin/users"),
    Snap("05-users"),
)
