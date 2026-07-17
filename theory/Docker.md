# Docker

Docker is a tool that **packs your app and everything it needs (JDK, libraries, config)
into one box called an image**, and runs that box the same way on any machine. "It works on
my laptop" stops being a problem, because the box carries its own environment.

All the real examples below come from the **OPUS** project
(`C:\java\trafficLabel\opus\opus`), which builds Docker images for a Java/Spring backend,
an nginx frontend, and a Playwright test runner, then ships them to **AWS ECS**.

---

## Why Docker (the problem it solves)

Before Docker you installed Java, Node, nginx, env variables, etc. **by hand** on each
server. Every machine was a little different, and upgrades were painful.

With Docker you write the setup **once** in a `Dockerfile`. That produces an **image**: a
frozen, ready-to-run snapshot. Any machine with Docker can run it identically.

- **Not a virtual machine.** A VM carries a full guest OS (heavy, GBs, slow boot). A
  container shares the **host's Linux kernel** and only adds your app + its libraries. So
  containers are small and start in seconds.

```text
VM:         App + libs + FULL guest OS   ──► heavy, minutes to boot
Container:  App + libs (shares host kernel) ──► light, seconds to boot
```

---

## The three core words

| Word          | What it is                                         | Analogy            |
|---------------|----------------------------------------------------|--------------------|
| **Dockerfile**| A text recipe of build steps.                      | The recipe         |
| **Image**     | The built, frozen result of the recipe.            | The cake (frozen)  |
| **Container** | A running copy of an image.                        | The cake, served   |

One image → many containers. You build the image once, then run as many containers from it
as you want.

A fourth word: **Registry** — a store for images (like Git for code). Examples: **Docker
Hub** (public) and **AWS ECR** (private). OPUS pushes its images to ECR:
`291008967373.dkr.ecr.eu-west-2.amazonaws.com/horizon:opus-backend-<version>`.

---

## Images are built from layers

Each line in a `Dockerfile` creates a **layer** stacked on the previous one. Layers are
**cached and reused**. If a line and everything above it did not change, Docker reuses the
cached layer instead of redoing the work.

Practical rule: **put the things that rarely change near the top, and the things that change
often (your code) near the bottom.** Then a code change only rebuilds the last layers, and
the build is fast.

```text
FROM openjdk:21        ← layer 1 (base, almost never changes → cached)
ENV JAVA_TOOL_OPTIONS  ← layer 2
COPY app.jar           ← layer 3 (your code → changes every build)
```

---

## Dockerfile instructions (with real OPUS examples)

Here is the **real backend Dockerfile** from the project:

```dockerfile
FROM openjdk:21
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=90 -XX:InitialRAMPercentage=25"
EXPOSE 80 9001
ADD /app.jar /app.jar
ENTRYPOINT ["java", "--add-opens","java.base/java.nio=ALL-UNNAMED", \
            "-XX:-OmitStackTraceInFastThrow", \
            "-Djava.security.egd=file:/dev/./urandom", \
            "-Dspring.profiles.active=aws", "-jar", "/app.jar"]
```

What each instruction means:

- **`FROM openjdk:21`** — the **base image** to start from. Here: an official image that
  already has Java 21 installed. Every Dockerfile starts with `FROM`.
- **`ENV ...`** — sets an **environment variable** inside the image. Here it tells the JVM
  to use up to 90% of the container's RAM (important: in a container the JVM must be told
  the memory limits).
- **`EXPOSE 80 9001`** — documents which **ports** the app listens on (80 = app, 9001 =
  management/metrics). Note: `EXPOSE` is only documentation; you still open the port at run
  time with `-p`.
- **`ADD /app.jar /app.jar`** — copies the pre-built jar into the image. (`ADD` also can
  fetch URLs and unzip; for a plain file **`COPY` is the recommended choice** — see below.)
- **`ENTRYPOINT [...]`** — the command that runs when the container starts. Here it launches
  the Spring Boot app with the `aws` profile active.

Other instructions you will meet:

- **`WORKDIR /usr/src/app`** — sets the working folder for later commands (real example: the
  Playwright test Dockerfile below).
- **`RUN <command>`** — runs a command **at build time** and bakes the result into a layer
  (e.g. `RUN npm install`). Used to install things.
- **`COPY src dst`** — copies files from your project into the image at build time.
- **`CMD [...]`** — default command/arguments at run time (see ENTRYPOINT vs CMD below).

### COPY vs ADD

- **`COPY`** — just copies local files. Simple and predictable. **Prefer it.**
- **`ADD`** — same, but also auto-extracts local tar archives and can download URLs. The
  extra magic can surprise you, so use `ADD` only when you actually need it. (OPUS uses
  `ADD /app.jar` where `COPY` would be the cleaner choice.)

### ENTRYPOINT vs CMD

Both say "what to run when the container starts", but they combine:

- **`ENTRYPOINT`** = the fixed program (hard to override).
- **`CMD`** = the default arguments (easy to override at `docker run`).

A common pattern: `ENTRYPOINT ["java","-jar","/app.jar"]` + `CMD ["--spring.profiles.active=local"]`,
so running the image normally uses `local`, but you can pass a different profile on the
command line without rewriting the entrypoint.

---

## More real Dockerfiles from OPUS

### Frontend — serve static files with nginx

```dockerfile
FROM nginx:latest
COPY /web /usr/share/nginx/html          # the built React files
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 81
CMD ["/usr/sbin/nginx", "-g", "daemon off;"]
```

Pattern: the React app is **already built** into static files (`/web`), and nginx just
serves them. `daemon off;` keeps nginx in the foreground — **a container must have one
process running in the foreground, or it exits immediately.**

### Test runner — Playwright

```dockerfile
FROM mcr.microsoft.com/playwright:focal
WORKDIR /usr/src/app
COPY . .                                 # copy the test project in
RUN npm install                          # install deps at build time
RUN npx playwright install               # install browsers
EXPOSE 83 9323
RUN chmod +x ./run-tests.sh
CMD ["sh", "-c", "node ./server.js & ./run-tests.sh"]
```

Pattern: a base image from a vendor (Microsoft) that already has the browsers' system
dependencies, then `RUN` steps install the rest **into the image** so the container is ready
to test immediately.

---

## .dockerignore

Like `.gitignore`, but for the Docker build. It stops listed files from being sent into the
image, which keeps images **smaller** and builds **faster** (and avoids leaking secrets).

OPUS backend `.dockerignore`:

```text
.idea
```

A fuller real-world one usually also has: `target/` (except the jar you need),
`node_modules`, `.git`, `*.log`, `.env`.

---

## Build and run: the everyday commands

```bash
# Build an image from the Dockerfile in the current folder, give it a name:tag
docker build -t opus-backend:1.0 .

# List images you have locally
docker images

# Run a container from the image
#   -d         = detached (background)
#   -p 80:80   = map host port 80 to container port 80
#   --name     = friendly name
#   -e KEY=val = set an env variable
docker run -d -p 80:80 -p 9001:9001 --name backend \
       -e SPRING_PROFILES_ACTIVE=local opus-backend:1.0

# See running containers
docker ps              # add -a to also see stopped ones

# Logs of a container
docker logs -f backend

# Open a shell inside a running container (to look around)
docker exec -it backend bash

# Stop / remove
docker stop backend
docker rm backend
docker rmi opus-backend:1.0
```

### Port mapping: the most common beginner trap

`EXPOSE 80` inside the Dockerfile does **not** open the port to your machine. You must add
`-p host:container` when you run:

```text
-p 8080:80   →  browser hits localhost:8080  →  reaches port 80 inside the container
```

---

## Image names and tags

An image is named `repository:tag`. The **tag** is usually a version.

```text
opus-backend:1.0
nginx:latest                     ← :latest just means "the default/newest" — not magic
291008967373.dkr.ecr.eu-west-2.amazonaws.com/horizon:opus-backend-1.0.5
└────────────── registry/repo ──────────────┘        └──── tag ────┘
```

Real OPUS images use the **release version** as the tag (`opus-backend-${ReleaseVersion}`)
so each deploy points at an exact, immutable image. **Avoid relying on `:latest` in
production** — you can't tell which build is actually running.

---

## Registry: push and pull (and the AWS ECR link)

A registry stores images so other machines (and AWS) can pull them.

```bash
# Tag your local image for the remote registry, then push
docker tag opus-backend:1.0 <registry>/horizon:opus-backend-1.0
docker push <registry>/horizon:opus-backend-1.0

# On another machine / in AWS, pull it
docker pull <registry>/horizon:opus-backend-1.0
```

OPUS uses **AWS ECR** (the private registry). The build pushes the image to ECR, and the
**ECS task definition** then references that exact image to run it:

```yaml
# from .aws/stack-template.yaml (ECS task definition)
ContainerDefinitions:
  - Image: "291008967373.dkr.ecr.eu-west-2.amazonaws.com/horizon:opus-backend-${ReleaseVersion}"
```

So the full pipeline is: **Dockerfile → build image → push to ECR → ECS runs a container
from it.** (See `AWS.md` for ECS/ECR.)

---

## Volumes: keeping data alive

Containers are **disposable**. When you remove a container, everything written inside it is
**gone**. To keep data (databases, uploads), mount a **volume** — storage that lives outside
the container.

```bash
# Named volume managed by Docker
docker run -v pgdata:/var/lib/postgresql/data postgres:16

# Bind mount: a real folder on your machine (great for local dev)
docker run -v "$PWD/logs:/app/logs" opus-backend:1.0
```

- **Volume** — managed by Docker, best for real data.
- **Bind mount** — maps a host folder, best for development (edit on host, see it in the
  container).

---

## Networks: how containers talk

Containers on the same Docker network can reach each other **by container name** as if it
were a hostname.

```bash
docker network create opus-net
docker run -d --name db   --network opus-net postgres:16
docker run -d --name backend --network opus-net opus-backend:1.0
# inside "backend", the database host is simply:  db
```

---

## docker compose (run several containers together)

In real apps you have many containers (backend + database + frontend). Starting each by hand
is tedious. **Docker Compose** describes them all in one `docker-compose.yml` and starts them
with one command.

OPUS deploys via AWS ECS (not Compose), but locally Compose is the usual tool. Example that
matches the OPUS pieces:

```yaml
services:
  db:
    image: postgres:16
    environment:
      POSTGRES_PASSWORD: secret
    volumes:
      - pgdata:/var/lib/postgresql/data

  backend:
    build: ./backend/.docker        # build from the Dockerfile there
    ports:
      - "80:80"
      - "9001:9001"
    environment:
      SPRING_PROFILES_ACTIVE: local
    depends_on:
      - db                          # start db first

  frontend:
    build: ./frontend/.docker
    ports:
      - "81:81"

volumes:
  pgdata:
```

```bash
docker compose up -d      # build + start everything
docker compose logs -f    # see all logs
docker compose down       # stop + remove everything
```

---

## The sidecar pattern (real OPUS example)

A container should do **one job**. When a task needs a helper, you run a **second small
container next to the main one** — a "sidecar". They share the same network/lifecycle.

OPUS runs a **logging sidecar**: next to each app container there is an
`aws-for-fluent-bit` container that collects the app's logs and ships them to AWS
OpenSearch. Its tiny Dockerfile:

```dockerfile
FROM public.ecr.aws/aws-observability/aws-for-fluent-bit:stable
COPY custom-fluent-bit.conf /fluent-bit/etc/custom-fluent-bit.conf
```

In the ECS task definition the app container and this `custom-fluent-bit` container are
listed together — the app does the work, the sidecar handles logging.

---

## Multi-stage builds (an upgrade for the OPUS backend)

The OPUS backend Dockerfile expects the jar to be **already built** outside Docker
(`ADD /app.jar`). A cleaner approach is a **multi-stage build**: build the jar inside Docker
in a "builder" stage, then copy only the finished jar into a small final image. The heavy
build tools (Maven, source) do **not** end up in the shipped image.

```dockerfile
# Stage 1: build the jar (this layer has Maven + source — thrown away after)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q dependency:go-offline      # cache deps (fast rebuilds)
COPY src ./src
RUN mvn -q package -DskipTests

# Stage 2: tiny runtime image — only the JRE + the jar
FROM eclipse-temurin:21-jre
COPY --from=build /src/target/app.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

Benefits: **smaller, safer final image** (no build tools), and the whole build is
reproducible inside Docker.

---

## Useful commands cheat sheet

```bash
docker build -t name:tag .        # build image
docker images                     # list images
docker run ...                    # start a container
docker ps -a                      # list containers (all)
docker logs -f <name>             # follow logs
docker exec -it <name> bash       # shell inside a running container
docker stop / rm <name>           # stop / delete a container
docker rmi <image>                # delete an image
docker system prune -a            # clean up unused images/containers (frees disk)
docker pull / push <image>        # registry download / upload
```

---

## Best practices (short)

- **Small base images** — prefer `-jre`/`-slim`/`alpine` over full ones when you can.
- **Layer order** — copy dependencies first, source last, so the cache helps.
- **One process per container** — extra jobs go in a sidecar.
- **`.dockerignore`** — keep junk and secrets out of the image.
- **No secrets in the image** — pass them as env vars / from a secrets store at run time
  (OPUS pulls secrets from AWS SSM at run time, not baked into the image).
- **Pin versions / use real tags** — avoid blindly trusting `:latest` in production.
- **Run as a non-root user** when possible.
- **Multi-stage builds** — ship only what you need to run.

---

## Self-check questions (theory)

**Basics**
1. Difference between an **image** and a **container**?
2. How is a container different from a **virtual machine**? Why does it start faster?
3. What is a **registry**? Name a public one and a private one.
4. What is a **Dockerfile**?

**Images & layers**
5. What is a **layer**, and how does layer **caching** speed up builds?
6. Why copy dependencies before source code in the Dockerfile?
7. What does the **tag** in `name:tag` mean? Why avoid `:latest` in prod?

**Dockerfile instructions**
8. What does **`FROM`** do? What is a base image?
9. Difference between **`COPY`** and **`ADD`**?
10. Difference between **`ENTRYPOINT`** and **`CMD`**?
11. Difference between **`RUN`** (build time) and **`CMD`/`ENTRYPOINT`** (run time)?
12. Does **`EXPOSE`** open a port to your machine? How do you actually open it?

**Running containers**
13. What does `-p 8080:80` do?
14. How do you set an **environment variable** for a container?
15. How do you get a **shell inside** a running container?

**Data & networking**
16. Why is container storage **temporary**? How do you keep data with a **volume**?
17. Difference between a **named volume** and a **bind mount**?
18. How do two containers talk to each other on the same Docker network?

**Bigger picture**
19. What problem does **Docker Compose** solve?
20. What is a **multi-stage build**, and why is it useful?
21. What is the **sidecar** pattern? (OPUS uses one for logging — which?)
22. Walk through the OPUS pipeline: **Dockerfile → image → ECR → ECS**.
23. Why should you **not** put secrets inside an image? Where should they go?
