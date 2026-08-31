# Spring WebFlux — Interview Preparation Guide

Target level: Middle → Senior Java Developer

## Table of Contents

1. [What Is Reactive Programming & Why WebFlux](#1-what-is-reactive-programming--why-webflux)
2. [Reactive Streams Specification](#2-reactive-streams-specification)
3. [Project Reactor: Mono and Flux](#3-project-reactor-mono-and-flux)
4. [Backpressure](#4-backpressure)
5. [Threading Model & Schedulers](#5-threading-model--schedulers)
6. [Common Operators](#6-common-operators)
7. [Error Handling](#7-error-handling)
8. [Context Propagation](#8-context-propagation)
9. [WebClient](#9-webclient)
10. [Annotated Controllers vs Functional Endpoints](#10-annotated-controllers-vs-functional-endpoints)
11. [R2DBC — Reactive Database Access](#11-r2dbc--reactive-database-access)
12. [Server-Sent Events & Streaming](#12-server-sent-events--streaming)
13. [Testing WebFlux Applications](#13-testing-webflux-applications)
14. [WebFlux vs Spring MVC](#14-webflux-vs-spring-mvc)
15. [Common Pitfalls](#15-common-pitfalls)
16. [Interview Questions](#16-interview-questions)

---

## 1. What Is Reactive Programming & Why WebFlux

**Reactive programming** is a paradigm built around asynchronous data streams and the propagation of change. Instead of pulling a result (blocking call, wait, get value), you subscribe to a stream and get values pushed to you as they become available.

Spring WebFlux is Spring's **non-blocking**, **asynchronous**, **event-loop based** web framework, introduced in Spring 5 as an alternative to Spring MVC. It is built on:

- **Project Reactor** — the reactive library implementing the Reactive Streams spec (`Mono`, `Flux`).
- **Netty** as the default embedded server (also supports Undertow, and Servlet 3.1+ containers like Tomcat/Jetty in non-blocking mode).

### Why it exists

Traditional Spring MVC uses a **thread-per-request** model: each incoming request occupies a thread from the servlet container's thread pool for the entire request lifecycle, including time spent waiting on I/O (DB calls, HTTP calls to other services). Under high concurrency with slow downstream dependencies, this model runs out of threads and throughput collapses.

WebFlux uses a small, fixed number of threads (an **event loop**, typically one per CPU core) that are never blocked. When an I/O operation is in progress, the thread is released back to the loop to process other requests. This allows a much higher number of concurrent connections with a much smaller number of threads — provided the entire chain (controller → service → repository → downstream clients) is non-blocking end to end.

**Key trade-off:** WebFlux does not make individual requests faster. It improves **scalability under high concurrency with I/O-bound, slow, or many downstream calls** (e.g., API gateways, streaming, fan-out to microservices). For CPU-bound or low-concurrency workloads it offers little benefit and adds complexity.

---

## 2. Reactive Streams Specification

Reactive Streams is a JVM specification (JSR not official, but a de-facto standard org.reactivestreams) with four interfaces that Reactor implements under the hood:

```java
public interface Publisher<T> {
    void subscribe(Subscriber<? super T> s);
}

public interface Subscriber<T> {
    void onSubscribe(Subscription s);
    void onNext(T t);
    void onError(Throwable t);
    void onComplete();
}

public interface Subscription {
    void request(long n);
    void cancel();
}

public interface Processor<T, R> extends Subscriber<T>, Publisher<R> {}
```

- **Publisher** — a source of data (`Mono`/`Flux` in Reactor).
- **Subscriber** — consumes data emitted by the Publisher.
- **Subscription** — the contract between them; the Subscriber uses it to `request(n)` items, enabling **backpressure** (pull-based flow control layered on top of push).
- **Processor** — acts as both a Subscriber and a Publisher (a bridge/pipeline stage).

The specification guarantees: `onSubscribe` is called first, then zero or more `onNext`, and finally exactly one terminal signal — either `onComplete` or `onError` (never both, never more than once).

**Nothing happens until you subscribe** — Publishers are lazy and cold by default (see §3).

---

## 3. Project Reactor: Mono and Flux

Reactor provides two core reactive types:

- **`Mono<T>`** — a Publisher that emits **0 or 1** element, then completes (or errors). Analogous to `Optional`/`CompletableFuture` but lazy and composable.
- **`Flux<T>`** — a Publisher that emits **0 to N** elements, then completes (or errors). Analogous to a reactive `Stream`/collection.

```java
Mono<String> mono = Mono.just("hello");
Flux<Integer> flux = Flux.range(1, 5);

Flux<String> fromDb = userRepository.findAll()      // Flux<User>
        .map(User::getName)                          // Flux<String>
        .filter(name -> name.startsWith("A"));
```

### Cold vs Hot publishers

- **Cold** (default): the sequence is generated fresh for *each* subscriber. Two subscribers to the same `Flux.just(...)` each get their own independent execution (e.g., a DB query runs twice).
- **Hot**: the sequence is generated once and shared/broadcast to all subscribers; late subscribers may miss earlier elements. Created via `share()`, `publish()`, `ConnectableFlux`, or `Sinks`.

```java
Flux<Long> hot = Flux.interval(Duration.ofSeconds(1)).share();
```

### Sinks (imperatively pushing data, replacing the deprecated `FluxProcessor`)

```java
Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
sink.tryEmitNext("event-1");
Flux<String> events = sink.asFlux();
```

### Nothing runs until `subscribe()`

```java
Mono<String> mono = Mono.just("x").doOnNext(System.out::println); // prints nothing yet
mono.subscribe(); // NOW it runs and prints "x"
```

This laziness is what allows Reactor to build an execution plan (a chain of operators) and assemble it efficiently before anything executes.

---

## 4. Backpressure

Backpressure is a mechanism that lets a **slow consumer** control the rate at which a **fast producer** emits data, preventing memory overflow from unbounded buffering.

In Reactive Streams, this is pull-based: the Subscriber calls `subscription.request(n)` to say "send me up to n more items." Reactor operators respect this contract internally.

Strategies when a producer emits faster than requested (`onBackpressureX` operators):

| Strategy | Behavior |
|---|---|
| `BUFFER` | Queue excess elements (risk of `OutOfMemoryError` if unbounded) |
| `DROP` | Drop the newest incoming elements while buffer is busy |
| `LATEST` | Keep only the most recent element, drop older unconsumed ones |
| `ERROR` | Emit `onError` (e.g., `IllegalStateException: overflow`) when consumer can't keep up |

```java
Flux.range(1, 1_000_000)
    .onBackpressureDrop()
    .publishOn(Schedulers.parallel())
    .subscribe(this::slowConsume);
```

`Flux.create()` / `Flux.generate()` allow custom control over emission and how backpressure signals are honored when bridging non-reactive sources.

---

## 5. Threading Model & Schedulers

WebFlux with Netty runs on a small fixed pool of **event loop threads** (default: number of CPU cores, named `reactor-http-nio-*`). These threads must **never block** (no JDBC, no `Thread.sleep`, no synchronous I/O) — a blocked event loop thread stalls all requests being processed by it.

Reactor gives explicit control over *where* work executes via **Schedulers**:

| Scheduler | Use case |
|---|---|
| `Schedulers.immediate()` | Runs on the current thread (no switch) |
| `Schedulers.single()` | One reusable dedicated thread |
| `Schedulers.parallel()` | Fixed pool sized to CPU cores — for CPU-bound work |
| `Schedulers.boundedElastic()` | Dynamically-sized pool with a cap, for wrapping **blocking** calls (legacy JDBC, file I/O, blocking libraries) |
| `Schedulers.newBoundedElastic(...)` | Custom bounded elastic pool |

### `publishOn` vs `subscribeOn`

- **`subscribeOn`** — affects where the *subscription* (the source/upstream emission) happens. Only the **first** `subscribeOn` in a chain takes effect regardless of its position, because it affects the whole assembly from the point subscription happens.
- **`publishOn`** — affects where execution happens **downstream** from that point onward; can be used multiple times to switch threads mid-chain.

```java
Flux.range(1, 5)
    .subscribeOn(Schedulers.boundedElastic())  // affects source emission
    .map(i -> i * 2)
    .publishOn(Schedulers.parallel())          // switches thread from here down
    .map(this::heavyCpuWork)
    .subscribe();
```

Wrapping a blocking legacy call:

```java
Mono<String> legacyBlockingCall() {
    return Mono.fromCallable(() -> legacyBlockingService.call())
               .subscribeOn(Schedulers.boundedElastic());
}
```

---

## 6. Common Operators

| Operator | Purpose |
|---|---|
| `map` | Synchronous 1-to-1 transformation |
| `flatMap` | 1-to-N async transformation, **interleaves** results (order not guaranteed) |
| `concatMap` | Like `flatMap` but preserves order, processes sequentially (subscribes to next only after previous completes) |
| `flatMapSequential` | Async like `flatMap`, but re-orders results to match source order |
| `filter` | Keep elements matching a predicate |
| `zip` | Combine multiple Publishers pairwise, waits for all to emit |
| `merge` | Combine multiple Publishers, interleaved as they emit |
| `concat` | Combine sequentially, one Publisher fully completes before next starts |
| `switchIfEmpty` | Fallback Publisher if the source completes with no elements |
| `defaultIfEmpty` | Fallback single value if source is empty |
| `then` / `thenMany` | Ignore upstream values, continue with another Publisher upon completion |
| `collectList` / `collectMap` | `Flux<T>` → `Mono<List<T>>` / `Mono<Map<K,V>>` |
| `reduce` | Aggregate elements into a single value |
| `doOnNext`/`doOnError`/`doOnComplete`/`doFinally` | Side-effect hooks, don't alter the stream |
| `retry` / `retryWhen` | Re-subscribe to the source on error |
| `timeout` | Emit an error/fallback if no signal within a duration |
| `cache` | Cache emitted elements/replay to late subscribers |

```java
userRepository.findById(id)                       // Mono<User>
    .flatMap(user -> orderRepository.findByUser(user)  // async, returns Flux<Order>
            .collectList()
            .map(orders -> new UserDto(user, orders)))
    .switchIfEmpty(Mono.error(new UserNotFoundException(id)));
```

**Interview favorite:** `flatMap` vs `concatMap` vs `flatMapSequential` — know that `flatMap` trades ordering for concurrency, `concatMap` trades concurrency for ordering, and `flatMapSequential` gets both concurrency *and* order (buffers out-of-order results).

---

## 7. Error Handling

Errors are **terminal signals** — once `onError` fires, the sequence stops (unless you recover). Reactor never throws exceptions synchronously out of the chain; they're propagated as signals.

| Operator | Behavior |
|---|---|
| `onErrorReturn(fallbackValue)` | Replace error with a static fallback value |
| `onErrorResume(fn)` | Replace error with a fallback Publisher |
| `onErrorMap(fn)` | Transform/wrap the exception into another exception |
| `onErrorContinue(fn)` | Skip the failing element and continue the sequence (use cautiously — not supported by all operators) |
| `doOnError(fn)` | Side-effect only (logging), doesn't recover |
| `retry(n)` | Re-subscribe up to n times on error |
| `retryWhen(spec)` | Custom retry policy (e.g., exponential backoff via `Retry.backoff(...)`) |

```java
webClient.get().uri("/api/data")
    .retrieve()
    .bodyToMono(Data.class)
    .timeout(Duration.ofSeconds(3))
    .retryWhen(Retry.backoff(3, Duration.ofMillis(200)))
    .onErrorResume(TimeoutException.class, ex -> Mono.just(Data.empty()))
    .onErrorMap(WebClientResponseException.class, ex -> new UpstreamException(ex));
```

**Never** try/catch inside a reactive chain expecting it to catch async errors — the exception must be captured *inside* an operator function (e.g. inside `map`) or it propagates as the `onError` signal automatically.

---

## 8. Context Propagation

Since execution hops across threads (event loop → boundedElastic → parallel, etc.), **`ThreadLocal` does not work reliably** in reactive chains (e.g., MDC for logging, Spring Security's `SecurityContextHolder`, transaction context).

Reactor solves this with **`Context`** — an immutable, per-subscription key-value store attached to the subscription chain, propagated automatically through operators (written top-down, read bottom-up):

```java
Mono<String> withContext = Mono.deferContextual(ctx ->
        Mono.just("Hello, " + ctx.get("user")))
    .contextWrite(Context.of("user", "Alice"));
```

Since Reactor 3.4+, there's also **`ContextRegistry`**/`reactor-context-propagation`, which auto-bridges `ThreadLocal`-based instrumentation (like MDC/Micrometer) into Reactor Context without manual wiring, commonly enabled via `Hooks.enableAutomaticContextPropagation()`.

**Interview point:** explain *why* `SecurityContextHolder.getContext()` (ThreadLocal-based) fails inside a WebFlux handler and how Spring Security's reactive stack instead uses `ReactiveSecurityContextHolder` backed by Reactor `Context`.

---

## 9. WebClient

`WebClient` is the **non-blocking, reactive HTTP client** that replaces the blocking `RestTemplate` (which is now in maintenance mode). It returns `Mono`/`Flux` and integrates naturally into reactive pipelines.

```java
WebClient client = WebClient.builder()
        .baseUrl("https://api.example.com")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();

Mono<User> user = client.get()
        .uri("/users/{id}", id)
        .retrieve()                                   // handles 4xx/5xx as errors
        .onStatus(HttpStatusCode::is4xxClientError,
                  resp -> Mono.error(new NotFoundException()))
        .bodyToMono(User.class);

Flux<Event> events = client.get()
        .uri("/events/stream")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .retrieve()
        .bodyToFlux(Event.class);
```

Key points for interviews:
- `retrieve()` — simple path, auto-maps error status codes to `WebClientResponseException`.
- `exchangeToMono()`/`exchangeToFlux()` — full access to the `ClientResponse` for custom handling (successor to the deprecated `exchange()`).
- Connection pooling / timeouts are configured via the underlying Netty `HttpClient` (`reactor.netty.http.client.HttpClient`), not via `WebClient` directly.
- **Never call `.block()`** on a `WebClient` result inside a WebFlux handler — this blocks the event loop thread (see §15).

---

## 10. Annotated Controllers vs Functional Endpoints

WebFlux supports **two programming models**:

### 1) Annotation-based (familiar, like Spring MVC)

```java
@RestController
@RequestMapping("/users")
class UserController {

    @GetMapping("/{id}")
    Mono<User> getUser(@PathVariable String id) {
        return userService.findById(id);
    }

    @GetMapping
    Flux<User> getAll() {
        return userService.findAll();
    }
}
```

### 2) Functional endpoints (`RouterFunction` + `HandlerFunction`)

A lambda-based, explicit-routing style, useful for fine-grained control and testability:

```java
@Configuration
class RouterConfig {
    @Bean
    RouterFunction<ServerResponse> routes(UserHandler handler) {
        return RouterFunctions.route()
                .GET("/users/{id}", handler::getUser)
                .GET("/users", handler::getAll)
                .POST("/users", handler::create)
                .build();
    }
}

@Component
class UserHandler {
    Mono<ServerResponse> getUser(ServerRequest req) {
        String id = req.pathVariable("id");
        return userService.findById(id)
                .flatMap(u -> ServerResponse.ok().bodyValue(u))
                .switchIfEmpty(ServerResponse.notFound().build());
    }
}
```

Both compile down to the same underlying `HandlerAdapter`/dispatch mechanism (`HttpHandler`); the choice is largely stylistic — functional endpoints are favored for lightweight microservices and explicit composition.

---

## 11. R2DBC — Reactive Database Access

Traditional JDBC is **blocking by design** — using it inside WebFlux (without wrapping in `boundedElastic`) defeats the purpose of the non-blocking stack. **R2DBC** (Reactive Relational Database Connectivity) is a spec/driver family providing truly non-blocking SQL access.

```java
interface UserRepository extends ReactiveCrudRepository<User, Long> {
    Flux<User> findByLastName(String lastName);

    @Query("SELECT * FROM users WHERE age > :age")
    Flux<User> findOlderThan(int age);
}
```

- Spring Data R2DBC provides repository abstractions analogous to Spring Data JPA, but **no lazy loading, no first-level cache/persistence context, no dirty checking** — it's simpler and more explicit than JPA.
- Transactions use `ReactiveTransactionManager` / `@Transactional` works on reactive return types, or programmatically via `TransactionalOperator`.
- If a legacy project must keep JDBC, wrap blocking repository calls with `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` — but this is a compromise, not a "real" reactive stack, and reintroduces a limited thread pool bottleneck.

---

## 12. Server-Sent Events & Streaming

WebFlux natively supports **streaming responses** since `Flux` is a natural fit for continuous data:

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
Flux<ServerSentEvent<String>> stream() {
    return Flux.interval(Duration.ofSeconds(1))
            .map(seq -> ServerSentEvent.<String>builder()
                    .id(String.valueOf(seq))
                    .event("tick")
                    .data("event " + seq)
                    .build());
}
```

Also supports **NDJSON** (`application/x-ndjson`) streaming and raw chunked `Flux<DataBuffer>` for large file downloads without loading the whole payload into memory. This is a major differentiator from Spring MVC, where streaming long-lived responses ties up a servlet thread for the connection's whole lifetime.

---

## 13. Testing WebFlux Applications

### `StepVerifier` — testing reactive sequences directly

```java
@Test
void shouldEmitValuesInOrder() {
    Flux<Integer> flux = Flux.just(1, 2, 3);

    StepVerifier.create(flux)
        .expectNext(1, 2, 3)
        .verifyComplete();
}

@Test
void shouldPropagateError() {
    StepVerifier.create(service.riskyCall())
        .expectErrorMatches(ex -> ex instanceof IllegalStateException)
        .verify();
}
```

`StepVerifier.withVirtualTime(...)` lets you test time-based operators (`delayElements`, `interval`, `timeout`) without actually waiting in real time.

### `WebTestClient` — testing controllers end-to-end (or against a running server)

```java
@Test
void shouldReturnUser() {
    webTestClient.get().uri("/users/1")
        .exchange()
        .expectStatus().isOk()
        .expectBody(User.class)
        .value(user -> assertThat(user.getName()).isEqualTo("Alice"));
}
```

`WebTestClient` can bind to a controller directly (`bindToController(...)`, no server startup) or bind to a live server (`bindToServer()`), and also supports streaming assertions for SSE/NDJSON.

---

## 14. WebFlux vs Spring MVC

| Aspect | Spring MVC | Spring WebFlux |
|---|---|---|
| Model | Thread-per-request, blocking | Event-loop, non-blocking |
| Underlying I/O | Servlet API (blocking) | Reactive Streams (Netty/Servlet 3.1+ async) |
| Concurrency scaling | Limited by thread pool size | High, small fixed thread count |
| Data access | JDBC, JPA (blocking) | R2DBC (or wrapped blocking calls) |
| Programming style | Imperative | Declarative/functional (operator chains) |
| Debugging/stack traces | Simple, linear | Harder — async stack traces span operators |
| Best for | CRUD apps, low/moderate concurrency, CPU-bound work, teams less familiar with reactive | High-concurrency I/O-bound systems, streaming, gateways, many downstream calls |
| Learning curve | Lower | Higher (backpressure, schedulers, debugging) |

**Important interview nuance:** you don't get benefits from WebFlux by just switching the framework — the *entire* call chain must be non-blocking end-to-end (web layer, service layer, DB driver, HTTP clients to other services). A WebFlux app that calls blocking JDBC underneath gains little and can even perform worse due to added reactive overhead with no non-blocking benefit.

---

## 15. Common Pitfalls

1. **Calling `.block()`/`.blockFirst()`/`.blockLast()` inside a reactive chain or on an event-loop thread.** This defeats non-blocking I/O and can throw `IllegalStateException` ("block()/blockFirst()/blockLast() are blocking, which is not supported in thread reactor-http-nio-*") when Reactor's blocking-call detection is active.
2. **Forgetting to subscribe.** A `Mono`/`Flux` chain that is built but never subscribed to does nothing — a classic bug ("I called the repository but nothing happened").
3. **Using `ThreadLocal` for contextual data (MDC, security context)** instead of Reactor `Context` — values silently vanish or leak across requests.
4. **Blocking calls hidden inside `map`** (e.g., calling a blocking JDBC repository inside `.map()`) — starves the event loop.
5. **Overusing `flatMap` without concurrency limits** on large datasets, causing unbounded parallel subscriptions; use `flatMap(fn, concurrency)` to cap it.
6. **Swallowing errors silently** via misuse of `onErrorContinue` on operators that don't support it, or forgetting a terminal error handler, causing errors to disappear from logs.
7. **Not understanding cold vs hot publishers**, leading to a source (e.g., a DB call) being executed multiple times unexpectedly for each subscriber.
8. **Unbounded buffering** with `onBackpressureBuffer()` on a fast producer/slow consumer, leading to `OutOfMemoryError`.
9. **Assuming reactive code is automatically faster.** It isn't for single low-concurrency requests — the benefit is at scale.

---

## 16. Interview Questions

**Q1. What problem does WebFlux solve compared to Spring MVC?**
It replaces the thread-per-request blocking model with an event-loop, non-blocking model, allowing high concurrency with a small, fixed number of threads — beneficial for I/O-bound, high-concurrency workloads. See [§1](#1-what-is-reactive-programming--why-webflux), [§14](#14-webflux-vs-spring-mvc).

**Q2. What's the difference between `Mono` and `Flux`?**
`Mono` emits 0..1 elements; `Flux` emits 0..N elements. Both are lazy, cold-by-default Publishers that do nothing until subscribed. See [§3](#3-project-reactor-mono-and-flux).

**Q3. What is backpressure and how does Reactive Streams implement it?**
A flow-control mechanism where the Subscriber requests a bounded number of elements (`Subscription.request(n)`) so a fast producer never overwhelms a slow consumer. See [§4](#4-backpressure).

**Q4. Difference between `subscribeOn` and `publishOn`?**
`subscribeOn` affects where the source/subscription runs (only the first one in a chain has effect); `publishOn` switches the execution thread for everything downstream of it, and can be used multiple times. See [§5](#5-threading-model--schedulers).

**Q5. Difference between `flatMap`, `concatMap`, and `flatMapSequential`?**
`flatMap` runs subscriptions concurrently, result order not guaranteed; `concatMap` runs sequentially, preserving order but no concurrency; `flatMapSequential` runs concurrently but reorders results to match source order. See [§6](#6-common-operators).

**Q6. Why shouldn't you call `.block()` inside a WebFlux request-handling thread?**
It blocks a limited event-loop thread that's meant to serve many requests concurrently, stalling all other requests scheduled on it; Reactor even detects and throws on this in many cases. See [§15](#15-common-pitfalls).

**Q7. How do you handle errors in a reactive chain?**
Via operators like `onErrorReturn`, `onErrorResume`, `onErrorMap`, `retry`/`retryWhen` — errors are terminal signals (`onError`) that propagate down the chain unless recovered. See [§7](#7-error-handling).

**Q8. Why doesn't `ThreadLocal` work reliably in WebFlux, and what's the alternative?**
Execution hops across threads (event loop, boundedElastic, parallel schedulers), so thread-bound state is lost; Reactor `Context`, propagated through the subscription chain, replaces it (e.g., `ReactiveSecurityContextHolder`). See [§8](#8-context-propagation).

**Q9. What is R2DBC and why not just use JDBC in a WebFlux app?**
R2DBC is a non-blocking reactive database driver spec; JDBC is inherently blocking, so using it directly in a reactive pipeline (without isolating it on `boundedElastic`) reintroduces blocking and defeats the purpose of the reactive stack. See [§11](#11-r2dbc--reactive-database-access).

**Q10. What's the difference between annotated controllers and functional endpoints in WebFlux?**
Both are supported programming models producing the same runtime behavior; annotated controllers use `@RestController`/`@GetMapping` (familiar MVC style), while functional endpoints use `RouterFunction`/`HandlerFunction` for explicit, lambda-based routing. See [§10](#10-annotated-controllers-vs-functional-endpoints).

**Q11. How do you test reactive code?**
`StepVerifier` for unit-testing `Mono`/`Flux` sequences (including virtual time for time-based operators), and `WebTestClient` for integration-testing endpoints, including streaming responses. See [§13](#13-testing-webflux-applications).

**Q12. What's the difference between a cold and a hot publisher?**
A cold publisher re-executes its source for every subscriber; a hot publisher executes once and broadcasts to all current subscribers, with late subscribers potentially missing earlier emissions. See [§3](#3-project-reactor-mono-and-flux).

**Q13. When would you NOT choose WebFlux for a new service?**
For CPU-bound workloads, low/moderate concurrency, simple CRUD apps, or when the team/ecosystem (ORMs, libraries) is predominantly blocking — the added complexity of reactive code isn't justified without the non-blocking, high-concurrency I/O benefit. See [§14](#14-webflux-vs-spring-mvc).

**Q14. How does WebFlux support streaming (e.g., Server-Sent Events)?**
`Flux` naturally models a stream of events over time; a controller can return `Flux<ServerSentEvent<T>>` with `produces = TEXT_EVENT_STREAM_VALUE`, streaming data as it's produced without holding a thread for the connection's full lifetime. See [§12](#12-server-sent-events--streaming).

**Q15. What is `WebClient` and how does it differ from `RestTemplate`?**
`WebClient` is Spring's non-blocking, reactive HTTP client returning `Mono`/`Flux`, replacing the blocking, now-maintenance-mode `RestTemplate`; it integrates directly into reactive pipelines without blocking calling threads. See [§9](#9-webclient).
