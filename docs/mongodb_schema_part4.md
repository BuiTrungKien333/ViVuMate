# MongoDB Schema — Part 4: Redis Strategy, Anti-patterns, Migration Notes

## 7. Redis Strategy — Presence & Typing

### 7.1 Presence (Online/Offline/Away)

| Approach | Phương án | Lý do chọn/loại |
|----------|----------|-----------------|
| MongoDB polling | ❌ | Quá chậm, write-heavy vô nghĩa cho ephemeral data |
| Redis Hash + TTL | ✅ **Chosen** | Sub-millisecond read, TTL auto-cleanup, pub/sub broadcast |

**Key Design:**

```
Key:    presence:{userId}
Type:   Hash
Fields: { status: "ONLINE|OFFLINE|AWAY", last_active: "ISO8601" }
TTL:    5 minutes (auto-set OFFLINE nếu không heartbeat)
```

**Flow:**

```
WebSocket CONNECT → SET presence:{userId} { status:"ONLINE", last_active:now }
                   → EXPIRE presence:{userId} 300
                   → PUBLISH channel:presence { userId, status:"ONLINE" }

Heartbeat (mỗi 60s) → EXPIRE presence:{userId} 300  (renew TTL)
                     → HSET presence:{userId} last_active now

Idle 2 phút         → HSET presence:{userId} status "AWAY"
                     → PUBLISH channel:presence { userId, status:"AWAY" }

WebSocket DISCONNECT → DEL presence:{userId}
                     → PUBLISH channel:presence { userId, status:"OFFLINE" }
                     → MongoDB: update user_chat_profiles.last_seen = now

TTL expired (crash)  → Redis tự xóa key → Redis Keyspace Notifications
                     → Listener: PUBLISH channel:presence { userId, status:"OFFLINE" }
```

> [!TIP]
> Dùng **Redis Keyspace Notifications** (`notify-keyspace-events Ex`) để phát hiện khi presence key expired (user crash/mất mạng) → auto broadcast offline.

### 7.2 Typing Indicator

```
Key:    typing:{conversationId}
Type:   SET (of userIds)
TTL:    3 seconds (auto-cleanup)
```

```java
// User bắt đầu gõ → gọi mỗi 2 giây khi đang gõ
public Mono<Void> setTyping(Long userId, String conversationId) {
    String key = "typing:" + conversationId;
    return reactiveRedisTemplate.opsForSet()
        .add(key, userId.toString())
        .then(reactiveRedisTemplate.expire(key, Duration.ofSeconds(3)))
        .then(Mono.fromRunnable(() ->
            // Broadcast qua WebSocket/Redis Pub/Sub
            messagingTemplate.convertAndSend(
                "/topic/conversation/" + conversationId + "/typing",
                new TypingEvent(userId, true)
            )
        ));
}

// Client nhận typing event, hiển thị "Phương đang gõ..."
// Sau 3s không có event mới → tự ẩn (client-side timeout)
```

### 7.3 Unread Count — Redis Cache Layer

```
Key:    unread:{userId}:{conversationId}
Type:   String (integer)
TTL:    No TTL (persistent in Redis, sync với MongoDB)
```

```java
// Khi có tin nhắn mới → increment Redis + MongoDB
public Mono<Void> incrementUnread(String conversationId, List<Long> recipientIds) {
    return Flux.fromIterable(recipientIds)
        .flatMap(userId -> {
            String redisKey = "unread:" + userId + ":" + conversationId;
            return reactiveRedisTemplate.opsForValue().increment(redisKey);
        })
        .then(reactiveMongoTemplate.updateFirst(    // MongoDB persistent
            new Query(Criteria.where("_id").is(new ObjectId(conversationId))),
            recipientIds.stream()
                .reduce(new Update(), (u, id) -> u.inc("unread_counts." + id, 1), (a, b) -> a),
            Conversation.class
        ))
        .then();
}

// Mark as read → reset Redis + MongoDB
public Mono<Void> markAsRead(String conversationId, Long userId) {
    String redisKey = "unread:" + userId + ":" + conversationId;
    return reactiveRedisTemplate.delete(redisKey)
        .then(reactiveMongoTemplate.updateFirst(
            new Query(Criteria.where("_id").is(new ObjectId(conversationId))),
            new Update().set("unread_counts." + userId, 0),
            Conversation.class
        ))
        .then();
}
```

---

## 8. Anti-Patterns to Avoid

### ❌ 1. Embedding messages bên trong conversations

**Sai lầm**: Lưu array messages trong conversation document.

**Vấn đề**: MongoDB document limit 16MB. Array messages là unbounded growth → document sẽ đạt limit nhanh chóng. Mỗi message mới → document growing → re-allocation trên disk → write amplification cực lớn.

**Thiết kế này tránh**: Messages là **collection riêng**, reference qua `conversation_id`. Conversation chỉ embed `last_message` preview (bounded, fixed-size).

---

### ❌ 2. Tracking read status per-message per-user

**Sai lầm**: Mỗi message có array `read_by: [userId1, userId2, ...]` hoặc collection riêng với 1 record per message per user.

**Vấn đề**: Group 100 người, 10,000 messages → 1,000,000 read records. Write amplification khủng khiếp.

**Thiết kế này tránh**: Dùng **Watermark Pattern** — chỉ 1 record per user per conversation, lưu `last_seen_message_id`. Mọi message có `_id ≤ watermark` = đã đọc. Giảm từ O(M×N) xuống O(N).

---

### ❌ 3. Dùng offset-based pagination cho messages

**Sai lầm**: `db.messages.find().skip(1000).limit(20)` — page 50+.

**Vấn đề**: `skip(N)` phải scan qua N documents. Page 1000 = scan 20,000 documents → latency tăng linear.

**Thiết kế này tránh**: **Cursor-based pagination** dùng `_id` (ObjectId tự nhiên tăng dần). Query: `{ _id: { $lt: lastSeenId } }` → nhảy thẳng vào B-tree position → O(log N) luôn.

---

### ❌ 4. Dùng $lookup (JOIN) cho hot read path

**Sai lầm**: Lưu chỉ `sender_id` trong message, rồi `$lookup` user collection mỗi lần load.

**Vấn đề**: `$lookup` chậm, không scale cho real-time chat. Load 30 messages = 30 lookups.

**Thiết kế này tránh**: **Extended Reference Pattern** — embed lightweight sender snapshot (`user_id`, `username`, `full_name`, `avatar_url`) trực tiếp trong message. Chấp nhận eventually consistent, cập nhật async khi user đổi profile.

---

### ❌ 5. Sử dụng auto-increment ID thay vì ObjectId

**Sai lầm**: Dùng sequence counter cho message ID (giống SQL identity).

**Vấn đề**: Bottleneck single point cho ID generation. Không hoạt động với sharding. Cần distributed locks.

**Thiết kế này tránh**: Dùng MongoDB **ObjectId** native — chứa timestamp, machine, PID, counter. Tự nhiên unique, sortable theo thời gian, distributed-friendly. Perfect cho cursor-based pagination.

---

## 9. Migration / Evolution Notes

### Thêm tính năng mới mà không breaking changes

| Tính năng tương lai | Cách mở rộng | Breaking? |
|---------------------|-------------|-----------|
| **Voice/Video calls** | Thêm collection `call_sessions` mới. Message type `SYSTEM` với `system_event: "CALL_STARTED"` | ❌ No |
| **Message forwarding** | Thêm field `forwarded_from: { message_id, conversation_id, sender_name }` vào `messages` | ❌ No |
| **Scheduled messages** | Thêm field `scheduled_at` + partial index `{ scheduled_at: 1 } where scheduled_at exists` | ❌ No |
| **Polls / Surveys** | Polymorphic Pattern — thêm `content_type: "POLL"` + `content.poll: { question, options[], votes }` | ❌ No |
| **Message bookmarks** | Collection mới `user_bookmarks` với `{ user_id, message_id, conversation_id, created_at }` | ❌ No |
| **Channels (như Slack)** | Mở rộng `conversations.type` thêm `"CHANNEL"`, thêm fields `is_public`, `topic` | ❌ No |
| **E2E Encryption** | Thêm fields `is_encrypted`, `encrypted_content`, `encryption_key_id` vào messages | ❌ No |
| **Message expiry (vanish mode)** | Thêm `expires_at` + TTL index → MongoDB tự xóa | ❌ No |

> [!NOTE]
> MongoDB schema-less nature cho phép thêm fields mới bất cứ lúc nào mà không cần migration. Existing documents không có field mới → `null` (xử lý trong code). Đây là ưu điểm lớn nhất so với relational approach.

### Schema Versioning Strategy

```json
// Thêm field schema_version vào documents quan trọng
{
  "_id": ObjectId("..."),
  "schema_version": 1,
  // ... other fields
}
```

Khi cần thay đổi cấu trúc lớn (hiếm khi), dùng background migration job đọc documents cũ, transform, write lại. Code xử lý đọc phải handle cả version cũ và mới (backward compatible).

---

## 10. Tổng Kết — Pattern Recap

| Pattern | Áp dụng ở đâu | Hiệu quả |
|---------|---------------|-----------|
| **Extended Reference** | `messages.sender`, `conversations.participants` | Tránh $lookup trên hot path → giảm latency 10x |
| **Computed Pattern** | `conversations.unread_counts`, `conversations.member_count`, `message_reactions.count` | Tránh $count/$size aggregation → O(1) reads |
| **Subset Pattern** | `conversations.last_message`, `pinned_messages.message_snapshot` | Chỉ embed dữ liệu cần hiển thị ngay → giảm document size |
| **Polymorphic Pattern** | `messages.content` (TEXT, IMAGE, FILE, VIDEO, ...) | 1 collection cho mọi loại tin nhắn → query đơn giản, indexes hiệu quả |
| **Outlier Pattern** | Groups > 500 members → tách `participants` ra collection riêng | Ngăn document vượt 16MB limit |
| **Bucket Pattern** | Không áp dụng trực tiếp | Messages không phải time-series thuần túy; cursor-based pagination trên flat collection hiệu quả hơn |

> [!NOTE]
> **Bucket Pattern** được cân nhắc nhưng **không áp dụng** cho messages vì: (1) chat messages cần random access bằng ID (cho reply, reaction, pin) — bucket pattern làm phức tạp operations này, (2) cursor-based pagination trên flat collection đã đủ nhanh khi có index `{ conversation_id, _id }`.
