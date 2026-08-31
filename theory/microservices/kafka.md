# Apache Kafka — Notes

## Table of Contents

1. [What is Kafka](#1-what-is-kafka)
2. [Core Concepts](#2-core-concepts)
3. [Why Use a Message Broker Instead of HTTP](#3-why-use-a-message-broker-instead-of-http)
4. [Delivery Guarantees](#4-delivery-guarantees)
   - [4.1 At-most-once](#41-at-most-once)
   - [4.2 At-least-once](#42-at-least-once)
   - [4.3 Exactly-once](#43-exactly-once)
5. [Clusters, Brokers, and Replication](#5-clusters-brokers-and-replication)
6. [Partitions in Depth](#6-partitions-in-depth)
7. [Message Retention & Deletion](#7-message-retention--deletion)
8. [Sizing Partitions and Consumer Instances](#8-sizing-partitions-and-consumer-instances-short-summary)

## 1. What is Kafka

Kafka is a **message broker**.

A message broker is a standalone program that runs independently of your microservices. Some microservices (**producers**) send it messages addressed to a recipient, while other microservices (**consumers**) regularly poll the broker for new messages. The broker temporarily stores messages, which is how it guarantees their delivery.

## 2. Core Concepts

- **Producer** — a program/microservice that sends (publishes) new messages to the message broker.
- **Consumer** — a program/microservice that receives messages from the broker. A single microservice can be both a producer and a consumer in the same system.
- **Topic** — a channel that a developer creates to send messages of a particular type.
- **Partition** — a topic is actually split into partitions (sub-channels) that messages are distributed into and read from. See §6 for details.
- **Consumer Group** *(added — wasn't in the original notes but is essential)* — a set of consumer instances that share the same `group.id` and jointly consume a topic. Kafka guarantees that **each partition is consumed by only one consumer within the group at a time**. This is the mechanism that lets you scale consumption horizontally: adding more consumer instances to a group lets you process more partitions in parallel (see §8).
- **Offset** *(added)* — a sequential ID Kafka assigns to each message **within a partition**. A consumer group tracks, per partition, the offset of the last message it has processed. This is how Kafka "remembers" where a consumer left off — it's a position marker, not a per-message status (more on this in §7).

## 3. Why Use a Message Broker Instead of HTTP

1. We can send many messages asynchronously without waiting for a response.
2. The broker guarantees delivery — the producer can be confident the message will be delivered and processed.
3. To add more consumers, new microservices simply subscribe to the same message stream on the broker. The producer requires no changes and doesn't need to know how many consumers exist or receive copies of its messages.

## 4. Delivery Guarantees

Kafka supports three delivery guarantee levels. Below are Spring Kafka examples for each (`spring-kafka` dependency assumed).

### 4.1 At-most-once

The message may be lost. This is the fastest option since there's no check that the message was actually delivered/processed. The offset is committed **before** processing — if the consumer crashes mid-processing, that message is gone for good.

```java
@KafkaListener(
    topics = "orders",
    groupId = "order-service",
    containerFactory = "atMostOnceContainerFactory"
)
public void listen(ConsumerRecord<String, String> record, Acknowledgment ack) {
    ack.acknowledge();        // offset committed BEFORE processing
    processOrder(record.value()); // if this throws, the message is lost
}
```

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, String> atMostOnceContainerFactory(
        ConsumerFactory<String, String> consumerFactory) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    return factory;
}
```
(`ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG` must be `false` on the underlying `ConsumerFactory`.)

### 4.2 At-least-once

The message is delivered to the consumer one or more times (the consumer **must be idempotent**). This is Spring Kafka's default-ish behavior when you commit the offset **after** successful processing.

```java
@KafkaListener(
    topics = "orders",
    groupId = "order-service",
    containerFactory = "atLeastOnceContainerFactory"
)
public void listen(ConsumerRecord<String, String> record, Acknowledgment ack) {
    processOrder(record.value()); // must be idempotent — may run more than once
    ack.acknowledge();            // offset committed AFTER successful processing
}
```

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, String> atLeastOnceContainerFactory(
        ConsumerFactory<String, String> consumerFactory) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
    return factory;
}
```

If processing throws before `ack.acknowledge()` is called, the same message will be redelivered on the next poll/rebalance — hence "idempotent" is required (e.g. dedupe by message ID, upsert instead of insert, etc.).

### 4.3 Exactly-once

Each message is processed **exactly once**, even across failures. Kafka achieves this via an **idempotent + transactional producer** combined with **read-committed** consumers, wrapping the "read → process → write" cycle in a Kafka transaction so the offset commit and the outgoing message are atomic.

```java
// Producer side — transactional, idempotent producer
@Bean
public ProducerFactory<String, String> producerFactory() {
    Map<String, Object> config = new HashMap<>();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

    DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(config);
    factory.setTransactionIdPrefix("order-service-tx-"); // makes the producer transactional
    return factory;
}

@Bean
public KafkaTransactionManager<String, String> kafkaTransactionManager(
        ProducerFactory<String, String> producerFactory) {
    return new KafkaTransactionManager<>(producerFactory);
}
```

```java
// Consumer side — must read only committed transactions
@Bean
public ConsumerFactory<String, String> consumerFactory() {
    Map<String, Object> config = new HashMap<>();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    config.put(ConsumerConfig.GROUP_ID_CONFIG, "order-service");
    config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    return new DefaultKafkaConsumerFactory<>(config);
}
```

```java
// Read-process-write, wrapped in one Kafka transaction
@Transactional("kafkaTransactionManager")
@KafkaListener(topics = "orders", groupId = "order-service")
public void listen(ConsumerRecord<String, String> record) {
    String processedPayload = process(record.value());
    kafkaTemplate.send("processed-orders", processedPayload);
    // The offset commit for "orders" and the send to "processed-orders"
    // are committed together, atomically, as one Kafka transaction.
}
```

> Note: exactly-once only holds **within Kafka** (Kafka → Kafka). If your listener also writes to an external system (a database, an HTTP call, etc.) in the same method, you're back to at-least-once semantics for that side effect unless you use an outbox pattern or a distributed transaction.

## 5. Clusters, Brokers, and Replication

Kafka is normally run on more than one server so that if one server goes down, Kafka — with all its stored messages — can keep running.

A group of servers running the same Kafka deployment is called a **cluster**. Each server in the cluster is called a **broker**.

*(added)* Broker coordination (leader election, cluster metadata) is handled either by **ZooKeeper** (older/classic setups) or, in modern Kafka (3.3+), by **KRaft** — Kafka's own built-in Raft-based consensus, which removes the ZooKeeper dependency entirely.

For durability, Kafka replicates data within the system: every message a producer sends is written to every replica of the relevant partition.

## 6. Partitions in Depth

A topic is actually split into **partitions** — sub-channels within the topic that messages land in and are read from. Messages are distributed evenly across partitions; the order of writing to / reading from partitions is not coordinated across the whole topic.

**Pros:** messages in different partitions can be written and read in parallel instead of queueing through a single channel, as would happen with one topic = one channel.

**Cons:** message delivery order across the whole topic can differ from insertion order. **Within a single partition, order is guaranteed.**

You can guarantee ordered delivery for a subset of messages within one topic by adding a `key` field — Kafka guarantees messages with the same key always land in the same partition.

Different partitions of the same topic can be placed on different brokers within the same cluster. This effectively spreads load across brokers.

Even though partitions are split across servers, copies of each partition are stored on multiple servers (replicas), in case one server fails.

*(added)* Each partition has one **leader** replica (handles all reads/writes for that partition) and zero or more **follower** replicas that copy data from the leader. The set of replicas that are fully caught up with the leader is called the **ISR (in-sync replica)** set. If the leader fails, a new leader is elected from the ISR — this is what makes the cluster resilient without losing data.

## 7. Message Retention & Deletion

*(This directly answers your comment: "When are messages deleted from Kafka, and what statuses do they have?")*

Unlike a traditional queue (e.g. RabbitMQ), **Kafka messages don't have a per-message "status"** (like "delivered" / "acked" / "pending"), and they are **not removed once a consumer reads or acknowledges them**. Multiple consumer groups can independently read the same message at different times — deleting on ack would break that.

Instead, Kafka tracks progress **per consumer group, per partition**, via the **offset** (§2) — essentially "how far this group has read," not a state on the message itself.

Messages are deleted from a partition's log based on a **retention policy**, set per topic:

- **Time-based retention** — `retention.ms` (default 7 days). Messages older than this are eligible for deletion, regardless of whether any consumer has read them.
- **Size-based retention** — `retention.bytes`. Once a partition's log exceeds this size, the oldest segments are deleted.
- **Log compaction** — `cleanup.policy=compact`. Instead of deleting by age, Kafka keeps only the **latest message per key** and removes older, superseded ones. Useful for "current state" topics (e.g. a changelog of the latest value per entity ID).

Deletion happens at the **log segment** level (Kafka splits each partition's log into segment files and deletes/compacts whole segments once they're eligible), not message-by-message.

## 8. Sizing Partitions and Consumer Instances (short summary)

- **Partition count = your ceiling on consumer parallelism.** Only one consumer per group can actively read a given partition at a time, so if you have more consumer instances in a group than partitions, the extra instances sit idle for that topic.
- Pick partition count based on the throughput you need and expected future scale — a common practical starting range is **6–12 partitions** for a moderate-throughput topic, adjusted after load testing.
- **You can increase partitions later, but not decrease them.** Plan with headroom rather than starting minimal.
- Increasing partition count **changes key → partition mapping** for existing keys, which can temporarily break ordering guarantees for keyed messages — do this deliberately, not casually.
- Number of running consumer instances (e.g. pod replicas) for a service should generally match or stay under the partition count of the topics it consumes, so you actually use the parallelism you paid for.
- Replication factor (typically 3 in production) is a separate knob from partition count — it's about durability/fault tolerance, not throughput.


# Kafka — Interview Questions & Short Answers

## Partitions & Consumer Groups

**Q: One consumer group, 2 instances of a service, topic with 3 partitions. How are partitions read?**
A: Kafka assigns partitions to instances as evenly as possible within the group. With 3 partitions / 2 instances, one instance gets 2 partitions and the other gets 1. See [Consumer Group](#2-core-concepts).

**Q: Same setup but 3 instances and 2 partitions?**
A: One instance gets no partitions and sits idle for that topic — only 2 instances can ever read in parallel, since a partition can only be consumed by one instance per group at a time. See [§8 Sizing Partitions and Consumer Instances](#8-sizing-partitions-and-consumer-instances-short-summary).

**Q: What happens when a consumer instance in the group crashes?**
A: Kafka triggers a **rebalance** — the crashed instance's partitions are reassigned to the remaining live instances in the group.

**Q: Can two different consumer groups read the same topic independently?**
A: Yes. Each consumer group tracks its own offsets per partition, so multiple groups can read the same topic at their own pace without affecting each other.

## Ordering

**Q: How do you guarantee messages are read in the exact order they were sent, one by one?**
A: Use a message `key` for the subset of messages that need relative ordering — Kafka guarantees same-key messages land in the same partition, and order within a single partition is guaranteed. If you need strict ordering across the **entire topic**, use a topic with a single partition (at the cost of losing parallelism). See [§6 Partitions in Depth](#6-partitions-in-depth).

**Q: Why isn't ordering guaranteed across an entire multi-partition topic by default?**
A: Messages are distributed across partitions independently, and partitions are read in parallel with no cross-partition coordination — order is only guaranteed *within* a partition.

## Delivery Guarantees

**Q: We want events consumed exactly once — how and where do you set that up?**
A: Both sides need configuration:
- **Producer:** `enable.idempotence=true` (prevents duplicates from producer retries); wrap writes in a **Kafka transaction** if you're producing to multiple partitions/topics atomically (Spring: `@Transactional("kafkaTransactionManager")`).
- **Consumer:** `isolation.level=read_committed` so it only reads committed transactional messages.

Full code example: [§4.3 Exactly-once](#43-exactly-once).

**Q: What's the difference between at-most-once, at-least-once, and exactly-once?**
A: At-most-once commits the offset *before* processing (may lose messages); at-least-once commits *after* processing (may reprocess, consumer must be idempotent); exactly-once uses idempotent + transactional producers with `read_committed` consumers so each message affects the system once. See [§4 Delivery Guarantees](#4-delivery-guarantees).

**Q: What does an idempotent producer actually prevent?**
A: Duplicate messages caused by the *producer itself* retrying a send after a network/ack timeout — it does not by itself prevent duplicate *processing* on the consumer side; that still needs at-least-once + idempotent consumer logic, or full exactly-once with transactions.

**Q: Does exactly-once still hold if the consumer also writes to an external database?**
A: No — Kafka's exactly-once guarantee only covers Kafka-to-Kafka. Writing to a DB in the same handler is effectively at-least-once for that DB write unless you use a pattern like the **transactional outbox**.

## Reliability & Fault Tolerance

**Q: What is replication factor and why does it matter?**
A: The number of copies of each partition kept across brokers. Higher replication factor = more fault tolerance (survive more broker failures) at the cost of more storage and network traffic. Typical production value: 3.

**Q: What is ISR (in-sync replica)?**
A: The set of replicas fully caught up with a partition's leader. If the leader broker fails, a new leader is elected only from the ISR, so no committed data is lost. See [§6 Partitions in Depth](#6-partitions-in-depth).

**Q: What's the difference between a broker, a cluster, and a topic?**
A: A **broker** is one Kafka server; a **cluster** is a group of brokers running the same Kafka deployment; a **topic** is a logical channel that lives across the cluster, split into partitions distributed among brokers. See [§2](#2-core-concepts) and [§5](#5-clusters-brokers-and-replication).

## Retention & Storage

**Q: When are messages deleted from Kafka, and by what?**
A: By a per-topic **retention policy** — time-based (`retention.ms`), size-based (`retention.bytes`), or **log compaction** (keeps only the latest value per key). Not deleted on consumer ack. See [§7 Message Retention & Deletion](#7-message-retention--deletion).

**Q: What's the difference between deleting by retention and log compaction?**
A: Retention deletes old data by age/size regardless of key. Compaction keeps the latest message per key forever (removing superseded ones), useful for "current state" topics like a changelog.

## Design / Real-World Setup

**Q: How do you decide how many partitions a topic should have?**
A: Base it on the parallelism/throughput you need, keeping in mind you can increase partitions later but not decrease them, and that adding partitions can reshuffle key→partition mapping. See [§8](#8-sizing-partitions-and-consumer-instances-short-summary).

**Q: Should the number of consumer instances match the number of partitions?**
A: It should not exceed it — extra instances beyond the partition count sit idle for that topic. Matching or staying at/under partition count makes full use of available parallelism.

**Q: What is consumer lag and why does it matter operationally?**
A: The gap between the latest offset in a partition and the offset a consumer group has processed. Growing lag signals the consumer can't keep up with the producer — a key metric to monitor/alert on in production.

**Q: Why does Kafka use a pull model (consumers poll) instead of the broker pushing messages to consumers?**
A: It lets each consumer control its own processing rate, avoiding overwhelming slow consumers, and simplifies handling consumers that are temporarily offline.