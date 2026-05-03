# 🏗️ ViVuMate — System Architecture Design v2.0 (Production-Grade)

> **Mục tiêu**: Kiến trúc backend hoàn chỉnh cho ứng dụng chat real-time phục vụ **hàng triệu người dùng**, với hiệu năng cao, dễ mở rộng, độ sẵn sàng cao (HA), bảo mật đa lớp, và khả năng vận hành production.
>
> **Phiên bản**: 2.0 | **Cập nhật**: 2025 | **Tech Stack**: Java 21 + Spring Boot 3.5 + Kubernetes

---

## Mục Lục

1. [Non-Functional Requirements (SLA)](#1-non-functional-requirements-sla)
2. [Architecture Decision Records (ADR)](#2-architecture-decision-records-adr)
3. [Tổng Quan Kiến Trúc](#3-tổng-quan-kiến-trúc-high-level-architecture)
4. [Tech Stack](#4-tech-stack-production-grade)
5. [Chiến Lược Lưu Trữ — Polyglot Persistence](#5-chiến-lược-lưu-trữ--polyglot-persistence)
6. [Database Schema Chi Tiết](#6-database-schema-chi-tiết)
7. [API Contract (REST + WebSocket)](#7-api-contract-rest--websocket)
8. [Message Journey & Processing Pipeline](#8-hành-trình-của-một-tin-nhắn)
9. [Multi-Device Sync](#9-đồng-bộ-đa-thiết-bị)
10. [Group Chat Flow](#10-luồng-nhóm-chat)
11. [State Management (Presence & Message Status)](#11-quản-lý-trạng-thái)
12. [Cross-Server Routing](#12-định-tuyến-tin-nhắn-giữa-các-server)
13. [Fault Tolerance & Resilience](#13-khả-năng-chịu-lỗi--phục-hồi)
14. [Scaling Strategy](#14-mở-rộng-quy-mô-scaling-strategy)
15. [Security Hardening](#15-bảo-mật-security-hardening)
16. [Environment Configuration](#16-environment-configuration)
17. [Kafka Topic Design](#17-kafka-topic-design)
18. [Error Handling Strategy](#18-error-handling-strategy)
19. [Testing Strategy](#19-testing-strategy)
20. [DevOps & Infrastructure](#20-devops--infrastructure)
21. [Danh Sách Bài Toán & Ưu Tiên](#21-danh-sách-bài-toán--ưu-tiên)
22. [Implementation Roadmap](#22-implementation-roadmap--step-by-step)
23. [Dependencies (pom.xml)](#23-tổng-kết-dependencies)

---

## 1. Non-Functional Requirements (SLA)

> Xác định rõ SLA trước khi thiết kế — đây là thước đo để đánh giá mọi quyết định kiến trúc.

### 1.1 Performance Targets

| Metric | Target | Measurement Method |
|--------|--------|--------------------|
| **P50 message latency** (gửi → nhận) | < 50ms | Prometheus histogram |
| **P99 message latency** | < 200ms | Prometheus histogram |
| **P99 API response time** | < 300ms | Micrometer + Grafana |
| **WebSocket connection setup** | < 500ms | Client-side timing |
| **Message throughput** | 100K msg/s peak | Kafka + load test |
| **Concurrent WS connections** | 1M per cluster | K8s HPA |
| **API requests/second** | 50K req/s | Gatling benchmark |
| **Search latency (full-text)** | < 500ms | Elasticsearch slow log |

### 1.2 Reliability & Availability

| Metric | Target | Strategy |
|--------|--------|----------|
| **Availability** (uptime) | 99.9% (~8.7h/year downtime) | Multi-AZ deployment, HA databases |
| **RTO** (Recovery Time Objective) | < 30 seconds | K8s auto-restart, client reconnect |
| **RPO** (Recovery Point Objective) | 0 messages lost | Kafka durability + MongoDB write concern |
| **Message delivery guarantee** | At-least-once | Idempotent write + client dedup |
| **Data durability** | 99.999999% (8 nines) | MongoDB Replica Set + S3 cross-region |

### 1.3 Scalability Targets

| Metric | Phase 1 (MVP) | Phase 3 (Growth) | Phase 5 (Scale) |
|--------|--------------|-----------------|----------------|
| **Registered users** | 10K | 1M | 10M+ |
| **DAU** (Daily Active Users) | 1K | 100K | 1M+ |
| **Messages/day** | 1M | 100M | 1B+ |
| **Chat Server instances** | 2 | 10 | 20–50 |
| **Storage (messages)** | 10GB | 1TB | 10TB+ |

---

## 2. Architecture Decision Records (ADR)

> Ghi lại lý do chọn công nghệ — giúp team mới hiểu context, tránh quyết định lại.

### ADR-001: Chọn MongoDB cho Chat Data thay vì PostgreSQL

- **Quyết định**: Dùng MongoDB cho messages, conversations
- **Lý do**: Chat data là document-oriented (message có nhiều loại content khác nhau), write-heavy (>80% operations là write), cần flexible schema cho attachments/reactions; MongoDB sharding native hỗ trợ horizontal scale dễ hơn PostgreSQL partitioning
- **Đánh đổi**: Không có ACID cross-document transactions; phải tự quản lý referential integrity
- **Phương án thay thế đã xét**: PostgreSQL với JSONB column — bị loại vì performance kém hơn ở scale lớn

### ADR-002: Chọn Redis Pub/Sub thay vì RabbitMQ cho Cross-Server Routing

- **Quyết định**: Redis Pub/Sub cho inter-server WS message routing
- **Lý do**: Đã có Redis trong stack; latency < 1ms (in-memory); đơn giản hơn RabbitMQ; message không cần durable (WS delivery là best-effort)
- **Đánh đổi**: Redis Pub/Sub không persistent — nếu server chưa subscribe thì mất message; không có dead-letter queue
- **Giảm thiểu rủi ro**: Kafka làm backup durable channel; client sync on reconnect

### ADR-003: Chọn Kafka thay vì RabbitMQ cho Event Streaming

- **Quyết định**: Apache Kafka cho async events (notifications, search indexing, analytics)
- **Lý do**: Durable event log (replay được); high throughput (millions msg/s); consumer groups linh hoạt; tích hợp tốt với Spring
- **Đánh đổi**: Operational complexity cao hơn; cần Zookeeper/KRaft
- **Phương án thay thế**: RabbitMQ — phù hợp nếu throughput thấp hơn, nhưng không có event replay

### ADR-004: Stateless Chat Server + Redis Session Store

- **Quyết định**: Chat Server stateless, dùng Redis lưu ws_routing
- **Lý do**: Horizontal scaling dễ dàng; Load Balancer chỉ cần sticky session cho WS lifetime; server crash không mất routing state
- **Đánh đổi**: Mọi routing lookup đều cần Redis round-trip (~0.5ms); Redis trở thành SPOF nếu không HA
- **Giảm thiểu**: Redis Cluster mode (3 master + 3 replica); circuit breaker nếu Redis down

### ADR-005: Spring WebSocket + STOMP thay vì raw WebSocket

- **Quyết định**: STOMP protocol over WebSocket
- **Lý do**: STOMP cung cấp pub/sub semantics, headers, destination routing built-in; Spring Security tích hợp native; SockJS fallback cho môi trường không hỗ trợ WS
- **Đánh đổi**: Overhead nhỏ hơn raw WS; client cần STOMP library

---

## 3. Tổng Quan Kiến Trúc (High-Level Architecture)

```mermaid
graph TB
    subgraph "Client Layer"
        MOB["📱 Mobile App<br/>(Android/iOS)"]
        WEB["🌐 Web App<br/>(React/Flutter Web)"]
    end

    subgraph "Edge Layer"
        CDN["☁️ CDN<br/>(CloudFront/Cloudflare)<br/>Static assets, media CDN"]
        LB["⚖️ Load Balancer<br/>(Nginx / AWS ALB)<br/>Sticky Session (WS)<br/>TLS Termination"]
        WAF["🛡️ WAF<br/>(Cloudflare / AWS WAF)<br/>DDoS, bot protection"]
    end

    subgraph "API Gateway Layer"
        GW["🚪 API Gateway<br/>(Spring Cloud Gateway)<br/>Rate Limit · JWT Auth · Routing<br/>CORS · Request logging"]
    end

    subgraph "Application Layer — Chat Cluster"
        CS1["💬 Chat Server 1<br/>(Spring Boot + STOMP WS)<br/>Java 21 Virtual Threads"]
        CS2["💬 Chat Server 2<br/>(Spring Boot + STOMP WS)<br/>Java 21 Virtual Threads"]
        CS3["💬 Chat Server N<br/>(Spring Boot + STOMP WS)<br/>Java 21 Virtual Threads"]
    end

    subgraph "Application Layer — REST Microservices"
        AUTH["🔐 Auth Service<br/>(JWT/OAuth2/OTP)"]
        USER["👤 User Service<br/>(Profile/Friend/Block)"]
        MEDIA["🖼️ Media Service<br/>(Upload/Resize/Presign)"]
        NOTIFY["🔔 Notification Service<br/>(FCM/APNs/Email)"]
        SEARCH["🔍 Search Service<br/>(Elasticsearch)"]
    end

    subgraph "Message Broker Layer"
        REDIS_PUB["📡 Redis Pub/Sub<br/>(Cross-server WS routing)<br/>~0.5ms latency"]
        KAFKA["📦 Apache Kafka<br/>(Durable event streaming)<br/>3 brokers, RF=3"]
    end

    subgraph "Data Layer"
        PG["🐘 PostgreSQL 16<br/>(User/Auth/Friend/Settings)<br/>1 Primary + 2 Read Replicas"]
        MONGO["🍃 MongoDB 7.0<br/>(Chat: messages, conversations)<br/>Replica Set + Sharding"]
        REDIS["⚡ Redis 7.2 Cluster<br/>(Cache/Session/Presence<br/>Typing/Unread/Routing)<br/>3 master + 3 replica"]
        S3["📁 AWS S3 / MinIO<br/>(Media: images, files, videos)<br/>Cross-region replication"]
        ES["🔍 Elasticsearch 8.x<br/>(Full-text search index)<br/>3-node cluster"]
    end

    subgraph "Observability Layer"
        PROM["📊 Prometheus + Grafana<br/>(Metrics + Dashboards)"]
        ELK["📝 ELK Stack<br/>(Centralized Logging)"]
        JAEGER["🔭 OpenTelemetry + Jaeger<br/>(Distributed Tracing)"]
        VAULT["🔒 HashiCorp Vault<br/>(Secrets Management)"]
    end

    MOB & WEB --> WAF
    WAF --> CDN
    CDN --> LB
    LB -->|"HTTP/REST"| GW
    LB -->|"WebSocket Upgrade<br/>(Sticky Session)"| CS1 & CS2 & CS3

    GW --> AUTH & USER & MEDIA & NOTIFY & SEARCH

    CS1 & CS2 & CS3 <--> REDIS_PUB
    CS1 & CS2 & CS3 --> KAFKA
    KAFKA --> NOTIFY & SEARCH

    AUTH & USER --> PG
    CS1 & CS2 & CS3 --> MONGO
    CS1 & CS2 & CS3 --> REDIS
    MEDIA --> S3
    SEARCH --> ES

    PROM -.->|scrape metrics| CS1 & CS2 & CS3
    ELK -.->|log shipping| CS1 & CS2 & CS3
    JAEGER -.->|trace spans| CS1 & CS2 & CS3
    VAULT -.->|inject secrets| CS1 & CS2 & CS3

    style CS1 fill:#4CAF50,color:#fff
    style CS2 fill:#4CAF50,color:#fff
    style CS3 fill:#4CAF50,color:#fff
    style REDIS fill:#DC382D,color:#fff
    style REDIS_PUB fill:#DC382D,color:#fff
    style PG fill:#336791,color:#fff
    style MONGO fill:#47A248,color:#fff
    style KAFKA fill:#231F20,color:#fff
    style LB fill:#FF9800,color:#fff
    style GW fill:#FF9800,color:#fff
    style WAF fill:#9C27B0,color:#fff
```

---

## 4. Tech Stack (Production-Grade)

### 4.1 Core Stack

| Layer | Technology | Version | Vai trò |
|-------|-----------|---------|---------|
| Language | **Java** | **21 LTS** | Virtual Threads, Structured Concurrency, Pattern Matching |
| Framework | **Spring Boot** | **3.5.x** | Servlet stack + STOMP WebSocket |
| RDBMS | **PostgreSQL** | **16** | User/Auth/Friend/Settings — ACID, relational |
| NoSQL | **MongoDB** | **7.0** | Chat messages, conversations — flexible schema |
| Cache | **Redis** | **7.2 Cluster** | Session, Presence, Typing, Unread, Routing, Token blacklist |
| Security | **Spring Security + JWT** | jjwt 0.12.3 | Stateless auth, multi-key JWT, refresh rotation |

### 4.2 Extended Stack

| Layer | Technology | Lý do |
|-------|-----------|-------|
| **WebSocket** | Spring WebSocket + STOMP | Giao thức chuẩn, tích hợp native Spring Security |
| **Message Router** | Redis Pub/Sub | Cross-server routing, ~0.5ms latency, đã có Redis |
| **Event Streaming** | Apache Kafka 3.7 (KRaft) | Durable log, replay, consumer groups; không cần Zookeeper |
| **Media Storage** | AWS S3 / MinIO | Object storage với presigned URL; MinIO cho local/dev |
| **Full-text Search** | Elasticsearch 8.x | Vietnamese ICU analyzer, async indexing từ Kafka |
| **API Gateway** | Spring Cloud Gateway | Rate limiting, auth propagation, circuit breaker |
| **Service Discovery** | Kubernetes Service DNS | Built-in khi dùng K8s; không cần Eureka thêm |
| **Local Cache** | Caffeine | L1 cache cho hot data (user profile, conversation metadata) |
| **Monitoring** | Micrometer + Prometheus + Grafana | Custom metrics, SLA dashboards, alerting |
| **Logging** | ELK Stack + Logback JSON | Structured logs, correlation ID, trace linking |
| **Tracing** | OpenTelemetry + Jaeger | Cross-service trace, DB query timing |
| **CI/CD** | GitHub Actions + Docker + Helm + K8s | Automated pipeline, immutable deployments |
| **Secret Management** | HashiCorp Vault | Dynamic secrets, auto-rotation |
| **DB Migration** | Flyway (PG) + Mongock (MongoDB) | Version-controlled schema changes |

### 4.3 Tận Dụng Java 21

| Feature | Áp dụng trong dự án | Lợi ích |
|---------|---------------------|---------|
| **Virtual Threads** (JEP 444) | WebSocket handler, blocking I/O (DB, Redis, HTTP clients) | Hàng triệu concurrent connections với memory nhỏ |
| **Structured Concurrency** (JEP 453) | Fan-out: persist + update unread + route + notify cùng lúc | Code concurrent dễ đọc, tự cancel khi parent fail |
| **Scoped Values** (JEP 446) | Truyền userId/requestId/traceId qua call chain | Thay ThreadLocal — không phù hợp với virtual threads |
| **Pattern Matching for switch** (JEP 441) | Xử lý MessageContent polymorphism | `switch(content) { case TextContent t -> ...; case ImageContent i -> ... }` |
| **Record Patterns** (JEP 440) | Destructure event DTOs | Clean code cho Kafka consumer handlers |
| **Sequenced Collections** (JEP 431) | Ordered message lists, participant ordering | `getFirst()`, `getLast()`, `reversed()` |

```yaml
# application.yml — Virtual Threads
spring:
  threads:
    virtual:
      enabled: true
```

```java
// WebSocket Virtual Thread Executor
@Bean
public TaskExecutor virtualThreadExecutor() {
    return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
}
```

---

## 5. Chiến Lược Lưu Trữ — Polyglot Persistence

```mermaid
graph LR
    subgraph "PostgreSQL 16 — ACID / Relational"
        PG_USER["👤 tbl_users"]
        PG_ROLE["🔑 tbl_roles / tbl_permissions"]
        PG_FRIEND["🤝 tbl_friendships"]
        PG_FRIEND_REQ["📨 tbl_friend_requests"]
        PG_TOKEN["🎫 tbl_refresh_tokens"]
        PG_SETTINGS["⚙️ tbl_user_settings"]
        PG_BLOCK["🚫 tbl_blocked_users"]
        PG_DEVICE["📱 tbl_user_devices"]
    end

    subgraph "MongoDB 7.0 — Document / Write-Heavy"
        M_CONV["💬 conversations"]
        M_MSG["📨 messages"]
        M_READ["👁️ message_read_states"]
        M_REACT["😀 message_reactions"]
        M_PROFILE["🪪 user_chat_profiles (snapshot)"]
        M_PIN["📌 pinned_messages"]
    end

    subgraph "Redis 7.2 Cluster — Ephemeral / Real-time"
        R_PRESENCE["🟢 presence:{userId}"]
        R_TYPING["⌨️ typing:{convId}"]
        R_UNREAD["🔴 unread:{userId}:{convId}"]
        R_UNREAD_TOTAL["🔴 unread:total:{userId}"]
        R_TOKEN_BL["🚫 token:blacklist:{jti}"]
        R_WS_ROUTE["📡 ws_routing:{userId}"]
        R_RATE["⏱️ rate:{userId}:{endpoint}"]
        R_FRIEND["👥 friends:{userId}"]
        R_CACHE_USER["💾 cache:user:{userId}"]
        R_CONV_LIST["📋 cache:convlist:{userId}"]
    end

    subgraph "AWS S3 / MinIO — Object Storage"
        S3_IMG["🖼️ /images"]
        S3_FILE["📎 /files"]
        S3_VIDEO["🎥 /videos"]
        S3_AVATAR["👤 /avatars"]
        S3_THUMB["🖼️ /thumbnails"]
    end

    subgraph "Elasticsearch 8.x — Search"
        ES_MSG["🔍 messages index"]
        ES_CONV["🔍 conversations index"]
        ES_USER["🔍 users index"]
    end
```

### Nguyên tắc lựa chọn storage

| Use Case | Storage | Lý do |
|----------|---------|-------|
| User identity, auth, relationships | PostgreSQL | ACID, foreign keys, relational queries |
| Chat messages (write-heavy, flexible schema) | MongoDB | Document model, horizontal sharding |
| Real-time ephemeral state (presence, typing) | Redis | TTL tự động, sub-millisecond |
| Session routing (ws_routing) | Redis | O(1) lookup, pub/sub built-in |
| Durable async events | Kafka | Replay, consumer groups |
| Media binary files | S3/MinIO | Object storage, CDN integration |
| Full-text search | Elasticsearch | Inverted index, Vietnamese analyzer |
| Hot data cache (user profiles) | Redis + Caffeine | L1+L2 caching |

---

## 6. Database Schema Chi Tiết

### 6.1 PostgreSQL — DDL

```sql
-- ============================================================
-- USERS & AUTH
-- ============================================================
CREATE TABLE tbl_users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(50) NOT NULL UNIQUE,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    display_name    VARCHAR(100) NOT NULL,
    avatar_url      VARCHAR(500),
    bio             VARCHAR(500),
    last_seen_at    TIMESTAMPTZ,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    is_verified     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_users_username ON tbl_users(username);
CREATE INDEX idx_users_email ON tbl_users(email);
CREATE INDEX idx_users_last_seen ON tbl_users(last_seen_at DESC);

CREATE TABLE tbl_refresh_tokens (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES tbl_users(id) ON DELETE CASCADE,
    token_hash      VARCHAR(255) NOT NULL UNIQUE,   -- SHA-256 của token
    device_id       VARCHAR(100),
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,                    -- NULL = còn hiệu lực
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_refresh_token_user ON tbl_refresh_tokens(user_id);
CREATE INDEX idx_refresh_token_expires ON tbl_refresh_tokens(expires_at);

-- ============================================================
-- FRIENDSHIP
-- ============================================================
CREATE TABLE tbl_friendships (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES tbl_users(id) ON DELETE CASCADE,
    friend_id       BIGINT NOT NULL REFERENCES tbl_users(id) ON DELETE CASCADE,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACCEPTED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_friendship UNIQUE (user_id, friend_id),
    CONSTRAINT chk_no_self_friend CHECK (user_id <> friend_id)
);
CREATE INDEX idx_friendship_user ON tbl_friendships(user_id, status);
CREATE INDEX idx_friendship_friend ON tbl_friendships(friend_id, status);

CREATE TABLE tbl_friend_requests (
    id              BIGSERIAL PRIMARY KEY,
    sender_id       BIGINT NOT NULL REFERENCES tbl_users(id) ON DELETE CASCADE,
    receiver_id     BIGINT NOT NULL REFERENCES tbl_users(id) ON DELETE CASCADE,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, ACCEPTED, REJECTED, CANCELLED
    message         VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    responded_at    TIMESTAMPTZ,
    CONSTRAINT uq_friend_request UNIQUE (sender_id, receiver_id),
    CONSTRAINT chk_no_self_request CHECK (sender_id <> receiver_id)
);
CREATE INDEX idx_friend_req_receiver ON tbl_friend_requests(receiver_id, status);
CREATE INDEX idx_friend_req_sender ON tbl_friend_requests(sender_id, status);

CREATE TABLE tbl_blocked_users (
    id              BIGSERIAL PRIMARY KEY,
    blocker_id      BIGINT NOT NULL REFERENCES tbl_users(id) ON DELETE CASCADE,
    blocked_id      BIGINT NOT NULL REFERENCES tbl_users(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_block UNIQUE (blocker_id, blocked_id)
);
CREATE INDEX idx_blocked_blocker ON tbl_blocked_users(blocker_id);
CREATE INDEX idx_blocked_blocked ON tbl_blocked_users(blocked_id);

-- ============================================================
-- DEVICES & SETTINGS
-- ============================================================
CREATE TABLE tbl_user_devices (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES tbl_users(id) ON DELETE CASCADE,
    device_id       VARCHAR(100) NOT NULL,
    platform        VARCHAR(20) NOT NULL,            -- ANDROID, IOS, WEB
    push_token      VARCHAR(500),
    device_name     VARCHAR(100),
    last_active_at  TIMESTAMPTZ,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_device UNIQUE (user_id, device_id)
);
CREATE INDEX idx_device_user ON tbl_user_devices(user_id, is_active);

CREATE TABLE tbl_user_settings (
    user_id                     BIGINT PRIMARY KEY REFERENCES tbl_users(id) ON DELETE CASCADE,
    notification_enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    notification_preview        BOOLEAN NOT NULL DEFAULT TRUE,   -- show message content in push
    online_status_visible       VARCHAR(20) NOT NULL DEFAULT 'FRIENDS',  -- ALL, FRIENDS, NONE
    read_receipts_enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    who_can_add_to_group        VARCHAR(20) NOT NULL DEFAULT 'FRIENDS',  -- ALL, FRIENDS
    who_can_send_friend_req     VARCHAR(20) NOT NULL DEFAULT 'ALL',
    theme                       VARCHAR(20) NOT NULL DEFAULT 'SYSTEM',
    language                    VARCHAR(10) NOT NULL DEFAULT 'vi',
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### 6.2 MongoDB — Document Schema

```javascript
// ============================================================
// conversations collection
// ============================================================
{
  "_id": ObjectId,
  "type": "DIRECT" | "GROUP",

  // --- DM only ---
  "dmHash": "min_userId_max_userId",  // unique index, prevent duplicate DMs

  // --- Group only ---
  "name": "Dev Team #general",
  "avatarUrl": "https://cdn.vivumate.com/groups/abc.jpg",
  "description": "Backend discussion",
  "ownerId": Long,                    // user_id from PostgreSQL (FK by convention)
  "settings": {
    "onlyAdminsCanSend": false,
    "onlyAdminsCanEditInfo": true,
    "maxMembers": 500
  },

  // --- Both ---
  "participantIds": [Long],           // Indexed. user_ids from PostgreSQL
  "participants": [                   // Denormalized snapshot for fast list rendering
    {
      "userId": Long,
      "role": "OWNER" | "ADMIN" | "MEMBER",
      "joinedAt": ISODate,
      "mutedUntil": ISODate | null,
      "isMuted": false,
      "nickname": null               // per-conversation nickname
    }
  ],
  "memberCount": 2,

  "lastMessage": {                   // Snapshot, updated on every new message
    "messageId": ObjectId,
    "senderId": Long,
    "senderName": "Nguyễn Văn A",
    "contentType": "TEXT" | "IMAGE" | "FILE" | "AUDIO" | "STICKER" | "SYSTEM",
    "contentPreview": "Anh ơi review PR...",
    "sentAt": ISODate
  },

  "unreadCounts": {                  // { userId: count } — updated by message service
    "123": 5,
    "456": 0
  },

  "lastActivityAt": ISODate,         // Index DESC for conversation list sorting
  "isDeleted": false,
  "deletedAt": ISODate | null,
  "createdAt": ISODate,
  "updatedAt": ISODate
}

// Indexes
db.conversations.createIndex({ "participantIds": 1, "lastActivityAt": -1 })
db.conversations.createIndex({ "dmHash": 1 }, { unique: true, sparse: true })
db.conversations.createIndex({ "lastActivityAt": -1 })

// ============================================================
// messages collection
// ============================================================
{
  "_id": ObjectId,
  "conversationId": ObjectId,         // Index
  "senderId": Long,                   // user_id from PostgreSQL

  "senderSnapshot": {                 // Denormalized at time of sending
    "userId": Long,
    "displayName": "Nguyễn Văn A",
    "avatarUrl": "https://cdn.../avatar.jpg"
  },

  "contentType": "TEXT" | "IMAGE" | "FILE" | "AUDIO" | "VIDEO" | "STICKER" | "SYSTEM" | "REPLY" | "FORWARD",
  "content": {
    // TEXT
    "text": "Anh ơi review PR #123 giúp em với!",

    // IMAGE
    // "url": "https://cdn.vivumate.com/images/abc.jpg",
    // "thumbnailUrl": "https://cdn.../thumb_abc.jpg",
    // "width": 1920, "height": 1080, "size": 204800,

    // FILE
    // "url": "...", "fileName": "Q3_report.pdf", "size": 2048000, "mimeType": "application/pdf",

    // SYSTEM (auto-generated)
    // "text": "Nguyễn Văn A đã tạo nhóm"
  },

  "mentions": [Long],                 // userIds được mention

  "replyTo": {                        // null if not a reply
    "messageId": ObjectId,
    "senderId": Long,
    "senderName": "...",
    "contentPreview": "..."
  },

  "reactions": {                      // { emoji: [userId] }
    "👍": [123, 456],
    "❤️": [789]
  },

  "status": "SENT" | "DELIVERED" | "SEEN",
  "isEdited": false,
  "editHistory": [                    // Populated when isEdited = true
    { "content": { "text": "..." }, "editedAt": ISODate }
  ],

  "deletedForEveryone": false,
  "deletedFor": [Long],               // userIds who deleted for themselves

  "clientMessageId": "uuid-v4",       // Idempotency key from client — UNIQUE index
  "createdAt": ISODate,               // Index DESC
  "updatedAt": ISODate
}

// Indexes — critical for performance
db.messages.createIndex({ "conversationId": 1, "createdAt": -1 })  // Primary read pattern
db.messages.createIndex({ "clientMessageId": 1 }, { unique: true }) // Idempotency
db.messages.createIndex({ "senderId": 1, "createdAt": -1 })
db.messages.createIndex({ "mentions": 1 })                          // Mention queries

// ============================================================
// message_read_states collection
// ============================================================
{
  "_id": ObjectId,
  "userId": Long,
  "conversationId": ObjectId,
  "lastReadMessageId": ObjectId,      // Watermark — all messages before this are "read"
  "lastDeliveredMessageId": ObjectId,
  "readAt": ISODate,
  "deliveredAt": ISODate,
  "updatedAt": ISODate
}
db.message_read_states.createIndex({ "userId": 1, "conversationId": 1 }, { unique: true })

// ============================================================
// user_chat_profiles collection — Cached snapshot
// ============================================================
{
  "_id": Long,                        // = PostgreSQL user_id
  "displayName": "Nguyễn Văn A",
  "avatarUrl": "https://cdn.../avatar.jpg",
  "username": "nguyenvana",
  "syncedAt": ISODate                 // Last sync from PostgreSQL
}
```

### 6.3 Redis Key Schema

```
# Presence
presence:{userId}           → Hash  { status: ONLINE|AWAY, lastActive: epoch }   TTL: 5min

# WebSocket Routing (stateless server lookup)
ws_routing:{userId}         → Set<serverId>                                        TTL: session lifetime

# Typing Indicator
typing:{conversationId}     → Set<userId>                                          TTL: 3s

# Unread Counts
unread:{userId}:{convId}    → Integer                                              No TTL
unread:total:{userId}       → Integer  (sum across all conversations)              No TTL

# Token Blacklist
token:bl:{jti}              → "1"                                                  TTL: remaining JWT lifetime

# Rate Limiting
rate:{userId}:{endpoint}    → Integer (request count)                             TTL: window duration (e.g. 60s)
rate:ip:{ip}                → Integer                                             TTL: 60s

# Cache
cache:user:{userId}         → JSON (user profile)                                 TTL: 30min
cache:friends:{userId}      → Set<userId>                                         TTL: 1h
cache:convlist:{userId}     → JSON (conversation list page 1)                     TTL: 5min
```

---

## 7. API Contract (REST + WebSocket)

### 7.1 REST API Conventions

```
Base URL:     https://api.vivumate.com/api/v1
Content-Type: application/json
Auth Header:  Authorization: Bearer <access_token>

Response envelope:
{
  "code": 1000,              // 1000 = success, 4xxx = client error, 5xxx = server error
  "message": "Success",
  "data": { ... } | [...],
  "timestamp": "2025-01-15T10:30:00Z"
}

Pagination response:
{
  "code": 1000,
  "data": {
    "items": [...],
    "nextCursor": "cursor_value_or_null",
    "hasMore": true,
    "total": 1500          // optional, only for count queries
  }
}
```

### 7.2 Authentication Endpoints

| Method | Path | Request | Response | Notes |
|--------|------|---------|----------|-------|
| POST | `/auth/register` | `{username, email, password, displayName}` | `{userId, accessToken, refreshToken}` | |
| POST | `/auth/login` | `{email, password, deviceId, platform}` | `{accessToken, refreshToken, user}` | |
| POST | `/auth/refresh` | `{refreshToken}` | `{accessToken, refreshToken}` | Rotation |
| POST | `/auth/logout` | `{refreshToken}` | `{}` | Blacklist access token |
| POST | `/auth/logout-all` | — | `{}` | Revoke tất cả devices |
| POST | `/auth/otp/send` | `{email}` | `{}` | OTP for password reset |
| POST | `/auth/otp/verify` | `{email, otp}` | `{resetToken}` | |
| POST | `/auth/password/reset` | `{resetToken, newPassword}` | `{}` | |

### 7.3 Conversation Endpoints

| Method | Path | Request | Response | Notes |
|--------|------|---------|----------|-------|
| GET | `/conversations` | `?cursor&limit=20` | `PageResponse<ConversationDTO>` | Sorted by lastActivityAt |
| POST | `/conversations/direct` | `{recipientId}` | `ConversationDTO` | Idempotent |
| POST | `/conversations/group` | `{name, memberIds[], avatarUrl?, description?}` | `ConversationDTO` | |
| GET | `/conversations/{id}` | — | `ConversationDTO` | |
| PATCH | `/conversations/{id}` | `{name?, avatarUrl?, description?, settings?}` | `ConversationDTO` | ADMIN+ |
| DELETE | `/conversations/{id}` | — | `{}` | Soft delete, OWNER only |
| POST | `/conversations/{id}/members` | `{userIds[]}` | `ConversationDTO` | ADMIN+ |
| DELETE | `/conversations/{id}/members/{userId}` | — | `{}` | ADMIN+ |
| POST | `/conversations/{id}/leave` | — | `{}` | Transfer ownership trước |
| PATCH | `/conversations/{id}/members/{userId}/role` | `{role: ADMIN|MEMBER}` | `{}` | OWNER only |
| POST | `/conversations/{id}/mute` | `{duration?: minutes}` | `{}` | Per-user |
| DELETE | `/conversations/{id}/mute` | — | `{}` | |

### 7.4 Message Endpoints

| Method | Path | Request | Response | Notes |
|--------|------|---------|----------|-------|
| GET | `/conversations/{id}/messages` | `?cursor={lastId}&limit=30` | `PageResponse<MessageDTO>` | Cursor-based |
| GET | `/conversations/{id}/messages/{msgId}` | — | `MessageDTO` | |
| POST | `/conversations/{id}/messages/{msgId}/read` | — | `{}` | Mark as read |
| DELETE | `/conversations/{id}/messages/{msgId}` | `{scope: ME|EVERYONE}` | `{}` | Time limit for EVERYONE |
| GET | `/conversations/{id}/pinned` | — | `[MessageDTO]` | |
| POST | `/conversations/{id}/messages/{msgId}/pin` | — | `{}` | ADMIN+ |
| DELETE | `/conversations/{id}/messages/{msgId}/pin` | — | `{}` | ADMIN+ |

### 7.5 Media Endpoints

| Method | Path | Request | Response | Notes |
|--------|------|---------|----------|-------|
| POST | `/media/upload` | `multipart/form-data` | `{url, thumbnailUrl, size, mimeType}` | Small files ≤ 5MB |
| POST | `/media/presign` | `{fileName, mimeType, size}` | `{uploadUrl, fileUrl, expiresIn}` | Large files via presigned URL |

### 7.6 WebSocket Protocol (STOMP)

```
Endpoint:  ws://api.vivumate.com/ws-connect
Protocol:  STOMP 1.2 over WebSocket (SockJS fallback)
Auth:      JWT token in STOMP CONNECT headers

STOMP CONNECT frame:
CONNECT
Authorization: Bearer <access_token>
heart-beat: 25000,25000
accept-version: 1.2
```

**Client → Server (application destinations `/app/...`):**

| Destination | Payload | Mô tả |
|-------------|---------|-------|
| `/app/chat.send` | `{conversationId, contentType, content, clientMessageId, replyToId?}` | Gửi tin nhắn |
| `/app/chat.edit` | `{messageId, conversationId, content}` | Sửa tin nhắn (trong 15 phút) |
| `/app/chat.delete` | `{messageId, conversationId, scope: ME|EVERYONE}` | Xóa tin nhắn |
| `/app/chat.react` | `{messageId, conversationId, emoji}` | Thêm/bỏ reaction |
| `/app/chat.typing` | `{conversationId, isTyping: true|false}` | Typing indicator |
| `/app/chat.read` | `{conversationId, lastReadMessageId}` | Mark as read |
| `/app/chat.sync` | `{lastSyncTimestamp}` | Delta sync on reconnect |
| `/app/presence.update` | `{status: ONLINE|AWAY|DND}` | Thay đổi trạng thái |

**Server → Client (subscriptions):**

| Topic | Payload | Mô tả |
|-------|---------|-------|
| `/topic/conversation/{convId}` | `MessageEvent` | Tin nhắn mới trong conversation |
| `/user/queue/messages` | `MessageEvent` | Tin nhắn mới (private delivery) |
| `/user/queue/ack` | `{clientMessageId, messageId, status, timestamp}` | ACK sau khi gửi |
| `/user/queue/sync` | `SyncResponse` | Delta sync response |
| `/topic/conversation/{convId}/typing` | `{userId, isTyping}` | Typing events |
| `/topic/conversation/{convId}/read` | `{userId, lastReadMessageId, readAt}` | Read receipts |
| `/topic/conversation/{convId}/members` | `MemberEvent` | Member changes |
| `/topic/presence` | `{userId, status, lastSeen?}` | Presence updates (friends only) |
| `/user/queue/errors` | `{code, message, clientMessageId?}` | WebSocket errors |
| `/user/queue/notifications` | `NotificationEvent` | In-app notifications |

---

## 8. Hành Trình Của Một Tin Nhắn

```mermaid
sequenceDiagram
    participant C as 📱 Sender
    participant LB as ⚖️ Load Balancer
    participant CS1 as 💬 Chat Server 1
    participant Redis as ⚡ Redis
    participant Mongo as 🍃 MongoDB
    participant CS2 as 💬 Chat Server 2
    participant Kafka as 📦 Kafka
    participant Notify as 🔔 Notification
    participant R2 as 📱 Receiver

    Note over C,R2: Phase 1: Validate & Persist

    C->>+LB: WS STOMP: /app/chat.send
    LB->>+CS1: Route (sticky session)

    Note over CS1: 1. Validate auth, membership,<br/>rate limit, content size<br/>2. Dedup by clientMessageId

    CS1->>Mongo: INSERT message (writeConcern: majority)
    Mongo-->>CS1: ✅ messageId

    Note over C,R2: Phase 2: Parallel Processing

    par Structured Concurrency (Java 21)
        CS1->>Mongo: UPDATE conversation (lastMessage, lastActivityAt)
        CS1->>Redis: INCR unread:{recipientId}:{convId}
        CS1->>Redis: INCR unread:total:{recipientId}
    end

    CS1-->>-C: /user/queue/ack {clientMessageId, messageId, status: SENT}

    Note over C,R2: Phase 3: Route to Recipients

    CS1->>Redis: GET ws_routing:{recipientId}

    alt Recipient on same server
        CS1->>R2: /user/queue/messages MESSAGE_EVENT
    else Recipient on different server
        Redis-->>CS1: "server-2"
        CS1->>Redis: PUBLISH chat:server-2 {message, targetUserIds}
        Redis->>CS2: Subscriber receives
        CS2->>R2: /user/queue/messages MESSAGE_EVENT
    end

    Note over C,R2: Phase 4: Async Events (Kafka)

    CS1->>Kafka: PRODUCE chat.messages {messageId, conversationId, ...}
    Kafka->>Notify: CONSUME → check presence → FCM/APNs if offline
    Notify->>R2: 📲 Push notification (if offline)

    Note over C,R2: Phase 5: Delivery Receipt

    R2->>CS2: /app/chat.read {conversationId, lastReadMessageId}
    CS2->>Mongo: UPSERT message_read_states (watermark)
    CS2->>Redis: DEL unread:{recipientId}:{convId}
    CS2->>Redis: PUBLISH chat:server-1 DELIVERY_RECEIPT
    Redis->>CS1: Forward receipt
    CS1->>C: /topic/conversation/{convId}/read {userId, status: DELIVERED}
```

### 8.1 Message Service — Structured Concurrency

```java
@Service
@RequiredArgsConstructor
@Slf4j(topic = "MESSAGE_SERVICE")
public class MessageServiceImpl implements MessageService {

    private final MongoTemplate mongoTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final WebSocketSessionManager sessionManager;
    private final CrossServerMessageRouter messageRouter;
    private final KafkaTemplate<String, ChatEvent> kafkaTemplate;
    private final MessageIdempotencyService idempotencyService;

    @Override
    public MessageResponse sendMessage(Long senderId, SendMessageRequest request) {
        // 1. Idempotency check — prevent duplicate on client retry
        if (idempotencyService.isDuplicate(request.getClientMessageId())) {
            return idempotencyService.getCachedResponse(request.getClientMessageId());
        }

        // 2. Validate
        ConversationDocument conv = validateAndGetConversation(
            request.getConversationId(), senderId
        );

        // 3. Build & persist (writeConcern: MAJORITY for durability)
        MessageDocument message = buildMessage(senderId, request, conv);
        MessageDocument saved = mongoTemplate.insert(message);

        // 4. Cache idempotency result
        MessageResponse response = MessageMapper.toResponse(saved);
        idempotencyService.cacheResponse(request.getClientMessageId(), response);

        // 5. Parallel: update metadata + route + async events
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {

            scope.fork(() -> {
                updateConversationLastMessage(conv, saved);
                return null;
            });

            scope.fork(() -> {
                incrementUnreadForRecipients(conv, senderId, saved.getId());
                return null;
            });

            scope.fork(() -> {
                routeToRecipients(conv, saved, senderId);
                return null;
            });

            scope.fork(() -> {
                publishToKafka(conv, saved, senderId);
                return null;
            });

            scope.join().throwIfFailed();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Message processing interrupted: {}", saved.getId(), e);
            // Message already persisted — continue with partial delivery
        }

        return response;
    }
}
```

---

## 9. Đồng Bộ Đa Thiết Bị

```mermaid
sequenceDiagram
    participant Phone as 📱 Phone
    participant Web as 🌐 Web
    participant Tablet as 📟 Tablet
    participant CS as 💬 Chat Server
    participant Redis as ⚡ Redis
    participant Mongo as 🍃 MongoDB

    Note over Phone,Mongo: Scenario 1 — Gửi từ Phone, sync đến tất cả devices

    Phone->>CS: SEND "Hello"
    CS->>Mongo: Persist

    par Broadcast to all devices of SENDER (multi-device echo)
        CS->>Phone: ACK + MESSAGE_EVENT (sender confirmation)
        CS->>Web: WS: NEW_MESSAGE
        CS->>Tablet: WS: NEW_MESSAGE
    end

    Note over Phone,Mongo: Scenario 2 — Web đọc tin → sync read status

    Web->>CS: mark_as_read {conversationId, lastReadMessageId}
    CS->>Mongo: UPSERT read_state (watermark)
    CS->>Redis: DEL unread:{userId}:{convId}

    par Sync READ_STATUS to other devices
        CS->>Phone: READ_STATUS_UPDATED {convId, unreadCount: 0}
        CS->>Tablet: READ_STATUS_UPDATED {convId, unreadCount: 0}
    end

    Note over Phone,Mongo: Scenario 3 — Tablet reconnect sau offline

    Tablet->>CS: WS CONNECT + STOMP SYNC {lastSyncTimestamp}
    CS->>Mongo: Query conversations WHERE lastActivityAt > lastSyncTimestamp AND userId IN participantIds
    CS->>Mongo: Query messages WHERE createdAt > lastSyncTimestamp (per changed conv)
    CS->>Redis: MGET unread:{userId}:{convId} for each conv

    CS->>Tablet: SYNC_BATCH {conversations[], messages{}, readStates{}, unreadCounts{}, serverTimestamp}
    Note over Tablet: Apply delta, update local DB, refresh UI
```

---

## 10. Luồng Nhóm Chat

### 10.1 Fan-out Strategy cho Group Messages

> Với nhóm lớn (100-500 members), fan-out naive (gửi N WebSocket messages) có thể gây bottleneck. Strategy:

| Group Size | Strategy | Lý do |
|-----------|---------|-------|
| < 50 members | Direct fan-out per WS session | Đơn giản, latency thấp |
| 50–200 members | Fan-out qua Redis Pub/Sub channel per group | Giảm lookup overhead |
| > 200 members | Kafka-based fan-out + batch delivery | Async, không block message handler |

```java
/**
 * Optimized fan-out for large groups using Virtual Threads
 */
private void routeGroupMessage(MessageDocument message,
                                ConversationDocument conversation,
                                Long senderId) {
    List<Long> recipients = conversation.getParticipantIds()
        .stream()
        .filter(id -> !id.equals(senderId))
        .toList();

    if (recipients.size() <= 50) {
        // Direct fan-out
        messageRouter.routeToRecipients(message, recipients);
    } else {
        // Batch fan-out via virtual threads
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            Lists.partition(recipients, 50).forEach(batch ->
                scope.fork(() -> {
                    messageRouter.routeToRecipients(message, batch);
                    return null;
                })
            );
            scope.join().throwIfFailed();
        }
    }
}
```

### 10.2 Group Permissions Matrix

| Operation | MEMBER | ADMIN | OWNER | Notes |
|-----------|--------|-------|-------|-------|
| Gửi tin nhắn | ✅* | ✅ | ✅ | *Nếu `onlyAdminsCanSend = false` |
| Đọc tin nhắn | ✅ | ✅ | ✅ | |
| Thêm member | ❌ | ✅ | ✅ | Kiểm tra block list + friendship |
| Xóa member | ❌ | ✅* | ✅ | *Admin không xóa được Admin khác |
| Rời nhóm | ✅ | ✅ | ✅* | *OWNER phải chuyển quyền trước |
| Đổi tên/avatar | ❌* | ✅* | ✅ | *Nếu `onlyAdminsCanEditInfo` |
| Promote/Demote Admin | ❌ | ❌ | ✅ | Max 10 admins |
| Chuyển OWNER | ❌ | ❌ | ✅ | Chỉ cho member hiện tại |
| Ghim tin nhắn | ❌ | ✅ | ✅ | Max 50 per group |
| Xóa nhóm | ❌ | ❌ | ✅ | Soft delete |

---

## 11. Quản Lý Trạng Thái

### 11.1 Presence State Machine

```mermaid
stateDiagram-v2
    [*] --> OFFLINE : Initial / App start

    OFFLINE --> ONLINE : WS CONNECT + heartbeat start
    ONLINE --> AWAY : Idle ≥ 2 min (no user activity)
    AWAY --> ONLINE : User activity (type, click, scroll)
    ONLINE --> DO_NOT_DISTURB : User toggle DND
    DO_NOT_DISTURB --> ONLINE : User toggle off
    ONLINE --> OFFLINE : WS DISCONNECT gracefully
    AWAY --> OFFLINE : WS DISCONNECT / TTL expired (crash)
    DO_NOT_DISTURB --> OFFLINE : WS DISCONNECT

    note right of ONLINE
        Redis HSET presence:{userId}
        { status: "ONLINE", lastActive: now }
        TTL: 5min (renewed by heartbeat every 25s)
        Notify online friends
    end note

    note right of AWAY
        Redis HSET presence:{userId} status "AWAY"
        TTL unchanged
    end note

    note right of OFFLINE
        Redis DEL presence:{userId}
        PostgreSQL: UPDATE last_seen_at = NOW()
        Notify online friends of disconnect
    end note
```

### 11.2 Heartbeat & TTL Strategy

```
Client heartbeat interval:  25s  (STOMP heart-beat: 25000,25000)
Server-side TTL:            5min (300s)
Safety margin:              5min / 25s = 12 missed heartbeats before considered offline

Redis keyspace notification:
CONFIG SET notify-keyspace-events "Ex"  → notify on key expiry
→ PresenceService subscribes to __keyevent@0__:expired
→ When presence:{userId} expires → broadcast OFFLINE to friends
```

### 11.3 Message Status Flow

```
Client gửi → SENDING (client local state)
Server INSERT OK → SENT (✓) → ACK to sender
Recipient device receives WS message → DELIVERED (✓✓) → Receipt to sender
Recipient mở conversation → SEEN (✓✓ blue) → Receipt to sender

Lưu ý group chat:
- DELIVERED khi TẤT CẢ members delivered
- SEEN khi có ÍT NHẤT 1 member seen (show N/M seen)
```

---

## 12. Định Tuyến Tin Nhắn Giữa Các Server

```mermaid
graph TB
    subgraph "Chat Server 1"
        S1_HANDLER["Message Handler"]
        S1_ROUTER["CrossServerRouter"]
        S1_SUB["Redis Subscriber<br/>chat:server-1"]
        S1_LOCAL["Local WS Delivery"]
    end

    subgraph "Chat Server 2"
        S2_SUB["Redis Subscriber<br/>chat:server-2"]
        S2_LOCAL["Local WS Delivery"]
    end

    subgraph "Redis Cluster"
        R_ROUTE["ws_routing:{userId}<br/>→ Set of serverIds"]
        R_PUBSUB["Pub/Sub Channels<br/>chat:server-{id}"]
    end

    S1_HANDLER --> S1_ROUTER
    S1_ROUTER -->|"1. Lookup routing"| R_ROUTE
    R_ROUTE -->|"server-1 → local"| S1_LOCAL
    R_ROUTE -->|"server-2 → publish"| R_PUBSUB
    R_PUBSUB -->|"subscribe"| S2_SUB
    S2_SUB --> S2_LOCAL

    style R_ROUTE fill:#DC382D,color:#fff
    style R_PUBSUB fill:#DC382D,color:#fff
```

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class CrossServerMessageRouter {

    private final RedisTemplate<String, String> redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    @Value("${app.server.id}")
    private String serverId;

    public void routeToRecipients(MessageDocument message, List<Long> recipientIds) {
        // Batch Redis MGET for all recipients' server locations
        List<String> routingKeys = recipientIds.stream()
            .map(id -> "ws_routing:" + id)
            .toList();

        // Group by target server
        Map<String, List<Long>> serverToUsers = new HashMap<>();
        for (int i = 0; i < recipientIds.size(); i++) {
            Long userId = recipientIds.get(i);
            Set<String> servers = sessionManager.getServersForUser(userId);
            if (servers != null && !servers.isEmpty()) {
                servers.forEach(server ->
                    serverToUsers.computeIfAbsent(server, k -> new ArrayList<>()).add(userId)
                );
            }
        }

        // Local delivery (same server)
        List<Long> localUsers = serverToUsers.remove(serverId);
        if (localUsers != null) deliverLocally(message, localUsers);

        // Remote delivery via Redis Pub/Sub
        serverToUsers.forEach((targetServer, users) -> {
            try {
                CrossServerMessage csm = CrossServerMessage.of(message, users, serverId);
                redisTemplate.convertAndSend(
                    "chat:server:" + targetServer,
                    objectMapper.writeValueAsString(csm)
                );
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize cross-server message", e);
            }
        });
    }

    @RedisListener(pattern = "chat:server:${app.server.id}")
    public void onCrossServerMessage(String rawMessage) {
        try {
            CrossServerMessage csm = objectMapper.readValue(rawMessage, CrossServerMessage.class);
            deliverLocally(csm.getMessage(), csm.getTargetUserIds());
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cross-server message", e);
        }
    }

    private void deliverLocally(MessageDocument message, List<Long> userIds) {
        MessageResponse response = MessageMapper.toResponse(message);
        userIds.forEach(userId ->
            messagingTemplate.convertAndSendToUser(
                userId.toString(),
                "/queue/messages",
                response
            )
        );
    }
}
```

---

## 13. Khả Năng Chịu Lỗi & Phục Hồi

### 13.1 Failure Scenarios & Recovery

| Component | Failure Mode | Detection | Recovery | SLA Impact |
|-----------|-------------|-----------|----------|------------|
| **Chat Server** | Crash / OOM | K8s liveness probe | Auto-restart (<30s); client exponential backoff reconnect; delta sync on reconnect | Nhỏ — stateless |
| **MongoDB Primary** | Primary down | Replica heartbeat | Auto-election (~10–15s); Spring Retry (3x, exp backoff) | Message delivery delay |
| **MongoDB Write** | Write timeout | Spring timeout | Client retry với `clientMessageId` idempotency | 0 message loss |
| **PostgreSQL Primary** | Primary down | Streaming replication lag | pgPool-II / PgBouncer failover; read replica promotion | Auth/profile degraded |
| **Redis Node** | Node down | Cluster heartbeat | Redis Cluster auto-failover; circuit breaker fallback to DB | Presence/unread degraded |
| **Kafka Broker** | Broker down | ISR shrink | RF=3 auto-recovery; producer retry with backoff | Push notification delay |
| **Load Balancer** | Single LB down | Health check | AWS ALB (managed HA) hoặc dual Nginx (keepalived) | Outage if single LB |
| **Network Partition** | Split brain | Redis sentinel quorum | Message dedup by `clientMessageId`; client retry | Potential duplicate (idempotent) |
| **Message Loss** | Server crash before persist | Client ACK timeout | Client retry; at-least-once delivery + server dedup | 0 message loss |

### 13.2 Circuit Breaker Configuration

```java
@Configuration
public class ResilienceConfig {

    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> mongoBreaker() {
        return factory -> factory.configure(
            id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .slidingWindowSize(10)
                    .permittedNumberOfCallsInHalfOpenState(5)
                    .slowCallRateThreshold(80)
                    .slowCallDurationThreshold(Duration.ofSeconds(2))
                    .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                    .timeoutDuration(Duration.ofSeconds(3))
                    .build())
                .build(),
            "mongodb", "redis", "kafka"
        );
    }
}
```

### 13.3 Client Reconnect Strategy

```javascript
// Client-side exponential backoff reconnect
class ChatClientReconnect {
    baseDelay = 1000;    // 1s
    maxDelay = 30000;    // 30s
    jitter = 0.2;        // ±20% randomization to avoid thundering herd
    attempt = 0;

    getDelay() {
        const delay = Math.min(this.baseDelay * Math.pow(2, this.attempt), this.maxDelay);
        const jitterMs = delay * this.jitter * (Math.random() * 2 - 1);
        this.attempt++;
        return delay + jitterMs;
    }

    onConnect() {
        this.attempt = 0;  // Reset on success
        // Send SYNC request with lastSyncTimestamp
    }
}
```

---

## 14. Mở Rộng Quy Mô (Scaling Strategy)

### 14.1 Horizontal Scaling

| Component | Scale Unit | Trigger | Max Scale |
|-----------|-----------|---------|-----------|
| **Chat Server** | Pod | CPU > 60% OR WS connections > 40K | 50 pods |
| **Auth/User Service** | Pod | CPU > 70% | 10 pods |
| **MongoDB** | Shard | Data > 500GB per shard OR ops > 10K/s | Unlimited |
| **Redis** | Shard | Memory > 70% | Expand cluster |
| **Kafka** | Partition/Broker | Consumer lag > threshold | Add brokers |
| **Elasticsearch** | Node | Heap > 70% | Expand cluster |

### 14.2 Caching Strategy (L1 + L2 + L3)

```
L1 — Caffeine (in-process, per Chat Server)
  - User profile cache: 10K entries, 5min TTL
  - Conversation metadata: 5K entries, 2min TTL
  - Hit rate target: 70%+

L2 — Redis (shared across all servers)
  - User profile: TTL 30min
  - Conversation list: TTL 5min
  - Friend list: TTL 1h
  - Hit rate target: 90%+

L3 — CDN (Cloudflare/CloudFront)
  - Static assets: 1 year TTL
  - Media files (images, videos): 30 days TTL
  - Profile avatars: 7 days TTL
```

### 14.3 Database Read/Write Split (CQRS lite)

```
Write path:
  Messages → MongoDB Primary (writeConcern: majority)
  User data → PostgreSQL Primary
  Events → Kafka

Read path:
  Message history → MongoDB Secondary (readPreference: secondaryPreferred)
  User profiles → PostgreSQL Read Replica OR Redis cache
  Conversation list → Redis cache + MongoDB Secondary
  Search → Elasticsearch (updated async via Kafka)
```

### 14.4 MongoDB Sharding Design

```javascript
// Shard key: conversationId (hashed)
// Strategy: hash sharding để distribute writes evenly
// Co-location: tất cả messages của 1 conversation nằm trên 1 shard → range queries hiệu quả

sh.enableSharding("vivumate")
sh.shardCollection("vivumate.messages", { "conversationId": "hashed" })
sh.shardCollection("vivumate.conversations", { "_id": "hashed" })

// Chunk size: 64MB (default)
// Min shards: 3 (initial)
// Scale: add shards khi avg chunk > 80% capacity
```

---

## 15. Bảo Mật (Security Hardening)

### 15.1 Security Layers

| Layer | Control | Implementation |
|-------|---------|----------------|
| **Transport** | TLS 1.3 only | Nginx SSL, HSTS header, WSS |
| **Edge** | DDoS + Bot protection | Cloudflare WAF + rate limit |
| **Authentication** | JWT + Refresh Token rotation | jjwt 0.12.3, short-lived access (15min) |
| **Authorization** | RBAC + Resource-level | Spring Security, @PreAuthorize, conversation membership |
| **Rate Limiting** | Token Bucket per user + per IP | Redis-based, configurable per endpoint |
| **Input Validation** | Jakarta Validation + sanitizer | XSS filter (OWASP Java HTML Sanitizer), message size limit |
| **Media Security** | Presigned URL + virus scan | S3 presigned (15min), ClamAV async scan |
| **Encryption at Rest** | Database encryption | MongoDB KMIP, PostgreSQL TDE, Redis AUTH + TLS |
| **Audit Trail** | Immutable log | Kafka → Audit log service → S3 cold storage |
| **Secrets** | No hardcoded secrets | HashiCorp Vault, env injection |

### 15.2 CORS Configuration

```java
@Configuration
public class SecurityConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
            "https://vivumate.com",
            "https://app.vivumate.com"
            // Dev: "http://localhost:3000"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-ID"));
        config.setExposedHeaders(List.of("X-Request-ID", "X-Rate-Limit-Remaining"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/ws-connect/**", config);
        return source;
    }
}
```

### 15.3 Security Headers (Nginx)

```nginx
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains; preload" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header X-XSS-Protection "1; mode=block" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Content-Security-Policy "default-src 'self'; img-src 'self' https://cdn.vivumate.com; connect-src 'self' wss://api.vivumate.com" always;
add_header Permissions-Policy "camera=(), microphone=(), geolocation=()" always;
```

### 15.4 WebSocket Origin Validation

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-connect")
            .setAllowedOrigins(
                "https://vivumate.com",
                "https://app.vivumate.com"
                // "http://localhost:3000" — dev only
            )
            .withSockJS()
            .setHeartbeatTime(25_000);
    }
}
```

### 15.5 Rate Limiting Tiers

| Tier | Endpoint | Limit | Window |
|------|----------|-------|--------|
| Send message (per user) | `/app/chat.send` | 60 messages | 1 minute |
| API (per user, authenticated) | `/api/**` | 300 requests | 1 minute |
| API (per IP, unauthenticated) | `/api/auth/**` | 20 requests | 1 minute |
| Login attempts | `/api/v1/auth/login` | 5 attempts | 10 minutes |
| Create group | `/api/v1/conversations/group` | 5 creates | 1 hour |
| Media upload | `/api/v1/media/upload` | 30 uploads | 1 hour |
| WebSocket connections | — | 5 per user | Concurrent |

---

## 16. Environment Configuration

### 16.1 application.yml (Production)

```yaml
spring:
  # Virtual Threads
  threads:
    virtual:
      enabled: true

  # Data Sources
  datasource:
    url: ${POSTGRES_URL}
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}
    hikari:
      maximum-pool-size: 30
      minimum-idle: 5
      connection-timeout: 3000
      idle-timeout: 600000
      max-lifetime: 1800000
      pool-name: VivuMate-HikariCP

  # MongoDB
  data:
    mongodb:
      uri: ${MONGO_URI}                    # mongodb://user:pass@host1,host2,host3/vivumate?replicaSet=rs0
      auto-index-creation: false           # Manage indexes manually in production
    redis:
      cluster:
        nodes: ${REDIS_CLUSTER_NODES}      # host1:6379,host2:6379,...
        max-redirects: 3
      lettuce:
        cluster:
          refresh:
            adaptive: true
            period: 30s
      password: ${REDIS_PASSWORD}
      ssl:
        enabled: true

  # Kafka
  kafka:
    bootstrap-servers: ${KAFKA_BROKERS}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all                            # Wait for all ISR replicas
      retries: 3
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5
    consumer:
      group-id: vivumate-${spring.profiles.active}
      auto-offset-reset: earliest
      enable-auto-commit: false
      max-poll-records: 500
    listener:
      ack-mode: manual_immediate

# WebSocket
websocket:
  allowed-origins:
    - https://vivumate.com
    - https://app.vivumate.com
  heartbeat:
    server: 25000
    client: 25000

# Application
app:
  server:
    id: ${HOSTNAME}                        # K8s Pod name → unique server ID
  jwt:
    access-token:
      expiry: 900                          # 15 minutes
    refresh-token:
      expiry: 604800                       # 7 days
  rate-limit:
    messages-per-minute: 60
    api-per-minute: 300
    login-per-10min: 5
  media:
    max-image-size: 10485760               # 10MB
    max-file-size: 52428800                # 50MB
    max-video-size: 104857600              # 100MB
    allowed-mime-types:
      - image/jpeg
      - image/png
      - image/gif
      - image/webp
      - application/pdf
      - video/mp4

# Actuator (for Prometheus scrape)
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true

# Logging
logging:
  pattern:
    console: '{"timestamp":"%d{ISO8601}","level":"%p","logger":"%logger{36}","traceId":"%X{traceId}","spanId":"%X{spanId}","message":"%m"}%n'
  level:
    root: INFO
    com.vivumate: DEBUG
    org.springframework.web.socket: INFO
```

---

## 17. Kafka Topic Design

### 17.1 Topic Configuration

| Topic | Partitions | Replication Factor | Retention | Consumer Groups | Key Strategy |
|-------|-----------|-------------------|-----------|----------------|--------------|
| `chat.messages` | 12 | 3 | 7 days | notification-svc, search-indexer, analytics | conversationId (co-locate) |
| `chat.notifications` | 6 | 3 | 3 days | notification-svc | userId (ordered per user) |
| `chat.presence` | 6 | 2 | 1 hour | analytics | userId |
| `chat.analytics` | 12 | 2 | 30 days | analytics-svc | conversationId |
| `chat.search-index` | 6 | 3 | 3 days | search-indexer | messageId |
| `chat.audit` | 3 | 3 | 90 days | audit-svc | userId |
| `chat.dlq` | 3 | 3 | 14 days | ops team (manual) | — |

### 17.2 Event Schemas

```java
// Base event
public record ChatEvent(
    String eventType,       // MESSAGE_SENT, MESSAGE_EDITED, MESSAGE_DELETED, etc.
    String conversationId,
    Long senderId,
    Instant occurredAt,
    String traceId          // OpenTelemetry trace correlation
) {}

// Message event (topic: chat.messages)
public record MessageSentEvent(
    String eventType,
    String messageId,
    String conversationId,
    Long senderId,
    String contentType,
    String contentPreview,  // truncated, for notifications
    List<Long> recipientIds,
    List<Long> mentionedUserIds,
    Instant sentAt,
    String traceId
) {}

// Notification event (topic: chat.notifications)
public record PushNotificationEvent(
    String eventType,       // PUSH_REQUIRED, IN_APP
    Long targetUserId,
    String title,
    String body,
    Map<String, String> data,  // conversationId, messageId for deep linking
    String priority,        // HIGH (mention/DM), NORMAL (group)
    Instant createdAt
) {}
```

### 17.3 Dead Letter Queue Handling

```java
@KafkaListener(topics = "${chat.topics.messages}", groupId = "notification-svc")
public void consume(ConsumerRecord<String, MessageSentEvent> record,
                    Acknowledgment ack) {
    try {
        processNotification(record.value());
        ack.acknowledge();
    } catch (RetryableException e) {
        // Spring Kafka RetryTemplate: 3 retries with backoff
        throw e;  // Let retry mechanism handle
    } catch (Exception e) {
        log.error("Non-retryable error, sending to DLQ", e);
        dlqTemplate.send("chat.dlq", record.key(), record.value());
        ack.acknowledge();  // Don't reprocess
    }
}
```

---

## 18. Error Handling Strategy

### 18.1 Error Code Convention

```java
public enum ErrorCode {
    // Success
    SUCCESS(1000, "Success", HttpStatus.OK),

    // Authentication (1001–1099)
    UNAUTHENTICATED(1001, "Authentication required", HttpStatus.UNAUTHORIZED),
    INVALID_TOKEN(1002, "Invalid or expired token", HttpStatus.UNAUTHORIZED),
    TOKEN_BLACKLISTED(1003, "Token has been revoked", HttpStatus.UNAUTHORIZED),
    ACCOUNT_DISABLED(1004, "Account is disabled", HttpStatus.FORBIDDEN),

    // Authorization (1100–1199)
    FORBIDDEN(1100, "Access denied", HttpStatus.FORBIDDEN),
    NOT_CONVERSATION_MEMBER(1101, "Not a member of this conversation", HttpStatus.FORBIDDEN),
    INSUFFICIENT_ROLE(1102, "Insufficient role for this operation", HttpStatus.FORBIDDEN),

    // Validation (1200–1299)
    INVALID_INPUT(1200, "Invalid input data", HttpStatus.BAD_REQUEST),
    MESSAGE_TOO_LONG(1201, "Message exceeds maximum length (4096 chars)", HttpStatus.BAD_REQUEST),
    UNSUPPORTED_MEDIA_TYPE(1202, "File type not supported", HttpStatus.BAD_REQUEST),
    FILE_TOO_LARGE(1203, "File exceeds size limit", HttpStatus.BAD_REQUEST),

    // Business Logic (1300–1499)
    USER_NOT_FOUND(1300, "User not found", HttpStatus.NOT_FOUND),
    CONVERSATION_NOT_FOUND(1301, "Conversation not found", HttpStatus.NOT_FOUND),
    MESSAGE_NOT_FOUND(1302, "Message not found", HttpStatus.NOT_FOUND),
    ALREADY_FRIENDS(1310, "Already friends", HttpStatus.CONFLICT),
    USER_BLOCKED(1311, "Cannot perform action on blocked user", HttpStatus.FORBIDDEN),
    GROUP_FULL(1312, "Group has reached maximum member limit", HttpStatus.CONFLICT),
    CANNOT_MESSAGE_SELF(1313, "Cannot create DM with yourself", HttpStatus.BAD_REQUEST),
    EDIT_TIME_EXPIRED(1320, "Message can only be edited within 15 minutes", HttpStatus.FORBIDDEN),
    DELETE_TIME_EXPIRED(1321, "Message can only be deleted for everyone within 1 hour", HttpStatus.FORBIDDEN),

    // Rate Limiting (1400–1499)
    RATE_LIMIT_EXCEEDED(1400, "Too many requests", HttpStatus.TOO_MANY_REQUESTS),
    MESSAGE_RATE_LIMIT(1401, "Message rate limit exceeded (60/min)", HttpStatus.TOO_MANY_REQUESTS),

    // Server Errors (5000–5099)
    INTERNAL_SERVER_ERROR(5000, "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR),
    DATABASE_ERROR(5001, "Database operation failed", HttpStatus.SERVICE_UNAVAILABLE),
    SERVICE_UNAVAILABLE(5002, "Service temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE);

    ErrorCode(int code, String message, HttpStatus httpStatus) { ... }
}
```

### 18.2 Global Exception Handler

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(
            AppException e, HttpServletRequest request) {
        log.warn("AppException: {} | path: {}", e.getErrorCode().getMessage(), request.getRequestURI());
        return ResponseEntity
            .status(e.getErrorCode().getHttpStatus())
            .body(ApiResponse.error(e.getErrorCode()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException e) {
        Map<String, String> errors = e.getBindingResult()
            .getFieldErrors()
            .stream()
            .collect(Collectors.toMap(
                FieldError::getField,
                fe -> Objects.requireNonNullElse(fe.getDefaultMessage(), "Invalid value")
            ));
        return ResponseEntity.badRequest()
            .body(ApiResponse.validationError(errors));
    }
}
```

### 18.3 WebSocket Error Delivery

```java
// Gửi error về client qua /user/queue/errors
public void sendWebSocketError(String userId, ErrorCode code, String clientMessageId) {
    WebSocketError error = WebSocketError.builder()
        .code(code.getCode())
        .message(code.getMessage())
        .clientMessageId(clientMessageId)  // Correlate với request
        .timestamp(Instant.now())
        .build();

    messagingTemplate.convertAndSendToUser(
        userId, "/queue/errors", error
    );
}

// Client subscribes to /user/queue/errors
// On error: show toast, remove optimistic message, allow retry
```

---

## 19. Testing Strategy

### 19.1 Test Pyramid

| Layer | Type | Framework | Coverage Target | Scope |
|-------|------|-----------|----------------|-------|
| Unit | Service/Domain logic | JUnit 5 + Mockito | 80%+ | Business rules, validators, mappers |
| Integration | Repository layer | Testcontainers (Mongo, PG, Redis) | 70%+ | DB queries, indexes, Redis ops |
| Integration | REST API | Spring Boot Test + MockMvc | 90%+ | All endpoints, auth, error codes |
| WebSocket | STOMP integration | StompClient test | 80%+ | Connect, send, receive, errors |
| Load | WS throughput | Gatling / k6 | — | Baseline + peak load scenarios |
| E2E | Full flow | Playwright / Selenium | Critical paths | Register → chat flow |

### 19.2 WebSocket Integration Test Example

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WebSocketIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine")
        .withExposedPorts(6379);

    @LocalServerPort
    int port;

    private StompSession session;

    @BeforeEach
    void connectWebSocket() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(
            new StandardWebSocketClient()
        );
        client.setMessageConverter(new MappingJackson2MessageConverter());

        String url = "ws://localhost:" + port + "/ws-connect";
        StompHeaders headers = new StompHeaders();
        headers.set("Authorization", "Bearer " + getTestToken());

        session = client.connectAsync(url, new StompSessionHandlerAdapter() {}, headers)
                        .get(5, TimeUnit.SECONDS);
    }

    @Test
    void sendMessage_shouldDeliverToRecipient() throws Exception {
        BlockingQueue<MessageResponse> received = new LinkedBlockingQueue<>();

        // Subscribe recipient
        session.subscribe("/user/queue/messages", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) { return MessageResponse.class; }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((MessageResponse) payload);
            }
        });

        // Send message
        SendMessageRequest request = SendMessageRequest.builder()
            .conversationId("test-conv-id")
            .contentType("TEXT")
            .content(Map.of("text", "Hello integration test!"))
            .clientMessageId(UUID.randomUUID().toString())
            .build();

        session.send("/app/chat.send", request);

        // Assert delivery within 3 seconds
        MessageResponse msg = received.poll(3, TimeUnit.SECONDS);
        assertThat(msg).isNotNull();
        assertThat(msg.getContent().get("text")).isEqualTo("Hello integration test!");
        assertThat(msg.getStatus()).isEqualTo("SENT");
    }
}
```

### 19.3 Load Test Scenarios (k6/Gatling)

```
Scenario 1 — Baseline (steady state)
  - 1,000 concurrent WebSocket connections
  - 100 messages/second total
  - Duration: 10 minutes
  - Success criteria: P99 latency < 200ms, 0 message loss

Scenario 2 — Peak load
  - 10,000 concurrent connections
  - 1,000 messages/second
  - Duration: 5 minutes spike
  - Success criteria: P99 < 500ms, error rate < 1%

Scenario 3 — Server failover
  - 1,000 connections
  - Kill 1 of 3 Chat Server pods
  - Measure: reconnect time, message loss
  - Success criteria: < 30s recovery, 0 message loss
```

---

## 20. DevOps & Infrastructure

### 20.1 CI/CD Pipeline

```mermaid
graph LR
    subgraph "CI"
        CODE["📝 git push"]
        BUILD["🔨 mvn compile\n+ test"]
        SCAN["🔍 SonarQube\nOWASP dep-check"]
        DOCKER["🐳 docker build\n(multi-stage)"]
        PUSH["📤 Push to\nContainer Registry"]
    end

    subgraph "CD"
        STAGE["🟡 Deploy Staging\n(K8s namespace)"]
        SMOKE["🧪 Smoke Tests\n+ E2E (critical paths)"]
        APPROVE["👤 Manual Gate\n(Tech Lead approval)"]
        PROD["🟢 Rolling Deploy\nProduction"]
        MONITOR["📊 Post-deploy\nSLI/SLO check\n(15min window)"]
    end

    CODE --> BUILD --> SCAN --> DOCKER --> PUSH
    PUSH --> STAGE --> SMOKE --> APPROVE --> PROD --> MONITOR
```

### 20.2 Dockerfile (Multi-stage, Production)

```dockerfile
# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN ./mvnw dependency:go-offline -q
COPY src src
RUN ./mvnw package -DskipTests -q

# Stage 2: Extract layers (Spring Boot layered jar)
FROM builder AS extractor
RUN java -Djarmode=layertools -jar target/vivumate-chat.jar extract

# Stage 3: Runtime (minimal JRE)
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

COPY --from=extractor --chown=app:app /build/dependencies/ ./
COPY --from=extractor --chown=app:app /build/snapshot-dependencies/ ./
COPY --from=extractor --chown=app:app /build/spring-boot-loader/ ./
COPY --from=extractor --chown=app:app /build/application/ ./

USER app
EXPOSE 8080 8081

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"status":"UP"'

ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:+ZGenerational", \
    "-XX:MaxRAMPercentage=75", \
    "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE}", \
    "org.springframework.boot.loader.launch.JarLauncher"]
```

### 20.3 Kubernetes Production Architecture

```yaml
# Namespace
apiVersion: v1
kind: Namespace
metadata:
  name: vivumate-prod
  labels:
    environment: production
---
# Chat Server Deployment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: chat-server
  namespace: vivumate-prod
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0        # Zero downtime
  selector:
    matchLabels:
      app: chat-server
  template:
    metadata:
      labels:
        app: chat-server
        version: "1.0.0"
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/path: "/actuator/prometheus"
        prometheus.io/port: "8081"
    spec:
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: DoNotSchedule
          labelSelector:
            matchLabels:
              app: chat-server
      containers:
        - name: chat-server
          image: registry.vivumate.com/chat-server:latest
          ports:
            - containerPort: 8080   # App
            - containerPort: 8081   # Actuator
          env:
            - name: HOSTNAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: SPRING_PROFILES_ACTIVE
              value: "production"
          envFrom:
            - secretRef:
                name: chat-server-secrets
            - configMapRef:
                name: chat-server-config
          resources:
            requests:
              cpu: "500m"
              memory: "512Mi"
            limits:
              cpu: "2000m"
              memory: "2Gi"
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8081
            initialDelaySeconds: 30
            periodSeconds: 10
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8081
            initialDelaySeconds: 15
            periodSeconds: 5
            failureThreshold: 3
          lifecycle:
            preStop:
              exec:
                command: ["sleep", "10"]  # Drain connections gracefully
---
# HPA
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: chat-server-hpa
  namespace: vivumate-prod
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: chat-server
  minReplicas: 3
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60
    - type: Pods
      pods:
        metric:
          name: websocket_connections_active
        target:
          type: AverageValue
          averageValue: "40000"  # Scale when avg > 40K connections/pod
```

### 20.4 Database Migration Strategy

```java
// Flyway migration — PostgreSQL
// File: src/main/resources/db/migration/V1__initial_schema.sql
// File: src/main/resources/db/migration/V2__add_user_devices.sql

// Mongock changeLog — MongoDB
@ChangeUnit(id = "create-conversations-indexes", order = "001")
public class CreateConversationsIndexes {

    @Execution
    public void createIndexes(MongoTemplate mongoTemplate) {
        mongoTemplate.indexOps("conversations")
            .ensureIndex(new Index()
                .on("participantIds", Sort.Direction.ASC)
                .on("lastActivityAt", Sort.Direction.DESC)
                .named("idx_participants_activity")
            );
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        mongoTemplate.indexOps("conversations").dropIndex("idx_participants_activity");
    }
}
```

---

## 21. Danh Sách Bài Toán & Ưu Tiên

### Foundation (Phase 1–3) — MVP

| # | Bài toán | Mô tả | Priority |
|---|---------|-------|----------|
| 1 | **WebSocket Infrastructure** | Spring STOMP setup, auth interceptor, session manager | 🔴 Critical |
| 2 | **Message Send/Receive** | Send, receive, real-time delivery | 🔴 Critical |
| 3 | **Message CRUD** | Edit (15min), delete (for me / for everyone 1h), reply | 🔴 Critical |
| 4 | **Conversation Management** | Create DM + Group, member ops | 🔴 Critical |
| 5 | **Presence System** | ONLINE/AWAY/OFFLINE + Redis TTL + friend broadcast | 🔴 Critical |
| 6 | **Unread Count** | Redis counter + MongoDB sync, total badge | 🔴 Critical |
| 7 | **Multi-device Sync** | Delta sync on reconnect, echo to all devices | 🔴 Critical |
| 8 | **Message Read Status** | Watermark, delivery/seen receipt | 🟡 High |
| 9 | **Typing Indicator** | Redis TTL, broadcast per conversation | 🟡 High |
| 10 | **Friendship System** | Request, accept, block, unfriend | 🟡 High |

### Growth (Phase 4–6)

| # | Bài toán | Mô tả | Priority |
|---|---------|-------|----------|
| 11 | **Media Upload** | Image/file/video → S3/MinIO + presigned URL | 🟡 High |
| 12 | **Cross-Server Routing** | Redis Pub/Sub multi-instance routing | 🟡 High |
| 13 | **Push Notifications** | FCM/APNs + Kafka async, respect mute/DND | 🟡 High |
| 14 | **Group Chat Advanced** | Roles matrix, pin, settings | 🟢 Medium |
| 15 | **Message Search** | Elasticsearch, Vietnamese ICU analyzer | 🟢 Medium |
| 16 | **Rate Limiting** | Redis Token Bucket, per-user/IP/endpoint | 🟢 Medium |
| 17 | **Message Reactions** | Emoji reactions, optimistic update | 🟢 Medium |
| 18 | **Mention System** | @username parse, priority notification | 🟢 Medium |

### Production-Ready (Phase 7–8)

| # | Bài toán | Mô tả | Priority |
|---|---------|-------|----------|
| 19 | **Fault Tolerance** | Circuit breaker, retry, fallback, DLQ | 🟢 Medium |
| 20 | **Monitoring & SLO** | Prometheus dashboards, alerting, SLI tracking | 🟢 Medium |
| 21 | **CI/CD Pipeline** | GitHub Actions → Docker → K8s + Helm | 🟢 Medium |
| 22 | **Database Scaling** | MongoDB sharding, PG read replicas, PgBouncer | 🔵 Plan now |
| 23 | **Content Moderation** | AI spam/abuse detection (async via Kafka) | 🔵 Low |
| 24 | **E2E Encryption** | Optional E2E for DM (Signal protocol) | 🔵 Low |
| 25 | **Voice/Video Calls** | WebRTC signaling server | 🔵 Low |
| 26 | **Analytics Dashboard** | DAU/MAU, message volume, engagement | 🔵 Low |

---

## 22. Implementation Roadmap — Step-by-Step

> **Nguyên tắc**: Mỗi Phase phải hoàn chỉnh và có test trước khi sang Phase tiếp theo. MVP (Phase 1–3) đủ để demo production scenarios.

---

### 📌 Phase 1: WebSocket Foundation
**Timeline: 2–3 tuần (completed)**

- [ ] **1.1 WebSocket Config**
  - [ ] `WebSocketConfig` implements `WebSocketMessageBrokerConfigurer`
  - [ ] STOMP endpoint `/ws-connect` + SockJS fallback
  - [ ] Broker: Simple → sau nâng Redis/RabbitMQ relay
  - [ ] App prefix `/app`, user prefix `/user`
  - [ ] Virtual Threads cho inbound/outbound channels

- [ ] **1.2 WebSocket Auth**
  - [ ] `WebSocketAuthInterceptor` implements `ChannelInterceptor`
  - [ ] JWT validation trên STOMP CONNECT frame
  - [ ] Token blacklist check (Redis)
  - [ ] Set `StompPrincipal` và session attributes (userId, username)
  - [ ] Graceful close on auth failure

- [ ] **1.3 Session Management**
  - [ ] `WebSocketSessionManager` (ConcurrentHashMap local + Redis global)
  - [ ] `WebSocketEventListener` → CONNECT, DISCONNECT events
  - [ ] Multi-device: `ws_routing:{userId}` → Set of serverIds
  - [ ] TTL management cho ws_routing keys

- [ ] **1.4 Heartbeat & Lifecycle**
  - [ ] STOMP heartbeat config (25s)
  - [ ] Redis TTL renewal on heartbeat
  - [ ] Graceful shutdown: drain connections trước khi pod restart

---

### 📌 Phase 2: Core Messaging
**Timeline: 3–4 tuần**

- [ ] **2.1 Send Message**
  - [ ] `@MessageMapping("/chat.send")` handler
  - [ ] Validate: membership, rate limit, content size (4KB), idempotency (clientMessageId)
  - [ ] Build + persist `MessageDocument` (writeConcern: majority)
  - [ ] ACK: `/user/queue/ack` `{clientMessageId, messageId, status: SENT}`
  - [ ] Parallel: update conversation + unread + route + Kafka publish

- [ ] **2.2 Message History (REST)**
  - [ ] `GET /conversations/{id}/messages?cursor={lastId}&limit=30`
  - [ ] Cursor-based (ObjectId), sort by `createdAt DESC`
  - [ ] Filter: `deletedFor`, `deletedForEveryone`

- [ ] **2.3 Edit Message**
  - [ ] `@MessageMapping("/chat.edit")` — sender only, within 15 min
  - [ ] Save to `editHistory`, set `isEdited = true`
  - [ ] Broadcast `MESSAGE_EDITED` event to all participants

- [ ] **2.4 Delete Message**
  - [ ] "Delete for me": add to `deletedFor[]`
  - [ ] "Delete for everyone": set `deletedForEveryone = true` (sender only, 1h window)
  - [ ] Update conversation `lastMessage` nếu deleted message là last
  - [ ] Broadcast `MESSAGE_DELETED` event

- [ ] **2.5 Reply + Mention**
  - [ ] `replyTo` snapshot in message
  - [ ] Parse `@username` mentions → `mentions[]` array
  - [ ] Increment `unreadMentions` counter

---

### 📌 Phase 3: Conversation Management
**Timeline: 2–3 tuần**

- [ ] **3.1 Direct Message**
  - [ ] `POST /conversations/direct {recipientId}`
  - [ ] Idempotent: `dmHash` unique constraint
  - [ ] Validate: not blocked, is friend (if setting enabled)

- [ ] **3.2 Group Chat Creation**
  - [ ] `POST /conversations/group {name, memberIds[], ...}`
  - [ ] 3–500 members, creator = OWNER
  - [ ] System message "X đã tạo nhóm"
  - [ ] WS broadcast `GROUP_CREATED` to all members

- [ ] **3.3 Group Member Management**
  - [ ] Add/Remove members (ADMIN+), Leave (any)
  - [ ] OWNER transfer before leave
  - [ ] System messages cho mọi thay đổi
  - [ ] Permission matrix enforcement

- [ ] **3.4 Conversation List**
  - [ ] `GET /conversations?cursor&limit=20` — sorted by `lastActivityAt DESC`
  - [ ] Include: last_message preview, unread count, participant info
  - [ ] Cache page 1 in Redis (TTL 5min)

---

### 📌 Phase 4: Presence, Typing & Read Status
**Timeline: 2 tuần**

- [ ] **4.1 Presence**
  - [ ] Redis key: `presence:{userId}` → Hash, TTL 5min
  - [ ] ONLINE → AWAY (2min idle), AWAY/ONLINE → OFFLINE (disconnect/TTL)
  - [ ] Redis keyspace notifications for TTL expiry detection
  - [ ] Broadcast to online friends (from Redis `friends:{userId}` cache)

- [ ] **4.2 Typing Indicator**
  - [ ] `@MessageMapping("/chat.typing")` {conversationId, isTyping}
  - [ ] Redis SET `typing:{convId}` TTL 3s, broadcast to participants
  - [ ] Client: resend every 2s while typing

- [ ] **4.3 Read Status (Watermark)**
  - [ ] `message_read_states` — watermark per user per conversation
  - [ ] `@MessageMapping("/chat.read")` — update watermark, reset unread
  - [ ] Broadcast read/delivered receipts to sender via cross-server routing

- [ ] **4.4 Unread Count**
  - [ ] Redis: `unread:{userId}:{convId}` + `unread:total:{userId}`
  - [ ] Increment on send, reset on mark-as-read
  - [ ] Periodic consistency sync (Redis ↔ MongoDB) — scheduled job

---

### 📌 Phase 5: Friendship & User Features
**Timeline: 2 tuần**

- [ ] **5.1 Friendship (PostgreSQL)**
  - [ ] Friend request, accept/reject, unfriend
  - [ ] Block/Unblock, check block before message/friend req
  - [ ] Cache friend list in Redis (TTL 1h), invalidate on change
  - [ ] WS notification on friend request

- [ ] **5.2 User Settings**
  - [ ] Notification preferences, privacy settings, device management
  - [ ] `POST /auth/logout-all` → revoke all refresh tokens

- [ ] **5.3 Media Upload**
  - [ ] `POST /media/upload` (small ≤ 5MB)
  - [ ] `POST /media/presign` (large files via presigned URL)
  - [ ] Image resize/thumbnail (Thumbnailator)
  - [ ] MIME validation, virus scan async
  - [ ] S3/MinIO config với lifecycle policy

---

### 📌 Phase 6: Scaling & Async Events
**Timeline: 2–3 tuần**

- [ ] **6.1 Cross-Server Routing**
  - [ ] `CrossServerMessageRouter` + Redis Pub/Sub
  - [ ] Server ID từ `${HOSTNAME}` (K8s Pod name)
  - [ ] Subscribe `chat:server:{serverId}` on startup

- [ ] **6.2 Kafka Event Streaming**
  - [ ] Topics: `chat.messages`, `chat.notifications`, `chat.search-index`, `chat.analytics`
  - [ ] Producer: publish after message persist (không block main flow)
  - [ ] DLQ strategy for failed consumers

- [ ] **6.3 Push Notification Service**
  - [ ] Kafka consumer `chat.notifications`
  - [ ] FCM (Android) + APNs (iOS) integration
  - [ ] Respect mute settings, DND mode, `notification_preview`
  - [ ] Device token management (register, refresh, cleanup stale)

- [ ] **6.4 Elasticsearch Search**
  - [ ] Kafka consumer `chat.search-index` → async indexing
  - [ ] Index mapping với Vietnamese ICU analyzer
  - [ ] `GET /search/messages?q={keyword}&conversationId={id}&cursor&limit`

---

### 📌 Phase 7: Production Hardening
**Timeline: 2–3 tuần**

- [ ] **7.1 Resilience**
  - [ ] Resilience4j: circuit breaker cho MongoDB, Redis, Kafka
  - [ ] Retry với exponential backoff + jitter
  - [ ] Fallback: cache-first khi DB down
  - [ ] Bulkhead: isolate thread pool per service category

- [ ] **7.2 Rate Limiting**
  - [ ] Redis Token Bucket implementation
  - [ ] Per-user: 60 msg/min, 300 API req/min
  - [ ] Per-IP: 20 req/min (unauthenticated)
  - [ ] `429 Too Many Requests` + `Retry-After` + `X-Rate-Limit-Remaining` headers

- [ ] **7.3 Monitoring & Observability**
  - [ ] Custom Micrometer metrics: `ws.connections.active`, `messages.sent.total`, `message.latency.p99`
  - [ ] Grafana dashboards: Chat Cluster Health, Message Pipeline, DB Performance
  - [ ] AlertManager rules: CPU > 80%, error rate > 5%, P99 latency > 500ms, consumer lag > 10K
  - [ ] Distributed tracing: OpenTelemetry + Jaeger
  - [ ] Structured JSON logging + correlation ID propagation

- [ ] **7.4 DB Optimization**
  - [ ] MongoDB: connection pool, write concern tuning, slow query profiler
  - [ ] PostgreSQL: HikariCP sizing, EXPLAIN ANALYZE, PgBouncer
  - [ ] Redis: pipeline operations, Lua scripts cho atomic unread updates
  - [ ] Periodic: unused index cleanup, compound index review

- [ ] **7.5 Security Hardening**
  - [ ] OWASP Java HTML Sanitizer cho message content
  - [ ] ClamAV async virus scan trên media upload
  - [ ] WebSocket connection limit per user (max 5 devices)
  - [ ] Audit logging: login, permission change, message deletion
  - [ ] CORS + CSP + security headers

---

### 📌 Phase 8: Advanced Features & DevOps
**Timeline: 3–4 tuần (ongoing)**

- [ ] **8.1 CI/CD Pipeline**
  - [ ] GitHub Actions: build → test → SonarQube → Docker → staging → prod
  - [ ] Blue-green deployment strategy
  - [ ] Flyway + Mongock migration automated

- [ ] **8.2 Containerization**
  - [ ] Multi-stage Dockerfile (JRE-only final stage, ZGC GC)
  - [ ] K8s manifests: Deployment, Service, HPA, PodDisruptionBudget
  - [ ] Helm charts cho reproducible deployments
  - [ ] Resource limits tuning từ load test data

- [ ] **8.3 Database Scaling**
  - [ ] MongoDB: Replica Set (1P + 2S) → Sharding khi data > 500GB
  - [ ] PostgreSQL: Primary + 2 Read Replicas + PgBouncer
  - [ ] Redis: Cluster mode (3M + 3R)

- [ ] **8.4 Advanced Chat Features**
  - [ ] Message forwarding
  - [ ] Poll/Survey trong group chat
  - [ ] Voice messages (pre-recorded audio)
  - [ ] Voice/Video calls (WebRTC signaling)
  - [ ] Message translation (AI-powered)

- [ ] **8.5 Testing**
  - [ ] Gatling load test scripts (baseline + peak + failover)
  - [ ] E2E Playwright tests cho critical paths
  - [ ] Chaos engineering: kill pods, network partition, Redis failover
  - [ ] Target: 80%+ coverage on service/domain layer

---

## 23. Tổng Kết Dependencies

```xml
<!-- pom.xml -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.0</version>
</parent>

<properties>
    <java.version>21</java.version>
    <spring-cloud.version>2024.0.x</spring-cloud.version>
</properties>

<dependencies>
    <!-- === Core === -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- === Data === -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-mongodb</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- === Messaging === -->
    <dependency>
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka</artifactId>
    </dependency>

    <!-- === Cloud Gateway === -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-gateway</artifactId>
    </dependency>

    <!-- === Resilience === -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
    </dependency>

    <!-- === Security / JWT === -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.3</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.12.3</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.12.3</version>
        <scope>runtime</scope>
    </dependency>

    <!-- === Media === -->
    <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>s3</artifactId>
        <version>2.25.60</version>
    </dependency>
    <dependency>
        <groupId>net.coobird</groupId>
        <artifactId>thumbnailator</artifactId>
        <version>0.4.20</version>
    </dependency>

    <!-- === Search === -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
    </dependency>

    <!-- === Caching === -->
    <dependency>
        <groupId>com.github.ben-manes.caffeine</groupId>
        <artifactId>caffeine</artifactId>
    </dependency>

    <!-- === Observability === -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-tracing-bridge-otel</artifactId>
    </dependency>
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>

    <!-- === DB Migration === -->
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
        <groupId>io.mongock</groupId>
        <artifactId>mongock-springboot-v3</artifactId>
        <version>5.4.4</version>
    </dependency>

    <!-- === Security Utils === -->
    <dependency>
        <groupId>org.owasp.antisamy</groupId>
        <artifactId>antisamy</artifactId>
        <version>1.7.5</version>
    </dependency>

    <!-- === Utils === -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
        <version>1.6.x</version>
    </dependency>

    <!-- === Testing === -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>mongodb</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

> [!CAUTION]
> **Không implement tất cả cùng lúc.** Tuân theo roadmap Phase 1 → 8. Mỗi phase phải hoàn thành và có test coverage đạt target trước khi sang phase tiếp theo. MVP (Phase 1–3) đủ để demo và validate real-world scenarios.

> [!TIP]
> **Quick Win**: Phase 1 + 2 có thể hoàn thành trong 4–5 tuần nếu focus. Đây là foundation quan trọng nhất — tất cả các phase sau đều xây trên nền này. Validate SLA targets ở mỗi milestone với load test nhỏ trước khi tiếp tục.

> [!NOTE]
> **ADR là sống**: Khi có quyết định kiến trúc mới, hãy thêm vào Section 2. Điều này giúp team mới onboard nhanh hơn và tránh re-debate các vấn đề đã giải quyết.
