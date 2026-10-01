# Tutorial: joining two Aerospike sets and a CSV file

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

You will run Aerospike beside a Pravaha QA host, point Pravaha at two of its sets and at a CSV file,
register two continuous queries that join all three — once from Python and once from the console —
and then push live data into Aerospike and watch the answers move.

**Every step was run on a QA host installed from the 0.1.1 bundle**, and every output shown is what
it printed. The files it uses are in the QA bundle's `tutorials/aerospike-fulfilment/` directory and
in the repository's [`examples/tutorials/aerospike-fulfilment/`](../../examples/tutorials/aerospike-fulfilment).

## What you are building

An online shop's fulfilment check. Three things happen to an order, recorded in three places:

| Event | Where it is recorded | How Pravaha reads it |
|---|---|---|
| The order is placed | Aerospike, set `orders` | the `aerospike` source, as the stream `orders` |
| The payment is captured | Aerospike, set `payments` | the `aerospike` source, as the stream `payments` |
| The parcel is dispatched | a CSV file the warehouse appends to | the `filesystem` source, as the stream `shipments` |

Two continuous queries join them:

- **`fulfilled_orders`**: orders paid within 5 minutes of being placed and shipped within 2 minutes.
- **`awaiting_dispatch`**: orders paid in time but **not** shipped within 2 minutes. This is the queue
  the warehouse has to clear, and it is the one to watch.

Pravaha keeps both answers current as records land in Aerospike and lines land in the file. Nothing
polls, and nothing re-runs the join.

## Before you start

- A Pravaha QA host, installed with `install.sh` and running (`docker compose ps` in `/opt/pravaha`
  shows `pravaha-server` healthy and `pravaha-console` up). See `README.md` in the bundle.
- The QA token `install.sh` printed (`id qa`), and the `admin` password it printed for the console.
- Python 3.9 or later on the host, with two packages:

```bash
python -m venv ~/pravaha-tutorial && . ~/pravaha-tutorial/bin/activate
pip install aerospike "pravaha-qa-0.1.1/dist/pravaha-0.1.1-py3-none-any.whl[flight]"
```

- The two scripts and the configuration file, from the bundle:

```bash
cp -r pravaha-qa-0.1.1/tutorials/aerospike-fulfilment ~/aerospike-fulfilment && cd ~/aerospike-fulfilment
ls
# aerospike-fulfilment.yaml  fulfilment.py  push_orders.py
```

## Step 1 — Start Aerospike next to Pravaha

Run Aerospike on the **same Docker network as the engine**, so the engine can reach it by name. The
compose project is called `pravaha`, so its network is `pravaha_default`:

```bash
sudo docker run -d --name aerospike \
  --network pravaha_default \
  --ulimit nofile=15000:15000 \
  -p 127.0.0.1:3000:3000 \
  aerospike/aerospike-server
```

- `--network pravaha_default`: the engine will connect to `aerospike:3000`.
- `-p 127.0.0.1:3000:3000`: your Python script on the host connects to `127.0.0.1:3000`. It is
  bound to loopback, so the database is not exposed to the network.
- `--ulimit nofile=15000:15000`: **required**. Without it Aerospike exits a second after starting,
  and `docker logs aerospike` says `1024 system file descriptors not enough, config specified 15000`.

Check it is serving. The image comes with one namespace, `test`, which this tutorial uses:

```bash
sudo docker exec aerospike asinfo -v namespaces
# test
```

> `test` keeps its data in memory, which is right for a tutorial. For anything you keep, configure a
> namespace with persistent storage in `aerospike.conf` and change `namespace:` in step 4 to match.

## Step 2 — Somewhere to put the CSV file

The warehouse's file has to be writable by you and readable by the engine. A QA host has a
directory for exactly that: **`/opt/pravaha/feeds/`**. It is owned by you and mounted read-only into
the engine at the same path.

```bash
ls -ld /opt/pravaha/feeds
# drwxr-xr-x 2 you you 4096 ... /opt/pravaha/feeds
```

> **Installed from the 0.1.1 bundle?** Its install predates `feeds/`. Create the directory, and
> give the engine the mount, once:
>
> ```bash
> sudo mkdir -p /opt/pravaha/feeds && sudo chown "$USER" /opt/pravaha/feeds
> sudo sed -i 's|      - ${PRAVAHA_HOME:-/opt/pravaha}/logs:/opt/pravaha/logs|&\n      - ${PRAVAHA_HOME:-/opt/pravaha}/feeds:/opt/pravaha/feeds:ro|' /opt/pravaha/docker-compose.yml
> ```

## Step 3 — Put some orders into Aerospike

`push_orders.py` writes orders and payments to Aerospike, and appends shipments to the CSV file.
Start with `--seed`, which writes six orders whose outcomes are known in advance:

```bash
python push_orders.py --seed
```

```text
order     1001  c1    north    2500  2026-09-26T10:00:00Z
order     1002  c2    south   12000  2026-09-26T10:00:30Z
order     1003  c1    north     800  2026-09-26T10:01:00Z
order     1004  c3    east     4300  2026-09-26T10:01:30Z
order     1005  c4    west     9900  2026-09-26T10:02:00Z
order     1006  c2    south    1500  2026-09-26T10:02:30Z
payment   5001  for order 1001  card      2500  2026-09-26T10:00:20Z
payment   5002  for order 1002  wallet   12000  2026-09-26T10:01:00Z
payment   5003  for order 1003  card       800  2026-09-26T10:01:40Z
payment   5004  for order 1004  bank      4300  2026-09-26T10:02:10Z
payment   5005  for order 1005  card      9900  2026-09-26T10:09:00Z
shipment  9001  for order 1001  dhl             2026-09-26T10:01:00Z
shipment  9002  for order 1002  ups             2026-09-26T10:02:00Z
shipment  9003  for order 1003  fedex           2026-09-26T10:03:00Z
```

What to expect from these six orders:

| Order | What happened | `fulfilled_orders` | `awaiting_dispatch` |
|---|---|---|---|
| 1001, 1002, 1003 | paid in time, shipped in time | **yes** | no |
| 1004 | paid in time, **never shipped** | no | **yes**, once its 2 minutes have passed |
| 1005 | paid 7 minutes after ordering, outside the 5-minute window | no | no |
| 1006 | never paid | no | no |

How the script lays the data out in Aerospike:

- Each record's `event_time` bin holds **nanoseconds since the epoch, as an integer**, which is the
  form Pravaha's Aerospike source reads a `TIMESTAMP` from.
- `order_id` and `payment_id` are stored **as bins as well as keys**. A record's key is not a bin,
  and a query can only read bins.

## Step 4 — Tell Pravaha about the two sets and the file

`aerospike-fulfilment.yaml` declares the three streams and binds each to its source. The part that
matters for a join:

```yaml
pravaha:
  streams:
    orders:
      schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 5s
    # payments and shipments are declared the same way
  sources:
    orders:
      plugin: aerospike
      options:
        hosts: "aerospike:3000"
        namespace: test
        set: orders
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,event_time:TIMESTAMP"
        event.time: event_time
        deletes: detect                                   # what lets a join read this set
        deletes.state.dir: /opt/pravaha/data/aerospike/orders
        scan.interval.ms: "1000"
    # payments: the same, set: payments
    shipments:
      plugin: filesystem
      options:
        path: /opt/pravaha/feeds/shipments.csv
        schema: "shipment_id:INT64,order_id:INT64,carrier:STRING,event_time:TIMESTAMP"
        event.time: event_time
        skip.header: "true"
        follow: "true"                                    # keep reading as the file grows
```

**Why `deletes: detect`.** Pravaha reads an Aerospike set by scanning it every
`scan.interval.ms`. By default a rescan hands over an updated record again, and nothing withdraws
the old version. A join or an aggregate over that would count the record twice, so Pravaha refuses
the query with `PRV-2042` rather than answer wrongly. With `detect`, each scan is compared with what
was already emitted: a change arrives as a withdrawal and an insertion, and a deleted record is
withdrawn.

Install the file and pull it in with one line. Spring merges its maps into the ones
`application.yaml` already has, so the demonstration stream `txn` stays as it was:

```bash
sudo install -o 10001 -g 10001 -m 0600 aerospike-fulfilment.yaml /opt/pravaha/conf/
sudo sed -i '1i spring.config.import: optional:file:/opt/pravaha/conf/aerospike-fulfilment.yaml' \
  /opt/pravaha/conf/application.yaml
cd /opt/pravaha && sudo docker compose up -d && cd -        # restarts the engine with the new mount and config
```

Check the engine bound all three sources:

```bash
sudo docker logs pravaha-server 2>&1 | grep 'sources bound'
# ... sources bound: [orders <- aerospike[...], payments <- aerospike[...], shipments <- filesystem[...], txn <- filesystem[...]]
```

## Step 5 — The two queries

```sql
-- fulfilled_orders: paid within 5 minutes of the order, shipped within 2.
SELECT o.order_id, o.customer_id, o.region, o.amount, p.pay_method, s.carrier
FROM orders o
JOIN payments p
  ON p.order_id = o.order_id
 AND p.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '5' MINUTE
JOIN shipments s
  ON s.order_id = o.order_id
 AND s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '2' MINUTE
```

```sql
-- awaiting_dispatch: paid in time, and no shipment within 2 minutes of the order.
SELECT o.order_id, o.customer_id, o.region, o.amount, p.pay_method
FROM orders o
JOIN payments p
  ON p.order_id = o.order_id
 AND p.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '5' MINUTE
LEFT JOIN shipments s
  ON s.order_id = o.order_id
 AND s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '2' MINUTE
WHERE s.shipment_id IS NULL
```

How to read them:

- **The time bounds are what make these continuous.** Without one, a stream join would have to
  remember every order forever in case a matching payment turned up. With `BETWEEN … + INTERVAL`,
  Pravaha keeps an order only as long as a match could still arrive, and then lets it go.
- **`LEFT JOIN … WHERE s.shipment_id IS NULL`** is "no shipment arrived". Pravaha cannot say that
  about an order until the 2-minute window has passed in *event time*. So a row appears in
  `awaiting_dispatch` once the stream's clock (its watermark) is past the order time plus 2 minutes
  plus the 5 seconds of out-of-orderness. It never appears early, and it is never retracted.
- The payment method column is `pay_method`, not `method`. `METHOD` is a reserved word in SQL, and a
  query naming `p.method` is refused with `PRV-2001` before anything runs.

## Step 6 — Register them from Python

`fulfilment.py` validates each query, registers it, and reads the answers. Point it at the engine:

```bash
export PRAVAHA_TOKEN=<the qa token>
export PRAVAHA_URL=grpc://localhost:19090 PRAVAHA_HTTP_URL=http://localhost:18080
python fulfilment.py register
```

```text
registered fulfilled_orders: RUNNING, fingerprint 7f8a08d2ed47
registered awaiting_dispatch: RUNNING, fingerprint c3436dd976aa
```

The heart of it is two SDK calls, `validate` and `register`:

```python
from pravaha import ClientOptions, connect

c = connect(options=ClientOptions.create(
    "grpc://localhost:19090", token=os.environ["PRAVAHA_TOKEN"],
    http_url="http://localhost:18080", allow_insecure_token=True))    # a QA host serves plaintext

check = c.validate(FULFILLED)                 # plans it without running it
if not check["valid"]:
    raise SystemExit(check["diagnostics"][0]["message"])
c.register("fulfilled_orders", FULFILLED, key_columns=[0])            # keyed by order_id
```

Read them:

```bash
python fulfilment.py read
```

```text
fulfilled_orders: 3 row(s)
    {'order_id': 1001, 'customer_id': 'c1', 'region': 'north', 'amount': 2500, 'pay_method': 'card', 'carrier': 'dhl'}
    {'order_id': 1002, 'customer_id': 'c2', 'region': 'south', 'amount': 12000, 'pay_method': 'wallet', 'carrier': 'ups'}
    {'order_id': 1003, 'customer_id': 'c1', 'region': 'north', 'amount': 800, 'pay_method': 'card', 'carrier': 'fedex'}
awaiting_dispatch: 0 row(s)
```

Three fulfilled orders, exactly as the table in step 3 predicted. `awaiting_dispatch` is empty, and
that is correct: nothing has arrived since 10:03, so the clock has not passed order 1004's dispatch
window, and Pravaha will not call it unshipped yet. Step 8 moves the clock.

## Step 7 — Do the same in the console

Open `http://<host>:17070` and sign in as `admin`, or as a user an administrator added for you.

1. **Catalog.** The **Workbench**'s catalog panel lists `orders`, `payments` and `shipments` with
   their columns, next to `txn`.
2. **Register a third query from the Workbench.** Paste:

   ```sql
   SELECT order_id, customer_id, region, amount FROM orders WHERE amount >= 9900
   ```

   Then fill in **Register as** `big_orders`, **Key columns** `order_id` (a name works; so does its
   position, `0`), and press **Register**. The console opens the query's page. `big_orders` is
   `RUNNING`, reading the Aerospike set directly.
3. **Queries** lists all three, with their state, the rows they have taken in, and whether their
   sources are reading.
4. **Views → `fulfilled_orders`** shows the view's columns and key, and follows it live: rows appear
   here as step 8 pushes data.
5. **Run once** reads a *view*: `SELECT * FROM fulfilled_orders WHERE region = 'north'` returns its
   rows at once. It does not read a *stream*. `SELECT * FROM orders` is refused with `PRV-4023`,
   because a stream's rows are what continuous queries consume, not something to read back.

## Step 8 — Push live data and watch it move

Use two terminals. In the first, follow the alert queue:

```bash
python fulfilment.py follow
```

In the second, push an order a second, stamped with the current time:

```bash
python push_orders.py --live --rate 1
```

```text
pushing about 1.0 orders a second; Ctrl-C to stop
order     20001  c13   west     1200  2026-09-27T03:36:10Z
order     20002  c34   east     9900  2026-09-27T03:36:11Z
order     20003  c33   west     4800  2026-09-27T03:36:12Z
order     20004  c6    north   15000  2026-09-27T03:36:13Z
order     20005  c27   east      500  2026-09-27T03:36:14Z
order     20006  c4    north   15000  2026-09-27T03:36:15Z
payment   60002  for order 20002  wallet    9900  2026-09-27T03:36:15Z
...
```

Nine orders in ten get paid, and four paid orders in five get shipped. Within seconds the first
terminal prints order 1004. The live data has moved the clock past its dispatch window at last.
From then on it prints each live order that missed its window, about two minutes after it was
placed:

```text
following awaiting_dispatch; Ctrl-C to stop
now waiting: {'order_id': 1004, 'customer_id': 'c3', 'region': 'east', 'amount': 4300, 'pay_method': 'bank'}
now waiting: {'order_id': 20020, 'customer_id': 'c12', 'region': 'west', 'amount': 2500, 'pay_method': 'bank'}
now waiting: {'order_id': 20017, 'customer_id': 'c14', 'region': 'west', 'amount': 1200, 'pay_method': 'bank'}
now waiting: {'order_id': 20024, 'customer_id': 'c13', 'region': 'north', 'amount': 2500, 'pay_method': 'wallet'}
```

After three minutes of pushing, on the verified run, the answers stood at:

```text
fulfilled_orders: 96 row(s)
awaiting_dispatch: 5 row(s)
```

The console's live view of `fulfilled_orders` grows as you watch.

The subscription behind `follow` is one SDK call. Each batch is one commit of the view, and each row
carries a weight: `+1` for a row that appeared. This view only ever adds rows, but a subscriber
should apply the weight rather than assume it:

```python
for batch in c.subscribe("awaiting_dispatch", snapshot=True):
    for row in batch:
        print("now waiting" if row.weight > 0 else "cleared", row.to_dict())
```

## What just happened

- **One computation per question, kept current.** Each record landing in Aerospike reached Pravaha
  on the next scan (every second). Each line appended to the CSV was read as it was written, and only
  the joins that could be affected did any work.
- **Two Aerospike sets and a file joined as streams.** Each side kept its own event time. A join is
  only as current as its slowest input, because its watermark is the minimum of theirs. So
  `awaiting_dispatch` waited until *every* input had moved past an order's window.
- **Why 1004 needed live data.** With nothing new arriving, event time stood still at the last seed
  record. The engine never guesses that a shipment will not come. It waits until the clock says the
  window has closed.

## When it does not work

| You see | Cause | Do |
|---|---|---|
| `aerospike` exits at once; its log says `system file descriptors not enough` | the container's open-file limit | run it with `--ulimit nofile=15000:15000` (step 1) |
| registration refused with `PRV-2042` | a join over an Aerospike source without `deletes: detect` | add `deletes: detect` and `deletes.state.dir` to both sources (step 4) |
| `PRV-2001  Encountered ". method"` | `METHOD` is a reserved word | name the bin something else (`pay_method`) |
| the engine logs a connection failure to `aerospike:3000` | Aerospike is not on the engine's network | start it with `--network pravaha_default` (step 1) |
| `push_orders.py`: `Permission denied: /opt/pravaha/feeds/shipments.csv` | `feeds/` is not yours | `sudo chown "$USER" /opt/pravaha/feeds` (step 2) |
| `awaiting_dispatch` stays empty | event time has not passed any order's window | push live data (step 8); rows appear about 2 minutes after the order |
| `fulfilled_orders` empty after registering | the engine was not restarted after step 4, so the streams have no source | `docker compose up -d` in `/opt/pravaha`, then check `sources bound` |

## Clean up

```bash
python fulfilment.py drop                        # drops fulfilled_orders and awaiting_dispatch
# big_orders: open it from the console's Queries page and press Drop
sudo sed -i '/aerospike-fulfilment.yaml/d' /opt/pravaha/conf/application.yaml
sudo rm /opt/pravaha/conf/aerospike-fulfilment.yaml
cd /opt/pravaha && sudo docker compose up -d && cd -
sudo docker rm -f aerospike
```

## Where next

In the console's Help:

- [The Aerospike source](/help/topics/source-aerospike) in full: every option, pushdown, scanning,
  and what `deletes: detect` costs.
- [Joins](/help/topics/joins): which shapes run, time bounds, `LEFT JOIN` and three-way joins.
- [The Python integration guide](/help/python-api-guide): every call used here, and the rest.
