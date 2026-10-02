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

# ⚖️ Judge0 on Windows (Docker Desktop / WSL2)

Judge0 1.13.1 sandboxes code with isolate 1.8.1, which needs **cgroup v1**. Docker Desktop on WSL2 runs **cgroup v2**, so every run comes back as status 13 "Internal Error" and the worker logs `Failed to create control group /sys/fs/cgroup/memory/box-N/`.

Check which one you have:

```bash
docker info --format '{{.CgroupVersion}}'     # needs to print 1 for Judge0
```

Fix (Docker Desktop only — does not touch your WSL distros):

1. Quit Docker Desktop.
2. Back up `%APPDATA%\Docker\settings-store.json`, then add this key to the JSON object:
   ```json
   "deprecatedCgroupv1": true
   ```
3. Start Docker Desktop, run `docker compose up -d`, and confirm `docker info` now prints `1`.
4. Smoke test: `curl -s -X POST "http://localhost:2358/submissions?wait=true" -H "Content-Type: application/json" -d '{"source_code":"print(1)","language_id":71,"expected_output":"1"}'` should return `"status":{"id":3,...}`.

To undo, restore the backup and restart Docker Desktop.

Also keep `judge0.conf` with LF line endings (enforced by `.gitattributes`). With CRLF, every value gets a trailing `\r` and Judge0 cannot reach its Redis (`Redis::CannotConnectError ... SocketError`).
