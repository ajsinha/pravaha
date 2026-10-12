# Tutorial 16 · AI in Pravaha

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

Pravaha can put a language model beside your views in a few places and no more: **ask your data** a
question, **draft** a continuous query or an alert from a description, **explain** a query, a refusal or
why a view changed, and answer a question **about the page** you are looking at. It is **off by
default**, and when it is on it can only do what you could do by hand: **the model writes names and
SQL, never answers** — Pravaha checks what it wrote, runs it **as you** (your grants, row filters and
masks) and shows you the SQL it chose; nothing is registered or created until you confirm. By default
the model is never sent a row, only names, types, keys and counts. In this tutorial you turn the layer
on with the **mock provider** — regular expressions that play the model, with no network and no key —
so you can see everything it does and everything it is stopped from doing: Admin → AI and Test
connection, questions and a follow-up, a draft that is dry-run and then registered, an alert from words
and one it will not write, explanations, the exact text a model is sent and a card number masked out of
it, a second person refused by the AI policy, and the audit trail of every call. Last, how to put a real
provider behind it. Every output below was printed by a real node running these steps.

**Time to complete:** about 35 minutes.

## Before you start

- [Tutorial 1](01-first-15-minutes.md) done; [tutorial 10](10-alerts.md) for alerts and
  [tutorial 11](11-governance.md) for users help, but are not needed.
- Nothing to buy and no key: the mock provider is part of Pravaha.
- The tutorial's files, in [data/t16/](data/t16/): `application.yaml` (the stream, a log channel, the
  audit file and the AI layer on), `mock.yaml` (the mock provider's rules), `payments.csv` (eight card
  payments from three regions), `region_minutes.sql` and `big_payments.sql`.

```bash
export PRAVAHA_HOME=$HOME/pravaha-t16
mkdir -p $PRAVAHA_HOME/conf/ai $PRAVAHA_HOME/data/incoming
cp docs/tutorials/data/t16/application.yaml $PRAVAHA_HOME/conf/
cp docs/tutorials/data/t16/mock.yaml $PRAVAHA_HOME/conf/ai/
cp docs/tutorials/data/t16/payments.csv $PRAVAHA_HOME/data/incoming/
bin/pravaha-server --spring.profiles.active=dev,users &
cd docs/tutorials/data/t16
export PRAVAHA_TOKEN=$(pravaha login --user pravaha-dev-admin) PRAVAHA_INSECURE_TOKEN=true
```

## What you will learn

- how to switch the AI layer on **safely**: one folder of provider files, one setting, the mock provider;
- who may use each feature: the engine's own permissions first, then the **AI policy**;
- **ask your data**: the SQL the model chose, the rows, the chart Pravaha chose, follow-ups, one repair
  turn, and `PRV-1088` when nothing can answer;
- **drafting** a continuous query (dry-run, then registered on confirmation) and an **alert** from words;
- **explain**: a query from its plan, a refusal, why a view changed;
- what leaves the node in a prompt — the `values` modes and masking — and the audit trail of every call;
- how a real provider (Anthropic, OpenAI, Ollama) is configured, with its key in the environment.

## Step 1 · Turn it on, with the mock provider

Providers are **files** in one folder, `$PRAVAHA_HOME/conf/ai/`, one YAML file per provider; the file's
name is the provider's name. Nothing in the folder is read until a setting turns the layer on:

<!-- file: data/t16/application.yaml -->
```yaml
  ai:
    enabled: true                    # off by default: no provider file is read and every AI call is PRV-1080
    default-model: mock/m            # provider/model: the file conf/ai/mock.yaml, its model m
    values: labels-only              # what a model may see of your data: names, types, keys, counts; never a value
```

The mock provider answers from the **first rule whose regular expression is found in the last message**
it is sent, and **echoes** a message no rule finds — which, as step 5 shows, is the best way there is to
see what a real model would be sent. Every feature's message starts `TASK: <name>`, and the words a
person typed follow a marker, so a rule can match them:

<!-- file: data/t16/mock.yaml -->
```yaml
type: mock
default-model: m
models:
  - name: m
mock:
  rules:
    # --- Ask your data: the model writes ONE SELECT over a view; Pravaha runs it as you.
    - match: 'TASK: ask[\s\S]*UNTRUSTED question id=[0-9a-f]+>>>\n[^\n]*only (for )?EU'
      reply: '{"sql": "SELECT region, SUM(spend) AS total FROM region_minutes WHERE region = ''EU'' GROUP BY region", "explanation": "Adds up the EU''s spend over every minute the view holds."}'
```

Two views to ask about — each region's spend per minute, and the payments of 1,000 or more:

```sql
SELECT region, window_start, window_end, SUM(amount) AS spend, COUNT(*) AS payments
FROM TABLE(TUMBLE(TABLE payments, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY region, window_start, window_end
```

<!-- capture: t16-register-minutes -->
```text
$ pravaha register --name region_minutes --sql-file region_minutes.sql --keys 0,2
registered region_minutes  state=RUNNING  fingerprint=cc0d4ae594f3
a query with the same fingerprint is the same computation, shared
```
<!-- /capture -->

<!-- capture: t16-register-big -->
```text
$ pravaha register --name big_payments --sql-file big_payments.sql --keys 0
registered big_payments  state=RUNNING  fingerprint=848c8f79360a
a query with the same fingerprint is the same computation, shared
```
<!-- /capture -->

What may **you** use? The node answers for the caller, with the layer off too, and calls no model:

<!-- capture: t16-features -->
```text
GET /api/v1/ai/features
200
{
  "enabled": true,
  "values": "labels-only",
  "features": {
    "ask": true,
    "draft": true,
    "alert": true,
    "explain": true,
    "narrative": true,
    "page": true
  }
}
```
<!-- /capture -->

And what is configured — an administrator's view, with each provider's **health**, which never calls
the network (`UNKNOWN` is honest: nothing has called it yet), and never a key:

<!-- capture: t16-admin -->
```text
GET /api/v1/admin/ai
200
{
  "enabled": true,
  "dir": "conf/ai",
  "defaultModel": "mock/m",
  "types": 20,
  "providers": [
    {
      "name": "mock",
      "type": "mock",
      "enabled": true,
      "keySet": false,
      "health": {
        "status": "UNKNOWN",
        "detail": "configured; no call yet",
        "lastOk": null,
        "lastFailure": null,
        "calls": 0,
        "failures": 0
      },
      "models": [
        "mock/m"
      ]
    }
  ],
  "fileErrors": {}
}
```
<!-- /capture -->

In the console it is **Admin → AI**. **Test connection** makes one real, cheap call to the provider and
shows the outcome and how long it took — administrators only, a few a minute, never a key in the answer.
Below the engine's layer is the console's own, whose one provider is the engine (`type: server`): the
console holds no key and calls no model itself.

![Admin, AI layer: the engine's layer ON, its folder $PRAVAHA_HOME/conf/ai, the twenty provider types this build knows and the default model mock/m; the provider mock, type mock, status UNKNOWN, model mock/m with json-mode, tool-calls and chat, configured with no call yet, and Test connection answering OK, m answered in so many milliseconds; below it the console's layer ON with its one provider engine of type server](img/t16/01-admin-ai.png)

You should see `"enabled": true`, every feature open to you, and the provider `mock` with the model
`mock/m`, `UNKNOWN` until Test connection says `OK`.

## Step 2 · Ask your data

A question becomes **one `SELECT` over one view you may read**, run through the same read path as Flight
and the PostgreSQL gateway — as you. Every answer starts with **the SQL the model chose**:

<!-- capture: t16-ask -->
```text
$ pravaha query --ask 'total spend per region' --chart
the SQL the model chose
  SELECT region, SUM(spend) AS total FROM region_minutes GROUP BY region
Adds up each region's spend over every minute the view holds.

region  total
EU      6400
US      2900
APAC    2700

chart  bar  x=region y=total
3 rows; run as you; values sent to the model: labels-only; nothing saved
```
<!-- /capture -->

The explanation is one sentence the model wrote **about its SQL**, not about the data — it has not seen
the data. The chart is **Pravaha's** choice from the result's shape, never the model's: one category and
one measure, up to 30 rows, is a `bar`; one value a `stat`; a time column and a measure a `line`:

<!-- capture: t16-ask-line -->
```text
$ pravaha query --ask 'total spend per minute' --chart
the SQL the model chose
  SELECT window_end, SUM(spend) AS total FROM region_minutes GROUP BY window_end
Adds up every region's spend in each minute.

window_end           total
1790845260000000000  6800
1790845320000000000  5200

chart  line  x=window_end y=total
2 rows; run as you; values sent to the model: labels-only; nothing saved
```
<!-- /capture -->

(On this build `query --ask` prints a `TIMESTAMP` column as nanoseconds since 1970 where `query`
prints the instant — `1790845260000000000` is 2026-10-01 09:01:00 UTC; AIASKTS-1.) **Follow-ups**:
`chat` sends each question with the last three and the SQL chosen for them, so "and only for EU?"
changes the previous SQL rather than starting again:

<!-- capture: t16-chat -->
```text
$ pravaha chat
the SQL the model chose
  SELECT region, SUM(spend) AS total FROM region_minutes GROUP BY region
Adds up each region's spend over every minute the view holds.

region  total
EU      6400
US      2900
APAC    2700
3 rows; run as you; values sent to the model: labels-only; nothing saved

the SQL the model chose
  SELECT region, SUM(spend) AS total FROM region_minutes WHERE region = 'EU' GROUP BY region
Adds up the EU's spend over every minute the view holds.

region  total
EU      6400
1 row; run as you; values sent to the model: labels-only; nothing saved
```
<!-- /capture -->

When the engine refuses what the model wrote — here a column, `amt`, that is not there — the model gets
**one repair turn** with the engine's own words, and the answer says how many turns it took:

<!-- capture: t16-ask-repair -->
```text
$ pravaha query --ask 'what is the average big payment' --json
{
  "question": "what is the average big payment",
  "sql": "SELECT AVG(amount) AS average FROM big_payments",
  "explanation": "The average amount of the big payments.",
  "columns": [
    {
      "name": "average",
      "type": "INT64"
    }
  ],
  "rows": [
    [
      2550
    ]
  ],
  "rowCount": 1,
  "truncated": false,
  "chart": {
    "kind": "stat",
    "x": null,
    "y": "average",
    "series": null
  },
  "values": "labels-only",
  "summary": null,
  "turns": 2
}
```
<!-- /capture -->

Refused again, it would be `PRV-1088`. So is a question the views cannot answer — the model says so,
and nothing is guessed or run:

<!-- capture: t16-ask-unanswerable -->
```text
$ pravaha query --ask 'what is the weather in Paris'
PRV-1088  the model could not turn the question into a query over the views you may read: No view holds the weather: the views are spend per region per minute and the big payments.
  look PRV-1088 up in the console's help under Errors, or in docs/guides/TROUBLESHOOTING.md
(exit 1)
```
<!-- /capture -->

In the console, **Catalog → Ask your data** (`/ask`) — and a question box on **Views** and on each
view's page. Each answer shows the question, a badge for the `values` mode, the chart in the theme's
colours, the rows, and **The SQL the model chose** with *Open in Workbench*; a follow-up goes in the same
box, and *New conversation* starts again:

![Ask your data after a follow-up: the question and only for EU?, badged labels only and a bar chart, one row answered by the engine for you, a single crimson bar for EU at 6,400, the row EU 6400, The SQL the model chose folded, Explain in plain words; below, About this page and the Ask about this page box](img/t16/02-ask.png)

You should see EU `6400`, US `2900`, APAC `2700` with `chart bar`, the follow-up narrowed to EU,
`"turns": 2` for the repaired question, and `PRV-1088` for the weather.

## Step 3 · Draft a continuous query, and register it on confirmation

The model writes the parts — a name, the SQL, the keys, the options; the engine judges the whole
`CREATE CONTINUOUS QUERY` **as registering it would**, and dry-runs it: its fingerprint, what it reads,
the lane it would take and the registered queries whose computation it would share:

<!-- capture: t16-draft -->
```text
$ pravaha ask 'spend per region per minute' --engine
the engine accepts it

  CREATE CONTINUOUS QUERY region_spend
    KEYED BY (region, window_end)
  AS
  SELECT region, window_start, window_end, SUM(amount) AS spend, COUNT(*) AS payments FROM TABLE(TUMBLE(TABLE payments, DESCRIPTOR(event_time), INTERVAL '1' MINUTE)) GROUP BY region, window_start, window_end

What it does
  Each region's spend and number of payments in every one-minute window of event time.

Dry run (nothing registered)
fingerprint  cc0d4ae594f3
reads        payments
lane         the lane of region_minutes's computation, which it would share: no new computation
shares with  region_minutes
columns      region VARCHAR, window_start TIMESTAMP(3) WITH LOCAL TIME ZONE, window_end TIMESTAMP(3) WITH LOCAL TIME ZONE, spend INT64, payments INT64

The engine's plan (physical)
  WindowedAggregate(TUMBLING 60000ms, keys=[0, 1, 2], [SUM(amount)->spend, COUNT(*)->payments])
    Project[region, window_start, window_end, amount]
      WindowAssign(TUMBLING size=60000ms slide=60000ms on event_time)
        Project[payment_id, user_id, region, card_number, amount, event_time]
          Scan(payments)
nothing was registered; add --register to register it after you confirm
```
<!-- /capture -->

The draft asks the same question as `region_minutes`, so it has the same fingerprint, and registering
it would **share that computation** — no new lane, no second copy of the state. Nothing was registered.
`--register` registers it after you confirm (`y` at a terminal, or `--yes`):

<!-- capture: t16-draft-register -->
```text
$ pravaha ask 'spend per region per minute' --engine --register --yes
the engine accepts it

  CREATE CONTINUOUS QUERY region_spend
    KEYED BY (region, window_end)
  AS
  SELECT region, window_start, window_end, SUM(amount) AS spend, COUNT(*) AS payments FROM TABLE(TUMBLE(TABLE payments, DESCRIPTOR(event_time), INTERVAL '1' MINUTE)) GROUP BY region, window_start, window_end

What it does
  Each region's spend and number of payments in every one-minute window of event time.

Dry run (nothing registered)
fingerprint  cc0d4ae594f3
reads        payments
lane         the lane of region_minutes's computation, which it would share: no new computation
shares with  region_minutes
columns      region VARCHAR, window_start TIMESTAMP(3) WITH LOCAL TIME ZONE, window_end TIMESTAMP(3) WITH LOCAL TIME ZONE, spend INT64, payments INT64

The engine's plan (physical)
  WindowedAggregate(TUMBLING 60000ms, keys=[0, 1, 2], [SUM(amount)->spend, COUNT(*)->payments])
    Project[region, window_start, window_end, amount]
      WindowAssign(TUMBLING size=60000ms slide=60000ms on event_time)
        Project[payment_id, user_id, region, card_number, amount, event_time]
          Scan(payments)
registered region_spend  state=RUNNING  fingerprint=cc0d4ae594f3
```
<!-- /capture -->

In the Workbench, **Describe a continuous query** shows the same review; *Register…* opens a
confirmation and only *Register it* acts — on the draft the console holds, never SQL sent back by the
browser. Asked again now, the name is taken, so the draft is `region_spend_2`, and it would share with
both:

![The Workbench's Describe a continuous query: the description spend per region per minute, and the draft region_spend_2, not registered: what it does, the CREATE CONTINUOUS QUERY statement keyed by region and window_end, and its dry run reading payments, on the lane of region_minutes's computation which it would share with no new computation, sharing with region_minutes and region_spend, its fingerprint, with Open in Workbench and Register](img/t16/03-draft.png)

You should see `the engine accepts it`, `shares with  region_minutes`, `nothing was registered`, and then
`registered region_spend` with the same fingerprint.

## Step 4 · An alert from words

"Tell me when a region spends over 5000 in a minute" becomes a `CREATE ALERT` on a view you may read,
judged by the very checks `CREATE ALERT` runs — the view, the condition's columns and literals, the
channels, the options:

<!-- capture: t16-alert -->
```text
$ pravaha alert --ask 'tell me when a region spends over 5000 in a minute'
the engine accepts it

  CREATE ALERT region_over_5000 ON region_minutes
    WHERE spend > 5000
    NOTIFY ops_log
    WITH (severity = 'critical')
Notifies when a region's spend in one minute is over 5000.
nothing was created; run again with --yes, or run the statement with `pravaha query --sql`
```
<!-- /capture -->

<!-- capture: t16-alert-create -->
```text
$ pravaha alert --ask 'tell me when a region spends over 5000 in a minute' --yes
the engine accepts it

  CREATE ALERT region_over_5000 ON region_minutes
    WHERE spend > 5000
    NOTIFY ops_log
    WITH (severity = 'critical')
Notifies when a region's spend in one minute is over 5000.
region_over_5000: ACTIVE -- watches region_minutes and notifies [ops_log]
```
<!-- /capture -->

<!-- capture: t16-alerts -->
```text
$ pravaha alerts
NAME              VIEW            STATE   FIRING  PENDING  SEVERITY  CHANNELS  PROBLEM
region_over_5000  region_minutes  ACTIVE  1       0        critical  ops_log   -
```
<!-- /capture -->

The model's `WITH (severity = 'critical')` is kept (one that only repeats a default — `severity =
'warning'` — is left out of the statement shown). EU's 09:00 minute, 5700, is over
5000, so the alert fired at once. An alert compares a view's **own** columns; when no view computes what
the words need, the model says which continuous query would have to exist first:

<!-- capture: t16-alert-refused -->
```text
$ pravaha alert --ask 'tell me when a user spends over 3000 per day'
PRV-1088  the model could not write this as an alert on a view you may read: No view totals a user's spend per day; a continuous query of each user's spend in one-day windows would have to exist first.
  look PRV-1088 up in the console's help under Errors, or in docs/guides/TROUBLESHOOTING.md
(exit 1)
```
<!-- /capture -->

On **Alerts**, *Describe an alert*. Asked again, the name `region_over_5000` is taken and the draft is
named `region_over_2` — the counter replaced the name's own number instead of following it
(AINAME-1):

![Alerts, Describe an alert: the description, and the draft alert region_over_2, not created: notifies when a region's spend in one minute is over 5000, on the view region_minutes, when spend > 5000, notify ops_log, its statement with severity critical, Open in Workbench and Create; the page badged 1 firing, and the list of alerts beginning below](img/t16/04-alert.png)

You should see the statement `CREATE ALERT region_over_5000 ON region_minutes WHERE spend > 5000 NOTIFY
ops_log`, the alert `ACTIVE` with `1` firing, and `PRV-1088` for the alert no view can support.

## Step 5 · Explain, and what a model is sent

A query in words, **grounded in the engine's plan**: the engine plans it as you, and the model is given
the SQL and the plan:

<!-- capture: t16-explain-sql -->
```text
$ pravaha explain-sql --query region_minutes --engine
It reads payments, puts each one in the one-minute window of its event time and adds up the amount and counts the payments per region in each window. A minute is published when the watermark -- the newest event time less five seconds -- passes its end, so each region has one row per closed minute.
```
<!-- /capture -->

A refusal: the engine checks the SQL first, and the model is given its code, its words and the engine's
verdict:

<!-- capture: t16-why -->
```text
$ pravaha why PRV-2050 --sql 'SELECT user_id, SUM(amount) AS total FROM payments GROUP BY user_id' --engine
PRV-2050  It means the query keeps a running total for every user forever: the stream never ends, so its state never stops growing. Total per window instead.
Try: SELECT user_id, window_end, SUM(amount) AS total FROM TABLE(TUMBLE(TABLE payments, DESCRIPTOR(event_time), INTERVAL '1' MINUTE)) GROUP BY user_id, window_start, window_end
the engine raised PRV-2050 for this statement
```
<!-- /capture -->

<!-- sql: refused PRV-2050 -->
```sql
SELECT user_id, SUM(amount) AS total FROM payments GROUP BY user_id
```

On a query's page, **Explain in plain words** (the light bulb) does the same, with the plan it is
grounded in under the answer:

![A query's page, region_minutes: Explain in plain words, grounded in the engine's own plan for this query, answering that it reads payments, puts each one in the one-minute window of its event time, adds up the amount and counts the payments per region, and publishes a minute when the watermark passes its end; badged labels only, with the plan folded below](img/t16/05-explain.png)

**Why did this view change?** Pravaha keeps a view's recent committed changes from the first time
anyone asks about it, so the first answer says it has only just started:

<!-- capture: t16-why-changed-first -->
```text
$ pravaha explain --why-changed big_payments
Pravaha has kept this view's changes since <time> and none has been committed since then. Ask again after it changes.

view          big_payments
kept since    <time>
commits       0
rows entered  0
rows left     0
key           payment_id
keys entered  -
keys left     -
values sent to the model: labels-only; your row filter and masks applied
```
<!-- /capture -->

Two payments arrive, one of them big:

<!-- capture: t16-append -->
```text
$ printf '%s\n' '9,gus,EU,4111-1111-1111-9999,3000,2026-10-01T09:02:30Z' '10,hal,US,5500-0000-0000-2222,90,2026-10-01T09:02:40Z' >> "$PRAVAHA_HOME/data/incoming/payments.csv"
```
<!-- /capture -->

The mock has **no rule** for `explain-change`, so it echoes what it was sent — exactly what a real model
would have received:

<!-- capture: t16-why-changed -->
```text
$ pravaha explain --why-changed big_payments
echo: TASK: explain-change
<<<BEGIN UNTRUSTED changes id=<id>>>>
VIEW big_payments
KEY COLUMNS: payment_id
COMMITS: 1 since <time>
ROWS ENTERED: 1
ROWS LEFT: 0
KEYS ENTERED (up to 20): [9]
KEYS LEFT (up to 20): (none)
A key that both entered and left was updated.
<<<END UNTRUSTED changes id=<id>>>>

view          big_payments
kept since    <time>
commits       1
rows entered  1
rows left     0
key           payment_id
keys entered  9
keys left     -
values sent to the model: labels-only; your row filter and masks applied
```
<!-- /capture -->

Read the prompt. Under `values: labels-only` a change is told as **its keys and counts only**: payment 9
entered, nothing left — not its amount, its user or its card. The material sits between markers that
carry a random id per request, and the fixed system text says everything between them is data, never an
instruction. A person's own words are cleaned and marked the same way, and a catalogue description that
says "ignore previous instructions" is one more string; if a model were swayed by it, the SQL it chose
is still run as you, and refused by your grants.

**Masking** is the policy's, on top: anything matching `pravaha.ai.policy.mask-patterns` — here a card
number — becomes `•••` before a prompt leaves the node, whoever typed it:

<!-- file: data/t16/application.yaml -->
```yaml
      # Anything that looks like a card number becomes ••• before a prompt leaves the node.
      mask-patterns: ["\\b\\d{4}-\\d{4}-\\d{4}-\\d{4}\\b"]
```

<!-- capture: t16-masked -->
```text
$ pravaha why PRV-7006 --sql "SELECT payment_id FROM big_payments WHERE card_number = '4111-1111-1111-1234'" --engine
PRV-7006  echo: TASK: explain-refusal
<<<BEGIN UNTRUSTED code id=<id>>>>
PRV-7006
<<<END UNTRUSTED code id=<id>>>>
<<<BEGIN UNTRUSTED sql id=<id>>>>
SELECT payment_id FROM big_payments WHERE card_number = '•••'
<<<END UNTRUSTED sql id=<id>>>>
<<<BEGIN UNTRUSTED engine-verdict id=<id>>>>
the engine accepts this statement now
<<<END UNTRUSTED engine-verdict id=<id>>>>
the engine accepts this statement now: it raised no refusal for it
```
<!-- /capture -->

(No rule matched, so this is the echo again: the SQL arrived with `'•••'` where the card number was.
`redact` does the same for the value after a named field, `card_number: …`.)

| `pravaha.ai.values` | A model is sent |
|---|---|
| `labels-only` (the default) | the names, types and keys of what **you** may read, your own words, counts — **never a value**. A column masked for you is left out of the schema, so the model cannot be told it exists |
| `aggregates` | the same, and your **own** results and changes (after your row filter and masks, masked columns left out, at most `ask-rows`) to be worded — ask-your-data's answer gets a `summary`. Choose it only for a provider you may send such figures to |

**Ask about this page** is in every screen's *About this page* panel. The console sends the panel's own
words and up to four of its help topics, never anything from the browser, and the answer comes from that
text only — "The page does not say." when it does not:

![A stream's page, payments: Time and source with out-of-orderness PT5S and allowed lateness PT0S; About this page with its three cards; and Ask about this page with the question what is allowed lateness?, answered that allowed lateness is how long after a window closes a late row may still correct its answer, a later row being counted and dropped, and that this stream allows none](img/t16/06-page.png)

You should see the explanation, `PRV-2050` with a `Try:` rewrite, the echoed prompt with `KEYS ENTERED
(up to 20): [9]` and no amount, and `card_number = '•••'`.

## Step 6 · Who may, and what is recorded

Two gates, and both must open. **The engine's capability first**: a person who may read no view is
refused `PRV-7002` before any model is asked, and one who may not register a query cannot have one
drafted. **Then the AI policy**, per feature, with its roles and its own per-minute limit, beside the
per-person minute, day and cost budgets of every call:

<!-- file: data/t16/application.yaml -->
```yaml
    policy:
      per-user-per-minute: 60        # every AI call, per person (PRV-1085)
```

<!-- file: data/t16/application.yaml -->
```yaml
      features:
        draft: {roles: [admin]}      # who may have a continuous query drafted (PRV-1084 for anyone else)
```

Create `ann`, an analyst, and ask as her (`ANN=$(pravaha login --user ann)`, as in tutorial 11):

<!-- capture: t16-user -->
```text
$ printf '%s\n' 'Tutorial-ann-2026' | pravaha user create ann --roles analyst --password-stdin
created ann
```
<!-- /capture -->

<!-- capture: t16-ann-features -->
```text
$ PRAVAHA_TOKEN=$ANN pravaha query --ask 'total spend per region'
the SQL the model chose
  SELECT region, SUM(spend) AS total FROM region_minutes GROUP BY region
Adds up each region's spend over every minute the view holds.

region  total
EU      6400
US      2900
APAC    2700
3 rows; run as you; values sent to the model: labels-only; nothing saved
```
<!-- /capture -->

<!-- capture: t16-ann-draft -->
```text
$ PRAVAHA_TOKEN=$ANN pravaha ask 'spend per region per minute' --engine
PRV-1084  feature 'draft' is not open to your roles
  look PRV-1084 up in the console's help under Errors, or in docs/guides/TROUBLESHOOTING.md
(exit 1)
```
<!-- /capture -->

Every call is audited twice, **never with its text**: the policy's `ai.call` (who, the model, the
outcome — `DENY` with `policy` for ann's draft) and the feature's own (`ai.ask`, `ai.draft`, …: the
views read, the outcome, sizes):

<!-- capture: t16-audit-calls -->
```text
$ pravaha audit --action ai.call --limit 4
SEQUENCE  AT                              PRINCIPAL          ACTION   TARGET  DECISION  REASON
N         <time>                          ann                ai.call  mock/m  DENY      policy
N         <time>                          ann                ai.call  mock/m  ALLOW     ok
N         <time>                          pravaha-dev-admin  ai.call  mock/m  ALLOW     ok
N         <time>                          pravaha-dev-admin  ai.call  mock/m  ALLOW     ok
N retained of 10000, 0 evicted; older: --cursor N
```
<!-- /capture -->

<!-- capture: t16-audit-detail -->
```text
$ pravaha audit --action ai.call --limit 3 --json | python3 -c "import json, sys; [print(e['principal'], e.get('detail')) for e in json.load(sys.stdin)['events']]"
ann feature=draft promptChars=3203 answerChars=0 promptTokens=0 completionTokens=0 cost=N ms=N
ann feature=ask promptChars=2133 answerChars=161 promptTokens=534 completionTokens=41 cost=N ms=N
pravaha-dev-admin feature=explain promptChars=1383 answerChars=479 promptTokens=346 completionTokens=120 cost=N ms=N
```
<!-- /capture -->

<!-- capture: t16-audit-asks -->
```text
$ pravaha audit --action ai.ask --limit 3
SEQUENCE  AT                              PRINCIPAL          ACTION  TARGET          DECISION  REASON
N         <time>                          ann                ai.ask  region_minutes  ALLOW     ok 3 rows
N         <time>                          pravaha-dev-admin  ai.ask  -               DENY      refused PRV-1088
N         <time>                          pravaha-dev-admin  ai.ask  big_payments    ALLOW     ok 1 rows
N retained of 10000, 0 evicted; older: --cursor N
```
<!-- /capture -->

The detail is the feature, the sizes of the prompt and the answer in characters and tokens, the cost and
the time — enough to see who used what and how much, and nothing of what was asked or answered.

You should see ann's question answered, her draft refused with `PRV-1084`, and her refused call in the
trail as `DENY  policy`.

## Step 7 · A real provider

Everything above works the same with a real model behind it; only the folder changes. Pravaha ships one
example file per type in `config/ai/` (`conf/ai/` in a distribution), **every one `enabled: false`**,
and a `*.yaml.example` is never read. Copy one, switch it on, and point `default-model` at it:

```yaml
# conf/ai/anthropic.yaml
type: anthropic
enabled: true
api-key: ${ANTHROPIC_API_KEY}     # an ${ENV_NAME} placeholder only: a literal key is refused when the file is read
timeout: 60s
default-model: sonnet
models:
  - name: sonnet                  # the logical name: pravaha.ai.default-model: anthropic/sonnet
    id: <the model id, from Anthropic's model list>
    max-tokens: 400
```

```yaml
# conf/ai/openai.yaml
type: openai
enabled: true
api-key: ${OPENAI_API_KEY}
default-model: fast
models:
  - name: fast
    id: gpt-4o-mini
    capabilities: [chat, tool-calls, json-mode]
    cost-per-1k-input: 0.00015    # in whatever unit you budget in: cost-per-user-per-day uses it
    cost-per-1k-output: 0.0006
```

```yaml
# conf/ai/ollama.yaml -- a model on your own machine: no key, and nothing leaves it
type: ollama
enabled: true
base-url: http://localhost:11434
timeout: 300s                     # local models can be slow to load
default-model: local
models:
  - name: local
    id: llama3.1
```

The rules a real provider lives by:

- **Keys come only from the environment.** `api-key` (and every other secret) must be `${NAME}`; a
  literal is refused when the file is read, and the refusal names the setting, never the value. The key
  is never in an admin answer, an audit row or a log line.
- **A bad file is reported and skipped**, the others load: `GET /api/v1/admin/ai` lists it under
  `fileErrors`. Files are re-read every `pravaha.ai.reload-interval` (5 s): add, change or remove one
  without a restart.
- **Health never calls the network**; Test connection does, once. 429 is `PRV-1085`, a timeout
  `PRV-1086`, any other failure `PRV-1087` — never the vendor's body, which can echo a prompt.
- **Choose `values` per provider you trust**: `labels-only` sends no data at all; `aggregates` sends
  your own figures to that vendor.

The tutorial does not run a real provider: it would need a key, a network and money, and its answers
would differ from run to run. The [AI guide](../guides/AI.md) and the [AI layer's
design](../design/architecture/ai.md) list every type (OpenAI-compatible presets, Azure, Gemini, Cohere,
Bedrock), TLS and proxies.

You should see, after a restart with `default-model: anthropic/sonnet` and the key exported, the
provider `anthropic` on Admin → AI and Test connection answering `OK`.

## Next steps

- [Tutorial 10 · Alerts](10-alerts.md): everything an alert does once it exists — firing and clearing,
  snooze, acknowledge, pause, the webhook.
- [Tutorial 11 · Governance](11-governance.md): row filters and masks, which the AI features obey — a
  masked column is not in a model's schema at all, and ask-your-data's rows are narrowed as the reader's
  own query would be.
- The reference: [AI.md](../guides/AI.md) (every feature, its endpoint, its codes) and the [AI
  layer](../design/architecture/ai.md) (provider files, policy, masking, audit, adding a provider).

## If something goes wrong

| Symptom | What it means | What to do |
|---|---|---|
| `PRV-1080` | the AI layer is off: every endpoint answers this before anything is read | `pravaha.ai.enabled: true` (or `PRAVAHA_AI_ENABLED=true`) and a restart |
| `PRV-1081` | the provider's configuration cannot be used: a literal key, an unknown type, a missing base URL | Admin → AI shows the file's error; fix the file (it is re-read within seconds) |
| `PRV-1082` | no such model: `default-model` names a provider or model that is not loaded | `provider/model`, as Admin → AI lists them |
| `PRV-1084` | the AI policy does not open the feature to your roles | `pravaha.ai.policy.features.<feature>.roles` |
| `PRV-1085` | a per-person or per-feature limit, or the vendor's own 429 | wait the seconds the message says; raise the limit |
| `PRV-1086` / `PRV-1087` | the model did not answer in time / failed | Test connection; `timeout` in the file, `pravaha.ai.features.timeout-seconds` |
| `PRV-1088` | the model's answer could not be used: refused twice, no query at all, or the question cannot be answered from what you may read | ask about a view you can read; nothing was run, registered or created |
| `PRV-7002` | you may read no view (or may not register, for a draft), or the SQL the model chose names something you may not read | the grants; the SQL is quoted in the message |
| `PRV-1051` | a question or description missing, or longer than `max-question-chars` (600) | shorten it |
| an answer is `echo: TASK: ...` | the mock provider has no rule for that message | add a rule to `conf/ai/mock.yaml`, or use a real provider |
