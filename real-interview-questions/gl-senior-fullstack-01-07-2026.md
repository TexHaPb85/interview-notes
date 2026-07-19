# Interview Notes — Senior Fullstack (01.07.2026)

## API design, REST standards

**1. Can you design a REST API for a "users" resource?**
- `GET/POST/PUT/DELETE /api/v1/users` — no verbs in the URL (no "add", "delete", "get" at the end). The action is chosen by the HTTP method itself.
```
GET    /api/v1/users        // list users
GET    /api/v1/users/{id}   // get one user
POST   /api/v1/users        // create user
PUT    /api/v1/users/{id}   // update user
DELETE /api/v1/users/{id}   // delete user
```

**2. Now we should additionally create an API for pets, with a one-to-many relationship with users (1 user → many pets). Design a "create" API for one pet.**
- `[POST] /api/v1/pets` with body `CreatePetRequestDto{userId, petName, petField1, petField2, ...}`
- Nest the resource under its parent — `POST /api/v1/users/{userId}/pets`, with the body containing only pet-specific fields (`userId` is already in the path, no need to repeat it in the body):
```
POST /api/v1/users/{userId}/pets
Body: { "petName": "...", "petField1": "...", "petField2": "..." }
```

**3. What is idempotence? Which HTTP methods are idempotent according to REST standards?**
- All except POST, maybe PATCH.

**4. How to make a POST method (e.g. creation) idempotent?**
- Compare the new object against existing DB records using unique fields.
- Use an idempotency key (e.g. a hash-based key); on collision, compare against every matching entry.
```
POST /api/v1/orders
Headers: Idempotency-Key: 3f9a1c...
```
The server stores the key with the result of the first request; if the same key arrives again, it returns the stored result instead of creating a duplicate.

## Architecture

**1. Main advantages of microservices?**
- Horizontal scaling is cheaper and more flexible than vertical scaling — many servers with smaller resources are cheaper than one expensive server with huge RAM/CPU.
- One failed microservice ≠ the whole system failing; extending resources for one microservice is easier than vertical scaling for the whole system.
- Easier to develop for many large teams (independent deployment).

**2. What is event sourcing (as an architecture style)?**
> *(marked "don't know" in original notes)*
- Instead of storing only the current state of an entity, you store the full sequence of state-changing **events**. The current state is derived by replaying (reducing) those events.
- Benefits: full audit history, ability to rebuild state at any point in time, natural fit with CQRS.
```
// instead of: Account { balance: 100 }
Events: [AccountOpened, MoneyDeposited(50), MoneyDeposited(70), MoneyWithdrawn(20)]
// current balance = replay(events) = 100
```

## Java

**1. SOLID — explain L.**
- **Liskov Substitution Principle** — a subclass should be substitutable for its parent class without breaking the program's correctness. A subclass must not weaken the behavior/contract expected from the parent.

**2. How to create an immutable class?**
`+` (too easy)

**3. `synchronized`?**
- Blocks concurrent access to a resource, within the scope of a block or a method. If the method itself is marked `synchronized`, the lock is on `this` (the current instance).
```java
public synchronized void increment() { count++; } // locks "this"
```

**4. What if it's a `static synchronized` method?**
- Then the lock is on the `Class` object itself — shared across **all** instances of the class.

**5. Which thread-safe collections do you know?**
- `ConcurrentHashMap`, `CopyOnWriteArrayList`, `BlockingQueue`.

**6. What is the difference between them and `Collections.synchronizedList(new ArrayList<>())`?**
- `Collections.synchronizedList(...)` uses pessimistic locking (locks the whole collection on every access), while classes like `ConcurrentHashMap` use optimistic/fine-grained locking (e.g. CAS, segment locking) — better performance under concurrency.

**7. `volatile`?**
- The variable is never cached in CPU registers/cache — every read/write goes directly to main memory. The compiler also won't reorder or optimize access to it. This prevents a whole class of visibility/reordering concurrency problems.

**8. Does it make sense to mark a reference type as `volatile`?**
> *(marked "not sure" in original notes — this answer is correct ✅)*
- Yes. If the reference is reassigned to point to another heap object in one thread, other threads may not see this reassignment without `volatile` (due to caching/reordering).

**9. Provide an example of problems for reference and primitive types without `volatile` that would be fixed by it.**
> *(no answer in original notes)*
```java
// primitive example - a thread may loop forever, never seeing the update
private boolean running = true; // no volatile
void stop() { running = false; }              // thread A
void loop() { while (running) { /* work */ } } // thread B may cache "running" and never see it become false

// reference example - classic double-checked locking without volatile
private static Helper instance; // should be "volatile"
static Helper getInstance() {
    if (instance == null) {
        synchronized (Helper.class) {
            if (instance == null) instance = new Helper(); // reordering may expose a half-constructed object
        }
    }
    return instance;
}
```

**10. `equals` & `hashCode` contract?**
- `o1.equals(o2)` ⇒ `o1.hashCode() == o2.hashCode()`
- `o1.hashCode() == o2.hashCode()` does **NOT** ⇒ `o1.equals(o2)` (hash collisions are allowed).

## Spring

**1. How to create a bean?**
- XML configuration, class-level annotations (`@Service`, `@Repository`, `@Component`), or a `@Bean` method inside a `@Configuration` class.
- Also created automatically by Spring Boot **starters** via auto-configuration: starters register `@Configuration` classes conditionally, based on what's found on the classpath. You can do the same yourself:
```java
@Configuration
@ConditionalOnClass(SomeLibraryClass.class)
public class MyAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public MyService myService() { return new MyService(); }
}
```

**2. What is Spring Actuator?**
> *(marked "not sure" in original notes — answer was incomplete, expanded below)*
- A Spring Boot module that exposes production-ready endpoints for monitoring and managing the application: health checks, metrics, app info, environment properties, thread dumps, request mappings, and more (not just health).
```
GET /actuator/health
GET /actuator/metrics
GET /actuator/env
```

**3. What is the N+1 problem?**
- 1 user → many pets. When selecting several users, we then select each user's pets in a separate query instead of one, e.g. `SELECT * FROM pets WHERE user_id IN (...)`.
- Yes — this is a correct, valid example of the N+1 problem.

## Security

**1. What is a JWT token?**
- A token used for authentication, consisting of 3 parts: header, payload, and signature — encoded and joined with dots: `header.payload.signature`.
```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.SflKxwRJSMeKKF2QT4fw...
   header               payload            signature
```

**2. What is the difference between a JWT and a random token also used for auth? What is the main advantage of JWT?**
- With a random/opaque token, the service must call a central auth server/DB on every request to check validity. A JWT can be verified locally (using its signature), without calling any other service — so validation is fast and doesn't create a bottleneck.
- Because of the signature, any service holding the shared secret/public key can independently validate the token on every request.

## DB

**1. Advantages and disadvantages of indexes in a DB?**
- Faster `SELECT`, but slower `INSERT`/`UPDATE`/`DELETE` (the index has to be updated too), plus extra storage space.

**2. `HAVING` vs `WHERE`?**
- `WHERE` filters rows before grouping; `HAVING` filters after grouping — used for conditions on aggregated/grouped columns.
```sql
SELECT user_id, AVG(deposit)
FROM deposits
WHERE created_at > '2026-01-01'
GROUP BY user_id
HAVING AVG(deposit) > 5;
```

**3. You have a composite index on columns A, B, C (in that order). Which queries will be optimized by this index?**
- Follows the **leftmost-prefix rule**: queries that filter using a leftmost prefix of the index — `A`, `A+B`, or `A+B+C` (in `WHERE`/`ORDER BY`) — can use this index. A query filtering only by `B` or `C` (without `A`) generally can't use it.
```sql
-- can use the index (A, A;B, A;B;C):
WHERE A = ? 
WHERE A = ? AND B = ?
-- cannot use the index:
WHERE B = ?
WHERE C = ?
```

## Testing

**1. You have a service, and need to write unit tests for its method. How many unit tests should you write? How does SonarLint count test coverage?**
- You should test every branch of your code's logic — every `if`, `switch`/`case`, etc. Every block of code should be covered.

**2. Which DB would you use for your tests?**
- Easiest: an in-memory DB like H2. But better: a containerized DB (via Docker), of the same type as in the real project, so tests behave realistically.
- How to do it: use the **Testcontainers** library — it spins up a real DB in a Docker container automatically for your tests.
```java
@Testcontainers
class OrderRepositoryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");
}
```

## AWS

**1. Which service is responsible for storing images?**
> *(no answer in original notes)*
- **Amazon S3** (Simple Storage Service) — object storage, commonly used for images, files, static assets, backups, etc.

## Docker

**1. Image vs container?**
- An image is like a class; a container is like an object (instance) of that class.
- `docker run` is not exactly just "create a container" — `docker run` = `docker create` (create the container from the image) + `docker start` (start it), combined in one command.
- To run a container, the machine (e.g. a Vagrant VM) first needs to pull the image from somewhere — a centralized image storage called a **Docker registry** (e.g. Docker Hub, Nexus, ECR).

**2. Docker volume?**
> ⚠️ *(marked "not sure" in original notes — original claim was inaccurate, corrected below)*
- A Docker volume is a mechanism for persisting data **outside** the container's writable layer, so the data survives container restarts/removal and can be shared between containers.
- Declared with the `VOLUME` instruction in a Dockerfile (can be placed anywhere in the file, not just at the end), or with `-v`/`--mount` when running a container.
```dockerfile
VOLUME /var/lib/mysql
```
```
docker run -v mydata:/var/lib/mysql mysql
```

**3. Terraform?**
`+` (too easy)

**4. `docker ps`?**
> *(marked "not sure" in original notes — this answer is correct ✅)*
- Lists all **running** containers (ID, image, status, ports, names). Use `docker ps -a` to see all containers, including stopped ones.

## CI/CD

**1. How did your CI/CD process work?**
- Run tests & SonarQube analysis in GitLab CI; once they pass, build and publish a JAR for the branch to Nexus.
- Running `./apps.py up` made a Vagrant VM pull the corresponding image from Nexus (based on branch + hash) and run the container.
```yaml
# simplified gitlab-ci.yml
stages: [test, build, publish]
test:
  script: [mvn test, sonar-scanner]
build:
  script: mvn package
publish:
  script: mvn deploy -DrepositoryId=nexus
```
