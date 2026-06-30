# Java Memory Model & JVM Tuning

How the JVM stores objects, how to size and inspect memory, how garbage collectors work,
and how to find a deadlock in a running app. These are very common senior-Java questions.

---

## JVM memory areas

| Area          | Shared?        | What lives here                                  | Typical error          |
|---------------|----------------|--------------------------------------------------|------------------------|
| **Heap**      | all threads    | all objects (`new ...`), arrays                  | `OutOfMemoryError`     |
| **Stack**     | one per thread | method frames: local variables, references       | `StackOverflowError`   |
| **Metaspace** | all threads    | class metadata (native memory, replaced PermGen) | `OutOfMemoryError: Metaspace` |
| PC register   | one per thread | address of the current instruction               | —                      |

The **heap** is split into generations (this is where GC works):

```
Heap
├── Young generation
│   ├── Eden        (new objects are created here)
│   ├── Survivor S0
│   └── Survivor S1
└── Old generation  (objects that lived long enough get "promoted" here)
```

> Most objects die young (short-lived). GC cleans the young generation often and fast
> (**minor GC**), and the old generation rarely (**major / full GC**, slower).

---

## `-Xms` and `-Xmx` (and friends)

These JVM flags control memory sizes at startup.

| Flag                       | Meaning                                  |
|----------------------------|------------------------------------------|
| `-Xms`                     | **initial** heap size                    |
| `-Xmx`                     | **maximum** heap size                    |
| `-Xss`                     | thread **stack** size                    |
| `-XX:MaxMetaspaceSize`     | max metaspace size                       |

```bash
java -Xms512m -Xmx2g -jar app.jar
```

> Tip: in production it is common to set `-Xms` **equal to** `-Xmx` (e.g. both `2g`). This
> avoids the JVM constantly resizing the heap and gives more stable performance.

---

## Heap snapshot (heap dump)

A **heap dump** is a snapshot of **all objects in the heap** at one moment. It's the main
tool to find **memory leaks** (why memory keeps growing / why you got `OutOfMemoryError`).

**How to create one:**

```bash
# by process id (find it with: jps  or  jcmd)
jmap -dump:live,format=b,file=heap.hprof <pid>
# or
jcmd <pid> GC.heap_dump /path/heap.hprof

# automatically when the app crashes with OOM:
java -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps -jar app.jar
```

**How to read it:** open the `.hprof` file in **Eclipse MAT** (Memory Analyzer) or
**VisualVM**. Look at the **dominator tree** / "leak suspects" to see which objects hold the
most memory and what keeps them alive.

---

## Profiler

A **profiler** measures what the app does **while it runs** — CPU hot spots, memory
allocations, thread activity, lock contention. You use it to find *why* the app is slow or
uses too much memory.

| Tool                                | Notes                                          |
|-------------------------------------|------------------------------------------------|
| **VisualVM**                        | free, CPU + memory + threads, easy to start    |
| **Java Flight Recorder (JFR)** + JDK Mission Control | built into the JDK, very low overhead |
| **async-profiler**                  | great CPU/allocation flame graphs              |
| **JProfiler / YourKit**             | powerful, commercial                           |

> Heap dump = a **photo** of memory at one instant. Profiler = a **video** of what happens
> over time.

---

## Garbage collectors (and why it matters)

The **garbage collector (GC)** automatically frees memory of objects that are no longer
reachable, so you don't `free()` manually. Different GCs make different trade-offs between
**throughput** (total work done) and **pause time** (how long the app freezes during GC).

| GC               | Flag                    | Best for                                  |
|------------------|-------------------------|-------------------------------------------|
| **Serial**       | `-XX:+UseSerialGC`      | tiny apps, single core                    |
| **Parallel**     | `-XX:+UseParallelGC`    | batch jobs — max **throughput**, longer pauses |
| **G1** (default) | `-XX:+UseG1GC`          | general use — balanced, predictable pauses |
| **ZGC**          | `-XX:+UseZGC`           | huge heaps, **very low pause** (~ms)      |
| **Shenandoah**   | `-XX:+UseShenandoahGC`  | low pause, similar goal to ZGC            |

> **Why know this for interviews:** the GC choice changes app behavior. A low-latency
> service (API, trading) cares about **short pauses** → G1 or ZGC. A nightly batch job
> cares about **total speed** → Parallel GC. G1 is the **default since Java 9**; CMS was
> removed in Java 14.

---

## How to find a deadlock in a running Spring app

A **deadlock** = two or more threads each holding a lock the other needs, so they wait
forever and that part of the app **hangs** (requests stop responding).

**Steps:**

1. Find the process id:
   ```bash
   jps        # lists Java processes + pids
   ```
2. Take a **thread dump** — the JVM itself detects deadlocks and prints them:
   ```bash
   jstack <pid>
   # or
   jcmd <pid> Thread.print
   ```
3. Look for this in the output:
   ```
   Found one Java-level deadlock:
   =============================
   "thread-A" is waiting to lock <0x...> which is held by "thread-B"
   "thread-B" is waiting to lock <0x...> which is held by "thread-A"
   ```
   Threads stuck in state **`BLOCKED`** waiting on a monitor are the suspects.

**Easier with a UI:** **VisualVM** or **JConsole** connect to the running JVM and have a
**"Detect Deadlock"** button. Online tools like fastthread.io can analyze a thread dump too.

> **Prevention:** always acquire multiple locks in the **same order**, prefer
> `Lock.tryLock(timeout)` over blocking forever, and keep critical sections small.
> See [Java multithreading](Java%20multithreading.md) for locks and synchronization.
