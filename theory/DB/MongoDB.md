# MongoDB in 1 hour

MongoDB is a **document-oriented NoSQL database**. Instead of tables and rows, it stores
data as flexible, JSON-like **documents**. Code examples here use **Spring Data
MongoDB** — `MongoRepository` for simple queries, `MongoTemplate` for custom ones.

## Quick links

- [What is MongoDB?](#what-is-mongodb)
- [Document, Collection, Database](#document-collection-database)
- [SQL vs MongoDB terms](#sql-vs-mongodb-terms)
- [CRUD operations](#crud-operations)
- [Schema design: embedding vs referencing](#schema-design-embedding-vs-referencing)
- [Indexes](#indexes)
- [Aggregation pipeline](#aggregation-pipeline)
- [Replication (Replica Set)](#replication-replica-set)
- [Sharding](#sharding)
- [Transactions and consistency](#transactions-and-consistency)
- [Most popular interview questions](#most-popular-interview-questions)

---

## What is MongoDB?

- It is a **document database**: each record is a document, similar to a JSON object.
- Documents are stored in **BSON** (Binary JSON) — like JSON, but with more types:
  `ObjectId`, `Date`, `Binary`, `Int64`, etc. Also faster to parse than plain JSON.
- Documents live inside a **collection**. Collections live inside a **database**.
- **Schema-flexible**: two documents in the same collection can have different fields.
  In real projects, most documents in one collection still share a common shape.

## Document, Collection, Database

```text
Database
  └── Collection ("users")
        ├── Document { "_id": ..., "name": "Alice", "age": 25 }
        ├── Document { "_id": ..., "name": "Bob",   "age": 30, "city": "Kyiv" }
        └── Document { ... }
```

Example document:

```json
{
  "_id": ObjectId("64f1a2b3c4d5e6f7a8b9c0d1"),
  "name": "Alice",
  "age": 25,
  "email": "alice@mail.com",
  "hobbies": ["reading", "chess"],
  "address": { "city": "Kyiv", "zip": "01001" }
}
```

- `_id` — every document has one. If you don't set it, MongoDB generates an `ObjectId`
  automatically. It works like a primary key, and MongoDB always creates a unique index
  on it.
- A field can hold: string, number, boolean, date, array, embedded (nested) document, or
  `null`.
- `ObjectId` is 12 bytes: timestamp + machine/process info + counter — so it is roughly
  sortable by creation time and unique without asking the server for a sequence.

The same document mapped as a **Spring Data MongoDB** entity:

```java
@Document(collection = "users")
public class User {

    @Id
    private String id;              // stored as ObjectId, mapped to String

    private String name;
    private int age;
    private String email;
    private List<String> hobbies;
    private Address address;        // embedded document, not a separate collection

    // constructors, getters, setters
}

public class Address {
    private String city;
    private String zip;
    // constructors, getters, setters
}
```

`@Document` marks the class as mapped to a collection. `@Id` marks the primary key field
— Spring Data converts it to/from `ObjectId` for you.

## SQL vs MongoDB terms

| SQL (PostgreSQL) | MongoDB                          |
|-------------------|-----------------------------------|
| Database           | Database                         |
| Table               | Collection                       |
| Row                 | Document                         |
| Column               | Field                            |
| Primary key           | `_id`                           |
| JOIN                    | `$lookup` (aggregation) or manual reference |
| Index                    | Index (same idea)                |
| Transaction                | Transaction (multi-document, since v4.0) |

## CRUD operations

Two common ways to work with MongoDB in Spring Boot: **`MongoRepository`** (declarative,
less code) and **`MongoTemplate`** (imperative, full control — closer to the shell).

### Repository (`MongoRepository`)

```java
public interface UserRepository extends MongoRepository<User, String> {
    List<User> findByAgeGreaterThan(int age);
    Optional<User> findByName(String name);
}
```

```java
// CREATE
userRepository.save(new User("Alice", 25));
userRepository.saveAll(List.of(new User("Bob", 30), new User("Carl", 22)));

// READ
List<User> adults = userRepository.findByAgeGreaterThan(20);
Optional<User> alice = userRepository.findByName("Alice");

// UPDATE — a repository has no "update one field" method:
// load the document, change it, save it back.
User user = userRepository.findByName("Alice").orElseThrow();
user.setAge(26);
userRepository.save(user);

// DELETE
userRepository.deleteById(userId);
```

### `MongoTemplate` — for partial updates and custom queries

```java
@Autowired
private MongoTemplate mongoTemplate;

// READ with a custom filter
Query query = Query.query(Criteria.where("age").gt(20));
List<User> adults = mongoTemplate.find(query, User.class);

// UPDATE only specific fields (like $set / $push in the shell)
Query filter = Query.query(Criteria.where("name").is("Alice"));
Update update = new Update()
        .set("age", 26)
        .push("hobbies", "hiking");
mongoTemplate.updateFirst(filter, update, User.class);

// DELETE with a filter
mongoTemplate.remove(Query.query(Criteria.where("age").lt(0)), User.class);
```

`Criteria` operators map to the shell ones: `.gt()`, `.gte()`, `.lt()`, `.lte()`, `.ne()`,
`.in()`, `.regex()`, `.exists()`. `Update` operators: `.set()`, `.unset()`, `.inc()`,
`.push()`, `.pull()`, `.addToSet()`.

## Schema design: embedding vs referencing

This is the most popular MongoDB design question. Two ways to model a relationship.

### Embedding — put related data inside the parent document

```java
@Document(collection = "posts")
public class Post {
    @Id
    private String id;
    private String title;
    private List<Comment> comments;   // embedded, not a separate collection
}

public class Comment {
    private String user;
    private String text;
}
```

- One read gets everything — Spring Data maps the whole tree in a single `find`, no join
  needed.
- Good for **1-to-few** relations, data that is always read together, data that does not
  grow without limit.
- Risk: a document has a **16MB size limit**. An array that keeps growing (like comments
  on a very popular post) can become a problem.

### Referencing — store only an `_id`, keep data in another collection

```java
@Document(collection = "users")
public class User {
    @Id
    private String id;
    private String name;
}

@Document(collection = "orders")
public class Order {
    @Id
    private String id;
    private String userId;   // manual reference — just an id, like a foreign key
    private double total;
}
```

- Good for **1-to-many** or **many-to-many**, when the child data is large, changes often,
  or is shared/queried on its own.
- Needs an extra query, or a `$lookup` stage built with the Spring Data `Aggregation` API:

```java
Aggregation aggregation = Aggregation.newAggregation(
        Aggregation.lookup("users", "userId", "_id", "user")
);
AggregationResults<Document> results =
        mongoTemplate.aggregate(aggregation, "orders", Document.class);
```

> Spring Data also has `@DBRef` for automatic reference loading, but most teams avoid it:
> it fetches each referenced document with its own query (no batching), which is slow for
> lists. A plain id field plus a manual `$lookup` is usually the better choice.

**Rule of thumb:** data that is always read together → embed. Data that is large, shared
by many parents, or grows without a limit → reference.

## Indexes

- `_id` always has an index — created automatically.
- Without an index, a query scans every document (`COLLSCAN`). With an index, it jumps
  straight to matching entries (`IXSCAN`).

### Declaring indexes on the entity

```java
@Document(collection = "users")
@CompoundIndex(name = "city_age_idx", def = "{'city': 1, 'age': -1}")
public class User {

    @Id
    private String id;

    @Indexed
    private int age;

    @Indexed(unique = true)
    private String email;

    private String city;
}
```

`@Indexed` only creates the index automatically if
`spring.data.mongodb.auto-index-creation=true` is set — it is off by default, since in
production index creation is usually a separate, controlled step.

### Creating an index programmatically

```java
mongoTemplate.indexOps(User.class)
        .ensureIndex(new Index().on("age", Sort.Direction.ASC));
```

- **Compound index** field order matters: an index on `{ city: 1, age: -1 }` helps a
  query that filters on `city`, or on `city` + `age`, but not one that filters on `age`
  alone.
- More indexes = faster reads, slower writes (every insert/update must update all
  matching indexes too) — same trade-off as in SQL databases.

## Aggregation pipeline

A sequence of **stages**. Documents flow through the stages one by one, like a Unix
pipe — each stage transforms the output of the previous one.

```java
public class UserTotal {
    @Id
    private String id;      // holds the userId — the $group key becomes _id
    private double total;
}
```

```java
Aggregation aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("status").is("paid")),
        Aggregation.group("userId").sum("total").as("total"),
        Aggregation.sort(Sort.Direction.DESC, "total"),
        Aggregation.limit(10)
);

AggregationResults<UserTotal> results =
        mongoTemplate.aggregate(aggregation, "orders", UserTotal.class);
List<UserTotal> topUsers = results.getMappedResults();
```

Common stages:

| Stage       | What it does                                  | Spring Data method            |
|-------------|------------------------------------------------|--------------------------------|
| `$match`    | Filters documents (like `WHERE`)                | `Aggregation.match(...)`       |
| `$group`    | Groups and aggregates (like `GROUP BY`)         | `Aggregation.group(...)`       |
| `$project`  | Reshapes documents — pick/rename/compute fields | `Aggregation.project(...)`     |
| `$sort`     | Orders documents                                | `Aggregation.sort(...)`        |
| `$limit` / `$skip` | Pagination                               | `Aggregation.limit(...)` / `.skip(...)` |
| `$lookup`   | Left outer join with another collection         | `Aggregation.lookup(...)`      |
| `$unwind`   | Turns each array element into its own document  | `Aggregation.unwind(...)`      |

## Replication (Replica Set)

A **Replica Set** is a group of `mongod` instances holding the same data.

```text
        writes         ┌─────────┐    oplog     ┌───────────┐
  app ─────────────────►│ PRIMARY │ ───────────► │ SECONDARY │
                        └─────────┘              └───────────┘
                             │           oplog
                             └────────────────► ┌───────────┐
                                                 │ SECONDARY │
                                                 └───────────┘
```

- Only the **PRIMARY** accepts writes.
- **SECONDARY** members copy changes from the primary's **oplog** (operation log) and can
  serve reads if the app allows it (`readPreference`).
- If the primary goes down, the remaining members hold an **election** and pick a new
  primary automatically — this gives high availability.
- Reading from secondaries can return slightly old data (**replication lag**) — an
  eventual-consistency trade-off, same idea as PostgreSQL read replicas.

### Connecting a Spring Boot app to a Replica Set

```yaml
# application.yml
spring:
  data:
    mongodb:
      uri: mongodb://host1:27017,host2:27017,host3:27017/mydb?replicaSet=rs0&readPreference=secondaryPreferred
```

With `readPreference=secondaryPreferred`, the driver reads from a secondary when one is
available and falls back to the primary — good for read-heavy queries that can tolerate
a bit of lag.

## Sharding

Horizontal scaling: split one big collection's data across several servers by a
**shard key**.

```text
  shard by userId:
  ┌───────────────┐   ┌───────────────┐   ┌───────────────┐
  │   Shard A     │   │   Shard B     │   │   Shard C     │
  │ userId 1–1M   │   │ userId 1M–2M  │   │ userId 2M–3M  │
  └───────────────┘   └───────────────┘   └───────────────┘
           ▲                    ▲                   ▲
           └──────────── mongos (router) ────────────┘
                              ▲
                        config servers
                     (store chunk ranges)
```

- **Shard** — holds a subset of the data (usually itself a replica set).
- **mongos** — router the app talks to; it sends each query to the right shard(s).
- **Config servers** — store the metadata: which key ranges live on which shard.
- Picking a good **shard key** is critical. A bad key (e.g. one with few distinct values,
  or one that is always increasing) sends most traffic to a single shard — a "hot shard".
- Used only for very large datasets that no longer fit on one server — most projects
  never need it.

### Connecting a Spring Boot app to a sharded cluster

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://mongos1:27017,mongos2:27017/mydb
```

The app connects to `mongos`, not to the shards directly, and still uses
`MongoRepository` / `MongoTemplate` as usual — `mongos` hides the sharding from the code.

## Transactions and consistency

- A write to a **single document** is always atomic, with no special setup needed.
- Since MongoDB **4.0**, **multi-document transactions** are supported (ACID, similar to
  SQL transactions) — useful when you must change several documents (maybe in several
  collections) together, all-or-nothing.

### Using transactions in Spring Boot

```java
@Configuration
public class MongoConfig {
    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }
}
```

```java
@Service
public class AccountService {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Transactional
    public void transferMoney(String fromId, String toId, double amount) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(fromId)),
                new Update().inc("balance", -amount),
                "accounts"
        );
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(toId)),
                new Update().inc("balance", amount),
                "accounts"
        );
    }
}
```

If anything inside the method throws, Spring rolls back the whole transaction — the same
`@Transactional` you already use for a relational DB, just backed by
`MongoTransactionManager` instead of a JDBC one.

### Write concern / read concern in Spring Boot

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://host1,host2,host3/mydb?w=majority&readConcernLevel=majority
```

- **Write concern** (`w=`) — how many replica set members must confirm a write before it
  is acknowledged. `w=1` = just the primary (fast, less safe). `w=majority` = most
  members must have it (safer, a bit slower).
- **Read concern** (`readConcernLevel=`) — what guarantee a read gets, e.g. `local`
  (fastest, may be rolled back later) vs `majority` (only reads data confirmed by most
  members).
- MongoDB is generally described as **CP** in CAP-theorem terms: only the primary takes
  writes, so it favors consistency over availability during a network partition — but
  read preference/read concern settings let you tune that trade-off.

## Most popular interview questions

**What is the difference between SQL and MongoDB?**
SQL databases store fixed-schema rows in tables and use joins. MongoDB stores flexible
JSON-like documents in collections and usually avoids joins by embedding related data.

**What is BSON and how is it different from JSON?**
BSON is the binary format MongoDB uses to store documents. It is based on JSON but adds
extra types (`ObjectId`, `Date`, binary data, `Int64`) and is faster to parse and scan
than text JSON.

**When do you embed data, and when do you reference it?**
Embed when data is read together and does not grow without limit (1-to-few). Reference
when data is large, shared across many parents, or queried on its own (1-to-many,
many-to-many).

**Why does an index slow down writes?**
Every insert or update must also update each index that touches the changed fields, so
more indexes mean more work per write — same trade-off as in relational databases.

**What is the aggregation pipeline?**
A list of stages (`$match`, `$group`, `$project`, `$sort`, `$lookup`, ...) that documents
pass through one by one, each stage transforming the output of the previous stage.

**How does a Replica Set handle a primary failure?**
The remaining secondary members detect the primary is unreachable and hold an election
to choose a new primary automatically — no manual failover needed.

**What is a shard key, and why does a bad one hurt performance?**
It is the field used to split a collection across shards. A bad key (low cardinality, or
always increasing) sends most reads/writes to one shard, creating a "hot shard" that
does not benefit from the extra servers.

**Does MongoDB support transactions?**
Yes, multi-document ACID transactions since version 4.0. A single-document write was
always atomic even before that, without needing a transaction.

**What is the difference between `find()` and `findOne()`?**
In the shell, `find()` returns a cursor over all matches, `findOne()` returns a single
document. In Spring Data this maps to `mongoTemplate.find(query, Class)` returning a
`List`, and `mongoTemplate.findOne(query, Class)` — or a repository method returning
`Optional<T>` — for a single result.

**When do you use `MongoRepository` vs `MongoTemplate`?**
`MongoRepository` for simple, predictable queries (method names, `@Query`). Switch to
`MongoTemplate` when you need dynamic filters, partial updates (`$set`, `$inc`), or
aggregation — things a repository interface can't express well.

**What does `@DBRef` do, and why is it often avoided?**
It tells Spring Data to store a reference and load the referenced document
automatically. It's convenient, but it runs one query per referenced document — no
batching, no `$lookup` — so it doesn't scale well for lists. A plain id field with a
manual `$lookup` is usually faster.

**Is MongoDB really "schema-less"?**
By default, yes — documents in one collection can differ. But you can enforce structure
with **schema validation** (`$jsonSchema` rules on a collection), and most real
applications keep a consistent shape anyway.

**What is `write concern` vs `read concern`?**
Write concern controls how many replica members must confirm a write before it is
acknowledged. Read concern controls what guarantee a read has about the data it returns
(e.g. confirmed by the majority, or not).
