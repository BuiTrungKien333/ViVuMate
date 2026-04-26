# Thiết Kế MongoDB Schema — VivuMate Real-time Chat

## 1. Tổng Quan Kiến Trúc

### Polyglot Persistence Strategy

| Hệ thống | Database | Lý do |
|-----------|----------|-------|
| User accounts, Auth, Roles | **PostgreSQL** (giữ nguyên) | ACID, quan hệ phức tạp (roles, permissions), Spring Security integration |
| Conversations, Messages, Reactions | **MongoDB** | Schema linh hoạt, write-heavy, horizontal scaling, document-oriented phù hợp chat |
| Presence (online/offline), Typing indicators | **Redis** | Ephemeral data, pub/sub, TTL tự động, cực nhanh |
| Unread counters (hot path) | **Redis** + MongoDB (sync) | Redis cho real-time read, MongoDB cho persistence |

> [!IMPORTANT]
> User data chính (auth, email, password, roles) **giữ nguyên ở PostgreSQL**. MongoDB chỉ lưu **snapshot nhẹ** (`user_id`, `username`, `full_name`, `avatar_url`) cho denormalization trong messages/conversations — áp dụng **Extended Reference Pattern**.

### Liên kết PostgreSQL ↔ MongoDB

Dùng `pg_user_id` (kiểu `Long` từ PostgreSQL) làm foreign key logic trong MongoDB. **KHÔNG** migrate user sang MongoDB.

---

## 2. Collection Inventory

| # | Collection | Mô tả | Dung lượng dự kiến |
|---|-----------|-------|-------------------|
| 1 | `conversations` | Metadata cuộc trò chuyện (DM + Group), last message preview, unread counts | Medium |
| 2 | `messages` | Tất cả tin nhắn, áp dụng Polymorphic Pattern cho các loại content | Very Large |
| 3 | `message_read_states` | Trạng thái delivered/seen per recipient per conversation | Large |
| 4 | `message_reactions` | Emoji reactions tách riêng, tránh unbounded growth trong messages | Medium |
| 5 | `user_chat_profiles` | Snapshot user info cho MongoDB (Extended Reference), settings chat | Small |
| 6 | `pinned_messages` | Tin nhắn ghim per conversation | Small |

> [!NOTE]
> **Typing indicators** và **Presence status** được xử lý hoàn toàn bằng **Redis** — không cần collection MongoDB. Chi tiết ở Part 4.

---

## 3. Schema Chi Tiết

### 3.1 Collection: `conversations`

**Pattern áp dụng**: Computed Pattern (unread count, member count), Subset Pattern (last message preview), Extended Reference Pattern (participant snapshots)

```json
{
  "_id": ObjectId("665a1b2c3d4e5f6a7b8c9d0e"),
  "type": "GROUP",                          // "DIRECT" | "GROUP"
  "name": "Team Backend VivuMate",          // null cho DIRECT
  "avatar_url": "https://cdn.vivumate.com/groups/team-be.jpg",  // null cho DIRECT
  "description": "Nhóm dev backend",       // optional

  // === PARTICIPANTS (Subset Pattern — chỉ embed metadata nhẹ) ===
  "participants": [
    {
      "user_id": 1001,                      // pg_user_id (Long từ PostgreSQL)
      "username": "trungkien",
      "full_name": "Bùi Trung Kiên",
      "avatar_url": "https://cdn.vivumate.com/avatars/kien.jpg",
      "role": "ADMIN",                      // "ADMIN" | "MEMBER" — chỉ dùng cho GROUP
      "joined_at": ISODate("2025-01-15T08:00:00Z"),
      "nickname": null,                     // biệt danh trong group
      "is_muted": false,                    // tắt thông báo
      "muted_until": null                   // mute tạm thời
    },
    {
      "user_id": 1002,
      "username": "minhphuong",
      "full_name": "Nguyễn Minh Phương",
      "avatar_url": "https://cdn.vivumate.com/avatars/phuong.jpg",
      "role": "MEMBER",
      "joined_at": ISODate("2025-01-15T08:00:00Z"),
      "nickname": "Phương đẹp trai",
      "is_muted": false,
      "muted_until": null
    }
  ],

  "participant_ids": [1001, 1002, 1003],    // array riêng để query nhanh

  // === COMPUTED FIELDS (Computed Pattern) ===
  "member_count": 3,                        // precomputed, update khi add/remove member
  "unread_counts": {                        // per-user unread count
    "1001": 0,
    "1002": 5,
    "1003": 12
  },

  // === LAST MESSAGE PREVIEW (Subset Pattern) ===
  "last_message": {
    "message_id": ObjectId("665b2c3d4e5f6a7b8c9d0e1f"),
    "sender_id": 1002,
    "sender_name": "Nguyễn Minh Phương",
    "content_preview": "Anh ơi review PR giúp em với 🙏",  // truncated 100 chars
    "content_type": "TEXT",
    "sent_at": ISODate("2025-06-01T14:30:00Z")
  },

  "last_activity_at": ISODate("2025-06-01T14:30:00Z"),  // = last_message.sent_at, dùng để sort

  // === GROUP-SPECIFIC SETTINGS ===
  "settings": {
    "only_admins_can_send": false,
    "only_admins_can_edit_info": true,
    "join_approval_required": false
  },

  // === DIRECT MESSAGE — unique pair ===
  "dm_pair": [1001, 1002],                  // sorted array, chỉ dùng cho type=DIRECT, unique index

  "created_by": 1001,
  "created_at": ISODate("2025-01-15T08:00:00Z"),
  "updated_at": ISODate("2025-06-01T14:30:00Z"),
  "deleted_at": null                         // soft delete
}
```

#### Giải thích từng field

| Field | Type | Required | Mục đích |
|-------|------|----------|----------|
| `_id` | ObjectId | ✅ | Primary key |
| `type` | String enum | ✅ | Phân biệt DM vs Group |
| `name` | String | Group only | Tên nhóm |
| `participants` | Array\<Object\> | ✅ | Extended Reference — snapshot nhẹ, tránh $lookup khi render |
| `participant_ids` | Array\<Long\> | ✅ | Flat array cho index + query `{participant_ids: userId}` |
| `member_count` | Int | ✅ | Computed Pattern — tránh `$size` aggregation |
| `unread_counts` | Object (Map) | ✅ | Computed Pattern — key là `user_id`, value là số tin chưa đọc |
| `last_message` | Object | Optional | Subset Pattern — preview hiển thị trong conversation list |
| `last_activity_at` | Date | ✅ | Sort conversation list, **đây là field quan trọng nhất cho hot read path** |
| `dm_pair` | Array\<Long\> | DM only | Sorted pair để tạo unique index, ngăn tạo duplicate DM |
| `settings` | Object | Group only | Cấu hình group |

#### Quyết định thiết kế — Embedding vs Referencing

| Quyết định | Lựa chọn | Lý do |
|------------|----------|-------|
| `participants` | **Embed** | Số lượng participant giới hạn (group thường < 1000 người). Embed tránh $lookup khi render conversation list — **hot read path**. |
| `unread_counts` | **Embed as Map** | Mỗi conversation lưu unread per-user. Dùng Map thay vì Array vì update atomic `$inc` dễ hơn. |
| `last_message` | **Embed (denormalized)** | Subset Pattern — chỉ lấy vài fields preview. Cập nhật mỗi khi có tin mới (write thêm 1 update nhưng save hàng triệu reads). |
| Messages content | **Reference** (separate collection) | Messages là unbounded — KHÔNG embed vào conversation. |

> [!WARNING]  
> **Unbounded growth risk**: Field `participants` và `unread_counts` có thể lớn với group khổng lồ (>1000 thành viên). **Giải pháp Outlier Pattern**: Với group > 500 members, chuyển `participants` chi tiết sang collection riêng `conversation_members` và chỉ giữ `participant_ids` + `member_count` trong conversation document. Trong thực tế VivuMate, giới hạn group 500 người là hợp lý.

#### Indexes cho `conversations`

```javascript
// 1. HOT PATH: Load danh sách conversation của user, sort theo activity mới nhất
//    Query: { participant_ids: userId } sort { last_activity_at: -1 }
db.conversations.createIndex(
  { "participant_ids": 1, "last_activity_at": -1 },
  { name: "idx_user_conversations_latest" }
)

// 2. Tìm DM conversation giữa 2 user (prevent duplicate)
//    Query: { type: "DIRECT", dm_pair: [userA, userB] }  (sorted)
db.conversations.createIndex(
  { "dm_pair": 1 },
  { unique: true, sparse: true, name: "idx_dm_pair_unique" }
)

// 3. Tìm conversation theo type
db.conversations.createIndex(
  { "type": 1, "last_activity_at": -1 },
  { name: "idx_type_activity" }
)
```

---

### 3.2 Collection: `messages`

**Pattern áp dụng**: Polymorphic Pattern (content types), Extended Reference Pattern (sender snapshot)

```json
{
  "_id": ObjectId("665b2c3d4e5f6a7b8c9d0e1f"),
  "conversation_id": ObjectId("665a1b2c3d4e5f6a7b8c9d0e"),

  // === SENDER SNAPSHOT (Extended Reference Pattern) ===
  "sender": {
    "user_id": 1002,
    "username": "minhphuong",
    "full_name": "Nguyễn Minh Phương",
    "avatar_url": "https://cdn.vivumate.com/avatars/phuong.jpg"
  },

  // === CONTENT (Polymorphic Pattern) ===
  "content_type": "TEXT",               // "TEXT"|"IMAGE"|"FILE"|"VIDEO"|"AUDIO"|"LINK_PREVIEW"|"SYSTEM"
  "content": {
    "text": "Anh ơi review PR giúp em với 🙏"
  },

  // === THREADING / REPLIES ===
  "reply_to": {
    "message_id": ObjectId("665b1a2b3c4d5e6f7a8b9c0d"),
    "sender_name": "Bùi Trung Kiên",
    "content_preview": "OK em gửi link PR đi",   // truncated
    "content_type": "TEXT"
  },
  "thread_root_id": null,              // nếu là reply trong thread → ID của message gốc
  "reply_count": 0,                     // Computed Pattern — đếm số reply

  // === MENTIONS ===
  "mentions": [
    { "user_id": 1001, "username": "trungkien", "type": "USER" }
    // type: "USER" | "EVERYONE"
  ],

  // === EDIT HISTORY ===
  "is_edited": false,
  "edit_history": [],                   // Array<{ content, edited_at }>
  // Khi edit: push old content vào đây, cập nhật content mới

  // === SOFT DELETE ===
  "deleted_for": [],                    // Array<Long> — user_ids đã xóa "cho tôi"
  "deleted_for_everyone": false,        // true = ẩn cho tất cả
  "deleted_at": null,

  // === METADATA ===
  "created_at": ISODate("2025-06-01T14:30:00Z"),
  "updated_at": ISODate("2025-06-01T14:30:00Z")
}
```

#### Polymorphic Pattern — Ví dụ các loại content

````carousel
```json
// TEXT message
{
  "content_type": "TEXT",
  "content": {
    "text": "Hello mọi người! 👋"
  }
}
```
<!-- slide -->
```json
// IMAGE message
{
  "content_type": "IMAGE",
  "content": {
    "text": "Ảnh team building hôm qua",      // caption (optional)
    "media": {
      "url": "https://cdn.vivumate.com/media/img_001.jpg",
      "thumbnail_url": "https://cdn.vivumate.com/media/img_001_thumb.jpg",
      "filename": "team_building.jpg",
      "mime_type": "image/jpeg",
      "size_bytes": 2048576,
      "width": 1920,
      "height": 1080
    }
  }
}
```
<!-- slide -->
```json
// FILE message
{
  "content_type": "FILE",
  "content": {
    "text": "Tài liệu thiết kế DB",
    "media": {
      "url": "https://cdn.vivumate.com/files/db_design.pdf",
      "thumbnail_url": null,
      "filename": "db_design_v2.pdf",
      "mime_type": "application/pdf",
      "size_bytes": 5242880
    }
  }
}
```
<!-- slide -->
```json
// LINK_PREVIEW message
{
  "content_type": "LINK_PREVIEW",
  "content": {
    "text": "Xem bài viết này hay lắm https://example.com/article",
    "link_preview": {
      "url": "https://example.com/article",
      "og_title": "10 MongoDB Best Practices",
      "og_description": "Hướng dẫn thiết kế schema MongoDB cho production",
      "og_image": "https://example.com/og_image.jpg",
      "og_site_name": "TechBlog"
    }
  }
}
```
<!-- slide -->
```json
// SYSTEM event message
{
  "content_type": "SYSTEM",
  "content": {
    "text": "Nguyễn Minh Phương đã tham gia nhóm",
    "system_event": "MEMBER_JOINED",     // MEMBER_JOINED|MEMBER_LEFT|GROUP_RENAMED|ADMIN_CHANGED
    "actor_id": 1002,
    "target_id": null
  }
}
```
````

#### Quyết định thiết kế

| Quyết định | Lựa chọn | Lý do |
|------------|----------|-------|
| Messages | **Separate collection** (Reference) | Unbounded — 1 conversation có thể tới hàng triệu messages |
| `sender` | **Embed snapshot** | Extended Reference — tránh $lookup khi load message feed (hot path). Chấp nhận stale data ngắn hạn. |
| `reply_to` | **Embed preview** | Chỉ embed preview nhẹ (sender_name + truncated content). Không embed toàn bộ message gốc. |
| `edit_history` | **Embed array** | Bounded — user edit có giới hạn thực tế (< 50 lần). |
| `reactions` | **Separate collection** | Tránh unbounded growth, tránh contention khi nhiều người react cùng lúc. |
| `content` | **Polymorphic embedded** | Cùng collection nhưng schema khác nhau theo `content_type`. Flexible, query đơn giản. |

> [!IMPORTANT]
> **Update strategy cho sender snapshot**: Khi user thay đổi `avatar_url` hoặc `full_name` trên PostgreSQL, dùng **background job** (async event) để cập nhật tất cả messages gần đây (ví dụ 30 ngày gần nhất). Messages cũ hơn giữ snapshot cũ — chấp nhận eventually consistent.

#### Indexes cho `messages`

```javascript
// 1. HOT PATH: Load messages trong conversation, cursor-based pagination (newest first)
//    Query: { conversation_id, _id: { $lt: cursor } } sort { _id: -1 } limit 30
db.messages.createIndex(
  { "conversation_id": 1, "_id": -1 },
  { name: "idx_conversation_messages_cursor" }
)

// 2. Load thread replies
//    Query: { thread_root_id: messageId } sort { _id: 1 }
db.messages.createIndex(
  { "thread_root_id": 1, "_id": 1 },
  { partialFilterExpression: { "thread_root_id": { $exists: true, $ne: null } },
    name: "idx_thread_replies" }
)

// 3. Full-text search on message content
db.messages.createIndex(
  { "content.text": "text" },
  { name: "idx_message_text_search",
    default_language: "none" }    // "none" vì tiếng Việt không có stemmer mặc định
)

// 4. Tìm messages có mention user cụ thể
db.messages.createIndex(
  { "conversation_id": 1, "mentions.user_id": 1 },
  { partialFilterExpression: { "mentions": { $exists: true, $ne: [] } },
    name: "idx_mentions" }
)

// 5. Messages của 1 user (cho profile / search)
db.messages.createIndex(
  { "sender.user_id": 1, "created_at": -1 },
  { name: "idx_sender_messages" }
)
```

> [!TIP]
> **Cursor-based pagination**: Dùng `_id` (ObjectId) làm cursor — ObjectId chứa timestamp nên tự nhiên sort theo thời gian. Không cần field `created_at` riêng cho pagination. Query mẫu: `{ conversation_id: X, _id: { $lt: ObjectId(lastSeenId) } }` + `sort({_id: -1})` + `limit(30)`.

---

*Tiếp tục ở Part 2: Message Read States, Reactions, User Chat Profiles, Pinned Messages*
