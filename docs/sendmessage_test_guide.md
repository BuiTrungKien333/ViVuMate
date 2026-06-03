# 🧪 Hướng Dẫn Test `sendMessage` — Step by Step

Hướng dẫn này giúp bạn test toàn diện chức năng **gửi tin nhắn qua WebSocket** từ đầu đến cuối, bao gồm cả happy path lẫn edge cases.

---

## 📋 Điều kiện tiên quyết

| Thành phần | Yêu cầu |
|------------|----------|
| Docker Desktop | Đang chạy (cho MongoDB, Redis, PostgreSQL) |
| Java 21 | Đã cài đặt |
| Browser | Chrome/Edge (để mở test client HTML) |
| MongoDB Compass | Khuyên dùng (để xem dữ liệu trực quan) |
| RedisInsight | Khuyên dùng (để xem Redis keys) |

---

## STEP 1: Khởi động hạ tầng

### 1.1 Start Docker services
```bash
cd e:\VivuMateProject
docker compose up -d
```

### 1.2 Start Spring Boot
```bash
cd e:\VivuMateProject
./mvnw spring-boot:run
```

> Đợi đến khi thấy log:
> ```
> Message broker configured: destinations=[/topic, /queue], heartbeat=[server=25000ms, client=25000ms]
> STOMP endpoints registered: /ws-connect (WebSocket + SockJS)
> ```

---

## STEP 2: Chuẩn bị dữ liệu test

### 2.1 Tạo 2 tài khoản test (nếu chưa có)

Dùng Postman hoặc `curl`:

**User A:**
```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser_a",
    "email": "testa@test.com",
    "password": "Test@123456",
    "fullName": "Test User A"
  }'
```

**User B:**
```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser_b",
    "email": "testb@test.com",
    "password": "Test@123456",
    "fullName": "Test User B"
  }'
```

> [!NOTE]
> Nếu hệ thống yêu cầu OTP/verify email, hãy hoàn tất flow đó trước.

### 2.2 Login lấy Access Token

**Login User A:**
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "identifier": "testuser_a",
    "password": "Test@123456"
  }'
```

Lưu lại:
- `access_token` → dùng cho WebSocket connect
- `userId` → dùng cho verify

**Login User B:** (tương tự)

### 2.3 Tạo Conversation DIRECT giữa A và B

```bash
curl -X POST http://localhost:8080/api/v1/conversations/direct \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {TOKEN_USER_A}" \
  -d '{
    "targetUserId": {USER_B_ID}
  }'
```

Lưu lại `conversationId` từ response (dạng 24-char hex, ví dụ: `6650a1b2c3d4e5f6a7b8c9d0`).

> [!IMPORTANT]
> Thay `{TOKEN_USER_A}` và `{USER_B_ID}` bằng giá trị thực tế.

---

## STEP 3: Mở WebSocket Test Client

1. Mở file [websocket-test-client.html](file:///e:/VivuMateProject/src/test/resources/websocket-test-client.html) trong browser
2. Hoặc mở trực tiếp: `file:///e:/VivuMateProject/src/test/resources/websocket-test-client.html`

---

## STEP 4: Kết nối WebSocket

### Cách 1: Quick Login (khuyên dùng)
1. Ở mục **① Connection**, nhập vào phần **Quick Login**:
   - `identifier`: `testuser_a`
   - `password`: `Test@123456`
2. Bấm **🔑 Login & Get Token**
3. Console hiển thị: `✓ Login OK — userId=X, username=testuser_a`
4. Token tự động điền vào ô JWT

### Cách 2: Paste token thủ công
- Paste `access_token` đã lưu ở Step 2.2 vào ô **JWT Access Token**

### Connect
- Bấm **▶ Connect**
- **Expected:** Console hiển thị:
  ```
  ✓ CONNECTED — session=xxxxx
  ✓ SUBSCRIBED to /user/queue/errors (id=sub-0)
  ✓ SUBSCRIBED to /user/queue/ack (id=sub-1)
  ✓ SUBSCRIBED to /user/queue/messages (id=sub-2)
  Auto-subscribed to: /user/queue/errors, /user/queue/ack, /user/queue/messages
  ```

---

## STEP 5: Test Cases

### ✅ Test Case 1: Happy Path — Gửi tin nhắn TEXT thành công

**Mục tiêu:** Xác nhận luồng gửi tin nhắn hoàn chỉnh: persist → ACK → broadcast.

**Payload:** (cập nhật `conversationId` bằng ID thực tế)
```json
{
  "clientMessageId": "test-uuid-001",
  "conversationId": "PASTE_YOUR_CONVERSATION_ID",
  "contentType": "TEXT",
  "content": { "text": "Hello from WebSocket test! 🎉" },
  "mentions": [],
  "replyTo": null
}
```

**Thao tác:**
1. Paste payload vào ô **③ Send Message** (thay `conversationId`)
2. Bấm **Send**

**Expected Result trong Console:**
```
→ SENT [/app/chat.send]: {...}
✓ ACK received: {"clientMessageId":"test-uuid-001","messageId":"...","status":"SENT","timestamp":"..."}
← BROADCAST received: {"id":"...","clientMessageId":"test-uuid-001","conversationId":"...","sender":{...},"contentType":"TEXT","content":{"text":"Hello from WebSocket test! 🎉"},...}
```

**Verify:**
- **Metrics:** Sent=1, Received=2 (1 ACK + 1 Broadcast)
- **MongoDB** (collection `messages`): Tìm document với `client_message_id: "test-uuid-001"`
- **MongoDB** (collection `conversations`): Field `last_message` đã được cập nhật
- **Spring Boot log:** `Message sent: id=..., conv=..., sender=..., recipients=[...]`

---

### ✅ Test Case 2: Idempotency — Gửi lại cùng `clientMessageId`

**Mục tiêu:** Xác nhận tin nhắn trùng lặp KHÔNG bị lưu 2 lần.

**Payload:** Giữ nguyên payload Test Case 1 (cùng `clientMessageId: "test-uuid-001"`)

**Thao tác:**
1. Bấm **Send** lần nữa (cùng payload)

**Expected Result:**
```
→ SENT [/app/chat.send]: {...}
✓ ACK received: {"clientMessageId":"test-uuid-001","messageId":"SAME_ID","status":"DUPLICATE",...}
```

**Verify:**
- ACK status = `DUPLICATE` (KHÔNG phải `SENT`)
- `messageId` trong ACK = **giống hệt** lần gửi đầu tiên
- **MongoDB** (collection `messages`): Chỉ có **1 document** với `client_message_id: "test-uuid-001"`
- **Redis:** `GET ws_idem:{userId}:test-uuid-001` → trả về messageId (TTL ~1 giờ)

---

### ✅ Test Case 3: Gửi tin nhắn mới (khác `clientMessageId`)

**Mục tiêu:** Xác nhận tin nhắn mới với UUID mới được xử lý bình thường.

**Payload:**
```json
{
  "clientMessageId": "test-uuid-002",
  "conversationId": "PASTE_YOUR_CONVERSATION_ID",
  "contentType": "TEXT",
  "content": { "text": "This is my second message 💬" },
  "mentions": [],
  "replyTo": null
}
```

**Expected:** ACK status = `SENT`, messageId khác với lần trước.

---

### ❌ Test Case 4: Validation Error — thiếu field bắt buộc

**Mục tiêu:** Xác nhận server reject tin nhắn thiếu `clientMessageId`.

**Payload:**
```json
{
  "conversationId": "PASTE_YOUR_CONVERSATION_ID",
  "contentType": "TEXT",
  "content": { "text": "Missing clientMessageId" }
}
```

**Expected:**
```
✗ ERROR from server: {"code":...,"message":"clientMessageId is required",...}
```

---

### ❌ Test Case 5: Invalid conversationId

**Mục tiêu:** Xác nhận server reject ObjectId không hợp lệ.

**Payload:**
```json
{
  "clientMessageId": "test-uuid-003",
  "conversationId": "invalid-id",
  "contentType": "TEXT",
  "content": { "text": "Bad conversation ID" }
}
```

**Expected:**
```
✗ ERROR from server: {"code":...,"message":"conversationId must be a valid 24-character ObjectId hex",...}
```

---

### 🔒 Test Case 6: Destination Security — SEND tới destination cấm

**Mục tiêu:** Xác nhận interceptor chặn SEND tới `/topic/*` hoặc `/user/queue/*`.

**Thao tác:**
1. Thay đổi ô **Destination** từ `/app/chat.send` thành `/topic/conversation/test`
2. Bấm **Send**

**Expected:**
- Console: `→ SENT [/topic/conversation/test]: {...}` (client side ghi nhận gửi)
- **KHÔNG nhận được ACK hay BROADCAST** (frame bị drop server-side)
- **Spring Boot log:** `DESTINATION_DENIED: Client attempted SEND to forbidden destination. destination=/topic/conversation/test, ...`
- Metrics: Sent tăng, nhưng Received **KHÔNG tăng**

> [!TIP]
> Thử lại với destination `/user/queue/messages` — cũng phải bị chặn tương tự.
> Nhớ đổi Destination lại về `/app/chat.send` sau khi test xong!

---

### 🔥 Test Case 7: Rate Limit — Flood Test

**Mục tiêu:** Xác nhận rate limiter chặn khi vượt quá giới hạn.

**Thao tác:**
1. Đổi Destination lại về `/app/chat.send`
2. Bấm **🔥 Flood Test (send ×150)**

**Expected:**
- Ban đầu: các message được xử lý bình thường (ACK `SENT`)
- Sau khi vượt limit (mặc định dev: 300/60s hoặc prod: 60/60s):
  ```
  ✗ ERROR from server: {...WS_RATE_LIMITED...}
  ```
- **Spring Boot log:** `RATE_LIMIT: Exceeded. userId=..., count=..., limit=.../60s`
- **Redis:** `GET ws_rate:{userId}` → hiển thị counter hiện tại

---

### 🏃 Test Case 8: lastMessage Race Condition (nâng cao)

**Mục tiêu:** Xác nhận khi 2 user gửi tin nhắn gần đồng thời, conversation luôn hiển thị tin nhắn MỚI NHẤT.

**Thao tác:**
1. Mở **2 tab browser** → mỗi tab mở test client HTML
2. Tab 1: Login & Connect với **User A**
3. Tab 2: Login & Connect với **User B**
4. Chuẩn bị payload cho cả 2 tab (cùng `conversationId`, khác `clientMessageId`)
5. Bấm **Send** ở cả 2 tab gần như đồng thời (càng nhanh càng tốt)

**Verify:**
- Mở MongoDB Compass → collection `conversations` → tìm document đó
- Field `last_message.sentAt` phải trỏ tới tin nhắn **được persist SAU** (mới nhất)
- Field `last_message.text` phải khớp với nội dung tin nhắn mới nhất

---

## STEP 6: Kiểm tra dữ liệu trên MongoDB & Redis

### MongoDB Compass

1. Connect: `mongodb://admin:123456@localhost:27017/vivumate_chat?authSource=admin`
2. Kiểm tra collection **`messages`**:
   - Tìm theo `conversation_id` → xem tất cả tin nhắn
   - Xác nhận field: `sender`, `content_type`, `content.text`, `client_message_id`, `created_at`
3. Kiểm tra collection **`conversations`**:
   - Field `last_message` → có `text`, `sender_name`, `sent_at`
   - Field `last_activity_at` → cùng thời gian với `last_message.sent_at`
   - Field `unread_counts.{userId}` → đã tăng cho recipients

### Redis (RedisInsight hoặc CLI)

```bash
docker exec -it vivumate-redis redis-cli -a 123456

# Kiểm tra idempotency key
GET ws_idem:{userId}:test-uuid-001
TTL ws_idem:{userId}:test-uuid-001

# Kiểm tra rate limit counter
GET ws_rate:{userId}
TTL ws_rate:{userId}

# Kiểm tra WebSocket session routing
KEYS ws_routing:*
```

---

## ✅ CHECKLIST TỔNG KẾT

| # | Test Case | Expected | Pass? |
|---|-----------|----------|-------|
| 1 | Happy Path — Text message | ACK(SENT) + Broadcast received | ☐ |
| 2 | Idempotency — Duplicate send | ACK(DUPLICATE), 1 doc in MongoDB | ☐ |
| 3 | New message — Different UUID | ACK(SENT), new messageId | ☐ |
| 4 | Validation — Missing clientMessageId | Error on /queue/errors | ☐ |
| 5 | Validation — Invalid conversationId | Error on /queue/errors | ☐ |
| 6 | Destination Security — /topic/* blocked | Frame dropped, no response | ☐ |
| 7 | Rate Limit — Flood 150 messages | WS_RATE_LIMITED after threshold | ☐ |
| 8 | Race Condition — 2 users concurrent | lastMessage = newest message | ☐ |

---

## 🎯 Sau khi test xong

Nếu **tất cả 8 test case PASS** → chức năng `sendMessage` đã hoàn chỉnh và sẵn sàng chuyển sang phần tiếp theo.

> [!IMPORTANT]
> **Nhớ xóa dữ liệu test** trong MongoDB nếu cần:
> ```javascript
> db.messages.deleteMany({ "client_message_id": /^test-uuid/ })
> ```
