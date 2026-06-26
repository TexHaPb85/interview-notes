# AWS services

AWS (Amazon Web Services) is a cloud provider. Instead of buying your own servers,
you **rent** computing, storage, networking, and databases from Amazon and pay only for
what you use.

This file explains the services from the job description: **S3, EC2, VPC, RDS, ECS, EKS,
MSK**. You already know `S3` and `EC2` deployments, so the rest is explained from that
starting point.

## Quick map

| Service | Type            | One line                                                  |
|---------|-----------------|-----------------------------------------------------------|
| **VPC** | Networking      | Your own private network inside AWS.                      |
| **EC2** | Compute (VM)    | A rented virtual server (a full machine you manage).      |
| **S3**  | Storage         | Unlimited file storage (objects in buckets).              |
| **RDS** | Database        | Managed SQL database (Postgres, MySQL, etc.).             |
| **ECS** | Containers      | Run Docker containers, AWS-native way.                    |
| **EKS** | Containers      | Run Docker containers with **Kubernetes**.                |
| **MSK** | Messaging       | Managed **Apache Kafka**.                                 |

A good mental order: **VPC** is the network everything lives in. **EC2** is a server inside
it. **S3** and **RDS** store your data. **ECS/EKS** run your containers. **MSK** moves
messages between services.

---

## VPC (Virtual Private Cloud)

A VPC is **your own private network** inside AWS. Think of it as a fenced area where your
servers, databases, and containers live and talk to each other safely.

Key pieces:

- **CIDR block** — the range of private IP addresses for the VPC, e.g. `10.0.0.0/16`
  (about 65,000 addresses).
- **Subnets** — smaller slices of the VPC, usually one per Availability Zone (data center).
  - **Public subnet** — can reach the internet (has a route to an Internet Gateway).
    You put load balancers and public servers here.
  - **Private subnet** — no direct internet access. You put databases and internal
    services here for safety.
- **Internet Gateway (IGW)** — the door between your VPC and the public internet.
- **NAT Gateway** — lets private servers reach **out** to the internet (e.g. to download
  updates) without being reachable **from** the internet.
- **Route tables** — rules that say "traffic for this IP range goes there".
- **Security Group** — a firewall **around one server/container**. Stateful: if you allow
  traffic in, the reply is allowed out automatically. Example: "allow port 443 from anywhere".
- **NACL (Network ACL)** — a firewall **around a whole subnet**. Stateless and rarely
  touched in daily work.

> Simple picture: VPC = the building, subnets = the floors, security groups = the lock on
> each office door.

---

## EC2 (Elastic Compute Cloud)

EC2 is a **virtual server** you rent — a full Linux/Windows machine in the cloud. You pick
the size (CPU/RAM), the OS image (AMI), and you are responsible for everything on it: the
OS, patches, your app, Docker, etc.

- **Instance** — one running virtual machine.
- **Instance type** — the size, e.g. `t3.micro` (small/cheap), `m5.large` (balanced).
- **AMI (Amazon Machine Image)** — the template (OS + pre-installed software) the instance
  boots from.
- **EBS volume** — a virtual hard disk attached to the instance.
- **Key pair** — the SSH key you use to log in.
- **Auto Scaling Group** — automatically adds/removes instances based on load.
- **Elastic Load Balancer (ELB/ALB)** — spreads incoming traffic across many instances.

You deployed Docker containers on EC2, which means **you managed the server yourself**.
ECS and EKS below remove most of that work.

---

## S3 (Simple Storage Service)

S3 stores **objects** (files) in **buckets**. It is not a normal file system with folders —
it is a giant key-value store: the **key** is the full path/name, the **value** is the file.

- **Bucket** — a top-level container with a globally unique name.
- **Object** — one file + its metadata. Can be up to 5 TB.
- **Key** — the object's full name, e.g. `invoices/2026/06/file.pdf`. The "folders" are
  just part of the name; they don't really exist.
- **Durability** — 11 nines (`99.999999999%`). Amazon copies your data across several data
  centers, so losing a file is almost impossible.
- **Storage classes** — trade price vs access speed:
  - `Standard` — frequent access.
  - `Standard-IA` / `One Zone-IA` — infrequent access, cheaper.
  - `Glacier` / `Glacier Deep Archive` — very cheap, for backups; slow to retrieve.
- **Versioning** — keep old versions of an object so you can recover after an overwrite.
- **Lifecycle rules** — auto-move old objects to cheaper classes or delete them.
- **Access** — private by default; you grant access with **IAM policies**, **bucket
  policies**, or **pre-signed URLs** (a temporary link to one object).

Common uses: storing images/uploads, static website files, logs, backups, and big-data files.

---

## RDS (Relational Database Service)

RDS is a **managed SQL database**. You still use normal SQL (PostgreSQL, MySQL, MariaDB,
Oracle, SQL Server), but AWS handles the boring operational work for you:

- Installing and patching the database engine.
- **Automated backups** and point-in-time recovery.
- **Multi-AZ** — a standby copy in another data center; if the main one fails, AWS switches
  to the standby automatically (high availability).
- **Read replicas** — extra read-only copies to spread read traffic (scaling reads).
- Monitoring, easy resizing of CPU/storage.

You do **not** get OS/SSH access — you only get the database endpoint. For best security you
put RDS in a **private subnet** so only your app can reach it.

> **Aurora** is Amazon's own cloud-native engine, compatible with PostgreSQL/MySQL, faster
> and with storage that grows automatically. It is part of the RDS family.

---

## ECS (Elastic Container Service)

ECS runs your **Docker containers** without you managing a Kubernetes cluster. It is AWS's
own simpler container orchestrator.

Core ideas:

- **Task definition** — a recipe: which Docker image, how much CPU/RAM, ports, env vars.
- **Task** — one running copy of that recipe (one or more containers together).
- **Service** — keeps a set number of tasks always running and restarts failed ones; can
  sit behind a load balancer.
- **Cluster** — the group of compute where tasks run.

ECS has two launch types — **where** the containers run:

| Launch type   | Who manages the servers? | Notes                                        |
|---------------|--------------------------|----------------------------------------------|
| **EC2**       | **You** (your EC2 fleet) | Cheaper at scale, more control, more work.    |
| **Fargate**   | **AWS** (serverless)     | You just give CPU/RAM; no servers to manage.  |

> **Fargate** is the key word: "serverless containers". You stop caring about EC2 instances
> entirely — AWS finds the capacity and runs your container.

This is the step up from your EC2-based deployment: instead of you installing Docker on a
server and starting containers by hand, ECS schedules and restarts them for you.

---

## EKS (Elastic Kubernetes Service)

EKS is **managed Kubernetes**. If your team already uses Kubernetes (k8s), EKS runs the
**control plane** (the Kubernetes "brain") for you, and you connect with standard k8s tools
like `kubectl` and Helm.

Quick Kubernetes vocabulary:

- **Pod** — the smallest unit; one or more containers that run together.
- **Node** — a worker machine (an EC2 instance, or Fargate) that runs pods.
- **Deployment** — declares "I want N copies of this pod"; k8s keeps that number alive.
- **Service** — a stable network name/IP for a set of pods.
- **Ingress** — routes outside HTTP traffic to services.
- **Control plane** — the part that schedules pods and tracks state. EKS manages this.

**ECS vs EKS** — both run containers:

| | ECS | EKS |
|--|-----|-----|
| Orchestrator | AWS's own | Kubernetes (open standard) |
| Learning curve | Easier | Harder |
| Portability | AWS only | Runs anywhere k8s runs |
| Best when | You want simple, AWS-only | You want the k8s ecosystem / multi-cloud |

> Rule of thumb: **ECS** for simple AWS-only setups, **EKS** when the team is already
> invested in Kubernetes.

---

## MSK (Managed Streaming for Apache Kafka)

MSK is **managed Apache Kafka**. Kafka is a system for **streaming events/messages** between
services: producers send messages, consumers read them, and the messages are kept in order
in a durable log.

Why managed: running Kafka yourself means managing brokers, ZooKeeper, scaling, patching,
and failover. MSK does that for you, while you keep the **standard Kafka API** (your apps
don't change).

Core Kafka terms:

- **Broker** — one Kafka server. A cluster has several.
- **Topic** — a named stream of messages (like a channel), e.g. `orders`.
- **Partition** — a topic is split into partitions for parallelism and ordering. Order is
  guaranteed **inside** a partition, not across them.
- **Producer** — app that writes messages.
- **Consumer** / **consumer group** — apps that read messages; a group shares the work.
- **Offset** — the position of a message in a partition; consumers track what they've read.
- **Retention** — how long messages are kept (e.g. 7 days), even after being read.

Common uses: event-driven microservices, log/metric pipelines, feeding big-data tools, and
decoupling producers from consumers so they scale independently.

---

## How they fit together (example)

A typical web app on AWS:

```text
Users ──► ALB (public subnet) ──► ECS/EKS containers (private subnet)
                                        │
                                        ├──► RDS Postgres (private subnet)
                                        ├──► S3 (file uploads)
                                        └──► MSK (publish "order created" events)
All of it lives inside one VPC.
```

---

## Self-check questions (theory)

Try to answer these out loud before reading any notes.

**VPC**
1. What is a VPC, and why use private subnets?
2. Difference between a **Security Group** and a **NACL**?
3. What does a **NAT Gateway** do, and why would a private server need one?
4. Public subnet vs private subnet — what makes a subnet "public"?

**EC2**
5. What is an **AMI**? What is an **EBS volume**?
6. What does an **Auto Scaling Group** do?
7. Difference between a load balancer and an Auto Scaling Group?

**S3**
8. Is S3 a file system? What are a **bucket**, an **object**, and a **key**?
9. What is **S3 durability** (11 nines) and how is it achieved?
10. Name a few **storage classes** and when to use each.
11. What is a **pre-signed URL**?
12. How do you keep an S3 bucket private?

**RDS**
13. What does "managed" mean — what does AWS do that you don't?
14. What is **Multi-AZ**, and how is it different from a **read replica**?
15. What is **Aurora**?
16. Why put RDS in a private subnet?

**ECS / containers**
17. What are a **task definition**, a **task**, and a **service** in ECS?
18. **EC2 launch type vs Fargate** — what's the difference?
19. What does "serverless containers" mean?

**EKS / Kubernetes**
20. What is a **Pod**? A **Deployment**? A **Service**?
21. What does EKS manage for you (the **control plane**)?
22. **ECS vs EKS** — when would you choose each?

**MSK / Kafka**
23. What is a **topic** and a **partition**? Where is message order guaranteed?
24. What is a **consumer group** and an **offset**?
25. Why use Kafka/MSK instead of calling a service directly (decoupling)?

**General**
26. What is **IAM** (users, roles, policies) — how does AWS control access?
27. What is an **Availability Zone** vs a **Region**?
28. What does "**managed service**" mean in general, and what's the trade-off?
