Here is the complete `README.md` formatted inside a raw Markdown block so you can copy and paste it directly into your file without any extra formatting getting in the way.

```markdown
# CodePulse Backend

CodePulse Backend is a Java & Spring Boot microservice environment integrated with PostgreSQL for data persistence, Redis for caching/state management, and Flyway for automated database schema migrations.

---

## 🛠️ Prerequisites

Before getting started, ensure you have the following installed on your machine:

* **Java Development Kit (JDK 21+ or 25)**
* **Docker & Docker Desktop**
* **Git**
* An IDE/Editor: **VS Code** (with *Extension Pack for Java*) or **IntelliJ IDEA**

---

## 🚀 Step-by-Step Setup Guide

### 1. Clone the Repository

```bash
git clone [https://github.com/your-username/codepulse-backend.git](https://github.com/your-username/codepulse-backend.git)
cd codepulse-backend

```

---

### 2. Set Up Environment Files (Before Starting Docker)

Run the following terminal commands to prepare your local configuration files:

```bash
# Create the environment configuration template (.env.example)
cat << 'EOF' > .env.example
DB_HOST=localhost
DB_PORT=5432
DB_NAME=codepulse
DB_USER=codepulse
DB_PASSWORD=codepulse_local
JWT_SECRET=your-super-secret-key-at-least-256-bits-long
JUDGE0_BASE_URL=http://localhost:2358
EOF

# Copy the template to your active local environment file
cp .env.example .env

# Verify that .env is ignored by Git
grep -qxF '.env' .gitignore || echo '.env' >> .gitignore

```

---

### 3. Launch Docker Containers (PostgreSQL & Redis)

Start the containerized services in detached mode:

```bash
docker compose up -d

```

#### Verify Containers are Running:

```bash
docker ps

```

You should see `codepulse-postgres` (port `5432`) and `codepulse-redis` (port `6379`) active.

---

### 4. Run the Spring Boot Application

#### Option A: Running in VS Code

1. Open the project in VS Code.
2. Install the **Extension Pack for Java** and **Spring Boot Extension Pack**.
3. Open `CodepulseBackendApplication.java`.
4. Click **Run** directly above the `main` method (or press `F5`).
5. *Note:* If running via terminal in VS Code, execute:
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

```



#### Option B: Running in IntelliJ IDEA

1. Open the project in IntelliJ.
2. Go to **Run** $\rightarrow$ **Edit Configurations...**
3. Select `CodepulseBackendApplication`.
4. Add `-Dspring.profiles.active=local` under **VM Options**.
5. Click **Run** (`Shift + F10`).

---

## 🔍 Health Checks & API Documentation

Once the app has booted, verify setup via these URLs:

* **Health Endpoint:** [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health) *(Expected: `"status": "UP"`)*
* **Swagger UI:** [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

---

## 🧹 Maintenance & Troubleshooting Commands

* **Full Database Volume Reset (Wipe & Re-run Flyway Migrations):**
```bash
docker compose down -v
docker compose up -d

```


* **Inspect Postgres Directly inside Docker:**
```bash
docker exec -it codepulse-postgres psql -U codepulse -d codepulse

```


* **Stop Docker Services:**
```bash
docker compose down

```



```

```