# 📋 Phase 2.1 — Architecture Review

## 1. Architecture Review

### ✅ Strengths

| # | Strength | Why it matters |
|---|---|---|
| S1 | **Persist-before-broadcast** | Message is durable in MongoDB BEFORE any delivery attempt. If server crashes after persist but before broadcast, message is NOT lost — recipient gets it on next load. This is the #1 rule in messaging systems. |
| S2 | **ACK with clientMessageId correlation** | Client can match ACK to the exact pending message. Enables optimistic UI: render immediately, confirm on ACK, retry on timeout. Standard pattern used by WhatsApp/Telegram. |
| S3 | **Redis idempotency** | Prevents double-processing on client reconnect/retry. Critical for "at-most-once" processing guarantee. |
| S4 | **Leveraging existing infrastructure** | Handler reuses `WebSocketExceptionHandler`, `WebSocketRateLimitInterceptor`, `StompPrincipal` — no new error handling infrastructure needed. |
| S5 | **Service layer untouched** | Core `MessageServiceImpl` logic (validate → persist → update conversation → unread) is preserved. Handler only orchestrates the WebSocket-specific flow around it. |
| S6 | **Virtual threads** | Handler runs on virtual thread — blocking I/O (MongoDB, Redis) doesn't exhaust platform threads. 10K concurrent sends are fine. |

### ⚠️ Weaknesses

#### W1: Idempotency key lacks user scoping

**Current:** `ws_idem:{clientMessageId}`

**Problem:** `clientMessageId` is client-generated (UUID). Without user scoping, there's a theoretical (though unlikely with UUIDs) namespace collision between users. More critically, a **malicious client** could probe another user's `clientMessageId` to block their message.

**Failure scenario:**
```
User A sends {clientMessageId: "abc-123"} → persisted OK
User B (malicious) sends {clientMessageId: "abc-123"} → gets DUPLICATE ACK
→ User B's legitimate message is silently dropped
```

**Fix:** Scope key by userId: `ws_idem:{userId}:{clientMessageId}`

---

#### W2: "pending" state leak on crash

**Current flow:**
```
SETNX ws_idem:abc "pending" → process → SET ws_idem:abc "messageId"
```

**Problem:** If server crashes between SETNX("pending") and SET(messageId), the key stays "pending" until TTL expires (5 min). During that window, client retries see the key exists but value is "pending" — no messageId to return in DUPLICATE ACK.

**Failure scenario:**
```
T=0  SETNX "pending" → true
T=1  Server crashes during MongoDB write
T=2  Client reconnects, retries same clientMessageId
T=3  SETNX → false, GET → "pending" (no messageId!)
T=4  Client stuck — can't get ACK for 5 minutes
```

**Fix:** Two options:
- **Option A (simple):** If value is "pending", delete the key and reprocess. The message either wasn't persisted (safe to retry) or was persisted (idempotency from MongoDB side — duplicate document, which is handled by the save operation).
- **Option B (robust):** Don't use "pending". Use a Lua script that does SETNX + returns the existing value atomically. If key exists with a real messageId → DUPLICATE. If key doesn't exist → process.

**Recommendation:** Option A — simpler, sufficient for Phase 2.1.

---

#### W3: No message ordering guarantee

**Problem:** Two messages sent rapidly by the same user can be persisted out of order if virtual threads execute concurrently.

**Failure scenario:**
```
T=0  User sends "Hello" (thread-1 starts)
T=1  User sends "How are you?" (thread-2 starts)
T=2  thread-2 finishes MongoDB write first (faster I/O)
T=3  thread-1 finishes MongoDB write
→ Recipients see: "How are you?" then "Hello" — wrong order
```

**Analysis for Phase 2.1 (single server):**
- MongoDB `ObjectId` includes a 4-byte timestamp (second precision) + 5-byte random + 3-byte counter
- Within the same second AND same process, the counter ensures ordering
- Across seconds, the timestamp ensures ordering
- **Risk is LOW** for single server — ObjectId ordering is sufficient in practice
- **Risk becomes HIGH** with multi-server (Phase 6) — different ObjectId counters

**Recommendation for Phase 2.1:** Accept ObjectId ordering as sufficient. Add `sequenceNumber` field to `MessageDocument` as **reserved/future-ready** but don't populate it yet. When Phase 6 arrives, implement Redis `INCR` per conversation.

---

#### W4: Multi-device gap

**Current:** Sender receives ACK only. Sender's OTHER devices (phone, tablet, other browser tabs) receive nothing.

**Problem:** User sends message from phone → opens laptop → message not visible until page refresh.

**How production apps handle this:**

| App | Behavior |
|---|---|
| WhatsApp | All sender devices receive the message via server push |
| Telegram | All devices synced via MTProto |
| Discord | All sessions receive the MESSAGE_CREATE event |

**Recommendation:** Include sender in the broadcast list. The sending device deduplicates using `clientMessageId`:

```
Sending device: receives ACK (confirmation) + broadcast (ignores via clientMessageId dedup)
Other devices:  receives broadcast → shows message in conversation
Recipients:     receives broadcast → shows message + plays notification
```

**Tradeoff:** Slightly more traffic (sender gets message twice — ACK + broadcast). But this is negligible and the UX improvement is significant.

---

#### W5: Synchronous broadcast in handler

**Current:** Handler loops through recipients and calls `convertAndSendToUser()` sequentially.

**Analysis:**
- With `SimpleBroker` (in-memory): each call is ~microseconds → 500 recipients ≈ <1ms. **Not a problem.**
- With external broker (Phase 7 RabbitMQ): each call becomes a network operation → 500 recipients ≈ 50-500ms. **Becomes a problem.**

**Recommendation for Phase 2.1:** Keep synchronous — SimpleBroker is fast enough. Extract broadcast logic into a separate method so it can be made async later.

---

## 2. Idempotency Review

### Current Design
```
Key:    ws_idem:{clientMessageId}
Value:  "pending" → overwritten with messageId
TTL:    5 minutes
```

### Improved Design

```
Key:    ws_idem:{userId}:{clientMessageId}
Value:  messageId (set AFTER successful persist, not "pending")
TTL:    5 minutes
```

**Changed flow:**

```java
String idemKey = IDEM_KEY_PREFIX + senderId + ":" + request.getClientMessageId();

// Step 1: Check if already processed
String existingMessageId = redisTemplate.opsForValue().get(idemKey);
if (existingMessageId != null) {
    // Already processed → return DUPLICATE ACK
    sendAck(senderId, clientMsgId, existingMessageId, DUPLICATE);
    return;
}

// Step 2: Process message (persist + update conversation)
SendMessageResult result = messageService.sendMessage(...);
String messageId = result.savedMessage().getId().toHexString();

// Step 3: Store idempotency key AFTER successful persist
redisTemplate.opsForValue().set(idemKey, messageId, IDEM_TTL);

// Step 4: ACK + broadcast
```

**Why this is better:**
- No "pending" state leak — key only exists with a valid messageId
- User-scoped — no cross-user collision
- Simpler — no SETNX + overwrite dance

**Edge case — double-submit before first persist completes:**
- Two concurrent requests with same `clientMessageId` from same user
- Both pass the GET check (key doesn't exist yet)
- Both persist → duplicate message in MongoDB
- **Mitigation:** STOMP processes messages sequentially per session. Two identical `clientMessageId` from the same session is impossible. Cross-session (multi-device) duplicate is handled by the second persist's ACK — client deduplicates on `clientMessageId`.
- **Probability:** Extremely low. Acceptable for Phase 2.1.

> [!NOTE]
> If this edge case needs elimination in the future, use a Lua script: `GET + SETNX` atomically. But for Phase 2.1 with sequential STOMP processing, the simple GET-then-SET is sufficient.

---

## 3. Message Ordering Analysis

### Can messages arrive out of order?

| Scenario | Risk | Phase 2.1 | Phase 6+ |
|---|---|---|---|
| Same user, rapid sends | Low | ObjectId counter handles it | ❌ Different servers = different counters |
| Different users, same conversation | N/A | No ordering guarantee needed between users | Same |
| Retry after reconnect | Low | Idempotency prevents reprocessing | Same |
| Network delay (client → server) | Low | Server timestamps, not client | Same |

### Recommendation

**Phase 2.1:** ObjectId ordering is sufficient. No action needed.

**Phase 6+ (multi-server):** Implement conversation sequence number:

```java
// In MessageDocument — add field now, populate later
private Long sequenceNumber;  // Reserved for Phase 6

// Phase 6 implementation: Redis INCR per conversation
String seqKey = "conv_seq:" + conversationId.toHexString();
Long seq = redisTemplate.opsForValue().increment(seqKey);
message.setSequenceNumber(seq);
```

**Action for Phase 2.1:** Add `sequenceNumber` field to `MessageDocument` but leave it `null`. Clients should sort by `createdAt` (from MongoDB `@CreatedDate`).

---

## 4. Multi-Device Synchronization

### Current behavior
```
User A has: Phone (session-1) + Laptop (session-2)
User A sends from Phone:
  Phone  → receives ACK ✅
  Laptop → receives NOTHING ❌
```

### Improved behavior
```
User A sends from Phone:
  Phone  → receives ACK ✅ + broadcast (dedup by clientMessageId)
  Laptop → receives broadcast ✅ (shows message in UI)
  
User B (recipient):
  All devices → receives broadcast ✅
```

### Implementation change

```diff
- // Broadcast to recipients (excluding sender)
- for (Long recipientId : result.recipientIds()) {
+ // Broadcast to ALL participants (multi-device sync)
+ for (Long participantId : result.allParticipantIds()) {
      messagingTemplate.convertAndSendToUser(
-         recipientId.toString(), "/queue/messages", broadcast
+         participantId.toString(), "/queue/messages", broadcast
      );
  }
```

### Client-side deduplication

```javascript
stompClient.subscribe('/user/queue/messages', (msg) => {
    const message = JSON.parse(msg.body);
    
    // Check if this is my own message that I already rendered optimistically
    if (message.sender.userId === myUserId 
        && pendingMessages.has(message.clientMessageId)) {
        // Already shown — just update status from SENDING to SENT
        pendingMessages.delete(message.clientMessageId);
        return;
    }
    
    // New message (from other user, or my message on another device)
    renderMessage(message);
});
```

### Service layer change

`SendMessageResult` should include **allParticipantIds** in addition to recipientIds:

```java
public record SendMessageResult(
    MessageDocument savedMessage,
    List<Long> recipientIds,       // excludes sender
    List<Long> allParticipantIds   // includes sender
) {}
```

---

## 5. Delivery Pipeline Review

### Current: Synchronous

```
Handler thread:
  persist → ACK → broadcast(1) → broadcast(2) → ... → broadcast(N) → done
```

### Analysis

| Approach | Phase 2.1 (SimpleBroker) | Phase 6+ (External Broker) |
|---|---|---|
| Synchronous | ✅ <1ms for 500 recipients | ❌ 50-500ms blocking |
| Async (@Async) | Overkill | ✅ Decouples handler from delivery |
| Event-driven (ApplicationEvent) | Overkill | ✅ Best separation of concerns |

### Recommendation for Phase 2.1

Keep synchronous but **structure the code for future async extraction:**

```java
@MessageMapping("/chat.send")
public void handleSendMessage(...) {
    // Synchronous: validate + persist + ACK
    ...
    
    // Synchronous now, extract to async later
    broadcastToParticipants(result, broadcast);
}

// Package-private for future extraction to @Async EventListener
void broadcastToParticipants(SendMessageResult result, MessageBroadcast broadcast) {
    for (Long participantId : result.allParticipantIds()) {
        messagingTemplate.convertAndSendToUser(
            participantId.toString(), "/queue/messages", broadcast
        );
    }
}
```

This allows future refactoring to:
```java
// Phase 6+: just change this one method
@Async
@EventListener
void onMessageCreated(MessageCreatedEvent event) {
    broadcastToParticipants(event.result(), event.broadcast());
}
```

---

## 6. Delivery Status Model

### Complete lifecycle

```
SENDING ──→ SENT ──→ DELIVERED ──→ READ
   │                                  
   └──→ FAILED                       
```

| Status | Where | Trigger | Phase |
|---|---|---|---|
| `SENDING` | Client-only | User presses Send (optimistic UI) | 2.1 ✅ |
| `SENT` | Server ACK | MongoDB persist succeeds | 2.1 ✅ |
| `DUPLICATE` | Server ACK | Idempotency check detects repeat | 2.1 ✅ |
| `FAILED` | Client-only | No ACK within timeout (e.g., 10s) | 2.1 ✅ |
| `DELIVERED` | Server event | Recipient's device confirms receipt | Phase 3 |
| `READ` | Server event | Recipient opens conversation | Phase 3 |

### Phase 2.1 scope: `SENT`, `DUPLICATE`, `FAILED`

- `SENT` + `DUPLICATE`: server-side ACK (already in plan)
- `FAILED`: client-side only — if no ACK received within timeout, show retry button

**No changes to plan needed.** Current `AckStatus { SENT, DUPLICATE }` is sufficient. Client implements SENDING + FAILED locally.

---

## 7. Offline User Strategy

### Current state (already implemented!)

| Mechanism | Status | How |
|---|---|---|
| **Message persistence** | ✅ Done | `messageRepository.save()` — message stored regardless of online status |
| **Unread counter** | ✅ Done | `conversationRepository.incrementUnreadCounts()` — atomic `$inc` per recipient |
| **Mention counter** | ✅ Done | `conversationRepository.incrementUnreadMentionsCounts()` |
| **Load on reconnect** | ✅ Done | `messageService.loadMessages()` — cursor-based pagination |
| **Mark as read** | ✅ Done | `conversationService.markAsRead()` — resets unread to 0 |
| **Clear history** | ✅ Done | `clearedAt` watermark respected in all queries |

### What's NOT handled yet (future phases)

| Gap | Phase |
|---|---|
| Push notification (FCM/APNs) | Phase 5 |
| Reconnect sync (which conversations changed?) | Phase 3 |
| Delivery receipts (DELIVERED status) | Phase 3 |

### Recommendation for Phase 2.1

**No changes needed.** The existing service layer already handles offline users correctly. The message is persisted and unread counts are updated — when the offline user opens the app, they see the conversation with unread badge and can load messages.

---

## 8. Scalability Review

### Current architecture limits

| Component | Limit | Bottleneck |
|---|---|---|
| WebSocket connections | ~70K/server | RAM (80KB/conn) |
| SimpleBroker | ~50K subscriptions | O(N) lookup per broadcast |
| MongoDB writes | ~5K-10K writes/s | Depends on hardware + writeConcern |
| Redis idempotency | ~100K ops/s | Not a bottleneck |

### Phase 2.1 scalability: sufficient

For single server with 10K-50K concurrent users:
- Each user sends ~0.1-1 msg/min average → 1K-50K msgs/min → 17-833 msgs/s
- MongoDB handles 5K writes/s easily → ✅
- Redis INCR for rate limit + GET/SET for idempotency → trivial → ✅
- Broadcast: average conversation has 2-10 participants → 2-10 `convertAndSendToUser` calls per message → SimpleBroker handles easily → ✅

### When to worry

| Users | Msgs/s | Action needed |
|---|---|---|
| 10K | ~170 | None — Phase 2.1 design is fine |
| 50K | ~830 | None — still within limits |
| 100K | ~1,700 | Phase 6: multi-server + cross-server routing |
| 500K | ~8,300 | Phase 7: RabbitMQ + MongoDB sharding |
| 1M+ | ~16,700 | Dedicated gateway + topic sharding |

---

## 9. Updated Architecture

### Message flow (Phase 2.1 — improved)

```
Client (STOMP SEND /app/chat.send)
  │
  │ {clientMessageId, conversationId, contentType, content, mentions?, replyTo?}
  │
  ▼
┌──────────────────────────────────────────────────┐
│             ChatMessageHandler                    │
│                                                   │
│  1. Extract senderId from StompPrincipal          │
│  2. Validate request fields                       │
│  3. Idempotency: GET ws_idem:{userId}:{clientMsgId}  │
│     └─ If exists → ACK(DUPLICATE), return         │
│                                                   │
│  4. messageService.sendMessage()                  │
│     ├─ Validate membership (MongoDB)              │
│     ├─ Build sender snapshot (PostgreSQL)          │
│     ├─ Persist MessageDocument (MongoDB)          │
│     ├─ Update lastMessage preview                 │
│     ├─ Increment unread counts                    │
│     └─ Return SendMessageResult                   │
│                                                   │
│  5. SET ws_idem:{userId}:{clientMsgId} EX 300     │
│  6. ACK → /user/queue/ack                         │
│  7. Broadcast → /user/queue/messages              │
│     └─ ALL participants (multi-device sync)       │
└──────────────────────────────────────────────────┘
  │                    │
  ▼                    ▼
Sender (all devices)  Recipients (all devices)
  │                    │
  ├─ /queue/ack        ├─ /queue/messages
  │  {SENT, msgId}     │  {full message}
  │                    │
  └─ /queue/messages   └─ Render + notification
     {full message}
     (dedup by clientMsgId)
```

---

## 10. Final Recommendation

### 🔴 Must Have Before Production (apply to Phase 2.1 plan)

| # | Change | Effort | Reason |
|---|---|---|---|
| M1 | **User-scoped idempotency key:** `ws_idem:{userId}:{clientMessageId}` | 1 line | Security — prevents cross-user collision |
| M2 | **Remove "pending" state:** use GET-then-SET instead of SETNX+overwrite | ~10 lines | Reliability — eliminates crash-state leak |
| M3 | **Include sender in broadcast** for multi-device sync | 1 line change | UX — all devices stay in sync |
| M4 | **Add `clientMessageId` field to `MessageBroadcast`** | 1 field | Enables client-side deduplication for multi-device |
| M5 | **Return `allParticipantIds` in `SendMessageResult`** | 1 field | Supports M3 (broadcast to all participants) |

### 🟡 Should Have Soon (Phase 3 or early Phase 6)

| # | Improvement | Reason |
|---|---|---|
| S1 | **Conversation sequence number** (`sequenceNumber` field on MessageDocument) | Ordering guarantee for multi-server. Add field now, populate in Phase 6. |
| S2 | **DELIVERED + READ receipts** | Core chat UX — users expect to see delivery/read status |
| S3 | **Event-driven broadcast** (Spring ApplicationEvent) | Decouples handler from delivery — prepares for Phase 6 async routing |
| S4 | **Reconnect sync endpoint** | Client needs to know which conversations changed since last online |

### 🟢 Nice to Have (Phase 7+)

| # | Optimization | When |
|---|---|---|
| N1 | `WriteConcern.MAJORITY` for message collection | When deploying MongoDB replica set |
| N2 | External broker (RabbitMQ STOMP relay) | When >50K subscriptions on SimpleBroker |
| N3 | Lua script for atomic idempotency (GET+SETNX) | When multi-server concurrent duplicate becomes measurable |
| N4 | MongoDB Change Streams for event-driven broadcast | When replacing polling-based sync |

---

## Summary: Changes to Implementation Plan

Only **M1–M5** should be applied to the Phase 2.1 plan before implementation:

```diff
  Idempotency key:
- ws_idem:{clientMessageId}
+ ws_idem:{userId}:{clientMessageId}

  Idempotency flow:
- SETNX "pending" → process → SET messageId
+ GET → if exists: DUPLICATE → process → SET messageId

  SendMessageResult:
- SendMessageResult(savedMessage, recipientIds)
+ SendMessageResult(savedMessage, recipientIds, allParticipantIds)

  Broadcast:
- for (Long recipientId : result.recipientIds())
+ for (Long participantId : result.allParticipantIds())

  MessageBroadcast DTO:
+ String clientMessageId  // for client-side dedup
```

Tất cả thay đổi đều nhỏ (tổng ~15 dòng code thay đổi) nhưng giải quyết các vấn đề quan trọng về **security**, **reliability**, và **multi-device UX**.
