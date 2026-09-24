# Module 1 — Authentication Service: Backend Implementation Roadmap

> **Scope:** Backend only. No frontend changes. No UI. Pure Spring Boot.
> **Prerequisite:** Module 0 (cross-cutting foundation) is done — `BaseEntity`, `ApiResponse<T>`, `GlobalExceptionHandler`, `AppException` hierarchy, `CorrelationIdFilter`, `RedisConfig`, and `application-{profile}.yml` all exist.

---

## Overview

What you are building in this module:

- `User` and `RefreshToken` entities + Flyway migration
- JWT generation, validation, and parsing (`JwtService`)
- Spring Security filter chain (`SecurityConfig` + `JwtAuthenticationFilter`)
- Auth endpoints: login, refresh, logout (`AuthController` + `AuthService`)
- Admin seed user so the system can bootstrap itself
- Unit tests for the critical security path

---

## Step 1 — Database Schema (Flyway Migration)

**File:** `src/main/resources/db/migration/V2__create_auth_tables.sql`

```sql
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name   VARCHAR(255) NOT NULL,
    role        VARCHAR(50)  NOT NULL,        -- ADMIN | EVALUATOR | CANDIDATE
    roll_number VARCHAR(50)  UNIQUE,                  -- required for CANDIDATE, null for ADMIN/EVALUATOR
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID
);

CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(512) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_id  ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_users_email ON users(email);
CREATE UNIQUE INDEX idx_users_roll_number ON users(roll_number) WHERE roll_number IS NOT NULL;
```

**Things to handle:**
- `V1__` is your foundation migration from Module 0 (e.g., `audit_log` table). This must be `V2__`.
- Use `gen_random_uuid()` — requires PostgreSQL 13+ (you have it). No UUID extension needed.
- `token_hash`: store a SHA-256 hash of the raw refresh token, never the token itself. Raw token goes to the client; hash goes in the DB.

---

## Step 2 — Common Enums

**File:** `src/main/java/com/codepulse/common/enums/Role.java`

```java
public enum Role {
    ADMIN,
    EVALUATOR,
    CANDIDATE
}
```

Add to your existing `com.codepulse.common.enums` package alongside `ContestStatus`, `SubmissionStatus`, etc. If the package doesn't exist yet, create it now — this is the single source of truth for roles across the whole app.

---

## Step 3 — Entities

### 3.1 `User` Entity

**File:** `com/codepulse/user/entity/User.java`

```java
@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
public class User extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "roll_number", unique = true)
    private String rollNumber;   // mandatory for CANDIDATE, null for ADMIN/EVALUATOR

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    // getters, setters, or use Lombok @Data / @Builder
}
```

**Things to handle:**
- `User` implements `UserDetails` from Spring Security — OR you write a separate `CustomUserDetails` wrapper (preferred: keeps your entity clean and decoupled from Spring Security internals).
- If `BaseEntity` already has `id`, `createdAt`, `updatedAt`, `createdBy`, `updatedBy` — do NOT redefine them here. Just extend.
- Do NOT add a `@OneToMany` to `RefreshToken` here. You don't need the reverse navigation in this module.

### 3.2 `RefreshToken` Entity

**File:** `com/codepulse/auth/entity/RefreshToken.java`

```java
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // getters, setters
}
```

**Things to handle:**
- `RefreshToken` does NOT extend `BaseEntity` — it has its own minimal auditing (just `created_at`, no `updated_at` since tokens are immutable once created).
- `FetchType.LAZY` on the user join — you'll almost always load the token and then decide whether to load the user separately, so eager loading wastes a join.

---

## Step 4 — Repositories

**File:** `com/codepulse/user/repository/UserRepository.java`

```java
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

**File:** `com/codepulse/auth/repository/RefreshTokenRepository.java`

```java
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    void deleteAllByUserId(UUID userId);           // used on logout
    void deleteAllByExpiresAtBefore(Instant now);  // cleanup job (Step 9)
}
```

---

## Step 5 — DTOs

Keep DTOs in a `dto` sub-package per feature. Never pass entities over the wire.

**`LoginRequest.java`**
```java
public record LoginRequest(
    @NotBlank @Email String email,
    @NotBlank String password
) {}
```

**`LoginResponse.java`**
```java
public record LoginResponse(
    String accessToken,
    String tokenType,       // "Bearer"
    long expiresIn,         // seconds
    UserSummary user
) {}
```

**`UserSummary.java`** (reused by other modules too — put in `com.codepulse.user.dto`)
```java
public record UserSummary(
    UUID id,
    String email,
    String fullName,
    Role role,
    String rollNumber   // null for ADMIN/EVALUATOR
) {}
```

**`RefreshResponse.java`**
```java
public record RefreshResponse(
    String accessToken,
    long expiresIn
) {}
```

**`RegisterUserRequest.java`** (Admin creates accounts — used by Module 2 too)
```java
public record RegisterUserRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8) String password,
    @NotBlank String fullName,
    @NotNull Role role,
    String rollNumber   // required when role == CANDIDATE; validated in service layer
) {}
```

**Things to handle:**
- Use Java records for DTOs — they're immutable and have no boilerplate.
- Do NOT put `password` in any response DTO — easy mistake to make when mapping from entity.
- `LoginResponse` wraps `UserSummary`, not the full `User` entity. Never return raw entity.

---

## Step 6 — `JwtService`

**File:** `com/codepulse/auth/service/JwtService.java`

This is the most security-critical class. Get it right before touching anything else.

```java
@Service
public class JwtService {

    @Value("${jwt.access-token.secret}")
    private String accessTokenSecret;

    @Value("${jwt.access-token.expiry-seconds}")
    private long accessTokenExpirySeconds;

    @Value("${jwt.refresh-token.expiry-seconds}")
    private long refreshTokenExpirySeconds;

    // Generate access token — short-lived, signed JWT
    public String generateAccessToken(User user) { ... }

    // Generate raw refresh token — this is a random opaque string, NOT a JWT
    // The hash of this goes in the DB; the raw value goes to the client
    public String generateRawRefreshToken() { ... }

    // Extract user email/subject from access token
    public String extractEmail(String token) { ... }

    // Validate access token (signature + expiry)
    public boolean isAccessTokenValid(String token) { ... }

    // Compute the DB-storable hash of the raw refresh token
    public String hashRefreshToken(String rawToken) { ... }

    public long getRefreshTokenExpirySeconds() { return refreshTokenExpirySeconds; }
}
```

**Implementation details:**
- Use `io.jsonwebtoken:jjwt-api` + `jjwt-impl` + `jjwt-jackson` (add to `pom.xml` / `build.gradle`).
- Access token claims to include: `sub` (email), `userId`, `role`, `iat`, `exp`.
- Refresh token = `UUID.randomUUID().toString()` + second UUID concatenated, then base64-encoded. It is NOT a JWT. It's opaque on purpose — if an attacker gets the DB, the hashes are useless without the raw token.
- Hash with `MessageDigest.getInstance("SHA-256")` then hex-encode.
- JWT secret must come from `application.yml` / env var, never hardcoded. Min 256 bits.

**`application.yml` additions:**
```yaml
jwt:
  access-token:
    secret: ${JWT_ACCESS_SECRET}   # set in .env / Docker env
    expiry-seconds: 900            # 15 minutes
  refresh-token:
    expiry-seconds: 604800         # 7 days
```

---

## Step 7 — Spring Security Configuration

### 7.1 `CustomUserDetailsService`

**File:** `com/codepulse/auth/security/CustomUserDetailsService.java`

```java
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        if (!user.isActive()) {
            throw new DisabledException("Account is deactivated");
        }

        return org.springframework.security.core.userdetails.User
            .withUsername(user.getEmail())
            .password(user.getPasswordHash())
            .roles(user.getRole().name())
            .disabled(!user.isActive())
            .build();
    }
}
```

### 7.2 `JwtAuthenticationFilter`

**File:** `com/codepulse/auth/security/JwtAuthenticationFilter.java`

```java
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        if (jwtService.isAccessTokenValid(token)) {
            String email = jwtService.extractEmail(token);
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);

            UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        filterChain.doFilter(request, response);
    }
}
```

**Things to handle:**
- The filter should NOT throw on an invalid token — it should just not set the `SecurityContext`. The downstream `JwtAuthEntryPoint` handles the 401.
- If the token is expired/invalid but syntactically present, log at `DEBUG` level (via MDC/correlation ID) and continue the chain without authentication. Don't leak why the token failed in the response body.

### 7.3 `JwtAuthEntryPoint`

**File:** `com/codepulse/auth/security/JwtAuthEntryPoint.java`

```java
@Component
public class JwtAuthEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("""
            {"success":false,"message":"Unauthorized","data":null}
        """);
    }
}
```

### 7.4 `SecurityConfig`

**File:** `com/codepulse/config/SecurityConfig.java`

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // enables @PreAuthorize on controller methods
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final JwtAuthEntryPoint jwtAuthEntryPoint;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(jwtAuthEntryPoint))
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("${cors.allowed-origins}"));  // from yml
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);   // required for httpOnly cookie refresh token
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
```

**Things to handle:**
- `@EnableMethodSecurity` — enables `@PreAuthorize`. Without this, role annotations on controllers are silently ignored. Don't forget it.
- `setAllowCredentials(true)` is required because the refresh token lives in an `httpOnly` cookie. Without this, the browser rejects the cookie.
- CORS allowed origins from `application.yml` — never `*` when credentials are involved (browsers reject this combination anyway).

---

## Step 8 — `AuthService` and `AuthController`

### 8.1 `AuthService`

**File:** `com/codepulse/auth/service/AuthService.java`

**User creation rule (enforced in `AuthService` or `UserService`):** if `role == CANDIDATE` and `rollNumber` is blank → throw `ValidationException("Roll number is mandatory for candidates")`.

**Login flow:**
1. Load user by email via `CustomUserDetailsService`. If not found → throw `ResourceNotFoundException` (becomes 404 from `GlobalExceptionHandler`) — or return a vague 401 (better for security; pick one and be consistent).
2. Verify password with `passwordEncoder.matches(raw, hash)`. If wrong → throw `UnauthorizedException`.
3. Generate access token via `JwtService.generateAccessToken(user)`.
4. Generate raw refresh token via `JwtService.generateRawRefreshToken()`.
5. Hash the raw token, save `RefreshToken` entity to DB.
6. Return `LoginResponse` with access token + `UserSummary`.
7. Set the raw refresh token in an `httpOnly`, `Secure`, `SameSite=Strict` cookie on the `HttpServletResponse`.

**Refresh flow:**
1. Read the `refresh_token` cookie from `HttpServletRequest`.
2. Hash it, look it up in `refresh_tokens` table.
3. If not found, revoked, or expired → throw `UnauthorizedException`.
4. Load the associated `User`.
5. **Rotate:** mark old token as `revoked = true`. Generate a new raw refresh token, save it, set new cookie.
6. Return new access token in `RefreshResponse`.

**Logout flow:**
1. Read the `refresh_token` cookie.
2. Hash it, find in DB, mark as `revoked = true` (or delete the row).
3. Expire the cookie by setting `Max-Age=0` on the response.
4. Return `ApiResponse.success("Logged out successfully")`.

```java
@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public LoginResponse login(LoginRequest request, HttpServletResponse response) { ... }
    public RefreshResponse refresh(HttpServletRequest request, HttpServletResponse response) { ... }
    public void logout(HttpServletRequest request, HttpServletResponse response) { ... }

    private void setRefreshCookie(HttpServletResponse response, String rawToken) {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", rawToken)
            .httpOnly(true)
            .secure(true)           // set false for local dev without HTTPS
            .sameSite("Strict")
            .path("/api/auth")      // scope cookie to auth endpoints only
            .maxAge(jwtService.getRefreshTokenExpirySeconds())
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", "")
            .httpOnly(true).secure(true).sameSite("Strict")
            .path("/api/auth").maxAge(0).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
```

### 8.2 `AuthController`

**File:** `com/codepulse/auth/controller/AuthController.java`

```java
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {

        LoginResponse data = authService.login(request, response);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(
            HttpServletRequest request,
            HttpServletResponse response) {

        RefreshResponse data = authService.refresh(request, response);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {

        authService.logout(request, response);
        return ResponseEntity.ok(ApiResponse.success(null, "Logged out successfully"));
    }
}
```

**Things to handle:**
- Every response goes through `ApiResponse<T>` — your Module 0 wrapper. No raw returns.
- `/api/auth/**` is `permitAll()` in `SecurityConfig` — all three endpoints are public (the logout endpoint validates the cookie internally, not via the JWT filter).
- `@Valid` on `LoginRequest` triggers your bean validation — `@NotBlank`, `@Email` are enforced automatically.

---

## Step 9 — Admin Seed Script

You cannot test anything without at least one user in the database. Do not hardcode credentials in code — use a Flyway migration that reads from environment variables or a fixed dev-only value clearly marked as "change before production."

**File:** `src/main/resources/db/migration/V3__seed_admin_user.sql`

```sql
-- Password: Admin@1234 (BCrypt hash below — change in production via the update endpoint)
-- Hash generated with BCrypt cost 12
INSERT INTO users (id, email, password_hash, full_name, role, roll_number, is_active, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'admin@codepulse.dev',
    '$2a$12$PUT_YOUR_BCRYPT_HASH_HERE',
    'System Admin',
    'ADMIN',
    TRUE,
    now(),
    now()
) ON CONFLICT (email) DO NOTHING;
```

**How to generate the BCrypt hash for the migration:**
- Write a one-off test or a quick `main` method:
```java
System.out.println(new BCryptPasswordEncoder(12).encode("Admin@1234"));
```
- Paste the output into the migration. Do NOT commit the plaintext password anywhere.

---

## Step 10 — Scheduled Refresh Token Cleanup

Expired refresh tokens pile up in the DB. Add a scheduled job to clean them.

**File:** `com/codepulse/auth/service/RefreshTokenCleanupService.java`

```java
@Service
@RequiredArgsConstructor
public class RefreshTokenCleanupService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Scheduled(cron = "0 0 3 * * *")   // 3 AM every day
    @Transactional
    public void deleteExpiredTokens() {
        refreshTokenRepository.deleteAllByExpiresAtBefore(Instant.now());
        log.info("Cleaned up expired refresh tokens");
    }
}
```

Add `@EnableScheduling` to your `SecurityConfig` or a separate `@Configuration` class.

---

## Step 11 — Unit Tests

Write these before calling the module done.

### `JwtServiceTest`

**File:** `src/test/java/.../auth/service/JwtServiceTest.java`

Cover these cases:
- `generateAccessToken()` → token is parseable and contains correct claims (`sub`, `role`, `userId`)
- `isAccessTokenValid()` → returns `true` for a fresh token
- `isAccessTokenValid()` → returns `false` for a tampered token (flip one character)
- `isAccessTokenValid()` → returns `false` for an expired token (set `expiry-seconds: 0` in test config)
- `hashRefreshToken()` → same input always produces same hash (deterministic SHA-256)

### `AuthServiceTest`

**File:** `src/test/java/.../auth/service/AuthServiceTest.java`

Use `@ExtendWith(MockitoExtension.class)`. Mock `UserRepository`, `RefreshTokenRepository`, `JwtService`, `PasswordEncoder`.

Cover:
- Login with correct credentials → returns `LoginResponse` with access token, sets cookie
- Login with wrong password → throws `UnauthorizedException`
- Login with unknown email → throws `ResourceNotFoundException` (or `UnauthorizedException` — pick one)
- Login with inactive account → `CustomUserDetailsService` throws `DisabledException`
- Refresh with valid token → rotates token, returns new access token
- Refresh with revoked token → throws `UnauthorizedException`
- Refresh with expired token → throws `UnauthorizedException`
- Logout → marks token revoked, clears cookie

### Integration Smoke Test (optional but recommended)

**File:** `src/test/java/.../auth/controller/AuthControllerIntegrationTest.java`

Use `@SpringBootTest` + `@AutoConfigureMockMvc` + a test H2/Testcontainers database.

Cover:
- `POST /api/auth/login` with valid credentials → 200, token in response body, cookie in response headers
- `POST /api/auth/refresh` with the returned cookie → 200, new access token
- `GET /api/some-protected-endpoint` without token → 401
- `GET /api/some-protected-endpoint` with valid token → 200

---

## Step 12 — Verify Against the Cross-Module Consistency Checklist

Before marking Module 1 done, check every item:

- [ ] `User` entity extends `BaseEntity`
- [ ] `AuthController` endpoints return `ApiResponse<T>`
- [ ] `UnauthorizedException`, `ResourceNotFoundException` extend the `AppException` hierarchy and are handled by `GlobalExceptionHandler`
- [ ] `SecurityConfig` uses env vars for JWT secrets — no hardcoded strings
- [ ] Flyway migrations are committed alongside the code that requires them (`V2__` before entity code, `V3__` for seed)
- [ ] `CorrelationIdFilter` (from Module 0) is in the filter chain and the trace ID appears in all auth-related log lines
- [ ] `RateLimitingFilter` (from Module 0) is protecting `/api/auth/login` from brute-force

---

## Definition of Done

- [ ] `POST /api/auth/login` returns access token + sets `httpOnly` refresh cookie
- [ ] `POST /api/auth/refresh` rotates the refresh token and returns a new access token
- [ ] `POST /api/auth/logout` revokes the refresh token and clears the cookie
- [ ] Any protected endpoint returns `401` with no token, `200` with a valid one
- [ ] Invalid/expired refresh token correctly returns `401` and does not issue a new access token
- [ ] Admin seed user exists and can log in after a clean `docker compose up`
- [ ] `JwtServiceTest` passes all cases above
- [ ] `AuthServiceTest` passes all cases above
- [ ] Expired refresh tokens are scheduled for cleanup

---

## Things That Can Go Wrong — Watch Out For These

| Pitfall | What to do |
|---|---|
| Cookie not being sent by browser | `setAllowCredentials(true)` in CORS config + cookie must have `SameSite=Strict` or `Lax` (not `None` unless HTTPS) |
| `Secure=true` on cookie breaks local dev (no HTTPS) | Use a `local` Spring profile that sets `secure: false` in cookie — read via `@Value("${cookie.secure:true}")` |
| `@EnableMethodSecurity` missing | `@PreAuthorize` annotations are silently ignored — all secured endpoints become open. Add it to `SecurityConfig`. |
| JWT secret too short | JJWT throws `WeakKeyException` at startup — use at least 32 random bytes (256 bits), base64-encoded |
| Storing raw refresh token in DB | Always store the hash. If someone reads your DB, they can't use the hashes to forge valid cookies. |
| Token in `localStorage` | Access token must be in memory (JS variable or React state) — never `localStorage` (XSS-accessible). Refresh token must be `httpOnly` cookie — never JS-accessible. |
| Returning `user.getPasswordHash()` in response | Audit every DTO. Make sure `password_hash` is never in a response. Easy to miss when copying fields from entity. |
| BCrypt cost factor too low | Use cost 12 (default BCryptPasswordEncoder uses 10). Barely slower at login, significantly harder to brute-force. |
| `@Transactional` missing on `AuthService` | The refresh flow does two DB writes (revoke old, save new) — if the second fails, you want the first rolled back. |

---

## Package Structure After This Module

```
com.codepulse
├── auth
│   ├── controller
│   │   └── AuthController.java
│   ├── dto
│   │   ├── LoginRequest.java
│   │   ├── LoginResponse.java
│   │   ├── RefreshResponse.java
│   │   └── RegisterUserRequest.java
│   ├── entity
│   │   └── RefreshToken.java
│   ├── repository
│   │   └── RefreshTokenRepository.java
│   ├── security
│   │   ├── CustomUserDetailsService.java
│   │   ├── JwtAuthenticationFilter.java
│   │   └── JwtAuthEntryPoint.java
│   └── service
│       ├── AuthService.java
│       ├── JwtService.java
│       └── RefreshTokenCleanupService.java
├── common
│   └── enums
│       └── Role.java              ← add here if not already done
├── config
│   └── SecurityConfig.java
└── user
    ├── dto
    │   └── UserSummary.java
    ├── entity
    │   └── User.java
    └── repository
        └── UserRepository.java
```

---

## What Module 2 Needs From You

When you move to Module 2 (User Management), the following must already work:
- `UserRepository.findByEmail()` and `UserRepository.existsByEmail()` (you built these in Step 4)
- `PasswordEncoder` bean (declared in `SecurityConfig`)
- `Role` enum (declared in Step 2)
- `RegisterUserRequest` DTO (you built this in Step 5 — Module 2 reuses it)
- `@PreAuthorize` + `@EnableMethodSecurity` active (Step 7.4)
- A working Admin account to call the Module 2 endpoints with
