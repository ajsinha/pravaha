"""Tutorial 15 -- Operating at scale (docs/tutorials/15-operating-at-scale.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Five queries on a node that shares lanes from the fourth (auto-from 3, two shared lanes), one asking
for a lane of its own; `pravaha lanes`; a drop that leaves headroom and an administrator's rebalance,
dry run first; the lane and read-admission metrics; `pravaha top`; Prometheus in Docker scraping the
node with an API key and the shipped rules, queried; the Grafana dashboards' panels; a thread dump
with jcmd, and the sidecar for a node in a container.
"""

import os

from tutorial_captures import REPO, Cli, Container, Node, Open, Sh, Snap, Until, Up

DOC = "15-operating-at-scale.md"

PROMETHEUS = Container(
    name="pravaha-prometheus", image="prom/prometheus:v2.53.2", port=9090, later=True,
    # On the host's network, so it reaches a node listening on 127.0.0.1; as you, so it can read the
    # files the scenario wrote into the node's home.
    options=("--network", "host", "--user", f"{os.getuid()}:{os.getgid()}",
             "-v", "{home}/prometheus.yml:/etc/prometheus/prometheus.yml:ro",
             "-v", "{home}/prometheus.token:/etc/prometheus/pravaha.token:ro",
             "-v", f"{REPO}/deploy/observability/prometheus:/etc/prometheus/rules:ro"),
    args=("--config.file=/etc/prometheus/prometheus.yml", "--web.listen-address=127.0.0.1:{port}",
          "--storage.tsdb.path=/tmp/prometheus"),
    ready="curl -sf http://127.0.0.1:{port}/-/ready",
)

NODE = Node(config="t15/application.yaml", containers=(PROMETHEUS,), files=(
    ("t15/streams", "conf/streams"),
    ("t15/txn.csv", "data/incoming/txn.csv"),
    ("t15/prometheus.yml", "prometheus.yml"),
))

SETTLEMENT = ("CREATE CONTINUOUS QUERY settlement KEYED BY (txn_id) WITH (lane = 'dedicated') "
              "AS SELECT txn_id, merchant, amount FROM txn")
WINDOW = ("SELECT {k}, window_start, window_end, SUM(amount) AS spend FROM TABLE(TUMBLE(TABLE txn, "
          "DESCRIPTOR(event_time), INTERVAL '1' MINUTE)) GROUP BY {k}, window_start, window_end")
#: Rates, durations and counts that are this run's, not the lesson's.
RATES = ((r"\b\d+(\.\d+)?/s\b", "N/s"),
         # The watermark's delay behind the wall clock: the sample data is of 2026-10-01, so days.
         (r"(\s)\d{2}d(\s)", r"\1Nd \2"))
PROM = "http://127.0.0.1:{pravaha-prometheus.port}"
#: A PromQL query's answer as `labels value`, one series a line.
PROMQL = ("python3 -c \"import json, sys; [print(r['metric']['__name__'], r['metric'].get('lane', '-'), "
          "r['value'][1]) for r in json.load(sys.stdin)['data']['result']]\"")
#: The node's process: the scratch node is told its HTTP port on its command line.
PID = "$(pgrep -f -- \"--server.port=$(echo {http} | sed 's/.*://')\" | head -n 1)"

STEPS = (
    Cli("t15-register-big", ("register", "--name", "big_txn", "--sql",
                             "SELECT txn_id, user_id, amount FROM txn WHERE amount >= 1000", "--keys", "0")),
    Cli("t15-register-users", ("register", "--name", "user_minutes", "--sql", WINDOW.format(k="user_id"),
                               "--keys", "0,2")),
    Cli("t15-register-merchants", ("register", "--name", "merchant_minutes", "--sql", WINDOW.format(k="merchant"),
                                   "--keys", "0,2")),
    Cli("t15-register-small", ("register", "--name", "small_txn", "--sql",
                               "SELECT txn_id, user_id, amount FROM txn WHERE amount < 100", "--keys", "0")),
    Cli("t15-register-dedicated", ("query", "--sql", SETTLEMENT)),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM settlement"), r"^8\s*$"),
    Cli("t15-lanes", ("lanes", "list")),
    Open("/admin/lanes"),
    Snap("01-lanes"),
    Cli("t15-drop", ("drop", "--name", "big_txn", "--yes")),
    Cli("t15-drop-users", ("drop", "--name", "user_minutes", "--yes")),
    Cli("t15-rebalance-dry", ("lanes", "rebalance")),
    Cli("t15-rebalance", ("lanes", "rebalance", "--yes")),
    Until(("lanes", "list"), r"small_txn\s+RUNNING\s+own"),
    Cli("t15-rebalance-status", ("lanes", "rebalance", "status")),
    Cli("t15-lanes-after", ("lanes", "list")),
    Open("/admin/lanes"),
    Snap("02-lanes-after"),
    Open("/queries/settlement"),
    Snap("03-dedicated"),
    # The node's own gauges are brought up to date every few seconds.
    Until("pravaha metrics | grep -E '^pravaha_lane_own_queries.* 3.0$'", r"own_queries"),
    Sh("t15-metrics", "pravaha metrics | grep -E '^pravaha_(lane_(shared|own)|read_)'"),
    Cli("t15-top", ("top", "--once", "--interval", "1"), subs=RATES),
    Open("/operations"),
    Snap("04-operations"),
    # Prometheus, with an API key of its own.
    Sh("t15-scraper", "printf '%s\\n' 'Scraper-only-2026' | pravaha user create metrics-reader --roles reader --service "
                      "--password-stdin"),
    Sh("t15-key", "pravaha key create prometheus --for metrics-reader --days 90 | tee key.out",
       show="pravaha key create prometheus --for metrics-reader --days 90"),
    Sh(None, "grep -o 'prv_[a-z]*_[0-9a-f]*_[A-Za-z0-9_-]*' key.out | head -n 1 > prometheus.token && chmod 644 prometheus.token "
             "&& sed -i \"s/localhost:18080/localhost:$(echo {http} | sed 's/.*://')/\" prometheus.yml"),
    Up("pravaha-prometheus"),
    Until(f"curl -s '{PROM}/api/v1/query?query=up' | grep -q '\"1\"' && echo up", r"^up"),
    Until(f"curl -s '{PROM}/api/v1/query?query=pravaha_lane_shared_queries' | grep -q value && echo in", r"^in"),
    Sh("t15-prom-up", f"curl -s '{PROM}/api/v1/query?query=up' | {PROMQL}",
       show=f"curl -s 'localhost:9090/api/v1/query?query=up' | {PROMQL}"),
    Sh("t15-prom-lanes", f"curl -s '{PROM}/api/v1/query?query=pravaha_lane_shared_queries' | {PROMQL}",
       show=f"curl -s 'localhost:9090/api/v1/query?query=pravaha_lane_shared_queries' | {PROMQL}"),
    Sh("t15-prom-rules", f"curl -s '{PROM}/api/v1/rules' | python3 -c \"import json, sys; "
                         "[print(g['name'] + ':', len(g['rules'])) for g in json.load(sys.stdin)['data']['groups']]\"",
       show="curl -s 'localhost:9090/api/v1/rules' | python3 -c \"import json, sys; "
            "[print(g['name'] + ':', len(g['rules'])) for g in json.load(sys.stdin)['data']['groups']]\""),
    Sh("t15-dashboards", f"cd {REPO} && for f in deploy/observability/grafana/*.json; do python3 -c \"import json, sys; "
                         "d = json.load(open(sys.argv[1])); print(sys.argv[1].split('/')[-1], '--', d['title'], '--', "
                         "len(d.get('panels', [])), 'panels')\" $f; done",
       show="for f in deploy/observability/grafana/*.json; do python3 -c \"import json, sys; "
            "d = json.load(open(sys.argv[1])); print(sys.argv[1].split('/')[-1], '--', d['title'], '--', "
            "len(d.get('panels', [])), 'panels')\" $f; done"),
    # A thread dump.
    Sh("t15-threads", f"jcmd {PID} Thread.print -l > threads.txt && grep -m1 '^Full thread dump' threads.txt "
                      "&& grep -oE '^\"[A-Za-z]+[-A-Za-z]*' threads.txt | sed 's/-$//' | sort | uniq -c | sort -k2 | "
                      "grep -E 'pravaha|lane|flight|Reference|main'",
       show="jcmd $(pgrep -f pravaha-server) Thread.print -l > threads.txt && grep -m1 '^Full thread dump' threads.txt "
            "&& grep -oE '^\"[A-Za-z]+[-A-Za-z]*' threads.txt | sed 's/-$//' | sort | uniq -c | sort -k2 | "
            "grep -E 'pravaha|lane|flight|Reference|main'",
       subs=((r"^\s*\d+ ", "      N "), (r"\(\d[^)]*\)", "(<version>)"))),
)
