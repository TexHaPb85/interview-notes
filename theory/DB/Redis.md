# Redis

## Quick links

- [What is Redis?](#what-is-redis)
- [Data types](#data-types)
- [Persistence: RDB vs AOF](#persistence-rdb-vs-aof)
- [Eviction policies](#eviction-policies)
- [High availability](#high-availability)
- [Common use cases](#common-use-cases)
- [Redis with Java and Spring Boot](#redis-with-java-and-spring-boot)
- [Most popular interview questions](#most-popular-interview-questions)

---

## What is Redis?

**Redis** (REmote DIctionary Server) is an **in-memory key-value data store**. Because
data lives in RAM, reads and writes are extremely fast — usually sub-millisecond.

- It's more than a plain cache — it can also work as a lightweight primary database
  (with persistence turned on), a message broker (pub/sub, streams), and a session store.
- Redis is mostly **single-threaded** for command execution — one core runs your
  commands one at a time, no locking needed between them. Since Redis 6, networking
  (reading/writing bytes on the socket) can use extra I/O threads, but your actual
  commands still run one at a time on the main thread. This keeps things simple and
  avoids race conditions, and it's still fast because operations are tiny and RAM access
  is quick.
- Works from any language — Java, Node.js, Python, Go, etc. — through a client library
  that speaks the Redis protocol (RESP).

## Data types

Redis isn't just "string in, string out" — values can be several structures:

| Type          | What it holds                        | Typical use                          |
|---------------|----------------------------------------|----------------------------------------|
| **String**     | Text or binary data, or a number      | Cache a value, counters (`INCR`)      |
| **List**        | Ordered list of strings             | Simple queue, recent-items list       |
| **Hash**         | Field → value pairs (like an object) | Store an object (user profile) as one key |
| **Set**           | Unordered, unique values          | Tags, "has this user done X" checks   |
| **Sorted Set (ZSet)** | Unique values with a score    | Leaderboards, ranking, priority queues |
| **Stream**          | Append-only log of events      | Event/message queue with consumer groups |
| **Bitmap / HyperLogLog** | Compact bit-level / approximate-count structures | Feature flags per user, huge unique-visitor counts |

```text
SET  user:5:name "Anna"          # String
LPUSH recent:visits "user:5"     # List
HSET  user:5 name "Anna" age 25  # Hash
SADD  tags:5 "vip" "beta"        # Set
ZADD  leaderboard 100 "user:5"   # Sorted Set — 100 is the score
```

## Persistence: RDB vs AOF

Redis keeps data in RAM, so by default a restart loses everything — persistence writes
data to disk so it survives.

- **RDB (snapshotting)** — saves the whole dataset to a single file at set intervals
  (e.g. every 5 minutes, or after N writes). Fast to restart from, compact file, but you
  can lose the writes made since the last snapshot.
- **AOF (Append-Only File)** — logs every write command as it happens. Much less data
  loss on crash (as little as ~1 second, depending on the fsync setting), but the file is
  bigger and replay on restart is slower.
- You can run **both together**: RDB for fast full backups/restarts, AOF for minimal
  data loss — Redis will use AOF to rebuild state on restart if it's enabled.

## Eviction policies

If Redis is used as a cache and runs out of memory (`maxmemory` is set), it needs a rule
for what to remove. Set with `maxmemory-policy`:

| Policy            | What it removes                                      |
|--------------------|--------------------------------------------------------|
| `noeviction`         | Nothing — new writes fail with an error once full   |
| `allkeys-lru`          | Least Recently Used key, from **all** keys          |
| `volatile-lru`           | Least Recently Used key, only among keys **with a TTL** |
| `allkeys-lfu`              | Least Frequently Used key, from all keys         |
| `volatile-lfu`               | Least Frequently Used key, only among keys with a TTL |
| `allkeys-random`                | A random key                                  |
| `volatile-ttl`                    | The key closest to expiring                 |

**TTL** = "time to live" — a key can be given an expiration time (`EXPIRE key 60`), after
which Redis deletes it automatically. `volatile-*` policies only ever evict keys that
have a TTL set; keys with no TTL are left alone.

## High availability

- **Replication** — one **primary** (master) handles writes and asynchronously copies
  them to one or more **replicas**, which can serve reads. If the primary fails, data may
  briefly lag on replicas (async replication).
- **Sentinel** — a separate set of processes that watches the primary and replicas.
  If the primary goes down, Sentinel promotes a replica to primary automatically and
  reconfigures the others — automatic failover, similar in spirit to a DB replica set
  election.
- **Cluster** — for scaling beyond one server's memory/throughput. Data is split into
  **16384 hash slots**; each key is mapped to a slot with `CRC16(key) % 16384`, and each
  slot is owned by one node (which can itself have replicas). Clients get redirected to
  the right node for a given key.

## Common use cases

- **Caching** — store the result of a slow DB query or API call so the next request is
  instant (see the `@Cacheable` example below).
- **Sessions** — keep HTTP session data in one shared place so any of your app's
  instances can read it, instead of "sticky sessions" tied to one server.
- **Rate limiting / counters** — `INCR` is atomic, so counting requests per user/IP per
  time window is simple and safe under concurrency.
- **Distributed locks** — `SET key value NX PX 30000` sets a key only if it doesn't
  already exist, with a 30-second expiry — a simple lock other instances can check.
- **Leaderboards** — a Sorted Set naturally keeps entries ranked by score.
- **Pub/sub and queues** — simple message passing between services, or a Stream acting
  as a lightweight event log with consumer groups.

## Redis with Java and Spring Boot

Dependency: `spring-boot-starter-data-redis`. Add `@EnableCaching` on a configuration
class to use `@Cacheable`/`@CacheEvict`.

```yaml
# application.yml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

### Declarative caching

```java
@Service
public class UserService {

    @Cacheable("users")               // look in Redis first
    public User getUser(Long id) {
        // runs only on a cache miss
        return userRepository.findById(id).orElseThrow();
    }

    @CachePut(value = "users", key = "#user.id")   // always run, then refresh the cache
    public User updateUser(User user) {
        return userRepository.save(user);
    }

    @CacheEvict(value = "users", key = "#id")      // remove stale entry
    public void deleteUser(Long id) {
        userRepository.deleteById(id);
    }
}
```

### Direct access with `RedisTemplate`

```java
@Autowired
private RedisTemplate<String, String> redis;

// String
redis.opsForValue().set("user:5:name", "Anna");
redis.opsForValue().set("session:abc", "userId=5", Duration.ofMinutes(30)); // with TTL
String name = redis.opsForValue().get("user:5:name");

// Hash
redis.opsForHash().put("user:5", "age", "25");
Object age = redis.opsForHash().get("user:5", "age");

// Sorted set (leaderboard)
redis.opsForZSet().add("leaderboard", "user:5", 100);
Set<String> top10 = redis.opsForZSet().reverseRange("leaderboard", 0, 9);
```

### A simple distributed lock

```java
Boolean locked = redis.opsForValue()
        .setIfAbsent("lock:order:123", "worker-1", Duration.ofSeconds(30));

if (Boolean.TRUE.equals(locked)) {
    try {
        // do the work only one instance should do at a time
    } finally {
        redis.delete("lock:order:123");
    }
}
```

> For real production locking (handling clock drift, safe unlock, retries), use a
> dedicated library like **Redisson**, which implements this more carefully than a
> hand-rolled `SETNX`.

## Most popular interview questions

**Is Redis single-threaded? How is it still so fast?**
Command execution is single-threaded — one core runs commands one at a time, no locking
between them. It stays fast because operations are simple, data is in RAM, and (since
Redis 6) network I/O can use extra threads even though command execution itself doesn't.

**What's the difference between RDB and AOF persistence?**
RDB takes periodic full snapshots — fast restart, but can lose recent writes. AOF logs
every write as it happens — much less data loss, but a bigger file and slower restart.
Many setups use both.

**How does Redis Cluster shard data across nodes?**
Every key is hashed with `CRC16` into one of 16384 fixed hash slots, and each slot is
owned by one node. A client asking for a key gets redirected to the node that owns its
slot.

**When would you use `allkeys-lru` vs `volatile-ttl` vs `allkeys-lfu`?**
`allkeys-lru` when everything in the cache is equally disposable and you just want to
keep what's been used recently. `volatile-ttl` when only some keys should ever be
evicted (the ones you explicitly gave a TTL) and you want the soonest-to-expire ones
gone first. `allkeys-lfu` when "used often" matters more than "used recently" — e.g. a
popular item accessed constantly should survive over something accessed once, recently.

**What's the difference between a List, a Set, and a Sorted Set?**
List keeps insertion order and allows duplicates. Set has no order and no duplicates.
Sorted Set has no duplicates but keeps everything ordered by an attached numeric score —
that's what makes it good for rankings.

**How would you implement a simple distributed lock with Redis?**
`SET key value NX PX <ttl>` — set the key only if it doesn't already exist, with an
expiry so a crashed holder doesn't lock things forever. For anything production-grade,
use a library like Redisson instead of hand-rolling it.

**Cache-aside vs write-through — what's the difference?**
Cache-aside (the pattern shown above with `@Cacheable`): the app reads from cache first,
and on a miss reads the DB and fills the cache itself. Write-through: every write goes
through the cache layer, which writes to the DB and cache together, so the cache is
never stale after a write.

**What is cache stampede (thundering herd), and how do you prevent it?**
When a popular cache key expires, many requests can hit the database at the same moment,
all trying to refill the same cache entry. Common fixes: a short lock so only one
request refills the cache while others wait or serve the (slightly) old value, or adding
small random jitter to TTLs so many keys don't expire at exactly the same time.

**Redis vs Memcached — why pick one over the other?**
Memcached is a simpler pure cache (strings only, multi-threaded). Redis supports richer
data types (lists, sets, sorted sets, streams), persistence, replication, and pub/sub —
useful when you need more than "just a cache" from the same tool.
