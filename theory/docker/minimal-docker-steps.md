# Minimal Docker Steps: Run App on Another PC

Goal: run Java + Spring Boot + React + Postgres app on a new Windows 11 PC (only Docker Desktop needed there).

## 1. Install Docker Desktop
Install Docker Desktop on the new PC. It includes Docker Engine + Compose.

## 2. Dockerfile for backend (Spring Boot)

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY .. .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
```

## 3. Dockerfile for frontend (React)
```dockerfile
FROM node:20 AS build
WORKDIR /app
COPY . .
RUN npm install && npm run build

FROM nginx:alpine
COPY --from=build /app/build /usr/share/nginx/html
```

## 4. One docker-compose.yml for all parts
```yaml
services:
  db:
    image: postgres:16
    environment:
      POSTGRES_PASSWORD: pass
    volumes:
      - dbdata:/var/lib/postgresql/data

  backend:
    build: ./backend
    depends_on:
      - db
    ports:
      - "8080:8080"

  frontend:
    build: ./frontend
    ports:
      - "3000:80"

volumes:
  dbdata:
```

## 5. Move project to new PC
Two ways:
- Copy whole project folder (with Dockerfiles + compose file) via USB/git, then build there.
- Or build images on your PC, push to Docker Hub, then `docker pull` on new PC.

## 6. Run it
```
docker compose up -d
```

No need to install Java, Node, nginx, Postgres manually on new PC. Docker handles everything.
