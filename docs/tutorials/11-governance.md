# Tutorial 11 · Governance

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

A view of every card payment, from Europe and the United States, is useful to an analyst in Paris and
dangerous in the wrong hands. With the **catalogue** on, who may do what is not a configuration file
but grants the engine keeps, journals and enforces on every read, subscription and registration,
whichever client asks — and what each reader is **shown** is decided by policies: a **row filter**
keeps some rows, a **mask** replaces a column's value. In this tutorial you put the view in a namespace
with an owner, a description and tags; create a second user, `ann`, an analyst in the EU; see her
refused, then grant her what she needs (looking first with `--dry-run`) and ask the engine why she may;
bind a filter to her region and a mask to every object tagged `pii`, and watch her and the
administrator ask the same question and get different answers; then revoke, and read the audit trail
of all of it. Every output below was printed by a real node running these steps.

**Time to complete:** about 30 minutes.

## Before you start

- [Tutorial 1](01-first-15-minutes.md) done: you signed in as `pravaha-dev-admin`, who holds the
  `admin` role.
- The tutorial's files, in [data/t11/](data/t11/): `application.yaml` (the stream, the catalogue on,
  an audit file), `payments.csv` (five card payments, three EU and two US) and `payments_view.sql`.
- A node home with the configuration and the data:

```bash
export PRAVAHA_HOME=$HOME/pravaha-t11
mkdir -p $PRAVAHA_HOME/conf $PRAVAHA_HOME/data/incoming
cp docs/tutorials/data/t11/application.yaml $PRAVAHA_HOME/conf/
cp docs/tutorials/data/t11/payments.csv $PRAVAHA_HOME/data/incoming/
bin/pravaha-server --spring.profiles.active=dev,users &
cd docs/tutorials/data/t11
export PRAVAHA_TOKEN=$(pravaha login --user pravaha-dev-admin) PRAVAHA_INSECURE_TOKEN=true
```

```yaml
# data/t11/application.yaml (what this tutorial needs beyond a stream and a source)
pravaha:
  catalog:
    enabled: true
    authority: catalog       # grants alone decide; nothing is imported from pravaha.security.policy
  security:
    audit: file              # every decision, allows as well as denials, to a file
    audit-file: logs/audit.jsonl
```

The catalogue is **off by default**. `authority: import` would instead import the node's old
`pravaha.security.policy` once as grants that mean what it meant, so that turning the catalogue on
changes nothing until someone grants or revokes.

## What you will learn

- the catalogue's names (`tenant.namespace.object`), owners, descriptions and tags, and moving a view;
- users, roles and **attributes** (the claims a policy reads), and signing in as a second person;
- the privileges (`USE`, `SELECT`, `SUBSCRIBE`, `BUILD_ON`, `MODIFY`, `MANAGE`, `OWN`, …), `grant`,
  `revoke`, `grants` and `access why`, and `--dry-run` on each change;
- **row filters** and **masks**: defined once, bound to an object or a tag, applied on every path;
- the audit trail, and the console's Catalog and Admin pages for all of it.

## Step 1 · The view in the catalogue

<!-- capture: t11-register -->
```text
$ pravaha register --name payments_view --sql-file payments_view.sql --keys 0
registered payments_view  state=RUNNING  fingerprint=04f7256c1ed0
a query with the same fingerprint is the same computation, shared
```
<!-- /capture -->

<!-- capture: t11-catalog -->
```text
$ pravaha catalog ls
NAME                          KIND       OWNER                   TAGS  DESCRIPTION
*                             NAMESPACE  ROLE admin              -     -
public                        NAMESPACE  ROLE admin              -     -
public.default                NAMESPACE  ROLE admin              -     -
public.default.payments_view  VIEW       USER pravaha-dev-admin  -     -
```
<!-- /capture -->

Every governed object has a three-part name — tenant, namespace, object — and an owner, who holds
every privilege on it. A registration lands in its tenant's `default` namespace, owned by whoever
registered it, so every statement written before the catalogue keeps working. `*` is the whole
catalogue; a grant there, on a tenant or on a namespace, is **inherited** by everything under it, now
and later. Make a namespace for the sales team and move the view into it — its grants go with it:

<!-- capture: t11-namespace -->
```text
$ pravaha catalog create-namespace sales --comment Order-to-cash
created public.sales, owned by USER pravaha-dev-admin
```
<!-- /capture -->

<!-- capture: t11-move -->
```text
$ pravaha catalog move payments_view --namespace sales
moved to public.sales.payments_view, with its grants
```
<!-- /capture -->

<!-- capture: t11-comment -->
```text
$ pravaha catalog comment sales.payments_view 'Every card payment, both regions'
public.sales.payments_view: Every card payment, both regions
```
<!-- /capture -->

<!-- capture: t11-tag -->
```text
$ pravaha catalog tag sales.payments_view pii domain=payments
public.sales.payments_view: pii, domain=payments
```
<!-- /capture -->

<!-- capture: t11-show -->
```text
$ pravaha catalog show sales.payments_view
name         public.sales.payments_view
kind         VIEW
engine name  payments_view
owner        USER pravaha-dev-admin
description  Every card payment, both regions
tags         pii, domain=payments

grants:
  none you may see

```
<!-- /capture -->

**Engine name** matters: SQL still reads the view as `payments_view` — engine names stay single
identifiers, unique in their tenant — and the catalogue name is where it lives for grants, tags and
policies. The tag `pii` is the one a policy will be bound to in step 4.

You should see `public.sales.payments_view`, owned by you, with a description and two tags.

## Step 2 · A second user, and a refusal

Create `ann`, an analyst, and give her an **attribute** — a fact about her that every session and API
key of hers carries as a claim, and that a policy can read:

<!-- capture: t11-user -->
```text
$ printf '%s\n' 'Tutorial-ann-2026' | pravaha user create ann --roles analyst --password-stdin
created ann
```
<!-- /capture -->

<!-- capture: t11-attrs -->
```text
$ pravaha user attrs ann region=EU
ann: region=EU
```
<!-- /capture -->

(Without `--password-stdin` the CLI asks for the password; in the console it is **Admin · Users →
Add a person**.) Sign in as her, in a variable of your own, so that one terminal can be both people:

<!-- capture: t11-login -->
```text
$ ANN=$(pravaha login --user ann)
signed in as ann; pass this as --token or PRAVAHA_TOKEN, or sign in with --save
```
<!-- /capture -->

<!-- capture: t11-whoami -->
```text
$ PRAVAHA_TOKEN=$ANN pravaha whoami
user              ann
principal         ann
tenant            public
roles             analyst
via               session
name              -
email             -
password expires  <time>
token from        env
```
<!-- /capture -->

<!-- capture: t11-ann-refused -->
```text
$ PRAVAHA_TOKEN=$ANN pravaha query --sql 'SELECT * FROM payments_view'
PRV-7002  ann may not read 'payments_view': ann may not read 'payments_view': ann holds no SELECT on public.sales.payments_view: no grant on it, its namespace, its tenant or the catalogue reaches USER ann or the roles [analyst], and they do not own it
  look PRV-7002 up in the console's help under Errors, or in docs/guides/TROUBLESHOOTING.md
(exit 1)
```
<!-- /capture -->

With `authority: catalog` nothing is granted that nobody granted. The refusal says which privilege
was missing, on what, and every route by which it could have reached her. (The message repeats its
first clause, GOVMSG-1.)

You should see `roles analyst` for ann, and `PRV-7002` for her read.

## Step 3 · Grant, after looking

Every change to grants takes `--dry-run`, which reads what it would touch and changes nothing:

<!-- capture: t11-grant-dry-run -->
```text
$ pravaha grant SELECT sales.payments_view --role analyst --dry-run
dry run: grant SELECT on sales.payments_view to ROLE analyst -- nothing was changed
object      public.sales.payments_view
held now    nothing you may see
held after  SELECT
would:
  - ROLE analyst may SELECT on sales.payments_view
not known without doing it:
  - grants reaching them through a role or a namespace are not changed, and are listed only as far as you may see them
```
<!-- /capture -->

<!-- capture: t11-grant -->
```text
$ pravaha grant SELECT sales.payments_view --role analyst
granted SELECT on public.sales.payments_view to ROLE analyst
```
<!-- /capture -->

Every privilege on an object also needs `USE` on its namespace — the right to resolve names in it:

<!-- capture: t11-grant-use -->
```text
$ pravaha grant USE sales --role analyst
granted USE on public.sales to ROLE analyst
```
<!-- /capture -->

<!-- capture: t11-grants -->
```text
$ pravaha grants --role analyst
OBJECT                      PRIVILEGE  TO    GRANTEE  BY
public.sales.payments_view  SELECT     ROLE  analyst  pravaha-dev-admin
public.sales                USE        ROLE  analyst  pravaha-dev-admin
```
<!-- /capture -->

And why — through which grant, role, namespace or ownership — ann may or may not do each thing:

<!-- capture: t11-why -->
```text
$ pravaha access why ann sales.payments_view
ann on public.sales.payments_view:
PRIVILEGE  ALLOWED  WHY
BUILD_ON   no       ann holds no BUILD_ON on public.sales.payments_view: no grant on it, its namespace, its tenant or the catalogue reaches USER ann or the roles [analyst], and they do not own it
MANAGE     no       ann holds no MANAGE on public.sales.payments_view: no grant on it, its namespace, its tenant or the catalogue reaches USER ann or the roles [analyst], and they do not own it
MODIFY     no       ann holds no MODIFY on public.sales.payments_view: no grant on it, its namespace, its tenant or the catalogue reaches USER ann or the roles [analyst], and they do not own it
OWN        no       ann holds no OWN on public.sales.payments_view: no grant on it, its namespace, its tenant or the catalogue reaches USER ann or the roles [analyst], and they do not own it
SELECT     yes      grant SELECT on public.sales.payments_view to ROLE analyst
SUBSCRIBE  no       ann holds no SUBSCRIBE on public.sales.payments_view: no grant on it, its namespace, its tenant or the catalogue reaches USER ann or the roles [analyst], and they do not own it
```
<!-- /capture -->

| Privilege | On | Means |
|---|---|---|
| `USE` | namespace | may resolve names in it — needed for anything inside it |
| `SELECT` | view, stream | may read by point read or scan |
| `SUBSCRIBE` | view | may receive its changes live — separate from `SELECT` |
| `BUILD_ON` | view, stream | may register a query that reads it |
| `CREATE` | namespace, tenant | may register queries in it, or create namespaces |
| `WRITE` | sink, channel | may name it in `WRITING TO`, or `NOTIFY` it from an alert |
| `MODIFY` | view, alert | may pause, resume, replace or drop it |
| `MANAGE` | any | may change its grants, description, tags and policies |
| `OWN` | any | everything; one owner, moved with `catalog owner` |

Reading a view needs `SELECT` on the view, **not** on its sources — that is what a view is for — but
registering a query needs `BUILD_ON` on every input, so nobody builds on what they could not read.
Grants only allow, to a role or a user; access is the union. Now ann reads:

<!-- capture: t11-ann-reads -->
```text
$ PRAVAHA_TOKEN=$ANN pravaha query --sql 'SELECT * FROM payments_view'
payment_id  customer   region  card_number          amount
1           ann-gmbh   EU      4111-1111-1111-1234  1200
2           bobco      US      4111-1111-1111-5678  300
3           cafe-lyon  EU      5500-0000-0000-9012  4500
4           dallas-tx  US      5500-0000-0000-3456  15000
5           eiffel-sa  EU      3700-0000-0000-7890  2200
5 rows
```
<!-- /capture -->

![The Catalog page of public.sales.payments_view: a VIEW owned by USER pravaha-dev-admin, described as every card payment, both regions, tagged pii and domain=payments, engine name payments_view; what you may do, each privilege with the reason; the grant SELECT to ROLE analyst by pravaha-dev-admin; and the row filter region_scope bound to the view and the mask card_last4 bound to TAG pii, each exempting the admin role](img/t11/01-object.png)

The same, by object, on **Admin · Grants**, where a grant is added or revoked from a form — asked of
the engine as you:

![Admin, Grants, opened on public.sales.payments_view, a VIEW owned by USER pravaha-dev-admin: SELECT granted to ROLE analyst by pravaha-dev-admin, with a Revoke button; a form to grant USE, SELECT, SUBSCRIBE, BUILD_ON, CREATE, WRITE, MODIFY or MANAGE to a role or a user; its description and its tags pii and domain=payments](img/t11/02-grants.png)

You should see ann's five rows, every card number in full — which is the problem the next step solves.

## Step 4 · What each reader is shown: a row filter and a mask

A grant decides **whether** ann may read; a policy decides **what** she is shown. Define a row filter
that keeps each reader to their own region — `session_attribute('region')` is ann's `region=EU` — with
the `admin` role exempt:

<!-- capture: t11-filter -->
```text
$ pravaha policy create-filter sales.region_scope --as "region = session_attribute('region')" --except-role admin --comment 'Each analyst sees their own region'
created public.sales.region_scope (ROW_FILTER); nothing is narrowed until it is bound: pravaha policy bind public.sales.region_scope --on <object>
```
<!-- /capture -->

<!-- capture: t11-bind-dry-run -->
```text
$ pravaha policy bind sales.region_scope --on sales.payments_view --dry-run
dry run: policy bind sales.region_scope to sales.payments_view -- nothing was changed
kind        ROW_FILTER
expression  region = session_attribute('region')
bound to    nothing
would:
  - every read of sales.payments_view by a role it does not except is narrowed by sales.region_scope, from the next read
not known without doing it:
  - a conflict with another mask on the same column for the same reader is found when it is bound
```
<!-- /capture -->

<!-- capture: t11-bind -->
```text
$ pravaha policy bind sales.region_scope --on sales.payments_view
bound sales.region_scope to public.sales.payments_view
```
<!-- /capture -->

Now a mask for the card number, bound not to one view but to the **tag** `pii` — every object tagged
`pii`, this one now and the next one tagged. The reference's example uses `RIGHT`:

<!-- capture: t11-mask-right -->
```text
$ pravaha policy create-mask sales.card_last4 --column card_number --as "'XXXX-XXXX-XXXX-' || RIGHT(card_number, 4)" --except-role admin
created public.sales.card_last4 (MASK); nothing is narrowed until it is bound: pravaha policy bind public.sales.card_last4 --on <object>
```
<!-- /capture -->

<!-- capture: t11-bind-mask-right -->
```text
$ pravaha policy bind sales.card_last4 --tag pii
bound sales.card_last4 to TAG 'pii'
```
<!-- /capture -->

<!-- capture: t11-ann-mask-refused -->
```text
$ PRAVAHA_TOKEN=$ANN pravaha query --sql 'SELECT * FROM payments_view'
PRV-7038  the mask on payments_view.card_number ('XXXX-XXXX-XXXX-' || RIGHT ( card_number , 4 )) does not plan over that column alone: PRV-2002  No match found for function signature RIGHT(<CHARACTER>, <NUMERIC>). Pravaha evaluates + - * / %, ABS, FLOOR, CEIL, ROUND, CASE WHEN, UPPER, LOWER, TRIM, SUBSTRING, ||, DATE_FORMAT, REGEXP_EXTRACT and SPLIT_INDEX, and the aggregates COUNT, SUM, MIN, MAX and AVG; each of the one-argument functions takes exactly one argument. See docs/guides/CONTINUOUS_QUERIES.md. This server has 1 stream(s) declared; their names are not listed here because that would tell a caller who may not read them that they exist. Use the listing call, which is filtered by what you may read.. A mask may read only the column it masks
  look PRV-7038 up in the console's help under Errors, or in docs/guides/TROUBLESHOOTING.md
(exit 1)
```
<!-- /capture -->

On this build that mask is accepted when it is created and bound, and then refuses every read it
applies to, because the engine does not evaluate `RIGHT` (MASKRIGHT-1). A mask that cannot run fails
**closed** — ann is refused, never shown the full number — but it should have been refused at
`create-mask`. Take it away (`unbind`, then `drop`: a policy cannot be dropped while it is bound, and
both ask for `--yes`) and write it with `SUBSTRING`, which the engine does evaluate:

<!-- capture: t11-unbind -->
```text
$ pravaha policy unbind sales.card_last4 --tag pii --yes
public.sales.card_last4 unbound TAG 'pii'
```
<!-- /capture -->

<!-- capture: t11-drop-mask -->
```text
$ pravaha policy drop sales.card_last4 --yes
dropped sales.card_last4
```
<!-- /capture -->

<!-- capture: t11-mask -->
```text
$ pravaha policy create-mask sales.card_last4 --column card_number --as "'XXXX-XXXX-XXXX-' || SUBSTRING(card_number, 16, 4)" --except-role admin
created public.sales.card_last4 (MASK); nothing is narrowed until it is bound: pravaha policy bind public.sales.card_last4 --on <object>
```
<!-- /capture -->

<!-- capture: t11-bind-mask -->
```text
$ pravaha policy bind sales.card_last4 --tag pii
bound sales.card_last4 to TAG 'pii'
```
<!-- /capture -->

<!-- capture: t11-policies -->
```text
$ pravaha policy ls
NAME                       KIND        COLUMN       EXPRESSION                                          EXCEPT ROLE  BOUND TO
public.sales.region_scope  ROW_FILTER  -            region = session_attribute('region')                admin        public.sales.payments_view
public.sales.card_last4    MASK        card_number  'XXXX-XXXX-XXXX-' || SUBSTRING(card_number, 16, 4)  admin        TAG 'pii'
```
<!-- /capture -->

The same question, two people:

<!-- capture: t11-ann-narrowed -->
```text
$ PRAVAHA_TOKEN=$ANN pravaha query --sql 'SELECT * FROM payments_view'
payment_id  customer   region  card_number          amount
1           ann-gmbh   EU      XXXX-XXXX-XXXX-1234  1200
3           cafe-lyon  EU      XXXX-XXXX-XXXX-9012  4500
5           eiffel-sa  EU      XXXX-XXXX-XXXX-7890  2200
3 rows
```
<!-- /capture -->

<!-- capture: t11-admin-reads -->
```text
$ pravaha query --sql 'SELECT * FROM payments_view'
payment_id  customer   region  card_number          amount
1           ann-gmbh   EU      4111-1111-1111-1234  1200
2           bobco      US      4111-1111-1111-5678  300
3           cafe-lyon  EU      5500-0000-0000-9012  4500
4           dallas-tx  US      5500-0000-0000-3456  15000
5           eiffel-sa  EU      3700-0000-0000-7890  2200
5 rows
```
<!-- /capture -->

A masked column is **shown, not compared**: filtering, grouping, joining or sorting on it would tell
ann which rows share a card number — what the mask hides — so it is refused. And the filter is
applied to the input, under her SQL, so no `WHERE` of hers can reach past it:

<!-- capture: t11-ann-compare-refused -->
```text
$ PRAVAHA_TOKEN=$ANN pravaha query --sql "SELECT * FROM payments_view WHERE card_number = '4111-1111-1111-5678'"
PRV-7006  payments_view.card_number is masked for you, and this query uses it as a filter operand. Comparing a masked column would tell you which rows share its value, or their order -- what the mask hides. Select it to see its masked value; compare, group, join or sort on another column
  look PRV-7006 up in the console's help under Errors, or in docs/guides/TROUBLESHOOTING.md
(exit 1)
```
<!-- /capture -->

<!-- capture: t11-ann-us -->
```text
$ PRAVAHA_TOKEN=$ANN pravaha query --sql "SELECT payment_id, region FROM payments_view WHERE region = 'US'"
payment_id  region
0 rows
```
<!-- /capture -->

Policies reach **every path** rows leave by: Flight reads and point reads, the PostgreSQL gateway,
subscriptions (the snapshot and every commit), registrations (a query ann registers over the view holds
only her rows, and so does everything built on it) and alerts (which run as their owner). Several
filters `AND` together; only `EXCEPT ROLE` exempts — not ownership, not `admin` unless named. A filter
that restricts nothing (`1 = 1 OR region = 'EU'`) is refused rather than recorded as a restriction
(`PRV-7003`), and a reader whose credential lacks a claim a policy reads is refused (`PRV-7039`), never
guessed for. A changed policy ends the open subscriptions of the readers it affects (`PRV-7007`).

![Admin, Policies: public.sales.region_scope, a ROW_FILTER with the expression region = session_attribute('region'), except admin, bound to public.sales.payments_view; public.sales.card_last4, a MASK on card_number with the SUBSTRING expression, except admin, bound to TAG pii; each with unbind and Drop; forms to bind a policy and to create a row filter or mask](img/t11/03-policies.png)

You should see three EU rows with masked cards for ann, five full rows for the administrator, `PRV-7006`
for ann's comparison, and no US rows for her.

## Step 5 · Revoke, after looking

<!-- capture: t11-revoke-dry-run -->
```text
$ pravaha revoke SELECT sales.payments_view --role analyst --dry-run
dry run: revoke SELECT on sales.payments_view from ROLE analyst -- nothing was changed
object      public.sales.payments_view
held now    SELECT
held after  nothing
would:
  - ROLE analyst loses SELECT on sales.payments_view at their next read, and their open subscriptions to it end
not known without doing it:
  - grants reaching them through a role or a namespace are not changed, and are listed only as far as you may see them
```
<!-- /capture -->

`revoke` without `--yes` only says what it would do, as `--dry-run` does; with it:

<!-- capture: t11-revoke -->
```text
$ pravaha revoke SELECT sales.payments_view --role analyst --yes
revoked SELECT on sales.payments_view from ROLE analyst
```
<!-- /capture -->

<!-- capture: t11-ann-revoked -->
```text
$ PRAVAHA_TOKEN=$ANN pravaha query --sql 'SELECT * FROM payments_view'
PRV-7002  ann may not read 'payments_view': ann may not read 'payments_view': ann holds no SELECT on public.sales.payments_view: no grant on it, its namespace, its tenant or the catalogue reaches USER ann or the roles [analyst], and they do not own it
  look PRV-7002 up in the console's help under Errors, or in docs/guides/TROUBLESHOOTING.md
(exit 1)
```
<!-- /capture -->

A revocation takes effect at the next read — no restart, no session to end — and an open subscription
of hers to the view would have ended within seconds with `PRV-7002`.

You should see `held after nothing`, then `PRV-7002` for ann.

## Step 6 · The audit trail

Every decision the policy made — allows as well as denials, grants and revocations — is in the trail,
newest first. ann's refusals:

<!-- capture: t11-audit -->
```text
$ pravaha audit --principal ann --decision deny --limit 5
SEQUENCE  AT                              PRINCIPAL  ACTION  TARGET         DECISION  REASON
N         <time>                          ann        schema  payments_view  DENY      ann may not read 'payments_view': ann holds no SELECT on public.sales.payments_view: no grant on it, its namespace, its tenant or the catalogue reaches USER ann or the roles [analyst], and they do not own it
N         <time>                          ann        query   payments_view  DENY      allowed by policy, then refused: PRV-7006  payments_view.card_number is masked for you, and this query uses it as a filter operand. Comparing a masked column would tell you which rows share its value, or their order -- what the mask hides. Select it to see its masked value; compare, group, join or sort on another column
N         <time>                          ann        query   payments_view  DENY      allowed by policy, then refused: PRV-7038  the mask on payments_view.card_number ('XXXX-XXXX-XXXX-' || RIGHT ( card_number , 4 )) does not plan over that column alone: PRV-2002  No match found for function signature RIGHT(<CHARACTER>, <NUMERIC>). Pravaha evaluates + - * / %, ABS, FLOOR, CEIL, ROUND, CASE WHEN, UPPER, LOWER, TRIM, SUBSTRING, ||, DATE_FORMAT, REGEXP_EXTRACT and SPLIT_INDEX, and the aggregates COUNT, SUM, MIN, MAX and AVG; each of the one-argument functions takes exactly one argument. See docs/guides/CONTINUOUS_QUERIES.md. This server has 1 stream(s) declared; their names are not listed here because that would tell a caller who may not read them that they exist. Use the listing call, which is filtered by what you may read.. A mask may read only the column it masks
N         <time>                          ann        schema  payments_view  DENY      ann may not read 'payments_view': ann holds no SELECT on public.sales.payments_view: no grant on it, its namespace, its tenant or the catalogue reaches USER ann or the roles [analyst], and they do not own it
N retained of 10000, 0 evicted
```
<!-- /capture -->

Without `--decision deny` it shows her allowed reads too — each with its SQL, and a `query.narrowed`
entry naming the filter and the mask that applied — and `catalog.grant`, `catalog.revoke` and the
policy changes are there under the administrator's name. The file (`logs/audit.jsonl`, one JSON object
a line) is the durable record; `pravaha audit` and the console read the recent ones the node keeps.

![Admin, Audit trail filtered to the principal ann: newest first, her denied read after the revocation with the reason and the SQL SELECT * FROM payments_view; an allowed query.narrowed entry naming the row filter public.sales.region_scope as region = 'EU' and the mask public.sales.card_last4; and her allowed queries with their SQL](img/t11/04-audit.png)

Last, the people: ann, her role and her attribute, as an administrator sees and changes them:

![Admin, Users: admin with the roles operator and admin; ann with the role analyst and the attribute region=EU, active, last signed in; pravaha-dev-admin with the role admin; each with Set buttons for roles and attributes, Disable and Issue reset, and a form to add a person](img/t11/05-users.png)

A person is disabled, never deleted (`pravaha user disable ann`), because the audit trail and the
registry name owners by id.

You should see ann's denials, the masked-read refusal among them, newest first.

## Next steps

- [Tutorial 10 · Alerts](10-alerts.md): an alert is a catalogue object too — `SELECT` to see it,
  `MODIFY` to pause or acknowledge it, `WRITE` on each channel to create one.
- [Tutorial 4 · Following a view](04-following-a-view.md): read the same view through the PostgreSQL
  gateway with an API key — which carries exactly its holder's attributes, so ann's key is narrowed as
  she is.
- The reference: [catalog and grants](../../pravaha-console/content/topics/catalog-and-grants.md),
  [row filters and masks](../../pravaha-console/content/topics/row-filters-and-masks.md) (what an
  expression may say, and the filters refused as restricting nothing), [the audit
  trail](../../pravaha-console/content/topics/audit.md), and
  [authorization](../../pravaha-console/content/topics/authorization.md).

## If something goes wrong

| Symptom | What it means | What to do |
|---|---|---|
| `PRV-7002` | the grants do not allow the read, subscription or registration; the message names the privilege and the object | `pravaha access why <user> <object>`; grant what is missing, and `USE` on the namespace |
| `PRV-7031` | no object by that name that you may see — the same answer whether it does not exist or you may not `USE` its namespace | check the name with `pravaha catalog ls`; ask for `USE` |
| `PRV-7033` | changing grants, descriptions, tags or policies needs `MANAGE`; giving an object away needs ownership | ask the owner |
| `PRV-7030` | the catalogue is off on this node | `pravaha.catalog.enabled: true` |
| `PRV-7034` at start | `authority: import` and `pravaha.security.policy` changed since the import | put the policy back, or move to `authority: catalog` |
| `PRV-7038` | a policy's expression is refused: a subquery, a non-deterministic or unknown function, a mask reading another column — or, on this build, `RIGHT` found only at read time | use the functions the engine evaluates (`SUBSTRING`, `CASE`, `||`, …) |
| `PRV-7039` | the reader's credential lacks a claim the policy reads | `pravaha user attrs <user> region=…` before binding |
| `PRV-7006` | a masked column used in a filter, group, join, sort or key | compare on another column |
| `PRV-2002` for `sales.payments_view` in SQL | SQL reads a view by its engine name; the catalogue name is for grants and policies | `SELECT … FROM payments_view` |
