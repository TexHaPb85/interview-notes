# Distributed transactions: 2PC, Saga, and Transactional Outbox

The scenario used everywhere in this file: two microservices, each with several running
instances, each with its **own database of a different vendor** — Service A uses
PostgreSQL, Service B uses MySQL — plus **Kafka**, which needs to get a message so other
consumers find out what happened. We need all of it (both DB writes + the Kafka message)
to succeed together, or none of it to happen at all. Code examples use Java, Spring, and
Kafka.

## Quick links

- [The problem: atomic operations across independent services](#the-problem-atomic-operations-across-independent-services)
- [Two-Phase Commit (2PC)](#two-phase-commit-2pc)
- [Saga pattern](#saga-pattern)
- [2PC vs Saga comparison](#2pc-vs-saga-comparison)
- [Why Saga fits our scenario better](#why-saga-fits-our-scenario-better)
- [The dual-write problem](#the-dual-write-problem)
- [Transactional Outbox pattern](#transactional-outbox-pattern)
- [Putting it together: Saga and Outbox for the 2-service scenario](#putting-it-together-saga-and-outbox-for-the-2-service-scenario)
- [Most popular interview questions](#most-popular-interview-questions)

---

## The problem: atomic operations across independent services

```text
Service A                    Service B                    Kafka
(PostgreSQL)                 (MySQL)

   │ local write                │ local write
   ▼                            ▼
[Order table]               [Payment table]

   We need ALL of these to succeed together — a DB row in A, a DB row in B,
   and an event on Kafka — or NONE of them at all.
```

A normal `@Transactional` method only protects **one database**. It knows nothing about
another service's database, and nothing about Kafka. Once more than one resource is
involved, a plain local transaction cannot give you atomicity anymore — you need a
distributed transaction strategy. The two main strategies are **Two-Phase Commit (2PC)**
and the **Saga pattern**.

## Two-Phase Commit (2PC)

2PC is the "classic" way relational databases get atomicity across several resources
(XA transactions). A **coordinator** talks to every **participant** in two rounds.

```text
                     ┌─────────────┐
                     │ Coordinator │
                     └──────┬──────┘
      "can you commit?"     │      "can you commit?"
   ┌────────────────────────┼────────────────────────┐
   ▼                        │                         ▼
[PostgreSQL]                │                     [MySQL]
 "yes, I'm ready" ──────────┼───────── "yes, I'm ready"
                            ▼
                    "ok — everyone commit!"
   ┌────────────────────────┴────────────────────────┐
   ▼                                                  ▼
[PostgreSQL commits]                          [MySQL commits]
```

- **Phase 1 (prepare):** every participant does the work, locks the affected rows, and
  tells the coordinator "I'm ready" — but does not commit yet.
- **Phase 2 (commit):** only after *every* participant said "ready", the coordinator
  tells them all to commit. If even one participant said "no", everyone rolls back.
- Locks are held from phase 1 until phase 2 finishes — so other transactions are blocked
  the whole time.
- The coordinator is a **single point of failure**: if it crashes between phase 1 and
  phase 2, participants can be stuck holding locks, unsure whether to commit or roll back.
- In Java, this needs a transaction manager that speaks XA — e.g. **Atomikos** or
  **Narayana** — plus XA-capable JDBC drivers for both databases.
- **Kafka is not a real XA participant.** Kafka has its own *producer transactions* (for
  exactly-once writes across its own topics/partitions), but that is a Kafka-internal
  guarantee — it does not plug into an external XA coordinator alongside PostgreSQL and
  MySQL. So a "textbook" 2PC across A + B + Kafka is not really possible in practice.

### What is XA?

**XA** is a standard (from X/Open, now part of The Open Group) that defines how a
**Transaction Manager** and a **Resource Manager** (a database, a JMS queue, etc.) talk
to each other during a two-phase commit. It is not a product — it's an interface. Any
driver that implements it (an `XADataSource` for a database, an `XAConnectionFactory`
for JMS) can be coordinated by any XA-compliant transaction manager. "**XA transaction**"
just means "a transaction coordinated through this standard interface" — it's a name for
*how* the coordination happens, not a separate kind of distributed transaction. **JTA**
(Java Transaction API) is Java's API on top of XA; **Atomikos** and **Narayana** are the
actual transaction-manager libraries you'd use in a Spring app.

### What is the coordinator? Is it a separate microservice connected to both DBs?

Depends on where the resources live:

- **Inside one process (the classic JTA/XA case):** the coordinator is a **library**, not
  a microservice — e.g. Atomikos or Narayana running inside your app. This works when
  *one* application process opens XA connections to *several* resources itself — say, two
  databases, or a database plus an XA-capable JMS queue, all reachable from the same app.
- **Across microservices, where each service owns its own private database:** a
  coordinator cannot simply open a connection to Service B's database — that breaks the
  "each service owns its own data" rule microservices are built on. Instead, the
  coordinator has to be a **separate process that talks to each service through an API**
  (e.g. `POST /prepare`, `POST /commit`, `POST /rollback`), and each service runs its own
  local DB transaction internally in response. This is a real, working architecture
  (frameworks like **Seata** implement it), but it's custom application logic on top —
  not the automatic, driver-level XA coordination JTA gives you for free within one process.

### How it looks in code, and how the "ready" vote works

Here's a typical **dual-write** anti-pattern — save to the DB, then separately try to
notify an external system, with no shared transaction at all:

```java
public void saveEventAndSendNotification(final Event newEvent) {
    eventRepository.save(newEvent);
    try {
        // Send a notification to the external system
        notificationSender.sendNotification(new Notification(newEvent.getType())); // sending Kafka event
    } catch (Exception e) {
        throw new RuntimeException("Notification was not sent to the external system");
    }
}
```

This is not atomic: the DB save can already be committed by the time the exception is
thrown, and nothing rolls it back. If both resources were XA-capable (say, the database
and an XA JMS queue — **not** Kafka, which doesn't support this), a JTA transaction
manager could make the whole method atomic instead:

```java
@Bean
public JtaTransactionManager transactionManager(UserTransaction userTransaction,
                                                  TransactionManager atomikosTransactionManager) {
    return new JtaTransactionManager(userTransaction, atomikosTransactionManager);
    // Atomikos/Narayana — coordinates every XA resource enlisted below
}
```

```java
@Transactional // now backed by the JtaTransactionManager above, not a plain JDBC transaction
public void saveEventAndSendNotification(final Event newEvent) {
    eventRepository.save(newEvent);                 // XA resource #1: the database
    notificationSender.sendNotification(             // XA resource #2: an XA-capable JMS queue
            new Notification(newEvent.getType()));
}                                                     // 2PC runs automatically when the method returns
```

Both `eventRepository` and `notificationSender` now need **XA-aware connections**
(`XADataSource`, `XAConnectionFactory`) enlisted in the same global transaction. You never
call `prepare`/`commit` yourself — the transaction manager does it when the method
finishes.

Underneath, both connections implement the same interface:

```java
public interface XAResource {
    int  prepare(Xid xid) throws XAException;         // "can you commit?" — the vote
    void commit(Xid xid, boolean onePhase) throws XAException;
    void rollback(Xid xid) throws XAException;
}
```

- `prepare()` is the "are you ready?" question. The resource does the actual work,
  guarantees it *can* commit later no matter what happens next (durably logged, locks
  held), and returns `XA_OK` — that's the "I'm ready" vote. If it can't guarantee that,
  it throws instead — a "no" vote.
- The transaction manager calls `prepare()` on every resource **first**, and only calls
  `commit()` on any of them once **every single one** answered `XA_OK`. If any vote is
  "no" (or times out), it calls `rollback()` on all of them instead.
- This is exactly why the locks stay held the whole time: from the moment a resource
  says "I'm ready" until the final commit or rollback arrives, it has to guarantee that
  answer will still hold true — so it can't let anyone else touch that data yet.

### Does this scale to more services (e.g. 5)?

Technically yes — the coordinator just runs the same two rounds with more participants.
In practice it gets worse, not better, as you add more:

- **Locks held longer.** The whole transaction only finishes as fast as its **slowest**
  participant. With 5 services instead of 2, the odds that one of them is slow (or
  briefly unreachable) go up, and everyone else keeps waiting with locks held.
- **More chances to fail.** If each service is available 99.9% of the time, 2 services
  succeeding together happens ~99.8% of the time — with 5 services that drops to ~99.5%.
  A single "no" vote rolls back *everything*, including the 4 services that were ready.
- **Recovery gets harder.** If the coordinator crashes mid-transaction, every participant
  is left "in doubt" until it comes back — with 5 resource managers instead of 2, there
  is more state to reconcile once it does.

This is the main practical reason 2PC doesn't scale well as the number of independent
services grows. Saga was designed specifically to avoid this: each service's local
transaction is short and independent, so one slow service doesn't block the other four.

## Saga pattern

A Saga replaces one big distributed transaction with a **sequence of small local
transactions** — one per service. If a later step fails, the Saga does not roll back like
a database would; instead, it runs **compensating transactions** that undo the effect of
the steps that already succeeded.

```text
Step 1: Service A            Step 2: Service B            Step 3: publish result
local tx (Order = NEW)  ──►  local tx (Payment)      ──►  OrderConfirmed → Kafka

        │ if step 2 or step 3 fails, run compensations instead:  │
        ▼                                                        ▼
compensate: Order = CANCELLED   ◄──────────────────  compensate: refund/void payment
```

- Each step is a **normal local transaction** in one service's own database — no
  cross-service locks, no coordinator.
- There is no automatic rollback. **You write the compensation yourself** — e.g.
  "cancel order" undoes "create order", "refund payment" undoes "charge payment". Some
  steps (like sending an email) may not be compensable at all and need a different plan
  (e.g. sending a follow-up "sorry" email instead of "undoing" the first one).
- Different teams wire the steps together differently (a coordinator service driving the
  steps, or services simply reacting to each other's events) — the core idea above is the
  same either way.
- Consistency is **eventual**, not immediate: for a short time, Order can be `NEW` in
  Service A while Service B hasn't processed payment yet. Anyone reading Order during
  that window sees a state that isn't final yet.

## 2PC vs Saga comparison

| Aspect              | Two-Phase Commit                              | Saga                                             |
|----------------------|-------------------------------------------------|---------------------------------------------------|
| Consistency          | Strong — all-or-nothing, immediately            | Eventual — temporarily inconsistent, then converges |
| Locking               | Yes — locks held across both phases            | No — each local transaction commits and releases locks right away |
| Failure handling        | Automatic rollback by the coordinator        | Manual — you write the compensating transactions |
| Fits Kafka?               | No — Kafka isn't a real XA resource        | Yes — publishing is just one more step in the sequence |
| Scalability                 | Poor — coordinator + locks limit throughput | Good — steps run independently, no cross-service locks |
| Infra needed                   | XA transaction manager (Atomikos, Narayana) | None extra — just your normal DB transactions + messaging |
| Typical fit                       | Few, stable resources, same "trust boundary" | Microservices, different DB vendors, message brokers |

## Why Saga fits our scenario better

Our scenario has three things that make 2PC a bad fit and Saga a good one:

- **Multiple instances of each service.** 2PC locks rows until the whole distributed
  commit finishes — under load, with many instances calling each other, that lock time
  adds up fast and throughput drops.
- **Different DB vendors (PostgreSQL + MySQL).** Both can do XA, but now you also depend
  on a separate coordinator process being reliable — one more thing that can fail.
- **Kafka in the mix.** As covered above, Kafka does not participate in an external 2PC
  transaction the way a database does. Saga treats "publish to Kafka" as just another
  step, so it fits naturally — but that step needs to be *reliable*, which brings up the
  next problem.

## The dual-write problem

Say Service A's local transaction commits the Order row, and then, in a *separate* call,
the code calls `kafkaTemplate.send(...)`. These are **two independent operations**, not
one atomic unit:

- The DB commit can succeed, then the app crashes before the Kafka call — the order
  exists, but nobody downstream ever finds out. **Lost event.**
- Or the Kafka send happens first and succeeds, then the DB transaction fails and rolls
  back — now Kafka has an event for an order that doesn't even exist. **Phantom event.**

This is the **dual-write problem**: writing to two different systems (a database and a
message broker) is not atomic just because the code lines are next to each other. The
Transactional Outbox pattern exists to fix exactly this.

## Transactional Outbox pattern

The trick: don't write directly to Kafka from inside the business transaction at all.
Instead, write the "intent to publish" as a normal row, **in an outbox table in the same
database, in the same local transaction** as the business change. A local ACID
transaction guarantees both rows are saved together, or neither is — no dual-write
problem, because there is only **one** write target (the database) at commit time. A
separate process reads the outbox afterward and actually sends the message to Kafka.

```text
@Transactional method — ONE local database transaction:
    INSERT INTO orders  (...)
    INSERT INTO outbox  (...)          ◄── same commit, or nothing at all

                    │
                    ▼
      scheduled poller (e.g. every 500 ms)
      SELECT * FROM outbox WHERE sent = false
                    │
                    ▼
           kafkaTemplate.send(...)
                    │
                    ▼
      UPDATE outbox SET sent = true
```

This uses the **polling publisher** approach: a scheduled job periodically queries the
outbox table for unsent rows and publishes them.

```sql
CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(50) NOT NULL,   -- e.g. order id
    event_type   VARCHAR(50) NOT NULL,   -- e.g. "OrderCreated"
    payload      TEXT        NOT NULL,   -- JSON body of the event
    sent         BOOLEAN     NOT NULL DEFAULT false,
    created_at   TIMESTAMP   NOT NULL DEFAULT now()
);
```

```java
@Transactional
public void createOrder(Order order) {
    orderRepository.save(order);                     // business table
    outboxRepository.save(OutboxEvent.of(order));     // same local transaction
}                                                      // one commit — both, or neither
```

```java
@Scheduled(fixedDelay = 500)
public void publishPendingEvents() {
    List<OutboxEvent> events = outboxRepository.findBySentFalse();
    for (OutboxEvent event : events) {
        kafkaTemplate.send(event.getTopic(), event.getPayload());
        event.setSent(true);
        outboxRepository.save(event);
    }
}
```

Things worth knowing about this approach:

- **Latency** — an event isn't published the instant it's created, only on the next poll.
  A shorter `fixedDelay` reduces this but adds more load on the DB.
- **At-least-once delivery, not exactly-once** — if the app crashes right after
  `kafkaTemplate.send()` but before `sent = true` is saved, the poller sends the same row
  again next time. **Consumers must be idempotent** (e.g. track processed event IDs).
- **There is an alternative:** instead of polling, a tool like **Debezium** can read the
  database's write-ahead log directly and stream outbox rows to Kafka with lower latency
  and no extra polling queries — at the cost of running extra infrastructure. Same table
  design, different way of reading it.

### Combine Outbox with 2PC, or with Saga?

For our scenario (Service A + Service B + Kafka), it's **Outbox + Saga**, not
Outbox + 2PC — for the same reason covered in the 2PC section: Kafka can't be an XA
participant, so a 2PC round can never include "publish to Kafka" at all, no matter how
you wire it. The Outbox pattern's whole job is to make "commit to my own DB + reliably
get a message out" atomic **without** needing every participant (Kafka included) to
speak XA — which is exactly the situation each Saga step is in. If every participant in
a transaction really were XA-capable (say, two databases only, no Kafka), you wouldn't
need the Outbox pattern at all — 2PC would already give you direct atomicity, with no
extra table or poller required.

## Putting it together: Saga and Outbox for the 2-service scenario

```text
Service A (PostgreSQL)                              Service B (MySQL)
────────────────────────                            ─────────────────────
1. local tx: insert Order
   + insert outbox row "OrderCreated"
2. poller sends "OrderCreated" ──────── Kafka ─────► consumer receives it
                                                      3. local tx: insert Payment
                                                         + insert outbox row
                                                         "PaymentDone" / "PaymentFailed"
5. consumer receives result   ◄──────── Kafka ─────  4. poller sends the result event
   event
6. if "PaymentFailed": local tx (compensation) —
   Order = CANCELLED + outbox row "OrderCancelled"
```

Each service only ever writes to its **own** database in its **own** local transaction —
never to another service's database, never directly to Kafka mid-transaction. The Outbox
table makes each step's "commit + notify" reliable; the Saga's compensation step (6)
handles what happens if a later step fails. Together they give you a workable answer to
"atomic across two different DBs and Kafka" without 2PC.

## Most popular interview questions

**Why is 2PC rarely used in microservices with Kafka involved?**
It needs a central coordinator (single point of failure), holds locks across all
participants until the whole commit finishes (bad for scalability with many service
instances), and Kafka isn't a real XA participant — so it doesn't fit a Kafka-based
architecture well.

**What is the dual-write problem?**
Writing to a database and to Kafka are two separate operations. If the app crashes
between them, or one succeeds while the other fails, the database and Kafka end up
disagreeing — an event is lost, or an event exists for data that was never actually saved.

**How does the Transactional Outbox pattern solve the dual-write problem?**
It replaces the direct Kafka write with a row inserted into an outbox table, in the same
local database transaction as the business change. Since both inserts are one ACID
transaction, they either both happen or neither does. A separate poller later reads
that table and does the actual Kafka publish.

**Does the Outbox pattern give exactly-once delivery?**
No — it gives **at-least-once**. If the app crashes between sending to Kafka and marking
the row as sent, the same event can be sent twice. Consumers need to handle duplicates
(idempotent processing, e.g. by tracking already-seen event IDs).

**Polling publisher vs Debezium/CDC — what's the trade-off?**
Polling is simple to build and needs no extra infrastructure, but adds latency (delay
between polls) and repeated DB queries. CDC (Debezium) reads the database log directly —
lower latency, no polling load — but you now run and operate an extra piece of
infrastructure.

**What is a compensating transaction, and how is it different from a rollback?**
A rollback undoes an *uncommitted* change inside one database transaction. A
compensating transaction is a *new, separate* transaction that reverses the effect of an
already-committed step — e.g. "refund payment" instead of an automatic undo — because by
the time you need it, the original step is already committed and visible elsewhere.

**Why do Saga consumers need to be idempotent?**
Because message delivery (Kafka + the outbox poller) is at-least-once. If the same event
is processed twice and the handler isn't idempotent, you can end up double-charging a
payment or creating a duplicate order.

**Can Kafka itself do "transactions"?**
Yes, but it's a different guarantee: Kafka's producer transactions give exactly-once
writes across Kafka's own topics/partitions (e.g. read-process-write within Kafka). They
don't extend to an external database like PostgreSQL or MySQL, which is exactly why the
Outbox pattern is needed to connect a DB commit to a Kafka publish reliably.
