# CodePulse Backend

CodePulse Backend is a Java & Spring Boot backend application integrated with PostgreSQL for data persistence, Redis for caching/state management, and Flyway for automated database schema migrations.

---

## 🛠️ Prerequisites

Before getting started, ensure you have the following installed on your machine:

- **Java Development Kit (JDK 21+ or 25)**
- **Docker Desktop** (includes Docker Engine & Docker Compose)
- **Git**
- An IDE:
  - **VS Code** (with the **Extension Pack for Java** and **Spring Boot Extension Pack**), or
  - **IntelliJ IDEA**

---

# 🚀 Step-by-Step Setup Guide

## 1. Clone the Repository

```bash
git clone https://github.com/justSWAYAM/codepulse-backend.git

## Dont forget to change directory
cd codepulse-backend
```

> **Note:** Replace the repository URL if you're using your own fork.

---

## 2. Set Up Environment Files

Before starting Docker, create your local environment configuration.

### Create `.env.example`

```bash
cat << 'EOF' > .env.example
DB_HOST=localhost
DB_PORT=5432
DB_NAME=codepulse
DB_USER=codepulse
DB_PASSWORD=codepulse_local
JWT_ACCESS_SECRET=<base64, at least 256 bits>
JWT_REFRESH_SECRET=<base64, at least 256 bits>
JUDGE0_BASE_URL=http://localhost:2358
EOF
```

### Copy it to `.env`

```bash
cp .env.example .env
```

### Ensure `.env` is ignored by Git

```bash
grep -qxF '.env' .gitignore || echo '.env' >> .gitignore
```

---

## 3. Start PostgreSQL & Redis

Launch the required Docker containers.

```bash
docker compose up -d
```

### Verify the Containers

```bash
docker ps
```

You should see containers similar to:

- `codepulse-postgres` (Port **5432**)
- `codepulse-redis` (Port **6379**)

---

## 4. Run the Spring Boot Application

### Option A — VS Code

1. Open the project in **VS Code**.
2. Install:
   - Extension Pack for Java
   - Spring Boot Extension Pack
3. Open:

   ```
   CodepulseBackendApplication.java
   ```

4. Click **Run** above the `main()` method, or press **F5**.

Alternatively, run from the integrated terminal:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

---

### Option B — IntelliJ IDEA

1. Open the project.
2. Navigate to:

   ```
   Run → Edit Configurations...
   ```

3. Select **CodepulseBackendApplication**.
4. Under **VM Options**, add:

```text
-Dspring.profiles.active=local
```

5. Click **Run** (`Shift + F10`).

---

# 🔍 Verify the Application

After the application starts successfully, verify everything is working.

### Health Check

```
http://localhost:8080/actuator/health
```

Expected response:

```json
{
  "status": "UP"
}
```

### Swagger API Documentation

```
http://localhost:8080/swagger-ui.html
```

---

# 🧹 Useful Docker Commands

## Reset the Database (Delete Volume & Re-run Flyway)

```bash
docker compose down -v
docker compose up -d
```

---

## Connect to PostgreSQL

```bash
docker exec -it codepulse-postgres psql -U codepulse -d codepulse
```

---

## Stop All Containers

```bash
docker compose down
```

---

# 📁 Project Stack

- **Java**
- **Spring Boot**
- **PostgreSQL**
- **Redis**
- **Flyway**
- **Docker**
- **Maven**
- **Swagger / OpenAPI**

---

# 📌 Notes

- Never commit your `.env` file.
- Flyway automatically runs database migrations during application startup.
- Ensure Docker Desktop is running before executing `docker compose up -d`.
- The application uses the `local` Spring profile for local development.

---

# ⚖️ Judge0 sandbox (cgroup v2)

The stock `judge0/judge0:1.13.1` image sandboxes code with isolate 1.8.1, which only works on **cgroup v1**. Docker Desktop (WSL2) and most current Linux hosts use **cgroup v2**, where every run fails with status 13 "Internal Error".

`docker compose` therefore builds a local image, `codepulse/judge0:1.13.1-isolate2`, from [`judge0/`](judge0/):

- `Dockerfile`: Judge0 1.13.1 with **isolate 2.2** (cgroup v2 support)
- `cgroup-setup.sh`: on container start, delegates the memory/pids/cpu controllers to `/sys/fs/cgroup/isolate`
- `isolate-wrapper.sh`: drops `--cg-timing` / `--no-cg-timing`, which Judge0 still sends but isolate 2 removed
- `isolate.cf`: isolate config (`cg_root = /sys/fs/cgroup/isolate`)

No Docker Desktop, WSL or kernel settings are needed. The first `docker compose up -d` builds the image (a few minutes); after editing anything in `judge0/`, run `docker compose build judge0-worker` and `docker compose up -d`.

Check it works:

```bash
docker logs codepulse-judge0-worker 2>&1 | grep cgroup-setup     # "isolate cgroup ready"
curl -s -X POST "http://localhost:2358/submissions?wait=true" -H "Content-Type: application/json"   -d '{"source_code":"print(1)","language_id":71,"expected_output":"1"}'   # "status":{"id":3,...}
```

To go back to the stock image, set both Judge0 services in `docker-compose.yml` to `image: judge0/judge0:1.13.1` (remove `build:`) and run `docker compose up -d`.

Keep `judge0.conf` and everything in `judge0/` with LF line endings (enforced by `.gitattributes`). With CRLF, every `judge0.conf` value gets a trailing `` and Judge0 cannot reach its Redis (`Redis::CannotConnectError ... SocketError`).
