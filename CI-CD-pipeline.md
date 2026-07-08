# CI/CD Pipeline — Docker Build & Deploy

How the **online-shop-management-system** ("opus") project is built and shipped, and
step-by-step how to set up the **same** thing for another project. Two ways are shown:

1. **Manual (opus style)** — Docker + `docker compose` + deploy by hand to AWS EC2.
2. **Automated CI/CD** — GitHub Actions builds/tests on every push and deploys for you.

At the end: **which one is better** and what to pick.

---

## First: what CI/CD means

| Term | Full name | What it does |
|------|-----------|--------------|
| **CI** | **C**ontinuous **I**ntegration | On every push, automatically **build** the code and **run tests**, so broken code is caught early. |
| **CD** | **C**ontinuous **D**elivery / **D**eployment | Automatically **package** the app (Docker image) and **deploy** it to a server. |

> **Honest note about the opus project:** it does **not** have an automated CI/CD pipeline
> (no GitHub Actions, no Jenkins, no GitLab CI). What it has is a **containerized build**
> (Docker) plus a **manual deploy** to a free AWS EC2 box. That is still a real, reproducible
> "build & ship" setup — it is just run by hand. Part A below documents exactly that. Part B
> adds the automation the project is missing.

---

## What the opus project actually contains

| File | Role |
|------|------|
| `backend/Dockerfile` | Multi-stage: build the Spring Boot jar (Maven + JDK 21), run it on a small JRE 21 image, port `9000`. |
| `frontend/Dockerfile` | Multi-stage: build the Vite/React static site (Node + pnpm), serve it with **nginx** on port `80`. |
| `frontend/nginx.conf` | Serves the SPA and **proxies `/api` → `backend:9000`** (replaces the Vite dev proxy). |
| `docker-compose.yml` | Ties **3 containers** together: `db` (postgres:16), `backend`, `frontend`. |
| `.env.example` | DB name / user / password for compose (copy to `.env`). |
| `*/.dockerignore` | Keeps `target/`, `node_modules/`, `.git/` out of the build. |

**How the pieces talk (one machine, Docker network):**

```
browser ──▶ frontend (nginx :80) ──/api──▶ backend (:9000) ──▶ db (postgres :5432)
```

Inside compose, containers find each other by **service name** (`db`, `backend`) — that is why
the backend DB URL is `jdbc:postgresql://db:5432/...` and **not** `localhost`.

---

## Key idea: multi-stage Docker build

A **multi-stage** Dockerfile has a **build stage** (heavy: full JDK/Node, all deps) and a
**run stage** (light: only JRE / nginx + the final artifact). The final image is small and
does not ship the build tools.

```
[ build stage ]  Maven + JDK 21  →  produces app.jar
        │  COPY --from=build
        ▼
[ run stage ]    JRE 21 only     →  runs app.jar   ← this is the shipped image
```

---

# Part A — Manual setup (opus style), step by step

Goal: run the whole app (frontend + backend + database) with **one command** on any PC, then
the **same** stack on a server. Requirement on any machine: **only Docker** — no Java, no
Node, no Postgres installed.

### Step 1 — Backend `Dockerfile`

Put this in your backend folder. **Maven** version (like opus):

```dockerfile
# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline     # cache deps: only re-runs if pom.xml changes
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- run stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

**Gradle** version (e.g. for `spribe-currency-api`) — same idea, Gradle commands:

```dockerfile
# ---- build stage ----
FROM gradle:8-jdk21 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
RUN gradle dependencies --no-daemon || true   # warm the dependency cache
COPY src ./src
RUN gradle clean bootJar --no-daemon -x test

# ---- run stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

### Step 2 — Frontend `Dockerfile` + `nginx.conf` *(only if the project has a UI)*

```dockerfile
# ---- build stage ----
FROM node:22-alpine AS build
WORKDIR /app
RUN npm install -g pnpm@9
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

# ---- run stage ----
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
```

`nginx.conf` — serve the SPA and forward `/api` to the backend container:

```nginx
server {
    listen 80;
    server_name _;
    client_max_body_size 100M;            # allow big uploads if you need them

    root /usr/share/nginx/html;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;  # SPA: unknown routes → index.html
    }

    location /api/ {
        proxy_pass http://backend:9000;    # "backend" = compose service name
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### Step 3 — `docker-compose.yml` (repo root)

```yaml
services:
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB:       ${POSTGRES_DB:-myapp-db}
      POSTGRES_USER:     ${POSTGRES_USER:-postgres}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-root}
    volumes:
      - pgdata:/var/lib/postgresql/data          # data survives "compose down"
    healthcheck:                                   # backend waits until DB is ready
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-postgres}"]
      interval: 5s
      timeout: 5s
      retries: 10
    restart: unless-stopped

  backend:
    build: ./backend                               # folder with the backend Dockerfile
    environment:
      SPRING_DATASOURCE_URL:      jdbc:postgresql://db:5432/${POSTGRES_DB:-myapp-db}
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER:-postgres}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:-root}
    depends_on:
      db:
        condition: service_healthy
    ports:
      - "9000:9000"
    restart: unless-stopped

  frontend:                                        # remove this block if no UI
    build: ./frontend
    depends_on:
      - backend
    ports:
      - "80:80"
    restart: unless-stopped

volumes:
  pgdata:
```

> **Why override `SPRING_DATASOURCE_URL` here?** Your `application.yml` points at
> `localhost` for local dev. Inside compose the DB is another container named `db`, so the URL
> must be `jdbc:postgresql://db:5432/...`. Env vars from compose **win** over `application.yml`.

### Step 4 — `.env.example` and `.dockerignore`

`.env.example` (copy to `.env`; compose reads `.env` automatically):

```bash
POSTGRES_DB=myapp-db
POSTGRES_USER=postgres
POSTGRES_PASSWORD=root
```

`.dockerignore` in **each** build folder (keeps the image small & the build fast):

```text
# backend/.dockerignore
target/
.git/
*.log
.idea/
```
```text
# frontend/.dockerignore
node_modules/
dist/
.git/
*.log
```

### Step 5 — Run it locally (any PC with Docker)

```bash
docker compose up -d --build     # build images + start (first time ~5-10 min)
# open http://localhost
docker compose logs -f backend   # watch backend logs
docker compose down              # stop (keeps DB data)
docker compose down -v           # stop + delete the DB volume
```

### Step 6 — Deploy to a server (AWS EC2 free tier)

1. **EC2 → Launch instance**: Ubuntu 24.04 LTS, type `t3.micro` (free-tier). Create a key
   pair, download the `.pem`.
2. **Security group** inbound: **SSH 22** from *My IP*, **HTTP 80** from anywhere.
3. **Install Docker on the box:**
   ```bash
   ssh -i your-key.pem ubuntu@<public-dns>
   sudo apt update && sudo apt install -y docker.io docker-compose-v2 git
   sudo usermod -aG docker ubuntu     # log out + back in
   ```
4. **Get the code + run:**
   ```bash
   git clone https://<user>:<token>@github.com/<you>/<repo>.git   # token = read-only PAT
   cd <repo>
   docker compose up -d --build
   ```
5. Open `http://<public-dns>`. **Stop the instance when not using it** to save credits.

> To ship a **new version** the manual way: `ssh` in → `git pull` → `docker compose up -d --build`.
> That one line is the whole "deploy" — and Part B automates exactly it.

---

# Part B — Automated CI/CD with GitHub Actions

Same Docker files as Part A, but now **GitHub does the work** on every push. Workflows live in
`.github/workflows/*.yml`. Two separate jobs is the clean split:

### B1 — CI: build & test on every push (`.github/workflows/ci.yml`)

This is the **cheapest, highest-value** step — add it first. It gates bad code.

```yaml
name: CI
on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven            # use "gradle" for a Gradle project
      - name: Build & test
        run: mvn -B clean verify   # Gradle: ./gradlew build
```

A green check ✅ appears on the commit / PR. Tests failing = red ❌ = don't merge.

### B2 — CD: build image + deploy on push to `main` (`.github/workflows/deploy.yml`)

The simplest deploy reuses your compose file over SSH — **no image registry needed**:

```yaml
name: Deploy
on:
  push:
    branches: [ main ]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - name: Deploy over SSH (pull + rebuild on the server)
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ubuntu
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            cd ~/app
            git pull
            docker compose up -d --build
```

**Secrets to add** (GitHub repo → Settings → Secrets and variables → Actions):

| Secret | Value |
|--------|-------|
| `EC2_HOST` | the EC2 public DNS / IP |
| `EC2_SSH_KEY` | contents of your `.pem` private key |

> **Bigger-project variant (registry-based):** instead of building on the server, build the
> image **in CI**, push it to **GHCR** (GitHub Container Registry) with
> `docker/build-push-action`, and on the server just `docker compose pull && docker compose up -d`.
> This keeps the server small and makes rollbacks easy (pull an older image tag). Overkill for a
> study project, standard for real teams.

### The full automated flow

```
git push
   │
   ▼
GitHub Actions ── CI: build + test ──✅──▶ CD: SSH to EC2 → git pull → docker compose up -d
                                    ──❌──▶ stop, nothing deploys
```

---

## Which is better?

| | Manual (Part A) | Automated / GitHub Actions (Part B) |
|---|---|---|
| Setup effort | **Lowest** — just Docker files | Medium — workflows + secrets |
| Deploy step | You SSH + type commands each time | **Automatic** on `git push` |
| Tests run automatically? | ❌ No | ✅ Yes, every push |
| Repeatable / no human error | ❌ Easy to forget a step | ✅ Same steps every time |
| Rollback | Manual | Easy (redeploy old image tag) |
| Best for | Learning the pieces, quick demo | Real projects, teamwork, portfolio |

**Recommendation:**

1. **Start with Part A** (Docker + compose + manual deploy). It makes you understand every
   piece — this is exactly what the opus project does, and it is enough for a study/demo app.
2. **Then add Part B1 (CI)** — the `ci.yml` build+test workflow. It is a few lines, costs
   nothing, and instantly makes the project look professional (green ✅ on every PR).
3. **Add Part B2 (auto-deploy) only** once you have a stable server. Until then, manual
   `git pull && docker compose up -d --build` is fine.

> **The sweet spot for a portfolio project = Docker setup + GitHub Actions CI (build/test).**
> That is "real CI" with almost no cost. Full auto-deploy is nice-to-have, not required.

---

## Quick checklist for the new project

- [ ] `backend/Dockerfile` (Maven **or** Gradle variant)
- [ ] `frontend/Dockerfile` + `nginx.conf` *(only if there is a UI)*
- [ ] `docker-compose.yml` at repo root (db + backend + frontend)
- [ ] `.env.example` and a `.dockerignore` per build folder
- [ ] `docker compose up -d --build` works locally → open `http://localhost`
- [ ] EC2 box created, Docker installed, repo cloned, stack running
- [ ] *(optional)* `.github/workflows/ci.yml` — build + test on push
- [ ] *(optional)* `.github/workflows/deploy.yml` + `EC2_HOST` / `EC2_SSH_KEY` secrets

---

*Reference: the opus project's own notes live in
`online-shop-management-system/.project_management/docker-aws-deploy.md`.*
