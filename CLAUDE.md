# CLAUDE.md — ViVuMate (core-api)

> Đọc file này TRƯỚC KHI làm bất kỳ task nào.

## Project Overview

ViVuMate là nền tảng mạng xã hội du lịch (travel social platform) với tính năng chat realtime.
Backend sử dụng **Polyglot Persistence**: PostgreSQL (user/auth data) + MongoDB (chat data) + Redis (caching/token/OTP).

- **Status**: In-progress (xây dựng MVP)
- **Java**: 21  |  **Spring Boot**: 3.5.9  |  **PostgreSQL**: 16 (PostGIS)  |  **MongoDB**: 7.0  |  **Redis**: 7.2

---

## Tech Stack

| Layer | Technology | Version | Ghi chú |
|---|---|---|---|
| Framework | Spring Boot | 3.5.9 | Servlet stack (spring-boot-starter-web) |
| JPA/ORM | Spring Data JPA + Hibernate | (managed) | PostgreSQL — ddl-auto: update (dev) |
| MongoDB | Spring Data MongoDB | (managed) | Blocking driver, SnakeCaseFieldNamingStrategy |
| Redis | Spring Data Redis | (managed) | Cache + Token blacklist + OTP storage |
| Security | Spring Security + JWT (jjwt) | 0.12.3 | Stateless, multi-key JWT |
| Validation | Jakarta Validation (Hibernate Validator) | (managed) | @Valid trên @RequestBody |
| API Docs | SpringDoc OpenAPI (Swagger UI) | 2.8.15 | `/swagger-ui.html` |
| Email | Spring Boot Starter Mail + Thymeleaf | (managed) | Template-based HTML email, @Async |
| WebClient | Spring WebFlux (WebClient only) | (managed) | Chỉ dùng cho external API call (Weather) |
| WebSocket | Spring Boot Starter WebSocket | (managed) | Chưa implement logic |
| Actuator | Spring Boot Actuator | (managed) | Expose: health |
| Lombok | Lombok | (managed) | @Getter, @Setter, @Builder, @RequiredArgsConstructor, @Slf4j |
| Docker | Spring Boot Docker Compose | (managed) | disabled by default |
| Test | JUnit 5 + Spring Security Test + Reactor Test | (managed) | Chưa có test thực tế |

> **Lưu ý:** Project dùng `spring-boot-starter-web` (Servlet/Blocking), KHÔNG phải WebFlux reactive stack. `spring-boot-starter-webflux` chỉ được thêm để dùng **WebClient** cho external HTTP calls.

---

## Architecture & Package Structure

```
src/main/java/com/vivumate/coreapi/
├── ViVuMateApplication.java          # @SpringBootApplication, @EnableAsync
├── config/                           # Spring configuration classes
│   ├── AppConfig.java                # PasswordEncoder (BCrypt)
│   ├── AuditorAwareImpl.java         # JPA Auditing — getCurrentUser từ SecurityContext
│   ├── DataSeeder.java               # CommandLineRunner — seed Roles, Permissions, Admin/User
│   ├── I18nConfig.java               # Locale resolver (Accept-Language header), MessageSource
│   ├── JpaConfig.java                # @EnableJpaAuditing
│   ├── OpenApiConfig.java            # Swagger/OpenAPI config với Bearer auth
│   ├── RedisConfig.java              # RedisTemplate, CacheManager, JSON serializer
│   ├── SecurityConfig.java           # SecurityFilterChain, public/protected endpoints
│   └── WebClientConfig.java          # WebClient bean cho external API calls
├── controller/                       # REST controllers (@RestController)
│   ├── AuthenticationController.java # /api/v1/auth/**
│   ├── UserController.java           # /api/v1/users/**
│   ├── WeatherController.java        # /api/v1/weather/**
│   └── TestController.java           # /api/v1/admin/** (testing endpoints)
├── service/                          # Service interfaces
│   ├── AuthenticationService.java
│   ├── UserService.java
│   ├── EmailService.java
│   ├── RedisService.java
│   ├── TokenBlacklistService.java    # Concrete class (không có interface)
│   ├── WeatherService.java           # Concrete class (không có interface)
│   └── impl/                         # Service implementations
│       ├── AuthenticationServiceImpl.java
│       ├── EmailServiceImpl.java
│       ├── RedisServiceImpl.java
│       └── UserServiceImpl.java
├── repository/                       # Spring Data JPA repositories
│   ├── UserRepository.java
│   ├── RoleRepository.java
│   ├── PermissionRepository.java
│   └── RefreshTokenRepository.java
├── entity/                           # JPA entities (PostgreSQL)
│   ├── BaseEntity.java               # id, createdAt, updatedAt, lastModifiedBy, deletedAt
│   ├── User.java                     # implements UserDetails
│   ├── Role.java
│   ├── Permission.java
│   ├── RefreshToken.java
│   └── enums/
│       ├── AuthProvider.java          # LOCAL, GOOGLE, FACEBOOK, GITHUB
│       ├── Gender.java                # MALE, FEMALE, OTHER
│       ├── PermissionCode.java        # USER_READ, POST_CREATE, LOCATION_VIEW, ...
│       ├── TokenType.java             # ACCESS_TOKEN, REFRESH_TOKEN, RESET_TOKEN, VERIFY_TOKEN
│       └── UserStatus.java           # ACTIVE, INACTIVE, BANNED
├── document/                         # MongoDB documents (Chat module)
│   ├── BaseDocument.java             # id (ObjectId), createdAt, updatedAt, deletedAt (Instant)
│   ├── ConversationDocument.java     # collection: "conversations"
│   ├── MessageDocument.java          # collection: "messages"
│   ├── enums/
│   │   ├── ContentType.java          # TEXT, IMAGE, FILE, VIDEO, AUDIO, LINK_PREVIEW, SYSTEM
│   │   ├── ConversationType.java     # DIRECT, GROUP
│   │   ├── MentionType.java          # USER, EVERYONE, ROLE
│   │   ├── ParticipantRole.java      # OWNER, ADMIN, MEMBER
│   │   └── SystemEvent.java          # MEMBER_JOINED, MEMBER_LEFT, MEMBER_REMOVED, ...
│   └── subdoc/                       # Embedded sub-documents
│       ├── ConversationSettings.java
│       ├── EditHistoryEntry.java
│       ├── LastMessagePreview.java
│       ├── LinkPreview.java
│       ├── MediaInfo.java
│       ├── Mention.java
│       ├── MessageContent.java
│       ├── Participant.java
│       ├── ReplyToSnapshot.java
│       └── SenderSnapshot.java
├── dto/
│   ├── request/                      # Request DTOs (Lombok @Getter class + Jakarta validation)
│   │   ├── AuthenticationRequest.java
│   │   ├── ChangePasswordRequest.java
│   │   ├── ForgotPasswordRequest.java
│   │   ├── RefreshTokenRequest.java
│   │   ├── ResendVerificationRequest.java
│   │   ├── ResetPasswordRequest.java
│   │   ├── UserCreationRequest.java
│   │   ├── UserUpdateRequest.java
│   │   ├── VerifyEmailRequest.java
│   │   └── VerifyLoginOtpRequest.java
│   └── response/                     # Response DTOs (Lombok @Builder class)
│       ├── ApiResponse.java          # Wrapper chuẩn: { code, message, data }
│       ├── AuthenticationResponse.java
│       ├── PageResponse.java         # { currentPage, totalPage, pageSize, totalElements, data }
│       ├── UserMiniResponse.java
│       ├── UserResponse.java
│       └── WeatherResponse.java
├── mapper/
│   └── UserMapper.java              # Static methods, manual mapping (KHÔNG dùng MapStruct)
├── exception/
│   ├── AppException.java            # extends RuntimeException, chứa ErrorCode
│   ├── ErrorCode.java               # Enum: code (int) + messageKey (i18n) + HttpStatus
│   └── GlobalExceptionHandler.java  # @RestControllerAdvice — xử lý tất cả exceptions
├── security/
│   ├── CustomUserDetailsService.java # Load user by username/email, check status
│   ├── JwtAuthenticationEntryPoint.java
│   ├── JwtAuthenticationFilter.java  # OncePerRequestFilter — parse JWT from claims (no DB call)
│   └── JwtUtils.java                # Multi-key JWT: access, refresh, reset, verify
└── util/
    └── Translator.java              # i18n message resolver via MessageSource
```

**Pattern**: Layered Architecture (Controller → Service Interface → ServiceImpl → Repository)

---

## Naming Conventions

### Class Naming (từ code thực)
| Type | Pattern | Ví dụ |
|---|---|---|
| Entity (JPA) | `PascalCase` | `User`, `Role`, `Permission`, `RefreshToken` |
| Document (Mongo) | `PascalCase` + `Document` suffix | `ConversationDocument`, `MessageDocument` |
| Base classes | `Base` prefix | `BaseEntity`, `BaseDocument` |
| Controller | `PascalCase` + `Controller` suffix | `AuthenticationController`, `UserController` |
| Service Interface | `PascalCase` + `Service` suffix | `AuthenticationService`, `UserService` |
| Service Impl | `PascalCase` + `ServiceImpl` suffix | `AuthenticationServiceImpl`, `UserServiceImpl` |
| Repository | `PascalCase` + `Repository` suffix | `UserRepository`, `RoleRepository` |
| Request DTO | `PascalCase` + `Request` suffix | `UserCreationRequest`, `AuthenticationRequest` |
| Response DTO | `PascalCase` + `Response` suffix | `UserResponse`, `ApiResponse`, `PageResponse` |
| Mapper | `PascalCase` + `Mapper` suffix | `UserMapper` |
| Enum | `PascalCase` | `UserStatus`, `TokenType`, `ContentType` |

### Field Naming
- **Java fields (Entity/DTO)**: `camelCase` — `fullName`, `avatarUrl`, `dateOfBirth`
- **Database columns (PostgreSQL)**: `snake_case` via `@Column(name = "full_name")`
- **MongoDB fields**: `snake_case` (auto via `SnakeCaseFieldNamingStrategy` trong application-dev.yml)
- **JSON response fields**: `snake_case` via `@JsonProperty("full_name")`, `@JsonProperty("avatar_url")`
- **Table naming**: Prefix `tbl_` — `tbl_users`, `tbl_roles`, `tbl_permissions`, `tbl_refresh_tokens`
- **MongoDB collections**: Plural lowercase — `conversations`, `messages`

### API Endpoint Pattern
```
/api/v1/{resource}          — RESTful
/api/v1/auth/**             — Public (white-listed)
/api/v1/public/**           — Public (white-listed)
/api/v1/users/me            — Current authenticated user
/api/v1/users/{id}          — Resource by ID
/api/v1/users/username/{username} — Resource by username
/api/v1/admin/**            — Admin endpoints
```

### Logging
- Sử dụng `@Slf4j(topic = "...")` với topic name viết hoa: `AUTHENTICATION_CONTROLLER`, `USER_SERVICE`, `JWT_AUTHENTICATION_FILTER`
- Pattern log: `(Attempt)`, `(Success)`, `(Failed)` prefix cho action tracking

---

## DTO & Response Pattern

### Standard API Response Wrapper
```java
// Mọi API đều trả về ApiResponse<T>
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private int code;       // 200 = success, 1xxx = business error, 9xxx = system error
    private String message; // i18n message hoặc "success"
    private T data;         // null khi error

    public static <T> ApiResponse<T> success(T data) { ... }
    public static <T> ApiResponse<T> error(int code, String message) { ... }
}
```

### Request DTO Pattern (Lombok class + Jakarta Validation)
```java
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserCreationRequest {
    @NotBlank(message = "Email is required")
    @Email(message = "Email format is invalid")
    private String email;

    @NotBlank(message = "Full name is required")
    @Size(max = 100, message = "Full name must not exceed 100 characters")
    private String fullName;

    private Gender gender;
    private LocalDate dateOfBirth;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters.")
    private String password;
}
```

### Response DTO Pattern (Lombok class + @JsonProperty for snake_case)
```java
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserResponse {
    private Long id;
    private String username;
    private String email;
    @JsonProperty("full_name")  private String fullName;
    @JsonProperty("avatar_url") private String avatarUrl;
    @JsonProperty("created_at") private LocalDateTime createdAt;
    // ...
}
```

### Pagination Response
```java
public class PageResponse<T> {
    private int currentPage;     // 1-based (controller nhận 1-based, convert sang 0-based cho Pageable)
    private int totalPage;
    private int pageSize;
    private long totalElements;
    private List<T> data;
}
```

---

## Service Layer Pattern

### Interface + Impl Pattern (blocking)
```java
// Interface
public interface UserService {
    UserResponse getMyProfile();
    UserResponse updateMyProfile(UserUpdateRequest request);
    void changePassword(ChangePasswordRequest request);
    PageResponse<UserMiniResponse> searchUsers(String keyword, int page, int size);
}

// Implementation
@Service
@RequiredArgsConstructor
@Slf4j(topic = "USER_SERVICE")
@Transactional(readOnly = true)          // Class-level readOnly
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserResponse getMyProfile() {
        User user = getCurrentUserManaged();          // Lấy từ SecurityContext → findById
        return UserMapper.toUserResponse(user);       // Manual mapping
    }

    @Transactional                                    // Override cho write methods
    @Override
    public UserResponse updateMyProfile(UserUpdateRequest request) {
        User user = getCurrentUserManaged();
        // Null-check từng field trước khi set
        if (request.getFullName() != null) user.setFullName(request.getFullName());
        // ...
        return UserMapper.toUserResponse(userRepository.save(user));
    }
}
```

### Error Handling Pattern
```java
// Throw AppException với ErrorCode enum
throw new AppException(ErrorCode.USER_NOT_FOUND);
throw new AppException(ErrorCode.EMAIL_EXISTED);

// ErrorCode enum chứa: code (int), messageKey (i18n key), httpStatus
UNCATEGORIZED_EXCEPTION(9999, "error.internal", HttpStatus.INTERNAL_SERVER_ERROR),
USER_NOT_FOUND(1011, "error.user.notfound", HttpStatus.NOT_FOUND),
```

### Get Current User Pattern
```java
private User getAuthenticatedUser() {
    return (User) SecurityContextHolder.getContext()
            .getAuthentication()
            .getPrincipal();
}

private User getCurrentUserManaged() {
    Long userId = getAuthenticatedUser().getId();
    return userRepository.findById(userId)
            .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
}
```

---

## Database — PostgreSQL (JPA)

### Tables (từ @Table annotations)
| Entity | Table | Soft Delete | Auditing |
|---|---|---|---|
| `User` | `tbl_users` | ✅ `@SQLDelete` + `@SQLRestriction` | ✅ via BaseEntity |
| `Role` | `tbl_roles` | ❌ | ✅ |
| `Permission` | `tbl_permissions` | ❌ | ✅ |
| `RefreshToken` | `tbl_refresh_tokens` | ❌ (revoke flag) | ✅ |
| Join: User↔Role | `tbl_users_roles` | — | — |
| Join: Role↔Permission | `tbl_roles_permissions` | — | — |

### BaseEntity (Auditing)
```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate    private LocalDateTime createdAt;
    @LastModifiedDate private LocalDateTime updatedAt;
    @LastModifiedBy private String lastModifiedBy;
    protected LocalDateTime deletedAt;  // Soft delete
}
```

### Soft Delete (User entity)
```java
@SQLDelete(sql = "update tbl_users set deleted_at = NOW() where id = ?")
@SQLRestriction("deleted_at is null")
```

### Repository Custom Queries
```java
// JPQL projection query — trả về DTO trực tiếp
@Query("select new com.vivumate.coreapi.dto.response.UserMiniResponse(...) from User u where u.id in :ids")
List<UserMiniResponse> findChatMembersByIds(@Param("ids") List<Long> ids);

// Native query cho restore soft-deleted
@Modifying
@Query(value = "UPDATE tbl_users SET deleted_at = NULL, status = 'ACTIVE' WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
int restoreById(@Param("id") Long id);

// JPQL bulk update
@Modifying
@Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.user = :user AND rt.revoked = false")
void revokeAllByUser(@Param("user") User user);
```

---

## Database — MongoDB (Chat Module)

### Collections (từ @Document annotations)
| Document | Collection | Patterns |
|---|---|---|
| `ConversationDocument` | `conversations` | Computed, Subset, Extended Reference |
| `MessageDocument` | `messages` | Polymorphic, Extended Reference |

### BaseDocument (MongoDB)
```java
public abstract class BaseDocument {
    @Id private ObjectId id;           // MongoDB ObjectId, KHÔNG phải Long
    @CreatedDate  private Instant createdAt;  // Instant (UTC), KHÔNG phải LocalDateTime
    @LastModifiedDate private Instant updatedAt;
    private Instant deletedAt;
}
```

### Indexes (từ @CompoundIndex và @Indexed)
```
conversations:
  - idx_user_conversations_latest: {participant_ids: 1, last_activity_at: -1}
  - idx_type_activity: {type: 1, last_activity_at: -1}
  - idx_dm_pair_unique: {dmPair: 1} — unique, sparse

messages:
  - idx_conversation_messages_cursor: {conversation_id: 1, _id: -1}
  - idx_sender_messages: {sender.user_id: 1, created_at: -1}
```

### MongoDB Field Naming Strategy
```yaml
# application-dev.yml
spring.data.mongodb.field-naming-strategy: org.springframework.data.mapping.model.SnakeCaseFieldNamingStrategy
```
→ Java `camelCase` fields tự động map sang `snake_case` trong MongoDB.

---

## Security & Authentication

### JWT Structure
- **4 loại token**, mỗi loại dùng **secret key riêng**:
  - `ACCESS_TOKEN` — 30 phút, chứa claims: `userId`, `authorities`, `sub` (username)
  - `REFRESH_TOKEN` — 7 ngày, chỉ chứa `sub`
  - `RESET_TOKEN` — 5 phút, chỉ chứa `sub`
  - `VERIFY_TOKEN` — 15 phút, chỉ chứa `sub`

### JWT Filter Flow (không query DB)
1. Extract token từ `Authorization: Bearer <token>` header
2. Check blacklist (Redis) → reject nếu revoked
3. Parse claims từ JWT (không load UserDetails từ DB)
4. Build `User` object từ claims → set vào SecurityContext

### Protected vs Public Endpoints
```java
// WHITE_LIST (permitAll):
"/api/v1/auth/**", "/api/v1/public/**", "/actuator/**",
"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error"

// Tất cả routes khác: .anyRequest().authenticated()
// Admin routes: @PreAuthorize("hasRole('ADMIN')") trên method
// Permission check: @PreAuthorize("hasAuthority('LOCATION_MANAGE')")
```

### Suspicious Login Detection
- Nếu `lastSeen` > 30 ngày hoặc null → yêu cầu OTP qua email
- OTP 6 chữ số, lưu Redis, TTL 5 phút

### Authority Format
```
ROLE_ADMIN, ROLE_USER          — Role-based
USER_READ, POST_CREATE, ...    — Permission-based (từ PermissionCode enum)
```

---

## External Services

### Redis
| Key Pattern | Mục đích | TTL |
|---|---|---|
| `BLACKLIST_TOKEN:<jwt>` | Access token blacklist (logout) | Remaining token TTL |
| `reset_pwd:<username>` | Reset password token | 5 phút |
| `cooldown_reset:<email>` | Rate limit (forgot password, resend) | 60 giây |
| `verify_email:<username>` | Email verification token | 15 phút |
| `login_otp:<email>` | Login OTP code | 5 phút |
| `weather::<lat>-<lon>-<lang>` | Weather cache (Spring Cache) | 15 phút (default) |

### Email (Resend SMTP)
- Provider: `smtp.resend.com:587` (TLS)
- Templates: Thymeleaf HTML — `reset-password.html`, `verify-email.html`, `login-otp.html`
- Gửi async via `@Async`
- From: `onboarding@resend.dev` / "ViVuMate Security"

### Weather API
- Provider: OpenWeatherMap (`api.openweathermap.org/data/2.5/weather`)
- Cached via `@Cacheable(value = "weather")` trên Redis
- Dùng `WebClient` (blocking `.block()`)

---

## Error Handling

### Exception Hierarchy
```
RuntimeException
  └── AppException (chứa ErrorCode enum)

ErrorCode enum:
  1001-1023 — Business/Validation errors
  2001-2002 — External API errors (Weather)
  3001      — Email errors
  9999      — Uncategorized system error
```

### GlobalExceptionHandler — Xử lý toàn bộ
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    // Business: AppException → buildErrorResponse(errorCode)
    // Validation: ConstraintViolation, MethodArgumentNotValid, HandlerMethodValidation
    // Security: AccessDenied, BadCredentials, LockedException, DisabledException
    // JWT: SignatureException, MalformedJwt, ExpiredJwt
    // 404: NoResourceFound, NoHandlerFound
    // Fallback: Exception → UNCATEGORIZED_EXCEPTION

    private ResponseEntity<ApiResponse<Void>> buildErrorResponse(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus())
            .body(ApiResponse.error(errorCode.getCode(), translator.toLocale(errorCode.getMessageKey())));
    }
}
```

### Response Format (Error)
```json
{
  "code": 1011,
  "message": "User not found"
}
```

### Response Format (Success)
```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

---

## Internationalization (i18n)

- Locale detection: `Accept-Language` header (default: `en-US`)
- Message files: `src/main/resources/i18n/messages.properties` (EN), `messages_vi.properties` (VI)
- Usage: `translator.toLocale("error.user.notfound")` → trả message theo locale
- Error messages trong `ErrorCode` sử dụng `messageKey` → i18n message

---

## Testing Pattern

- Test framework: JUnit 5 (spring-boot-starter-test), Spring Security Test, Reactor Test
- **Chưa có test implementation thực tế** — chỉ có default test file từ Spring Initializr
- Test dependencies đã được khai báo trong pom.xml

---

## Environment & Running

### Prerequisites
- Java 21
- Docker (cho PostgreSQL, MongoDB, Redis)

### Khởi chạy
```bash
# 1. Start infrastructure
docker-compose up -d

# 2. Run Spring Boot
./mvnw spring-boot:run
# Hoặc
mvn spring-boot:run

# 3. Access
# API:     http://localhost:8080
# Swagger: http://localhost:8080/swagger-ui.html
# Health:  http://localhost:8080/actuator/health
```

### Profiles
| Profile | File | Mô tả |
|---|---|---|
| `dev` (default) | `application-dev.yml` | Local dev — ddl-auto: update, show-sql: true |
| `docker` | `application-docker.yml` | Docker network |
| `prod` | `application-prod.yml` | Production |

### Docker Services
| Service | Image | Port |
|---|---|---|
| PostgreSQL | `postgis/postgis:16-3.4` | 5432 |
| MongoDB | `mongo:7.0` | 27017 |
| Redis | `redis:7.2-alpine` | 6379 |

---

## Critical Rules — DO & DON'T

### ✅ DO
- **Luôn return `ApiResponse<T>`** từ controller — dùng `ApiResponse.success(data)` hoặc `ApiResponse.error(code, msg)`
- **Throw `AppException(ErrorCode.XXX)`** cho business errors — KHÔNG throw raw exceptions
- **Dùng `@Transactional(readOnly = true)`** ở class-level cho service, override bằng `@Transactional` cho write methods
- **Null-check từng field** khi update — pattern partial update (không dùng `@DynamicUpdate`)
- **Dùng `@JsonProperty("snake_case")`** trên response DTO fields — giữ JSON output snake_case
- **Dùng `@Slf4j(topic = "...")`** với topic UPPER_CASE mô tả class
- **Dùng `translator.toLocale(key)`** cho user-facing messages — KHÔNG hardcode string
- **Dùng `UserMapper.toUserResponse(user)`** cho entity-to-DTO — static method, KHÔNG dùng MapStruct
- **Pagination 1-based** từ controller, convert `page - 1` khi tạo `PageRequest`
- **Enum values** đặt UPPER_CASE: `ACTIVE`, `MALE`, `ACCESS_TOKEN`
- **MongoDB documents** dùng `Instant` (UTC) cho timestamps, JPA entities dùng `LocalDateTime`
- **PostgreSQL table names** prefix `tbl_` — `tbl_users`, `tbl_roles`

### ❌ DON'T
- **KHÔNG dùng reactive/.block()** trong service logic — project là Servlet stack, `.block()` chỉ dùng trong `WeatherService` (external API call)
- **KHÔNG query DB trong JWT filter** — authentication dựa hoàn toàn trên JWT claims
- **KHÔNG trả raw entity** từ controller — luôn map sang response DTO
- **KHÔNG dùng `@Data`** cho entity — dùng `@Getter @Setter` riêng (Lombok best practice cho JPA)
- **KHÔNG dùng Java Record** cho DTO — project dùng Lombok class pattern
- **KHÔNG dùng MapStruct** — project dùng manual static mapper
- **KHÔNG hardcode error messages** — dùng `ErrorCode` enum + i18n `messageKey`
- **KHÔNG dùng `@CreatedBy`** — chỉ dùng `@LastModifiedBy` trong BaseEntity
- **KHÔNG mix LocalDateTime và Instant** trong cùng một layer: JPA entities dùng `LocalDateTime`, MongoDB documents dùng `Instant`

---

## Key Files — Đọc khi cần

| Mục đích | File |
|---|---|
| Entry point | `src/main/java/com/vivumate/coreapi/ViVuMateApplication.java` |
| Security config | `src/main/java/com/vivumate/coreapi/config/SecurityConfig.java` |
| JWT logic | `src/main/java/com/vivumate/coreapi/security/JwtUtils.java` |
| JWT filter | `src/main/java/com/vivumate/coreapi/security/JwtAuthenticationFilter.java` |
| Global error handler | `src/main/java/com/vivumate/coreapi/exception/GlobalExceptionHandler.java` |
| Error codes | `src/main/java/com/vivumate/coreapi/exception/ErrorCode.java` |
| API response wrapper | `src/main/java/com/vivumate/coreapi/dto/response/ApiResponse.java` |
| Auth service | `src/main/java/com/vivumate/coreapi/service/impl/AuthenticationServiceImpl.java` |
| User service | `src/main/java/com/vivumate/coreapi/service/impl/UserServiceImpl.java` |
| User entity | `src/main/java/com/vivumate/coreapi/entity/User.java` |
| Base entity (JPA) | `src/main/java/com/vivumate/coreapi/entity/BaseEntity.java` |
| Base document (Mongo) | `src/main/java/com/vivumate/coreapi/document/BaseDocument.java` |
| Chat documents | `src/main/java/com/vivumate/coreapi/document/ConversationDocument.java` |
| Redis config | `src/main/java/com/vivumate/coreapi/config/RedisConfig.java` |
| Application config | `src/main/resources/application.yml` |
| Dev profile | `src/main/resources/application-dev.yml` |
| Docker compose | `docker-compose.yml` |
| i18n messages (EN) | `src/main/resources/i18n/messages.properties` |
| Data seeder | `src/main/java/com/vivumate/coreapi/config/DataSeeder.java` |

---

*Tự động tạo từ codebase — 2026-04-02*
