package com.vivumate.coreapi.config;

import com.vivumate.coreapi.websocket.interceptor.WebSocketAuthInterceptor;
import com.vivumate.coreapi.websocket.interceptor.WebSocketRateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.util.List;
import java.util.concurrent.Executors;

/**
 * WebSocket STOMP configuration for the real-time messaging system.
 * <p>
 * <b>Architecture decisions:</b>
 * <ul>
 *   <li><b>Protocol:</b> STOMP over WebSocket — provides a structured messaging
 *       protocol with destinations, subscriptions, and message acknowledgments
 *       on top of raw WebSocket.</li>
 *   <li><b>Broker:</b> Simple in-memory broker (Spring default). For production
 *       multi-instance deployment, cross-server routing is handled by Redis Pub/Sub
 *       in {@code CrossServerMessageRouter} (Phase 6), NOT by an external STOMP broker.
 *       This avoids the operational complexity of RabbitMQ/ActiveMQ while achieving
 *       the same result.</li>
 *   <li><b>SockJS Fallback:</b> Enabled for browsers that don't support WebSocket
 *       natively (rare in modern browsers, but important for corporate proxies
 *       and firewalls that may block WebSocket upgrade requests).</li>
 *   <li><b>Authentication:</b> JWT-based via {@link WebSocketAuthInterceptor} on
 *       the STOMP CONNECT frame — consistent with the REST API's stateless auth.</li>
 * </ul>
 * <p>
 * <b>Virtual Threads (Java 21):</b>
 * <ul>
 *   <li>{@code spring.threads.virtual.enabled=true} auto-applies to Tomcat, @Async, @Scheduled</li>
 *   <li>STOMP channels do NOT auto-use virtual threads — they have their own thread pools</li>
 *   <li>This config <b>explicitly</b> sets virtual thread executors for both
 *       inbound (client→server) and outbound (server→client) channels via
 *       {@link #wsVirtualThreadExecutor()}</li>
 *   <li>Result: each WebSocket message runs on a virtual thread (~few KB stack)
 *       instead of a platform thread (~1MB stack), enabling millions of concurrent
 *       I/O operations without thread pool exhaustion</li>
 * </ul>
 * <p>
 * <b>Destination conventions:</b>
 * <pre>
 * /app/chat.*          → Application destinations (processed by @MessageMapping)
 * /topic/conversation/{id}  → Broadcast to conversation participants
 * /user/queue/messages      → Private queue for user-specific messages
 * /user/queue/errors        → Private queue for error notifications
 * /user/queue/sync          → Private queue for multi-device sync data
 * /user/queue/notifications → Private queue for in-app notifications
 * </pre>
 *
 * @see WebSocketAuthInterceptor
 * @see WebSocketRateLimitInterceptor
 * @see com.vivumate.coreapi.websocket.handler.WebSocketEventListener
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
@Slf4j(topic = "WEBSOCKET_CONFIG")
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor authInterceptor;
    private final WebSocketRateLimitInterceptor rateLimitInterceptor;

    @Value("${application.security.cors.allowed-origins}")
    private List<String> allowedOrigins;

    // ═══════════════════════════════════════════════════════════
    // VIRTUAL THREAD EXECUTOR — Java 21
    // ═══════════════════════════════════════════════════════════

    /**
     * Creates a Virtual Thread executor for WebSocket STOMP channels.
     * <p>
     * <b>Why this is critical:</b> Without explicit configuration, Spring's STOMP
     * message broker uses {@code ThreadPoolTaskExecutor} with a <b>fixed pool of
     * ~10 platform threads</b>. Each platform thread consumes ~1MB of stack memory.
     * Under load, all 10 threads can be blocked on I/O (MongoDB, Redis), causing
     * message processing to queue up and latency to spike.
     * <p>
     * <b>With Virtual Threads:</b>
     * <ul>
     *   <li>Each WebSocket message gets its own virtual thread (~few KB stack)</li>
     *   <li>When a virtual thread blocks on I/O, the carrier thread is released
     *       to run other virtual threads — zero wasted CPU time</li>
     *   <li>No pool exhaustion — virtual threads are created on-demand, not pooled</li>
     *   <li>Can handle millions of concurrent blocking operations</li>
     * </ul>
     * <p>
     * <b>Note:</b> {@code spring.threads.virtual.enabled=true} does NOT apply to
     * STOMP channels. It only affects Tomcat, @Async, and @Scheduled.
     * We must configure STOMP channels explicitly.
     */
    @Bean("wsVirtualThreadExecutor")
    public AsyncTaskExecutor wsVirtualThreadExecutor() {
        log.info("Initializing WebSocket Virtual Thread Executor (Java 21)");
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }

    // ═══════════════════════════════════════════════════════════
    // MESSAGE BROKER
    // ═══════════════════════════════════════════════════════════

    /**
     * Configures the message broker.
     * <p>
     * <b>Simple broker</b> handles subscriptions to:
     * <ul>
     *   <li>{@code /topic/*} — broadcast destinations (conversation messages, presence events)</li>
     *   <li>{@code /queue/*} — point-to-point destinations (user-specific queues)</li>
     * </ul>
     * <p>
     * <b>Heartbeat:</b> Server sends heartbeat every 25s, expects client heartbeat
     * every 25s. If no heartbeat received within the timeout, the connection is
     * considered dead and will be closed. The 25-second interval is chosen to:
     * <ul>
     *   <li>Keep the connection alive through NAT timeouts (typically 30-60s)</li>
     *   <li>Detect dead connections quickly enough for presence accuracy</li>
     *   <li>Minimize bandwidth overhead on mobile networks</li>
     * </ul>
     * <p>
     * <b>User destination prefix:</b> {@code /user} — Spring automatically routes
     * messages sent via {@code convertAndSendToUser(userId, dest, payload)} to the
     * correct user's session(s). The userId comes from {@code StompPrincipal.getName()}.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{25000, 25000})  // server-to-client (ping), client-to-server (pong)
                .setTaskScheduler(createHeartbeatScheduler());

        // Prefix for @MessageMapping destinations
        // Client sends to: /app/chat.send → handled by @MessageMapping("/chat.send")
        registry.setApplicationDestinationPrefixes("/app");

        // Prefix for user-specific destinations
        // Server sends: convertAndSendToUser("1001", "/queue/messages", msg)
        // Client subscribes: /user/queue/messages
        registry.setUserDestinationPrefix("/user");
    }

    // ═══════════════════════════════════════════════════════════
    // STOMP ENDPOINTS
    // ═══════════════════════════════════════════════════════════

    /**
     * Registers the STOMP WebSocket endpoint.
     * <p>
     * <b>Endpoint:</b> {@code /ws-connect}
     * <ul>
     *   <li>WebSocket URL: {@code ws://host:port/ws-connect}</li>
     *   <li>SockJS URL: {@code http://host:port/ws-connect} (HTTP transport fallback)</li>
     * </ul>
     * <p>
     * <b>CORS:</b> Uses the same allowed origins as the REST API
     * (configured in {@code application.yml}).
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = allowedOrigins.toArray(String[]::new);

        // Primary: native WebSocket (mobile apps, modern browsers)
        registry.addEndpoint("/ws-connect")
                .setAllowedOrigins(origins);

        // Fallback: SockJS for environments where WebSocket is blocked
        registry.addEndpoint("/ws-connect")
                .setAllowedOrigins(origins)
                .withSockJS()
                .setStreamBytesLimit(512 * 1024)         // 512KB per streaming response
                .setHttpMessageCacheSize(1000)            // Cache up to 1000 messages during transport switch
                .setDisconnectDelay(30 * 1000L);          // 30s before closing idle SockJS session

        log.info("STOMP endpoints registered: /ws-connect (WebSocket + SockJS), allowedOrigins={}",
                allowedOrigins);
    }

    // ═══════════════════════════════════════════════════════════
    // CHANNEL CONFIGURATION — Virtual Threads
    // ═══════════════════════════════════════════════════════════

    /**
     * Configures the inbound channel (client → server) with:
     * <ol>
     *   <li><b>Virtual Thread executor</b> — each incoming STOMP frame
     *       (CONNECT, SEND, SUBSCRIBE, etc.) is processed on a virtual thread</li>
     *   <li>{@link WebSocketAuthInterceptor} — authenticates on CONNECT (must be first)</li>
     *   <li>{@link WebSocketRateLimitInterceptor} — rate-limits SEND frames</li>
     * </ol>
     * <p>
     * <b>Without virtual threads:</b> Default pool = ~10 platform threads.
     * If 10 users send messages simultaneously and each message handler blocks
     * on MongoDB/Redis I/O for 50ms, the 11th message must WAIT in a queue.
     * <p>
     * <b>With virtual threads:</b> Unlimited concurrent processing.
     * 10,000 simultaneous messages? Each gets its own virtual thread.
     * When one blocks on I/O, the carrier thread handles others.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor, rateLimitInterceptor);
        registration.executor(wsVirtualThreadExecutor());
    }

    /**
     * Configures the outbound channel (server → client) with
     * a virtual thread executor.
     * <p>
     * This channel handles:
     * <ul>
     *   <li>Delivering messages to subscribed clients</li>
     *   <li>Sending heartbeat frames</li>
     *   <li>Broadcasting events (new message, typing, presence)</li>
     * </ul>
     * <p>
     * <b>Why virtual threads here too:</b> When broadcasting a message
     * to a group of 500 members, the outbound channel must serialize
     * and write the message to 500 WebSocket sessions. With platform
     * threads, this could exhaust the pool. With virtual threads,
     * each write operation is non-blocking at the thread level.
     */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.executor(wsVirtualThreadExecutor());
    }

    // ═══════════════════════════════════════════════════════════
    // TRANSPORT LIMITS
    // ═══════════════════════════════════════════════════════════

    /**
     * Configures WebSocket transport limits.
     * <p>
     * <b>Message size limit:</b> 64KB — sufficient for text messages (max ~4KB)
     * with overhead. Media is uploaded separately via REST API; only the URL
     * is sent via WebSocket.
     * <p>
     * <b>Send buffer size limit:</b> 512KB — max bytes buffered per session
     * when the client is slow to read. Prevents memory exhaustion from
     * slow consumers.
     * <p>
     * <b>Send time limit:</b> 20 seconds — max time to wait for a slow client
     * to accept a message. After this, the message is dropped and the
     * session may be closed.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(64 * 1024)          // 64 KB max message size
                .setSendBufferSizeLimit(512 * 1024)       // 512 KB send buffer per session
                .setSendTimeLimit(20 * 1000);             // 20 seconds send timeout
    }

    // ═══════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════

    /**
     * Creates a task scheduler for the simple broker's heartbeat mechanism.
     * <p>
     * The simple broker needs a scheduler to send periodic heartbeat frames.
     * Pool size = 1 is sufficient — heartbeat scheduling is lightweight.
     * <p>
     * <b>Note:</b> This intentionally uses a platform thread (not virtual thread).
     * Schedulers need a persistent thread that sleeps and wakes up periodically —
     * this is fundamentally a scheduling concern, not an I/O concern.
     * Virtual threads are designed for I/O-bound tasks, not periodic scheduling.
     */
    private ThreadPoolTaskScheduler createHeartbeatScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }
}
