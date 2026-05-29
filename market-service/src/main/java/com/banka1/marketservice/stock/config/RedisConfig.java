package com.banka1.marketservice.stock.config;

import com.banka1.marketservice.stock.dto.StockPriceSnapshotDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Registruje {@link RedisTemplate} za stock price snapshot cache (L2, cross-replica).
 *
 * <p>Conditional na {@code spring.data.redis.host} — kada env-var nije setovan
 * (lokalni unit testovi, deploy bez Redisa), bean se ne registruje i
 * {@code StockPriceFeedService} fallback-uje na in-process ConcurrentHashMap.
 *
 * <p>Jackson serializer reuse-uje Spring Boot auto-konfigurisani {@code ObjectMapper}
 * (sadrzi JavaTimeModule za {@link java.time.Instant} i BigDecimal handling).
 */
@Configuration
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RedisConfig {

    @Bean
    public RedisTemplate<String, StockPriceSnapshotDto> stockPriceRedisTemplate(
            RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, StockPriceSnapshotDto> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new Jackson2JsonRedisSerializer<>(objectMapper, StockPriceSnapshotDto.class));
        template.afterPropertiesSet();
        return template;
    }
}
