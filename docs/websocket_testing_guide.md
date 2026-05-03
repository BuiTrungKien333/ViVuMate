# 🔌 WebSocket Infrastructure — Testing Guide

## Tổng quan

Bộ test bao gồm **7 nhóm kiểm thử** với **15 kịch bản cụ thể**, kiểm tra toàn bộ infrastructure đã xây dựng qua Round 1–4.

### Công cụ cần thiết

| Tool | Mục đích |
|---|---|
| [Test Client HTML](file:///e:/VivuMateProject/src/test/resources/websocket-test-client.html) | Test client trực quan trong browser |
| Redis CLI (`redis-cli`) | Kiểm chứng trạng thái Redis |
| Server logs (IntelliJ console) | Xác nhận log events |
| 2+ browser tabs/windows | Test multi-device + session limit |

### Khởi động

```bash
# 1. Start Redis + MongoDB + PostgreSQL (nếu dùng Docker Compose)
docker compose up -d

# 2. Start Spring Boot app (profile dev)
./mvnw spring-boot:run

# 3. Mở test client trong browser
# File → Open: src/test/resources/websocket-test-client.html
```

> [!IMPORTANT]
> Đảm bảo `FRONTEND_URL` env var cho phép origin `null` hoặc `file://` khi test từ local HTML file. 
> Cách đơn giản nhất: set `FRONTEND_URL=*` trong env dev, hoặc mở test client qua `http://localhost:8080` nếu được serve bởi Spring.

---

## Test 1: Authentication (STOMP CONNECT)

### 1.1 ✅ Happy path — Connect thành công

**Bước:**
1. Mở test client HTML
2. Nhập username/password → Click **🔑 Login & Get Token**
3. Console hiện `✓ Login OK — userId=..., username=...`
4. Click **▶ Connect**
5. Console hiện `✓ CONNECTED — session=...`

**Kiểm chứng server log:**
```
WEBSOCKET_AUTH_INTERCEPTOR - AUTH_OK: userId=1, username=admin, sessionId=..., currentSessions=0
WEBSOCKET_EVENT_LISTENER  - SESSION_CONNECT: userId=1, username=admin, sessionId=...
WEBSOCKET_SESSION_MANAGER - Session registered. userId=1, sessionId=..., localSessions=1/10
```

**Kiểm chứng Redis:**
```bash
redis-cli

# Routing key phải tồn tại
EXISTS ws_routing:1
# → (integer) 1

# Giá trị là serverId
SMEMBERS ws_routing:1
# → "local-dev"

# TTL phải gần 90s
TTL ws_routing:1
# → (integer) ~89
```

### 1.2 ❌ No token — Bị reject

**Bước:**
1. Xóa trắng ô JWT Token
2. Click **▶ Connect**

**Expected:** Console hiện `✗ STOMP ERROR` hoặc `✗ WebSocket CLOSED`

**Server log:** `WEBSOCKET_AUTH_INTERCEPTOR - AUTH_FAIL: No Bearer token. sessionId=...`

### 1.3 ❌ Invalid/Expired token — Bị reject

**Bước:**
1. Paste một token rác: `eyJhbGciOiJIUzI1NiJ9.invalid.token`
2. Click **▶ Connect**

**Expected:** Connection bị reject

**Server log:** `WEBSOCKET_AUTH_INTERCEPTOR - AUTH_FAIL: Invalid JWT. sessionId=..., reason=...`

### 1.4 ❌ Blacklisted token (sau logout)

**Bước:**
1. Login → lấy token → Connect thành công → Disconnect
2. Gọi `POST /api/v1/auth/logout` với token đó (dùng Postman/cURL):
   ```bash
   curl -X POST http://localhost:8080/api/v1/auth/logout \
     -H "Authorization: Bearer <access_token>" \
     -H "Content-Type: application/json" \
     -d '{"refreshToken":"<refresh_token>"}'
   ```
3. Paste lại token cũ → Click **▶ Connect**

**Expected:** Connection bị reject

**Server log:** `WEBSOCKET_AUTH_INTERCEPTOR - AUTH_FAIL: Blacklisted token. sessionId=...`

---

## Test 2: Session Management

### 2.1 Multi-device (2 sessions cùng user)

**Bước:**
1. Login lấy token (user A)
2. Mở **2 browser tabs**, paste cùng token, Connect cả 2

**Kiểm chứng server log:**
```
# Tab 1:
Session registered. userId=1, ..., localSessions=1/10
# Tab 2:
Session registered. userId=1, ..., localSessions=2/10
```

**Redis:**
```bash
SMEMBERS ws_routing:1
# → "local-dev" (1 entry vì cùng server)
```

### 2.2 Disconnect → Session cleanup

**Bước:**
1. Từ test 2.1, đóng 1 tab (hoặc click Disconnect)
2. Kiểm tra server log

**Server log:**
```
SESSION_DISCONNECT: userId=1, sessionId=..., remainingSessions=1, closeStatus=...
```

**Bước tiếp:** Đóng tab còn lại

**Server log:**
```
SESSION_DISCONNECT: userId=1, sessionId=..., remainingSessions=0, closeStatus=...
WEBSOCKET_SESSION_MANAGER - All local sessions closed. userId=1, serverId removed from routing
```

**Redis:**
```bash
EXISTS ws_routing:1
# → (integer) 0    ← key đã bị xóa
```

---

## Test 3: Session Limit (max-per-user)

> [!NOTE]
> Dev profile: `max-per-user: 10`. Để test nhanh, tạm đổi thành `2` trong `application-dev.yml`.

### 3.1 Vượt giới hạn session

**Chuẩn bị:** Set `vivumate.websocket.session.max-per-user: 2` trong `application-dev.yml`, restart server

**Bước:**
1. Login lấy token
2. Mở 3 browser tabs, paste cùng token
3. Connect tab 1 → ✅
4. Connect tab 2 → ✅
5. Connect tab 3 → ❌ Bị reject

**Server log (tab 3):**
```
AUTH_FAIL: Session limit reached. userId=1, sessionId=..., limit=2
```

**Sau khi test xong:** Đổi lại `max-per-user: 10` và restart

---

## Test 4: Rate Limiting

### 4.1 Bình thường — Send dưới giới hạn

**Bước:**
1. Connect thành công
2. Click **Send** vài lần

**Expected:** Tất cả gửi thành công, counter Sent tăng, không có Errors

### 4.2 🔥 Flood test — Vượt giới hạn

> [!NOTE]
> Dev limit: 300 msgs/60s. Flood test gửi 150 messages liên tục — sẽ không vượt trong dev. Để test rate limit thực tế, tạm giảm `max-sends-per-window: 50` trong `application-dev.yml`.

**Chuẩn bị:** Set `vivumate.websocket.rate-limit.max-sends-per-window: 50`, restart

**Bước:**
1. Connect thành công
2. Click **🔥 Flood Test (send ×150)**
3. Quan sát console — sau ~50 messages sẽ nhận error

**Server log:**
```
RATE_LIMIT: Exceeded. userId=1, count=51, limit=50/60s, destination=/app/chat.send
```

**Redis verification:**
```bash
GET ws_rate:1
# → "150" (hoặc số message đã gửi)

TTL ws_rate:1
# → (integer) ~55   ← còn lại trong window 60s
```

**Sau khi test:** Đổi lại `max-sends-per-window: 300`, restart. Hoặc đợi 60s để window reset.

---

## Test 5: TTL Renewal (Redis Pipeline)

### 5.1 Key TTL được renew tự động

**Bước:**
1. Connect thành công
2. Chờ 30–40 giây (renewal interval)
3. Kiểm tra Redis TTL

**Redis (lặp lại mỗi 30s):**
```bash
TTL ws_routing:1
# Lần 1 (ngay sau connect): ~89
# Lần 2 (sau 30s):          ~89  ← TTL được reset lại 90s
# Lần 3 (sau 60s):          ~89  ← Vẫn reset
```

**Server log (mỗi 30s):**
```
SESSION_HEARTBEAT: renewed=1/1, totalSessions=1
```

### 5.2 Self-healing — Key bị xóa manual

**Bước:**
1. Connect thành công
2. Xóa key trong Redis manual:
   ```bash
   DEL ws_routing:1
   ```
3. Chờ đến lần renewal tiếp (max 30s)
4. Key tự động được tạo lại

**Server log:**
```
WEBSOCKET_SESSION_MANAGER - Routing key missing, re-registered. userId=1
```

**Redis:**
```bash
EXISTS ws_routing:1
# → (integer) 1   ← key đã được phục hồi
```

---

## Test 6: Graceful Shutdown

### 6.1 Server shutdown — Routing cleanup

**Bước:**
1. Connect 1–2 sessions
2. Xác nhận Redis keys tồn tại:
   ```bash
   KEYS ws_routing:*
   ```
3. Stop server (Ctrl+C trong terminal / Stop trong IntelliJ)
4. Kiểm tra Redis ngay lập tức

**Server log (shutdown):**
```
WEBSOCKET_SESSION_MANAGER - Draining all local sessions. localUsers=1, totalSessions=2
WEBSOCKET_SESSION_MANAGER - Drain completed. usersCleanedUp=1, serverId=local-dev
```

**Redis (ngay sau shutdown):**
```bash
KEYS ws_routing:*
# → (empty array)    ← tất cả routing đã bị xóa
```

> [!TIP]
> Đây là điểm quan trọng nhất: routing keys **KHÔNG còn stale** sau shutdown bình thường. Chỉ khi server crash (kill -9) mới cần dựa vào TTL expiry.

---

## Test 7: Heartbeat

### 7.1 Heartbeat hoạt động

**Bước:**
1. Connect thành công
2. Để yên 2–3 phút
3. Connection vẫn sống (status badge vẫn CONNECTED)

**Console (nếu enable STOMP debug):** Thấy periodic PING/PONG frames

### 7.2 Network drop simulation

**Bước:**
1. Connect thành công
2. Disconnect WiFi/mạng trên máy client (hoặc đóng laptop lid)
3. Đợi ~30–60 giây
4. Server sẽ detect heartbeat timeout và close session

**Server log:**
```
SESSION_DISCONNECT: userId=1, sessionId=..., closeStatus=CloseStatus[code=1006, ...]
```

---

## Redis Verification Cheat Sheet

```bash
# ═══════════ ROUTING ═══════════
KEYS ws_routing:*                  # List tất cả routing keys
SMEMBERS ws_routing:{userId}       # Xem servers cho user
TTL ws_routing:{userId}            # Kiểm tra TTL còn lại

# ═══════════ RATE LIMIT ═══════════
GET ws_rate:{userId}               # Đếm SEND frames trong window hiện tại
TTL ws_rate:{userId}               # TTL còn lại của window

# ═══════════ CLEANUP ═══════════
DEL ws_routing:{userId}            # Xóa manual để test self-healing
DEL ws_rate:{userId}               # Reset rate limit counter
```

---

## Final Checklist

| # | Test | Expected | ✓ |
|---|---|---|---|
| 1.1 | Connect với valid JWT | `AUTH_OK` + `SESSION_CONNECT` + Redis key created | ☐ |
| 1.2 | Connect không có token | `AUTH_FAIL: No Bearer token` | ☐ |
| 1.3 | Connect với invalid JWT | `AUTH_FAIL: Invalid JWT` | ☐ |
| 1.4 | Connect với blacklisted token | `AUTH_FAIL: Blacklisted token` | ☐ |
| 2.1 | 2 tabs cùng user | `localSessions=2/10` | ☐ |
| 2.2 | Đóng tất cả tabs | Redis key xóa, `All local sessions closed` | ☐ |
| 3.1 | Vượt session limit | `AUTH_FAIL: Session limit reached` | ☐ |
| 4.1 | Send bình thường | Messages gửi thành công | ☐ |
| 4.2 | Flood test vượt limit | `RATE_LIMIT: Exceeded` | ☐ |
| 5.1 | TTL renewal sau 30s | TTL reset về ~90s | ☐ |
| 5.2 | DEL key → self-heal | `Routing key missing, re-registered` | ☐ |
| 6.1 | Graceful shutdown | `Drain completed`, Redis keys xóa | ☐ |
| 7.1 | Idle 2–3 phút | Connection vẫn sống | ☐ |
| 7.2 | Network drop | Server detect + cleanup session | ☐ |

> [!CAUTION]
> **Test 3.1 và 4.2** yêu cầu tạm thay đổi config rồi restart server. Nhớ đổi lại giá trị gốc sau khi test xong!
