# Phase 2.1 — Send Message via WebSocket

## Goal

Implement `@MessageMapping("/chat.send")` handler that turns the WebSocket infrastructure into a **working real-time chat**. After this phase, two users can send and receive messages instantly.

## Message Flow

```
Client (STOMP SEND)                     Server
  │                                       │
  ├─ /app/chat.send ──────────────────► ChatMessageHandler
  │  {clientMessageId, conversationId,    │
  │   contentType, content,               ├─ 1. Extract senderId from StompPrincipal
  │   mentions?, replyTo?}                ├─ 2. Validate request fields
  │                                       ├─ 3. Idempotency check (Redis SETNX)
  │                                       │     └─ If duplicate → ACK(DUPLICATE), return
  │                                       ├─ 4. messageService.sendMessage()
  │                                       │     └─ membership ✓ → persist → update conv → unread
  │                                       ├─ 5. Store messageId in idem key
  │  ◄── /user/queue/ack ────────────────├─ 6. ACK to sender {clientMessageId, messageId, SENT}
  │                                       │
  │  ◄── /user/queue/messages ───────────└─ 7. Broadcast to ALL recipients
  │      {full message payload}                  (excluding sender)
```

> [!NOTE]
> **Multi-device:** `convertAndSendToUser()` delivers to ALL sessions of a user. Sender's other devices will get the ACK on `/queue/ack`. Full multi-device sync (sender receives own message on other devices) sẽ được implement ở Phase riêng.

---

## Proposed Changes

### Component 1: WebSocket DTOs

#### [NEW] `websocket/dto/SendMessageRequest.java`

Client STOMP payload. Validated manually in handler for cleaner error messages.

```java
Fields:
  String clientMessageId    // UUID — idempotency key, client generates
  String conversationId     // ObjectId hex (24 chars)
  ContentType contentType   // TEXT, IMAGE, FILE, VIDEO, AUDIO, LINK_PREVIEW
  MessageContent content    // {text, media, linkPreview}
  List<Mention> mentions    // optional
  ReplyToSnapshot replyTo   // optional
```

#### [NEW] `websocket/dto/MessageAckResponse.java`

ACK sent to sender via `/user/queue/ack`. Allows client to correlate with pending message.

```java
Fields:
  String clientMessageId    // Correlation ID from request
  String messageId          // MongoDB ObjectId hex (server-generated)
  AckStatus status          // SENT | DUPLICATE
  Instant timestamp

Enum AckStatus { SENT, DUPLICATE }
```

#### [NEW] `websocket/dto/MessageBroadcast.java`

Broadcast payload sent to recipients via `/user/queue/messages`. Clean API contract — converts from MongoDB document.

```java
Fields:
  String id                 // MongoDB messageId hex
  String conversationId     // hex
  SenderSnapshot sender     // {userId, username, fullName, avatarUrl}
  ContentType contentType
  MessageContent content
  List<Mention> mentions
  ReplyToSnapshot replyTo
  Instant createdAt

Static factory: from(MessageDocument doc) → MessageBroadcast
```

---

### Component 2: Service Layer

#### [MODIFY] [MessageService.java](file:///e:/VivuMateProject/src/main/java/com/vivumate/coreapi/service/MessageService.java)

Change `sendMessage()` return type from `MessageDocument` to `SendMessageResult` record.

```java
// NEW record — wraps saved message + recipient IDs
public record SendMessageResult(
    MessageDocument savedMessage,
    List<Long> recipientIds    // participantIds excluding sender
) {}

// CHANGED return type
SendMessageResult sendMessage(ObjectId conversationId, Long senderUserId, ...);
```

**Rationale:** `sendMessage()` already computes `recipientIds` internally (line 83-85 of current impl). Returning them avoids a duplicate DB call in the handler.

#### [MODIFY] [MessageServiceImpl.java](file:///e:/VivuMateProject/src/main/java/com/vivumate/coreapi/service/impl/MessageServiceImpl.java)

Minimal change — wrap return value + replace TODO comment:

```diff
- return saved;
+ return new SendMessageResult(saved, recipientIds);
```

---

### Component 3: Chat Message Handler

#### [NEW] `websocket/handler/ChatMessageHandler.java`

The core `@Controller` with `@MessageMapping("/chat.send")`.

```java
@Controller
@RequiredArgsConstructor
@Slf4j(topic = "CHAT_MESSAGE_HANDLER")
public class ChatMessageHandler {

    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;

    private static final String IDEM_KEY_PREFIX = "ws_idem:";
    private static final Duration IDEM_TTL = Duration.ofMinutes(5);
}
```

**Method: `handleSendMessage()`**

```
Step 1: Extract senderId from StompPrincipal (set during CONNECT auth)
Step 2: Validate request — conversationId format, content not empty, clientMessageId not blank
Step 3: Idempotency — Redis SETNX "ws_idem:{clientMessageId}" "pending" EX 300
         → If key exists: get stored messageId, ACK(DUPLICATE), return
Step 4: Call messageService.sendMessage() — membership validation + persist + unread update
Step 5: Overwrite idem key with actual messageId (for future duplicate detection)
Step 6: ACK to sender → convertAndSendToUser(senderId, "/queue/ack", ack)
Step 7: Broadcast → for each recipientId: convertAndSendToUser(recipientId, "/queue/messages", broadcast)

On exception: delete idem key → allows client retry
```

**Idempotency design (Redis key lifecycle):**

```
T=0  Client sends {clientMessageId: "abc-123"}
T=1  SETNX ws_idem:abc-123 "pending" EX 300  → true (new)
T=2  messageService.sendMessage() → saved, messageId = "6650..."
T=3  SET ws_idem:abc-123 "6650..." EX 300    → overwrite with messageId
T=4  ACK → {clientMessageId: "abc-123", messageId: "6650...", status: SENT}

--- Client retries (reconnect / duplicate send) ---

T=10 SETNX ws_idem:abc-123 "pending" EX 300  → false (exists!)
T=11 GET ws_idem:abc-123 → "6650..."
T=12 ACK → {clientMessageId: "abc-123", messageId: "6650...", status: DUPLICATE}

--- After 5 minutes ---

T=300 Key expires naturally → client can reuse clientMessageId (unlikely)
```

---

## Error Handling

| Scenario | Error source | Delivery |
|---|---|---|
| Invalid request (bad conversationId, empty content) | `IllegalArgumentException` | `/user/queue/errors` via `WebSocketExceptionHandler` |
| Not a participant | `AppException(CONVERSATION_ACCESS_DENIED)` | `/user/queue/errors` via `WebSocketExceptionHandler` |
| Rate limit exceeded | `WebSocketRateLimitInterceptor` | STOMP ERROR frame (before handler) |
| Duplicate message | Idempotency check | `/user/queue/ack` with `status: DUPLICATE` (not an error) |
| MongoDB failure | Runtime exception | `/user/queue/errors` + idem key cleaned up |

> [!IMPORTANT]
> Tất cả exceptions từ `@MessageMapping` methods đều được catch bởi [WebSocketExceptionHandler.java](file:///e:/VivuMateProject/src/main/java/com/vivumate/coreapi/websocket/handler/WebSocketExceptionHandler.java) hiện tại — không cần thêm error handling code.

---

## File Summary

| Action | File | Description |
|---|---|---|
| **[NEW]** | `websocket/dto/SendMessageRequest.java` | Client STOMP payload |
| **[NEW]** | `websocket/dto/MessageAckResponse.java` | ACK response to sender |
| **[NEW]** | `websocket/dto/MessageBroadcast.java` | Broadcast payload to recipients |
| **[NEW]** | `websocket/handler/ChatMessageHandler.java` | `@MessageMapping("/chat.send")` controller |
| **[MODIFY]** | `service/MessageService.java` | Return type → `SendMessageResult` |
| **[MODIFY]** | `service/impl/MessageServiceImpl.java` | Wrap return + remove TODO |

## Verification Plan

### Manual Testing (dùng websocket-test-client.html)

1. **Happy path:** Login → Connect → Send message → Xem ACK trên console
2. **Duplicate:** Send cùng `clientMessageId` 2 lần → ACK thứ 2 có `status: DUPLICATE`
3. **Access denied:** Send tới conversationId mà user không thuộc về → Error trên `/queue/errors`
4. **Multi-recipient:** 2 browser tabs (2 users khác nhau, cùng conversation) → User B nhận message khi User A gửi
5. **Redis verification:** `GET ws_idem:{clientMessageId}` → thấy messageId

### Build Verification

```bash
./mvnw compile -q
```
