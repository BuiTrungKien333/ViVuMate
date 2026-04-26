# MongoDB Schema — Part 2: Read States, Reactions, Profiles, Pinned Messages

### 3.3 Collection: `message_read_states`

**Mục đích**: Theo dõi trạng thái `delivered` / `seen` per user per conversation. Tách riêng khỏi `messages` để tránh contention và unbounded growth.

```json
{
  "_id": ObjectId("665c3d4e5f6a7b8c9d0e1f2a"),
  "conversation_id": ObjectId("665a1b2c3d4e5f6a7b8c9d0e"),
  "user_id": 1002,                          // pg_user_id

  // === WATERMARK APPROACH (thay vì track per-message) ===
  "last_delivered_message_id": ObjectId("665b2c3d4e5f6a7b8c9d0e1f"),
  "last_delivered_at": ISODate("2025-06-01T14:30:00Z"),

  "last_seen_message_id": ObjectId("665b1a2b3c4d5e6f7a8b9c0d"),
  "last_seen_at": ISODate("2025-06-01T14:28:00Z"),

  "created_at": ISODate("2025-01-15T08:00:00Z"),
  "updated_at": ISODate("2025-06-01T14:30:00Z")
}
```

#### Quyết định thiết kế — Watermark Pattern

> [!TIP]
> Thay vì track trạng thái **per-message** (sẽ tạo hàng triệu records), dùng **Watermark Pattern**: chỉ lưu ObjectId của message cuối cùng đã delivered/seen. Mọi message có `_id ≤ last_seen_message_id` đều được coi là "đã xem". Giảm write amplification từ O(N) xuống O(1) per conversation per user.

| Quyết định | Lựa chọn | Lý do |
|------------|----------|-------|
| Per-message tracking | ❌ Rejected | Mỗi message × mỗi recipient = unbounded growth. Group 100 người, 1000 tin → 100,000 records |
| Watermark per user per conversation | ✅ **Chosen** | 1 record per user per conversation. Update atomic khi user đọc tin mới nhất |

#### Indexes

```javascript
// 1. Lookup read state cho 1 user trong 1 conversation (unique pair)
db.message_read_states.createIndex(
  { "conversation_id": 1, "user_id": 1 },
  { unique: true, name: "idx_conversation_user_unique" }
)

// 2. Tìm ai đã xem message cụ thể trong conversation
//    (dùng khi hiển thị "seen by" dưới message)
db.message_read_states.createIndex(
  { "conversation_id": 1, "last_seen_message_id": 1 },
  { name: "idx_conversation_seen_message" }
)
```

#### Nghiệp vụ "Mark all as read"

```javascript
// Atomic update: đánh dấu đã đọc hết conversation
db.message_read_states.updateOne(
  { conversation_id: convId, user_id: userId },
  {
    $set: {
      last_seen_message_id: latestMessageId,
      last_seen_at: new Date(),
      last_delivered_message_id: latestMessageId,
      last_delivered_at: new Date(),
      updated_at: new Date()
    }
  },
  { upsert: true }
)

// Đồng thời reset unread count trong conversations
db.conversations.updateOne(
  { _id: convId },
  { $set: { "unread_counts.1002": 0, updated_at: new Date() } }
)
```

---

### 3.4 Collection: `message_reactions`

**Mục đích**: Lưu emoji reactions tách riêng, nhóm theo emoji per message để tránh unbounded growth trong `messages`.

```json
{
  "_id": ObjectId("665d4e5f6a7b8c9d0e1f2a3b"),
  "message_id": ObjectId("665b2c3d4e5f6a7b8c9d0e1f"),
  "conversation_id": ObjectId("665a1b2c3d4e5f6a7b8c9d0e"),

  // === GROUPED BY EMOJI ===
  "reactions": [
    {
      "emoji": "👍",
      "count": 3,                              // Computed Pattern
      "users": [
        { "user_id": 1001, "reacted_at": ISODate("2025-06-01T14:31:00Z") },
        { "user_id": 1002, "reacted_at": ISODate("2025-06-01T14:32:00Z") },
        { "user_id": 1003, "reacted_at": ISODate("2025-06-01T14:33:00Z") }
      ]
    },
    {
      "emoji": "❤️",
      "count": 1,
      "users": [
        { "user_id": 1002, "reacted_at": ISODate("2025-06-01T14:35:00Z") }
      ]
    }
  ],

  "total_reaction_count": 4,                   // Computed Pattern — tổng reactions
  "created_at": ISODate("2025-06-01T14:31:00Z"),
  "updated_at": ISODate("2025-06-01T14:35:00Z")
}
```

#### Quyết định thiết kế

| Approach | Status | Lý do |
|----------|--------|-------|
| Embed reactions trong `messages` | ❌ | Unbounded growth, contention khi nhiều người react đồng thời |
| 1 document per reaction | ❌ | Quá nhiều documents, query phức tạp để aggregate |
| **1 document per message, grouped by emoji** | ✅ | Balanced: 1 read lấy tất cả reactions, bounded (emoji types có giới hạn) |

> [!NOTE]
> **Bounded growth**: Mỗi message thường có < 20 loại emoji khác nhau, mỗi emoji < 100 users react. Max document size ~50KB — an toàn.  
> **Outlier Pattern**: Nếu message viral (>500 reactions cùng emoji), chỉ giữ [count](file:///e:/VivuMateProject/src/main/java/com/vivumate/coreapi/entity/User.java#109-113) và **top 100 users gần nhất**, phần còn lại query riêng.

#### Indexes

```javascript
// 1. Lookup reactions cho 1 message
db.message_reactions.createIndex(
  { "message_id": 1 },
  { unique: true, name: "idx_message_reactions" }
)

// 2. Batch load reactions cho nhiều messages (khi load message feed)
db.message_reactions.createIndex(
  { "conversation_id": 1, "message_id": 1 },
  { name: "idx_conversation_message_reactions" }
)
```

#### Thêm/xóa reaction — Atomic operations

```javascript
// THÊM reaction 👍 từ user 1003
db.message_reactions.updateOne(
  { message_id: msgId, "reactions.emoji": "👍" },
  {
    $push: { "reactions.$.users": { user_id: 1003, reacted_at: new Date() } },
    $inc: { "reactions.$.count": 1, total_reaction_count: 1 },
    $set: { updated_at: new Date() }
  }
)

// Nếu emoji chưa tồn tại → dùng upsert logic:
db.message_reactions.updateOne(
  { message_id: msgId, "reactions.emoji": { $ne: "🔥" } },
  {
    $push: { reactions: { emoji: "🔥", count: 1, users: [{ user_id: 1003, reacted_at: new Date() }] } },
    $inc: { total_reaction_count: 1 },
    $set: { updated_at: new Date() }
  },
  { upsert: true }
)

// XÓA reaction
db.message_reactions.updateOne(
  { message_id: msgId, "reactions.emoji": "👍" },
  {
    $pull: { "reactions.$.users": { user_id: 1003 } },
    $inc: { "reactions.$.count": -1, total_reaction_count: -1 },
    $set: { updated_at: new Date() }
  }
)
```

---

### 3.5 Collection: `user_chat_profiles`

**Mục đích**: Extended Reference Pattern — snapshot thông tin user từ PostgreSQL sang MongoDB. Dùng làm source cho denormalized data trong messages/conversations.

```json
{
  "_id": ObjectId("665e5f6a7b8c9d0e1f2a3b4c"),
  "pg_user_id": 1001,                          // ID từ PostgreSQL (Long)
  "username": "trungkien",
  "full_name": "Bùi Trung Kiên",
  "avatar_url": "https://cdn.vivumate.com/avatars/kien.jpg",
  "cover_url": "https://cdn.vivumate.com/covers/kien.jpg",

  // === CHAT-SPECIFIC SETTINGS ===
  "chat_settings": {
    "notification_enabled": true,
    "notification_sound": "default",
    "show_read_receipts": true,
    "show_online_status": true
  },

  // === COMPUTED STATS ===
  "total_conversations": 42,
  "total_messages_sent": 15230,

  "created_at": ISODate("2025-01-01T00:00:00Z"),
  "updated_at": ISODate("2025-06-01T14:00:00Z")
}
```

#### Sync Strategy: PostgreSQL → MongoDB

```
PostgreSQL User UPDATE (avatar, name)
    ↓ (Spring ApplicationEvent / CDC)
user_chat_profiles.updateOne({ pg_user_id: userId }, { $set: { ... } })
    ↓ (Async background job)
messages.updateMany(
  { "sender.user_id": userId, created_at: { $gte: 30DaysAgo } },
  { $set: { "sender.avatar_url": newUrl, "sender.full_name": newName } }
)
conversations.updateMany(
  { "participants.user_id": userId },
  { $set: { "participants.$.avatar_url": newUrl, "participants.$.full_name": newName } }
)
```

> [!CAUTION]
> Denormalized data (sender snapshot trong messages, participants trong conversations) sẽ **eventually consistent**. Tin nhắn cũ hơn 30 ngày giữ snapshot cũ — chấp nhận tradeoff này vì update toàn bộ history quá tốn kém.

#### Indexes

```javascript
// Lookup by PostgreSQL user ID (unique)
db.user_chat_profiles.createIndex(
  { "pg_user_id": 1 },
  { unique: true, name: "idx_pg_user_id" }
)
```

---

### 3.6 Collection: `pinned_messages`

```json
{
  "_id": ObjectId("665f6a7b8c9d0e1f2a3b4c5d"),
  "conversation_id": ObjectId("665a1b2c3d4e5f6a7b8c9d0e"),
  "message_id": ObjectId("665b2c3d4e5f6a7b8c9d0e1f"),

  // === MESSAGE SNAPSHOT (Subset Pattern) ===
  "message_snapshot": {
    "sender_name": "Nguyễn Minh Phương",
    "content_preview": "Link tài liệu thiết kế: https://...",
    "content_type": "LINK_PREVIEW",
    "sent_at": ISODate("2025-06-01T14:30:00Z")
  },

  "pinned_by": 1001,                        // user who pinned
  "pinned_at": ISODate("2025-06-01T15:00:00Z"),
  "order": 1,                               // display order

  "created_at": ISODate("2025-06-01T15:00:00Z"),
  "updated_at": ISODate("2025-06-01T15:00:00Z")
}
```

#### Indexes

```javascript
// Load pinned messages cho 1 conversation
db.pinned_messages.createIndex(
  { "conversation_id": 1, "order": 1 },
  { name: "idx_conversation_pinned_order" }
)

// Prevent pin duplicate
db.pinned_messages.createIndex(
  { "conversation_id": 1, "message_id": 1 },
  { unique: true, name: "idx_conversation_message_unique" }
)
```

---

*Tiếp tục ở Part 3: Shard Keys, Query Patterns, Anti-patterns*
