## Software Requirements Specification (SRS) & System Architecture Document

**Product Name:** **Project Pravaha (प्रवाह)** — *A Pluggable, High-Throughput Continuous Query Engine*

**Document Version:** 1.0-DRAFT

---

## 1. Executive Summary & Vision

**Pravaha** (Sanskrit for *"continuous stream or uninterrupted flow"*) is an enterprise-grade, distributed continuous query engine designed to execute real-time stateful, windowed, and relational streaming queries over high-throughput persistence engines.

While traditional stream processors (e.g., Apache Flink) require dedicated infrastructure and complex operations, **Pravaha** acts as a lightweight, embeddable, and plug-and-play middleware framework. It maps storage CDC (Change Data Capture) feeds directly into an Apache Calcite-based relational algebra pipeline, allowing continuous SQL execution while maintaining state across persistent backends like **Aerospike**, **ScyllaDB/Cassandra**, **Redis**, or **PostgreSQL**.

### Key Architectural Differentiators

* **Pluggable Storage Abstraction (SPI):** Decoupled Source/Sink mechanics supporting Aerospike, Cassandra, and SQL stores without modifying the query engine core.
* **Declarative Continuous SQL:** Uses Apache Calcite with `STREAM` and windowing semantics (`TUMBLE`, `HOP`, `SESSION`).
* **Polyglot Client Access:** Features an embedded Apache Calcite Avatica gateway providing native protocol compatibility for **Java**, **Python** (`phoenixdb`), **Go**, and **Node.js**.
* **Embedded Local State:** Integrates RocksDB for high-speed, local state management during windowed aggregations, backing up state periodically to persistent stores.

---

## 2. Product Requirements & Use Cases

### Functional Requirements (FR)

* **FR-1 (Dynamic Schema Discovery & Binding):** The engine shall dynamically inspect target persistence schemas (e.g., Aerospike Bins, Cassandra Columns) at boot time or via declarative YAML/JSON configuration.
* **FR-2 (Continuous Query Processing):** The engine must continuously evaluate standard ANSI SQL queries extended with streaming capabilities (`SELECT STREAM ...`).
* **FR-3 (Pushdown Optimization):** The Calcite optimizer layer must push filter predicates (`WHERE bin_x = 'val'`) directly into native storage secondary index queries whenever possible.
* **FR-4 (Windowing & State Management):** Support Tumbling, Hopping, and Session windows with sliding event-time evaluation using RocksDB as an off-heap state backend.
* **FR-5 (Sink Routing):** Computed deltas/aggregations must automatically write out to configured destination sinks (e.g., updating an Aerospike record or triggering an HTTP/gRPC alert).
* **FR-6 (Polyglot Client API):** Expose an HTTP/Protobuf Avatica RPC interface enabling non-Java clients (Python, Go) to register queries and stream continuous result sets.

### Non-Functional Requirements (NFR)

* **NFR-1 (Sub-millisecond Latency):** Query parsing and stream evaluation overhead must not add more than 2ms latency per record (excluding network transfer).
* **NFR-2 (High Throughput):** A single engine instance must process $\ge 100,000$ CDC events/second per core.
* **NFR-3 (Fault Tolerance & Exact-Once Processing):** State checkpoints must allow the engine to recover to the exact event stream offset without duplicating aggregated states on crash.
* **NFR-4 (Zero Tight-Coupling):** Storage-specific client libraries must reside strictly within plugin modules (`pravaha-plugin-aerospike`, `pravaha-plugin-cassandra`).

---

## 3. High-Level System Architecture

```
                  ┌─────────────────────────────────────────────────┐
                  │                 Polyglot Clients                │
                  │   (Python / Go / Java / Node.js via Avatica)   │
                  └────────────────────────┬────────────────────────┘
                                           │ HTTP / Protobuf RPC
                                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                   PRAVAHA ENGINE CORE                                  │
│                                                                                        │
│  ┌──────────────────────────────────────────────────────────────────────────────────┐  │
│  │                              Calcite Avatica Server                              │  │
│  └───────────────────────────────────────┬──────────────────────────────────────────┘  │
│                                          │                                             │
│  ┌───────────────────────────────────────▼──────────────────────────────────────────┐  │
│  │                              SQL / Streaming Parser                              │  │
│  │                 (Validates AST, Resolves Schemas via SPI)                        │  │
│  └───────────────────────────────────────┬──────────────────────────────────────────┘  │
│                                          │                                             │
│  ┌───────────────────────────────────────▼──────────────────────────────────────────┐  │
│  │                           Cost-Based Optimizer (CBO)                             │  │
│  │             (Rule-based Filter & Projection Pushdown to Source SPI)              │  │
│  └───────────────────────────────────────┬──────────────────────────────────────────┘  │
│                                          │                                             │
│  ┌───────────────────────────────────────▼──────────────────────────────────────────┐  │
│  │                       Continuous Relational Execution Pipeline                   │  │
│  │                                                                                  │  │
│  │   ┌────────────────────────┐    ┌───────────────────┐    ┌───────────────────┐   │  │
│  │   │  Blocking Queue Buffer │ ──>│  RocksDB State    │ ──>│ Delta Output      │   │  │
│  │   │  (Backpressure Control)│    │  (Window Storage) │    │ Dispatcher        │   │  │
│  │   └────────────────────────┘    └───────────────────┘    └─────────┬─────────┘   │  │
│  └────────────────────────────────────────────────────────────────────┼─────────────┘  │
└──────────────────────────────────────────▲────────────────────────────┼────────────────┘
                                           │                            │
                     ContinuousRecord Stream│                            │ ContinuousRecord
                                           │                            ▼
┌──────────────────────────────────────────┴─────────────────────────────────────────────┐
│                               PLUG-AND-PLAY SPI LAYER                                 │
│                                                                                        │
│     ┌────────────────────────────────┐            ┌──────────────────────────────┐     │
│     │      StreamSourcePlugin        │            │       StreamSinkPlugin       │     │
│     └───────────────┬────────────────┘            └───────────────┬──────────────┘     │
└─────────────────────┼─────────────────────────────────────────────┼────────────────────┘
                      │                                             │
         ┌────────────┴────────────┐                   ┌────────────┴────────────┐
         │                         │                   │                         │
         ▼                         ▼                   ▼                         ▼
┌─────────────────┐       ┌─────────────────┐ ┌─────────────────┐       ┌─────────────────┐
│ Aerospike CDC / │       │ Cassandra CDC / │ │ Aerospike Set / │       │ Redis / Kafka / │
│ Change Notifier │       │ CommitLog Feed  │ │ Materialized    │       │ PostgreSQL      │
└─────────────────┘       └─────────────────┘ └─────────────────┘       └─────────────────┘

```

---

## 4. Component Deep Dive & Plugin SPI Interface

The core framework exposes clean Service Provider Interfaces (SPIs) that decouple stream intake, continuous evaluation, and state persistence.

### 4.1 Core Data Abstraction

```java
package com.pravaha.core.model;

import java.io.Serializable;
import java.util.Map;

/**
 * Universal event wrapper carrying internal CDC payloads across Pravaha.
 */
public final class ContinuousRecord implements Serializable {
    private final String sourceEngine;
    private final String entityName; // Table, Set, or Keyspace name
    private final long eventTimestamp;
    private final Map<String, Object> payload;

    public ContinuousRecord(String sourceEngine, String entityName, long eventTimestamp, Map<String, Object> payload) {
        this.sourceEngine = sourceEngine;
        this.entityName = entityName;
        this.eventTimestamp = eventTimestamp;
        this.payload = payload;
    }

    public Object getField(String name) { return payload.get(name); }
    public Map<String, Object> getPayload() { return payload; }
    public long getEventTimestamp() { return eventTimestamp; }
    public String getEntityName() { return entityName; }
}

```

### 4.2 Stream Source & Sink Plugin SPIs

```java
package com.pravaha.spi;

import com.pravaha.core.model.ContinuousRecord;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

public interface StreamSourcePlugin {
    void init(Map<String, Object> config) throws Exception;
    
    /**
     * Spawns non-blocking worker listening to physical store CDC 
     * and pushing normalized records into internal engine queue.
     */
    void start(BlockingQueue<ContinuousRecord> outputQueue);
    
    void stop() throws Exception;
}

public interface StreamSinkPlugin {
    void init(Map<String, Object> config) throws Exception;
    
    /**
     * Persists evaluated window outputs/deltas to target engine.
     */
    void write(ContinuousRecord record) throws Exception;
    
    void close() throws Exception;
}

```

---

## 5. Pravaha Engine Core (Calcite Custom Adapter)

The core engine translates standard Calcite relational expressions into push-based enumerators backed by thread-safe queues.

### 5.1 Custom `StreamableTable` Implementation

```java
package com.pravaha.core.engine;

import com.pravaha.spi.StreamSourcePlugin;
import com.pravaha.core.model.ContinuousRecord;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.StreamableTable;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractTable;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class PravahaStreamTable extends AbstractTable implements StreamableTable, ScannableTable {

    private final StreamSourcePlugin sourcePlugin;
    private final List<String> fieldNames;
    private final List<Class<?>> fieldTypes;

    public PravahaStreamTable(StreamSourcePlugin sourcePlugin, List<String> fieldNames, List<Class<?>> fieldTypes) {
        this.sourcePlugin = sourcePlugin;
        this.fieldNames = fieldNames;
        this.fieldTypes = fieldTypes;
    }

    @Override
    public Table stream() {
        return this;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        RelDataTypeFactory.Builder builder = typeFactory.builder();
        for (int i = 0; i < fieldNames.size(); i++) {
            builder.add(fieldNames.get(i), typeFactory.createJavaType(fieldTypes.get(i)));
        }
        return builder.build();
    }

    @Override
    public Enumerable<Object[]> scan(DataContext root) {
        BlockingQueue<ContinuousRecord> queue = new LinkedBlockingQueue<>(20_000);
        sourcePlugin.start(queue);

        return new AbstractEnumerable<Object[]>() {
            @Override
            public Enumerator<Object[]> enumerator() {
                return new PravahaQueueEnumerator(queue, fieldNames);
            }
        };
    }
}

```

### 5.2 Blocking Queue Push Enumerator

```java
package com.pravaha.core.engine;

import com.pravaha.core.model.ContinuousRecord;
import org.apache.calcite.linq4j.Enumerator;

import java.util.List;
import java.util.concurrent.BlockingQueue;

public class PravahaQueueEnumerator implements Enumerator<Object[]> {

    private final BlockingQueue<ContinuousRecord> queue;
    private final List<String> fieldNames;
    private Object[] current;

    public PravahaQueueEnumerator(BlockingQueue<ContinuousRecord> queue, List<String> fieldNames) {
        this.queue = queue;
        this.fieldNames = fieldNames;
    }

    @Override
    public Object[] current() {
        return current;
    }

    @Override
    public boolean moveNext() {
        try {
            // Blocks continuously waiting for the next pushed CDC record
            ContinuousRecord record = queue.take();
            this.current = new Object[fieldNames.size()];
            for (int i = 0; i < fieldNames.size(); i++) {
                this.current[i] = record.getField(fieldNames.get(i));
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void reset() {}

    @Override
    public void close() {}
}

```

---

## 6. Embedded RocksDB Local State Management

For windowed computations (`GROUP BY TUMBLE(...)`), maintaining window aggregations in heap memory causes high Garbage Collection pressure under volume. **Pravaha** embeds **RocksDB** to store sliding state off-heap.

```java
package com.pravaha.core.state;

import org.rocksdb.*;

import java.io.File;

public class PravahaStateBackend {

    private RocksDB db;
    private Options options;

    public void init(String dbPath) throws RocksDBException {
        RocksDB.loadLibrary();
        options = new Options();
        options.setCreateIfMissing(true);
        options.setOptimizeUniversalCompaction();
        
        File dir = new File(dbPath);
        if (!dir.exists()) dir.mkdirs();
        
        db = RocksDB.open(options, dbPath);
    }

    public void updateWindowAggregate(String windowKey, byte[] aggregatedValue) throws RocksDBException {
        db.put(windowKey.getBytes(), aggregatedValue);
    }

    public byte[] getWindowAggregate(String windowKey) throws RocksDBException {
        return db.get(windowKey.getBytes());
    }

    public void close() {
        if (db != null) db.close();
        if (options != null) options.close();
    }
}

```

---

## 7. Configuration Schema & Engine Bootstrapping

Pravaha is completely declarative. A developer configures inputs, outputs, and SQL definitions via YAML.

### 7.1 Declarative Configuration (`pravaha-config.yaml`)

```yaml
pravaha:
  version: "1.0"
  instance_id: "pravaha-node-01"

  engine:
    query: >
      SELECT STREAM 
        TUMBLE_END(rowtime, INTERVAL '10' SECOND) as window_end,
        user_id, 
        COUNT(*) as transaction_count,
        SUM(amount) as total_volume
      FROM aerospike_cdc_stream
      WHERE status = 'COMPLETED'
      GROUP BY TUMBLE(rowtime, INTERVAL '10' SECOND), user_id

  source:
    plugin_class: "com.pravaha.plugin.aerospike.AerospikeCdcSourcePlugin"
    properties:
      hosts: "127.0.0.1:3000"
      namespace: "financial"
      set: "transactions"
      cdc_http_port: 8080

  sink:
    plugin_class: "com.pravaha.plugin.redis.RedisSinkPlugin"
    properties:
      redis_uri: "redis://localhost:6379"
      key_prefix: "agg:users:"

  state_backend:
    type: "ROCKSDB"
    path: "/var/lib/pravaha/state"

```

---

## 8. Polyglot Client Integration (Python Example)

Clients in non-Java ecosystems submit queries or listen to Pravaha's continuous output via Avatica's protocol.

```python
import phoenixdb
import phoenixdb.cursor
import time

def run_pravaha_continuous_client():
    # Connect to Pravaha Avatica Gateway
    avatica_url = "http://localhost:8765/"
    conn = phoenixdb.connect(avatica_url, autocommit=True)
    
    cursor = conn.cursor()
    
    print("Executing Continuous Query on Pravaha Engine...")
    cursor.execute("""
        SELECT STREAM user_id, transaction_count, total_volume 
        FROM pravaha_output_stream
    """)
    
    # Process continuous stream push
    while True:
        row = cursor.fetchone()
        if row:
            user_id, count, volume = row[0], row[1], row[2]
            print(f"[STREAM EVENT] User: {user_id} | 10s Window Count: {count} | Total Vol: ${volume:.2f}")
        else:
            time.sleep(0.01)

if __name__ == "__main__":
    run_pravaha_continuous_client()

```

---

## 9. Technology Stack Summary

| Subsystem | Selected Technology | Rationale |
| --- | --- | --- |
| **Parsing & Optimization** | Apache Calcite | Enterprise SQL parser, CBO, and native `StreamableTable` support. |
| **RPC & Client Protocol** | Apache Calcite Avatica | Native HTTP/Protobuf protocol with ready clients (Python, Go, JS). |
| **Local State Management** | Embedded RocksDB | Ultra-fast off-heap key-value storage for sliding window state. |
| **Primary Persistence** | Aerospike / Cassandra | High-throughput, low-latency key-value & wide-column storage backends. |
| **Inter-Thread Messaging** | JCTools / `BlockingQueue` | Lock-free / low-latency bounded queue for CDC intake backpressure. |

---
