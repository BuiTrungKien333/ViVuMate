package com.vivumate.coreapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis configuration providing two templates for different use cases:
 * <ul>
 *   <li>{@link #redisTemplate} — JSON-serialized values for structured data
 *       (cache, business objects). Uses polymorphic type info restricted to
 *       {@code com.vivumate.coreapi.*} and standard Java types.</li>
 *   <li>{@link #stringRedisTemplate} — Plain string values for lightweight
 *       runtime state: WebSocket routing keys ({@code ws_routing:{userId}}),
 *       rate-limit counters ({@code ws_rate:{userId}}), presence keys, etc.
 *       No JSON overhead, no type metadata — just raw strings and numbers.</li>
 * </ul>
 * <p>
 * <b>Why two templates?</b>
 * WebSocket routing values are simple strings like {@code "local-dev"} or
 * {@code "chat-server-0"}. Using {@code GenericJackson2JsonRedisSerializer}
 * wraps them with {@code @class} metadata, making them harder to inspect
 * in Redis CLI and adding serialization overhead on every heartbeat renewal.
 * {@code StringRedisTemplate} stores them as plain strings — simpler, faster,
 * and easier to debug.
 */
@Configuration
@EnableCaching
@Slf4j
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @PostConstruct
    public void logConnectionInfo() {
        log.info("Redis Connecting to: {}:{}", redisHost, redisPort);
    }

    // ═══════════════════════════════════════════════════════════
    // JSON SERIALIZER (for cache / business objects)
    // ═══════════════════════════════════════════════════════════

    @Bean
    public GenericJackson2JsonRedisSerializer jsonRedisSerializer() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        BasicPolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.vivumate.coreapi")
                .allowIfSubType("java.util")
                .allowIfSubType("java.time")
                .build();

        objectMapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL);

        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }

    // ═══════════════════════════════════════════════════════════
    // CACHE MANAGER
    // ═══════════════════════════════════════════════════════════

    @Bean
    public RedisCacheConfiguration cacheConfiguration(GenericJackson2JsonRedisSerializer jsonRedisSerializer) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(15))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonRedisSerializer));
    }

    @Bean
    public CacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            RedisCacheConfiguration cacheConfiguration
    ) {
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfiguration)
                .transactionAware()
                .build();
    }

    // ═══════════════════════════════════════════════════════════
    // REDIS TEMPLATES
    // ═══════════════════════════════════════════════════════════

    /**
     * General-purpose RedisTemplate with JSON serialization.
     * <p>
     * Used for: Spring Cache, business object storage, any use case
     * where values need polymorphic deserialization.
     * <p>
     * <b>NOT recommended for:</b> WebSocket routing, rate-limit counters,
     * or other runtime state where values are simple strings/numbers.
     * Use {@link #stringRedisTemplate} instead.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
                                                       GenericJackson2JsonRedisSerializer jsonSerializer) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * String-only RedisTemplate for lightweight runtime state.
     * <p>
     * Used for: WebSocket routing keys ({@code ws_routing:{userId}}),
     * rate-limit counters ({@code ws_rate:{userId}}), presence keys,
     * typing indicators, and any key/value where the data is a plain
     * string or number.
     * <p>
     * <b>Advantages over generic RedisTemplate:</b>
     * <ul>
     *   <li>No JSON overhead — {@code "local-dev"} is stored as-is, not
     *       {@code ["java.lang.String","local-dev"]}</li>
     *   <li>Redis CLI friendly — values are human-readable without decoding</li>
     *   <li>Lower latency — no serialization/deserialization cost</li>
     * </ul>
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

}
