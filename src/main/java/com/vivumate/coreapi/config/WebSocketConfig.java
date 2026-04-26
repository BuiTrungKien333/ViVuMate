package com.vivumate.coreapi.config;

import com.vivumate.coreapi.websocket.interceptor.WebSocketAuthInterceptor;
import com.vivumate.coreapi.websocket.interceptor.WebSocketRateLimitInterceptor;
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
 * <b>All parameters are externalized</b> to {@code application.yml} under
 * the {@code vivumate.websocket.*} namespace. Profile-specific overrides
 * (dev/docker/prod) tune values per environment without code changes.
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
 *   <li><b>SockJS Fallback:</b> Configurable per environment. Enabled in dev for
 *       browser testing, disabled in prod where mobile apps use native WebSocket.</li>
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
@Slf4j(topic = "WEBSOCKET_CONFIG")
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor authInterceptor;
    private final WebSocketRateLimitInterceptor rateLimitInterceptor;

    // --- Endpoint & Broker ---
    private final String endpoint;
    private final String appDestinationPrefix;
    private final String userDestinationPrefix;
    private final List<String> brokerDestinations;

    // --- Heartbeat ---
    private final long heartbeatServer;
    private final long heartbeatClient;

    // --- Transport ---
    private final int messageSizeLimit;
    private final int sendBufferSizeLimit;
    private final int sendTimeLimit;

    // --- SockJS ---
    private final boolean sockjsEnabled;
    private final int sockjsStreamBytesLimit;
    private final int sockjsHttpMessageCacheSize;
    private final long sockjsDisconnectDelay;

    // --- CORS ---
    private final List<String> allowedOrigins;

    public WebSocketConfig(
            WebSocketAuthInterceptor authInterceptor,
            WebSocketRateLimitInterceptor rateLimitInterceptor,
            @Value("${vivumate.websocket.endpoint:/ws-connect}") String endpoint,
            @Value("${vivumate.websocket.app-destination-prefix:/app}") String appDestinationPrefix,
            @Value("${vivumate.websocket.user-destination-prefix:/user}") String userDestinationPrefix,
            @Value("${vivumate.websocket.broker-destinations}") List<String> brokerDestinations,
            @Value("${vivumate.websocket.heartbeat.server:25000}") long heartbeatServer,
            @Value("${vivumate.websocket.heartbeat.client:25000}") long heartbeatClient,
            @Value("${vivumate.websocket.transport.message-size-limit:65536}") int messageSizeLimit,
            @Value("${vivumate.websocket.transport.send-buffer-size-limit:524288}") int sendBufferSizeLimit,
            @Value("${vivumate.websocket.transport.send-time-limit:20000}") int sendTimeLimit,
            @Value("${vivumate.websocket.sockjs.enabled:true}") boolean sockjsEnabled,
            @Value("${vivumate.websocket.sockjs.stream-bytes-limit:524288}") int sockjsStreamBytesLimit,
            @Value("${vivumate.websocket.sockjs.http-message-cache-size:1000}") int sockjsHttpMessageCacheSize,
            @Value("${vivumate.websocket.sockjs.disconnect-delay:30000}") long sockjsDisconnectDelay,
            @Value("${application.security.cors.allowed-origins}") List<String> allowedOrigins) {

        this.authInterceptor = authInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.endpoint = endpoint;
        this.appDestinationPrefix = appDestinationPrefix;
        this.userDestinationPrefix = userDestinationPrefix;
        this.brokerDestinations = brokerDestinations;
        this.heartbeatServer = heartbeatServer;
        this.heartbeatClient = heartbeatClient;
        this.messageSizeLimit = messageSizeLimit;
        this.sendBufferSizeLimit = sendBufferSizeLimit;
        this.sendTimeLimit = sendTimeLimit;
        this.sockjsEnabled = sockjsEnabled;
        this.sockjsStreamBytesLimit = sockjsStreamBytesLimit;
        this.sockjsHttpMessageCacheSize = sockjsHttpMessageCacheSize;
        this.sockjsDisconnectDelay = sockjsDisconnectDelay;
        this.allowedOrigins = allowedOrigins;
    }

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
     * Configures the message broker with externalized heartbeat and prefix values.
     * <p>
     * <b>Simple broker</b> handles subscriptions to configured destinations.
     * <p>
     * <b>Heartbeat:</b> Configured via {@code vivumate.websocket.heartbeat.*}.
     * Default: server sends every 25s, expects client pong every 25s.
     * If no heartbeat received within the timeout, the connection is
     * considered dead and will be closed. The 25-second interval is chosen to:
     * <ul>
     *   <li>Keep the connection alive through NAT timeouts (typically 30-60s)</li>
     *   <li>Detect dead connections quickly enough for presence accuracy</li>
     *   <li>Minimize bandwidth overhead on mobile networks</li>
     * </ul>
     * <p>
     * <b>User destination prefix:</b> Spring automatically routes messages sent via
     * {@code convertAndSendToUser(userId, dest, payload)} to the correct user's
     * session(s). The userId comes from {@code StompPrincipal.getName()}.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        String[] destinations = brokerDestinations.toArray(String[]::new);

        registry.enableSimpleBroker(destinations)
                .setHeartbeatValue(new long[]{heartbeatServer, heartbeatClient})
                .setTaskScheduler(createHeartbeatScheduler());

        registry.setApplicationDestinationPrefixes(appDestinationPrefix);
        registry.setUserDestinationPrefix(userDestinationPrefix);

        log.info("Message broker configured: destinations={}, heartbeat=[server={}ms, client={}ms], " +
                        "appPrefix={}, userPrefix={}",
                brokerDestinations, heartbeatServer, heartbeatClient,
                appDestinationPrefix, userDestinationPrefix);
    }

    // ═══════════════════════════════════════════════════════════
    // STOMP ENDPOINTS
    // ═══════════════════════════════════════════════════════════

    /**
     * Registers the STOMP WebSocket endpoint with optional SockJS fallback.
     * <p>
     * <b>Endpoint:</b> Configurable via {@code vivumate.websocket.endpoint}
     * (default: {@code /ws-connect}).
     * <ul>
     *   <li>WebSocket URL: {@code ws://host:port/ws-connect}</li>
     *   <li>SockJS URL (if enabled): {@code http://host:port/ws-connect}</li>
     * </ul>
     * <p>
     * <b>SockJS:</b> Controlled by {@code vivumate.websocket.sockjs.enabled}.
     * Enabled in dev for browser testing, disabled in prod where mobile apps
     * use native WebSocket. Disabling in prod removes unnecessary HTTP
     * fallback endpoints and reduces attack surface.
     * <p>
     * <b>CORS:</b> Uses the same allowed origins as the REST API
     * (configured in {@code application.security.cors.allowed-origins}).
     * Production profile restricts to production domains only.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = allowedOrigins.toArray(String[]::new);

        // Primary: native WebSocket (mobile apps, modern browsers)
        registry.addEndpoint(endpoint)
                .setAllowedOrigins(origins);

        // Optional fallback: SockJS for environments where WebSocket is blocked
        if (sockjsEnabled) {
            registry.addEndpoint(endpoint)
                    .setAllowedOrigins(origins)
                    .withSockJS()
                    .setStreamBytesLimit(sockjsStreamBytesLimit)
                    .setHttpMessageCacheSize(sockjsHttpMessageCacheSize)
                    .setDisconnectDelay(sockjsDisconnectDelay);

            log.info("STOMP endpoints registered: {} (WebSocket + SockJS), allowedOrigins={}", endpoint, allowedOrigins);
        } else {
            log.info("STOMP endpoints registered: {} (WebSocket only, SockJS disabled), allowedOrigins={}",
                    endpoint, allowedOrigins);
        }
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
     * Configures WebSocket transport limits from externalized properties.
     * <p>
     * <b>Message size limit</b> ({@code vivumate.websocket.transport.message-size-limit}):
     * 64KB default — sufficient for text messages (max ~4KB) with overhead.
     * Media is uploaded separately via REST API; only the URL is sent via WebSocket.
     * <p>
     * <b>Send buffer size limit</b> ({@code vivumate.websocket.transport.send-buffer-size-limit}):
     * 512KB default — max bytes buffered per session when the client is slow to read.
     * Prevents memory exhaustion from slow consumers.
     * <p>
     * <b>Send time limit</b> ({@code vivumate.websocket.transport.send-time-limit}):
     * 20 seconds default — max time to wait for a slow client to accept a message.
     * After this, the message is dropped and the session may be closed.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(messageSizeLimit)
                .setSendBufferSizeLimit(sendBufferSizeLimit)
                .setSendTimeLimit(sendTimeLimit);

        log.info("Transport limits: messageSizeLimit={}KB, sendBufferSizeLimit={}KB, sendTimeLimit={}ms",
                messageSizeLimit / 1024, sendBufferSizeLimit / 1024, sendTimeLimit);
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
