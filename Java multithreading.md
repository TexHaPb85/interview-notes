# Java multithreading

## Thread-safe collections

Java gives you three generations of thread-safe collections:

1. **The legacy ones (`Vector`, `Hashtable`) — blocking.** They put `synchronized` on
   every method. Fine for correctness, terrible for speed. Every single method has the
   `synchronized` keyword on it, and the lock object is `this` — the collection itself.
2. **`Collections.synchronizedList/Map/Set` — blocking.** Performance is the same, as it
   just wraps a collection in a `synchronized` block; but it is more comfortable to wrap
   any collection than to use the legacy `Vector` or `Hashtable`.
3. **`java.util.concurrent` (`ConcurrentHashMap`, `CopyOnWriteArrayList`,
   `ConcurrentLinkedQueue`) — non-blocking approach, CAS algorithm.**

### ConcurrentHashMap

Instead of one lock for the whole map, it locks only the individual **bucket** being
written to. 16 threads writing to 16 different buckets proceed fully in parallel. Reads
don't lock at all — they use `volatile` reads and CAS.

### CopyOnWriteArrayList

Writers make a full copy of the array, so readers always work on a stable snapshot and
never need to lock at all. It is quite similar to **repeatable read** isolation level in
PostgreSQL.

### ConcurrentLinkedQueue

Uses CAS (compare-and-set — a single atomic CPU instruction) with no locks whatsoever.
An unbounded thread-safe queue based on linked nodes.

---

## CAS (Compare-And-Swap)

CAS is how `java.util.concurrent` changes a value **without a lock**. It works with three
things: the variable, the **expected** old value, and the **new** value. In one atomic CPU
step: *"if the value is still the expected one, set it to the new one; otherwise fail."*
On failure the thread re-reads the current value and retries.

```text
do {
    old = x;           // read current value
    new = old + 1;     // build the new value
} while ( CAS(x, old, new) == FAIL );   // retry until our CAS wins
```

No thread is blocked: the loser just loops once more with a fresh value.

### Optimistic vs pessimistic locking

|            | Pessimistic (locks)              | Optimistic (CAS)                  |
|------------|----------------------------------|-----------------------------------|
| Assumption | conflict is likely               | conflict is rare                  |
| Strategy   | block others first, then work    | just try; retry if someone won    |
| Main cost  | **time** — threads wait for lock | **CPU** — losers spin and retry   |
| Threads    | one works, the rest are blocked  | all run, only losers redo work    |
| Best when  | high contention / long critical sections | low contention / short updates |

> A lock is **pessimistic**: *"I expect a conflict, so I block everyone first."*
> CAS is **optimistic**: *"I expect no conflict. I just try. If I was wrong, I retry."*

CAS is **one way** to implement optimistic locking (the lock-free, hardware way). Other
optimistic methods use version numbers or timestamps (e.g. JPA `@Version`, MVCC checks).

- **Pessimistic** costs **time**: a thread waits until the resource is free.
- **Optimistic** costs **CPU**: a thread keeps retrying with fresh values until it succeeds.
- Trade-off: under heavy contention optimistic can waste a lot of CPU on retries, so
  pessimistic locking can actually be faster there. Neither is always better.

---

## Do all java.util.concurrent collections use optimistic locking?

No. It is a mix of three styles.

| Collection                                    | How it stays thread-safe                                              | Lock-free?       |
|-----------------------------------------------|-----------------------------------------------------------------------|------------------|
| `ConcurrentLinkedQueue` / `ConcurrentLinkedDeque` | pure CAS on the head/tail nodes                                   | yes              |
| `ConcurrentSkipListMap` / `ConcurrentSkipListSet` | CAS on skip-list nodes                                            | yes              |
| `ConcurrentHashMap`                           | CAS to add the first node in an empty bin; `synchronized` on the bin during a collision; reads never lock | hybrid |
| `CopyOnWriteArrayList` / `CopyOnWriteArraySet`| writers take a `ReentrantLock` and copy the array; readers never lock | no (writers lock)|
| `ArrayBlockingQueue` / `LinkedBlockingQueue`  | `ReentrantLock` + conditions — blocking by design                     | no               |

So: queues and skip-lists are optimistic/lock-free; the `CopyOnWrite` and `Blocking`
collections use real locks; `ConcurrentHashMap` sits in the middle.

---

## java.util.concurrent vs PostgreSQL isolation levels

This is an analogy for intuition, not an exact match. DB isolation is about multi-row
transactions; collections are about single operations.

| Java collection / pattern            | Behaviour                                                       | Closest PG concept              |
|--------------------------------------|-----------------------------------------------------------------|---------------------------------|
| `CopyOnWriteArrayList` iterator      | reads a frozen snapshot taken when the iterator was made; later writes are invisible | Repeatable Read / snapshot (MVCC) |
| `ConcurrentHashMap` reads            | each read sees the latest committed value; no snapshot across the whole map | Read Committed                  |
| `synchronized` collection / `BlockingQueue` | one thread at a time, fully serialized                   | Serializable (via locking)      |
| CAS retry loop on conflict           | conflict detected → redo with fresh value                       | Serializable SSI → `40001` → retry |

- A `CopyOnWriteArrayList` iterator never throws `ConcurrentModificationException`, the
  same way a PG snapshot never sees concurrent writes.
- `ConcurrentHashMap` has no map-wide snapshot: reading key A and then key B can see two
  different points in time — like Read Committed, where each statement gets a fresh snapshot.
- The CAS retry loop mirrors the Serializable failure-and-retry pattern (`40001`).

---

## How do atomics work under the hood? What makes the non-blocking approach possible?

Atomic classes (`AtomicInteger`, `AtomicLong`, `AtomicReference`) hold a `volatile` field
and update it with CAS. Two guarantees combine:

- **`volatile` → visibility.** Every thread reads the newest value, not a cached copy.
- **CAS → atomicity.** The read-compare-write happens as one indivisible step.

The atomic step is a real CPU instruction:

- x86: `LOCK CMPXCHG`
- ARM: `LDREX` / `STREX` (load-linked / store-conditional)

The CPU locks the cache line for that one instruction, so no other core can change the
value in the middle. Java reaches these instructions through `VarHandle` (old code used
`sun.misc.Unsafe`).

**Why this is non-blocking:** no thread holds a lock or sleeps. A thread that loses the
CAS does not wait — it just retries. So one slow or paused thread cannot freeze the others
(no deadlock, no priority inversion).

```java
AtomicInteger counter = new AtomicInteger(0);
counter.incrementAndGet();   // internally: read, +1, CAS, retry if the CAS failed
```

---

## Deadlock and livelock

Both mean "threads make no progress", but they look different.

- **Deadlock** — threads are **blocked**. Each holds a lock and waits forever for a lock
  the other holds. CPU is idle (the threads sleep).
- **Livelock** — threads are **not blocked**. They keep running and reacting to each other
  but never finish. CPU is busy (often near 100%), yet no real work is done. Like two
  people in a corridor who keep stepping to the same side to let the other pass.

### Deadlock example

```java
// Thread 1: lock A, then B      Thread 2: lock B, then A
synchronized (A) {               synchronized (B) {
    synchronized (B) { ... }         synchronized (A) { ... }
}                                }
```

Thread 1 holds A and waits for B. Thread 2 holds B and waits for A. Neither releases. Stuck.

### Livelock example

Two threads both use `tryLock` and "back off" (release and retry) when they see the other
holds a lock. Under load they keep releasing and retrying at the same time, so neither ever
gets both locks. Also happens as a **CAS retry storm**: many threads spin on the same value
and almost all CAS calls fail.

### How to find them on a real project

**Deadlock (easy to detect):**
- Take a thread dump: `jstack <pid>` (or `kill -3 <pid>`). The JVM prints
  *"Found one Java-level deadlock"* and shows which thread waits on which lock.
- `jconsole` / VisualVM have a **Detect Deadlock** button.
- In code: `ThreadMXBean.findDeadlockedThreads()`.
- Symptom in prod: requests hang, threads stuck in state `BLOCKED`, CPU low.

**Livelock (harder):**
- No deadlock is reported, but the app still hangs. Threads are in state `RUNNABLE`.
- Take **several** thread dumps a few seconds apart — the same threads keep spinning in the
  same retry code, but nothing advances.
- Symptom in prod: **high CPU** with no throughput; retry counters / log lines repeat fast.

### How to avoid them

- **Deadlock:** always take locks in the **same global order**; use `tryLock(timeout)`
  instead of waiting forever; keep critical sections small.
- **Livelock:** add **random back-off** before retrying and **cap the retries**, so threads
  stop colliding in lock-step.

---

## Thread pools (Executors)

A thread pool reuses a set of threads to run many tasks, so you don't create a new thread
per task. Most are built from the `Executors` factory or the `ThreadPoolExecutor` class.

| Pool                              | Created with                                  | What it does                                                                 |
|-----------------------------------|-----------------------------------------------|------------------------------------------------------------------------------|
| **FixedThreadPool**               | `Executors.newFixedThreadPool(n)`             | Exactly `n` threads, unbounded queue. Steady, predictable load.              |
| **CachedThreadPool**              | `Executors.newCachedThreadPool()`             | Makes threads on demand, reuses idle ones, drops them after 60s. Threads are unbounded — risky under a flood. |
| **SingleThreadExecutor**          | `Executors.newSingleThreadExecutor()`         | One thread; tasks run one by one in order.                                   |
| **ScheduledThreadPool**           | `Executors.newScheduledThreadPool(n)`         | Runs tasks after a delay or on a repeating schedule (cron-like).             |
| **WorkStealingPool / ForkJoinPool** | `Executors.newWorkStealingPool()` / `ForkJoinPool` | Each thread has its own task deque and **steals** work from busy threads. Splits big tasks into small ones (fork/join). Best for CPU-bound divide-and-conquer. Backs parallel streams. |
| **VirtualThreadPerTaskExecutor** (Java 21) | `Executors.newVirtualThreadPerTaskExecutor()` | One cheap **virtual thread** per task. Great for many IO-bound tasks.        |

`ThreadPoolExecutor` is the real engine behind `Fixed`, `Cached`, and `Single`. In
production, build it directly so you control the key knobs:

- **corePoolSize / maxPoolSize** — min and max threads.
- **workQueue** — where waiting tasks sit (use a **bounded** queue to avoid OOM).
- **keepAliveTime** — how long extra threads stay idle before they stop.
- **RejectedExecutionHandler** — what to do when the queue is full (e.g. `CallerRunsPolicy`).

> Tip: the `Executors.newFixedThreadPool` default uses an **unbounded** queue, so tasks can
> pile up until the app runs out of memory. A bounded `ThreadPoolExecutor` is safer.
