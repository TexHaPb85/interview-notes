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

## Open questions / TODO

- What is CAS, and how does it work?
- Do all `java.util.concurrent` collections use optimistic locking?
- Deeper comparison between `java.util.concurrent` collections and PostgreSQL isolation levels.
- How do atomics work under the hood? What makes the non-blocking approach possible?
