"""Tutorial 7 -- Kafka end to end (docs/tutorials/07-kafka-end-to-end.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

A Kafka broker in Docker; payments produced to a topic as JSON, read by a kafka source, filtered by
a continuous query and written back to a topic by kafka-sink; a malformed payment on the dead-letter
queue; each user's spend per minute written in Avro and read back by a second kafka source with the
same schema file; the checkpointed offsets reported to a consumer group.
"""

from tutorial_captures import Cli, Container, Node, Open, Sh, Snap, Until

DOC = "07-kafka-end-to-end.md"

KAFKA = Container(
    name="pravaha-kafka", image="apache/kafka:3.9.1", port=9092,
    # One broker that is its own controller, listening (and advertising) on the one port it is given.
    env=(("KAFKA_NODE_ID", "1"), ("KAFKA_PROCESS_ROLES", "broker,controller"),
         ("KAFKA_LISTENERS", "PLAINTEXT://:{port},CONTROLLER://:9093"),
         ("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:{port}"),
         ("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER"),
         ("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"),
         ("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093"),
         ("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1"), ("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1"),
         ("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1"), ("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")),
    ready="docker exec {container} /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:{port} --list",
)

NODE = Node(config="t07/application.yaml", containers=(KAFKA,), files=(
    ("t07/streams", "conf/streams"),
    ("t07/sinks", "conf/sinks"),
    ("t07/connectors", "conf/connectors"),
    ("t07/payments.jsonl", "payments.jsonl"),
    ("t07/user_spend.avsc", "conf/user_spend.avsc"),
    ("t07/user_spend.avsc", "user_spend.avsc"),
    ("t07/user_spend.sql", "user_spend.sql"),
))

BIN = "/opt/kafka/bin"
Q = ("--name", "big_payments")


def kafka(capture, tool: str, args: str, stdin: str = "", pipe: str = "", prefix: str = "") -> Sh:
    """A Kafka tool run inside the broker's container, as a reader runs it: ``prefix`` feeds it, ``stdin``
    is a file it reads, ``pipe`` what its output goes through."""
    flag = "-i " if stdin or prefix else ""
    tail = (f" < {stdin}" if stdin else "") + (f" | {pipe}" if pipe else "")
    return Sh(capture, f"{prefix}docker exec {flag}{{pravaha-kafka}} {BIN}/{tool} "
                       f"--bootstrap-server localhost:{{pravaha-kafka.port}} {args}{tail}",
              show=f"{prefix}docker exec {flag}pravaha-kafka {BIN}/{tool} --bootstrap-server localhost:9092 {args}{tail}")


def produce(capture, *lines: str) -> Sh:
    quoted = " ".join(f"'{line}'" for line in lines)
    return kafka(capture, "kafka-console-producer.sh", "--topic payments", prefix=f"printf '%s\\n' {quoted} | ")


#: A dead letter's id, from `pravaha dlq list --json` (the tutorial copies it from the list).
NEWEST = """"$(python3 -c 'import json,sys; print(json.load(sys.stdin)["entries"][0]["id"])' < dlq.json)\""""
#: The WHEN column of `pravaha dlq list` is as wide as its instant: set to one width, as tutorial 6 does.
WHEN = ((r"^ID(\s+)WHEN\s+CODE", r"ID\1WHEN                    CODE"),
        (r"^(\S+\s+)\d{4}-\d\d-\d\dT[\d:.]+Z\s+", r"\1<time>                  "),
        (r"^1 dead letter \(\d+ bytes\)", "1 dead letter (N bytes)"))
#: What `pravaha describe` prints that is about this run, not the query.
DESCRIBE_OMIT = (r"^(execution|registered|sql|SELECT|FROM|GROUP|--)",)
#: The console consumer's own count, and the Kafka tools' warnings, go to stderr: not part of the lesson.
QUIET = "2>/dev/null"

BAD = '{"payment_id":9,"user_id":"erin","merchant":"acme","amount":"12,000","event_time":"2026-10-01T09:03:20Z"}'
FIXED = '{"payment_id":9,"user_id":"erin","merchant":"acme","amount":12000,"event_time":"2026-10-01T09:03:20Z"}'
LATER = '{"payment_id":10,"user_id":"frank","merchant":"globex","amount":50,"event_time":"2026-10-01T09:04:30Z"}'
BIG = "SELECT payment_id, user_id, amount FROM payments WHERE amount >= 2000"
FROM_AVRO = "SELECT user_id, window_end, spend FROM user_spend_avro WHERE spend >= 5000"

BEFORE_START = (
    Sh(None, "mkdir -p data"),
    Sh("t07-topics", "for topic in payments big-payments user-spend; do docker exec {pravaha-kafka} "
                     f"{BIN}/kafka-topics.sh --bootstrap-server localhost:{{pravaha-kafka.port}} "
                     "--create --topic $topic --partitions 1; done",
       show="for topic in payments big-payments user-spend; do docker exec pravaha-kafka "
            f"{BIN}/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic $topic --partitions 1; done"),
    kafka("t07-produce", "kafka-console-producer.sh", "--topic payments", stdin="payments.jsonl"),
    kafka("t07-peek", "kafka-console-consumer.sh", f"--topic payments --from-beginning --max-messages 3 {QUIET}"),
)

STEPS = (
    Cli("t07-plugins", ("plugins",), omit=(r"^(?!NAME|kafka)",)),
    Cli("t07-streams", ("streams", "list")),
    Cli("t07-sinks", ("sinks",)),
    Cli("t07-connectors", ("connectors", "list")),
    Open("/catalog/streams/payments"),
    Snap("01-stream"),
    Cli("t07-register-big", ("register", *Q, "--sql", BIG, "--keys", "0", "--sink", "big_payments_out")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM big_payments"), r"^4\s*$"),
    Cli("t07-query-big", ("query", "--sql", "SELECT * FROM big_payments")),
    produce("t07-bad", BAD),
    Until(("dlq", "count", *Q), r"^total\s+1\s*$"),
    Cli("t07-dlq-list", ("dlq", "list", *Q), subs=WHEN),
    Sh(None, "pravaha dlq list --name big_payments --json > dlq.json"),
    Sh("t07-dlq-show", f"pravaha dlq show --name big_payments --id {NEWEST}",
       show="pravaha dlq show --name big_payments --id <id>"),
    Open("/queries/big_payments/dead-letters"),
    Snap("02-dead-letters"),
    produce("t07-fixed", FIXED, LATER),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM big_payments"), r"^5\s*$"),
    Cli("t07-query-big-fixed", ("query", "--sql", "SELECT * FROM big_payments")),
    Until(("describe", "big_payments"), r"5 rows written"),
    Cli("t07-describe-big", ("describe", "big_payments"), omit=DESCRIBE_OMIT),
    kafka("t07-consume-big", "kafka-console-consumer.sh",
          "--topic big-payments --from-beginning --isolation-level read_committed "
          f"--property print.key=true --property key.separator=' => ' --max-messages 5 {QUIET}"),
    kafka("t07-groups", "kafka-consumer-groups.sh", "--describe --group pravaha-payments"),
    Open("/queries/big_payments"),
    Snap("03-query-sink"),
    Cli("t07-register-spend", ("register", "--name", "user_spend", "--sql-file", "user_spend.sql", "--keys", "0,1",
                               "--sink", "user_spend_out")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM user_spend"), r"^8\s*$"),
    Cli("t07-query-spend", ("query", "--sql", "SELECT * FROM user_spend")),
    Until(("describe", "user_spend"), r"8 rows written"),
    kafka("t07-avro-bytes", "kafka-console-consumer.sh",
          "--topic user-spend --from-beginning --isolation-level read_committed --property print.key=true "
          f"--max-messages 1 {QUIET}", pipe="od -An -c"),
    Cli("t07-register-from-avro", ("register", "--name", "spend_from_avro", "--sql", FROM_AVRO, "--keys", "0,1")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM spend_from_avro"), r"^4\s*$"),
    Cli("t07-query-from-avro", ("query", "--sql", "SELECT * FROM spend_from_avro")),
    Open("/views/spend_from_avro/live", "document.getElementById('live-state').dataset.state === 'fresh'"),
    Snap("04-avro-view"),
    Cli("t07-health", ("health",)),
)
