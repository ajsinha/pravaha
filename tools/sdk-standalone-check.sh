#!/usr/bin/env bash
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# Proves the client SDKs work on their own: from the artefacts tools/build-sdk.sh leaves in
# target/sdk-dist, in directories outside this repository, against a running node.
#
#   tools/build-sdk.sh && tools/sdk-standalone-check.sh --docker pravaha/pravaha-server:local
#   tools/sdk-standalone-check.sh --endpoint grpc://localhost:19090 --view my_view   # a node you started
#
# Four clients, each of which sees nothing of this repository but the SDK artefacts:
#   1. a Maven project whose only dependency is com.ash.messaging:pravaha-sdk-java-flight, resolved
#      from a Maven repository holding no Pravaha artefact except the four in target/sdk-dist (the
#      rest is hard-linked from ~/.m2, and what is not there is downloaded). Its dependency tree is printed, and the
#      check fails if any Pravaha module but pravaha-api and the two SDK modules is in it;
#   2. the same program compiled and run with javac/java against the -all jar alone, no Maven;
#   3. a fresh virtualenv with the wheel installed with its [flight] extra (not editable, the
#      repository not on sys.path), reading the same view;
#   4. a fresh virtualenv with the bare wheel: `import pravaha` works, and a Flight call names the
#      extra it needs.
#
# --docker IMAGE starts a throwaway node from IMAGE on 127.0.0.1:39090 (HTTP 38080) with a small
# CSV-fed stream `txn`, registers `by_user` over it from client 1, and removes the container on
# exit. With --endpoint the view must already have rows, or client 1 registers `by_user` over a
# stream `txn` (txn_id, user_id, amount, status) the node declares. Needs a JDK 21 or later, Maven (`mvn`)
# and Python with venv or uv; client 3 downloads pyarrow unless pip or uv has it cached. Not part
# of any gate: it starts a server. On a machine where the daemon is reached through a group:
#   sg docker -c "tools/sdk-standalone-check.sh --docker pravaha/pravaha-server:local"
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
# JDK 21 or later on JAVA_HOME, or a stop naming the requirement (ADR-062).
source "$root/tools/jdk.sh"
dist="$root/target/sdk-dist"
work="${TMPDIR:-/tmp}/pravaha-sdk-standalone-check"
endpoint=""
image=""
view="by_user"
docker_bin="${DOCKER:-docker}"
container="pravaha-sdk-standalone-check"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --endpoint) endpoint="$2"; shift ;;
        --docker) image="$2"; shift ;;
        --view) view="$2"; shift ;;
        --dist) dist="$2"; shift ;;
        --work) work="$2"; shift ;;
        -h|--help) sed -n '5,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "sdk-standalone-check: unknown option $1 (see --help)" >&2; exit 2 ;;
    esac
    shift
done
[[ -n "$endpoint" || -n "$image" ]] || { echo "sdk-standalone-check: --endpoint or --docker is required" >&2; exit 2; }
[[ -d "$dist/java" && -d "$dist/python" ]] || { echo "sdk-standalone-check: run tools/build-sdk.sh first ($dist)" >&2; exit 2; }
case "$work" in "$root"|"$root"/*) echo "sdk-standalone-check: --work must be outside the repository" >&2; exit 2 ;; esac

version="$(ls "$dist/java" | sed -n 's/^pravaha-sdk-java-flight-\(.*\)-all\.jar$/\1/p')"
wheel="$(ls "$dist"/python/*.whl)"
rm -rf "$work"
mkdir -p "$work"
step() { printf '\n== %s\n' "$*"; }

if [[ -n "$image" ]]; then
    endpoint="grpc://localhost:39090"
    mkdir -p "$work/node"
    cat > "$work/node/application.yaml" <<'EOF'
pravaha:
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING"
  sources:
    txn:
      plugin: filesystem
      options:
        path: /opt/sdkcheck/txn.csv
        schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING"
EOF
    printf '1,alice,500,COMPLETED\n2,bob,90,PENDING\n3,carol,900,COMPLETED\n4,dave,150,COMPLETED\n' > "$work/node/txn.csv"
    chmod -R a+rX "$work/node"
    step "node: $image on 127.0.0.1:39090"
    "$docker_bin" rm -f "$container" >/dev/null 2>&1 || true
    trap '"$docker_bin" rm -f "$container" >/dev/null 2>&1 || true' EXIT
    # dev profile: a node that serves anonymous callers, which is what a throwaway on loopback is.
    "$docker_bin" run -d --name "$container" -p 127.0.0.1:38080:18080 -p 127.0.0.1:39090:19090 \
        -v "$work/node:/opt/sdkcheck:ro" "$image" \
        --spring.profiles.active=dev --spring.config.additional-location=file:/opt/sdkcheck/application.yaml >/dev/null
    for _ in $(seq 1 60); do
        "$docker_bin" logs "$container" 2>&1 | grep -q "Flight SQL listening" && break
        sleep 1
    done
    "$docker_bin" logs "$container" 2>&1 | grep -q "Flight SQL listening" \
        || { "$docker_bin" logs "$container" 2>&1 | tail -20; echo "sdk-standalone-check: the node did not start" >&2; exit 1; }
fi

# ---- the client program, shared by 1 and 2 ----
mkdir -p "$work/java-client/src/main/java"
cat > "$work/java-client/src/main/java/SdkProof.java" <<'EOF'
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.sdk.flight.Row;
import java.util.List;

/** Registers the view if it is not there yet, then prints its rows. */
public class SdkProof {
    public static void main(String[] args) throws Exception {
        String endpoint = args[0];
        String view = args[1];
        try (PravahaFlightClient client = PravahaFlightClient.connect(endpoint)) {
            if (client.queries().stream().noneMatch(q -> q.name().equals(view))) {
                client.register(view, "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED'", List.of(0));
                System.out.println("registered " + view);
            }
            for (int attempt = 0; attempt < 100; attempt++) {
                StringBuilder out = new StringBuilder();
                try (QueryResult result = client.query("SELECT * FROM " + view)) {
                    for (Row row : result) {
                        out.append("  ").append(java.util.Arrays.toString(row.toArray())).append('\n');
                    }
                }
                if (out.length() > 0) {
                    String from = PravahaFlightClient.class.getProtectionDomain().getCodeSource().getLocation().getPath();
                    System.out.println("rows of " + view + " (SDK loaded from " + from.substring(from.lastIndexOf('/') + 1) + "):");
                    System.out.print(out);
                    return;
                }
                Thread.sleep(200);
            }
            throw new IllegalStateException("no rows in " + view);
        }
    }
}
EOF
opens=(--add-opens=java.base/java.nio=ALL-UNNAMED)

# ---- 1. Maven, from a repository holding only the SDK's artefacts ----
step "1. a Maven client depending on com.ash.messaging:pravaha-sdk-java-flight:$version alone"
m2="$work/m2"
if ! cp -al "$HOME/.m2/repository" "$m2" 2>/dev/null; then rm -rf "$m2"; mkdir -p "$m2"; fi
rm -rf "$m2/com/ash/messaging"
mvn_q=(mvn -B -q -Dmaven.repo.local="$m2")
"${mvn_q[@]}" install:install-file -Dfile="$dist/java/pravaha-$version.pom" -DpomFile="$dist/java/pravaha-$version.pom"
for a in pravaha-api pravaha-sdk-java pravaha-sdk-java-flight; do
    "${mvn_q[@]}" install:install-file -Dfile="$dist/java/$a-$version.jar" -DpomFile="$dist/java/$a-$version.pom"
done
cat > "$work/java-client/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example.client</groupId>
  <artifactId>sdk-proof-java</artifactId>
  <version>1.0</version>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency>
      <groupId>com.ash.messaging</groupId>
      <artifactId>pravaha-sdk-java-flight</artifactId>
      <version>$version</version>
    </dependency>
  </dependencies>
</project>
EOF
(cd "$work/java-client" \
    && mvn -B -Dmaven.repo.local="$m2" dependency:tree -DoutputFile=tree.txt >/dev/null \
    && "${mvn_q[@]}" compile dependency:build-classpath -Dmdep.outputFile=cp.txt)
cat "$work/java-client/tree.txt"
stray="$(grep -o 'com\.ash\.messaging:[a-z0-9-]*' "$work/java-client/tree.txt" \
    | grep -vx 'com\.ash\.messaging:pravaha-\(api\|sdk-java\|sdk-java-flight\)' || true)"
[[ -z "$stray" ]] || { echo "sdk-standalone-check: the client's tree holds $stray" >&2; exit 1; }
java "${opens[@]}" -cp "$work/java-client/target/classes:$(cat "$work/java-client/cp.txt")" SdkProof "$endpoint" "$view"

# ---- 2. the -all jar, no build tool ----
step "2. javac and java against pravaha-sdk-java-flight-$version-all.jar alone"
mkdir -p "$work/all-client"
cp "$dist/java/pravaha-sdk-java-flight-$version-all.jar" "$work/java-client/src/main/java/SdkProof.java" "$work/all-client/"
(cd "$work/all-client" \
    && javac -cp "pravaha-sdk-java-flight-$version-all.jar" SdkProof.java \
    && java "${opens[@]}" -cp "pravaha-sdk-java-flight-$version-all.jar:." SdkProof "$endpoint" "$view")

# ---- 3 and 4. Python, from the wheel ----
venv() { # venv DIR SPEC
    if python3 -m venv "$1" >/dev/null 2>&1 && "$1/bin/python" -m pip --version >/dev/null 2>&1; then
        "$1/bin/python" -m pip install --quiet "$2"
    elif command -v uv >/dev/null 2>&1; then
        rm -rf "$1"
        uv venv --quiet "$1" && uv pip install --quiet --python "$1/bin/python" "$2"
    else
        echo "sdk-standalone-check: no way to make a virtualenv (python3 -m venv with pip, or uv)" >&2
        exit 1
    fi
}
step "3. pip install '$(basename "$wheel")[flight]' into a fresh virtualenv"
venv "$work/py-flight" "$wheel[flight]"
(cd "$work" && "$work/py-flight/bin/python" - "$endpoint" "$view" <<'EOF'
import sys, pravaha
endpoint, view = sys.argv[1], sys.argv[2]
print("pravaha", pravaha.__version__ if hasattr(pravaha, "__version__") else "", "from", pravaha.__file__)
with pravaha.connect(endpoint) as client:
    rows = list(client.query(f"SELECT * FROM {view}"))
print(f"rows of {view}:")
for row in rows:
    print("  ", dict(row) if hasattr(row, "keys") else row)
assert rows, "no rows"
EOF
)

step "4. pip install '$(basename "$wheel")' (no extras) into a fresh virtualenv"
venv "$work/py-bare" "$wheel"
(cd "$work" && "$work/py-bare/bin/python" - "$endpoint" <<'EOF'
import sys, pravaha
print("import pravaha: ok, from", pravaha.__file__)
try:
    import pyarrow  # noqa: F401
    raise SystemExit("pyarrow is installed in the bare environment; the check proves nothing")
except ImportError:
    print("pyarrow: not installed")
try:
    pravaha.connect(sys.argv[1])
    raise SystemExit("a Flight call worked without the flight extra")
except ImportError as e:
    assert "pravaha[flight]" in str(e), e
    print("pravaha.connect ->", type(e).__name__ + ":", str(e).splitlines()[0], "|", str(e).splitlines()[1].strip())
EOF
)

step "sdk-standalone-check: all four clients worked from the SDK artefacts alone"
