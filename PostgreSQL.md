# PostgreSQL in 1 hour

PostgreSQL is a relational DB that stores table data in **heap files** divided into 8KB pages.

- Each table → one or more heap files (segments of 1GB each by default).
- Heap files are divided into **8KB heap pages** (blocks).
- Pages contain many **tuples**.
- A tuple = one physical version of a row (entry in a table).
- A tuple = row data at a moment in time + headers needed for **MVCC** (Multi-Version Concurrency Control).

## Tuple header fields

| Field   | Purpose                                                          |
|---------|-----------------------------------------------------------------|
| `xmin`  | Transaction ID that **inserted/created** this tuple             |
| `xmax`  | Transaction ID that **deleted/updated** this tuple (0 if live)  |
| `ctid`  | Physical location: `(page_number, tuple_index)` e.g. `(0,1)`    |

```text
Page 0:
  Tuple(xmin=100, xmax=0,   data="Alice, 25")   ← live
  Tuple(xmin=101, xmax=105, data="Bob, 30")     ← dead (xmax set = deleted/updated)
  Tuple(xmin=105, xmax=0,   data="Bob, 31")     ← live (new version after UPDATE)
```

### Is there DML without a transaction?

No. There is **no** "transactionless" DML in PostgreSQL. Every statement, with or without explicit `BEGIN`, runs inside a transaction.

```sql
-- You think this has no transaction:
UPDATE users SET name = 'John' WHERE id = 1;

-- PostgreSQL sees this:
BEGIN;                                      -- implicit, automatic
  UPDATE users SET name = 'John' WHERE id = 1;
COMMIT;                                     -- implicit, automatic
```

This is **autocommit mode** — each statement is silently wrapped in its own transaction.

### Why tuples (MVCC)

- Other transactions that started before your `UPDATE` must still see the OLD value.
- PostgreSQL cannot overwrite in place — that would break snapshot isolation.
- Old versions stay on disk until no active transaction can see them anymore.

## VACUUM — cleanup of dead tuples

Cleanup of dead tuples is done by **VACUUM** (manual or autovacuum).

Autovacuum monitors `n_dead_tup` in `pg_stat_user_tables`. When dead tuples exceed
`threshold + scale_factor × table_size` (default: `50 + 20%` of rows), a worker wakes up,
scans the heap, removes index entries for dead tuples, and marks their heap slots free in
the **Free Space Map**.

```sql
CREATE TABLE client (
    id bigserial NOT NULL,
    -- ...
    CONSTRAINT "clientPK" PRIMARY KEY (id)
)
WITH (
    autovacuum_enabled = on
);
```

Manual run:

```sql
VACUUM users;            -- clean dead tuples, update FSM/VM
VACUUM VERBOSE users;    -- same + detailed output
VACUUM ANALYZE users;    -- clean + update planner statistics
VACUUM FULL users;       -- full rewrite, reclaim disk space (LOCK!)
```

## DML under the hood

### INSERT

1. Find a page with enough free space (via Free Space Map).
2. Write new tuple with: `t_xmin = current tx ID`, `t_xmax = 0`, `t_ctid = self`.
3. Write to **WAL** (Write-Ahead Log) first.
4. Modify the page in `shared_buffers` (dirty page).
5. Background writer / checkpoint eventually flushes to disk.

### UPDATE = logical DELETE of old tuple + INSERT of new tuple

PostgreSQL does **not** modify the existing tuple in place.

```text
  OLD tuple:                      NEW tuple:
┌──────────────────┐            ┌──────────────────┐
│ t_xmin = 100     │            │ t_xmin = 200     │ ← new value
│ t_xmax = 200     │ ← deleted  │ t_xmax = 0       │
│ t_ctid = self    │──────────► │ t_ctid = self    │
└──────────────────┘            └──────────────────┘
```

MVCC effect:
- Readers of old snapshots still see the old version.
- New readers see the new version.
- No locks between readers and writers.

### DELETE

1. Find the tuple.
2. Set `t_xmax = current tx ID`.
3. The tuple is **not** physically removed.
4. It becomes a "dead tuple" after the transaction commits.
5. VACUUM later reclaims the space.

## SELECT path

```text
Query → Parser → Rewriter → Planner/Optimizer → Executor
                                  ↓
                          Seq Scan / Index Scan
                                  ↓
                        Buffer Cache (shared_buffers)
                        ↙                         ↘
                  Cache HIT                   Cache MISS → read from disk
                  return tuple                load 8KB page into cache → return
```

Visibility check on every tuple:
- `t_xmin` committed AND `t_xmax` not committed (or 0) → visible.
- Uses the **Visibility Map** to skip pages where all tuples are known visible.

The planner does not know "what's fast" — it uses a cost model:

```text
Cost = (pages to read) × page_cost + (rows to process) × cpu_cost
```

It generates multiple candidate plans and picks the lowest estimated cost.

## Indexes

An index is an extra data structure for quick row selection. It **slows down inserts**
because each indexed value must find its place in that structure.

**B-tree ≠ Binary tree.** Used automatically for primary keys, but **NOT for foreign keys** —
you must add it manually. An unindexed FK column causes a sequential scan on joins and on
delete (to check that no FK points to the deleted row; if one does → cancel delete + error).

### B-tree (actually a B+ tree)

- Internal nodes — keys only, used for navigation.
- Leaf nodes — keys + `ctid` (pointer to heap tuple).
- Leaf nodes are linked as a doubly-linked list → fast range scans.

### Hash index (≈ Java HashMap)

```text
hash(value) → bucket → [ctid1, ctid2, ...]

WHERE email = 'john@gmail.com'
  → hash('john@gmail.com') = 0x4A3F
  → jump to bucket 0x4A3F
  → get ctid → fetch tuple
```

`O(1)` lookup — but **only for equality (`=`)**.

History: in PostgreSQL < 10, hash indexes were **not WAL-logged** (so they were unsafe).

When to use hash today: very long string, equality-only, high cardinality.

```sql
CREATE INDEX idx_hash_token ON sessions USING hash(token);
-- token is a 256-char UUID-like string
-- B-tree would store and compare the full string at each node
-- Hash: one hash computation → bucket → done
-- Also good: no range queries will EVER happen on this column
```

### GIN — Generalized Inverted Index

Unlike a B-tree that stores a single value per row, a GIN index stores a map of keys and
their **posting lists** (the row IDs where each key occurs). Good for arrays, full-text, JSONB.

Example — a `products` table with a `jsonb` column:

```sql
CREATE INDEX idx_attrs ON products USING gin (attrs);
```

```text
  id=1   {"color": "red",  "size": "M"}
  id=2   {"color": "blue", "size": "M"}
  id=3   {"color": "red",  "size": "L"}
```

GIN stores the **inverse**: every key and value points to the rows that contain it.

```text
  entry      rows
  ───────    ───────
  "color"    1, 2, 3
  "size"     1, 2, 3
  "red"      1, 3
  "blue"     2
  "M"        1, 2
  "L"        3
```

A containment query then just intersects the matching lists — the table is never scanned:

```sql
SELECT * FROM products WHERE attrs @> '{"color": "red"}';
```

```text
  "color"  → 1, 2, 3
  "red"    → 1, 3
  ───────────────── ∩
  result   → 1, 3      ← only these rows are fetched
```

Cost: large index and heavy writes — one row with N keys/values = N index entries.

## Case study: "App is slow, we suspect the DB"

Symptoms: app was faster 2 weeks ago, slows down as rows grow.

### Phase 0 — Setup extension (do once)

```sql
-- Step 1: enable pg_stat_statements (tracks query performance)
CREATE EXTENSION IF NOT EXISTS pg_stat_statements SCHEMA public;

-- Step 2: verify it works
SELECT count(*) FROM pg_stat_statements;
-- If 0 → just installed, run some queries first, come back later
-- If "must be loaded via shared_preload_libraries" → needs server config (ask DBA)

-- Step 3: reset stats to get a clean baseline
SELECT pg_stat_statements_reset();

-- Step 4: let the app run for a while (minutes/hours), then continue
```

### Phase 1 — Find the worst queries

```sql
-- Top 20 by TOTAL time consumed (biggest impact on the system)
SELECT
    left(query, 120)                    AS query_snippet,
    calls,
    round(mean_exec_time::numeric, 2)   AS avg_ms,
    round(total_exec_time::numeric, 2)  AS total_ms,
    round(stddev_exec_time::numeric, 2) AS stddev_ms,
    rows / calls                        AS avg_rows_returned
FROM pg_stat_statements
WHERE calls > 5                          -- ignore one-off queries
ORDER BY total_exec_time DESC
LIMIT 20;

-- Top 20 by AVERAGE time (consistently slow single calls)
SELECT
    left(query, 120)                    AS query_snippet,
    calls,
    round(mean_exec_time::numeric, 2)   AS avg_ms,
    round(total_exec_time::numeric, 2)  AS total_ms
FROM pg_stat_statements
WHERE calls > 5
ORDER BY mean_exec_time DESC
LIMIT 20;
```

After finding a bad query, run `EXPLAIN (ANALYZE, BUFFERS)` on it.

### EXPLAIN vs EXPLAIN ANALYZE

```text
EXPLAIN         → only generates and shows the plan, ZERO execution.
                  No rows read, no rows modified, no locks. Safe on production.

EXPLAIN ANALYZE → actually executes (reads + writes), then shows the plan.
                  Your UPDATE would modify real data!
```

Example plan:

```text
Gather  (cost=1004.68..6605.05 rows=80 width=8)
  Workers Planned: 2
  ->  Hash Join  (cost=4.68..5597.05 rows=33 width=8)
        Hash Cond: (ptd.temporal_data = td.id)
        ->  Nested Loop  (cost=2.27..5589.24 rows=1669 width=16)
              ->  Hash Join  (cost=1.85..5236.38 rows=260 width=8)
                    Hash Cond: (p.scheme_id = s.id)
                    ->  Nested Loop  (cost=0.29..5231.70 rows=573 width=16)
                          ->  Parallel Seq Scan on client c  (cost=0.00..4512.83 rows=220 width=8)
                                Filter: ((country_code)::text = 'CA'::text)
                          ->  Index Scan using program_client_idx on program p  (cost=0.29..3.18 rows=3 width=24)
                                Index Cond: (client_id = c.id)
                    ->  Hash  (cost=1.40..1.40 rows=5 width=8)
                          ->  Seq Scan on scheme s  (cost=0.00..1.40 rows=5 width=8)
              ->  Index Scan using program_temporal_data_..._idx on program_temporal_data ptd
        ->  Hash  (cost=2.38..2.38 rows=1 width=8)
              ->  Index Scan using temporal_data_name_idx on temporal_data td
```

How to read a node:

```text
cost=3.76..3.79
      ↑      ↑
   startup  total

Startup cost: work needed BEFORE the first row can be returned.
Total cost:   work needed to return ALL rows.
Unit: not milliseconds — an abstract "cost unit" (seq_page_cost = 1.0 is the baseline).
```

Why two numbers matter:

```text
Seq Scan   (cost=0.00..6500)    startup=0     → can stream rows immediately
Sort       (cost=8500..9200)    startup=8500  → must read ALL rows before first output
Aggregate  (cost=3.76..3.79)    startup≈total → must process all input before output

-- For LIMIT queries the planner prefers low startup cost
-- For full scans the planner prefers low total cost
```

`rows=N` — estimated number of rows this node will **output** (not scan):

```text
table has 1,000,000 rows
WHERE country_code = 'CA'
stats say 5% are CA
→ rows = 50,000 estimate
```

This is the most important number to verify — when actual rows ≠ estimated, the whole plan can be wrong.

`width=N` — average size in **bytes** of one output row from this node:

```text
width=8     → probably just an integer/bigint column (id only)
width=32    → a few integer/text columns
width=164   → wide row (e.g. hstore value_map)
width=1000+ → lots of text/json data
```

Used to estimate memory; affects whether a sort/hash fits in `work_mem` or spills to disk.

## Transactions & isolation levels

Running transactions in parallel can produce four classic **anomalies**. Each isolation
level forbids more of them — buying correctness at the cost of concurrency.

### 1. Dirty read — *reads uncommitted data*

One transaction sees changes another has not committed yet (and may roll back).

```text
 tx1                              tx2
 ───────────────────────────     ──────────────────────────────
 UPDATE accounts
   SET balance = 50 WHERE id=1;
                                  SELECT balance WHERE id=1;
                                    → reads 50   (NOT committed!)
 ROLLBACK;                        → acted on 50, which never existed
```

PostgreSQL **never** allows this — even its weakest level reads only committed data.

### 2. Non-repeatable read — *the same row changes between two reads*

A row read twice in one transaction returns different values, because another transaction
updated and committed it in between.

```text
 tx1                              tx2
 ───────────────────────────     ──────────────────────────────
 SELECT balance WHERE id=1;
   → 100
                                  UPDATE accounts
                                    SET balance = 50 WHERE id=1;
                                  COMMIT;
 SELECT balance WHERE id=1;
   → 50   (same row, new value!)
```

### 3. Phantom read — *the same query returns a different set of rows*

A condition is re-evaluated and **new rows that match it have appeared** (or vanished).

```text
 tx1                              tx2
 ───────────────────────────     ──────────────────────────────
 SELECT count(*)
   WHERE user=1;   → 1
                                  INSERT INTO accounts(user, …)
                                    VALUES (1, …);
                                  COMMIT;
 SELECT count(*)
   WHERE user=1;   → 2   (a "phantom" row appeared)
```

### 4. Serialization anomaly — *result impossible in any serial order*

Each transaction is individually correct, but their combination is not equal to running
them one-after-another in *either* order. Classic **write skew** — two on-call doctors:

```text
 tx1                              tx2
 ───────────────────────────     ──────────────────────────────
 SELECT count(*) WHERE            SELECT count(*) WHERE
   on_call=true;   → 2              on_call=true;   → 2
 -- both see 2 on call, so each thinks it is safe to leave
 UPDATE doctors SET on_call=false
   WHERE name='Alice';
                                  UPDATE doctors SET on_call=false
                                    WHERE name='Bob';
 COMMIT;                          COMMIT;
 -- result: 0 doctors on call — neither serial order could produce this
```

### Isolation levels

| Level             | Behavior                                                                                     |
|-------------------|----------------------------------------------------------------------------------------------|
| Read Uncommitted  | Reads uncommitted changes from parallel transactions. In PostgreSQL it is silently upgraded to Read Committed. |
| Read Committed    | Reads only committed data. **Default** in PostgreSQL. A new snapshot is taken per statement.  |
| Repeatable Read   | Snapshot taken once at the START of the first statement, held for the whole transaction.      |
| Serializable      | Postgres tracks read/write dependencies; on a conflict/anomaly it aborts one transaction with an error (you must retry it). |

### Which level prevents which anomaly

| Anomaly                | Read Committed | Repeatable Read | Serializable |
|------------------------|----------------|-----------------|--------------|
| Dirty read             | prevented      | prevented       | prevented    |
| Non-repeatable read    | possible       | prevented       | prevented    |
| Phantom read           | possible       | prevented \*    | prevented    |
| Serialization anomaly  | possible       | possible        | prevented    |

\* Per the SQL standard, Repeatable Read still permits phantoms. PostgreSQL's Repeatable
Read is implemented as **snapshot isolation**, so it blocks phantoms too — stronger than
the standard requires.

### PostgreSQL specifics

- **Read Uncommitted = Read Committed** — dirty reads can never happen in PostgreSQL.
- **Repeatable Read = snapshot isolation** — the whole transaction sees one frozen snapshot;
  a concurrent write to a row you update raises `could not serialize access` (retry).
- **Serializable = SSI** (Serializable Snapshot Isolation) — adds dependency tracking on top
  of snapshots to catch write skew. On conflict it aborts with SQLSTATE `40001`
  (`serialization_failure`) — the application must **catch and retry** the transaction.
- Stricter level → fewer anomalies but more aborts/retries and less concurrency.

```sql
BEGIN ISOLATION LEVEL REPEATABLE READ;
-- ...
COMMIT;

-- or for a single transaction already open:
SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;
```

Retry pattern for Serializable / Repeatable Read (handle `40001`):

```text
loop:
  BEGIN ISOLATION LEVEL SERIALIZABLE
  run statements
  try COMMIT
    success → done
    error 40001 (serialization_failure) → ROLLBACK, retry from top
```

## Partitioning

Splitting one big table into smaller physical tables (**partitions**) by a **partition key**,
while queries still see one logical table.

```sql
CREATE TABLE events (id bigint, created_at date, ...) PARTITION BY RANGE (created_at);

CREATE TABLE events_2024_01 PARTITION OF events
    FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');
CREATE TABLE events_2024_02 PARTITION OF events
    FOR VALUES FROM ('2024-02-01') TO ('2024-03-01');
```

Strategies: `RANGE` (dates/numbers), `LIST` (discrete values e.g. country), `HASH` (even spread).

### Partitioning vs indexing

For a **point lookup** partitioning buys almost nothing — a B-tree is logarithmic, so doubling
the rows adds only ~1 level. Partitioning is **not** a lookup-speed tool; it solves different
problems:

1. **Partition pruning** — if the query filters on the *partition key*, Postgres skips whole
   partitions without touching their indexes at all. A free coarse level on top of your B-tree.
2. **Instant data lifecycle** — `DROP`/`DETACH PARTITION` removes a month of data in
   milliseconds. The alternative, `DELETE ... WHERE date < ...`, makes millions of dead tuples
   → bloat → heavy VACUUM. This is the #1 real-world reason to partition (logs, time-series).
3. **Smaller indexes that fit in RAM** — one B-tree over 1B rows may not fit in `shared_buffers`;
   per-partition indexes do. Inserts hit fewer, hotter pages.
4. **Cheaper maintenance** — `VACUUM` / `ANALYZE` / `REINDEX` run per-partition, in parallel.

**Trade-off:** a query that does *not* filter on the partition key gets **slower** — Postgres
must probe every partition's index.

> **Indexing speeds up finding rows; partitioning speeds up managing and dropping rows.**
> They are complementary, not alternatives — you still index each partition.

## Scaling a production database

### Vertical scaling (scale up) — bigger machine

Add CPU / RAM / faster disk to the single server.

- **Pro:** simplest — no app changes, no consistency problems, one source of truth.
- **Con:** hard ceiling (biggest instance you can buy), cost grows non-linearly, single point
  of failure, downtime to resize.
- **Do this first.** For most apps a well-tuned single Postgres goes very far.

### Horizontal scaling (scale out) — more machines

Spread load across multiple servers. Two distinct flavours:

**1. Read replicas (replication).** Primary handles writes; streaming replicas serve reads.

```text
        writes        ┌─────────┐   WAL    ┌──────────┐  reads
  app ───────────────►│ PRIMARY │ ───────► │ REPLICA  │◄──────── app
                      └─────────┘          └──────────┘
```

- Scales **reads** and gives high availability (promote a replica on failure).
- Does **not** scale writes — every write still goes through one primary.
- Replicas are slightly behind → **replication lag** (eventual consistency on reads).

**2. Sharding.** Split the data itself across nodes by a **shard key** (e.g. `user_id`); each
node owns a slice.

- The only way to scale **writes** beyond one machine.
- Costly: cross-shard joins/transactions are hard, rebalancing is painful, choosing a good
  shard key is critical. Native Postgres has no built-in sharding — needs Citus, Vitess-style
  tooling, or app-level routing.

### Rule of thumb

```text
slow queries?      → index / tune first
single box maxed?  → scale UP (vertical)
read-heavy?        → add read replicas
write-heavy / too big for one box? → shard (last resort)
```

Partitioning (above) is **not** scaling — it's still one server. But it's often the first step
that makes later sharding easier.
